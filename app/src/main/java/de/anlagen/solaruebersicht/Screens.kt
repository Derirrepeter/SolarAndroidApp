@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.anlagen.solaruebersicht

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val ALL_SITES = "Alle aktiven Anlagen"
private val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
private val MONTHS = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
    "September", "Oktober", "November", "Dezember")

// ======================================================================= //
// Anmeldung
// ======================================================================= //
@Composable
fun LoginScreen(state: UiState, vm: MainViewModel) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    var pw by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFC27A), Color(0xFFFFE2BF), MaterialTheme.colorScheme.background)))
            .verticalScroll(rememberScrollState())
            .statusBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        // Logo ohne eigenen Hintergrund direkt auf dem Farbverlauf
        Image(painterResource(R.drawable.logo_koch), contentDescription = "Elektro & IT-Service Koch",
            modifier = Modifier.fillMaxWidth().height(190.dp).padding(vertical = 8.dp))
        Spacer(Modifier.height(4.dp))
        Text("Solar Übersicht", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Alle Anlagen auf einen Blick – Anmeldung mit dem Konto von solar-assistant.io",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(email, { email = it }, label = { Text("E-Mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text("Passwort") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(remember, { remember = it })
            Text("Angemeldet bleiben (Passwort verschlüsselt speichern)")
        }
        state.loginError?.let { Text(it, color = C.RED) }
        Button(onClick = { vm.login(email, pw, remember) }, enabled = !state.busy && email.isNotBlank() && pw.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Anmelden", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { vm.startDemo() }, modifier = Modifier.fillMaxWidth()) {
            Text("Demo ohne Anmeldung ansehen (Zufallswerte)")
        }
    }
}

