package studio.alans.meallog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The steps the - and + on the Eating row go through; any other time comes from the clock picker. */
private val EAT_STEPS = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)

/**
 * "I'm about to have this": text and/or a photo of the plate or menu -> the CamAPS entry from the
 * server's planner. The answer arms its own reminders: the bolus (cancelled by the bolus itself)
 * and any later step (Boost, slow carbs). Each shows with an X. The plan is kept until an hour
 * after its eating time (Alan, 7 Nov: an old meal shouldn't greet him at the next one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(start: PlanStart?) {
    val c = LocalContext.current
    val api = remember { Api(c) }
    val p = remember { Prefs(c) }
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(TextFieldValue(start?.text ?: "")) }
    val text = field.text
    val focus = remember { FocusRequester() }
    var eatIn by remember { mutableStateOf(0) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var plan by remember {
        mutableStateOf(runCatching {
            if (p.planJson.isNotBlank()) Plan.of(JSONObject(p.planJson)).takeIf { fresh(it) } else null
        }.getOrNull())
    }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<String?>(null) }
    var stages by remember { mutableStateOf<List<String>>(emptyList()) }
    var startedAt by remember { mutableStateOf(0L) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Voice: the words land after whatever was already typed, live as he talks.
    var voiceBase by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoice { said, partial ->
        val base = voiceBase ?: field.text.trimEnd().also { voiceBase = it }
        val t = listOf(base, said).filter { it.isNotBlank() }.joinToString(" ")
        field = TextFieldValue(t, TextRange(t.length))
        if (!partial) voiceBase = null
    }
    var error by remember { mutableStateOf<String?>(null) }
    Reminders.version.collectAsState().value   // redraw when reminders change

    // Full-size photo through a FileProvider (the preview contract only gives a thumbnail, too small to judge portions).
    val shot = remember {
        val f = java.io.File(c.cacheDir, "photos").apply { mkdirs() }.resolve("plan.jpg")
        androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.files", f) to f
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo = decode(shot.second)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) photo = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }
    LaunchedEffect(start) { if (start?.camera == true) { plan = null; camera.launch(shot.first) } }

    fun newMeal() {
        plan = null; p.planJson = ""; p.planSummary = ""; p.planCarbs = -1f
        field = TextFieldValue(""); photo = null; error = null; eatIn = 0
        scope.launch { kotlinx.coroutines.delay(150); runCatching { focus.requestFocus() } }
    }
    // Let an old plan go by itself, but never under his fingers (typing a reply or waiting on one).
    LaunchedEffect(plan) {
        while (plan != null) {
            kotlinx.coroutines.delay(60_000)
            val pl = plan ?: break
            if (!fresh(pl) && !busy && field.text.isBlank() && photo == null) { plan = null; p.planJson = "" }
        }
    }

    fun send(said: String? = null) {
        val t = (said ?: text).trim()
        val img = if (said != null) null else photo
        if (t.isEmpty() && img == null) return
        val previous = plan
        busy = true; error = null
        asking = t.ifEmpty { "Photo" }
        stages = emptyList(); startedAt = System.currentTimeMillis()
        val asked = if (previous == null) asking!! else previous.asked + "\n" + asking!!
        job = scope.launch {
            runCatching {
                api.planResult(api.plan(t, img?.let(::jpeg), previous?.session, if (previous == null) eatIn
                    else previous.takeIf { it.shiftMs != 0L }?.let { ((it.eatAt - System.currentTimeMillis()) / 60_000L).toInt().coerceIn(0, 240) }),
                    asked) { stages = it }
            }.onSuccess { pl ->
                if (previous != null) Reminders.open(c).filter { it.setAt >= previous.madeAt - 5000 }.forEach { Reminders.cancel(c, it.id) }
                arm(c, pl)
                plan = pl; field = TextFieldValue(""); photo = null
                p.planJson = pl.json().toString(); p.planAt = pl.madeAt
                p.planSummary = pl.summary; p.planCarbs = (pl.carbs ?: -1.0).toFloat()
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
            busy = false; asking = null; job = null
        }
    }

    // He started cooking and is eating later than he said: the plan and its reminders move with him.
    fun shift(ms: Long) {
        val pl = plan ?: return
        Reminders.shift(c, Reminders.open(c).filter { it.setAt >= pl.madeAt - 5000 && it.meal == 0L }.map { it.id }, ms)
        val n = pl.copy(shiftMs = pl.shiftMs + ms)
        plan = n; p.planJson = n.json().toString()
    }
    fun notEating() {
        val pl = plan ?: return
        Reminders.open(c).filter { it.setAt >= pl.madeAt - 5000 && it.meal == 0L }.forEach { Reminders.cancel(c, it.id) }
        newMeal()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val pl = plan
        if (pl == null) {
            if (!busy) {
                Text(askFor(System.currentTimeMillis() + eatIn * 60_000L), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
            }
        } else {
            if (pl.menu != null) MenuPicks(pl, onPick = { send("I'll have: $it") }, onChange = {
                // his own version of a pick: the order in the box, cursor at the end, to edit and Send
                val t = "I'll have: $it, but "
                field = TextFieldValue(t, TextRange(t.length))
                scope.launch { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
            })
            else PlanResult(pl, onShift = ::shift, onNotEating = ::notEating)
            Spacer(Modifier.height(12.dp))
        }

        if (busy) {
            PlanProgress(asking.orEmpty(), stages, startedAt, photoSent = asking == "Photo" || stages.firstOrNull() == "Looking at the photo",
                reply = plan != null, onCancel = { job?.cancel() })
            return@Column
        }
        photo?.let { b ->
            Row(verticalAlignment = Alignment.Top) {
                Image(b.asImageBitmap(), null, Modifier.weight(1f).heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
                IconButton(onClick = { photo = null }) { Icon(Icons.Default.Close, "Remove photo") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (pl != null) {
            Text(if (pl.menu != null) "Something else?" else "Change this plan", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        }
        val canSend = text.isNotBlank() || photo != null
        val cs = MaterialTheme.colorScheme
        // One composer card: the words, then photo / mic on the left and Plan it on the right.
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column {
                TextField(field, { field = it; voiceBase = null }, Modifier.fillMaxWidth().focusRequester(focus),
                    minLines = if (pl == null) 2 else 1, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    // the keyboard's Enter key is Send (Alan, 11 Nov: no more closing the keyboard to find the button)
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
                    placeholder = {
                        Text(when {
                            voice.listening -> "Listening…"
                            pl == null -> "A chicken sandwich and Taytos, or a restaurant"
                            pl.menu != null -> "What you'll have, or what you don't fancy"
                            else -> "Answer or change it, like a 30g bag"
                        }, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
                    },
                    trailingIcon = if (text.isNotEmpty() && !voice.listening) {
                        { IconButton(onClick = { field = TextFieldValue(""); voiceBase = null }) { Icon(Icons.Default.Close, "Clear") } }
                    } else null)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp, bottom = 8.dp)) {
                    IconButton(onClick = { camera.launch(shot.first) }) {
                        Icon(painterResource(R.drawable.sc_camera), "Camera", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(painterResource(R.drawable.ic_image), "Photo from gallery", tint = cs.onSurfaceVariant)
                    }
                    MicButton(voice)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { send() }, enabled = canSend, modifier = Modifier.height(44.dp),
                        contentPadding = PaddingValues(start = 18.dp, end = 14.dp)) {
                        Text(if (pl == null) "Plan it" else "Send", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        (voice.error ?: error)?.let {
            Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
        if (pl == null) {
            WhenRow(eatIn) { eatIn = it }
            if (text.isBlank() && photo == null) {
                UsualPicks(System.currentTimeMillis() + eatIn * 60_000L) { q ->
                    field = TextFieldValue(q.label, TextRange(q.label.length))
                    scope.launch { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
                }
            }
        }
        if (pl != null) {
            PlanDetails(pl)
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(onClick = { newMeal() }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Plan a new meal", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** The mic: tap to talk, tap again to stop. A ring swells with his voice while it listens. */
@Composable
private fun MicButton(v: VoiceState) {
    val cs = MaterialTheme.colorScheme
    val ring by animateFloatAsState(if (v.listening) 1f + v.level * 0.35f else 1f, label = "mic")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        if (v.listening) Box(Modifier.size(40.dp).graphicsLayer { scaleX = ring; scaleY = ring }
            .clip(CircleShape).background(cs.error.copy(alpha = 0.25f)))
        if (v.listening) FilledIconButton(onClick = { v.toggle() }, Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.error, contentColor = cs.onError)) {
            Icon(painterResource(R.drawable.ic_mic), "Stop listening")
        } else IconButton(onClick = { v.toggle() }) {
            Icon(painterResource(R.drawable.ic_mic), "Speak", tint = cs.onSurfaceVariant)
        }
    }
}

