package com.fkdeepal.tools.ext.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.fkdeepal.tools.ext.service.HudWatchService
import com.fkdeepal.tools.ext.utils.HudDisplayHelper
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("开机", "BootReceiver receiver")
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.i("开机启动")
        val appContext = context.applicationContext ?: context
        if (!Settings.canDrawOverlays(appContext)) {
            Timber.w("开机启动,没有悬浮窗权限")
            return
        }
        Timber.i("开机启动,canDrawOverlays")

        // 先打印屏幕列表（开机阶段 HUD 屏通常还没就绪），并开始监听屏幕变化
        Timber.i("开机启动,屏幕列表:\n${HudDisplayHelper.displayBrief(appContext)}")
        HudDisplayHelper.appendEventLog(appContext, "开机启动，${HudDisplayHelper.displayBrief(appContext)}")
        HudDisplayHelper.startWatching(appContext)

        // 前台服务保活：进程被回收后 DisplayListener 还在，HUD 屏出现时能自动接上
        HudWatchService.start(appContext)

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                Timber.i("开机启动,准备启动HUD")
                val ok = HudDisplayHelper.startHud(appContext, true)
                Timber.i("开机启动,startHud 结果: $ok / ${HudDisplayHelper.lastResult}")
            }.onFailure {
                Timber.e(it, "开机启动,启动 HUD 失败")
            }
        }, 2000)
    }
}