// ======================================================================= //
// Live
// ======================================================================= //
@Composable
fun LiveScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val sites = state.visibleSites
    val now = System.currentTimeMillis() / 1000.0

    // Gesamt: Summe (bzw. Mittel) aller Anlagen, die online sind
    val online = sites.filter { state.live[it.name]?.status == Status.ONLINE }
    val totals = mutableMapOf<String, Double>()
    val totalLim = mutableMapOf<String, Limits>()
    for (m in listOf(M.PV, M.LOAD, M.GRID, M.BATT, M.SOC, M.PV_TODAY)) {
        val per = online.mapNotNull { s -> state.live[s.name]?.let { l -> l.values[m]?.let { v -> Triple(s, l, v) } } }
        if (per.isEmpty()) continue
        val n = if (M.isAverage(m)) per.size.toDouble() else 1.0
        totals[m] = per.sumOf { it.third } / n
        val lims = per.mapNotNull { (s, l, _) -> limitsFor(m, s, l.settings ?: emptyMap(), l.values) }
        if (lims.isNotEmpty()) totalLim[m] = Limits(lims.sumOf { it.lo } / n, lims.sumOf { it.hi } / n, lims.first().redTo)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val t = if (state.lastUpdate > 0) Instant.ofEpochMilli(state.lastUpdate).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) else "–"
            Text("${online.size} von ${sites.size} Anlagen online · Stand $t", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
        if (TOTAL_CARD !in state.hidden) item {
            val faults = online.count { state.live[it.name]?.fault == true }
            SiteCard(TOTAL_CARD, "${online.size}/${sites.size} online" + (if (faults > 0) " · ⚠ $faults Störung" + (if (faults > 1) "en" else "") else ""),
                null, false, totals, totalLim, dim = false,
                highlight = true, onClick = null,
                // Energie heute: Summe aller Anlagen online
                today = online.mapNotNull { state.live[it.name]?.today }.takeIf { it.isNotEmpty() }
                    ?.reduce { a, b -> DoubleArray(a.size) { i -> a[i] + b[i] } },
                flow = TOTAL_CARD in state.flowCards, onToggle = { vm.toggleFlow(TOTAL_CARD) })
        }
        items(sites, key = { it.name }) { site ->
            val l = state.live[site.name] ?: SiteLive()
            val stale = l.status == Status.ONLINE && l.lastTs > 0 && now - l.lastTs > 120
            val status = when {
                stale -> "keine Daten seit ${((now - l.lastTs) / 60).toInt()} min"
                l.status == Status.ONLINE -> "online" + (l.values[M.TEMP]?.let { " · WR ${fmt(it)} °C" } ?: "")
                else -> l.message
            }
            val lims = M.LIVE.mapNotNull { m -> limitsFor(m, site, l.settings ?: emptyMap(), l.values)?.let { m to it } }.toMap()
            SiteCard(site.name, status, l.status, stale, l.values, lims,
                dim = l.status != Status.ONLINE || stale, highlight = false,
                onClick = if (site.openUrl.isNotEmpty()) {
                    { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.openUrl))) }
                } else null,
                flow = site.name in state.flowCards, onToggle = { vm.toggleFlow(site.name) }, extras = l.extras,
                mode = l.mode, fault = l.fault && l.status == Status.ONLINE,
                today = l.today.takeIf { l.status == Status.ONLINE })
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
internal fun SiteCard(
    name: String, status: String, st: Status?, stale: Boolean, values: Map<String, Double>, limits: Map<String, Limits>,
    dim: Boolean, highlight: Boolean, onClick: (() -> Unit)?,
    flow: Boolean = false, onToggle: (() -> Unit)? = null, extras: List<ExtraLive> = emptyList(),
    mode: String? = null, fault: Boolean = false, today: DoubleArray? = null,
) {
    // Farbe je Zustand: online grün, keine neuen Daten gelb, offline grau, Fehler rot, lädt blau
    val (dot, tint) = when {
        highlight -> Color.White to Color.Transparent
        fault && st == Status.ONLINE -> C.RED to Color(0xFFFDE7E5)
        stale -> Color(0xFFE0A800) to Color(0xFFFFF4D1)
        st == Status.ONLINE -> C.GREEN to Color(0xFFE3F5EA)
        st == Status.OFFLINE -> C.GREY to Color(0xFFEDEDED)
        st == Status.ERROR -> C.RED to Color(0xFFFDE7E5)
        else -> C.BLUE to Color(0xFFE3EEFB)
    }
    val dark = isSystemInDarkTheme()
    val headerMod = if (highlight) Modifier.background(Brand.HEADER)
    else Modifier.background(if (dark) dot.copy(alpha = 0.18f) else tint)
    Card(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(Modifier.fillMaxWidth().then(headerMod).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(8.dp))
            Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, softWrap = false,
                color = if (highlight) Color.White else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Text(status, fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                color = if (highlight) Color.White else if (dark) dot else dot.copy(red = dot.red * 0.8f, green = dot.green * 0.8f, blue = dot.blue * 0.8f))
            if (onToggle != null && st != Status.OFFLINE) {
                Spacer(Modifier.width(8.dp))
                ViewToggle(flow, onLight = highlight, onToggle)
            }
        }
        Column(Modifier.padding(10.dp)) {
            if (st == Status.OFFLINE) return@Column      // offline: nur Name und Status
            if (mode != null || fault) {
                // Betriebsmodus laut SolarAssistant; bei Störung rot und fett
                Text(if (fault) "⚠ Störung · ${mode ?: "siehe Anlage"}" else "Betriebsmodus: $mode",
                    fontSize = 12.sp, fontWeight = if (fault) FontWeight.Bold else FontWeight.Normal,
                    color = if (fault) C.RED else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
            if (flow) {
                // Ansicht "Energiefluss"
                EnergyFlow(values, active = !dim, today = today)
                if (today == null) values[M.PV_TODAY]?.let {
                    Text("PV heute ${fmt(it)} kWh", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                ExtrasPanel(extras, dim)
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(M.PV, M.LOAD, M.GRID))
                    Gauge(m, values[m], limits[m], dim, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Gauge(M.BATT, values[M.BATT], limits[M.BATT], dim, Modifier.weight(1f))
                BatterySoc(values[M.SOC], limits[M.SOC]?.redTo, dim, Modifier.weight(1f))
                ValueTile(M.PV_TODAY, values[M.PV_TODAY], dim, Modifier.weight(1f))
            }
            today?.let { EnergyTodayRow(it, dim) }
            ExtrasPanel(extras, dim)
        }
    }
}

// ======================================================================= //
// Verläufe
// ======================================================================= //
private val PERIODS = listOf("Tag" to (1L to "2m"), "Woche" to (7L to "15m"), "Monat" to (30L to "1h"), "Jahr" to (365L to "1d"))
private val EXTRAS = listOf("(keiner)", M.TEMP, "Battery temperature", M.BATT_V, "Battery current", "Grid voltage",
    "Grid frequency", "PV power 1", "PV power 2", "PV power 3", "Load power essential", "AC output voltage")

@Composable
fun HistoryScreen(state: UiState, vm: MainViewModel) {
    var siteName by rememberSaveable { mutableStateOf(ALL_SITES) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    var back by rememberSaveable { mutableIntStateOf(0) }       // 0 = bis jetzt, 1 = ein Zeitraum zurück …
    var tick by remember { mutableIntStateOf(0) }                // erhöht sich jede Minute → Diagramm wandert mit
    var extra by rememberSaveable { mutableStateOf("(keiner)") }
    var reload by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<HistoryResult?>(null) }
    var info by remember { mutableStateOf("") }

    val (days, interval) = PERIODS[period].second
    val zone = ZoneId.systemDefault()
    // Zeitraum endet rechts genau bei der aktuellen Uhrzeit (bzw. ganze Zeiträume davor)
    val now = remember(tick, back, period) { Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES) }
    val end = now.minusSeconds(back * days * 86400)
    val start = end.minusSeconds(days * 86400)
    LaunchedEffect(back) {
        if (back == 0) while (true) { kotlinx.coroutines.delay(60_000); tick++ }
    }
    val sites = if (siteName == ALL_SITES) state.activeSites else state.sites.filter { it.name == siteName }
    val siteKey = sites.joinToString { it.name }

    LaunchedEffect(siteName, period, back, tick, extra, reload, siteKey) {
        if (sites.isEmpty() && !state.demo) { info = "keine Anlage online – kurz warten"; return@LaunchedEffect }
        info = "lade …"
        val ms = listOf(M.PV, M.LOAD, M.GRID, M.BATT, M.SOC) + listOfNotNull(extra.takeIf { it != "(keiner)" })
        result = try {
            vm.loadHistory(sites, ms, start, end, interval).also {
                info = if (it.skipped.isEmpty()) "" else "ohne Daten: ${it.skipped.joinToString()}"
            }
        } catch (e: Exception) {
            info = "Fehler: ${e.message}"; null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
        Selector("Anlage", listOf(ALL_SITES) + state.visibleSites.map { it.name }, siteName, { siteName = it },
            Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PERIODS.forEachIndexed { i, (label, _) ->
                FilterChip(selected = period == i, onClick = { period = i }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back++ }) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "zurück")
            }
            val f = DateTimeFormatter.ofPattern(if (days == 1L) "EE dd.MM. HH:mm" else "dd.MM.yy HH:mm", java.util.Locale.GERMANY)
            val name = when (days) { 1L -> "Letzte 24 Stunden"; 7L -> "Letzte 7 Tage"; 30L -> "Letzte 30 Tage"; else -> "Letzte 12 Monate" }
            Text(if (back == 0) "$name · bis jetzt (${end.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))})"
                else "${start.atZone(zone).format(f)} – ${end.atZone(zone).format(f)}",
                Modifier.weight(1f), fontSize = 13.sp)
            IconButton(onClick = { if (back > 0) back-- }, enabled = back > 0) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "vor")
            }
            TextButton(onClick = { back = 0; tick++ }) { Text("Jetzt") }
        }
        Selector("Zusatzwert", EXTRAS.map { if (it == "(keiner)") it else M.label(it) },
            if (extra == "(keiner)") extra else M.label(extra),
            { sel -> extra = EXTRAS.first { (if (it == "(keiner)") it else M.label(it)) == sel } })
        if (info.isNotEmpty()) Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        result?.data?.let { d ->
            val note = if (interval == "1d") " (Tagesmittel)" else ""
            val who = if (siteName == ALL_SITES) " – Summe ${sites.size} Anlagen" else ""
            ChartCard { TimeChart("Leistung$note$who", "kW", listOf(
                ChartLine("PV", d[M.PV].orEmpty(), C.YELLOW, fill = true),
                ChartLine("Verbrauch", d[M.LOAD].orEmpty(), C.BLUE),
                ChartLine("Netz", d[M.GRID].orEmpty(), C.RED),
            ), start, end, scale = 0.001, height = 200.dp) }
            ChartCard { TimeChart("Batterie$note", "kW", listOf(ChartLine("Batterie", d[M.BATT].orEmpty(), C.GREEN, negColor = C.ORANGE)),
                start, end, scale = 0.001) }
            ChartCard { TimeChart("Ladestand" + if (siteName == ALL_SITES) " (Mittel)" else "", "%",
                listOf(ChartLine("", d[M.SOC].orEmpty(), C.GREEN)), start, end, yRange = 0.0 to 100.0, height = 130.dp) }
            if (extra != "(keiner)") ChartCard { TimeChart(M.label(extra), M.unit(extra),
                listOf(ChartLine("", d[extra].orEmpty(), C.VIOLET)), start, end, height = 140.dp) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ======================================================================= //
// Summen
// ======================================================================= //
@Composable
fun TotalsScreen(state: UiState, vm: MainViewModel) {
    var siteName by rememberSaveable { mutableStateOf(ALL_SITES) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var force by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<TotalsResult?>(null) }
    var info by remember { mutableStateOf("") }
    val sites = if (siteName == ALL_SITES) state.activeSites else state.sites.filter { it.name == siteName }
    val siteKey = sites.joinToString { it.name }

    LaunchedEffect(siteName, force, siteKey) {
        if (sites.isEmpty() && !state.demo) { info = "keine Anlage online – kurz warten"; return@LaunchedEffect }
        info = "lade …"
        result = try {
            vm.loadTotals(sites, force > 0).also {
                info = if (it.skipped.isEmpty()) "" else "ohne Daten: ${it.skipped.joinToString()}"
            }
        } catch (e: Exception) {
            info = "Fehler: ${e.message}"; null
        }
    }

    val today = LocalDate.now()
    val n = TOTAL_COLUMNS.size
    val total = sortedMapOf<LocalDate, DoubleArray>()
    result?.perSite?.values?.forEach { days ->
        days.forEach { (d, v) -> val row = total.getOrPut(d) { DoubleArray(n) }; v.forEachIndexed { i, x -> row[i] += x } }
    }
    val last30 = (0 until 30).map { today.minusDays(it.toLong()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Selector("Anlage", listOf(ALL_SITES) + state.visibleSites.map { it.name }, siteName, { siteName = it },
                Modifier.weight(1f).padding(top = 6.dp))
            TextButton(onClick = { force++ }) { Text("Neu laden") }
        }
        if (info.isNotEmpty()) Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (result == null) return@Column

        val asc = last30.reversed()
        ChartCard { BarChart("Letzte 30 Tage" + if (siteName == ALL_SITES) " – Summe ${result?.perSite?.size} Anlagen" else "",
            asc.map { "${it.dayOfMonth}.${it.monthValue}." },
            listOf("PV-Ertrag" to C.YELLOW, "Verbrauch" to C.BLUE, "Einspeisung" to C.GREEN),
            asc.map { d -> total[d]?.let { doubleArrayOf(it[1], it[0], it[5]) } ?: DoubleArray(3) }) }

        TabRow(selectedTabIndex = tab) {
            listOf("30 Tage", "12 Monate", "Vergleich").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Spacer(Modifier.height(4.dp))
        val cols = TOTAL_COLUMNS.map { it.second }
        ChartCard { when (tab) {
            0 -> KwhTable("Tag", cols, last30.map { d ->
                "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.format(DateTimeFormatter.ofPattern("dd.MM."))}" to (total[d] ?: DoubleArray(n))
            })
            1 -> {
                val months = (0 until 12).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }
                KwhTable("Monat", cols, months.map { m ->
                    val v = DoubleArray(n)
                    total.filterKeys { it.year == m.year && it.monthValue == m.monthValue }.values
                        .forEach { row -> row.forEachIndexed { i, x -> v[i] += x } }
                    "${MONTHS[m.monthValue - 1]} ${m.year}" to v
                })
            }
            else -> {
                val since30 = today.minusDays(29)
                val since12 = today.withDayOfMonth(1).minusMonths(11)
                KwhTable("Anlage", listOf("PV heute", "PV 30 Tage", "PV 12 Mon.", "Verbr. 12 M.", "Bezug 12 M.", "Einsp. 12 M."),
                    result!!.perSite.toSortedMap().map { (name, days) ->
                        fun s(i: Int, since: LocalDate) = days.filterKeys { !it.isBefore(since) }.values.sumOf { it[i] }
                        name to doubleArrayOf(days[today]?.get(1) ?: 0.0, s(1, since30), s(1, since12),
                            s(0, since12), s(4, since12), s(5, since12))
                    })
            }
        } }
        Spacer(Modifier.height(16.dp))
    }
}

// ======================================================================= //
// Einstellungen
// ======================================================================= //
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Konto", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        Text(if (state.demo) "Demo-Modus (Zufallswerte)" else "Angemeldet als ${state.email}")
        OutlinedButton(onClick = { vm.logout() }) { Text(if (state.demo) "Demo beenden" else "Abmelden (gespeichertes Passwort löschen)") }
        HorizontalDivider()

        Text("Aktualisierung", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 30, 60).forEach { s ->
                FilterChip(selected = state.intervalSec == s, onClick = { vm.setInterval(s) }, label = { Text("$s s") })
            }
        }
        Text("Werte werden nur abgefragt, solange die App geöffnet ist.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()

        HaSection(state, vm)
        HorizontalDivider()
        Text("Angezeigte Anlagen", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        // Summen-Karte (Gesamt bzw. Energiefluss-Summe) ein-/ausblenden
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gesamt / Energiefluss-Summe", fontWeight = FontWeight.Bold)
                Text("Summe aller Anlagen, die online sind (Umschalten per Knopf „Flow“ / „Gauges“)", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = TOTAL_CARD !in state.hidden, onCheckedChange = { vm.toggleHidden(TOTAL_CARD) })
        }
        state.sites.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name)
                    Text(state.live[s.name]?.message ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.name !in state.hidden, onCheckedChange = { vm.toggleHidden(s.name) })
            }
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Image(painterResource(R.drawable.logo_koch), contentDescription = "Elektro & IT-Service Koch",
            colorFilter = if (isSystemInDarkTheme()) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
            modifier = Modifier.fillMaxWidth().height(120.dp))
        Text("Solar Übersicht 1.4.2 · Daten von solar-assistant.io (inoffiziell)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Name der Gesamt-Karte (auch Schlüssel für die gespeicherte Ansicht) */
const val TOTAL_CARD = "Gesamt"

/**
 * Umschaltknopf im Kopfband einer Karte. Die Beschriftung nennt die andere Ansicht:
 * bei Gauges steht "Flow" darauf, beim Energiefluss "Gauges".
 */
@Composable
private fun ViewToggle(flow: Boolean, onLight: Boolean, onToggle: () -> Unit) {
    val accent = if (onLight) Color.White else MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .border(1.5.dp, accent, RoundedCornerShape(50))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(if (flow) "Gauges" else "Flow", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

/** Energie heute je Stelle (Reihenfolge wie TOTAL_COLUMNS: Verbrauch, PV, geladen, entladen, Bezug, Einspeisung) */
@Composable
internal fun EnergyTodayRow(t: DoubleArray, dim: Boolean) {
    val items = listOf("PV" to t[1], "Haus" to t[0], "Bezug" to t[4], "Einsp." to t[5], "geladen" to t[2], "entladen" to t[3])
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Energie heute (kWh)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Row(Modifier.fillMaxWidth()) {
            items.forEach { (label, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtKwh(v), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = if (dim) C.GREY else MaterialTheme.colorScheme.onSurface)
                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
