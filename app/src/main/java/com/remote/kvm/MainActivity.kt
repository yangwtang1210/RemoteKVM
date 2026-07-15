package com.remote.kvm

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remote.kvm.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val PREFS_NAME = "kvm_prefs"
        const val KEY_SERVER_IP = "server_ip"
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleButton: Button
    private lateinit var serverInput: EditText
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        toggleButton = findViewById(R.id.toggleButton)
        serverInput = findViewById(R.id.serverInput)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_SERVER_IP, "")
        if (!savedIp.isNullOrEmpty()) {
            serverInput.setText(savedIp)
        }

        toggleButton.setOnClickListener {
            if (isCapturing) stopCapture() else preCheck()
        }

        findViewById<Button>(R.id.saveIpButton).setOnClickListener {
            val ip = serverInput.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_SERVER_IP, ip).apply()
            Toast.makeText(this, "已保存: $ip", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.hideIconBtn).setOnClickListener {
            hideLauncherIcon()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }

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
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                return
            }
        }

        val ip = serverInput.text.toString().trim()
        if (ip.isEmpty()) {
            log("请先输入服务器地址并保存")
            return
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_SERVER_IP, ip).apply()
        ScreenCaptureService.SERVER_IP = ip

        Toast.makeText(this, "IP: $ip，准备采集", Toast.LENGTH_SHORT).show()
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        @Suppress("DEPRECATION")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val ip = serverInput.text.toString().trim()
                val svcIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                startForegroundService(svcIntent)
                isCapturing = true

                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(BootReceiver.KEY_ENABLED, true).apply()

                log("采集已启动，目标: $ip")
                Toast.makeText(this, "采集启动，正在连接 $ip ...", Toast.LENGTH_LONG).show()
                updateUI()
            } else {
                Toast.makeText(this, "未授权屏幕采集", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(BootReceiver.KEY_ENABLED, false).apply()
        isCapturing = false
        log("已停止")
        updateUI()
    }

    private fun updateUI() {
        if (isCapturing) {
            statusText.text = "采集中"
            statusText.setTextColor(0xFF00E676.toInt())
            toggleButton.text = "停止采集"
        } else {
            statusText.text = "就绪"
            statusText.setTextColor(0xFFFFFFFF.toInt())
            toggleButton.text = "开始采集"
        }
    }

    private fun hideLauncherIcon() {
        val comp = ComponentName(this, MainActivity::class.java)
        packageManager.setComponentEnabledSetting(
            comp,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Toast.makeText(
            this,
            "图标已隐藏\n需恢复时执行:\nadb shell pm enable com.remote.kvm/com.remote.kvm.MainActivity",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun log(msg: String) {
        logText.text = msg
    }
}