/** "What's for lunch?" by the time he'll be eating. */
private fun askFor(at: Long): String {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> "What's for breakfast?"
        in 11..14 -> "What's for lunch?"
        in 17..21 -> "What's for dinner?"
        else -> "What are you eating?"
    }
}

/** 20 min / 1 h / 1 h 30 min */
private fun mins(m: Int): String = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "${m / 60} h ${m % 60} min"
}

/** A clock time picked for today (or tomorrow, if that time is long gone). */
private fun clockToday(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis < now - 6 * 3600_000L) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: Long, title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val cal = remember { java.util.Calendar.getInstance().apply { timeInMillis = initial } }
    val st = rememberTimePickerState(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(st) } },
        confirmButton = { TextButton(onClick = { onPick(clockToday(st.hour, st.minute)) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** When he'll start eating: one line, - and + to nudge, tap it for a clock. Replaces the row of chips. */
@Composable
private fun WhenRow(eatIn: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(20_000); now = System.currentTimeMillis() } }
    var picking by remember { mutableStateOf(false) }
    Surface(onClick = { picking = true }, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(22.dp), tint = cs.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Eating", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (eatIn == 0) "Now" else "In ${mins(eatIn)}", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (eatIn > 0) Text("  ${Api.hhmm(now + eatIn * 60_000L)}", style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant)
                }
            }
            FilledTonalIconButton(onClick = { onChange(EAT_STEPS.lastOrNull { it < eatIn } ?: 0) }, enabled = eatIn > 0) {
                Icon(painterResource(R.drawable.ic_minus), "Sooner")
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = { onChange(EAT_STEPS.firstOrNull { it > eatIn } ?: (eatIn + 30).coerceAtMost(240)) },
                enabled = eatIn < 240) {
                Icon(Icons.Default.Add, "Later")
            }
        }
    }
    if (picking) TimeDialog(now + eatIn * 60_000L, "Start eating at", onPick = { t ->
        onChange(((t - System.currentTimeMillis() + 30_000L) / 60_000L).toInt().coerceIn(0, 240)); picking = false
    }, onDismiss = { picking = false })
}

