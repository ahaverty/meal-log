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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private val EAT_IN = listOf(0 to "Now", 10 to "10 min", 20 to "20 min", 30 to "30 min", 45 to "45 min")

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
        plan = null; p.planJson = ""; field = TextFieldValue(""); photo = null; error = null; eatIn = 0
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
                api.planResult(api.plan(t, img?.let(::jpeg), previous?.session, if (previous == null) eatIn else null),
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val pl = plan
        if (pl == null) {
            if (!busy) {
                Text("What are you eating?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            }
        } else {
            if (pl.menu != null) MenuPicks(pl, onPick = { send("I'll have: $it") }, onChange = {
                // his own version of a pick: the order in the box, cursor at the end, to edit and Send
                val t = "I'll have: $it, but "
                field = TextFieldValue(t, TextRange(t.length))
                scope.launch { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
            })
            else PlanResult(pl)
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
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
        val canSend = text.isNotBlank() || photo != null
        OutlinedTextField(field, { field = it; voiceBase = null }, Modifier.fillMaxWidth().focusRequester(focus),
            minLines = if (pl == null) 2 else 1, maxLines = 6,
            shape = RoundedCornerShape(16.dp),
            // the keyboard's Enter key is Send (Alan, 11 Nov: no more closing the keyboard to find the button)
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
            placeholder = {
                Text(when {
                    voice.listening -> "Listening…"
                    pl == null -> "A chicken sandwich and Taytos, or a restaurant"
                    pl.menu != null -> "What you'll have, or what you don't fancy"
                    else -> "Answer or change it, like a 30g bag"
                }, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            },
            trailingIcon = if (text.isNotEmpty() && !voice.listening) {
                { IconButton(onClick = { field = TextFieldValue(""); voiceBase = null }) { Icon(Icons.Default.Close, "Clear") } }
            } else null)
        (voice.error ?: error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (pl == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                EAT_IN.forEach { (m, label) -> FilterChip(eatIn == m, { eatIn = m }, label = { Text(if (m == 0) "Eating now" else "In $label") }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalIconButton(onClick = { camera.launch(shot.first) }, Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sc_camera), "Camera")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_image), "Photo from gallery") }
            Spacer(Modifier.width(8.dp))
            MicButton(voice)
            Spacer(Modifier.weight(1f))
            Button(onClick = { send() }, enabled = canSend, modifier = Modifier.height(48.dp)) {
                Text(if (pl == null) "Plan it" else "Send", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
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
        if (v.listening) Box(Modifier.size(48.dp).graphicsLayer { scaleX = ring; scaleY = ring }
            .clip(CircleShape).background(cs.error.copy(alpha = 0.25f)))
        FilledIconButton(onClick = { v.toggle() }, Modifier.size(48.dp),
            colors = if (v.listening) IconButtonDefaults.filledIconButtonColors(containerColor = cs.error, contentColor = cs.onError)
                else IconButtonDefaults.filledIconButtonColors()) {
            Icon(painterResource(R.drawable.ic_mic), if (v.listening) "Stop listening" else "Speak")
        }
    }
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
    System.currentTimeMillis() < pl.madeAt + ((pl.eatInMin ?: 0).coerceAtLeast(0) + 60) * 60_000L

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
private fun PlanResult(pl: Plan) {
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
