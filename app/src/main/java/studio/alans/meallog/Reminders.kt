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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Timed reminders that track whether they were done.
 *
 * A reminder is "due" until it is closed: done (tapped Done, or for a bolus reminder FX Watcher saw
 * the bolus), skipped, or missed. When its time comes it rings, then rings again every 5 minutes
 * (louder from the third ring) until he answers. Unanswered after [NAG_MAX] rings it is closed as
 * missed, with one notification that says so. Anything still open [STALE_MS] after its time (phone
 * off, app killed) is closed as missed too, so last night's reminders never hang around.
 * Closed reminders show under "Earlier" for a day and are dropped after [KEEP_MS].
 *
 * The next reminder also sits in the notification shade as a live countdown, like a clock timer.
 */
data class Reminder(
    val id: Int, val at: Long, val text: String, val bolus: Boolean, val setAt: Long,
    val state: String = DUE, val closedAt: Long = 0, val rings: Int = 0, val note: String = "",
    /** A meal to log: its time (ms), 0 for other reminders. */
    val meal: Long = 0, val mealCarbs: Double = 0.0,
    /** Ring only if glucose agrees: {"below":5,"above":null,"trend":"falling"}; "" = always ring. */
    val cond: String = "",
) {
    val open get() = state == DUE
    val check: GlucoseCheck? get() = GlucoseCheck.of(cond)

    fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("text", text).put("bolus", bolus)
        .put("set_at", setAt).put("state", state).put("closed_at", closedAt).put("rings", rings).put("note", note)
        .put("meal", meal).put("meal_carbs", mealCarbs).put("cond", cond)

    companion object {
        const val DUE = "due"
        const val DONE = "done"
        const val SKIPPED = "skipped"
        const val MISSED = "missed"

        fun of(j: JSONObject) = Reminder(j.getInt("id"), j.getLong("at"), j.optString("text"), j.optBoolean("bolus"),
            j.optLong("set_at"), j.optString("state", DUE).ifBlank { DUE }, j.optLong("closed_at"), j.optInt("rings"),
            j.optString("note"), j.optLong("meal"), j.optDouble("meal_carbs", 0.0), j.optString("cond"))
    }
}

object Reminders {
    const val ACTION_RING = "studio.alans.meallog.RING"
    private const val ACTION_DONE = "studio.alans.meallog.DONE"
    private const val ACTION_SNOOZE = "studio.alans.meallog.SNOOZE"
    private const val ACTION_SKIP = "studio.alans.meallog.SKIP"
    private const val CH_BOLUS = "bolus_reminder"
    private const val CH_QUIET = "quiet_reminder"   // non-urgent: no sound, no vibration
    private const val CH_TIMER = "countdown"
    private const val CH_MISSED = "missed"           // only urgent reminders get here
    const val TIMER_ID = 999

    const val NAG_MIN = 5
    const val NAG_MAX = 4                    // rings at 0, 5, 10, 15 min; missed at 20
    private const val STALE_MS = 2 * 3600_000L
    private const val KEEP_MS = 3 * 24 * 3600_000L
    private const val TIMER_AHEAD_MS = 6 * 3600_000L

