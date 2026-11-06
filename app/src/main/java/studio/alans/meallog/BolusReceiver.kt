package studio.alans.meallog

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * FX Watcher's bolus broadcast (its Settings > Connections > Share boluses with other apps).
 * Any bolus answers the bolus reminder. A meal bolus (carbs >= the minimum) opens the meal sheet
 * over CamAPS, once per bolus: "update" (carbs seen after the bolus) opens it if "new" didn't.
 */
class BolusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
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
        open(c, at, units, carbs)
    }

    companion object {
        private const val CH = "meal_sheet"

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
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Reminders.restore(c)
    }
}
