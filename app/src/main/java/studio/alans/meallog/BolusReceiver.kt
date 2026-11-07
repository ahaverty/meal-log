package studio.alans.meallog

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch

/**
 * FX Watcher's bolus broadcast (its Settings > Connections > Share boluses with other apps).
 * Any bolus answers the bolus reminder. A meal bolus (carbs >= the minimum) opens the meal sheet
 * over CamAPS, once per bolus: "update" (carbs seen after the bolus) opens it if "new" didn't.
 */
class BolusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_CARBS -> return carbs(c, i)
            ACTION_BOOST -> return Reminders.boostStarted(c, i.getStringExtra("kind").orEmpty(),
                i.getLongExtra("start", System.currentTimeMillis()))
        }
        val units = i.getDoubleExtra("units", 0.0)
        val at = i.getLongExtra("at", System.currentTimeMillis())
        val carbs = if (i.hasExtra("carbs")) i.getDoubleExtra("carbs", 0.0) else null
        Log.i("MealLog", "bolus ${i.getStringExtra("kind")} $units U ${carbs ?: "-"} g at ${Api.stamp(at)}")
        val p = Prefs(c)
        p.lastBolusAt = at
        p.lastBolusUnits = units.toFloat()
        p.lastBolusCarbs = (carbs ?: -1.0).toFloat()
        Reminders.bolusSeen(c, at)
        if (!p.askAfterBolus || carbs == null || carbs < p.minCarbs) return
        if (p.handledBolus == at) return
        p.handledBolus = at
        // Matches the meal planned in the Plan tab: log it without asking.
        if (PlanAutoLog.tryLog(c, at, carbs) { goAsync() }) return
        open(c, at, units, carbs)
    }

    /**
     * CamAPS Add meal, entered without a bolus (FX Watcher 3.13+). A hypo treatment is logged as a
     * glucose shot straight away, no questions. A meal or snack opens the sheet (or, put off, a
     * reminder that keeps asking until it's logged), since those are the ones he forgets.
     */
    private fun carbs(c: Context, i: Intent) {
        val p = Prefs(c)
        val at = i.getLongExtra("at", System.currentTimeMillis())
        val g = i.getDoubleExtra("carbs", 0.0)
        val type = i.getStringExtra("meal_type").orEmpty()   // hypo | meal | slow
        Log.i("MealLog", "carbs $type $g g at ${Api.stamp(at)}")
        if (g <= 0 || p.p.getLong("carbs_seen", 0) == at) return
        p.p.edit().putLong("carbs_seen", at).apply()
        if (type == "hypo") {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val ok = runCatching { Api(c).text("Glucose shot, ${g.toInt()} g carbs (hypo treatment)", at) }
                    quiet(c, if (ok.isSuccess) "Hypo treatment logged" else "Couldn't log the hypo treatment",
                        if (ok.isSuccess) "${g.toInt()} g glucose shot at ${Api.hhmm(at)}."
                        else "${g.toInt()} g at ${Api.hhmm(at)}: ${ok.exceptionOrNull()?.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        // Same carbs as a meal bolus just now: the bolus already asked.
        if (kotlin.math.abs(p.lastBolusAt - at) < 15 * 60_000L && p.lastBolusCarbs >= p.minCarbs) return
        if (!p.askAfterBolus) return Reminders.logLater(c, at, g, inMin = 1)
        open(c, at, 0.0, g)
    }

    companion object {
        private const val CH = "meal_sheet"
        const val ACTION_CARBS = "studio.alans.fxwatcher.action.CARBS"
        const val ACTION_BOOST = "studio.alans.fxwatcher.action.BOOST"

        fun quiet(c: Context, title: String, text: String) {
            Notify.channel(c, "auto_log", "Logged for you", NotificationManager.IMPORTANCE_LOW, sound = false)
            c.getSystemService(NotificationManager::class.java).notify(7, NotificationCompat.Builder(c, "auto_log")
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
                .setTimeoutAfter(3 * 3600_000L).build())
        }

        fun open(c: Context, at: Long, units: Double, carbs: Double) {
            val i = SheetActivity.intent(c, at, units, carbs).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Android lets an app start a screen from the background only with "Display over other apps".
            if (Settings.canDrawOverlays(c)) {
                try {
                    c.startActivity(i)
                    return
                } catch (e: Exception) {
                    Log.w("MealLog", "sheet start", e)
                }
            }
            // Without it: one quiet notification that opens the sheet.
            Notify.channel(c, CH, "After a meal bolus", NotificationManager.IMPORTANCE_DEFAULT, sound = false)
            val pi = PendingIntent.getActivity(c, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("What are you eating?")
                .setContentText("${carbs.toInt()} g at ${Api.hhmm(at)}. Tap to log it.")
                .setContentIntent(pi).setAutoCancel(true)
                .setTimeoutAfter(2 * 3600_000L)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(Notify.SHEET, n)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Reminders.restore(c)
    }
}
