package studio.alans.meallog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * The last 24 h of carbs, protein, fat and sat fat against his nutrition-system goals, on the Plan
 * page before he plans (Alan, 17 Nov 2026: catch a lot of sat fat before going for a snack).
 * Cached on the phone so it shows instantly; refreshed each time the page opens.
 */
@Composable
fun NutrientStrip() {
    val c = LocalContext.current
    val p = remember { Prefs(c) }
    var data by remember { mutableStateOf(runCatching { JSONObject(p.p.getString("nutrients", "") ?: "") }.getOrNull()) }
    LaunchedEffect(Unit) {
        runCatching { Api(c).nutrients() }.onSuccess { data = it; p.p.edit().putString("nutrients", it.toString()).apply() }
    }
    val rows = data?.optJSONArray("rows").objects()
    if (rows.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val good = Color(0xFF4CAF7A); val warn = Color(0xFFE0A030); val bad = cs.error; val low = Color(0xFF7FA7D6)
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("LAST 24 HOURS", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { r ->
                    val g = r.optInt("g"); val goal = r.optInt("goal").coerceAtLeast(1)
                    val st = r.optString("status")
                    val col = when (st) { "high" -> if (r.optString("kind") == "maximum") bad else warn; "near" -> warn; "low" -> low; else -> good }
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("name"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        Text("$g g", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = if (st == "high" && r.optString("kind") == "maximum") bad else cs.onSurface)
                        // bar to 1.5x the goal, with a tick at the goal
                        val track = cs.surfaceContainerHighest
                        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 0.dp)) {
                            val full = 1.5f
                            val frac = (g.toFloat() / goal / full).coerceIn(0f, 1f)
                            val r6 = CornerRadius(size.height / 2)
                            drawRoundRect(track, cornerRadius = r6)
                            drawRoundRect(col, size = Size(size.width * frac, size.height), cornerRadius = r6)
                            val x = size.width / full
                            drawLine(cs.onSurface.copy(alpha = 0.6f), Offset(x, -3f), Offset(x, size.height + 3f), 2f)
                        }
                        Text(when (st) { "high" -> if (r.optString("kind") == "maximum") "over ${goal} g" else "plenty"
                            "near" -> "near ${goal} g"; "low" -> "low"; else -> "ok" },
                            style = MaterialTheme.typography.labelSmall, color = if (st == "ok") cs.onSurfaceVariant else col,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            data?.optString("note")?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
