package com.fkdeepal.tools.ext.utils

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import com.fkdeepal.tools.ext.AmapFloatManager
import com.fkdeepal.tools.ext.exts.toast
import com.fkdeepal.tools.ext.manager.UserDataManager
import com.fkdeepal.tools.ext.ui.HudDisplayActivity
import timber.log.Timber

/**
 * HUD 副屏查找 + 投屏 + 诊断工具。
 *
 * 背景（2026-11 车机 OTA，Sw:E.0.5.92 / Android 11 / API 30）：
 *
 * 1) 以前"把 Activity 启动到副屏 + 在它上面挂悬浮窗"的路子会被系统安全校验拒绝：
 *
 *      java.lang.SecurityException: Permission Denial: starting Intent { ... }
 *          from ProcessRecord{...} with launchDisplayId=2
 *          at com.android.server.wm.SafeActivityOptions.checkPermissions(SafeActivityOptions.java:234)
 *
 *    放行条件只有：调用方有 INTERNAL_SYSTEM_WINDOW（签名权限）、目标屏是 public、
 *    或目标屏是 private/虚拟屏且属于自己。第三方 App 一条都不占。
 *    所以这里保留旧方案但**必须 try/catch**，失败就降级。
 *
 * 2) 新方案：`appContext.createDisplayContext(display)` 拿到绑定到目标屏的 Context，
 *    再用它的 WindowManager 添加 TYPE_APPLICATION_OVERLAY 悬浮窗 —— 只依赖 SYSTEM_ALERT_WINDOW。
 *
 * 3) OTA 之后 HUD 屏经常会"消失"（DisplayManager 里只剩两块 1920x1080 的屏）。
 *    说明系统当前没创建/没开放那块屏，App 侧只能：等它出现（DisplayListener）、
 *    以及把屏幕列表 + HUD 相关应用清单打到日志里，方便排查/找厂商。
 */
object HudDisplayHelper {

    private const val TAG = "HudDisplayHelper"

    /** 已知 HUD 分辨率：OTA 后 840x640，老固件 800x480 */
    private val KNOWN_HUD_SIZES = listOf(
        840 to 640, 640 to 840,
        800 to 480, 480 to 800
    )

    /** 用来猜"哪个应用负责 HUD" */
    private val HUD_APP_KEYWORDS = listOf(
        "hud", "arhud", "cluster", "instrument", "headup", "head_up"
    )

    private var sWatching = false
    private var sLastAttemptAt = 0L
    private var sWatchHandler: Handler? = null

    /** 最近一次启动结果，给测试页展示 */
    @Volatile
    var lastResult: String = ""
        private set

    // ===================== 屏幕信息 =====================

