package studio.alans.meallog

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

/**
 * "Remind me to bolus in 10 min": Alan orders a sandwich, plans the carbs, then forgets to bolus
 * when it arrives. The reminder rings only if no bolus came in since it was set; a bolus from FX
 * Watcher's broadcast cancels it quietly.
 */
object Reminders {
    private const val ACTION_RING = "studio.alans.meallog.RING"
    private const val ACTION_DONE = "studio.alans.meallog.DONE"
    private const val ACTION_SNOOZE = "studio.alans.meallog.SNOOZE"
    private const val CH = "bolus_reminder"

    fun set(c: Context, minutes: Int, note: String) {
        val p = Prefs(c)
        val now = System.currentTimeMillis()
        p.reminderAt = now + minutes * 60_000L
        p.reminderSetAt = now
        p.reminderNote = note
        schedule(c, p.reminderAt)
    }

    fun cancel(c: Context) {
        val p = Prefs(c)
        p.reminderAt = 0
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, ACTION_RING))
        c.getSystemService(NotificationManager::class.java).cancel(Notify.REMINDER)
    }

    /** A bolus came in: it answers a reminder set before it. */
    fun bolusSeen(c: Context, at: Long) {
        val p = Prefs(c)
        if (p.reminderAt > 0 && at >= p.reminderSetAt - 60_000L) cancel(c)
    }

    fun restore(c: Context) {
        val p = Prefs(c)
        if (p.reminderAt > System.currentTimeMillis()) schedule(c, p.reminderAt)
        else if (p.reminderAt > 0) ring(c)
    }

    private fun schedule(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi(c, ACTION_RING))
    }

    private fun pi(c: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(c, action.hashCode(), Intent(c, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun ring(c: Context) {
        val p = Prefs(c)
        if (p.reminderAt == 0L) return
        if (p.lastBolusAt >= p.reminderSetAt - 60_000L) {   // bolused already (broadcast came while we were asleep)
            cancel(c)
            return
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Bolus reminder", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ch.enableVibration(true)
            ch.setBypassDnd(true)
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = p.reminderNote.takeIf { it.isNotBlank() }?.let { " for $it" } ?: ""
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Bolus now$note")
            .setContentText("No bolus seen since you set this at ${Api.hhmm(p.reminderSetAt)}.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .addAction(0, "Done", pi(c, ACTION_DONE))
            .addAction(0, "5 more min", pi(c, ACTION_SNOOZE))
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        nm.notify(Notify.REMINDER, n)
    }

    fun handle(c: Context, action: String?) {
        when (action) {
            ACTION_RING -> ring(c)
            ACTION_DONE -> cancel(c)
            ACTION_SNOOZE -> {
                val p = Prefs(c)
                c.getSystemService(NotificationManager::class.java).cancel(Notify.REMINDER)
                p.reminderAt = System.currentTimeMillis() + 5 * 60_000L
                schedule(c, p.reminderAt)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Reminders.handle(c, i.action)
}

object Notify {
    const val SHEET = 1
    const val REMINDER = 2

    fun channel(c: Context, id: String, name: String, importance: Int, sound: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, name, importance)
        if (!sound) ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }
}
