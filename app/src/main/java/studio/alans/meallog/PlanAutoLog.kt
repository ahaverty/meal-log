package studio.alans.meallog

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * After a meal bolus that matches the plan, log the planned meal without asking (Alan, 6 Nov:
 * "surely it could just grab the plan unless there's a big difference"). It matches when the
 * bolus carbs are within 5 g or 20% of the planned calculator carbs, and the bolus comes within
 * 45 min of when the plan said to bolus. Otherwise the sheet opens as before, plan first.
 */
object PlanAutoLog {
    private const val CH = "meal_sheet_quiet"

    /** True when it took the bolus (logging in the background); false = open the sheet instead. */
    fun tryLog(c: Context, at: Long, carbs: Double, async: () -> BroadcastReceiver.PendingResult?): Boolean {
        val p = Prefs(c)
        val summary = p.planSummary
        val planned = p.planCarbs.toDouble()
        if (summary.isBlank() || planned <= 0 || p.planJson.isBlank()) return false
        val plan = runCatching { Plan.of(JSONObject(p.planJson)) }.getOrNull() ?: return false
        val due = p.planAt + (plan.bolusInMin ?: plan.eatInMin ?: 0) * 60_000L
        if (at < p.planAt - 5 * 60_000L || abs(at - due) > 45 * 60_000L) return false
        if (abs(carbs - planned) > max(5.0, planned * 0.2)) return false
        if (!Api(c).configured()) return false

        p.planSummary = ""   // used: the sheet won't offer it again
        val pending = async()   // only now: keeps the receiver alive while it logs
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching { Api(c).text(summary, at) }
                .onFailure { Log.w("MealLog", "auto-log plan", it) }.isSuccess
            if (ok) {
                p.loggedBolus = at
                notify(c, "Logged from your plan", "$summary, ${carbs.toInt()} g at ${Api.hhmm(at)}. Tap if it's wrong.",
                    Intent(Intent.ACTION_VIEW, Uri.parse("meallog://log")).setPackage(c.packageName))
            } else {
                p.planSummary = summary
                BolusReceiver.open(c, at, 0.0, carbs)
            }
            pending?.finish()
        }
        return true
    }

    private fun notify(c: Context, title: String, text: String, open: Intent) {
        Notify.channel(c, CH, "After a meal bolus", NotificationManager.IMPORTANCE_DEFAULT, sound = false)
        val pi = PendingIntent.getActivity(c, 3, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true)
            .setTimeoutAfter(2 * 3600_000L)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(Notify.SHEET, n)
    }
}
