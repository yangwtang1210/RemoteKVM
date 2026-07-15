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
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "RemoteKVM"
        const val CHANNEL_ID = "capture_channel"
        const val NOTIFICATION_ID = 1
        const val ENCODE_WIDTH = 1080
        const val ENCODE_HEIGHT = 2400
        const val ENCODE_DPI = 320
        const val BITRATE = 8_000_000
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL = 2
        // 改成你服务器的公网 IP
        var SERVER_IP = "YOUR_SERVER_IP"

        var isRunning = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: H265Encoder? = null
    private var wsClient: WebSocketClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "无授权，静默停止")
            stopSelf()
            return START_NOT_STICKY
        }

        // 从 SharedPreferences 读取服务器 IP
        val prefs = getSharedPreferences("kvm_prefs", MODE_PRIVATE)
        SERVER_IP = prefs.getString("server_ip", SERVER_IP) ?: SERVER_IP

        startForeground(NOTIFICATION_ID, buildNotification())
        connectWebSocket()
        startCapture(resultCode, data)
        return START_STICKY
    }

    // ==================== WebSocket 连接 ====================

    private fun connectWebSocket() {
        try {
            val uri = URI("ws://$SERVER_IP:8765/?role=phone")
            wsClient = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.i(TAG, "WebSocket 已连接: $SERVER_IP")
                }

                override fun onMessage(message: String?) {
                    // 收到浏览器发来的控制指令
                    message?.let { handleControlMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "WebSocket 断开: $code, 3秒后重连")
                    Thread.sleep(3000)
                    if (isRunning) connectWebSocket()
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket 错误", ex)
                }
            }
            wsClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket 连接失败", e)
        }
    }

    private fun handleControlMessage(msg: String) {
        try {
            val json = JSONObject(msg)
            val type = json.getString("type")

            when (type) {
                "touch" -> {
                    val action = json.getString("action")
                    val x = json.optInt("x", 0)
                    val y = json.optInt("y", 0)
                    injectTouch(action, x, y)
                }
                "key" -> {
                    val action = json.getString("action")
                    val code = json.getInt("code")
                    injectKey(action, code)
                }
                "text" -> {
                    val text = json.getString("text")
                    injectText(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析控制指令失败: $msg", e)
        }
    }

    // ==================== 触控/按键注入（需要 root）====================

    private fun injectTouch(action: String, x: Int, y: Int) {
        when (action) {
            "down" -> {
                // 按下：记录起始点
                touchStartX = x
                touchStartY = y
                runRoot("input touchscreen motionevent DOWN $x $y")
            }
            "move" -> {
                runRoot("input touchscreen motionevent MOVE $x $y")
            }
            "up" -> {
                runRoot("input touchscreen motionevent UP $touchStartX $touchStartY")
            }
        }
    }

    private var touchStartX = 0
    private var touchStartY = 0

    private fun injectKey(action: String, code: Int) {
        if (action == "down") {
            runRoot("input keyevent $code")
        }
    }

    private fun injectText(text: String) {
        // 对特殊字符转义
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        runRoot("input text '$escaped'")
    }

    private fun runRoot(cmd: String) {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                process.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "root 执行失败: $cmd", e)
            }
        }.start()
    }

    // ==================== 视频采集 ====================

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)

            encoder = H265Encoder(
                ENCODE_WIDTH, ENCODE_HEIGHT,
                BITRATE, FRAME_RATE, I_FRAME_INTERVAL
            ).also {
                it.prepare()
                // SPS/PPS 用特殊标记发给浏览器
                it.onSpsAvailable = { sps -> sendNalu(1, sps) }
                it.onPpsAvailable = { pps -> sendNalu(2, pps) }
                it.onFrameAvailable = { _, data, _ -> sendFrame(data) }
            }

            val inputSurface = encoder!!.getInputSurface()
            if (inputSurface == null) {
                Log.e(TAG, "获取 InputSurface 失败")
                stopSelf()
                return
            }

            encoder!!.start()

            virtualDisplay = projection?.createVirtualDisplay(
                "RemoteKVM",
                ENCODE_WIDTH, ENCODE_HEIGHT, ENCODE_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null
            )

            isRunning = true
            Log.i(TAG, "采集启动: ${ENCODE_WIDTH}x${ENCODE_HEIGHT} @ ${FRAME_RATE}fps")
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            isRunning = false
            stopSelf()
        }
    }

    // ==================== 数据发送 ====================

    private fun sendNalu(type: Int, data: ByteArray) {
        // 协议：[1字节类型][NALU数据]
        val buf = ByteBuffer.allocate(1 + data.size)
        buf.put(type.toByte())
        buf.put(data)
        wsClient?.send(buf.array())
    }

    private fun sendFrame(data: ByteArray) {
        if (wsClient?.isOpen != true) return
        // 协议：[0x03][帧数据]
        val buf = ByteBuffer.allocate(1 + data.size)
        buf.put(3.toByte())
        buf.put(data)
        wsClient?.send(buf.array())
    }

    private fun stopCapture() {
        isRunning = false
        try { wsClient?.close() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        projection = null
        encoder = null
        wsClient = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "后台服务", NotificationManager.IMPORTANCE_MIN
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
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }
    }
}
