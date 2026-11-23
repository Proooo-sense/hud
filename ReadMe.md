# 高德车机版地图导航信息投屏 Hud
## 限制
1. 仅适用Android 8以上，仅在Android 11系统上测试过
2. 投屏的信息仅有车道线和基于导航信息，如下图
  ![投屏效果示意图](/参考文档/投屏效果示意图.png "")
## 原理

基于Android 多屏幕开发 （Presentation），如果HUD不是基于这个的话，就无法使用了。

## 开发流程

1. 获取屏幕 Display 数据

```kotlin
private val mDisplayManager by lazy { ContextCompat.getSystemService<DisplayManager>(this, DisplayManager::class.java) }
val displays = mDisplayManager?.getDisplays()
```

2. 获取对应hud的屏幕id，并启动Activity显示在该屏幕上

```kotlin
  fun startActivity(context: Context,launchDisplayId:Int){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O   ) {
                val options = ActivityOptions.makeBasic()
                options.setLaunchDisplayId(launchDisplayId)
                val intent = Intent(context, xx::class.java)
                if (context is Activity){

                }else{
                    intent. addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent, options.toBundle());
            }
     }
```

## 其他
在hud上启动整个Activity 可能会遮住原本的内容，可考虑在hud上显示悬浮窗。Android上显示悬浮窗的没有设置目标显示屏幕的接口。但可先在目标显示屏幕启动一个Activity，然后在这个Activity上显示悬浮窗，接着关闭Activity。

---

## 车机 OTA 之后（Sw:E.0.5.92）的适配

OTA 后固件为 Android 11（`ro.build.version.sdk=30`、`ro.build.display.id=V1.130.92.253-20261105010223`），
HUD 屏分辨率为 **840x640**。两个变化会直接影响本项目：

### 1. 跨屏启动 Activity 被系统拒绝

`ActivityOptions#setLaunchDisplayId` 现在会走 `SafeActivityOptions#checkPermissions` →
`ActivityStackSupervisor#isCallerAllowedToLaunchOnDisplay` 校验，抛出的异常会把进程带崩：

```
java.lang.SecurityException: Permission Denial: starting Intent { cmp=.../.ui.HudDisplayActivity (has extras) }
    from ProcessRecord{...} (pid=..., uid=...) with launchDisplayId=2
    at com.android.server.wm.SafeActivityOptions.checkPermissions(SafeActivityOptions.java:234)
```

放行条件只有三种：调用方持有 `INTERNAL_SYSTEM_WINDOW`（签名权限）、目标屏是 **public**、
或目标屏是 **private/虚拟屏且属于调用方自己**。普通 App 一条都不占。

**新做法**（`utils/HudDisplayHelper.kt`）：不再依赖把 Activity 启动到副屏，
而是给目标屏创建 display Context，直接挂 `TYPE_APPLICATION_OVERLAY` 悬浮窗：

```kotlin
val display = HudDisplayHelper.findHudDisplay(appContext)          // 保存的 id → 840x640 自动匹配
val displayContext = appContext.createDisplayContext(display)      // 绑定到 HUD 屏
AmapFloatManager.showFloat(displayContext)                         // 只依赖 SYSTEM_ALERT_WINDOW
```

### 2. HUD 屏可能"消失"

OTA 之后经常出现 `DisplayManager` 里只剩主屏 + 中控屏（都是 1920x1080）、HUD 那块 840x640 的屏
根本不存在的情况。此时任何 App 都画不上去（`launchDisplayId` 会报 `display not found`，
悬浮窗也没地方挂）。

已做的处理：

- `DisplayManager.registerDisplayListener` 常驻监听：HUD 屏一旦出现，自动重试投屏；
- 屏幕列表、屏幕事件（接入/移除）写到日志与 `filesDir/hud_display_events.log`；
- 测试页「获取屏幕数据」= 完整诊断（屏幕列表含 type/ownerUid/flags + HUD 相关应用清单 + 事件日志），并复制到剪贴板；
- 测试页「扫描HUD应用」= 列出/启动包名含 hud/cluster/instrument 的应用，用来"叫醒"厂商 HUD 服务；
- 测试页「本机预览」= 把同一套 HUD 悬浮窗显示在中控屏上，用来验证导航数据链路（HUD 屏不在时也能开发调试）。

如果列表里始终没有 840x640 的屏，需要从车机侧确认：HUD 显示开关/显示模式，
或让厂商确认新固件里 HUD 屏的创建条件（是否只对自家 HUD 服务开放）。
