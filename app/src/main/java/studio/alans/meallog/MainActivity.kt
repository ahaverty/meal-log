package studio.alans.meallog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Home: log what you're eating now (guessed from the time of day), and the bolus reminder. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MealTheme { Surface(Modifier.fillMaxSize()) { Home() } } }
    }
}

@Composable
private fun Home() {
    val c = LocalContext.current
    val p = remember { Prefs(c) }
    var settings by remember { mutableStateOf(false) }
    val openedAt = remember { System.currentTimeMillis() }
    Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Meal Log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
        }
        if (!Settings.canDrawOverlays(c)) {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("To pop up over CamAPS after a bolus, allow Display over other apps.")
                    TextButton(onClick = {
                        c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
                    }) { Text("Allow") }
                }
            }
        }
        ReminderCard(p)
        MealPanel(openedAt, null, null, sheet = false) { }
        Spacer(Modifier.height(32.dp))
    }
    if (settings) SettingsDialog(p) { settings = false }
}

@Composable
private fun ReminderCard(p: Prefs) {
    val c = LocalContext.current
    var at by remember { mutableLongStateOf(p.reminderAt) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(at) { while (true) { now = System.currentTimeMillis(); at = p.reminderAt; delay(5000) } }
    Card(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Remind me to bolus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (at > now) {
                val mins = ((at - now) / 60_000L) + 1
                Text("Rings at ${Api.hhmm(at)} (in $mins min)${p.reminderNote.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}. " +
                        "A bolus before then cancels it.")
                TextButton(onClick = { Reminders.cancel(c); at = 0 }) { Text("Cancel reminder") }
            } else {
                Text("Ordering now, eating soon? It rings only if you haven't bolused by then.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    placeholder = { Text("What for? (optional, e.g. sandwich 45 g)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { m ->
                        FilledTonalButton(onClick = { Reminders.set(c, m, note.trim()); at = p.reminderAt; note = "" },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text("$m m") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(p: Prefs, close: () -> Unit) {
    var ask by remember { mutableStateOf(p.askAfterBolus) }
    var min by remember { mutableStateOf(p.minCarbs.toString()) }
    var url by remember { mutableStateOf(p.url) }
    var token by remember { mutableStateOf(p.token) }
    var cfId by remember { mutableStateOf(p.cfId) }
    var cfSecret by remember { mutableStateOf(p.cfSecret) }
    AlertDialog(onDismissRequest = close, title = { Text("Settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ask after a meal bolus", Modifier.weight(1f)); Switch(ask, { ask = it })
            }
            OutlinedTextField(min, { min = it.filter(Char::isDigit) }, label = { Text("Meal bolus from (g carbs)") })
            Text("Needs FX Watcher 3.11+ with Settings > Connections > Share boluses on.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(url, { url = it }, label = { Text("Server address") })
            OutlinedTextField(token, { token = it }, label = { Text("App token") })
            OutlinedTextField(cfId, { cfId = it }, label = { Text("Cloudflare Access client id") })
            OutlinedTextField(cfSecret, { cfSecret = it }, label = { Text("Cloudflare Access client secret") })
        }
    }, confirmButton = {
        TextButton(onClick = {
            p.askAfterBolus = ask; p.minCarbs = min.toIntOrNull() ?: 10
            p.url = url; p.token = token; p.cfId = cfId; p.cfSecret = cfSecret
            close()
        }) { Text("Save") }
    }, dismissButton = { OutlinedButton(onClick = close) { Text("Cancel") } })
}
