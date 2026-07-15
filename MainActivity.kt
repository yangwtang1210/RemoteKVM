package com.remote.kvm

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remote.kvm.service.ScreenCaptureService

/**
 * MainActivity — 被控端主界面（仅首次设置时使用）
 *
 * 这个界面只在两种情况下出现：
 * 1. 安装后首次通过 adb 启动，完成初始授权
 * 2. 重启后 MediaProjection 授权丢失，需要重新授权
 *
 * 设置完成后：
 * - 桌面无图标
 * - 最近任务不显示
 * - 开机自动启动采集服务
 *
 * 启动命令：
 *   adb shell am start -n com.remote.kvm/.MainActivity
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_NOTIFICATION = 1002
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleButton: Button
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener {
            if (isCapturing) {
                stopCapture()
            } else {
                preCheck()
            }
        }

        requestNotificationPermission()
        syncState()
    }

    override fun onResume() {
        super.onResume()
        syncState()
    }

    private fun syncState() {
        isCapturing = ScreenCaptureService.isRunning
        updateUI()
    }

    private fun preCheck() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                log("需要通知权限，请授权后重试")
                requestNotificationPermission()
                return
            }
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val svcIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                startForegroundService(svcIntent)
                isCapturing = true

                // 保存状态：标记已完成初始设置，后续开机自动启动
                getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(BootReceiver.KEY_ENABLED, true)
                    .apply()

                log("✓ 采集启动，以后开机自动运行")
                updateUI()

                // 3 秒后自动关闭界面（完全静默）
                toggleButton.postDelayed({
                    finish()  // 关闭 Activity，不留痕迹
                }, 3000)

            } else {
                Toast.makeText(this, "未授权屏幕采集", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))

        // 清除自启标记
        getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_ENABLED, false)
            .apply()

        isCapturing = false
        log("已停止，开机不再自动启动")
        updateUI()
    }

    private fun updateUI() {
        if (isCapturing) {
            statusText.text = "● 采集中"
            statusText.setTextColor(0xFF00E676.toInt())
            toggleButton.text = "停止采集"
        } else {
            statusText.text = "○ 就绪"
            statusText.setTextColor(0xFFFFFFFF.toInt())
            toggleButton.text = "开始采集"
        }
    }

    private fun log(msg: String) {
        logText.text = msg
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION
            )
        }
    }
}
