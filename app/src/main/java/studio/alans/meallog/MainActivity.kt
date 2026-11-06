package studio.alans.meallog

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class Page(val title: String) { Plan("Plan a meal"), Log("Log food"), Reminders("Reminders"), Settings("Settings") }

/** Where to open: a page, plus what a link or shortcut brought. n changes on every open, so the same link reopens. */
data class Route(val page: Page, val plan: PlanStart? = null, val install: Boolean = false, val n: Long = System.nanoTime())

/**
 * Plan (default), Log and Reminders, Settings behind the gear. meallog:// links and the launcher
 * shortcuts open the right page; with a meal bolus not logged yet, the app opens on Log.
 */
class MainActivity : ComponentActivity() {
    private val route = mutableStateOf(Route(Page.Plan))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        route.value = route(intent)
        setContent { MealTheme { App(route.value) { route.value = it } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = route(intent)
    }

    private fun route(i: Intent?): Route {
        val u = i?.data
        if (i?.action == Updater.ACTION_INSTALL) return Route(Page.Settings, install = true)
        if (u?.scheme != "meallog") {
            return Route(if (Prefs(this).unloggedBolus() > 0) Page.Log else Page.Plan)
        }
        return when (u.host) {
            "log" -> Route(Page.Log)
            "reminders" -> Route(Page.Reminders)
            "settings" -> Route(Page.Settings)
            "remind" -> {
                val m = u.getQueryParameter("min")?.toIntOrNull()?.coerceIn(1, 240) ?: 15
                val r = Reminders.add(this, m, u.getQueryParameter("text").orEmpty(), bolus = true)
                Toast.makeText(this, "Bolus reminder at ${Api.hhmm(r.at)}", Toast.LENGTH_SHORT).show()
                Route(Page.Reminders)
            }
            else -> Route(Page.Plan, PlanStart(u.getQueryParameter("text"), u.getQueryParameter("camera") == "1"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(route: Route, go: (Route) -> Unit) {
    val c = LocalContext.current
    val page = route.page
    val reminders = run { Reminders.version.collectAsState().value; Reminders.all(c) }
    LaunchedEffect(Unit) { Updater.check(c, force = false) }   // quiet; shows as a notification + in Settings
    BackHandler(page == Page.Settings) { go(Route(Page.Plan)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    if (page == Page.Settings) IconButton(onClick = { go(Route(Page.Plan)) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (page != Page.Settings) IconButton(onClick = { go(Route(Page.Settings)) }) {
                        BadgedBox(badge = { if (Updater.available(c) != null) Badge() }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (page != Page.Settings) NavigationBar {
                NavigationBarItem(page == Page.Plan, { go(Route(Page.Plan)) },
                    icon = { Icon(painterResource(R.drawable.sc_plan), null) }, label = { Text("Plan") })
                NavigationBarItem(page == Page.Log, { go(Route(Page.Log)) },
                    icon = {
                        BadgedBox(badge = { if (Prefs(c).unloggedBolus() > 0) Badge() }) { Icon(painterResource(R.drawable.sc_log), null) }
                    }, label = { Text("Log") })
                NavigationBarItem(page == Page.Reminders, { go(Route(Page.Reminders)) },
                    icon = {
                        BadgedBox(badge = { if (reminders.isNotEmpty()) Badge { Text("${reminders.size}") } }) {
                            Icon(painterResource(R.drawable.sc_alarm), null)
                        }
                    }, label = { Text("Reminders") })
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())) {
            key(route.n) {
                when (page) {
                    Page.Plan -> PlanScreen(route.plan)
                    Page.Log -> LogPage()
                    Page.Reminders -> RemindersPage(reminders)
                    Page.Settings -> SettingsScreen(autoInstall = route.install)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogPage() {
    val c = LocalContext.current
    val p = remember { Prefs(c) }
    val openedAt = remember { System.currentTimeMillis() }
    val bolusAt = remember { p.unloggedBolus() }
    var forBolus by remember { mutableStateOf(bolusAt > 0) }
    Column {
        if (bolusAt > 0) {
            Text("Which meal?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(forBolus, { forBolus = true },
                    label = { Text("The ${Api.hhmm(bolusAt)} bolus, ${p.lastBolusCarbs.toInt()} g") })
                FilterChip(!forBolus, { forBolus = false }, label = { Text("Something now") })
            }
        }
        key(forBolus) {
            if (forBolus) MealPanel(bolusAt, p.lastBolusCarbs.toDouble(), p.lastBolusUnits.toDouble(), sheet = false) { }
            else MealPanel(openedAt, null, null, sheet = false) { }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersPage(list: List<Reminder>) {
    val c = LocalContext.current
    var note by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (list.isEmpty()) {
            Text("Nothing coming up.", style = MaterialTheme.typography.bodyLarge)
            Text("Plans set their own reminders. Bolus reminders cancel themselves when FX Watcher sees the bolus.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            list.forEach { ReminderRow(it) }
        }
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Remind me to bolus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Ordered, eating soon? It rings only if you haven't bolused by then.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("What for? (optional)") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { m ->
                        FilledTonalButton(onClick = { Reminders.add(c, m, note.trim(), bolus = true); note = "" }) { Text("$m min") }
                    }
                }
            }
        }
    }
}
