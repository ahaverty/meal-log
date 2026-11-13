package studio.alans.meallog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Calendar

/** One of his usual meals: what to put in the box, about how many carbs, how often he's had it. */
data class QuickPick(val label: String, val carbs: Int, val times: Int)

/**
 * His usual meals for any time of day, kept on the phone so the Plan page shows them instantly with
 * no network wait (Alan, 13 Nov). The server already ranks past meals by time of day, weekday and
 * how recent; /picks returns whole meals (no drinks) plus meals he planned before, for every
 * two-hour slot of the day, in one background call, and the lot is stored here. Refreshed every few hours by an inexact alarm, on app open when stale,
 * and after he logs food.
 */
object QuickPicks {
    private const val KEY = "quick_picks"
    private const val STALE_MS = 4 * 3600_000L
    private const val EVERY_MS = 6 * 3600_000L
    private val SLOTS = (0 until 24 step 2).toList()
    private val lock = Mutex()

    /** Changes when a refresh lands, so the Plan page redraws. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** The best [n] for a meal at [at], from the cache only. */
    fun near(c: Context, at: Long, n: Int = 3): List<QuickPick> {
        val slots = load(c)?.optJSONObject("slots") ?: return emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val slot = SLOTS.minBy { s -> minOf(kotlin.math.abs(s - h), 24 - kotlin.math.abs(s - h)) }
        val a = slots.optJSONArray(slot.toString()) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { QuickPick(it.optString("label"), it.optInt("carbs"), it.optInt("times")) }
            .filter { it.label.isNotBlank() }
            .take(n)
    }

    private fun load(c: Context): JSONObject? =
        runCatching { JSONObject(Prefs(c).p.getString(KEY, "") ?: "") }.getOrNull()

    private fun fetchedAt(c: Context) = load(c)?.optLong("fetched_at") ?: 0L

    /** Starts a background refresh if the cache is older than a few hours (or [force]). Never blocks. */
    fun refreshSoon(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        if (!force && System.currentTimeMillis() - fetchedAt(app) < STALE_MS) return
        CoroutineScope(Dispatchers.IO).launch { refresh(app) }
    }

    /** Asks the server for every slot of the day and stores them together. False if it couldn't. */
    suspend fun refresh(c: Context): Boolean {
        if (lock.isLocked) return false
        return lock.withLock {
            val api = Api(c)
            if (!api.configured()) return@withLock false
            runCatching {
                val slots = api.picks().getJSONObject("slots")   // the whole day in one call
                Prefs(c).p.edit().putString(KEY, JSONObject().put("fetched_at", System.currentTimeMillis())
                    .put("slots", slots).toString()).apply()
                version.value++
            }.onFailure { Log.w("MealLog", "quick picks", it) }.isSuccess
        }
    }

    /** A quiet, inexact alarm every few hours keeps them fresh without the app being opened. */
    fun schedule(c: Context) {
        val pi = PendingIntent.getBroadcast(c, 4242, Intent(c, QuickPicksReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        c.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.RTC,
            System.currentTimeMillis() + EVERY_MS, EVERY_MS, pi)
    }
}

class QuickPicksReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { QuickPicks.refresh(c.applicationContext) } finally { pending.finish() }
        }
    }
}
