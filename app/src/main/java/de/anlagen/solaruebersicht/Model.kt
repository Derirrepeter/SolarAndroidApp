package de.anlagen.solaruebersicht

import java.util.Locale
import kotlin.math.abs

/** Namen der Messwerte, wie die Anlagen sie in ihrer Datenbank führen. */
object M {
    const val PV = "PV power"
    const val LOAD = "Load power"
    const val GRID = "Grid power"
    const val BATT = "Battery power"
    const val SOC = "Battery state of charge"
    const val TEMP = "Inverter temperature"
    const val BATT_V = "Battery voltage"
    const val PV_TODAY = "Heute: PV-Ertrag"

    /** Werte, die in der Live-Ansicht abgefragt werden */
    val LIVE = listOf(PV, LOAD, GRID, BATT, SOC, TEMP, BATT_V, PV_TODAY)

    /** Tageswerte: aus den stündlichen Mittelwerten (W je Stunde = Wh) summiert */
    val DAILY = mapOf(
        PV_TODAY to "PV power hourly",
    )

    val GERMAN = mapOf(
        PV to "PV-Leistung",
        LOAD to "Verbrauch",
        "Load power essential" to "Verbrauch (Notstrom)",
        "Load power non-essential" to "Verbrauch (übrige)",
        GRID to "Netz",
        "Grid voltage" to "Netzspannung",
        "Grid frequency" to "Netzfrequenz",
        BATT to "Batterie",
        SOC to "Ladestand",
        BATT_V to "Batteriespannung",
        "Battery current" to "Batteriestrom",
        "Battery temperature" to "Batterietemp.",
        TEMP to "WR-Temp.",
        "AC output voltage" to "AC-Ausgangsspannung",
        "Generator power" to "Generator",
        "PV power 1" to "PV-String 1",
        "PV power 2" to "PV-String 2",
        "PV power 3" to "PV-String 3",
        "PV power 4" to "PV-String 4",
        PV_TODAY to "PV heute",
    )

    fun label(m: String) = GERMAN[m] ?: m

    fun unit(m: String): String {
        val s = m.lowercase(Locale.ROOT)
        return when {
            s.startsWith("heute") -> "kWh"
            "state of charge" in s -> "%"
            "power" in s -> "W"
            "temperature" in s -> "°C"
            "voltage" in s -> "V"
            "current" in s -> "A"
            "frequency" in s -> "Hz"
            else -> ""
        }
    }

    /** Diese Werte werden über mehrere Anlagen gemittelt statt addiert. */
    fun isAverage(m: String): Boolean {
        val s = m.lowercase(Locale.ROOT)
        return listOf("state of charge", "temperature", "voltage", "frequency").any { it in s }
    }
}

/** Eine Anlage: kind "sa" = SolarAssistant (über solar-assistant.io), "ha" = Home Assistant */
data class Site(val name: String, val url: String, val ratedW: Double?, val kind: String = "sa", val openUrl: String = url)

enum class Status { LOADING, ONLINE, OFFLINE, ERROR }

data class SiteLive(
    val status: Status = Status.LOADING,
    val message: String = "lade …",
    val values: Map<String, Double> = emptyMap(),
    val lastTs: Double = 0.0,                 // Zeitstempel des neuesten Messwerts (Sekunden)
    val settings: Map<String, Double>? = null, // Grenzwerte aus den Wechselrichter-Einstellungen
    val retryAt: Long = 0,                     // offline: erst ab dann wieder versuchen
    val extras: List<ExtraLive> = emptyList(), // Home Assistant: Zusatzkomponenten
    val mode: String? = null,                  // SolarAssistant: Betriebsmodus ("Device mode")
    val fault: Boolean = false,                // SolarAssistant meldet eine Störung
    val modeAt: Long = 0,                      // wann der Modus zuletzt gelesen wurde (ms)
    val today: DoubleArray? = null,            // Energie heute (kWh), Reihenfolge wie TOTAL_COLUMNS
    val todayAt: Long = 0,                     // wann "heute" zuletzt gelesen wurde (ms)
)

data class Limits(val lo: Double, val hi: Double, val redTo: Double? = null)

/** Bereich (min, max, rote Zone) eines Gauges – aus den Einstellungen der Anlage. */
fun limitsFor(metric: String, site: Site?, settings: Map<String, Double>, values: Map<String, Double>): Limits? {
    val m = metric.lowercase(Locale.ROOT)
    val ac = settings["max_ac_w"] ?: site?.ratedW ?: 10000.0
    val volt = values[M.BATT_V] ?: 51.2
    return when {
        metric == M.PV -> Limits(0.0, settings["max_solar_w"] ?: (ac * 1.3))
        m.startsWith("load power") -> Limits(0.0, ac)
        metric == M.GRID -> Limits(-(settings["max_sell_w"] ?: ac), ac)
        metric == M.BATT -> {
            val charge = settings["batt_charge_w"] ?: settings["max_charge_a"]?.times(volt)
            val discharge = settings["batt_discharge_w"] ?: settings["max_discharge_a"]?.times(volt)
            Limits(-(discharge ?: (ac * 0.6)), charge ?: (ac * 0.6))
        }
        "state of charge" in m -> Limits(0.0, 100.0, settings["stop_discharge_pct"])
        "temperature" in m -> Limits(0.0, if ("battery" in m) 60.0 else 80.0)
        else -> null
    }
}

/** Physikalisch mögliche Bereiche – alles außerhalb wird verworfen statt angezeigt. */
private val PLAUSIBLE = listOf(
    "state of charge" to (0.0 to 100.0),
    "temperature" to (-30.0 to 120.0),
    "frequency" to (40.0 to 70.0),
    "grid voltage" to (0.0 to 300.0),
    "battery voltage" to (0.0 to 1000.0),
    "power" to (-100000.0 to 100000.0),
)

fun plausible(values: Map<String, Double>): Map<String, Double> = values.filter { (k, v) ->
    val range = PLAUSIBLE.firstOrNull { it.first in k.lowercase(Locale.ROOT) }?.second
    range == null || v in range.first..range.second
}

/** Zahl im deutschen Format: 1.234 bzw. 12,3 */
fun fmt(v: Double?): String {
    if (v == null || v.isNaN()) return "–"
    return if (abs(v) >= 100) String.format(Locale.GERMANY, "%,.0f", v)
    else String.format(Locale.GERMANY, "%.1f", v)
}

fun fmtKwh(v: Double): String =
    if (abs(v) >= 1000) String.format(Locale.GERMANY, "%,.0f", v) else String.format(Locale.GERMANY, "%.1f", v)

/** Kurzform für Skalenenden: 11500 W → "11.5k" */
fun shortNum(v: Double, unit: String): String =
    if (unit == "W" && abs(v) >= 1000) {
        val s = String.format(Locale.ROOT, "%.1f", v / 1000).trimEnd('0').trimEnd('.')
        "${s}k"
    } else String.format(Locale.ROOT, "%.0f", v)
