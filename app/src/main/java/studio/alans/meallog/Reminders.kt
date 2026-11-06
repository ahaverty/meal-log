package studio.alans.meallog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Timed reminders. A bolus reminder ("bolus for the sandwich at 10:35") rings like an alarm, but
 * only if no bolus came in since it was set: FX Watcher's broadcast cancels it quietly. Other
 * reminders from a plan (start Boost, enter the slow carbs) ring once as a loud notification.
 */
data class Reminder(val id: Int, val at: Long, val text: String, val bolus: Boolean, val setAt: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("text", text).put("bolus", bolus).put("set_at", setAt)

    companion object {
        fun of(j: JSONObject) = Reminder(j.getInt("id"), j.getLong("at"), j.optString("text"), j.optBoolean("bolus"), j.optLong("set_at"))
    }
}

object Reminders {
    private const val ACTION_RING = "studio.alans.meallog.RING"
    private const val ACTION_DONE = "studio.alans.meallog.DONE"
    private const val ACTION_SNOOZE = "studio.alans.meallog.SNOOZE"
    private const val CH_BOLUS = "bolus_reminder"
    private const val CH_STEP = "plan_step"

    /** Changes to the list, for the screens that show it. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun all(c: Context): List<Reminder> {
        val raw = Prefs(c).p.getString("reminders", "[]") ?: "[]"
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { Reminder.of(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList()).sortedBy { it.at }
    }

    private fun save(c: Context, list: List<Reminder>) {
        Prefs(c).p.edit().putString("reminders", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
        version.value++
    }

    fun add(c: Context, inMin: Int, text: String, bolus: Boolean): Reminder {
        val now = System.currentTimeMillis()
        val list = all(c)
        val id = (list.maxOfOrNull { it.id } ?: 0) % 9000 + 1
        val r = Reminder(id, now + inMin.coerceAtLeast(1) * 60_000L, text, bolus, now)
        // one bolus reminder at a time: a new plan replaces the old one
        if (bolus) list.filter { it.bolus }.forEach { unschedule(c, it.id) }
        save(c, (if (bolus) list.filterNot { it.bolus } else list) + r)
        schedule(c, r)
        return r
    }

    fun cancel(c: Context, id: Int) {
        unschedule(c, id)
        c.getSystemService(NotificationManager::class.java).cancel(1000 + id)
        save(c, all(c).filterNot { it.id == id })
    }

    /** A bolus came in: it answers every bolus reminder set before it. */
    fun bolusSeen(c: Context, at: Long) {
        all(c).filter { it.bolus && at >= it.setAt - 60_000L }.forEach { cancel(c, it.id) }
    }

    fun restore(c: Context) {
        val now = System.currentTimeMillis()
        all(c).forEach { if (it.at > now) schedule(c, it) else ring(c, it.id) }
    }

    private fun schedule(c: Context, r: Reminder) {
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(r.at, open(c, "reminders")), pi(c, ACTION_RING, r.id))
    }

    private fun unschedule(c: Context, id: Int) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, ACTION_RING, id))
    }

    private fun pi(c: Context, action: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(c, action.hashCode() + id * 7,
            Intent(c, ReminderReceiver::class.java).setAction(action).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun open(c: Context, page: String): PendingIntent =
        PendingIntent.getActivity(c, page.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("meallog://$page"), c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun channels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CH_BOLUS) == null) {
            val ch = NotificationChannel(CH_BOLUS, "Bolus reminder", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ch.enableVibration(true)
            ch.setBypassDnd(true)
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_STEP) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_STEP, "Meal plan steps", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) })
        }
    }

    fun ring(c: Context, id: Int) {
        val r = all(c).firstOrNull { it.id == id } ?: return
        val p = Prefs(c)
        if (r.bolus && p.lastBolusAt >= r.setAt - 60_000L) {   // bolused already (broadcast came while asleep)
            cancel(c, id)
            return
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        channels(nm)
        val b = NotificationCompat.Builder(c, if (r.bolus) CH_BOLUS else CH_STEP)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open(c, "reminders"))
            .addAction(0, "Done", pi(c, ACTION_DONE, id))
            .addAction(0, "5 more min", pi(c, ACTION_SNOOZE, id))
            .setPriority(NotificationCompat.PRIORITY_MAX)
        if (r.bolus) {
            b.setContentTitle("Bolus now" + (r.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                .setContentText("No bolus seen since ${Api.hhmm(r.setAt)}.")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(open(c, "reminders"), true)
                .setOngoing(true)
        } else {
            b.setContentTitle(r.text).setContentText("From your meal plan at ${Api.hhmm(r.setAt)}.")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
        }
        val n = b.build()
        if (r.bolus) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(1000 + id, n)
    }

    fun handle(c: Context, action: String?, id: Int) {
        when (action) {
            ACTION_RING -> ring(c, id)
            ACTION_DONE -> cancel(c, id)
            ACTION_SNOOZE -> {
                c.getSystemService(NotificationManager::class.java).cancel(1000 + id)
                val list = all(c)
                val r = list.firstOrNull { it.id == id } ?: return
                val moved = r.copy(at = System.currentTimeMillis() + 5 * 60_000L)
                save(c, list.map { if (it.id == id) moved else it })
                schedule(c, moved)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Reminders.handle(c, i.action, i.getIntExtra("id", -1))
}

object Notify {
    const val SHEET = 1

    fun channel(c: Context, id: String, name: String, importance: Int, sound: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, name, importance)
        if (!sound) ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }
}
