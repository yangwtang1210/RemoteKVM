package com.remote.kvm.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import com.remote.kvm.encoder.H265Encoder

/**
 * ScreenCaptureService — 屏幕采集前台服务（静默版）
 *
 * 静默策略：
 * - 通知渠道 LOW 重要性 → 不响铃、不震动、不弹出
 * - 前台通知常驻但无感
 * - 开机自启后自动恢复采集
 */
class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val CHANNEL_ID = "capture_channel"
        const val NOTIFICATION_ID = 1

        // ===== 编码参数 =====
        const val ENCODE_WIDTH = 1920
        const val ENCODE_HEIGHT = 1080
        const val ENCODE_DPI = 320
        const val BITRATE = 15_000_000
        const val FRAME_RATE = 60
        const val I_FRAME_INTERVAL = 1

        var isRunning = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: H265Encoder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 从 Intent 取出授权结果
        @Suppress("DEPRECATION")
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            // 授权失败（常见于开机自启时 MediaProjection 未授权）
            // 静默停止，不打扰用户
            Log.w(TAG, "无授权，静默停止")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture(resultCode, data)

        return START_STICKY  // 被系统杀掉后自动重启
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)

            encoder = H265Encoder(
                ENCODE_WIDTH, ENCODE_HEIGHT,
                BITRATE, FRAME_RATE, I_FRAME_INTERVAL
            ).also {
                it.prepare()
                it.start()
            }

            virtualDisplay = projection?.createVirtualDisplay(
                "RemoteKVM",
                ENCODE_WIDTH, ENCODE_HEIGHT, ENCODE_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder!!.inputSurface,
                null, null
            )

            isRunning = true
            Log.i(TAG, "✓ 采集启动: ${ENCODE_WIDTH}x${ENCODE_HEIGHT} @ ${FRAME_RATE}fps")

        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            isRunning = false
            stopSelf()
        }
    }

    private fun stopCapture() {
        isRunning = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        projection = null
        encoder = null
        Log.i(TAG, "✓ 采集停止")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== 静默通知 =====

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台服务",
            NotificationManager.IMPORTANCE_MIN  // 最低重要性：不响铃、不震动、不在状态栏弹出
        ).apply {
            description = "静默运行"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setSilent(true)        // 无声音
            .setOngoing(true)       // 不可清除
            .setPriority(Notification.PRIORITY_MIN)  // 最低优先级
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
