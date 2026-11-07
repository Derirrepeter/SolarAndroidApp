package de.anlagen.solaruebersicht

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Einstellungen einer Home-Assistant-Anlage.
 * entities: Messwert (z. B. M.PV) → Liste von Entitäts-IDs. Leistungen werden addiert,
 * Ladestand/Temperatur/Spannung gemittelt. invert: Vorzeichen dieser Messwerte umdrehen.
 */
data class HaConfig(
    val name: String,
    val url: String,                       // z. B. http://10.2.10.141:8123
    val openPath: String = "",             // z. B. /dashboard-haus/energy (Antippen der Karte)
    val entities: Map<String, List<String>> = emptyMap(),
    val invert: Set<String> = emptySet(),
    val limits: Map<String, Double> = emptyMap(),   // eigene Skalen (leer = automatisch)
    val extras: List<Extra> = emptyList(),          // Zusatzkomponenten (Wallbox, Auto, Lüftung …)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("url", url); put("openPath", openPath)
        put("entities", JSONObject().apply { entities.forEach { (k, v) -> put(k, JSONArray(v)) } })
        put("invert", JSONArray(invert.toList()))
        put("limits", JSONObject().apply { limits.forEach { (k, v) -> put(k, v) } })
        put("extras", Extra.toJson(extras))
    }

    companion object {
        fun fromJson(o: JSONObject): HaConfig {
            val ents = o.optJSONObject("entities") ?: JSONObject()
            val lims = o.optJSONObject("limits") ?: JSONObject()
            val inv = o.optJSONArray("invert") ?: JSONArray()
            val entities = ents.keys().asSequence().associateWith { k ->
                val a = ents.getJSONArray(k); (0 until a.length()).map { a.getString(it) }
            }.toMutableMap()
            // bis Version 1.4: Ladestand = Mittel aus Akku 1–3 (BMS) – weicht vom echten Wert ab
            if (entities[M.SOC] == OLD_SOC) entities[M.SOC] = listOf("sensor.deye_battery_soc")
            return HaConfig(
                o.getString("name"), o.getString("url"), o.optString("openPath"),
                entities,
                (0 until inv.length()).map { inv.getString(it) }.toSet(),
                lims.keys().asSequence().associateWith { lims.getDouble(it) },
                when {
                    o.has("extras") -> Extra.fromJson(o.getJSONArray("extras"))
                    // ältere Einstellung des Hauses: Zusatzkomponenten automatisch ergänzen
                    "sensor.pv_deye_leistung" in entities[M.PV].orEmpty() -> Extra.haus()
                    else -> emptyList()
                },
            )
        }

        private val OLD_SOC = listOf("sensor.akku_1_akku_1_soc", "sensor.akku_2_akku_2_soc", "sensor.akku_3_akku_3_soc")

        /** Vorlage: Deye über Solarman, wie im Home Assistant "Haus" eingerichtet */
        fun template() = HaConfig(
            name = "Haus",
            url = "http://10.2.10.141:8123",
            openPath = "/dashboard-haus/energy",
            entities = mapOf(
                M.PV to listOf("sensor.pv_deye_leistung", "sensor.deye_generator_total_power",
                    "sensor.shellyplug_s_7c87ceba53a9_power"),
                M.LOAD to listOf("sensor.deye_load_total_power"),
                M.GRID to listOf("sensor.deye_total_grid_power"),
                M.BATT to listOf("sensor.deye_battery_output_power"),
                M.SOC to listOf("sensor.deye_battery_soc"),
                M.TEMP to listOf("sensor.deye_deye_heat_sink_temperature"),
                M.BATT_V to listOf("sensor.deye_battery_voltage"),
                M.PV_TODAY to listOf("sensor.deye_daily_production", "sensor.deye_generator_daily_energie"),
            ),
            invert = setOf(M.BATT),
            extras = Extra.haus(),
        )

        /** Reihenfolge und Beschriftung der Felder im Einstellungsdialog */
        val FIELDS = listOf(
            M.PV to "PV-Leistung (W)", M.LOAD to "Verbrauch (W)", M.GRID to "Netz (W, Bezug +)",
            M.BATT to "Batterie (W, Laden +)", M.SOC to "Ladestand (%)", M.TEMP to "WR-Temperatur",
            M.BATT_V to "Batteriespannung", M.PV_TODAY to "PV heute (kWh, Tageszähler)",
        )
    }
}

class HaException(msg: String) : Exception(msg)

