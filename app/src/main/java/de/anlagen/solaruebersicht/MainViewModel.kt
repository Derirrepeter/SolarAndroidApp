package de.anlagen.solaruebersicht

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class UiState(
    val loggedIn: Boolean = false,
    val demo: Boolean = false,
    val busy: Boolean = false,
    val loginError: String? = null,
    val email: String = "",
    val sites: List<Site> = emptyList(),
    val live: Map<String, SiteLive> = emptyMap(),
    val lastUpdate: Long = 0,
    val hidden: Set<String> = emptySet(),
    val flowCards: Set<String> = setOf(TOTAL_CARD),   // Karten in der Energiefluss-Ansicht
    val intervalSec: Int = 10,
) {
    val visibleSites get() = sites.filter { it.name !in hidden }
    val activeSites get() = visibleSites.filter { live[it.name]?.status == Status.ONLINE }
}

data class HistoryResult(val data: Map<String, Series>, val skipped: List<String>)
data class TotalsResult(val perSite: Map<String, Map<LocalDate, DoubleArray>>, val skipped: List<String>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(UiState(email = prefs.email, hidden = prefs.hidden, intervalSec = prefs.interval, flowCards = prefs.flowCards))
    val state: StateFlow<UiState> = _state

    private var client: SolarClient? = null
    private val haClients = mutableMapOf<String, HaClient>()       // Name → Home-Assistant-Zugang
    val haConfigs: List<HaConfig> get() = prefs.haSites
    private var pollJob: Job? = null
    private var foreground = false
    private val totalsCache = mutableMapOf<String, Pair<Long, Map<LocalDate, DoubleArray>>>()

    init {
        // gespeicherte Anmeldung → automatisch verbinden
        val pw = prefs.password
        if (prefs.email.isNotBlank() && pw != null) login(prefs.email, pw, remember = true)
    }

    // ---- Anmeldung -------------------------------------------------------- //
    fun login(email: String, password: String, remember: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, loginError = null) }
            try {
                val c = SolarClient(email.trim(), password)
                c.login()
                val sites = c.listSites()
                client = c
                prefs.email = email.trim()
                if (remember) prefs.savePassword(password) else prefs.clearPassword()
                totalsCache.clear()
                val all = sites + haSites()
                _state.update {
                    it.copy(loggedIn = true, demo = false, busy = false, email = email.trim(), sites = all,
                        live = all.associate { s -> s.name to SiteLive() })
                }
                restartPolling()
            } catch (e: LoginException) {
                prefs.clearPassword()
                _state.update { it.copy(busy = false, loginError = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, loginError = "Verbindung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Home-Assistant-Anlagen aus den Einstellungen (nur mit gespeichertem Token) */
    private fun haSites(): List<Site> {
        haClients.clear()
        return prefs.haSites.mapNotNull { cfg ->
            val token = prefs.haToken(cfg.name) ?: return@mapNotNull null
            haClients[cfg.name] = HaClient(cfg, token)
            Site(cfg.name, cfg.url, null, kind = "ha", openUrl = cfg.url.trimEnd('/') + cfg.openPath)
        }
    }

    /** HA-Anlage speichern (token = null: vorhandenes Token behalten) und Liste neu aufbauen */
    fun saveHa(cfg: HaConfig, token: String?, oldName: String?) {
        val list = prefs.haSites.filter { it.name != oldName && it.name != cfg.name } + cfg
        prefs.haSites = list
        if (oldName != null && oldName != cfg.name) {
            val t = prefs.haToken(oldName); prefs.setHaToken(oldName, null)
            if (token.isNullOrEmpty() && t != null) prefs.setHaToken(cfg.name, t)
        }
        if (!token.isNullOrBlank()) prefs.setHaToken(cfg.name, token.trim())
        totalsCache.remove(cfg.name)
        rebuildHaSites()
    }

    fun deleteHa(name: String) {
        prefs.haSites = prefs.haSites.filter { it.name != name }
        prefs.setHaToken(name, null)
        rebuildHaSites()
    }

    fun hasHaToken(name: String) = prefs.haToken(name) != null

    private fun rebuildHaSites() {
        if (_state.value.demo) return
        val sa = _state.value.sites.filter { it.kind != "ha" }
        val all = sa + haSites()
        _state.update { st -> st.copy(sites = all, live = all.associate { it.name to (st.live[it.name] ?: SiteLive()) }) }
        restartPolling()
    }

    /** Verbindung testen: liefert eine kurze Übersicht der gelesenen Werte */
    suspend fun testHa(cfg: HaConfig, token: String): String {
        val c = HaClient(cfg, token.ifEmpty { prefs.haToken(cfg.name) ?: "" })
        val (v, _) = c.readLive()
        if (v.isEmpty()) return "Verbunden, aber keiner der Sensoren liefert einen Zahlenwert."
        return v.entries.joinToString("\n") { (k, x) -> "${M.label(k)}: ${fmt(x)} ${M.unit(k)}" }
    }

    suspend fun listHaSensors(cfg: HaConfig, token: String) =
        HaClient(cfg, token.ifEmpty { prefs.haToken(cfg.name) ?: "" }).listSensors()

    fun startDemo() {
        val sites = listOf("cook", "guggenmos", "heisspi4", "hengeler", "schneiderpfronten", "woanders")
            .map { Site(it, "", 10000.0) }
        client = null
        totalsCache.clear()
        _state.update {
            it.copy(loggedIn = true, demo = true, loginError = null, sites = sites,
                live = sites.associate { s -> s.name to SiteLive() })
        }
        restartPolling()
    }

    fun logout() {
        pollJob?.cancel()
        prefs.clearPassword()
        client = null
        totalsCache.clear()
        _state.update { UiState(email = it.email, hidden = it.hidden, intervalSec = it.intervalSec, flowCards = it.flowCards) }
    }

    fun setInterval(sec: Int) {
        prefs.interval = sec
        _state.update { it.copy(intervalSec = sec) }
        restartPolling()
    }

    /** Knopf "Ansicht": Karte zwischen Gauges und Energiefluss umschalten */
    fun toggleFlow(name: String) {
        val f = _state.value.flowCards.toMutableSet().apply { if (!add(name)) remove(name) }
        prefs.flowCards = f
        _state.update { it.copy(flowCards = f) }
    }

    fun toggleHidden(name: String) {
        val h = _state.value.hidden.toMutableSet().apply { if (!add(name)) remove(name) }
        prefs.hidden = h
        _state.update { it.copy(hidden = h) }
    }

    // ---- Live-Abfrage ------------------------------------------------------ //
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg) restartPolling() else pollJob?.cancel()
    }

    fun refreshNow() {
        // offline gemeldete Anlagen sofort erneut prüfen
        _state.update { st -> st.copy(live = st.live.mapValues { (_, v) -> v.copy(retryAt = 0) }) }
        restartPolling()
    }

    private fun restartPolling() {
        pollJob?.cancel()
        if (!foreground || !_state.value.loggedIn) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollOnce()
                delay(_state.value.intervalSec * 1000L)
            }
        }
    }

    private suspend fun pollOnce() {
        val sites = _state.value.visibleSites
        val results = coroutineScope { sites.map { s -> async { s.name to fetchSite(s) } }.awaitAll() }
        _state.update { st -> st.copy(live = st.live + results, lastUpdate = System.currentTimeMillis()) }
    }

    private suspend fun fetchSite(site: Site): SiteLive {
        val prev = _state.value.live[site.name] ?: SiteLive()
        if (prev.retryAt > System.currentTimeMillis()) return prev
        if (_state.value.demo) return Demo.live(prev, site.name == "woanders", site.name == "hengeler")
        if (site.kind == "ha") return fetchHa(site, prev)
        val c = client ?: return prev
        return try {
            val (raw, last) = c.readLive(site.url, M.LIVE)
            if (prev.lastTs > 0 && last > 0 && last < prev.lastTs - 30)
                throw IllegalStateException("unplausible Antwort verworfen")   // Zeitstempel springt zurück
            val settings = prev.settings ?: try {
                c.settings(site.url)
            } catch (e: Exception) {
                emptyMap()        // Standard-Skalen verwenden
            }
            // Energie heute (kWh) höchstens alle 5 Minuten neu lesen
            val todayDue = System.currentTimeMillis() - prev.todayAt > 300_000
            val today = if (todayDue) runCatching { c.todayTotals(site.url) }.getOrNull() ?: prev.today else prev.today
            // Betriebsmodus + Statussymbol (ok/fault) höchstens einmal pro Minute nachlesen
            val inv = if (System.currentTimeMillis() - prev.modeAt < 60_000) null
            else runCatching { c.inverterStatus(site.url) }.getOrNull()
            SiteLive(Status.ONLINE, "online", plausible(raw), last, settings,
                mode = inv?.mode ?: prev.mode, fault = inv?.fault ?: prev.fault,
                modeAt = if (inv != null) System.currentTimeMillis() else prev.modeAt,
                today = today, todayAt = if (todayDue) System.currentTimeMillis() else prev.todayAt)
        } catch (e: OfflineException) {
            // bei solar-assistant.io als getrennt gemeldet → nur alle 5 Minuten prüfen
            SiteLive(Status.OFFLINE, "offline", emptyMap(), 0.0, prev.settings, System.currentTimeMillis() + 300_000)
        } catch (e: LoginException) {
            prev.copy(status = Status.ERROR, message = "Anmeldung: ${e.message}", values = emptyMap())
        } catch (e: Exception) {
            prev.copy(status = Status.ERROR, message = "Fehler: ${e.message ?: e.javaClass.simpleName}", values = emptyMap())
        }
    }

    private suspend fun fetchHa(site: Site, prev: SiteLive): SiteLive {
        val c = haClients[site.name] ?: return prev.copy(status = Status.ERROR, message = "kein Token")
        return try {
            val (raw, last) = c.readLive()
            if (raw.isEmpty()) throw HaException("keine Werte – Sensoren prüfen")
            val settings = prev.settings ?: try {
                c.autoLimits()
            } catch (e: Exception) {
                emptyMap()
            }
            // Energie heute (kWh) höchstens alle 5 Minuten neu lesen
            val todayDue = System.currentTimeMillis() - prev.todayAt > 300_000
            val today = if (todayDue) runCatching { c.todayTotals() }.getOrNull() ?: prev.today else prev.today
            val extras = runCatching { c.readExtras() }.getOrDefault(prev.extras)
            SiteLive(Status.ONLINE, "online", plausible(raw), last, settings, extras = extras,
                today = today, todayAt = if (todayDue) System.currentTimeMillis() else prev.todayAt)
        } catch (e: HaException) {
            prev.copy(status = Status.ERROR, message = "Fehler: ${e.message}", values = emptyMap())
        } catch (e: java.io.IOException) {
            // nicht erreichbar (z. B. VPN aus) → als offline zeigen, in 1 Minute erneut versuchen
            SiteLive(Status.OFFLINE, "nicht erreichbar (VPN?)", emptyMap(), 0.0, prev.settings, System.currentTimeMillis() + 60_000)
        }
    }

    // ---- Verläufe und Summen ---------------------------------------------- //
    suspend fun loadHistory(sites: List<Site>, measurements: List<String>, start: Instant, end: Instant, interval: String): HistoryResult {
        if (_state.value.demo) return HistoryResult(Demo.series(measurements, start, end, interval), emptyList())
        val c = client
        val results = coroutineScope {
            sites.map { s ->
                async {
                    s.name to runCatching {
                        if (s.kind == "ha") haClients.getValue(s.name).fetchSeries(measurements, start, end, interval)
                        else c!!.fetchSeries(s.url, measurements, start, end, interval)
                    }.getOrNull()
                }
            }.awaitAll()
        }
        val ok = results.mapNotNull { it.second }
        if (ok.isEmpty()) throw IllegalStateException("keine Daten")
        return HistoryResult(combineSeries(ok), results.filter { it.second == null }.map { it.first })
    }

    suspend fun loadTotals(sites: List<Site>, force: Boolean): TotalsResult {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val firstMonth = today.withDayOfMonth(1).minusMonths(11)
        val start = firstMonth.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        if (_state.value.demo) return TotalsResult(sites.associate { it.name to Demo.totals(firstMonth, today) }, emptyList())
        val c = client
        val results = coroutineScope {
            sites.map { s ->
                async {
                    val hit = totalsCache[s.name]
                    if (!force && hit != null && System.currentTimeMillis() - hit.first < 600_000) s.name to hit.second
                    else s.name to runCatching {
                        if (s.kind == "ha") haClients.getValue(s.name).fetchDailyTotals(start, end)
                        else c!!.fetchDailyTotals(s.url, start, end)
                    }.getOrNull()
                        ?.also { totalsCache[s.name] = System.currentTimeMillis() to it }
                }
            }.awaitAll()
        }
        val ok = results.filter { it.second != null }.associate { it.first to it.second!! }
        if (ok.isEmpty()) throw IllegalStateException("keine Daten")
        return TotalsResult(ok, results.filter { it.second == null }.map { it.first })
    }
}

