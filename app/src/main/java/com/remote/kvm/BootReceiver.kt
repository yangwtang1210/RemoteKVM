package com.remote.kvm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.remote.kvm.service.ScreenCaptureService

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "BootReceiver"
        const val PREFS_NAME = "kvm_prefs"
        const val KEY_ENABLED = "capture_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "收到广播: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED
            || action == "android.intent.action.QUICKBOOT_POWERON"
            || action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_ENABLED, false)

            if (enabled) {
                Log.i(TAG, "开机自启：启动采集服务")
                try {
                    val svcIntent = Intent(context, ScreenCaptureService::class.java)
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
