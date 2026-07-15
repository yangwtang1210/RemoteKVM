package com.remote.kvm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.remote.kvm.service.ScreenCaptureService

/**
 * BootReceiver — 开机自启
 *
 * 手机重启后自动启动屏幕采集服务。
 * 注意：Android 10+ 的 MediaProjection 授权不会跨重启保留，
 * 如果服务启动后采集失败，需要通过 adb 重新打开 MainActivity 授权一次。
 *
 * Root 手机可以用以下命令静默授权（不需要手动操作）：
 *   adb shell su -c "appops set com.remote.kvm PROJECT_MEDIA allow"
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
        const val PREFS_NAME = "kvm_prefs"
        const val KEY_ENABLED = "capture_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "收到广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_QUICKBOOT_POWERON,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {

                // 检查用户是否已经完成过初始设置
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean(KEY_ENABLED, false)

                if (enabled) {
                    Log.i(TAG, "开机自启：启动采集服务")
                    val svcIntent = Intent(context, ScreenCaptureService::class.java)
                    try {
                        context.startForegroundService(svcIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动服务失败", e)
                    }
                } else {
                    Log.i(TAG, "尚未完成初始设置，跳过自启")
                }
            }
        }
    }
}
