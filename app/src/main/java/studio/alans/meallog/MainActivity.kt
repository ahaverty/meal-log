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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
    val reminders = run { Reminders.version.collectAsState().value; Reminders.open(c) }
    LaunchedEffect(Unit) { Reminders.sweep(c); Updater.check(c, force = false); QuickPicks.schedule(c); QuickPicks.refreshSoon(c) }   // quiet; shows as a notification + in Settings
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
                        BadgedBox(badge = { if (reminders.isNotEmpty()) Badge { Text("${reminders.size}") } }) {   // open ones only
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
    var bolus by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) } }
    val earlier = run { Reminders.version.collectAsState().value; Reminders.earlier(c, now) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val ringing = list.filter { it.at <= now }
        val next = ringing.firstOrNull() ?: list.firstOrNull()
        if (next == null) {
            Text("Nothing coming up.", style = MaterialTheme.typography.bodyLarge)
            Text("Only urgent reminders ring and pop up: a bolus not seen yet, or a glucose check that came true. The rest are silent notifications.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TimerCard(next, now)
            val rest = list.filter { it.id != next.id }
            if (rest.isNotEmpty()) {
                Text("Up next", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
                rest.forEach { ReminderRow(it, now) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("New timer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text(if (bolus) "What for? (optional)" else "What? e.g. dinner ready") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(bolus, { bolus = true }, label = { Text("Bolus (cancels when I bolus)") })
                    FilterChip(!bolus, { bolus = false }, label = { Text("Plain timer") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 30, 45, 60).forEach { m ->
                        FilledTonalButton(onClick = {
                            Reminders.add(c, m, note.trim().ifEmpty { if (bolus) "" else "Timer" }, bolus = bolus); note = ""
                        }) { Text("$m min") }
                    }
                }
            }
        }
        if (earlier.isNotEmpty()) {
            Text("Earlier", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
            earlier.forEach { ReminderRow(it, now) }
        }
    }
}

/** The next reminder as a big clock-app timer: ring counting down, Done / +5 min / Skip. */
@Composable
private fun TimerCard(r: Reminder, now: Long) {
    val c = LocalContext.current
    val over = r.at <= now
    val total = (r.at - r.setAt).coerceAtLeast(1L)
    val frac = if (over) 1f else ((r.at - now).toFloat() / total).coerceIn(0f, 1f)
    val ring = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (over) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 14.dp.toPx()
                    val inset = w / 2
                    val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                    val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                    drawArc(ring, -90f, 360f * frac, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (over) Text("LATE", style = MaterialTheme.typography.labelLarge, color = ring, letterSpacing = 2.sp)
                    Text(clock(if (over) now - r.at else r.at - now), fontSize = 52.sp, fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Monospace, color = if (over) ring else MaterialTheme.colorScheme.onSurface)
                    Text((if (over) "was due " else "rings at ") + Api.hhmm(r.at), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(Reminders.title(r), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Text(status(r, now), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Reminders.close(c, r.id, Reminder.SKIPPED) }, Modifier.weight(1f)) { Text("Skip") }
                OutlinedButton(onClick = { Reminders.snooze(c, r.id) }, Modifier.weight(1f)) { Text("+5 min") }
                Button(onClick = { Reminders.close(c, r.id, Reminder.DONE) }, Modifier.weight(1.3f)) { Text("Done") }
            }
        }
    }
}

/** 19:42 / 1:05:09 */
private fun clock(ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