/** Zugriff auf Home Assistant per REST (Live, Verlauf) und WebSocket (Statistik). */
class HaClient(val cfg: HaConfig, rawToken: String) {
    private val token = rawToken.trim()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val base = cfg.url.trimEnd('/')
    private val units = mutableMapOf<String, String>()     // Einheit je Entität (aus /api/states)

    private fun get(path: String): String {
        val req = Request.Builder().url(base + path).header("Authorization", "Bearer $token").build()
        return http.newCall(req).execute().use { r: Response ->
            when {
                r.code == 401 -> throw HaException(TOKEN_HINT)
                !r.isSuccessful -> throw IOException("HTTP ${r.code}")
                else -> r.body!!.string()
            }
        }
    }

    private fun allIds() = cfg.entities.values.flatten().distinct()
    private fun isAvg(metric: String) = M.isAverage(metric)

    /** Wert in Basiseinheit: Leistung in W, Energie in kWh */
    private fun normalize(v: Double, unit: String?): Double = when (unit) {
        "kW" -> v * 1000
        "MW" -> v * 1_000_000
        "Wh" -> v / 1000
        "MWh" -> v * 1000
        else -> v
    }

    // ---- Live-Werte ------------------------------------------------------- //
    suspend fun readLive(): Pair<Map<String, Double>, Double> = withContext(Dispatchers.IO) {
        val raw = mutableMapOf<String, Double>()
        var newest = 0.0
        for (id in allIds()) {
            val o = try {
                JSONObject(get("/api/states/$id"))
            } catch (e: IOException) {
                if (e.message == "HTTP 404") continue else throw e
            }
            val unit = o.optJSONObject("attributes")?.optString("unit_of_measurement")
            if (!unit.isNullOrEmpty()) units[id] = unit
            val v = o.optString("state").toDoubleOrNull() ?: continue     // unavailable/unknown
            raw[id] = normalize(v, unit)
            o.optString("last_updated").takeIf { it.isNotEmpty() }?.let {
                newest = maxOf(newest, OffsetDateTime.parse(it).toInstant().epochSecond.toDouble())
            }
        }
        val values = mutableMapOf<String, Double>()
        for ((metric, ids) in cfg.entities) {
            val vs = ids.mapNotNull { raw[it] }
            if (vs.isEmpty()) continue
            var v = if (isAvg(metric)) vs.average() else vs.sum()
            if (metric in cfg.invert) v = -v
            values[metric] = v
        }
        values to newest
    }

    // ---- Zusatzkomponenten (Wallbox, Auto, Lüftung, Klima …) ---------------- //
    private val todayCache = mutableMapOf<String, Pair<Long, Double>>()   // Zähler → (Abfragezeit, kWh heute)

    suspend fun readExtras(): List<ExtraLive> = withContext(Dispatchers.IO) {
        if (cfg.extras.isEmpty()) return@withContext emptyList()
        val states = mutableMapOf<String, Pair<String, String?>>()        // Zustand, Einheit
        for (id in cfg.extras.flatMap { it.entities }.distinct()) {
            val o = try {
                JSONObject(get("/api/states/$id"))
            } catch (e: IOException) {
                if (e.message == "HTTP 404") continue else throw e
            }
            val unit = o.optJSONObject("attributes")?.optString("unit_of_measurement")?.ifEmpty { null }
            unit?.let { units[id] = it }
            states[id] = o.optString("state") to unit
        }
        val today = energyToday(cfg.extras.mapNotNull { it.energy })
        cfg.extras.map { e ->
            ExtraLive(
                e.name, e.icon,
                e.power?.let { p -> states[p]?.let { (s, u) -> s.toDoubleOrNull()?.let { normalize(it, u) } } },
                e.energy?.let { today[it] },
                e.info.mapNotNull { i ->
                    states[i.entity]?.let { (s, u) -> formatExtraState(i.entity, s, u, i.skipZero) }?.let { "${i.label}: $it" }
                },
            )
        }
    }

