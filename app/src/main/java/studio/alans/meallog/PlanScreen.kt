package studio.alans.meallog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
    var text by remember { mutableStateOf(start?.text ?: "") }
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

    fun send() {
        val t = text.trim()
        val img = photo
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
                plan = pl; text = ""; photo = null
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
            Text("Describe it or photograph the plate or menu. You get the CamAPS entry, when to bolus, and reminders.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        } else {
            PlanResult(pl, onNew = {
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
                        Text("Counting it up, about 30 s", fontWeight = FontWeight.SemiBold)
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
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = if (pl == null) 3 else 1,
            placeholder = {
                Text(if (pl == null) "e.g. chicken stuffing sandwich and Taytos" else "Answer or correct it, e.g. thin bread, walking home")
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
    val blocks = pl.text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    val entry = blocks.firstOrNull { it.startsWith("CamAPS", ignoreCase = true) } ?: blocks.firstOrNull().orEmpty()
    val rest = blocks.filter { it != entry }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pl.asked.lines().first(), style = MaterialTheme.typography.titleMedium, maxLines = 2,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onNew) { Text("New meal") }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            entry.lines().forEachIndexed { i, line ->
                if (i == 0 && line.startsWith("CamAPS", true)) {
                    Text(line, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text(line, style = if (line.startsWith("(")) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    val mine = Reminders.all(c).filter { it.setAt >= pl.madeAt - 5000 }
    if (mine.isNotEmpty()) {
        Text("Reminders set", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        mine.forEach { r -> ReminderRow(r) }
    }

    rest.forEach { b ->
        Text(b, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
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
                Text(if (r.bolus) "Bolus" + (r.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") else r.text,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2)
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
