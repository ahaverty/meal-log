package studio.alans.meallog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What did you eat: a few guesses from the diary (time of day + carbs), type-ahead over past foods,
 * free text as a last resort. at = the bolus (or now), carbs = the bolus carbs (null when just opened).
 * onDone(true) after logging or "nothing to log", onDone(false) on the X (not now).
 *
 * Sheet layout (12 Nov, after Alan hit "Not now" where Submit usually sits): the header and the
 * action bar are pinned and only the middle scrolls. The filled Log button is ALWAYS bottom right,
 * and nothing that skips or postpones ever goes there. Not now = the X or Back only.
 */
@Composable
fun MealPanel(at: Long, carbs: Double?, units: Double?, sheet: Boolean, onDone: (Boolean) -> Unit) {
    val c = LocalContext.current
    val api = remember { Api(c) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var guesses by remember { mutableStateOf<Guesses?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Food>>(emptyList()) }
    val plate = remember { mutableStateListOf<Food>() }
    var done by remember { mutableStateOf<LogResult?>(null) }
    var queuedText by remember { mutableStateOf<String?>(null) }
    var skipping by remember { mutableStateOf(false) }
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

    val pr = remember { Prefs(c) }
    var tapped by remember { mutableStateOf<List<Food>>(emptyList()) }
    fun markLogged() { if (at == pr.lastBolusAt) pr.loggedBolus = at; Reminders.mealLogged(c, at) }

    fun log(items: List<Food>, extra: String? = null) {
        busy = true
        tapped = items
        keyboard?.hide()
        scope.launch {
            runCatching { api.log(items, at).also { if (extra != null) api.text(extra, at) } }
                .onSuccess { done = it; plate.clear(); query = ""; if (it.entryIds.isNotEmpty() || it.problems.isEmpty()) markLogged() }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val typed = query.trim()
    val canType = typed.length >= 2
    fun logTyped() {
        if (!canType) return
        keyboard?.hide()
        if (plate.isNotEmpty()) { log(plate.toList(), typed); return }  // never drop the plate or the words
        busy = true
        sendText(api, scope, typed, at, { busy = false; queuedText = typed; query = "" }, { busy = false; error = it })
    }
    fun primary() { if (plate.isNotEmpty()) log(plate.toList(), typed.takeIf { canType }) else logTyped() }
    // Back while "Nothing logged" counts down confirms it (it must not turn into Not now + a nag).
    BackHandler(enabled = skipping) {
        skipping = false
        scope.launch { runCatching { api.skip(at) }; Reminders.mealLogged(c, at); onDone(true) }
    }
    val settled = done != null || queuedText != null || skipping

    Column(Modifier.fillMaxWidth()) {
        if (sheet) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("What are you eating?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val sub = buildList {
                        if (carbs != null) add("${carbs.toInt()} g")
                        if (units != null && units > 0) add("${"%.1f".format(units)} U")
                        add("at ${Api.hhmm(at)}")
                    }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!skipping) IconButton(onClick = { onDone(false) }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Not now") }
            }
        }

        Column((if (sheet) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier)
            .fillMaxWidth().padding(16.dp)) {
            if (!sheet) {
                Text(if (carbs != null) "Usual for ${carbs.toInt()} g around ${Api.hhmm(at)}" else "Usual around ${Api.hhmm(at)}",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
            }

            val d = done
            if (d != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        val names = d.logged.ifEmpty { tapped.map { "${it.name.substringBefore(" (")} (${fmt(it.quantity)} ${it.unit})" } }
                        Text(if (d.problems.isEmpty() || d.entryIds.isNotEmpty()) "Logged at ${Api.hhmm(at)}" else "Not logged",
                            fontWeight = FontWeight.SemiBold)
                        Text(names.joinToString(", "))
                        if (d.entryIds.isEmpty()) d.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            if (d.entryIds.isNotEmpty()) TextButton(onClick = {
                                scope.launch {
                                    runCatching { api.undo(d.entryIds) }.onFailure { error = it.message }
                                    if (at == pr.loggedBolus) pr.loggedBolus = 0
                                    done = null
                                    reload++
                                }
                            }) { Text("Undo") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { done = null; if (sheet) onDone(true) else reload++ }) { Text("Done") }
                        }
                    }
                }
                if (sheet) LaunchedEffect(d) { delay(6000); onDone(true) }
                return@Column
            }
            queuedText?.let { t ->
                LaunchedEffect(t) { markLogged() }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text("Got it: \"$t\". It lands in your diary at ${Api.hhmm(at)} in about 20 s.", Modifier.padding(16.dp))
                }
                LaunchedEffect(t) { delay(3500); if (sheet) onDone(true) else { queuedText = null; reload++ } }
                return@Column
            }
            if (skipping) {
                // "Nothing to log" waits 4 s with Undo before it tells the server, so a misfire costs nothing.
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Nothing logged for this bolus", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { skipping = false }) { Text("Undo") }
                    }
                }
                LaunchedEffect(Unit) {
                    delay(4000)
                    runCatching { api.skip(at) }
                    Reminders.mealLogged(c, at)
                    onDone(true)
                }
                return@Column
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { reload++ }) { Text("Try again") }
            }

            // A meal planned in the Plan tab in the last 3 h comes first.
            if (sheet && pr.planSummary.isNotBlank() && System.currentTimeMillis() - pr.planAt < 3 * 3600_000L) {
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !busy) {
                        val t = pr.planSummary
                        pr.planSummary = ""
                        keyboard?.hide()
                        busy = true
                        sendText(api, scope, t, at, { busy = false; queuedText = t }, { busy = false; error = it })
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

            // Guesses: one tap logs. Placeholders hold their space while loading so nothing jumps.
            val g = guesses
            if (g == null && error == null) {
                repeat(2) { i ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(60.dp), shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        if (i == 0) Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Looking at what you usually have", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            g?.guesses?.take(4)?.forEach { s ->
                val mine = busy && tapped == s.items
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !busy) { log(s.items) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer
                        .copy(alpha = if (busy && !mine) 0.45f else 1f)),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(s.items.joinToString(", ") { "${fmt(it.quantity)} ${it.unit}" } +
                                    if (s.times > 1) " · had ${s.times} times" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (mine) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text("${s.carbs} g", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Something else: type-ahead over past foods builds a plate; the last row logs the words as typed.
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Something else? Type a food") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (plate.isEmpty() && canType) logTyped() else keyboard?.hide() }),
            )
            matches.take(6).forEach { f ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { plate.add(f); query = "" }
                    .padding(vertical = 12.dp, horizontal = 4.dp)) {
                    Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${fmt(f.quantity)} ${f.unit} · ${f.carbs.toInt()} g", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            if (canType) {
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { logTyped() }.padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text("“$typed”", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (plate.isEmpty()) "estimate" else "estimate, with the plate", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

        }

        // Action bar. Left: the quiet way out. Right, always in the same place: the filled Log.
        if (!settled && (sheet || plate.isNotEmpty() || canType)) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (plate.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        plate.forEach { f ->
                            InputChip(selected = true, onClick = { plate.remove(f) },
                                label = { Text("${f.name.substringBefore(" (")} ${f.carbs.toInt()} g", maxLines = 1) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sheet && plate.isEmpty()) {
                        OutlinedButton(onClick = { keyboard?.hide(); skipping = true }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("Nothing to log") }
                    }
                    Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                    Button(onClick = { primary() }, enabled = !busy && (plate.isNotEmpty() || canType),
                        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 120.dp, max = 220.dp)) {
                        Text(when {
                            plate.isNotEmpty() && canType -> "Log ${plate.size + 1} items"
                            plate.isNotEmpty() -> "Log ${plate.size} · ${plate.sumOf { it.carbs }.toInt()} g"
                            canType -> "Log “$typed”"
                            else -> "Log"
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }

        // Log tab only: today's diary sits under the action bar so Log never ends up off screen.
        if (!sheet) guesses?.today?.takeIf { it.isNotEmpty() }?.let { today ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