/** His usual meals around that time, from the phone's cache (no wait). Tapping one fills the box to edit or send. */
@Composable
private fun UsualPicks(at: Long, onPick: (QuickPick) -> Unit) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val v = QuickPicks.version.collectAsState().value
    val picks = remember(at / 600_000L, v) { QuickPicks.near(c, at) }
    if (picks.isEmpty()) return
    Text("YOUR USUAL AROUND ${Api.hhmm(at - at % 1_800_000L)}", style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.2.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            picks.forEachIndexed { i, q ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.4f))
                Row(Modifier.fillMaxWidth().clickable { onPick(q) }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("About ${q.carbs} g carbs" + if (q.times > 1) "  ·  had it ${q.times} times" else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_north_west), "Use this", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * When the planned meal starts, with a way out when cooking runs long (Alan, 13 Nov: he tapped
 * Eating now, started cooking, and the reminders went off at the stove). +10 / +20 or a clock moves
 * the plan and every reminder it set; "Not eating after all" drops them.
 */
@Composable
private fun MealTime(pl: Plan, onShift: (Long) -> Unit, onNotEating: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(22.dp), tint = cs.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (pl.shiftMs != 0L) "Eating (moved)" else "Eating", style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant)
                Text(Api.hhmm(pl.eatAt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("Later", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            TextButton(onClick = { onShift(10 * 60_000L) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+10") }
            TextButton(onClick = { onShift(20 * 60_000L) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+20") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Pick a time") }, onClick = { menu = false; picking = true })
                    DropdownMenuItem(text = { Text("Not eating after all") }, onClick = { menu = false; onNotEating() })
                }
            }
        }
    }
    if (picking) TimeDialog(pl.eatAt, "Start eating at", onPick = { t ->
        picking = false; onShift(t - pl.eatAt)
    }, onDismiss = { picking = false })
}

