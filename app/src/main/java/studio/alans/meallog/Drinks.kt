package studio.alans.meallog

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One drink he can tap: a past diary food (foodId) or a soft drink the server knows by key. */
data class Drink(val label: String, val name: String, val unit: String, val carbs: Double, val alcoholG: Double,
                 val soft: Boolean, val foodId: String? = null, val key: String? = null) {
    val id: String get() = foodId ?: key ?: label
    fun json(): JSONObject = JSONObject().put("label", label).put("name", name).put("unit", unit).put("carbs", carbs)
        .put("alcohol_g", alcoholG).put("kind", if (soft) "soft" else "alcohol").put("food_id", foodId).put("key", key)

    companion object {
        fun of(j: JSONObject) = Drink(j.optString("label"), j.optString("name"), j.optString("unit", "serving"),
            j.optDouble("carbs", 0.0), j.optDouble("alcohol_g", 0.0), j.optString("kind") == "soft",
            j.optString("food_id").ifBlank { null }?.takeIf { it != "null" }, j.optString("key").ifBlank { null }?.takeIf { it != "null" })
    }
}

/** A drink had tonight. [entryIds] empty while it is still on its way to the diary. */
data class Had(val id: String, val drink: Drink, val count: Int, val at: Long, val entryIds: List<String>,
               val sent: Boolean, val error: String? = null) {
    fun json(): JSONObject = JSONObject().put("id", id).put("drink", drink.json()).put("count", count).put("at", at)
        .put("entry_ids", JSONArray(entryIds)).put("sent", sent).put("error", error)

    companion object {
        fun of(j: JSONObject) = Had(j.getString("id"), Drink.of(j.getJSONObject("drink")), j.optInt("count", 1),
            j.getLong("at"), j.optJSONArray("entry_ids").strings(), j.optBoolean("sent"),
            j.optString("error").ifBlank { null }?.takeIf { it != "null" })
    }
}

/**
 * Drinks mode (Alan, 17 Nov 2026): on a night out, log a pint in one tap and the same again in one
 * more, see the running count against his plan (3 or 4, then soft drinks or 0.0), so the overnight
 * and next-morning glucose trouble eases. Taps show at once and go to the diary in the background,
 * retrying through bad pub signal; drinks logged elsewhere (voice, the web app) join the count.
 * While a night is on (a drink in the last 5 h, not ended), the app opens on Drinks.
 */
object Drinks {
    private const val NIGHT_KEY = "drinks_night"
    private const val CAT_KEY = "drinks_catalogue"
    const val GAP_MS = 5 * 3600_000L
    private val lock = Mutex()

    /** Changes whenever the night changes, so the page redraws. */
    val version = MutableStateFlow(0)

    private fun prefs(c: Context) = Prefs(c).p

    private fun night(c: Context): JSONObject =
        runCatching { JSONObject(prefs(c).getString(NIGHT_KEY, "") ?: "") }.getOrNull() ?: JSONObject()

    private fun save(c: Context, j: JSONObject) {
        prefs(c).edit().putString(NIGHT_KEY, j.toString()).apply()
        version.value++
    }

    fun had(c: Context): List<Had> = night(c).optJSONArray("had").objects().mapNotNull { runCatching { Had.of(it) }.getOrNull() }
        .sortedBy { it.at }

    private fun putHad(c: Context, list: List<Had>, edit: (JSONObject) -> Unit = {}) {
        val j = night(c)
        j.put("had", JSONArray().apply { list.forEach { put(it.json()) } })
        edit(j)
        save(c, j)
    }

    /** His plan: this many alcoholic drinks, then soft ones. */
    fun limit(c: Context): Int = prefs(c).getInt("drinks_limit", 4)
    fun setLimit(c: Context, n: Int) { prefs(c).edit().putInt("drinks_limit", n.coerceIn(1, 8)).apply(); version.value++ }

    /** The drink the big button repeats. */
    fun current(c: Context): Drink? = night(c).optJSONObject("current")?.let { runCatching { Drink.of(it) }.getOrNull() }
    fun select(c: Context, d: Drink) { val j = night(c); j.put("current", d.json()); save(c, j) }

    fun lastAt(c: Context): Long = had(c).maxOfOrNull { it.at } ?: 0L

    /** A night is on: a drink in the last 5 h with alcohol in the night, and he hasn't said he's done. */
    fun active(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        val h = had(c)
        val last = h.maxOfOrNull { it.at } ?: return false
        return now - last < GAP_MS && h.any { !it.drink.soft } && night(c).optLong("ended", 0) < last
    }

    fun alcoholCount(list: List<Had>) = list.filter { !it.drink.soft }.sumOf { it.count }
    fun total(list: List<Had>) = list.sumOf { it.count }

    /** His drinks, cached so the buttons show with no signal. */
    fun catalogue(c: Context): List<Drink> =
        runCatching { JSONObject(prefs(c).getString(CAT_KEY, "") ?: "") }.getOrNull()
            ?.optJSONArray("drinks").objects().map(Drink::of)

