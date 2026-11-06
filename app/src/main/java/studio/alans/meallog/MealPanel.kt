package studio.alans.meallog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What did you eat: a few guesses from the diary (time of day + carbs), type-ahead over past foods,
 * free text as a last resort. at = the bolus (or now), carbs = the bolus carbs (null when just opened).
 * onDone(true) after logging or "nothing to log", onDone(false) on "not now".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealPanel(at: Long, carbs: Double?, units: Double?, sheet: Boolean, onDone: (Boolean) -> Unit) {
    val c = LocalContext.current
    val api = remember { Api(c) }
    val scope = rememberCoroutineScope()
    var guesses by remember { mutableStateOf<Guesses?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Food>>(emptyList()) }
    val plate = remember { mutableStateListOf<Food>() }
    var done by remember { mutableStateOf<LogResult?>(null) }
    var queuedText by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(at, carbs, reload) {
        if (!api.configured()) { error = "Set the server in Settings."; return@LaunchedEffect }
        error = null
        runCatching { api.guesses(at, carbs) }.onSuccess { guesses = it }.onFailure { error = it.message }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { matches = emptyList(); return@LaunchedEffect }
        delay(250)
        runCatching { api.foods(q, at) }.onSuccess { matches = it }
    }

    fun log(items: List<Food>) {
        busy = true
        scope.launch {
            runCatching { api.log(items, at) }
                .onSuccess { done = it; plate.clear(); query = "" }
                .onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (sheet) "What are you eating?" else "Log a meal",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val sub = buildList {
                    if (carbs != null) add("${carbs.toInt()} g")
                    if (units != null && units > 0) add("${"%.1f".format(units)} U")
                    add("at ${Api.hhmm(at)}")
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sheet) IconButton(onClick = { onDone(false) }) { Icon(Icons.Default.Close, "Not now") }
        }
        Spacer(Modifier.height(12.dp))

        val d = done
        if (d != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("Logged at ${Api.hhmm(at)}", fontWeight = FontWeight.SemiBold)
                    Text(d.logged.joinToString(", ").ifBlank { "Nothing new" })
                    d.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { api.undo(d.entryIds) }.onFailure { error = it.message }
                                done = null
                                reload++
                            }
                        }) { Text("Undo") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { done = null; if (sheet) onDone(true) else reload++ }) { Text("OK") }
                    }
                }
            }
            if (sheet) LaunchedEffect(d) { delay(6000); onDone(true) }
            return@Column
        }
        queuedText?.let { t ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Sent: \"$t\". It's logged at ${Api.hhmm(at)} in about 20 s.", Modifier.padding(16.dp))
            }
            LaunchedEffect(t) { delay(3500); if (sheet) onDone(true) else { queuedText = null; reload++ } }
            return@Column
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { reload++ }) { Text("Try again") }
        }

        // A meal planned in the Plan tab in the last 3 h comes first.
        val pr = remember { Prefs(c) }
        if (sheet && pr.planSummary.isNotBlank() && System.currentTimeMillis() - pr.planAt < 3 * 3600_000L) {
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !busy) {
                    val t = pr.planSummary
                    pr.planSummary = ""
                    sendText(api, scope, t, at, { queuedText = t }, { error = it })
                },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pr.planSummary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("You planned this at ${Api.hhmm(pr.planAt)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (pr.planCarbs >= 0) Text("${pr.planCarbs.toInt()} g", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Guesses: one tap logs.
        val g = guesses
        if (g == null && error == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Looking at what you usually have…")
            }
        }
        g?.guesses?.take(4)?.forEach { s ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !busy) { log(s.items) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s.items.joinToString(", ") { "${fmt(it.quantity)} ${it.unit}" } +
                                if (s.times > 1) " · had ${s.times} times" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${s.carbs} g", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Something else: type-ahead over past foods, builds a plate.
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Something else? Type a food") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val t = query.trim()
                if (t.length >= 2 && matches.isEmpty()) sendText(api, scope, t, at, { queuedText = t; query = "" }, { error = it })
            }),
        )
        matches.take(6).forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { plate.add(f); query = "" }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmt(f.quantity)} ${f.unit} · ${f.carbs.toInt()} g", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        val typed = query.trim()
        if (typed.length >= 2) {
            TextButton(onClick = { sendText(api, scope, typed, at, { queuedText = typed; query = "" }, { error = it }) }) {
                Text("Log \"$typed\" as typed (estimated)", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (plate.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                plate.forEachIndexed { i, f ->
                    InputChip(selected = true, onClick = { plate.removeAt(i) },
                        label = { Text("${f.name.substringBefore(" (")} ${f.carbs.toInt()} g", maxLines = 1) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
                }
            }
            Button(onClick = { log(plate.toList()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Log ${plate.size} food${if (plate.size > 1) "s" else ""} (${plate.sumOf { it.carbs }.toInt()} g)")
            }
        }

        if (sheet) {
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = {
                    scope.launch { runCatching { api.skip(at) }; onDone(true) }
                }) { Text("Nothing to log") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDone(false) }) { Text("Not now") }
            }
        } else {
            g?.today?.takeIf { it.isNotEmpty() }?.let { today ->
                Spacer(Modifier.height(16.dp))
                Text("Today", style = MaterialTheme.typography.titleSmall)
                today.forEach { e ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(e.time, Modifier.width(52.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(e.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${e.carbs.toInt()} g", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun sendText(api: Api, scope: kotlinx.coroutines.CoroutineScope, t: String, at: Long,
                     ok: () -> Unit, fail: (String?) -> Unit) {
    scope.launch { runCatching { api.text(t, at) }.onSuccess { ok() }.onFailure { fail(it.message) } }
}

fun fmt(q: Double): String = if (q == Math.floor(q)) q.toInt().toString() else "%.2f".format(q).trimEnd('0').trimEnd('.')
