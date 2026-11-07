package de.anlagen.solaruebersicht

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LoginException(msg: String) : Exception(msg)
class OfflineException : Exception("offline")

/** Einfacher Cookie-Speicher im Arbeitsspeicher (je Anlage ein eigener). */
class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun copyOf(): MemoryCookieJar = MemoryCookieJar().also { it.store.addAll(store) }
}

/**
 * Eine Anmeldung bei solar-assistant.io, aber je Anlage eine eigene HTTP-Sitzung.
 *
 * Der Fernzugriff einer Anlage wird über solar-assistant.io/authorize freigeschaltet.
 * Laufen mehrere Freischaltungen gleichzeitig über dieselbe Sitzung, können sie sich
 * stören. Deshalb: getrennte Sitzungen, Freischaltung nur nacheinander (authMutex)
 * und danach eine Kontrolle der "Site ID", ob wirklich die richtige Anlage antwortet.
 */
class SolarClient(private val email: String, private val password: String) {

    companion object {
        const val BASE = "https://solar-assistant.io"
        const val QUERY_PATH = "/grafana/api/datasources/proxy/1/query"
    }

    private val base = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "SolarUebersicht-Android/1.0").build())
        }
        .build()
    private val mainJar = MemoryCookieJar()
    private val main = base.newBuilder().cookieJar(mainJar).build()
    private val loginMutex = Mutex()
    private val authMutex = Mutex()
    private val siteClients = ConcurrentHashMap<String, OkHttpClient>()   // ohne Weiterleitungen

    private fun Response.finalUrl() = request.url.toString()

    // ---- Anmeldung und Standortliste ------------------------------------- //
    suspend fun login() = loginMutex.withLock {
        withContext(Dispatchers.IO) {
            val (signInUrl, page) = main.newCall(Request.Builder().url("$BASE/sign_in").build()).execute()
                .use { it.finalUrl() to it.body!!.string() }
            // schon angemeldet (z. B. parallele Anmeldung gerade fertig) → Seite leitet weiter → fertig
            if (!signInUrl.trimEnd('/').endsWith("/sign_in")) return@withContext
            val token = Regex("""name="_csrf_token"[^>]*value="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: Regex("""value="([^"]+)"[^>]*name="_csrf_token"""").find(page)?.groupValues?.get(1)
                ?: throw LoginException("Anmeldeseite hat sich verändert (kein CSRF-Token).")
            val form = FormBody.Builder()
                .add("_csrf_token", token)
                .add("user[email]", email)
                .add("user[password]", password)
                .add("user[remember_me]", "true")
                .build()
            main.newCall(Request.Builder().url("$BASE/sign_in").post(form).build()).execute().use { r ->
                if (r.finalUrl().trimEnd('/').endsWith("/sign_in"))
                    throw LoginException("Anmeldung fehlgeschlagen – E-Mail oder Passwort falsch?")
            }
        }
    }

    suspend fun listSites(): List<Site> = withContext(Dispatchers.IO) {
        val sites = LinkedHashMap<String, Site>()
        var page = 1
        while (page <= 20) {
            val (url, text) = main.newCall(Request.Builder().url("$BASE/sites?page=$page").build()).execute()
                .use { it.finalUrl() to it.body!!.string() }
            if (url.trimEnd('/').endsWith("/sign_in")) throw LoginException("Nicht angemeldet.")
            val found = Regex("""href="(https://([a-z0-9-]+)\.[a-z]+\.solar-assistant\.io)/?"""").findAll(text).toList()
            var new = 0
            found.forEachIndexed { i, m ->
                val name = m.groupValues[2]
                if (name in sites) return@forEachIndexed
                new++
                // Zeile der Liste enthält die Leistungsklasse, z. B. "… 3 MPPTs 12K"
                val end = if (i + 1 < found.size) found[i + 1].range.first else text.length
                val row = htmlToText(text.substring(m.range.last + 1, end))
                val k = Regex("""\b(\d+(?:\.\d+)?)K\b""").find(row)?.groupValues?.get(1)?.toDoubleOrNull()
                sites[name] = Site(name, m.groupValues[1], k?.times(1000))
            }
            if (new == 0) break
            page++
        }
        sites.values.toList()
    }

    // ---- Zugriff auf eine einzelne Anlage -------------------------------- //
    /** Fernzugriff für genau eine Anlage freischalten – nie mehrere gleichzeitig. */
    private suspend fun authorize(siteUrl: String): OkHttpClient = authMutex.withLock {
        val host = siteUrl.substringAfter("//").trimEnd('/')
        repeat(2) { attempt ->
            val jar = mainJar.copyOf()
            val client = base.newBuilder().cookieJar(jar).build()
            val (finalUrl, page) = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("$siteUrl/configuration").build()).execute()
                    .use { it.finalUrl() to it.body!!.string() }
            }
            if (finalUrl.startsWith(BASE) && "/sign_in" in finalUrl) {
                if (attempt == 0) {
                    login(); return@repeat
                }
                throw LoginException("Freischaltung verlangt erneute Anmeldung.")
            }
            if ("/sites/offline" in finalUrl) throw OfflineException()
            // Kontrolle: meldet sich wirklich die gewünschte Anlage?
            val siteId = Regex("""Site ID:\s*([a-z0-9.-]+)""").find(htmlToText(page))?.groupValues?.get(1)
            if (siteId != null && !siteId.equals(host, ignoreCase = true))
                throw IOException("falsche Anlage ($siteId)")
            val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            siteClients[siteUrl] = noRedirect
            return@withLock noRedirect
        }
        throw IOException("Freischaltung fehlgeschlagen")
    }

    private suspend fun siteGet(siteUrl: String, url: HttpUrl): String {
        var client = siteClients[siteUrl] ?: authorize(siteUrl)
        repeat(2) { attempt ->
            val request = Request.Builder().url(url).build()
            val result = withContext(Dispatchers.IO) {
                val resp = try {
                    client.newCall(request).execute()
                } catch (e: IOException) {
                    Thread.sleep(1500)    // kurzer Verbindungsaussetzer → einmal wiederholen
                    client.newCall(request).execute()
                }
                resp.use { r ->
                    when {
                        r.code in 300..399 -> null          // nicht (mehr) freigeschaltet
                        !r.isSuccessful -> throw IOException("HTTP ${r.code}")
                        else -> r.body!!.string()
                    }
                }
            }
            if (result != null) return result
            if (attempt == 0) client = authorize(siteUrl)
        }
        throw IOException("Anlage lässt sich nicht freischalten")
    }

    /** InfluxDB-Abfrage über die Grafana-Schnittstelle der Anlage. */
    suspend fun query(siteUrl: String, statements: List<String>): JSONArray {
        val url = (siteUrl + QUERY_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("db", "solar_assistant")
            .addQueryParameter("epoch", "ms")
            .addQueryParameter("q", statements.joinToString(";"))
            .build()
        val text = siteGet(siteUrl, url)
        if (!text.trimStart().startsWith("{")) throw IOException("Unerwartete Antwort")
        return JSONObject(text).optJSONArray("results") ?: JSONArray()
    }

    suspend fun settings(siteUrl: String): Map<String, Double> =
        parseSettings(siteGet(siteUrl, "$siteUrl/inverter/settings".toHttpUrl()))

    /** Betriebsmodus und Zustand (Statussymbol ok/fault) von der Seite "Inverter" */
    suspend fun inverterStatus(siteUrl: String): InverterStatus =
        parseInverterStatus(siteGet(siteUrl, "$siteUrl/inverter/status".toHttpUrl()))
}

// ---- Auswertung ----------------------------------------------------------- //
fun htmlToText(page: String): String {
    val stripped = page.replace(Regex("""(?is)<(select|script|style)\b.*?</\1>"""), " ")
    return HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("""\s+"""), " ")
}

/** mode: z. B. "Discharge above 20%"; fault: SolarAssistant zeigt das Störungssymbol */
data class InverterStatus(val mode: String?, val fault: Boolean)

private val FAULT_WORDS = Regex("fault|error|alarm|warn|fail|fehler|störung", RegexOption.IGNORE_CASE)

fun parseInverterStatus(page: String): InverterStatus {
    // Symbol neben dem Modus: /images/inverter/ok-….svg bzw. /images/inverter/fault-….svg
    val icon = Regex("""/images/inverter/([a-z_]+)[-.]""").find(page)?.groupValues?.get(1)
    val mode = Regex("""(?is)metric-name">\s*Device mode\s*</div>\s*<div[^>]*>(.*?)</div>""").find(page)
        ?.groupValues?.get(1)?.let { htmlToText(it).trim() }?.ifEmpty { null }
    return InverterStatus(mode, icon == "fault" || (mode != null && FAULT_WORDS.containsMatchIn(mode)))
}