    @JvmStatic
    fun listDisplays(context: Context): List<Display> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return try {
            manager.displays.toList()
        } catch (t: Throwable) {
            Timber.e(t, "$TAG 读取屏幕列表失败")
            emptyList()
        }
    }

    /** 单块屏幕的文字描述（带反射拿到的 type / ownerUid，便于判断 private/virtual） */
    @JvmStatic
    fun describe(display: Display): String {
        return try {
            val metrics = DisplayMetrics()
            display.getMetrics(metrics)
            val flags = flagText(display.flags)
            "#${display.displayId} '${display.name}' ${display.width}x${display.height}" +
                    " ${display.refreshRate}Hz state=${stateName(display.state)} rot=${display.rotation}" +
                    " dpi=${metrics.densityDpi} type=${displayType(display)}" +
                    " ownerUid=${displayOwnerUid(display)}" +
                    if (flags.isEmpty()) "" else " flags=$flags"
        } catch (t: Throwable) {
            "#${display.displayId} （读取失败: ${t.message}）"
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> "UNKNOWN($state)"
    }

    private fun flagText(flags: Int): String {
        if (flags == 0) return ""
        val sb = StringBuilder()
        if (flags and Display.FLAG_PRIVATE != 0) sb.append("PRIVATE,")
        if (flags and Display.FLAG_PRESENTATION != 0) sb.append("PRESENTATION,")
        if (flags and Display.FLAG_SECURE != 0) sb.append("SECURE,")
        if (flags and Display.FLAG_ROUND != 0) sb.append("ROUND,")
        if (sb.isEmpty()) return "0x" + Integer.toHexString(flags)
        return sb.toString().trimEnd(',')
    }

    private fun displayType(display: Display): String = runCatching {
        Display::class.java.getMethod("getType").invoke(display).toString()
    }.getOrDefault("?")

    private fun displayOwnerUid(display: Display): String = runCatching {
        Display::class.java.getMethod("getOwnerUid").invoke(display).toString()
    }.getOrDefault("?")

    private fun Display.isUsable(): Boolean = state != Display.STATE_OFF

    /**
     * 精简版屏幕列表（启动/回前台时打日志用，不做应用扫描，避免卡顿）
     */
    @JvmStatic
    fun displayBrief(context: Context): String {
        val displays = listDisplays(context)
        val sb = StringBuilder("共 ${displays.size} 块屏")
        displays.forEach { sb.append('\n').append(describe(it)) }
        return sb.toString()
    }

    /**
     * 完整诊断报告：屏幕列表 + HUD 相关应用 + 结论提示。
     * 测试页"获取屏幕数据"按钮、App 启动、开机启动都会调用它写日志。
     */
    @JvmStatic
    fun displayReport(context: Context): String {
        val displays = listDisplays(context)
        val sb = StringBuilder()
        sb.append("=== 屏幕列表(").append(displays.size).append("块) ===\n")
        if (displays.isEmpty()) {
            sb.append("（DisplayManager 返回空）\n")
        } else {
            displays.forEach { sb.append(describe(it)).append('\n') }
        }

        sb.append("\n=== HUD 相关应用 ===\n")
        val apps = candidateHudApps(context)
        if (apps.isEmpty()) {
            sb.append("未发现包名含 hud/cluster/instrument 的应用\n")
        } else {
            apps.forEach { sb.append(it).append('\n') }
        }

        sb.append("\n=== 结论 ===\n")
        val hud = findHudDisplay(context)
        if (hud == null) {
            sb.append("当前没有可用于投屏的 HUD 屏：\n")
            sb.append("- 只有 1920x1080 之类的主屏/中控屏时，说明系统此刻没有创建 HUD 显示，\n")
            sb.append("  App 无论用 launchDisplayId 还是悬浮窗都画不上去；\n")
            sb.append("- OTA 后 HUD 屏多由厂商 HUD 服务按需创建，或改成 private 虚拟屏（只对创建者可见）；\n")
            sb.append("- 下一步：确认车机设置里的 HUD 开关/显示模式，或让厂商确认 HUD 屏的创建条件。\n")
        } else {
            sb.append("找到 HUD 屏: ").append(describe(hud)).append('\n')
        }
        if (lastResult.isNotEmpty()) {
            sb.append("\n最近一次启动结果: ").append(lastResult).append('\n')
        }

        sb.append("\n=== 屏幕事件日志(最近) ===\n")
        val events = readEventLog(context).lines()
        if (events.size > 25) {
            sb.append(events.subList(events.size - 25, events.size).joinToString("\n"))
        } else {
            sb.append(readEventLog(context))
        }
        sb.append('\n')

        return sb.toString()
    }

    /** 包名里带 hud/cluster/instrument 的应用（含是否启用、是否有启动入口） */
    @JvmStatic
    fun candidateHudApps(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            pm.getInstalledPackages(0)
                .asSequence()
                .filter { pkg -> HUD_APP_KEYWORDS.any { pkg.packageName.contains(it, ignoreCase = true) } }
                .map { pkg ->
                    val launchable = pm.getLaunchIntentForPackage(pkg.packageName) != null
                    "${pkg.packageName} version=${pkg.versionName} enabled=${pkg.applicationInfo?.enabled == true} launchable=$launchable"
                }
                .sorted()
                .toList()
        } catch (t: Throwable) {
            Timber.e(t, "$TAG 扫描 HUD 相关应用失败")
            emptyList()
        }
    }

    /** 依次启动上一步扫到的应用，返回结果文本（用来"叫醒"厂商 HUD 服务，看屏幕会不会出现） */
    @JvmStatic
    fun launchCandidateHudApps(context: Context): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        candidateHudApps(context).forEach { line ->
            val pkg = line.substringBefore(' ')
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                sb.append("$pkg: 没有启动入口\n")
            } else {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    sb.append("$pkg: 已启动\n")
                } catch (t: Throwable) {
                    sb.append("$pkg: 启动失败 ${t.javaClass.simpleName}\n")
                }
            }
        }
        if (sb.isEmpty()) sb.append("没有可启动的 HUD 相关应用\n")
        return sb.toString()
    }

    @JvmStatic
    fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(ClipData.newPlainText("hud_report", text))
        }
    }

    // ===================== 屏幕事件日志（不依赖 debug 模式） =====================

    private const val EVENT_LOG = "hud_display_events.log"

    /** 记录一次屏幕事件到 filesDir/hud_display_events.log（开车过程中也能留下证据） */
    @JvmStatic
    fun appendEventLog(context: Context, text: String) {
        Timber.i("$TAG $text")
        runCatching {
            val file = java.io.File(context.filesDir, EVENT_LOG)
            if (file.length() > 512 * 1024) file.delete()
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$time $text\n")
        }
    }

    /** 读取屏幕事件日志（测试页展示） */
    @JvmStatic
    fun readEventLog(context: Context): String {
        return runCatching {
            val file = java.io.File(context.filesDir, EVENT_LOG)
            if (!file.exists()) "（还没有屏幕事件记录）" else file.readText()
        }.getOrDefault("（读取失败）")
    }

    // ===================== 找屏 =====================

    @JvmStatic
    fun findHudDisplay(context: Context): Display? {
        val displays = listDisplays(context)
        if (displays.isEmpty()) return null

        // 1) 设置页里手动指定的屏幕（包括手动指到中控屏，属于用户明确选择）
        val savedId = runCatching { UserDataManager.getHudDisplayId() }.getOrNull()
        if (savedId != null && savedId != Display.DEFAULT_DISPLAY) {
            val saved = displays.firstOrNull { it.displayId == savedId }
            if (saved != null && saved.isUsable()) return saved
            Timber.w("$TAG 设置里保存的屏幕 id=$savedId 已不存在或不可用，改为自动匹配")
        }

        // 2) 按 HUD 分辨率自动识别（OTA 后 840x640）
        displays.firstOrNull { d ->
            d.isUsable() && KNOWN_HUD_SIZES.any { it.first == d.width && it.second == d.height }
        }?.let {
            Timber.i("$TAG 按分辨率匹配到 HUD 屏: ${describe(it)}")
            return it
        }

        // 3) 找不到就返回 null（不再瞎猜副屏，否则会把 HUD 画到中控屏上）
        Timber.w("$TAG 没找到 HUD 屏，当前:\n${displayBrief(context)}")
        return null
    }

    // ===================== 启动 HUD =====================

    /**
     * 启动 HUD（主线程调用）。
     * 顺序：旧方案（老固件兼容）→ 新方案（displayContext 悬浮窗）。
     * @return true 表示已经显示
     */
    @JvmStatic
    fun startHud(context: Context, isAutoStart: Boolean = false): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { startHud(context, isAutoStart) }
            lastResult = "已切回主线程执行"
            return false
        }

        val appContext = context.applicationContext ?: context

        if (AmapFloatManager.isShowing()) {
            lastResult = "HUD 已在显示"
            return true
        }
        if (!Settings.canDrawOverlays(appContext)) {
            lastResult = "没有悬浮窗权限"
            Timber.w("$TAG 没有悬浮窗权限，无法显示 HUD")
            return false
        }

        sLastAttemptAt = System.currentTimeMillis()

        val display = findHudDisplay(appContext)
        if (display == null) {
            lastResult = "没有找到 HUD 屏（当前 ${listDisplays(appContext).size} 块屏）"
            appendEventLog(appContext, "启动HUD失败：$lastResult")
            Timber.w("$TAG $lastResult\n${displayReport(appContext)}")
            startWatching(appContext)
            return false
        }

        appendEventLog(appContext, "启动HUD，目标屏 ${describe(display)}")
        Timber.i("$TAG 目标 HUD 屏: ${describe(display)}")

        if (startLegacyHudActivity(context, display.displayId, isAutoStart)) {
            lastResult = "旧方案启动成功 displayId=${display.displayId}"
            return true
        }

        val ok = showOverlayOnDisplay(appContext, display)
        lastResult = if (ok) {
            "悬浮窗启动成功 displayId=${display.displayId}"
        } else {
            "悬浮窗启动失败 displayId=${display.displayId}（详见日志）"
        }
        return ok
    }

    /** 旧方案：跨屏启动 Activity。新固件会抛 SecurityException，必须 catch */
    private fun startLegacyHudActivity(context: Context, displayId: Int, isAutoStart: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)

            val intent = Intent(context, HudDisplayActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("type", 1)
            intent.putExtra("is_auto_start", isAutoStart)

            context.startActivity(intent, options.toBundle())
            Timber.i("$TAG 旧方案启动成功（launchDisplayId=$displayId）")
            true
        } catch (t: Throwable) {
            Timber.w("$TAG 旧方案不可用（launchDisplayId=$displayId）：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 新方案：用目标屏的 Context 挂悬浮窗 */
    @JvmStatic
    fun showOverlayOnDisplay(context: Context, display: Display): Boolean {
        return try {
            val displayContext = context.createDisplayContext(display)
            val ok = AmapFloatManager.showFloat(displayContext)
            if (ok) {
                Timber.i("$TAG 已在 ${describe(display)} 上添加 HUD 悬浮窗")
            } else {
                Timber.e("$TAG 在 ${describe(display)} 上添加 HUD 悬浮窗失败")
                startWatching(context)
            }
            ok
        } catch (t: Throwable) {
            Timber.e(t, "$TAG 创建副屏 Context 失败: ${describe(display)}")
            startWatching(context)
            false
        }
    }

    /** 调试用：把同一套 HUD 悬浮窗显示在默认屏幕上（HUD 屏不在时用来验证导航数据链路） */
    @JvmStatic
    fun showPreviewOnDefaultDisplay(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        return try {
            val ok = AmapFloatManager.showFloat(appContext)
            lastResult = if (ok) "本机预览已显示" else "本机预览失败"
            ok
        } catch (t: Throwable) {
            Timber.e(t, "$TAG 本机预览失败")
            lastResult = "本机预览失败: ${t.javaClass.simpleName}"
            false
        }
    }

    // ===================== 屏幕监听 =====================

    @JvmStatic
    fun isWatching(): Boolean = sWatching

    /** HUD 屏晚出现（或被系统重建）时自动重试 */
    @JvmStatic
    fun startWatching(context: Context) {
        if (sWatching) return
        val appContext = context.applicationContext ?: context
        val manager = appContext.getSystemService(DisplayManager::class.java) ?: return
        val handler = Handler(Looper.getMainLooper())
        sWatchHandler = handler

        manager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                appendEventLog(appContext, "屏幕接入 displayId=$displayId，共 ${listDisplays(appContext).size} 块屏")
                Timber.i("$TAG 屏幕列表:\n${displayReport(appContext)}")
                if (AmapFloatManager.isShowing()) return
                handler.postDelayed({
                    if (!AmapFloatManager.isShowing() &&
                        System.currentTimeMillis() - sLastAttemptAt > RETRY_INTERVAL_MS
                    ) {
                        appendEventLog(appContext, "屏幕接入后自动重试启动 HUD")
                        startHud(appContext, true)
                    }
                }, 1500)
            }

            override fun onDisplayRemoved(displayId: Int) {
                appendEventLog(appContext, "屏幕移除 displayId=$displayId，剩 ${listDisplays(appContext).size} 块屏")
                if (!AmapFloatManager.isShowing()) return
                AmapFloatManager.hideHudFloat()
            }

            override fun onDisplayChanged(displayId: Int) {
                Timber.d("$TAG 屏幕变化 displayId=$displayId")
            }
        }, handler)

        sWatching = true
        Timber.i("$TAG 已开始监听屏幕变化")
    }

    private const val RETRY_INTERVAL_MS = 5000L
}