/** Zufallswerte zum Ausprobieren ohne Anmeldung. */
object Demo {
    private val settings = mapOf("max_ac_w" to 12000.0, "max_solar_w" to 11500.0, "max_sell_w" to 800.0,
        "max_charge_a" to 45.0, "max_discharge_a" to 65.0, "stop_discharge_pct" to 18.0)

    fun live(prev: SiteLive, extras: Boolean = false, fault: Boolean = false): SiteLive {
        val pv = Random.nextDouble(0.0, 9000.0)
        val load = Random.nextDouble(300.0, 3000.0)
        val batt = Random.nextDouble(-2500.0, 2400.0)
        val soc = ((prev.values[M.SOC] ?: Random.nextDouble(10.0, 95.0)) + Random.nextDouble(-1.0, 1.0)).coerceIn(5.0, 100.0)
        val v = mapOf(M.PV to pv, M.LOAD to load, M.BATT to batt, M.GRID to load - pv + batt, M.SOC to soc,
            M.TEMP to Random.nextDouble(25.0, 60.0), M.BATT_V to 53.5, M.PV_TODAY to Random.nextDouble(2.0, 40.0))
        return SiteLive(Status.ONLINE, "online (Demo)", v, System.currentTimeMillis() / 1000.0, settings,
            extras = if (extras) demoExtras() else emptyList(),
            mode = if (fault) "Fault (Demo)" else "Discharge above 20%", fault = fault,
            today = prev.today ?: doubleArrayOf(Random.nextDouble(6.0, 18.0), Random.nextDouble(10.0, 45.0),
                Random.nextDouble(2.0, 9.0), Random.nextDouble(1.0, 8.0), Random.nextDouble(0.5, 5.0), Random.nextDouble(3.0, 25.0)))
    }

