package studio.alans.meallog

import android.content.Context
import android.content.SharedPreferences

/** Settings and the little state the app keeps. Server settings default to the baked-in build values. */
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

    var url: String
        get() = p.getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.MEAL_URL
        set(v) = p.edit().putString("url", v.trim().trimEnd('/')).apply()
    var token: String
        get() = p.getString("token", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.MEAL_TOKEN
        set(v) = p.edit().putString("token", v.trim()).apply()
    var cfId: String
        get() = p.getString("cf_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CF_ID
        set(v) = p.edit().putString("cf_id", v.trim()).apply()
    var cfSecret: String
        get() = p.getString("cf_secret", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CF_SECRET
        set(v) = p.edit().putString("cf_secret", v.trim()).apply()

    /** Open the sheet after a meal bolus. */
    var askAfterBolus: Boolean
        get() = p.getBoolean("ask_after_bolus", true)
        set(v) = p.edit().putBoolean("ask_after_bolus", v).apply()
    /** Smallest carbs (g) that count as a meal bolus. */
    var minCarbs: Int
        get() = p.getInt("min_carbs", 10)
        set(v) = p.edit().putInt("min_carbs", v).apply()

    /** Last bolus FX Watcher told us about (ms), for the reminder check. */
    var lastBolusAt: Long
        get() = p.getLong("last_bolus_at", 0)
        set(v) = p.edit().putLong("last_bolus_at", v).apply()
    var lastBolusUnits: Float
        get() = p.getFloat("last_bolus_units", 0f)
        set(v) = p.edit().putFloat("last_bolus_units", v).apply()
    var lastBolusCarbs: Float
        get() = p.getFloat("last_bolus_carbs", -1f)
        set(v) = p.edit().putFloat("last_bolus_carbs", v).apply()

    /** The bolus reminder: when it rings (0 = none), when it was set, and what for. */
    var reminderAt: Long
        get() = p.getLong("reminder_at", 0)
        set(v) = p.edit().putLong("reminder_at", v).apply()
    var reminderSetAt: Long
        get() = p.getLong("reminder_set_at", 0)
        set(v) = p.edit().putLong("reminder_set_at", v).apply()
    var reminderNote: String
        get() = p.getString("reminder_note", "") ?: ""
        set(v) = p.edit().putString("reminder_note", v).apply()

    /** The last meal planned in the Plan tab: the sheet after the next bolus offers it first. */
    var planSummary: String
        get() = p.getString("plan_summary", "") ?: ""
        set(v) = p.edit().putString("plan_summary", v).apply()
    var planCarbs: Float
        get() = p.getFloat("plan_carbs", -1f)
        set(v) = p.edit().putFloat("plan_carbs", v).apply()
    var planAt: Long
        get() = p.getLong("plan_at", 0)
        set(v) = p.edit().putLong("plan_at", v).apply()

    /** Bolus times (ms) the sheet has already been shown or answered for. */
    var handledBolus: Long
        get() = p.getLong("handled_bolus", 0)
        set(v) = p.edit().putLong("handled_bolus", v).apply()
}