    /** Changes to the list, for the screens that show it. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Everything, open and closed, oldest first. */
    fun all(c: Context): List<Reminder> {
        val raw = Prefs(c).p.getString("reminders", "[]") ?: "[]"
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { Reminder.of(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList()).sortedBy { it.at }
    }

    /** Still to do, soonest first. */
    fun open(c: Context) = all(c).filter { it.open }

    /** Closed in the last day, newest first. */
    fun earlier(c: Context, now: Long = System.currentTimeMillis()) =
        all(c).filter { !it.open && now - it.closedAt < 24 * 3600_000L }.sortedByDescending { it.at }

    private fun save(c: Context, list: List<Reminder>) {
        val now = System.currentTimeMillis()
        val kept = list.filter { it.open || now - it.closedAt < KEEP_MS }
        Prefs(c).p.edit().putString("reminders", JSONArray().apply { kept.forEach { put(it.json()) } }.toString()).apply()
        version.value++
        timer(c)
    }

    private fun update(c: Context, id: Int, f: (Reminder) -> Reminder): Reminder? {
        val list = all(c)
        val r = list.firstOrNull { it.id == id } ?: return null
        val n = f(r)
        save(c, list.map { if (it.id == id) n else it })
        return n
    }

    fun add(c: Context, inMin: Int, text: String, bolus: Boolean, cond: GlucoseCheck? = GlucoseCheck.parse(text),
            meal: Long = 0, mealCarbs: Double = 0.0): Reminder {
        val now = System.currentTimeMillis()
        val list = all(c)
        val id = (list.maxOfOrNull { it.id } ?: 0) % 9000 + 1
        val r = Reminder(id, now + inMin.coerceAtLeast(1) * 60_000L, text, bolus, now,
            cond = if (bolus) "" else cond?.json()?.toString().orEmpty(), meal = meal, mealCarbs = mealCarbs)
        // one bolus reminder at a time: a new plan replaces the old one
        val replaced = if (bolus) list.filter { it.bolus && it.open } else emptyList()
        replaced.forEach { unschedule(c, it.id); nm(c).cancel(1000 + it.id) }
        save(c, list.map { if (it in replaced) it.copy(state = Reminder.SKIPPED, closedAt = now, note = "replaced") else it } + r)
        schedule(c, r.id, r.at)
        return r
    }

    /** Takes it off the list altogether (the X, or a plan that was redone). */
    fun cancel(c: Context, id: Int) {
        AlertOverlay.hide(id)
        unschedule(c, id)
        nm(c).cancel(1000 + id)
        save(c, all(c).filterNot { it.id == id })
    }

    /** Closes it as done / skipped / missed and stops the ringing. */
    fun close(c: Context, id: Int, state: String, note: String = "") {
        AlertOverlay.hide(id)
        unschedule(c, id)
        nm(c).cancel(1000 + id)
        update(c, id) { if (it.open) it.copy(state = state, closedAt = System.currentTimeMillis(), note = note) else it }
    }

    /** Ring again in [min] minutes, counting from now. */
    fun snooze(c: Context, id: Int, min: Int = NAG_MIN) {
        AlertOverlay.hide(id)
        nm(c).cancel(1000 + id)
        val r = update(c, id) { it.copy(at = System.currentTimeMillis() + min * 60_000L, rings = 0) } ?: return
        schedule(c, r.id, r.at)
    }

    /**
     * He moved the meal (started cooking, realised he's eating later): these reminders move by [ms].
     * One already ringing stops and counts again from now, so nothing goes off while he cooks.
     */
    fun shift(c: Context, ids: Collection<Int>, ms: Long) {
        val now = System.currentTimeMillis()
        val list = all(c)
        val moved = list.filter { it.open && it.id in ids }
        moved.forEach { AlertOverlay.hide(it.id); unschedule(c, it.id); nm(c).cancel(1000 + it.id) }
        val next = moved.associate { r ->
            val at = (if (r.at <= now && r.rings > 0) now + ms else r.at + ms).coerceAtLeast(now + 60_000L)
            r.id to r.copy(at = at, rings = 0)
        }
        save(c, list.map { next[it.id] ?: it })
        next.values.forEach { schedule(c, it.id, it.at) }
    }

    /** A bolus came in: it answers every bolus reminder set before it. */
    fun bolusSeen(c: Context, at: Long) {
        open(c).filter { it.bolus && at >= it.setAt - 60_000L }
            .forEach { close(c, it.id, Reminder.DONE, "bolus seen ${Api.hhmm(at)}") }
    }

    /** Carbs went in without a bolus and the sheet was put off: ask again until it is logged. */
    fun logLater(c: Context, at: Long, carbs: Double, inMin: Int = 30) {
        if (open(c).any { it.meal != 0L && kotlin.math.abs(it.meal - at) < 60_000L }) return
        add(c, inMin, "Log what you ate at ${Api.hhmm(at)}" + (if (carbs > 0) " (${carbs.toInt()} g)" else ""),
            bolus = false, cond = null, meal = at, mealCarbs = carbs)
    }

    /** The food for that meal got logged (or "nothing to log"): its reminder is done. */
    fun mealLogged(c: Context, at: Long) {
        open(c).filter { it.meal != 0L && kotlin.math.abs(it.meal - at) < 60_000L }.forEach { close(c, it.id, Reminder.DONE) }
    }

    /** CamAPS shows Boost / Ease-off started (FX Watcher): the reminder to start it is done. */
    fun boostStarted(c: Context, kind: String, at: Long) {
        val k = kind.lowercase().replace("-", "")
        open(c).filter { !it.bolus && it.meal == 0L && it.at - at < 3600_000L &&
                it.text.lowercase().replace("-", "").let { t -> k in t && "start" in t } }
            .forEach { close(c, it.id, Reminder.DONE, "$kind started ${Api.hhmm(at)}") }
    }

    /** Closes reminders nobody answered long ago. Runs on app open, boot and every ring. */
    fun sweep(c: Context) {
        val now = System.currentTimeMillis()
        val stale = open(c).filter { now - it.at > STALE_MS }
        stale.forEach { unschedule(c, it.id); nm(c).cancel(1000 + it.id); AlertOverlay.hide(it.id) }
        save(c, all(c).map { if (it in stale) it.copy(state = Reminder.MISSED, closedAt = now, note = "never answered") else it })
    }

    fun restore(c: Context) {
        sweep(c)
        val now = System.currentTimeMillis()
        open(c).forEach { if (it.at > now) schedule(c, it.id, it.at) else ring(c, it.id) }
    }

    private fun schedule(c: Context, id: Int, at: Long) {
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(at, open(c, "reminders")), pi(c, ACTION_RING, id))
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

    private fun nm(c: Context): NotificationManager = c.getSystemService(NotificationManager::class.java).also(::channels)

    private fun channels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CH_BOLUS) == null) {
            val ch = NotificationChannel(CH_BOLUS, "Reminder alarm", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Urgent only: bolus not seen, glucose checks that came true."
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ch.enableVibration(true)
            ch.setBypassDnd(true)
            nm.createNotificationChannel(ch)
        }
        // The old vibrating "Meal plan steps" channel: Android won't let an app make a channel quieter.
        if (nm.getNotificationChannel("plan_step") != null) nm.deleteNotificationChannel("plan_step")
        if (nm.getNotificationChannel(CH_QUIET) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Reminders (quiet)", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null); enableVibration(false); description = "Log your food, plan steps, timers. No sound or vibration." })
        }
        if (nm.getNotificationChannel(CH_TIMER) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Countdown to the next reminder", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); setShowBadge(false) })
        }
        if (nm.getNotificationChannel(CH_MISSED) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) })
        }
    }

    /** The live countdown in the shade for the next reminder, like the clock app's timer. */
    fun timer(c: Context) {
        val nm = nm(c)
        val now = System.currentTimeMillis()
        val next = open(c).firstOrNull { it.at > now && it.at - now < TIMER_AHEAD_MS }
        if (next == null) { nm.cancel(TIMER_ID); return }
        val more = open(c).count { it.at > now } - 1
        val n = NotificationCompat.Builder(c, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title(next))
            .setContentText((if (next.bolus || next.check != null) "Rings at " else "Quiet reminder at ") + Api.hhmm(next.at) + (if (more > 0) "  ·  $more more after" else ""))
            .setWhen(next.at).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(open(c, "reminders"))
            .addAction(0, "Done", pi(c, ACTION_DONE, next.id))
            .addAction(0, "Skip", pi(c, ACTION_SKIP, next.id))
            .build()
        nm.notify(TIMER_ID, n)
    }

    fun title(r: Reminder) = if (r.bolus) "Bolus" + (r.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") else r.text

    fun ring(c: Context, id: Int, g: Glucose? = null) {
        val r = all(c).firstOrNull { it.id == id } ?: return
        if (!r.open) return
        val now = System.currentTimeMillis()
        val chk = r.check
        if (chk != null && g != null && !chk.met(g)) {   // live glucose decides: don't ask when it isn't true
            close(c, id, if (r.rings == 0) Reminder.SKIPPED else Reminder.DONE,
                (if (r.rings == 0) "not needed, glucose " else "settled, glucose ") + g.say())
            return
        }
        if (r.bolus && Prefs(c).lastBolusAt >= r.setAt - 60_000L) {   // bolused already (broadcast came while asleep)
            close(c, id, Reminder.DONE, "bolus seen ${Api.hhmm(Prefs(c).lastBolusAt)}")
            return
        }
        if (now - r.at > STALE_MS) { sweep(c); return }
        val nm = nm(c)
        if (r.rings >= NAG_MAX) {   // asked 4 times, no answer: close it as missed and say so once
            close(c, id, Reminder.MISSED, "no answer")
            nm.notify(1000 + id, NotificationCompat.Builder(c, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Missed: ${title(r)}")
                .setContentText("Due ${Api.hhmm(r.at)}, no answer after ${NAG_MAX} rings. Tap if you did it.")
                .setContentIntent(open(c, "reminders"))
                .addAction(0, "I did it", pi(c, ACTION_DONE, id))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build())
            return
        }
        val ringNo = r.rings + 1
        // Urgent = bolus not seen, or a glucose check that came true (or glucose couldn't be read).
        // Those ring, nag and pop up over other apps. Everything else (log your food, plan steps,
        // plain timers) is one silent notification that stays until answered or goes stale.
        val urgent = r.bolus || chk != null
        val tap = if (r.meal != 0L) PendingIntent.getActivity(c, 3000 + id,
                SheetActivity.intent(c, r.meal, 0.0, r.mealCarbs).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else open(c, "reminders")
        if (!urgent) {
            nm.notify(1000 + id, NotificationCompat.Builder(c, CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(tap)
                .setContentTitle(r.text)
                .setContentText("Due ${Api.hhmm(r.at)}" + if (r.meal != 0L) ". Tap to log it." else "")
                .addAction(0, "Done", pi(c, ACTION_DONE, id))
                .addAction(0, "Skip", pi(c, ACTION_SKIP, id))
                .setSilent(true).setOnlyAlertOnce(true)
                .setTimeoutAfter(STALE_MS)
                .build())
            update(c, id) { it.copy(rings = ringNo) }
            return
        }
        val b = NotificationCompat.Builder(c, CH_BOLUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(tap)
            .addAction(0, "Done", pi(c, ACTION_DONE, id))
            .addAction(0, "5 more min", pi(c, ACTION_SNOOZE, id))
            .addAction(0, "Skip", pi(c, ACTION_SKIP, id))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(tap, true)
            .setOngoing(true)
            .setWhen(r.at).setShowWhen(true).setUsesChronometer(true)   // counts up: how late it is
            .setContentTitle(if (r.bolus) "Bolus now" + (r.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") else r.text)
            .setContentText(
                (if (r.bolus) "No bolus seen since ${Api.hhmm(r.setAt)}. " else "Due ${Api.hhmm(r.at)}. ") +
                    (if (chk != null) (g?.let { "Glucose ${it.say()}. " } ?: "Couldn't read glucose. ") else "") +
                    if (ringNo < NAG_MAX) "Rings again in $NAG_MIN min until you tap Done." else "Last ring.")
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(1000 + id, n)
        AlertOverlay.show(c, id, if (r.bolus) "Bolus now" + (r.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") else r.text,
            (if (r.bolus) "No bolus seen since ${Api.hhmm(r.setAt)}." else "Due ${Api.hhmm(r.at)}.") +
                (if (chk != null) g?.let { " Glucose ${it.say()}." } ?: " Couldn't read glucose." else ""))
        update(c, id) { it.copy(rings = ringNo) }
        schedule(c, id, now + NAG_MIN * 60_000L)   // the next nag (or the missed note)
    }

    enum class Answer { DONE, SNOOZE, SKIP }

    /** From the popup's buttons. */
    fun answer(c: Context, id: Int, a: Answer) = handle(c, when (a) {
        Answer.DONE -> ACTION_DONE; Answer.SNOOZE -> ACTION_SNOOZE; Answer.SKIP -> ACTION_SKIP
    }, id)

    fun handle(c: Context, action: String?, id: Int) {
        when (action) {
            ACTION_RING -> ring(c, id)
            ACTION_DONE -> {
                val r = all(c).firstOrNull { it.id == id }
                if (r != null && r.state == Reminder.MISSED) {   // "I did it" on the missed note
                    nm(c).cancel(1000 + id)
                    update(c, id) { it.copy(state = Reminder.DONE, note = "done late") }
                } else close(c, id, Reminder.DONE)
            }
            ACTION_SKIP -> close(c, id, Reminder.SKIPPED)
            ACTION_SNOOZE -> snooze(c, id)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val r = Reminders.all(c).firstOrNull { it.id == id }
        if (i.action != Reminders.ACTION_RING || r?.check == null || !r.open) return Reminders.handle(c, i.action, id)
        // A glucose condition: read live glucose first (up to ~20 s); if it can't be read, ring anyway.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val g = withTimeoutOrNull(20_000L) { runCatching { Api(c).glucose() }.getOrNull() }
                Reminders.ring(c, id, g?.takeIf { it.ageMin <= 15 })
            } finally {
                pending.finish()
            }
        }
    }
}

/** Live glucose from the server (Nightscout): latest value, how old, change over the last 15 min. */
data class Glucose(val mmol: Double, val ageMin: Int, val delta15: Double) {
    val trend get() = when { delta15 <= -0.3 -> "falling"; delta15 >= 0.3 -> "rising"; else -> "steady" }
    fun say() = "%.1f %s".format(mmol, trend)
}

/** "Only if glucose is under 5 and falling". Under 4 always counts, whatever the trend. */
data class GlucoseCheck(val below: Double?, val above: Double?, val trend: String?) {
    fun met(g: Glucose): Boolean {
        if (below != null && g.mmol < 4.0) return true
        if (below != null && g.mmol >= below) return false
        if (above != null && g.mmol <= above) return false
        return trend == null || g.trend == trend
    }

    fun json(): JSONObject = JSONObject().put("below", below).put("above", above).put("trend", trend)
    fun say() = listOfNotNull(below?.let { "under %.1f".format(it) }, above?.let { "over %.1f".format(it) }, trend)
        .joinToString(" and ")

    companion object {
        fun of(s: String): GlucoseCheck? = if (s.isBlank()) null else runCatching {
            val j = JSONObject(s)
            fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.getDouble(k) else null
            GlucoseCheck(d("below"), d("above"), j.optString("trend").takeIf { it == "falling" || it == "rising" })
                .takeIf { it.below != null || it.above != null }
        }.getOrNull()

        private const val NUM = """(\d{1,2}(?:\.\d)?)"""
        private val BELOW = Regex("""(?:under|below|less than|<)\s*""" + NUM, RegexOption.IGNORE_CASE)
        private val ABOVE = Regex("""(?:over|above|more than|>)\s*""" + NUM, RegexOption.IGNORE_CASE)

        /** Reads the condition out of a reminder's words ("check glucose, under 5 and falling?"). */
        fun parse(text: String): GlucoseCheck? {
            val t = text.lowercase()
            if (listOf("glucose", "mmol", "bg", "hypo", "sugar").none { it in t }) return null
            val below = BELOW.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            val above = ABOVE.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            if (below == null && above == null) return null   // a plain "check glucose" always rings
            val trend = when {
                Regex("falling|dropping|going down|heading down").containsMatchIn(t) -> "falling"
                Regex("rising|going up|climbing").containsMatchIn(t) -> "rising"
                else -> null
            }
            return GlucoseCheck(below, above, trend)
        }
    }
}

object Notify {
    const val SHEET = 1

    fun channel(c: Context, id: String, name: String, importance: Int, sound: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        // the old "After a meal bolus" channel could vibrate; its replacement can't
        if (nm.getNotificationChannel("meal_sheet") != null) nm.deleteNotificationChannel("meal_sheet")
        if (nm.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, name, importance)
        if (!sound) { ch.setSound(null, null); ch.enableVibration(false) }
        nm.createNotificationChannel(ch)
    }
}
