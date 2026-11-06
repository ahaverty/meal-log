package studio.alans.meallog

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** After a bolus, what the app needs from Android and FX Watcher, the server, and updates. */
@Composable
fun SettingsScreen(autoInstall: Boolean) {
    val c = LocalContext.current
    val p = remember { Prefs(c) }
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(p.askAfterBolus) }
    var min by remember { mutableStateOf(p.minCarbs.toString()) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section("After a meal bolus") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask what I'm eating")
                    Text("Opens over CamAPS straight after the bolus", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(ask, { ask = it; p.askAfterBolus = it })
            }
            OutlinedTextField(min, { v -> min = v.filter(Char::isDigit).take(3); min.toIntOrNull()?.let { p.minCarbs = it } },
                Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Counts as a meal from (g carbs)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        Section("Needs") {
            val overlay = Settings.canDrawOverlays(c)
            Status("Display over other apps", if (overlay) "Allowed" else "Needed to open over CamAPS", overlay) {
                c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
            }
            val fxOk = runCatching { c.packageManager.getPackageInfo("studio.alans.fxwatcher", 0); true }.getOrDefault(false)
            val perm = c.checkSelfPermission("studio.alans.fxwatcher.permission.RECEIVE_BOLUS") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            val heard = p.lastBolusAt
            Status("FX Watcher boluses",
                when {
                    !fxOk -> "FX Watcher isn't installed"
                    !perm -> "Reinstall Meal Log after updating FX Watcher to 3.11+"
                    heard > 0 -> "Last heard ${Api.stamp(heard).substring(5)}: ${"%.1f".format(p.lastBolusUnits)} U" +
                            (if (p.lastBolusCarbs >= 0) ", ${p.lastBolusCarbs.toInt()} g" else "")
                    else -> "None yet. In FX Watcher: Settings > Connections > Share boluses"
                }, fxOk && perm && heard > 0, null)
        }

        Section("Updates") {
            var avail by remember { mutableStateOf(Updater.available(c)) }
            var msg by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(autoInstall) { if (autoInstall && avail != null) { msg = "Downloading…"; msg = Updater.install(c) } }
            Text("Meal Log v${Updater.installedName(c)}")
            avail?.let {
                Text("v${it.name} is ready", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                if (it.notes.isNotBlank()) Text(it.notes, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                if (avail != null) FilledTonalButton(onClick = { msg = "Downloading…"; scope.launch { msg = Updater.install(c) } }) {
                    Text("Install v${avail!!.name}")
                } else TextButton(onClick = {
                    msg = "Checking…"
                    scope.launch { msg = Updater.check(c, force = true); avail = Updater.available(c) }
                }) { Text("Check for update") }
                TextButton(onClick = { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES))) }) { Text("Releases") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Section("Meal server") {
            var open by remember { mutableStateOf(p.url.isBlank()) }
            Text(if (p.url.isBlank()) "Not set" else Uri.parse(p.url).host ?: p.url, style = MaterialTheme.typography.bodyMedium)
            if (!open) TextButton(onClick = { open = true }) { Text("Change") } else {
                var url by remember { mutableStateOf(p.url) }
                var token by remember { mutableStateOf(p.token) }
                var cfId by remember { mutableStateOf(p.cfId) }
                var cfSecret by remember { mutableStateOf(p.cfSecret) }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Address") }, singleLine = true)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
                OutlinedTextField(cfId, { cfId = it }, Modifier.fillMaxWidth(), label = { Text("Cloudflare Access client id (optional)") }, singleLine = true)
                OutlinedTextField(cfSecret, { cfSecret = it }, Modifier.fillMaxWidth(), label = { Text("Cloudflare Access client secret") }, singleLine = true)
                TextButton(onClick = { p.url = url; p.token = token; p.cfId = cfId; p.cfSecret = cfSecret; open = false }) { Text("Save") }
            }
        }

        Text("Unofficial, not a medical device. Meal Log never doses or suggests insulin units.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun Status(label: String, detail: String, ok: Boolean, fix: (() -> Unit)?) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (ok) "✓ " else "! ") + label, fontWeight = FontWeight.Medium,
                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok && fix != null) TextButton(onClick = fix) { Text("Fix") }
    }
    Spacer(Modifier.height(2.dp))
}
