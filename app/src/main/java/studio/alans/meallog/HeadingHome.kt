package studio.alans.meallog

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Heading home after drinks (Alan, 17 Nov 2026): he'd forget to tap a button, so the phone notices.
 * Android's activity recognition says when he starts walking, cycling or riding in a car; if he is
 * still moving after a few minutes (a trip to the bar or the toilet is shorter) while a night is on,
 * the app asks the server what to do (Ease-off now, a snack if glucose is low or falling, no Boost,
 * learned from his past drinking nights) and sets overnight glucose checks that only ring if glucose
 * agrees. Fallback: 2 h after the last drink with nothing else. A drink logged after it means he's
 * still out: the checks go and it can ask again later. Never units; nothing is set in CamAPS for him.
 */
object HeadingHome {
    private const val TAG = "HeadingHome"
    private const val CH = "heading_home"
    private const val ACT_TRANSITION = "studio.alans.meallog.TRANSITION"
    private const val ACT_CHECK = "studio.alans.meallog.MOVING_CHECK"
    private const val ACT_FALLBACK = "studio.alans.meallog.HOME_FALLBACK"
    private const val ACT_NOT_YET = "studio.alans.meallog.HOME_NOT_YET"
    private const val WALK_MIN = 8
    private const val RIDE_MIN = 5
    const val FALLBACK_MS = 2 * 3600_000L

    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun p(c: Context) = Prefs(c).p

