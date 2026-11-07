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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private val EAT_IN = listOf(0 to "Now", 10 to "10 min", 20 to "20 min", 30 to "30 min", 45 to "45 min")

/**
 * "I'm about to have this": text and/or a photo of the plate or menu -> the CamAPS entry from the
 * server's planner. The answer arms its own reminders: the bolus (cancelled by the bolus itself)
 * and any later step (Boost, slow carbs). Each shows with an X. The plan is kept for 3 h.
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
            if (System.currentTimeMillis() - p.planAt < 3 * 3600_000L && p.planJson.isNotBlank()) Plan.of(JSONObject(p.planJson)) else null
        }.getOrNull())
    }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<String?>(null) }
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

    fun send(said: String? = null) {
        val t = (said ?: text).trim()
        val img = if (said != null) null else photo
        if (t.isEmpty() && img == null) return
        val previous = plan
        busy = true; error = null
        asking = t.ifEmpty { "Photo" }
        scope.launch {
            runCatching {
                api.planResult(api.plan(t, img?.let(::jpeg), previous?.session, if (previous == null) eatIn else null),
                    if (previous == null) asking!! else previous.asked + "\n" + asking!!)
            }.onSuccess { pl ->
                if (previous != null) Reminders.all(c).filter { it.setAt >= previous.madeAt - 5000 }.forEach { Reminders.cancel(c, it.id) }
                arm(c, pl)
                plan = pl; field = TextFieldValue(""); photo = null
                p.planJson = pl.json().toString(); p.planAt = pl.madeAt
                p.planSummary = pl.summary; p.planCarbs = (pl.carbs ?: -1.0).toFloat()
            }.onFailure { error = it.message }
            busy = false; asking = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val pl = plan
        if (pl == null) {
            Text("What are you about to eat?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Describe it or photograph the plate. For a menu, send a photo or just the restaurant's name: you get your top 3 picks, then the CamAPS entry, when to bolus, and reminders.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        } else {
            if (pl.menu != null) MenuPicks(pl, onPick = { send("I'll have: $it") }, onChange = {
                // his own version of a pick: the order in the box, cursor at the end, to edit and Send
                val t = "I'll have: $it, but "
                field = TextFieldValue(t, TextRange(t.length))
                scope.launch { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
            }, onNew = {
                plan = null; p.planJson = ""
            })
            else PlanResult(pl, onNew = {
                plan = null; p.planJson = ""
            })
            Spacer(Modifier.height(12.dp))
        }

        if (busy) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (plan == null) "Thinking, 30 s (menus up to 2 min)" else "Counting it up, about 30 s", fontWeight = FontWeight.SemiBold)
                        asking?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                }
            }
            return@Column
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }

        photo?.let { b ->
            Row(verticalAlignment = Alignment.Top) {
                Image(b.asImageBitmap(), null, Modifier.weight(1f).heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
                IconButton(onClick = { photo = null }) { Icon(Icons.Default.Close, "Remove photo") }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(field, { field = it }, Modifier.fillMaxWidth().focusRequester(focus), minLines = if (pl == null) 3 else 1,
            placeholder = {
                Text(if (pl == null) "e.g. chicken stuffing sandwich and Taytos, or Wagamama Dundrum" else if (pl.menu != null) "Or say what you'll have, or what you don't fancy" else "Answer or change it, e.g. 30g bag, walking home")
            })
        if (pl == null) {
            Text("Eating", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EAT_IN.forEach { (m, label) -> FilterChip(eatIn == m, { eatIn = m }, label = { Text(label) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { camera.launch(shot.first) }) { Text("Camera") }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photo") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { send() }, enabled = text.isNotBlank() || photo != null) { Text(if (pl == null) "Plan it" else "Send") }
        }
        if (pl != null) PlanDetails(pl)
    }
}

/** Arms the plan's reminders: the bolus (or a 10 min "did you bolus?" check when it's now) and later steps. */
private fun arm(c: android.content.Context, pl: Plan) {
    val what = listOfNotNull(pl.summary.takeIf { it.isNotBlank() }, pl.carbs?.let { "${it.toInt()} g" }).joinToString(", ")
    val b = pl.bolusInMin
    if (b != null) {
        if (b > 0) Reminders.add(c, b, what, bolus = true)
        else Reminders.add(c, 10, "$what (did you bolus?)", bolus = true)
    }
    pl.steps.forEach { Reminders.add(c, it.inMin, it.text, bolus = false) }
}

@Composable
private fun PlanResult(pl: Plan, onNew: () -> Unit) {
    val c = LocalContext.current
    val cv = remember(pl) { Camaps.view(pl) }
    var msg by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pl.asked.lines().first(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 2, modifier = Modifier.weight(1f))
        TextButton(onClick = onNew) { Text("New meal") }
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
    Text(msg ?: "The plan floats over CamAPS while you type it in. Drag it aside, tap it to close.",
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        color = if (msg != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

    val mine = Reminders.all(c).filter { it.setAt >= pl.madeAt - 5000 }
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
private fun MenuPicks(pl: Plan, onPick: (String) -> Unit, onChange: (String) -> Unit, onNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val m = pl.menu ?: return
    val picks = m.optJSONArray("picks") ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(m.optString("place").ifBlank { "Your picks" }, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
        TextButton(onClick = onNew) { Text("New meal") }
    }
    Text("Top picks for you. Have one as is, change it (a different side, no sauce), or type your own below.", style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant)
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
fun ReminderRow(r: Reminder) {
    val c = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Api.hhmm(r.at), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(60.dp))
            Column(Modifier.weight(1f)) {
                Text(if (r.bolus) "Bolus" else r.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (r.bolus) Text("Cancels itself when you bolus", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { Reminders.cancel(c, r.id) }) { Icon(Icons.Default.Close, "Cancel reminder") }
        }
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
