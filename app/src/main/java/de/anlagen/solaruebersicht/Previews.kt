package de.anlagen.solaruebersicht

/*
 * Vorschau der Bildschirme in Android Studio – ohne Handy und ohne Emulator.
 *
 * So geht's: Diese Datei öffnen und oben rechts im Editor auf "Split" oder "Design"
 * klicken. Nach einer Änderung im Code aktualisiert sich die Vorschau automatisch
 * (sonst oben im Vorschau-Fenster auf "Build & Refresh" klicken).
 *
 * Die Werte hier sind Beispielwerte. Aussehen und Aufbau kommen aus den echten
 * Bausteinen (Components.kt, Screens.kt, MainActivity.kt) – wer dort etwas ändert,
 * sieht es hier sofort.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId

private val demoSettings = mapOf(
    "max_ac_w" to 12000.0, "max_solar_w" to 11500.0, "max_sell_w" to 800.0,
    "max_charge_a" to 45.0, "max_discharge_a" to 65.0, "stop_discharge_pct" to 18.0,
)
private val demoSite = Site("woanders", "", 12000.0)

private fun demoValues(pv: Double, load: Double, batt: Double, soc: Double, today: Double) = mapOf(
    M.PV to pv, M.LOAD to load, M.BATT to batt, M.GRID to load - pv + batt,
    M.SOC to soc, M.BATT_V to 53.5, M.PV_TODAY to today, M.TEMP to 48.0,
)

private fun limits(values: Map<String, Double>) =
    M.LIVE.mapNotNull { m -> limitsFor(m, demoSite, demoSettings, values)?.let { m to it } }.toMap()

/** Kopfzeile mit Logo */
@Preview(name = "Kopfzeile", widthDp = 400)
@Composable
fun PreviewHeader() = AppTheme {
    BrandHeader("Elektro & IT-Service Koch")
}

/** Live: Gesamt-Karte und Anlagen-Karten in den verschiedenen Zuständen */
@Preview(name = "Live – Karten", widthDp = 400, heightDp = 1500, showBackground = true, backgroundColor = 0xFFFFF7EF)
@Composable
fun PreviewLive() = AppTheme {
    Column(Modifier.background(MaterialTheme.colorScheme.background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val total = demoValues(18400.0, 9200.0, -2100.0, 52.0, 141.0)
        SiteCard("Gesamt", "5/6 online", null, false, total, limits(total), dim = false, highlight = true, onClick = null,
            flow = true, onToggle = {})
        val a = demoValues(5770.0, 2398.0, -487.0, 77.0, 3.0)
        SiteCard("woanders", "online · WR 48 °C", Status.ONLINE, false, a, limits(a), dim = false, highlight = false, onClick = null,
            flow = false, onToggle = {})
        val b = demoValues(820.0, 1500.0, -900.0, 24.0, 12.4)
        SiteCard("guggenmos", "keine Daten seit 4 min", Status.ONLINE, true, b, limits(b), dim = true, highlight = false, onClick = null)
        SiteCard("cook", "offline", Status.OFFLINE, false, emptyMap(), emptyMap(), dim = true, highlight = false, onClick = null)
        SiteCard("Haus", "Fehler: Token ungültig", Status.ERROR, false, emptyMap(), emptyMap(), dim = true, highlight = false, onClick = null)
    }
}

/** Einzelne Bausteine: Gauges, Ladestand-Batterie in allen Farben, Kachel */
@Preview(name = "Bausteine", widthDp = 400, showBackground = true)
@Composable
fun PreviewParts() = AppTheme {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val v = demoValues(7300.0, 2100.0, 1600.0, 64.0, 22.5)
        val lim = limits(v)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Gauge(M.PV, v[M.PV], lim[M.PV], false, Modifier.weight(1f))
            Gauge(M.GRID, v[M.GRID], lim[M.GRID], false, Modifier.weight(1f))
            Gauge(M.BATT, v[M.BATT], lim[M.BATT], false, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatterySoc(30.0, 18.0, false, Modifier.weight(1f))     // rot
            BatterySoc(62.0, 18.0, false, Modifier.weight(1f))     // orange
            BatterySoc(90.0, 18.0, false, Modifier.weight(1f))     // grün
        }
        Row {
            ValueTile(M.PV_TODAY, 22.5, false, Modifier.width(120.dp))
            Gauge(M.TEMP, 48.0, Limits(0.0, 80.0), false, Modifier.width(130.dp))
        }
    }
}

/** Verläufe: Tagesdiagramme mit Beispielwerten */
@Preview(name = "Verläufe – Tag", widthDp = 400, heightDp = 700, showBackground = true, backgroundColor = 0xFFFFF7EF)
@Composable
fun PreviewHistory() = AppTheme {
    val zone = ZoneId.systemDefault()
    val end = java.time.Instant.now()
    val start = end.minusSeconds(86400)
    val d = Demo.series(listOf(M.PV, M.LOAD, M.GRID, M.BATT, M.SOC), start, end, "2m")
    Column(Modifier.padding(10.dp)) {
        ChartCard {
            TimeChart("Leistung", "kW", listOf(
                ChartLine("PV", d[M.PV].orEmpty(), C.YELLOW, fill = true),
                ChartLine("Verbrauch", d[M.LOAD].orEmpty(), C.BLUE),
                ChartLine("Netz", d[M.GRID].orEmpty(), C.RED),
            ), start, end, scale = 0.001, height = 200.dp)
        }
        ChartCard {
            TimeChart("Batterie", "kW", listOf(ChartLine("Batterie", d[M.BATT].orEmpty(), C.GREEN, negColor = C.ORANGE)),
                start, end, scale = 0.001)
        }
        ChartCard {
            TimeChart("Ladestand", "%", listOf(ChartLine("", d[M.SOC].orEmpty(), C.GREEN)), start, end,
                yRange = 0.0 to 100.0, height = 130.dp)
        }
    }
}

/** Summen: Balkendiagramm und Tabelle */
@Preview(name = "Summen", widthDp = 400, heightDp = 800, showBackground = true, backgroundColor = 0xFFFFF7EF)
@Composable
fun PreviewTotals() = AppTheme {
    val today = LocalDate.now()
    val days = Demo.totals(today.minusDays(29), today)
    val asc = days.keys.sorted()
    Column(Modifier.padding(10.dp)) {
        ChartCard {
            BarChart("Letzte 30 Tage", asc.map { "${it.dayOfMonth}.${it.monthValue}." },
                listOf("PV-Ertrag" to C.YELLOW, "Verbrauch" to C.BLUE, "Einspeisung" to C.GREEN),
                asc.map { d -> days.getValue(d).let { doubleArrayOf(it[1], it[0], it[5]) } })
        }
        ChartCard {
            KwhTable("Tag", TOTAL_COLUMNS.map { it.second },
                asc.reversed().take(8).map { "${it.dayOfMonth}.${it.monthValue}." to days.getValue(it) })
        }
        Text("Beispielwerte – echte Werte kommen aus den Anlagen.", fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** Energiefluss mit Beispielwerten */
@Preview(name = "Energiefluss", widthDp = 400, showBackground = true, backgroundColor = 0xFFFFF7EF)
@Composable
fun PreviewEnergyFlow() = AppTheme {
    Column(Modifier.padding(10.dp)) {
        ChartCard { EnergyFlow(demoValues(7100.0, 2160.0, 1130.0, 75.0, 18.4), active = true) }
    }
}
