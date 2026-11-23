package com.fkdeepal.tools.ext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.fkdeepal.tools.ext.utils.HudDisplayHelper
import timber.log.Timber

/**
 * 常驻的 HUD 监视服务。
 *
 * 存在的意义：HUD 屏在 OTA 后经常是"晚到"的（开机后很久才出现，或者被系统 HUD 服务重建），
 * 而 App 进程被回收后 DisplayListener 也就没了。用前台服务保证：
 * 1) 进程常驻，DisplayListener 一直有效；
 * 2) HUD 屏一出现就自动投屏（HudDisplayHelper 内部会处理）。
 *
 * 注意：会有一条常驻通知（这是前台服务的要求）。
 */
class HudWatchService : Service() {

    companion object {
        private const val CHANNEL_ID = "hud_watch"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, HudWatchService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Timber.i("HudWatchService 已启动")
            }.onFailure {
                Timber.e(it, "HudWatchService 启动失败")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HudWatchService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Timber.e(it, "startForeground 失败") }
        HudDisplayHelper.startWatching(this)
        Timber.i("HudWatchService.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HudDisplayHelper.startWatching(this)
        HudDisplayHelper.startHud(this, false)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HUD 投屏",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("HUD 投屏助手")
            .setContentText("正在等待 HUD 屏幕 / 保持投屏")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