    /** Energie seit Mitternacht je Zähler (kWh) aus dem Verlauf; Zähler-Rücksetzungen werden erkannt. Alle 5 min neu. */
    private fun energyToday(ids: List<String>): Map<String, Double> {
        val now = System.currentTimeMillis()
        val due = ids.filter { (todayCache[it]?.first ?: 0L) < now - 300_000 }
        if (due.isNotEmpty()) runCatching {
            val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val url = "$base/api/history/period/$midnight".toHttpUrl().newBuilder()
                .addQueryParameter("filter_entity_id", due.joinToString(","))
                .addQueryParameter("minimal_response", "")
                .addQueryParameter("no_attributes", "")
                .build()
            val arr = JSONArray(get(url.toString().removePrefix(base)))
            for (i in 0 until arr.length()) {
                val rows = arr.getJSONArray(i)
                if (rows.length() == 0) continue
                val id = rows.getJSONObject(0).getString("entity_id")
                var sum = 0.0
                var last: Double? = null
                for (r in 0 until rows.length()) {
                    val v = rows.getJSONObject(r).optString("state").toDoubleOrNull()?.let { normalize(it, units[id]) } ?: continue
                    val l = last
                    when {
                        l == null -> last = v
                        v >= l -> { sum += v - l; last = v }
                        v < l * 0.5 -> { sum += v; last = v }     // Zähler wurde zurückgesetzt
                        // winziger Rücksprung (z. B. nach "nicht verfügbar"): ignorieren
                    }
                }
                todayCache[id] = now to sum
            }
        }
        return ids.mapNotNull { id -> todayCache[id]?.let { id to it.second } }.toMap()
    }

