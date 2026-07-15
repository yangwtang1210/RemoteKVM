package com.remote.kvm.service

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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
        var SERVER_IP: String = ""
        var isRunning = false
            private set
        private const val CHANNEL_ID = "kvm_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RemoteKVM"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var webSocket: WebSocket? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand, isRunning=$isRunning")
        if (isRunning) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("正在初始化"))
        isRunning = true

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")

        Log.d(TAG, "resultCode=$resultCode, data=${data != null}")

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Missing resultCode or data, stopping")
            showToast("启动参数错误，停止采集")
            stopSelf()
            return START_NOT_STICKY
        }

        initWebSocket()
        startCapture(resultCode, data)
        return START_STICKY
    }

    private fun initWebSocket() {
        if (SERVER_IP.isEmpty()) {
            val prefs = getSharedPreferences("kvm_prefs", MODE_PRIVATE)
            SERVER_IP = prefs.getString("server_ip", "") ?: ""
        }
        Log.d(TAG, "SERVER_IP='$SERVER_IP'")

        if (SERVER_IP.isEmpty()) {
            updateNotification("未配置服务器地址")
            showToast("未配置服务器地址")
            isRunning = false
            stopSelf()
            return
        }

        val url = if (SERVER_IP.startsWith("ws://") || SERVER_IP.startsWith("wss://"))
            "$SERVER_IP/ws"
        else
            "ws://$SERVER_IP:8765/ws"

        Log.d(TAG, "Connecting to: $url")
        updateNotification("正在连接 $SERVER_IP ...")
        showToast("正在连接 $url")

        val client = OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected!")
                updateNotification("已连接 → $SERVER_IP")
                showToast("已连接到 $SERVER_IP")
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {}

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
                stopCapture()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed: ${t.message}")
                updateNotification("连接失败: ${t.message}")
                showToast("连接失败: ${t.message}")
                isRunning = false
                stopCapture()
            }
        })
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCapture")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null")
            showToast("屏幕采集权限获取失败")
            isRunning = false
            stopSelf()
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "KVM", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, null
        )

        val imageReader = android.media.ImageReader.newInstance(
            w, h, android.graphics.PixelFormat.RGBA_8888, 2
        )
        virtualDisplay?.surface = imageReader.surface

        running = true
        readerThread = Thread {
            var seq = 0L
            while (running) {
                try {
                    val image = imageReader.acquireLatestImage()
                    if (image == null) {
                        Thread.sleep(33)
                    } else {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w

                        val bitmap = android.graphics.Bitmap.createBitmap(
                            w + rowPadding / pixelStride, h,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buf)

                        if (rowPadding > 0) {
                            val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, w, h)
                            bitmap.recycle()
                            sendBitmap(cropped, seq++)
                            cropped.recycle()
                        } else {
                            sendBitmap(bitmap, seq++)
                            bitmap.recycle()
                        }
                        image.close()
                    }
                } catch (_: Exception) {}
            }
        }.apply { start() }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
            }
        }, null)
    }

    private fun sendBitmap(bmp: android.graphics.Bitmap, seq: Long) {
        val ws = webSocket ?: return
        try {
            val quality = 25
            val stream = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
            val jpegBytes = stream.toByteArray()

            val header = ByteBuffer.allocate(12)
            header.putLong(seq)
            header.putInt(jpegBytes.size)
            header.flip()

            val payload = ByteBuffer.allocate(12 + jpegBytes.size)
            payload.put(header)
            payload.put(jpegBytes)

            ws.send(ByteString.of(*payload.array()))
        } catch (_: Exception) {}
    }

    private fun stopCapture() {
        running = false
        readerThread?.join(1000)
        readerThread = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        webSocket?.close(1000, "stop")
        webSocket = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "KVM", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RemoteKVM").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_send).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RemoteKVM").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_send).build()
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