/** Beschriftungen auf der Einstellungsseite (Deye, Growatt, …) → interner Schlüssel */
private val SETTING_LABELS = mapOf(
    "max_ac_w" to listOf("Max AC output power", "Rated power", "Nominal power", "Max output power"),
    "max_solar_w" to listOf("Max solar power", "Max PV power", "PV max power"),
    "max_sell_w" to listOf("Max sell power", "Max export power", "Export limit power"),
    "max_charge_a" to listOf("Max charge current", "Max charging current", "Max battery charge current"),
    "max_discharge_a" to listOf("Max discharge current", "Max discharging current"),
    "battery_ah" to listOf("Battery capacity"),
    "stop_discharge_pct" to listOf("Stop battery discharge capacity", "Battery low capacity",
        "Discharge cut-off SOC", "Low battery SOC", "Shutdown capacity"),
)

fun parseSettings(page: String): Map<String, Double> {
    val text = htmlToText(page)
    val out = mutableMapOf<String, Double>()
    for ((key, labels) in SETTING_LABELS) {
        for (label in labels) {
            val m = Regex(Regex.escape(label) + """\s*:?\s*(-?\d+(?:[.,]\d+)?)\s*(kW|W|Ah|A|%)?""", RegexOption.IGNORE_CASE)
                .find(text) ?: continue
            var v = m.groupValues[1].replace(',', '.').toDouble()
            if (m.groupValues[2].equals("kw", ignoreCase = true)) v *= 1000
            out[key] = v
            break
        }
    }
    return out
}