    // ---- Verlauf: Tag/Woche aus den Rohdaten, Monat/Jahr aus der Statistik ---- //
    suspend fun fetchSeries(measurements: List<String>, start: Instant, end: Instant, interval: String): Map<String, Series> {
        val step = when (interval) { "2m" -> 120L; "15m" -> 900L; "1h" -> 3600L; else -> 86400L }
        val wanted = measurements.filter { cfg.entities[it]?.isNotEmpty() == true }
        val ids = wanted.flatMap { cfg.entities.getValue(it) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val perEntity: Map<String, Map<Long, Double>> =
            if (step <= 900) rawHistory(ids, start, end, step) else {
                val st = statsMean(ids, start, end, if (step == 3600L) "hour" else "day")
                // Sensoren ohne Langzeitstatistik (z. B. Deye-Ladestand): aus dem normalen Verlauf mitteln
                val missing = ids.filter { st[it].isNullOrEmpty() }
                if (missing.isEmpty()) st else st + rawHistory(missing, start, end, step)
            }
        return wanted.associateWith { metric ->
            val cols = cfg.entities.getValue(metric).mapNotNull { perEntity[it] }
            val keys = cols.flatMap { it.keys }.toSortedSet()
            keys.map { t ->
                val vs = cols.mapNotNull { it[t] }
                var v: Double? = if (vs.isEmpty()) null else if (isAvg(metric)) vs.average() else vs.sum()
                if (v != null && metric in cfg.invert) v = -v
                t.toDouble() to v
            }
        }
    }

    /** Rohverlauf per REST, auf ein Zeitraster gemittelt; Lücken mit dem letzten Wert gefüllt. */
    private suspend fun rawHistory(ids: List<String>, start: Instant, end: Instant, step: Long) = withContext(Dispatchers.IO) {
        if (units.isEmpty()) runCatching { readLive() }
        val url = "$base/api/history/period/$start".toHttpUrl().newBuilder()
            .addQueryParameter("end_time", end.toString())
            .addQueryParameter("filter_entity_id", ids.joinToString(","))
            .addQueryParameter("minimal_response", "")
            .addQueryParameter("no_attributes", "")
            .build()
        val arr = JSONArray(get(url.toString().removePrefix(base)))
        val first = (start.epochSecond / step) * step
        val last = (end.epochSecond / step) * step
        val out = mutableMapOf<String, Map<Long, Double>>()
        for (i in 0 until arr.length()) {
            val rows = arr.getJSONArray(i)
            if (rows.length() == 0) continue
            val id = rows.getJSONObject(0).getString("entity_id")
            val sums = mutableMapOf<Long, Pair<Double, Int>>()
            var carry: Double? = null
            for (r in 0 until rows.length()) {
                val o = rows.getJSONObject(r)
                val v = o.optString("state").toDoubleOrNull()?.let { normalize(it, units[id]) }
                val t = OffsetDateTime.parse(o.getString("last_changed")).toInstant().epochSecond
                if (t < start.epochSecond) { carry = v; continue }
                if (v == null) continue
                val b = (t / step) * step
                val (s, n) = sums[b] ?: (0.0 to 0)
                sums[b] = (s + v) to (n + 1)
            }
            val series = mutableMapOf<Long, Double>()
            var b = first
            while (b < last + step && b < end.epochSecond) {
                val p = sums[b]
                if (p != null) carry = p.first / p.second
                carry?.let { series[b] = it }
                b += step
            }
            out[id] = series
        }
        out
    }

    private suspend fun statsMean(ids: List<String>, start: Instant, end: Instant, period: String): Map<String, Map<Long, Double>> {
        val res = ws(JSONObject().apply {
            put("type", "recorder/statistics_during_period")
            put("start_time", start.toString()); put("end_time", end.toString())
            put("statistic_ids", JSONArray(ids)); put("period", period)
            put("types", JSONArray(listOf("mean")))
            put("units", JSONObject().put("power", "W").put("energy", "kWh"))
        })
        return ids.associateWith { id ->
            val rows = res.optJSONArray(id) ?: JSONArray()
            (0 until rows.length()).mapNotNull { i ->
                val o = rows.getJSONObject(i)
                if (o.isNull("mean")) null else (o.getLong("start") / 1000) to o.getDouble("mean")
            }.toMap()
        }
    }

    // ---- Summen aus der Energie-Konfiguration -------------------------------- //
    /** Tagessummen in kWh je Spalte von TOTAL_COLUMNS (Verbrauch, PV, Batt. ein/aus, Bezug, Einspeisung) */
    suspend fun fetchDailyTotals(start: Instant, end: Instant, period: String = "day"): Map<LocalDate, DoubleArray> {
        val prefs = ws(JSONObject().put("type", "energy/get_prefs"))
        val solar = mutableListOf<String>(); val battIn = mutableListOf<String>(); val battOut = mutableListOf<String>()
        val gridIn = mutableListOf<String>(); val gridOut = mutableListOf<String>()
        val src = prefs.optJSONArray("energy_sources") ?: JSONArray()
        for (i in 0 until src.length()) {
            val s = src.getJSONObject(i)
            fun id(k: String) = s.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            when (s.optString("type")) {
                "solar" -> id("stat_energy_from")?.let(solar::add)
                "battery" -> { id("stat_energy_from")?.let(battOut::add); id("stat_energy_to")?.let(battIn::add) }
                "grid" -> {
                    id("stat_energy_from")?.let(gridIn::add); id("stat_energy_to")?.let(gridOut::add)
                    // ältere Konfiguration: Listen flow_from / flow_to
                    s.optJSONArray("flow_from")?.let { a -> for (j in 0 until a.length()) gridIn.add(a.getJSONObject(j).getString("stat_energy_from")) }
                    s.optJSONArray("flow_to")?.let { a -> for (j in 0 until a.length()) gridOut.add(a.getJSONObject(j).getString("stat_energy_to")) }
                }
            }
        }
        val all = (solar + battIn + battOut + gridIn + gridOut).distinct()
        if (all.isEmpty()) throw HaException("Energie-Dashboard in Home Assistant ist nicht eingerichtet")
        val res = ws(JSONObject().apply {
            put("type", "recorder/statistics_during_period")
            put("start_time", start.toString()); put("end_time", end.toString())
            put("statistic_ids", JSONArray(all)); put("period", period)
            put("types", JSONArray(listOf("change")))
            put("units", JSONObject().put("energy", "kWh"))
        })
        val zone = ZoneId.systemDefault()
        val perDay = sortedMapOf<LocalDate, MutableMap<String, Double>>()
        for (id in all) {
            val rows = res.optJSONArray(id) ?: continue
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                if (o.isNull("change")) continue
                // Tageswerte: +2 h schützt vor Sommerzeit-Versatz; 5-Minuten-Werte direkt zuordnen
                val shift = if (period == "day") 7_200_000 else 0
                val day = Instant.ofEpochMilli(o.getLong("start") + shift).atZone(zone).toLocalDate()
                perDay.getOrPut(day) { mutableMapOf() }.merge(id, o.getDouble("change"), Double::plus)
            }
        }
        return perDay.mapValues { (_, m) ->
            fun s(ids: List<String>) = ids.sumOf { m[it] ?: 0.0 }
            val pv = s(solar); val bin = s(battIn); val bout = s(battOut); val gin = s(gridIn); val gout = s(gridOut)
            // Verbrauch wie im Energie-Dashboard: Erzeugung + Bezug + Entladung − Einspeisung − Ladung
            doubleArrayOf(maxOf(0.0, pv + gin + bout - gout - bin), pv, bin, bout, gin, gout)
        }
    }

    /** Energie heute (kWh) aus der 5-Minuten-Statistik – nicht erst nach der vollen Stunde */
    suspend fun todayTotals(): DoubleArray? {
        val today = LocalDate.now()
        val midnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        return fetchDailyTotals(midnight, Instant.now().plusSeconds(3600), "5minute")[today]
    }