/** Every stage the planner reports, in its usual order, for the checklist while he waits. */
private val STAGE_ORDER = listOf("Looking at the photo", "Reading your meal", "Finding the menu", "Reading the menu",
    "Checking your glucose", "Checking insulin on board", "Reading your pump settings", "Reading recent notes",
    "Checking how similar meals went", "Checking your food likes", "Reading notes", "Saving a note", "Putting it together")

/**
 * While the planner works: what it's doing now (live from the server), what it has done, a bar
 * that fills over the usual time (35 s a meal, longer for a photo, ~2 min once it looks for a
 * menu) and a clock. Cancel just stops waiting.
 */
@Composable
private fun PlanProgress(asking: String, stages: List<String>, startedAt: Long, photoSent: Boolean, reply: Boolean,
                         onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(250) } }
    val secs = ((now - startedAt) / 1000).coerceAtLeast(0)
    val menu = stages.any { it.contains("menu") }
    val expected = when { menu -> 110f; photoSent -> 50f; reply -> 25f; else -> 35f }
    // eases towards the end and never gets there until the answer does
    val frac = (1f - kotlin.math.exp(-(now - startedAt) / 1000f / expected * 1.6f)).coerceIn(0.03f, 0.95f)
    val bar by animateFloatAsState(frac, label = "plan")
    val current = stages.lastOrNull() ?: if (photoSent) "Looking at the photo" else "Reading your meal"
    val done = stages.distinct().filter { it != current }
        .sortedBy { STAGE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }

    Card(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (reply) "Updating the plan" else "Planning", style = MaterialTheme.typography.labelLarge,
                    color = cs.primary, modifier = Modifier.weight(1f))
                Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant)
            }
            Text(asking.lines().last(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            LinearProgressIndicator(progress = { bar }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(6.dp)
                .clip(RoundedCornerShape(3.dp)))
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (done + current).forEach { st ->
                    val active = st == current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            if (active) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = cs.primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(st, style = MaterialTheme.typography.bodyMedium,
                            color = if (active) cs.onSurface else cs.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Text(if (secs < expected) "Usually about ${if (menu) "2 min" else "${expected.toInt()} s"}"
                    else "Taking a bit longer than usual",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** A plan holds until an hour after he planned to start eating. */
private fun fresh(pl: Plan): Boolean =
    System.currentTimeMillis() < pl.eatAt + 60 * 60_000L

/** Arms the plan's reminders: the bolus (or a 10 min "did you bolus?" check when it's now) and later steps. */
private fun arm(c: android.content.Context, pl: Plan) {
    val what = listOfNotNull(pl.summary.takeIf { it.isNotBlank() }, pl.carbs?.let { "${it.toInt()} g" }).joinToString(", ")
    val b = pl.bolusInMin
    if (b != null) {
        if (b > 0) Reminders.add(c, b, what, bolus = true)
        else Reminders.add(c, 10, "$what (did you bolus?)", bolus = true)
    }
    pl.steps.forEach { Reminders.add(c, it.inMin, it.text, bolus = false, cond = it.cond ?: GlucoseCheck.parse(it.text)) }
}

@Composable
private fun PlanResult(pl: Plan, onShift: (Long) -> Unit, onNotEating: () -> Unit) {
    val c = LocalContext.current
    val cv = remember(pl) { Camaps.view(pl) }
    var msg by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pl.asked.lines().first(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 2, modifier = Modifier.weight(1f))
        PlannedAt(pl)
    }
    val facts = listOfNotNull(cv.glucose?.let { "Glucose $it" }, cv.mealType?.let { "$it meal" })
    if (facts.isNotEmpty()) {
        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    MealTime(pl, onShift, onNotEating)

    Card(Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text("IN CAMAPS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            cv.steps.forEachIndexed { i, st ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                StepRow(i + 1, st)
            }
        }
    }
    Button(onClick = { msg = Camaps.openWithOverlay(c, pl) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp)) {
        Text("Open CamAPS with this plan", style = MaterialTheme.typography.titleSmall)
    }
    msg?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }

    val mine = Reminders.all(c).filter { it.setAt >= pl.madeAt - 5000 && it.meal == 0L }
    if (mine.isNotEmpty()) {
        Text("Reminders", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
        mine.forEach { r -> ReminderRow(r) }
    }

    cv.question?.let { q ->
        Card(Modifier.fillMaxWidth().padding(top = 14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("To firm it up", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(q, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

/** A menu answer: the planner's top three picks for him, best first. Tapping one plans it. */
@Composable
private fun MenuPicks(pl: Plan, onPick: (String) -> Unit, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val m = pl.menu ?: return
    val picks = m.optJSONArray("picks") ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(m.optString("place").ifBlank { "Your picks" }, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
        PlannedAt(pl)
    }
    (0 until picks.length()).forEach { i ->
        val k = picks.getJSONObject(i)
        val order = k.optString("order").ifBlank { k.optString("name") }
        Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(k.optString("name").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (order != k.optString("name")) Text(order, style = MaterialTheme.typography.bodyMedium)
                    val facts = listOfNotNull(
                        if (k.has("carbs") && !k.isNull("carbs")) "~${k.optInt("carbs")} g carbs" else null,
                        k.optString("meal_type").takeIf { it.isNotBlank() }?.let { "$it meal" })
                    if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.titleSmall,
                        color = cs.primary)
                    k.optString("why").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onPick(order) }) { Text("Have this") }
                        OutlinedButton(onClick = { onChange(order) }) { Text("Change it") }
                    }
                }
            }
        }
    }
    m.optString("source").takeIf { it.startsWith("http") }?.let {
        Text("Menu: " + android.net.Uri.parse(it).host.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** When the plan was made, quietly, where the New meal link used to be. */
@Composable
private fun PlannedAt(pl: Plan) {
    Text(Api.hhmm(pl.madeAt), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
}

/** One CamAPS step: the menu words, the number to type (big), when, and a few words. */
@Composable
private fun StepRow(n: Int, st: CamapsStep) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Text("$n", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.label, style = MaterialTheme.typography.titleSmall, color = cs.primary,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (st.kind != CamapsStep.Kind.Check && st.time != null) {
                    Text(st.time, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                }
            }
            when (st.kind) {
                CamapsStep.Kind.Bolus, CamapsStep.Kind.AddMeal -> Row(verticalAlignment = Alignment.Bottom) {
                    Text("Carbs ", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Text(st.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                CamapsStep.Kind.Other -> if (st.value.isNotBlank()) Text(st.value, style = MaterialTheme.typography.titleMedium)
                else -> Text(st.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            st.sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            if (st.ifText != null || st.thenText != null) {
                Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(cs.errorContainer).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    st.ifText?.let {
                        Text("If " + it.replaceFirstChar { ch -> ch.lowercase() }, style = MaterialTheme.typography.labelLarge,
                            color = cs.onErrorContainer)
                    }
                    st.thenText?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            color = cs.onErrorContainer)
                    }
                }
            }
        }
    }
}

/** The planner's reasoning (classification, breakdown, ratio note), folded away under the plan. */
@Composable
private fun PlanDetails(pl: Plan) {
    val blocks = pl.text.replace("**", "").split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        // Never show the planner's raw JSON line, even if the server couldn't read it (12 Nov).
        .filterNot { it.startsWith("{") && it.endsWith("}") }
    val structured = (pl.camaps?.optJSONArray("steps")?.length() ?: 0) > 0
    // With steps from the planner the "CamAPS entry" block is already shown above as steps.
    val rest = if (structured) blocks.filterNot { it.startsWith("CamAPS", ignoreCase = true) } else blocks
    if (rest.isEmpty()) return
    var open by remember(pl) { mutableStateOf(!structured && pl.menu == null) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 8.dp)) {
        Text(if (open) "Hide breakdown" else "Breakdown and why")
    }
    if (open) rest.forEach { b ->
        Text(b, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}

@Composable
fun ReminderRow(r: Reminder, now: Long = System.currentTimeMillis()) {
    val c = LocalContext.current
    val closed = !r.open
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = when {
            r.state == Reminder.MISSED -> MaterialTheme.colorScheme.errorContainer
            closed -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        })) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = if (closed) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Api.hhmm(r.at), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(60.dp),
                color = if (closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(Reminders.title(r), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
                Text(status(r, now), style = MaterialTheme.typography.bodySmall,
                    color = if (r.state == Reminder.MISSED) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!closed) {
                TextButton(onClick = { Reminders.close(c, r.id, Reminder.DONE) }) { Text("Done") }
                IconButton(onClick = { Reminders.close(c, r.id, Reminder.SKIPPED) }) { Icon(Icons.Default.Close, "Skip") }
            }
        }
    }
}

/** One line under a reminder: what it waits for, or how it ended. */
fun status(r: Reminder, now: Long = System.currentTimeMillis()): String = when (r.state) {
    Reminder.DONE -> "Done" + (r.note.takeIf { it.isNotBlank() }?.let { ", $it" } ?: " ${Api.hhmm(r.closedAt)}")
    Reminder.SKIPPED -> if (r.note.startsWith("not needed")) "Didn't ring: ${r.note.removePrefix("not needed, ")}"
        else "Skipped" + (r.note.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
    Reminder.MISSED -> "Missed" + (r.note.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
    else -> when {
        r.at <= now -> "Ringing every ${Reminders.NAG_MIN} min until you tap Done"
        r.bolus -> "Cancels itself when you bolus"
        r.meal != 0L -> "Closes itself when the food is logged"
        r.check != null -> "Only rings if glucose is ${r.check!!.say()}"
        else -> "In ${left(r.at - now)}"
    }
}

/** 3 h 05 min / 12 min / 45 s */
fun left(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "%d h %02d min".format(s / 3600, (s % 3600) / 60)
        s >= 60 -> "${(s + 59) / 60} min"
        else -> "$s s"
    }
}

/** How the Plan page was opened: a shortcut or link may bring text or ask for the camera. */
data class PlanStart(val text: String?, val camera: Boolean)

/** Decodes a camera photo at most ~2000 px on the long side, upright. */
private fun decode(f: java.io.File): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > 2400) sample *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rot = when (androidx.exifinterface.media.ExifInterface(f.path).getAttributeInt(
        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
    }
    if (rot == 0f) return b
    val m = android.graphics.Matrix().apply { postRotate(rot) }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

/** A JPEG small enough to send (long side 1600 px). */
private fun jpeg(b: Bitmap): ByteArray {
    val scale = minOf(1f, 1600f / maxOf(b.width, b.height))
    val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
    return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
