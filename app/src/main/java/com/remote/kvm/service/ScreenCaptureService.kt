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
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.remote.kvm.encoder.H265Encoder

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val CHANNEL_ID = "capture_channel"
        const val NOTIFICATION_ID = 1

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
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "无授权，静默停止")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture(resultCode, data)

        return START_STICKY
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

            val inputSurface = encoder!!.getInputSurface()
            if (inputSurface == null) {
                Log.e(TAG, "获取 InputSurface 失败")
                stopSelf()
                return
            }

            virtualDisplay = projection?.createVirtualDisplay(
                "RemoteKVM",
                ENCODE_WIDTH, ENCODE_HEIGHT, ENCODE_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台服务",
            NotificationManager.IMPORTANCE_MIN
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
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (Build.VERSION.SDK_INT >= 26) {
            // API 26+ 通知已经在 channel 层面设为 MIN，不需要额外 setSilent
        } else {
            @Suppress("DEPRECATION")
            builder.setSound(null)
        }

        return builder.build()
    }
}