    // ---- Gauge-Skalen automatisch aus den Höchstwerten des letzten Jahres ---- //
    suspend fun autoLimits(): Map<String, Double> {
        val metrics = listOf(M.PV, M.LOAD, M.GRID, M.BATT)
        val ids = metrics.flatMap { cfg.entities[it].orEmpty() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val now = Instant.now()
        val res = ws(JSONObject().apply {
            put("type", "recorder/statistics_during_period")
            put("start_time", now.minusSeconds(365L * 86400).toString())
            put("statistic_ids", JSONArray(ids)); put("period", "month")
            put("types", JSONArray(listOf("max", "min")))
            put("units", JSONObject().put("power", "W"))
        })
        fun ext(id: String, key: String, highest: Boolean): Double? {
            val rows = res.optJSONArray(id) ?: return null
            val vs = (0 until rows.length()).mapNotNull { i -> rows.getJSONObject(i).let { if (it.isNull(key)) null else it.getDouble(key) } }
            return if (highest) vs.maxOrNull() else vs.minOrNull()
        }
        fun sumMax(m: String) = cfg.entities[m].orEmpty().mapNotNull { ext(it, "max", true) }.sum().takeIf { it > 0 }
        fun sumMin(m: String) = cfg.entities[m].orEmpty().mapNotNull { ext(it, "min", false) }.sum()
        val out = mutableMapOf<String, Double>()
        sumMax(M.PV)?.let { out["max_solar_w"] = niceCeil(it) }
        val top = kotlin.math.max(sumMax(M.LOAD) ?: 0.0, sumMax(M.GRID) ?: 0.0)
        if (top > 0) out["max_ac_w"] = niceCeil(top)
        val exp = -sumMin(M.GRID)
        if (exp > 0) out["max_sell_w"] = niceCeil(exp)
        if (cfg.entities[M.BATT]?.isNotEmpty() == true) {
            val hi = sumMax(M.BATT) ?: 0.0
            val lo = -sumMin(M.BATT)
            val (charge, discharge) = if (M.BATT in cfg.invert) lo to hi else hi to lo
            if (charge > 0) out["batt_charge_w"] = niceCeil(charge)
            if (discharge > 0) out["batt_discharge_w"] = niceCeil(discharge)
        }
        return out + cfg.limits         // eigene Angaben haben Vorrang
    }

    /** Alle Sensoren (für die Auswahl in den Einstellungen): ID → Name, Wert, Einheit */
    suspend fun listSensors(): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("/api/states"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.getString("entity_id").startsWith("sensor.") }
            .map {
                val a = it.optJSONObject("attributes") ?: JSONObject()
                Triple(it.getString("entity_id"), a.optString("friendly_name"),
                    it.optString("state") + " " + a.optString("unit_of_measurement"))
            }.sortedBy { it.first }
    }

    // ---- WebSocket: anmelden, einen Befehl senden, Antwort lesen ------------ //
    private suspend fun ws(command: JSONObject): JSONObject {
        val incoming = Channel<JSONObject>(Channel.UNLIMITED)
        val failed = CompletableDeferred<Throwable>()
        val wsUrl = base.replaceFirst("http", "ws") + "/api/websocket"
        val socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(JSONObject(text)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.complete(t) }
        })
        try {
            return withTimeout(60_000) {
                suspend fun next(): JSONObject {
                    if (failed.isCompleted) throw IOException("WebSocket: ${failed.await().message}")
                    return incoming.receive()
                }
                next()                                            // auth_required
                socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                val auth = next()
                if (auth.optString("type") != "auth_ok") throw HaException(TOKEN_HINT)
                socket.send(command.put("id", 1).toString())
                var result: JSONObject? = null
                while (result == null) {
                    val msg = next()
                    if (msg.optInt("id") != 1) continue
                    if (!msg.optBoolean("success")) throw HaException(msg.optJSONObject("error")?.optString("message") ?: "Fehler")
                    result = msg.optJSONObject("result") ?: JSONObject()
                }
                result
            }
        } finally {
            socket.close(1000, null)
        }
    }
}

const val TOKEN_HINT = "Token ungültig – bitte ein langlebiges Zugriffstoken aus Home Assistant eintragen " +
    "(Profil → Sicherheit → Langlebige Zugangstoken), nicht das Passwort."

/** Ein Home-Assistant-Token ist lang (ca. 180 Zeichen) und besteht aus drei Teilen mit Punkten. */
fun looksLikeHaToken(t: String) = t.trim().let { it.length > 100 && it.count { c -> c == '.' } == 2 && ' ' !in it }

/** Auf "schöne" Skalenwerte aufrunden: 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10 × 10^n */
fun niceCeil(v: Double): Double {
    if (v <= 0) return 0.0
    val mag = 10.0.pow(floor(log10(v)))
    val f = v / mag
    val nice = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).first { it >= f - 1e-9 }
    return ceil(nice * mag)
}