private fun influx(name: String) = "\"" + name.replace("\"", "\\\"") + "\""
private fun utc(i: Instant) = i.truncatedTo(ChronoUnit.SECONDS).toString()

/** Ein Ergebnis-Block einer Abfrage: Spaltennamen und Zeilen */
private fun JSONArray.seriesAt(i: Int): Pair<JSONArray, JSONArray>? {
    val s = optJSONObject(i)?.optJSONArray("series")?.optJSONObject(0) ?: return null
    return s.getJSONArray("columns") to s.getJSONArray("values")
}

private fun JSONArray.doubleOrNull(i: Int): Double? = if (isNull(i)) null else optDouble(i).takeUnless { it.isNaN() }

/** Live-Werte einer Anlage in einer einzigen Abfrage: (Werte, neuester Zeitstempel). */
suspend fun SolarClient.readLive(siteUrl: String, columns: List<String>): Pair<Map<String, Double>, Double> {
    val midnight = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
    val statements = columns.map { col ->
        val daily = M.DAILY[col]
        if (daily != null) "SELECT sum(*) FROM ${influx(daily)} WHERE time >= '${utc(midnight)}'"
        else "SELECT last(*) FROM ${influx(col)}"
    }
    val res = query(siteUrl, statements)
    val values = mutableMapOf<String, Double>()
    var newest = 0.0
    columns.forEachIndexed { i, col ->
        val (cols, rows) = res.seriesAt(i) ?: return@forEachIndexed
        val row = rows.getJSONArray(0)
        // "combined" = Summe aller Wechselrichter, sonst erster Wechselrichter
        val fields = (1 until cols.length()).mapNotNull { c ->
            row.doubleOrNull(c)?.let { cols.getString(c).substringAfter("_") to it }
        }.toMap()
        if (fields.isEmpty()) return@forEachIndexed
        var v = fields["combined"] ?: fields.values.first()
        if (col in M.DAILY) v /= 1000.0           // Wh → kWh
        else if (row.optLong(0) > 0) newest = maxOf(newest, row.optLong(0) / 1000.0)
        values[col] = v
    }
    return values to newest
}

