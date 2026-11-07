package de.anlagen.solaruebersicht

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Zusatzkomponenten einer Home-Assistant-Anlage (Wallbox, Auto, Lüftung, Klimaanlage …):
 * je eine Kachel mit aktueller Leistung, Energie heute und einigen Zustandswerten.
 */
data class ExtraInfo(val label: String, val entity: String, val skipZero: Boolean = false)

data class Extra(
    val name: String,
    val icon: String,                 // wallbox, car, fan, ac, plug
    val power: String? = null,        // Leistungssensor (W oder kW)
    val energy: String? = null,       // Zählerstand (kWh) → "heute" = Zuwachs seit Mitternacht
    val info: List<ExtraInfo> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("icon", icon)
        power?.let { put("power", it) }; energy?.let { put("energy", it) }
        put("info", JSONArray(info.map { JSONObject().put("label", it.label).put("entity", it.entity).put("skipZero", it.skipZero) }))
    }

    val entities get() = listOfNotNull(power, energy) + info.map { it.entity }

    companion object {
        fun fromJson(o: JSONObject): Extra {
            val a = o.optJSONArray("info") ?: JSONArray()
            return Extra(
                o.getString("name"), o.optString("icon", "plug"),
                o.optString("power").ifEmpty { null }, o.optString("energy").ifEmpty { null },
                (0 until a.length()).map { a.getJSONObject(it) }
                    .map { ExtraInfo(it.getString("label"), it.getString("entity"), it.optBoolean("skipZero")) },
            )
        }

        /** Vorlage für das Haus – wie die "individual"-Einträge im Dashboard "Haus" */
        fun haus() = listOf(
            Extra("Wallbox", "wallbox", "sensor.evcc_keba_charge_power", "sensor.evcc_keba_charge_total_import", listOf(
                ExtraInfo("Modus", "select.evcc_keba_mode"),
                ExtraInfo("Phasen", "sensor.evcc_keba_phases_active"),
                ExtraInfo("Strom", "sensor.evcc_keba_charge_current", skipZero = true),
            )),
            Extra("LEAF", "car", "sensor.keba_ladeleistung", null, listOf(
                ExtraInfo("Angesteckt", "binary_sensor.evcc_keba_connected"),
                ExtraInfo("Lädt", "binary_sensor.evcc_keba_charging"),
                ExtraInfo("Ladevorgang", "sensor.evcc_keba_session_energy", skipZero = true),
                ExtraInfo("Akku", "sensor.evcc_keba_vehicle_soc", skipZero = true),
                ExtraInfo("Reichweite", "sensor.evcc_keba_vehicle_range", skipZero = true),
            )),
            Extra("Lüftung", "fan", "sensor.shelly_shelly_luftung_leistung", "sensor.shellyplusplugs_b0b21c129820_energy", listOf(
                ExtraInfo("Profil", "sensor.vallox_aktuelles_profil"),
                ExtraInfo("Lüfter", "sensor.vallox_ventilatordrehzahl"),
                ExtraInfo("Zuluft", "sensor.vallox_zuluft"),
                ExtraInfo("Außen", "sensor.vallox_aussenluft"),
            )),
            Extra("Klimaanlage", "ac", "sensor.waschkeller_klima_leistung", "sensor.waschkeller_klima_energie", listOf(
                ExtraInfo("Steckdose", "switch.waschkeller_klima"),
                ExtraInfo("Modus", "select.waschkeller_klimaesp8266_klima_modus"),
                ExtraInfo("Soll", "number.waschkeller_klimaesp8266_klima_solltemperatur"),
            )),
        )

        fun toJson(list: List<Extra>) = JSONArray(list.map { it.toJson() })
        fun fromJson(a: JSONArray) = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/** Aktueller Zustand einer Zusatzkomponente */
data class ExtraLive(
    val name: String,
    val icon: String,
    val power: Double?,              // W
    val today: Double?,              // kWh seit Mitternacht
    val info: List<String>,          // fertige Texte, z. B. "Modus: PV"
)

/** Zustandswert lesbar machen (on/off, evcc-Modi, Vallox-Profile …) */
private val WORDS = mapOf(
    "pv" to "PV", "minpv" to "Min+PV", "now" to "Sofort", "off" to "Aus", "on" to "An",
    "home" to "Zuhause", "away" to "Abwesend", "boost" to "Boost", "fireplace" to "Kamin", "extra" to "Extra",
    "auto" to "Auto", "bypass" to "Bypass", "heat recovery" to "Wärmerückgewinnung",
)

fun formatExtraState(entity: String, state: String, unit: String?, skipZero: Boolean): String? {
    if (state.isEmpty() || state == "unavailable" || state == "unknown" || state == "null") return null
    val domain = entity.substringBefore('.')
    if (domain == "binary_sensor") return if (state == "on") "ja" else "nein"
    val v = state.toDoubleOrNull()
    if (v != null) {
        if (skipZero && v == 0.0) return null
        val num = when {
            abs(v) >= 100 || v == Math.floor(v) -> String.format(Locale.GERMANY, "%.0f", v)
            else -> String.format(Locale.GERMANY, "%.1f", v)
        }
        return if (unit.isNullOrEmpty()) num else "$num $unit"
    }
    return WORDS[state.lowercase(Locale.ROOT)] ?: state
}

private fun extraEmoji(icon: String) = when (icon) {
    "wallbox" -> "🔌"; "car" -> "🚗"; "fan" -> "🌀"; "ac" -> "❄️"; "heat" -> "🔥"; "water" -> "💧"
    else -> "⚡"
}

/** Kacheln der Zusatzkomponenten (je zwei nebeneinander) – unter der Live-Karte einer HA-Anlage */
@Composable
fun ExtrasPanel(extras: List<ExtraLive>, dim: Boolean) {
    if (extras.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Verbraucher", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extras.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ExtraTile(it, dim, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExtraTile(e: ExtraLive, dim: Boolean, modifier: Modifier) {
    val running = !dim && (e.power ?: 0.0) >= 5
    val accent = when (e.icon) { "car", "wallbox" -> C.GREEN; "fan" -> C.BLUE; "ac" -> Color(0xFF00A3C4); else -> Brand.ORANGE }
    val bg = if (running) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(extraEmoji(e.icon), fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(e.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (e.power == null) "–" else fmtPower(e.power), fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = if (running) accent else MaterialTheme.colorScheme.onSurface)
        e.today?.let {
            Text("heute ${String.format(Locale.GERMANY, "%.2f", it)} kWh", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        e.info.forEach {
            Text(it, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Beispielwerte für die Demo (Anlage "woanders" bzw. die Einzelanlage) */
fun demoExtras(): List<ExtraLive> {
    val car = kotlin.random.Random.nextDouble(1400.0, 7400.0)
    return listOf(
        ExtraLive("Wallbox", "wallbox", car, 6.4, listOf("Modus: PV", "Phasen: 1", "Strom: 9 A")),
        ExtraLive("LEAF", "car", car, null, listOf("Angesteckt: ja", "Lädt: ja", "Ladevorgang: 4,2 kWh")),
        ExtraLive("Lüftung", "fan", kotlin.random.Random.nextDouble(24.0, 30.0), 0.62,
            listOf("Profil: Zuhause", "Lüfter: 35 %", "Zuluft: 16,1 °C", "Außen: 13,5 °C")),
        ExtraLive("Klimaanlage", "ac", 0.0, 0.0, listOf("Steckdose: Aus", "Modus: Kühlen", "Soll: 22 °C")),
    )
}
