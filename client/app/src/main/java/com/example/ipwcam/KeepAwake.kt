package com.example.ipwcam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager

/**
 * 長時間配信用のスリープ防止。
 *  - 画面の自動消灯を無効化 (FLAG_KEEP_SCREEN_ON)
 *  - CPUスリープ防止 (PARTIAL_WAKE_LOCK)
 *  - Wi-Fiの省電力停止 (WifiLock)
 * acquire() と release() は必ず対で呼ぶこと。
 */
class KeepAwake(private val activity: Activity) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var dimmed = false

    fun acquire() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = activity.applicationContext
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipwcam:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "ipwcam:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        try { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
    }

    /** 画面を最低輝度にする/戻す(焼き付き・発熱・電池対策。プレビューをタップで切替) */
    fun toggleDim(): Boolean {
        dimmed = !dimmed
        val lp = activity.window.attributes
        lp.screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
        return dimmed
    }

    companion object {
        /**
         * 電池最適化の除外を一度だけ案内する。
         * (除外しないと、長時間の放置でバックグラウンド制限を受ける機種がある)
         */
        fun promptIgnoreBatteryOptimizationsOnce(activity: Activity) {
            try {
                if (StreamSettings.wasBatteryPromptShown(activity)) return
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
                StreamSettings.markBatteryPromptShown(activity)
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}
