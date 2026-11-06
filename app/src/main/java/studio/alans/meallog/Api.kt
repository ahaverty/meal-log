package studio.alans.meallog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Food(val foodId: String, val name: String, val quantity: Double, val unit: String, val carbs: Double) {
    fun json(): JSONObject = JSONObject().put("food_id", foodId).put("name", name)
        .put("quantity", quantity).put("unit", unit).put("carbs", carbs)

    companion object {
        fun of(j: JSONObject) = Food(j.getString("food_id"), j.getString("name"), j.optDouble("quantity", 1.0),
            j.optString("unit", "serving"), j.optDouble("carbs", 0.0))
    }
}

data class Guess(val id: String, val label: String, val carbs: Int, val times: Int, val items: List<Food>)
data class Logged(val time: String, val name: String, val carbs: Double)
data class Guesses(val mealType: String, val guesses: List<Guess>, val today: List<Logged>)
data class Plan(val text: String, val session: String?, val bolusInMin: Int?, val carbs: Double?, val summary: String)
data class LogResult(val logged: List<String>, val entryIds: List<String>, val problems: List<String>)

/** Your meal server (see the README for the API), optionally behind Cloudflare Access. */
class Api(c: Context) {
    private val prefs = Prefs(c)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    fun configured() = prefs.url.isNotBlank() && prefs.token.isNotBlank()

    private fun req(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val u = (prefs.url + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> u.addQueryParameter(k, v) }
        val b = Request.Builder().url(u.build()).header("Authorization", "Bearer ${prefs.token}")
        if (prefs.cfId.isNotBlank()) {
            b.header("CF-Access-Client-Id", prefs.cfId).header("CF-Access-Client-Secret", prefs.cfSecret)
        }
        return b
    }

    private suspend fun call(r: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Server said ${resp.code}" +
                    (runCatching { JSONObject(body).optString("error") }.getOrNull()?.let { ": $it" } ?: ""))
            if (!body.trimStart().startsWith("{")) throw RuntimeException("Not signed in to the nutrition system")
            JSONObject(body)
        }
    }

    private fun post(path: String, j: JSONObject) =
        req(path).post(j.toString().toRequestBody("application/json".toMediaType())).build()

    suspend fun guesses(at: Long, carbs: Double?): Guesses {
        val q = mutableMapOf("at" to stamp(at))
        if (carbs != null) q["carbs"] = carbs.toString()
        val j = call(req("/suggest", q).build())
        val gs = j.optJSONArray("suggestions").objects().map { s ->
            Guess(s.getString("id"), s.getString("label"), s.optInt("carbs"), s.optInt("times"),
                s.getJSONArray("items").objects().map(Food::of))
        }
        val today = j.optJSONArray("today").objects().map {
            Logged(it.optString("time"), it.optString("name"), it.optDouble("carbs", 0.0))
        }
        return Guesses(j.optString("meal_type"), gs, today)
    }

    suspend fun foods(q: String, at: Long): List<Food> =
        call(req("/foods", mapOf("q" to q, "at" to stamp(at))).build()).optJSONArray("foods").objects().map(Food::of)

    suspend fun log(items: List<Food>, at: Long): LogResult {
        val j = call(post("/log", JSONObject().put("at", stamp(at))
            .put("items", JSONArray().apply { items.forEach { put(it.json()) } })))
        return LogResult(j.optJSONArray("logged").strings(), j.optJSONArray("entry_ids").strings(),
            j.optJSONArray("problems").strings())
    }

    suspend fun text(text: String, at: Long) {
        call(post("/text", JSONObject().put("text", text).put("at", stamp(at))))
    }

    suspend fun undo(ids: List<String>) {
        call(post("/undo", JSONObject().put("entry_ids", JSONArray(ids))))
    }

    /** Starts a meal plan (text and/or a JPEG); session continues an earlier one. Returns the job id. */
    suspend fun plan(text: String, jpeg: ByteArray?, session: String?): String {
        val j = JSONObject().put("text", text)
        if (jpeg != null) j.put("image_b64", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
        if (session != null) j.put("session", session)
        return call(post("/plan", j)).getString("job")
    }

    /** Waits for a plan job (it runs Claude, 20-60 s). */
    suspend fun planResult(job: String): Plan {
        while (true) {
            val j = call(req("/plan/$job").build())
            when (j.optString("state")) {
                "done" -> {
                    val x = j.optJSONObject("extras") ?: JSONObject()
                    return Plan(j.optString("text"), j.optString("session").ifBlank { null },
                        if (x.isNull("bolus_in_min")) null else x.optInt("bolus_in_min"),
                        if (x.isNull("carbs")) null else x.optDouble("carbs"), x.optString("summary"))
                }
                "failed" -> throw RuntimeException(j.optString("error", "The planner failed"))
                "unknown" -> throw RuntimeException("The server restarted, try again")
            }
            kotlinx.coroutines.delay(2500)
        }
    }

    suspend fun skip(at: Long) {
        call(post("/skip", JSONObject().put("at", stamp(at))))
    }

    companion object {
        fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
        fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