    /** Adds [n] of [d] now. Shows at once; the diary write happens in the background. */
    fun add(c: Context, d: Drink, n: Int = 1) {
        if (n <= 0) return
        val now = System.currentTimeMillis()
        var list = had(c)
        // a new night: the old one's drinks go
        if (list.isNotEmpty() && (now - list.maxOf { it.at } > GAP_MS || night(c).optLong("ended", 0) >= list.maxOf { it.at }))
            list = emptyList()
        val before = alcoholCount(list)
        list = list + Had(UUID.randomUUID().toString(), d, n, now, emptyList(), false)
        putHad(c, list) { j ->
            j.put("ended", 0)
            j.put("current", d.json())
            // he just reached his plan: the button switches to a soft drink for the next one
            if (!d.soft && before < limit(c) && alcoholCount(list) >= limit(c)) softPick(c)?.let { j.put("current", it.json()) }
        }
        HeadingHome.stillOut(c)   // a drink after "heading home": still out, ask again later
        HeadingHome.arm(c)
        flush(c)
    }

    /** The soft drink to suggest: the last soft one he had, else the first soft one in his list. */
    fun softPick(c: Context): Drink? =
        had(c).lastOrNull { it.drink.soft }?.drink ?: catalogue(c).firstOrNull { it.soft }

    /** Takes the last drink off (and out of the diary if it got there). */
    fun undoLast(c: Context) {
        val list = had(c)
        val last = list.lastOrNull() ?: return
        putHad(c, list.dropLast(1))
        if (last.entryIds.isNotEmpty()) {
            val app = c.applicationContext
            CoroutineScope(Dispatchers.IO).launch { runCatching { Api(app).undo(last.entryIds) } }
        }
    }

    fun end(c: Context) { val j = night(c); j.put("ended", System.currentTimeMillis()); save(c, j); HeadingHome.disarm(c) }

    /** Sends whatever hasn't reached the diary yet. Safe to call often. */
    fun flush(c: Context) {
        val app = c.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                val api = Api(app)
                if (!api.configured()) return@withLock
                for (h in had(app).filter { !it.sent }) {
                    val r = runCatching { api.drink(h.drink, h.count, h.at) }
                    val now = had(app)
                    if (now.none { it.id == h.id }) {   // undone while it was on its way
                        r.getOrNull()?.takeIf { it.isNotEmpty() }?.let { runCatching { api.undo(it) } }
                        continue
                    }
                    putHad(app, now.map {
                        if (it.id != h.id) it
                        else r.fold({ ids -> it.copy(entryIds = ids, sent = true, error = null) },
                            { e -> it.copy(error = e.message ?: "not sent") })
                    })
                    if (r.isFailure) break   // no signal: try again later, in order
                }
            }
        }
    }

    fun pending(c: Context) = had(c).any { !it.sent }

    /** Fetches his drinks and tonight's diary; drinks logged elsewhere join the count. Returns active(). */
    suspend fun refresh(c: Context): Boolean {
        val app = c.applicationContext
        val j = Api(app).drinks()
        prefs(app).edit().putString(CAT_KEY, JSONObject().put("drinks", j.optJSONArray("drinks") ?: JSONArray()).toString()).apply()
        lock.withLock {
            val tonight = j.optJSONObject("tonight")?.optJSONArray("drinks").objects()
            var list = had(app)
            val known = list.flatMap { it.entryIds }.toSet()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            val added = tonight.filter { it.optString("id") !in known }.mapNotNull { e ->
                val at = runCatching { fmt.parse(e.optString("at"))!!.time }.getOrNull() ?: return@mapNotNull null
                // one we sent that the server answered without ids: same minute, same name
                if (list.any { kotlin.math.abs(it.at - at) < 90_000 && it.drink.name.equals(e.optString("name"), true) && it.entryIds.isEmpty() }) return@mapNotNull null
                Had(e.optString("id"), Drink(e.optString("label"), e.optString("name"), "serving", e.optDouble("carbs", 0.0),
                    e.optDouble("alcohol_g", 0.0), e.optString("kind") == "soft"), e.optInt("count", 1), at,
                    listOf(e.optString("id")), true)
            }
            if (added.isNotEmpty()) {
                if (list.isNotEmpty() && added.minOf { it.at } - list.maxOf { it.at } > GAP_MS) list = emptyList()
                list = (list + added).sortedBy { it.at }
                putHad(app, list)
            }
            // a drink in the diary that's gone from it (deleted elsewhere) leaves the count
            val ids = tonight.map { it.optString("id") }.toSet()
            val since = tonight.firstOrNull()?.optString("at")?.let { runCatching { fmt.parse(it)!!.time }.getOrNull() }
            if (since != null) {
                val kept = had(app).filter { !(it.sent && it.entryIds.isNotEmpty() && it.at >= since && it.entryIds.none { id -> id in ids }) }
                if (kept.size != had(app).size) putHad(app, kept)
            }
        }
        val homeFor = prefs(app).getLong("home_for", 0)
        if (homeFor != 0L && homeFor != lastAt(app) && lastAt(app) > homeFor) HeadingHome.stillOut(app)
        HeadingHome.sync(app)
        version.value++
        return active(app)
    }

    /** A drink just logged some other way (Log page): if it's alcohol, the night starts and the app goes to Drinks. */
    fun looksLikeAlcohol(name: String): Boolean =
        Regex("\\b(guinness|beer|ale|stout|lager|cider|wine|prosecco|pints?|smithwick|heineken|moretti|ipa|gin|vodka|whiske?y|rum|cocktail)\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(name) && !Regex("0\\.0|alcohol.free|non.alcoholic", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
