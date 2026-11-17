package studio.alans.meallog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Amber = Color(0xFFE0A030)
private val Soft = Color(0xFF5AA9E6)

/**
 * Tonight's drinks: one big button repeats the last drink, a row of glasses is the count and the
 * plan (his N drinks, then soft ones), and tapping a glass further along fills up to it, so missed
 * logs catch up in one tap. Everything shows at once; the diary write happens behind.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrinksScreen() {
    val c = LocalContext.current
    Drinks.version.collectAsState().value
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var err by remember { mutableStateOf<String?>(null) }
    var undoArmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { Drinks.refresh(c) }.onFailure { err = it.message }
        while (true) {   // keep the clock fresh and retry anything stuck on bad signal
            delay(20_000); now = System.currentTimeMillis()
            if (Drinks.pending(c)) Drinks.flush(c)
        }
    }
    LaunchedEffect(undoArmed) { if (undoArmed) { delay(4000); undoArmed = false } }

    val cs = MaterialTheme.colorScheme
    val active = Drinks.active(c, now)
    val had = if (active || Drinks.lastAt(c) > now - Drinks.GAP_MS) Drinks.had(c) else emptyList()
    val limit = Drinks.limit(c)
    val alcohol = Drinks.alcoholCount(had)
    val total = Drinks.total(had)
    val cat = Drinks.catalogue(c)
    val current = Drinks.current(c)?.takeIf { had.isNotEmpty() } ?: cat.firstOrNull { !it.soft }
    // each glass in order, by what filled it
    val glasses = had.flatMap { h -> List(h.count) { h.drink.soft } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The count
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text("$alcohol", fontSize = 64.sp, fontWeight = FontWeight.Light,
                color = if (alcohol > limit) cs.error else cs.onSurface)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 12.dp)) {
                Text(if (alcohol == 1) "drink of $limit" else "drinks of $limit", style = MaterialTheme.typography.titleMedium)
                val soft = total - alcohol
                val carbs = had.sumOf { it.drink.carbs * it.count }
                Text(listOfNotNull(if (soft > 0) "+ $soft soft" else null, "${carbs.toInt()} g carbs",
                    had.firstOrNull()?.let { "since ${Api.hhmm(it.at)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        Text(planLine(alcohol, limit), style = MaterialTheme.typography.bodyLarge,
            color = when { alcohol > limit -> cs.error; alcohol == limit -> Soft; else -> cs.onSurfaceVariant },
            fontWeight = if (alcohol >= limit) FontWeight.SemiBold else FontWeight.Normal)

        // The glasses: filled = had (amber beer, blue soft), outlined = the plan still to come.
        val slots = maxOf(limit + 2, glasses.size + 1)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..slots) {
                val filled = i <= glasses.size
                val soft = if (filled) glasses[i - 1] else i > limit
                Glass(filled, soft, i, last = i == limit) {
                    if (!filled) {
                        // fills up to this glass: the missed ones count too. Past the plan, a soft drink.
                        val n = i - glasses.size
                        val d = (if (i > limit && alcohol >= limit) Drinks.softPick(c) else null) ?: current
                        if (d != null) Drinks.add(c, d, n)
                    }
                }
            }
        }

        // The big repeat button
        if (current != null) {
            Button(onClick = { Drinks.add(c, current) }, modifier = Modifier.fillMaxWidth().height(76.dp),
                shape = RoundedCornerShape(22.dp),
                colors = if (current.soft) ButtonDefaults.buttonColors(containerColor = Soft) else ButtonDefaults.buttonColors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((if (had.isEmpty()) "+1 " else "Another ") + current.label, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("${current.carbs.toInt()} g carbs" + if (current.alcoholG > 0) "" else " · no alcohol",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val pend = had.count { !it.sent }
            Text(when {
                pend > 0 && had.any { it.error != null } -> "$pend not in the diary yet, retrying"
                pend > 0 -> "Saving…"
                had.isNotEmpty() -> "In the diary"
                else -> ""
            }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (had.isNotEmpty()) {
                if (undoArmed) TextButton(onClick = { Drinks.undoLast(c); undoArmed = false }) { Text("Tap again to remove ${had.last().drink.label}") }
                else TextButton(onClick = { undoArmed = true }) { Text("Undo last") }
            }
        }

        // Pick a different drink: one tap logs it and it becomes the repeat.
        val alc = cat.filter { !it.soft }
        val soft = cat.filter { it.soft }
        if (alc.isNotEmpty()) {
            Label("Something else")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                alc.forEach { d -> DrinkChip(d, d.id == current?.id) { Drinks.add(c, d) } }
            }
        }
        Label(if (alcohol >= limit) "Switch to" else "Soft and 0.0")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            soft.forEach { d -> DrinkChip(d, d.id == current?.id) { Drinks.add(c, d) } }
        }
        if (cat.isEmpty()) Text(err ?: "Loading your drinks…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

        // The plan
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My plan: $limit, then soft", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { Drinks.setLimit(c, limit - 1) }) { Icon(painterResource(R.drawable.ic_minus), "One fewer") }
            IconButton(onClick = { Drinks.setLimit(c, limit + 1) }) { Text("+", fontSize = 22.sp) }
        }
        if (had.isNotEmpty()) {
            OutlinedButton(onClick = { Drinks.end(c) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Done for tonight")
            }
            Text("Alcohol can bring glucose down overnight and into the morning.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun planLine(alcohol: Int, limit: Int): String = when {
    alcohol == 0 -> "Plan: $limit drinks, then soft drinks or 0.0."
    alcohol < limit - 1 -> "${limit - alcohol} to go, then switch."
    alcohol == limit - 1 -> "Next one is your last. Then soft drinks."
    alcohol == limit -> "That's your $limit. Soft drinks or 0.0 from here."
    else -> "${alcohol - limit} over the plan. Switch now."
}

@Composable
private fun Label(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
private fun DrinkChip(d: Drink, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.secondaryContainer else cs.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(d.label, style = MaterialTheme.typography.titleSmall)
            Text("${d.carbs.toInt()} g" + if (d.alcoholG > 0) "" else if (d.carbs >= 10) " · counts for insulin" else "",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** A pint glass. Filled = had; outlined = the plan; blue = soft. */
@Composable
private fun Glass(filled: Boolean, soft: Boolean, n: Int, last: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val col = if (soft) Soft else Amber
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !filled, onClick = onClick).padding(4.dp)) {
        Canvas(Modifier.size(width = 34.dp, height = 46.dp)) {
            val w = size.width; val h = size.height
            val glass = Path().apply {
                moveTo(0f, 0f); lineTo(w, 0f); lineTo(w * 0.86f, h); lineTo(w * 0.14f, h); close()
            }
            if (filled) {
                drawPath(glass, col)
                // the head
                val head = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w * 0.975f, h * 0.18f); lineTo(w * 0.025f, h * 0.18f); close() }
                drawPath(head, if (soft) Color.White.copy(alpha = 0.35f) else Color(0xFFF3E6C8))
            } else {
                drawPath(glass, col.copy(alpha = if (soft) 0.55f else 0.8f), style = Stroke(2.dp.toPx()))
            }
            if (last && !filled) drawLine(col, Offset(w * 0.2f, h * 0.55f), Offset(w * 0.8f, h * 0.55f), 2.dp.toPx())
        }
        Text("$n", style = MaterialTheme.typography.labelSmall, color = if (filled) cs.onSurface else cs.onSurfaceVariant)
    }
}