    fun series(measurements: List<String>, start: Instant, end: Instant, interval: String): Map<String, Series> {
        val step = when (interval) { "2m" -> 120L; "15m" -> 900L; "1h" -> 3600L; else -> 86400L }
        val out = measurements.associateWith { mutableListOf<Pair<Double, Double?>>() }
        var t = start.epochSecond
        var soc = 50.0
        while (t < end.epochSecond) {
            val time = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault())
            val hour = time.hour + time.minute / 60.0
            val sun = if (step < 86400) max(0.0, sin((hour - 6) / 14 * PI)) else 0.35
            val pv = 9000 * sun * Random.nextDouble(0.6, 1.0)
            val load = Random.nextDouble(300.0, 2500.0)
            val batt = max(-4000.0, min(3000.0, (pv - load) * 0.7))
            soc = (soc + batt * step / 3600 / 10000 * 20).coerceIn(10.0, 100.0)
            val v = mapOf(M.PV to pv, M.LOAD to load, M.BATT to batt, M.GRID to load - pv + batt, M.SOC to soc)
            measurements.forEach { m -> out.getValue(m).add(t.toDouble() to (v[m] ?: Random.nextDouble(20.0, 50.0))) }
            t += step
        }
        return out
    }

    fun totals(from: LocalDate, to: LocalDate): Map<LocalDate, DoubleArray> {
        val out = sortedMapOf<LocalDate, DoubleArray>()
        var d = from
        while (!d.isAfter(to)) {
            val pv = Random.nextDouble(3.0, 45.0)
            val load = Random.nextDouble(5.0, 20.0)
            out[d] = doubleArrayOf(load, pv, pv * 0.3, pv * 0.28, max(0.0, load - pv * 0.5), max(0.0, pv * 0.5 - load * 0.3))
            d = d.plusDays(1)
        }
        return out
    }
}