typealias Series = List<Pair<Double, Double?>>     // (Unix-Sekunden, Wert)

/** Mittelwerte je Zeitraster für mehrere Messwerte. */
suspend fun SolarClient.fetchSeries(
    siteUrl: String, measurements: List<String>, start: Instant, end: Instant, interval: String,
): Map<String, Series> {
    val tz = if (interval.endsWith("d")) " tz('${ZoneId.systemDefault().id}')" else ""
    val statements = measurements.map {
        "SELECT mean(*) FROM ${influx(it)} WHERE time >= '${utc(start)}' AND time < '${utc(end)}' " +
            "GROUP BY time($interval) fill(null)$tz"
    }
    val res = query(siteUrl, statements)
    return measurements.mapIndexed { i, m ->
        val s = res.seriesAt(i)
        if (s == null) m to emptyList()
        else {
            val (cols, rows) = s
            val idx = (0 until cols.length()).firstOrNull { cols.getString(it) == "mean_combined" } ?: 1
            m to (0 until rows.length()).map { r ->
                val row = rows.getJSONArray(r)
                row.optLong(0) / 1000.0 to row.doubleOrNull(idx)
            }
        }
    }.toMap()
}

/** Spalten der Summen-Seite – wie auf der Webseite der Anlage (Reiter "Σ") */
val TOTAL_COLUMNS = listOf(
    "Load power hourly" to "Verbrauch",
    "PV power hourly" to "PV-Ertrag",
    "Battery power in hourly" to "Batt. geladen",
    "Battery power out hourly" to "Batt. entladen",
    "Grid power in hourly" to "Netzbezug",
    "Grid power out hourly" to "Einspeisung",
)

/** Tagessummen in kWh: Datum → Werte in Reihenfolge von TOTAL_COLUMNS */
suspend fun SolarClient.fetchDailyTotals(siteUrl: String, start: Instant, end: Instant): Map<java.time.LocalDate, DoubleArray> {
    val zone = ZoneId.systemDefault()
    val statements = TOTAL_COLUMNS.map { (m, _) ->
        "SELECT sum(\"combined\") FROM ${influx(m)} WHERE time >= '${utc(start)}' AND time < '${utc(end)}' " +
            "GROUP BY time(1d) fill(none) tz('${zone.id}')"
    }
    val res = query(siteUrl, statements)
    val days = sortedMapOf<java.time.LocalDate, DoubleArray>()
    TOTAL_COLUMNS.indices.forEach { i ->
        val (_, rows) = res.seriesAt(i) ?: return@forEach
        for (r in 0 until rows.length()) {
            val row = rows.getJSONArray(r)
            val v = row.doubleOrNull(1) ?: continue
            // Zeitstempel = Mitternacht Ortszeit; +2 h schützt vor Sommerzeit-Versatz
            val day = Instant.ofEpochMilli(row.getLong(0) + 7_200_000).atZone(zone).toLocalDate()
            days.getOrPut(day) { DoubleArray(TOTAL_COLUMNS.size) }[i] = v / 1000.0
        }
    }
    return days
}

/** Energie heute (kWh) in Reihenfolge von TOTAL_COLUMNS – Stundenwerte inkl. laufender Stunde */
suspend fun SolarClient.todayTotals(siteUrl: String): DoubleArray? {
    val today = java.time.LocalDate.now()
    val midnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
    return fetchDailyTotals(siteUrl, midnight, Instant.now().plusSeconds(3600))[today]
}

/** Verläufe mehrerer Anlagen zusammenfassen: Leistung addieren, SOC/Temperatur mitteln. */
fun combineSeries(perSite: List<Map<String, Series>>): Map<String, Series> {
    val keys = perSite.flatMap { it.keys }.toSet()
    return keys.associateWith { m ->
        val buckets = sortedMapOf<Double, MutableList<Double>>()
        perSite.forEach { d -> d[m]?.forEach { (t, v) -> if (v != null) buckets.getOrPut(t) { mutableListOf() }.add(v) } }
        buckets.map { (t, vs) -> t to (if (M.isAverage(m)) vs.average() else vs.sum()) as Double? }
    }
}
