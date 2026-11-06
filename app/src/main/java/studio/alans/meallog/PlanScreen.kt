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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import java.io.ByteArrayOutputStream

/**
 * "I'm about to have this": a description, a menu or a photo -> the CamAPS entry, from the
 * server's planner (Alan's camaps-meal-advisor skill on his Claude subscription). Never units.
 * From the answer: a smart bolus reminder, and the meal remembered so the sheet after the bolus
 * offers it first.
 */
@Composable
fun PlanScreen() {
    val c = LocalContext.current
    val api = remember { Api(c) }
    val p = remember { Prefs(c) }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var asked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> if (b != null) photo = b }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) photo = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }

    fun send() {
        val t = text.trim()
        val img = photo
        if (t.isEmpty() && img == null) return
        busy = true; error = null; note = null
        val session = plan?.session
        asked = t.ifEmpty { "(photo)" }
        scope.launch {
            runCatching { api.planResult(api.plan(t, img?.let(::jpeg), session)) }
                .onSuccess {
                    plan = it; text = ""; photo = null
                    if (it.summary.isNotBlank()) { p.planSummary = it.summary; p.planCarbs = (it.carbs ?: -1.0).toFloat(); p.planAt = System.currentTimeMillis() }
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Plan a meal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Describe it, or photograph the plate or the menu. You get the CamAPS entry and when to bolus.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        plan?.let { pl ->
            asked?.let { Text("You: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(pl.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val mins = pl.bolusInMin
                if (mins != null && mins > 0) {
                    FilledTonalButton(onClick = {
                        Reminders.set(c, mins, listOfNotNull(pl.summary.takeIf { it.isNotBlank() }, pl.carbs?.let { "${it.toInt()} g" }).joinToString(", "))
                        note = "Reminder set for ${Api.hhmm(System.currentTimeMillis() + mins * 60_000L)}. A bolus before then cancels it."
                    }) { Text("Remind me to bolus in $mins min") }
                }
                OutlinedButton(onClick = { plan = null; asked = null; note = null }) { Text("New meal") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Working it out (about 30 s)…")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        photo?.let { b ->
            Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop)
            TextButton(onClick = { photo = null }) { Text("Remove photo") }
        }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text(if (plan == null) "e.g. chicken stuffing sandwich and Taytos, eating in 10 min"
                                 else "Reply, e.g. the bread was thin, 2 slices") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { camera.launch(null) }, enabled = !busy) { Text("Camera") }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy) { Text("Photo") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { send() }, enabled = !busy && (text.isNotBlank() || photo != null)) {
                Text(if (plan == null) "Plan it" else "Send")
            }
        }
    }
}

/** A JPEG small enough to send (long side 1600 px). */
private fun jpeg(b: Bitmap): ByteArray {
    val scale = minOf(1f, 1600f / maxOf(b.width, b.height))
    val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
    return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