    fun canSense(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pi(c: Context, action: String, mutable: Boolean = false) = PendingIntent.getBroadcast(c, action.hashCode(),
        Intent(action).setClass(c, HomeReceiver::class.java),
        (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A night is on: listen for movement and set the 2 h fallback from the last drink. */
    fun arm(c: Context) {
        val app = c.applicationContext
        val last = Drinks.lastAt(app)
        if (last > 0) alarm(app, ACT_FALLBACK, last + FALLBACK_MS)
        if (!canSense(app) || p(app).getBoolean("moving_on", false)) return
        val kinds = listOf(DetectedActivity.WALKING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
        val req = ActivityTransitionRequest(kinds.flatMap { k ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(k).setActivityTransition(it).build()
            }
        })
        runCatching {
            ActivityRecognition.getClient(app).requestActivityTransitionUpdates(req, pi(app, ACT_TRANSITION, mutable = true))
                .addOnSuccessListener { p(app).edit().putBoolean("moving_on", true).apply() }
                .addOnFailureListener { Log.w(TAG, "transitions failed", it) }
        }.onFailure { Log.w(TAG, "transitions failed", it) }
    }

    /** The night is over: stop listening. */
    fun disarm(c: Context) {
        val app = c.applicationContext
        if (p(app).getBoolean("moving_on", false) && canSense(app)) {
            runCatching { ActivityRecognition.getClient(app).removeActivityTransitionUpdates(pi(app, ACT_TRANSITION, mutable = true)) }
        }
        p(app).edit().putBoolean("moving_on", false).remove("moving_since").apply()
        app.getSystemService(AlarmManager::class.java).apply { cancel(pi(app, ACT_CHECK)); cancel(pi(app, ACT_FALLBACK)) }
    }

    /** Keeps listening only while a night is on (app open, boot, a drink logged). */
    fun sync(c: Context) { if (Drinks.active(c)) arm(c) else disarm(c) }

    private fun alarm(c: Context, action: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, action)) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, action)) }
    }

    fun onTransition(c: Context, i: Intent) {
        val r = ActivityTransitionResult.extractResult(i) ?: return
        for (e in r.transitionEvents) {
            val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            Log.i(TAG, "transition ${e.activityType} enter=$enter")
            if (enter) {
                if (p(c).getLong("moving_since", 0) == 0L) {
                    val now = System.currentTimeMillis()
                    p(c).edit().putLong("moving_since", now).putInt("moving_kind", e.activityType).apply()
                    alarm(c, ACT_CHECK, now + (if (e.activityType == DetectedActivity.WALKING) WALK_MIN else RIDE_MIN) * 60_000L)
                }
            } else if (e.activityType == p(c).getInt("moving_kind", -1)) {
                // stopped: back at the bar, or out of the toilet
                p(c).edit().remove("moving_since").apply()
                c.getSystemService(AlarmManager::class.java).cancel(pi(c, ACT_CHECK))
            }
        }
    }

    /** This night already got its heading-home (and no drink since). */
    fun asked(c: Context): Boolean = p(c).getLong("home_for", 0) == Drinks.lastAt(c) && Drinks.lastAt(c) > 0

    /** The plan for tonight, if one was made since the last drink. */
    fun plan(c: Context): JSONObject? = runCatching { JSONObject(p(c).getString("home_plan", "") ?: "") }.getOrNull()
        ?.takeIf { asked(c) }

    /** A drink was logged: he's still out. The overnight checks go; it can ask again later. */
    fun stillOut(c: Context) {
        val ids = runCatching { JSONArray(p(c).getString("home_checks", "[]")) }.getOrNull() ?: JSONArray()
        for (k in 0 until ids.length()) Reminders.cancel(c, ids.getInt(k))
        p(c).edit().remove("home_checks").remove("home_for").remove("home_plan").remove("moving_since").apply()
        c.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
        version.value++
    }

    private const val NOTE_ID = 7100

    /** Asks the server what to do and sets the overnight checks. [why] = "moving" | "late" | "tapped". */
    fun trigger(c: Context, why: String, notify: Boolean = true) {
        val app = c.applicationContext
        if (!Drinks.active(app) || (asked(app) && why != "tapped")) return
        val forNight = Drinks.lastAt(app)
        p(app).edit().putLong("home_for", forNight).apply()
        CoroutineScope(Dispatchers.IO).launch {
            val plan = runCatching { Api(app).home() }.getOrElse { fallback(app, it.message) }
            if (Drinks.lastAt(app) != forNight) return@launch   // a drink came in meanwhile
            val ids = JSONArray()
            plan.optJSONArray("checks").objects().forEach { ch ->
                val r = Reminders.add(app, ch.optInt("in_min", 120), ch.optString("text"), bolus = false,
                    cond = ch.optJSONObject("if_glucose")?.let { GlucoseCheck.of(it.toString()) })
                ids.put(r.id)
            }
            // old checks from an earlier ask tonight go
            runCatching { JSONArray(p(app).getString("home_checks", "[]")) }.getOrNull()?.let { old ->
                for (k in 0 until old.length()) Reminders.cancel(app, old.getInt(k))
            }
            p(app).edit().putString("home_plan", plan.put("why_asked", why).put("at", System.currentTimeMillis()).toString())
                .putString("home_checks", ids.toString()).apply()
            version.value++
            if (notify) notify(app, plan, why)
        }
    }

    /** No signal: the planner's standing rules from the count on the phone. */
    private fun fallback(c: Context, err: String?): JSONObject {
        val n = Drinks.alcoholCount(Drinks.had(c))
        val steps = JSONArray()
        if (n >= 2) steps.put(JSONObject().put("do", "Ease-off").put("text", "Start Ease-off now, for 6 h."))
        steps.put(JSONObject().put("do", "No Boost").put("text", "No Boost tonight, even if you're high at bed."))
        val since = (System.currentTimeMillis() - Drinks.lastAt(c)) / 60_000L
        val checks = JSONArray()
        listOf(2, 3, 5).map { maxOf(30L, it * 60 - since).toInt() }.distinct().forEach {
            checks.put(JSONObject().put("in_min", it).put("text", "Check glucose: under 5 and falling after drinks?")
                .put("if_glucose", JSONObject().put("below", 5).put("trend", "falling")))
        }
        return JSONObject().put("summary", "$n drinks tonight").put("steps", steps).put("checks", checks)
            .put("why", JSONArray().put("No signal, so these are the standing rules (${err ?: "offline"})."))
    }

    private fun notify(c: Context, plan: JSONObject, why: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Heading home after drinks", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Once a night: what to set before bed after drinks. One sound, no nagging." })
        }
        val steps = plan.optJSONArray("steps").objects().map { it.optString("text") }
        val title = when (why) { "late" -> "No drink for 2 h. Heading home?"; else -> "Heading home?" }
        val open = PendingIntent.getActivity(c, 7101, Intent(Intent.ACTION_VIEW, Uri.parse("meallog://drinks"), c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(steps.firstOrNull() ?: plan.optString("summary"))
            .setStyle(NotificationCompat.BigTextStyle().bigText((steps + "Overnight glucose checks are set (they only ring if you're dropping).").joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(0, "Not yet", pi(c, ACT_NOT_YET))
            .build()
        runCatching { nm.notify(NOTE_ID, n) }
    }

    fun onAlarm(c: Context, action: String) {
        when (action) {
            ACT_CHECK -> if (p(c).getLong("moving_since", 0) > 0) trigger(c, "moving")
            ACT_FALLBACK -> if (System.currentTimeMillis() - Drinks.lastAt(c) >= FALLBACK_MS - 60_000L) trigger(c, "late")
            ACT_NOT_YET -> { stillOut(c); p(c).edit().putLong("home_for", Drinks.lastAt(c)).apply()   // don't ask again until the next drink
                c.getSystemService(NotificationManager::class.java).cancel(NOTE_ID) }
        }
    }

    fun isTransition(a: String?) = a == ACT_TRANSITION
}

class HomeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (HeadingHome.isTransition(i.action)) HeadingHome.onTransition(c, i) else HeadingHome.onAlarm(c, i.action ?: return)
    }
}
