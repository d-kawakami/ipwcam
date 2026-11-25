package com.example.ipwcam

import android.content.Context

/** 配信設定(アプリ内で共有)。送信間隔は SharedPreferences に保存する。 */
object StreamSettings {
    private const val PREFS = "ipwcam_settings"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_BATTERY_ASKED = "battery_opt_asked"

    /** 上限: 1時間 */
    const val MAX_INTERVAL_MS = 3_600_000L

    /** 送信間隔(ms)。0 = リアルタイム(従来動作) */
    fun getIntervalMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL_MS, 0L)
            .coerceIn(0L, MAX_INTERVAL_MS)

    fun setIntervalMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_INTERVAL_MS, ms.coerceIn(0L, MAX_INTERVAL_MS)).apply()
    }

    /** 「秒」入力文字列(小数可)をmsに変換。不正値は0(リアルタイム)。 */
    fun parseSecondsToMs(text: String?): Long {
        val sec = text?.trim()?.toDoubleOrNull() ?: return 0L
        if (sec <= 0.0) return 0L
        return (sec * 1000).toLong().coerceIn(0L, MAX_INTERVAL_MS)
    }

    fun formatMsAsSeconds(ms: Long): String =
        if (ms % 1000L == 0L) (ms / 1000L).toString() else "%.1f".format(ms / 1000.0)

    fun wasBatteryPromptShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATTERY_ASKED, false)

    fun markBatteryPromptShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
    }
}
