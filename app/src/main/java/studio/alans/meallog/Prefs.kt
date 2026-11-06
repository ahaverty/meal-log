package studio.alans.meallog

import android.content.Context
import android.content.SharedPreferences

/** Settings and the little state the app keeps. */
class Prefs(c: Context) {
    val p: SharedPreferences = c.getSharedPreferences("meallog", Context.MODE_PRIVATE)

    init {
        // A private build can come with its server settings baked in. Keep them as real settings,
        // so they survive updating to the public GitHub build, which has none.
        if (!p.contains("url") && BuildConfig.MEAL_URL.isNotBlank()) {
            p.edit().putString("url", BuildConfig.MEAL_URL).putString("token", BuildConfig.MEAL_TOKEN)
                .putString("cf_id", BuildConfig.CF_ID).putString("cf_secret", BuildConfig.CF_SECRET).apply()
        }
    }

    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v.trim()).apply()

    var url: String get() = str("url"); set(v) = put("url", v.trimEnd('/'))
    var token: String get() = str("token"); set(v) = put("token", v)
    var cfId: String get() = str("cf_id"); set(v) = put("cf_id", v)
    var cfSecret: String get() = str("cf_secret"); set(v) = put("cf_secret", v)

    /** Open the sheet after a meal bolus. */
    var askAfterBolus: Boolean
        get() = p.getBoolean("ask_after_bolus", true)
        set(v) = p.edit().putBoolean("ask_after_bolus", v).apply()
    /** Smallest carbs (g) that count as a meal bolus. */
    var minCarbs: Int
        get() = p.getInt("min_carbs", 10)
        set(v) = p.edit().putInt("min_carbs", v).apply()

    /** Where the plan card over CamAPS sits (px from the top), -1 = default. */
    var overlayY: Int
        get() = p.getInt("overlay_y", -1)
        set(v) = p.edit().putInt("overlay_y", v).apply()

    /** Last bolus FX Watcher told us about. */
    var lastBolusAt: Long
        get() = p.getLong("last_bolus_at", 0)
        set(v) = p.edit().putLong("last_bolus_at", v).apply()
    var lastBolusUnits: Float
        get() = p.getFloat("last_bolus_units", 0f)
        set(v) = p.edit().putFloat("last_bolus_units", v).apply()
    var lastBolusCarbs: Float
        get() = p.getFloat("last_bolus_carbs", -1f)
        set(v) = p.edit().putFloat("last_bolus_carbs", v).apply()

    /** The bolus the sheet was last opened for, and the last bolus whose food got logged. */
    var handledBolus: Long
        get() = p.getLong("handled_bolus", 0)
        set(v) = p.edit().putLong("handled_bolus", v).apply()
    var loggedBolus: Long
        get() = p.getLong("logged_bolus", 0)
        set(v) = p.edit().putLong("logged_bolus", v).apply()

    /** A meal bolus in the last 3 h with nothing logged for it yet, or 0. */
    fun unloggedBolus(now: Long = System.currentTimeMillis()): Long {
        val at = lastBolusAt
        return if (at > 0 && now - at < 3 * 3600_000L && lastBolusCarbs >= minCarbs && loggedBolus != at) at else 0
    }

    /** The last plan (JSON from the server, plus what was asked), kept for 3 h. */
    var planJson: String get() = str("plan_json"); set(v) = p.edit().putString("plan_json", v).apply()
    var planAt: Long
        get() = p.getLong("plan_at", 0)
        set(v) = p.edit().putLong("plan_at", v).apply()
    /** What was planned, for the sheet after the bolus ("" once used). */
    var planSummary: String get() = str("plan_summary"); set(v) = p.edit().putString("plan_summary", v).apply()
    var planCarbs: Float
        get() = p.getFloat("plan_carbs", -1f)
        set(v) = p.edit().putFloat("plan_carbs", v).apply()
}
