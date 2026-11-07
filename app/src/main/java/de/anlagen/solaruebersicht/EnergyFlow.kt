package de.anlagen.solaruebersicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Unter dieser Leistung (W) gilt eine Leitung als "kein Fluss" – grau, keine Kugel */
private const val FLOW_MIN_W = 50.0

/** Ab dieser Leistung (W) ist die Leitung am dicksten und die Kugel am schnellsten */
private const val FLOW_FULL_W = 10_000.0

/** Leistung kurz und lesbar: 850 W bzw. 7,10 kW */
fun fmtPower(w: Double?): String = when {
    w == null -> "–"
    abs(w) >= 1000 -> String.format(Locale.GERMANY, "%.2f kW", abs(w) / 1000)
    else -> String.format(Locale.GERMANY, "%.0f W", abs(w))
}

/** Leistung als Zahl und Einheit getrennt (für zwei Zeilen im Kreis) */
private fun powerParts(w: Double?): Pair<String, String> = when {
    w == null -> "–" to ""
    abs(w) >= 1000 -> String.format(Locale.GERMANY, "%.2f", abs(w) / 1000) to "kW"
    else -> String.format(Locale.GERMANY, "%.0f", abs(w)) to "W"
}

/**
 * Energiefluss der Anlage: Wechselrichter in der Mitte, PV links oben, Netz rechts oben,
 * Akku links unten, Hausverbrauch rechts unten. Die Leitungen laufen rechtwinklig
 * (waagerecht zur Mitte, senkrecht in den Wechselrichter). Je Leitung wandert eine Kugel in
 * Flussrichtung; mehr Leistung = dickere Leitung und schnellere Kugel.
 *
 * Symbole: weißer Kreis mit farbigem Doppelring, darin ein Strich-Symbol, der Wert und die
 * Einheit; die Bezeichnung steht außen (oben über, unten unter dem Kreis).
 *
 * Vorzeichen wie in der übrigen App: Netz + = Bezug, − = Einspeisung; Batterie + = laden, − = entladen.
 */
@Composable
fun EnergyFlow(values: Map<String, Double>, active: Boolean, modifier: Modifier = Modifier, today: DoubleArray? = null) {
    val pv = values[M.PV]
    val load = values[M.LOAD]
    val grid = values[M.GRID]
    val batt = values[M.BATT]
    val soc = values[M.SOC]

    // laufende Zeit in Sekunden für die Animation der Kugeln
    val time by produceState(0f) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = (it - start) / 1000f }
    }
    val tm = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val nodeBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val grey = C.GREY

    // mit Energie heute etwas höher: unten stehen dann bis zu zwei Zeilen mehr
    Canvas(modifier.fillMaxWidth().height(if (today != null) 336.dp else 306.dp)) {
        val w = size.width
        val r = 40.dp.toPx()          // Radius der äußeren Kreise
        val ri = 30.dp.toPx()         // Radius Wechselrichter
        val halo = 9.dp.toPx()        // äußerster Ring um die Kreise
        val topY = 76.dp.toPx()
        val botY = 232.dp.toPx()
        val pvC = Offset(w * 0.19f, topY)
        val gridC = Offset(w * 0.81f, topY)
        val battC = Offset(w * 0.19f, botY)
        val homeC = Offset(w * 0.81f, botY)
        val invC = Offset(w * 0.5f, 169.dp.toPx())

        fun on(p: Double?) = active && p != null && abs(p) > FLOW_MIN_W
        val gridCol = if ((grid ?: 0.0) < 0) C.GREEN else C.RED
        val battCol = if ((batt ?: 0.0) < 0) C.ORANGE else C.GREEN
        val socCol = if (active && soc != null) socColor(soc) else grey
        val ink = if (active) text else grey          // Farbe der Strich-Symbole

        val pvRing = if (active) C.YELLOW else grey
        val gridRing = if (on(grid)) gridCol else grey
        val battRing = if (on(batt)) battCol else socCol
        val homeRing = if (active) C.BLUE else grey
        val invRing = if (active) Brand.ORANGE else grey

        // ---- Ringe außen (unter den Leitungen) ----
        halo(pvC, r, pvRing); halo(gridC, r, gridRing); halo(battC, r, battRing); halo(homeC, r, homeRing)
        halo(invC, ri, invRing)

        // ---- Leitungen rechtwinklig wie ein Schaltplan: waagerecht zur Mitte, dann senkrecht
        //      von oben bzw. unten in den Wechselrichter (links/rechts leicht versetzt) ----
        val dx = 12.dp.toPx()
        fun intoInv(x: Float, top: Boolean): Offset {
            val h = sqrt(ri * ri - (x - invC.x) * (x - invC.x))
            return Offset(x, if (top) invC.y - h else invC.y + h)
        }
        // jeweils vom Außenkreis zum Wechselrichter
        val pvPath = listOf(Offset(pvC.x + r, pvC.y), Offset(invC.x - dx, pvC.y), intoInv(invC.x - dx, true))
        val gridPath = listOf(Offset(gridC.x - r, gridC.y), Offset(invC.x + dx, gridC.y), intoInv(invC.x + dx, true))
        val battPath = listOf(Offset(battC.x + r, battC.y), Offset(invC.x - dx, battC.y), intoInv(invC.x - dx, false))
        val homePath = listOf(Offset(homeC.x - r, homeC.y), Offset(invC.x + dx, homeC.y), intoInv(invC.x + dx, false))

        // ---- Linien mit wandernden Kugeln (Richtung = Energiefluss) ----
        flow(pvPath, pv, on(pv) && (pv ?: 0.0) > 0, C.YELLOW, track, time)                           // PV → WR
        flow(if ((grid ?: 0.0) >= 0) gridPath else gridPath.reversed(), grid, on(grid), gridCol, track, time) // Bezug / Einspeisung
        flow(if ((batt ?: 0.0) >= 0) battPath.reversed() else battPath, batt, on(batt), battCol, track, time) // Laden / Entladen
        flow(homePath.reversed(), load, on(load), C.BLUE, track, time)                                // WR → Haus

        // ---- Kreise mit Symbol, Wert und Einheit ----
        val value = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = text)
        val unit = TextStyle(fontSize = 11.sp, color = muted)
        val u = r * 0.30f                                   // halbe Symbolgröße
        fun icon(c: Offset) = Offset(c.x, c.y - r * 0.44f)

        node(pvC, r, pvRing, nodeBg)
        drawSolar(icon(pvC), u, ink)
        inner(tm, pvC, r, powerParts(pv), value, unit)

        node(gridC, r, gridRing, nodeBg)
        drawPylon(icon(gridC), u, ink)
        inner(tm, gridC, r, powerParts(grid), value.copy(color = if (on(grid)) gridCol else text), unit)

        node(battC, r, battRing, nodeBg)
        drawBattery(tm, icon(battC), u, soc, ink, socCol)
        inner(tm, battC, r, powerParts(batt), value, unit)

        node(homeC, r, homeRing, nodeBg)
        drawHouse(icon(homeC), u, ink)
        inner(tm, homeC, r, powerParts(load), value, unit)

        node(invC, ri, invRing, nodeBg)
        drawInverter(invC, ri, ink)

        // ---- Bezeichnung außen: oben über den Kreisen, unten darunter ----
        val label = TextStyle(fontSize = 12.sp, color = muted)
        val gap = 3.dp.toPx()
        val out = r + halo + gap
        labelAbove(tm, pvC, out, "PV", label)
        labelAbove(tm, gridC, out, when {
            !on(grid) -> "Netz"
            (grid ?: 0.0) < 0 -> "Einspeisung"
            else -> "Netzbezug"
        }, label.copy(color = if (on(grid)) gridCol else muted))
        val battLabel = "Akku" + if (on(batt)) (if ((batt ?: 0.0) < 0) " · entlädt" else " · lädt") else ""
        val labelH = centered(tm, Offset(battC.x, battC.y + out), battLabel, label)
        centered(tm, Offset(homeC.x, homeC.y + out), "Verbrauch", label)
        // links neben dem Wechselrichter (oben und unten laufen die Leitungen hinein)
        val wr = tm.measure("Wechselrichter", label.copy(fontSize = 11.sp))
        drawText(wr, topLeft = Offset(invC.x - ri - halo - wr.size.width, invC.y - wr.size.height / 2f))

        // ---- Energie heute (kWh): immer unter dem Symbol, klein und fett ----
        if (today != null) {
            val small = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = muted)
            fun kwh(v: Double) = "${fmtKwh(v)} kWh"
            val upper = r + halo + 2.dp.toPx()
            val lower = out + labelH            // unten steht schon die Bezeichnung → darunter weiter
            lines(tm, Offset(pvC.x, pvC.y + upper), listOf("heute ${kwh(today[1])}"), small)
            lines(tm, Offset(gridC.x, gridC.y + upper), listOf("Bezug ${kwh(today[4])}", "Einsp. ${kwh(today[5])}"), small)
            lines(tm, Offset(battC.x, battC.y + lower), listOf("geladen ${kwh(today[2])}", "entladen ${kwh(today[3])}"), small)
            lines(tm, Offset(homeC.x, homeC.y + lower), listOf("heute ${kwh(today[0])}"), small)
        }
    }
}

// ---- Linien und Kugeln ------------------------------------------------------ //
/** Leitung entlang der Punkte pts (in Flussrichtung) mit einer wandernden Kugel */
private fun DrawScope.flow(
    pts: List<Offset>, power: Double?, active: Boolean,
    color: Color, track: Color, time: Float,
) {
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) lineTo(p.x, p.y)
    }
    if (!active || power == null) {
        drawPath(path, track, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        return
    }
    // Leitungsdicke und Tempo der (einen) Kugel wachsen mit der Leistung:
    // 50 W: 3 dp, ~8 s je Strecke · 1 kW: ~6 dp, ~5 s · 10 kW: 14 dp, ~1 s
    val f = min(1.0, abs(power) / FLOW_FULL_W).toFloat()
    val width = (3f + 11f * sqrt(f)).dp.toPx()  // Wurzel: auch kleine Leistungen sichtbar dicker
    val speed = 0.12f + 0.9f * f                 // Durchläufe pro Sekunde
    drawPath(path, color.copy(alpha = 0.35f), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val dot = pointAlong(pts, (time * speed) % 1f)
    val rd = max(4.5.dp.toPx(), width / 2 + 2.dp.toPx())
    drawCircle(Color.White, rd + 1.5.dp.toPx(), dot)   // heller Rand – Kugel auch auf dicker Leitung sichtbar
    drawCircle(color, rd, dot)
}

/** Punkt bei Anteil t (0…1) der Gesamtlänge eines Linienzugs */
private fun pointAlong(pts: List<Offset>, t: Float): Offset {
    val lens = pts.zipWithNext { a, b -> (b - a).getDistance() }
    var rest = lens.sum() * t
    for (i in lens.indices) {
        if (rest <= lens[i] || i == lens.lastIndex) {
            val f = if (lens[i] > 0f) (rest / lens[i]).coerceIn(0f, 1f) else 0f
            return pts[i] + (pts[i + 1] - pts[i]) * f
        }
        rest -= lens[i]
    }
    return pts.last()
}

// ---- Kreise ----------------------------------------------------------------- //
/** Zarter Schein und zwei feine Ringe außen um einen Kreis */
private fun DrawScope.halo(c: Offset, r: Float, col: Color) {
    drawCircle(col.copy(alpha = 0.07f), r + 5.dp.toPx(), c)
    drawCircle(col.copy(alpha = 0.30f), r + 5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    drawCircle(col.copy(alpha = 0.14f), r + 9.dp.toPx(), c, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.node(c: Offset, r: Float, ring: Color, bg: Color) {
    drawCircle(bg, r, c)
    drawCircle(ring.copy(alpha = 0.85f), r, c, style = Stroke(2.dp.toPx()))
}

/** Wert und Einheit in der unteren Hälfte des Kreises */
private fun DrawScope.inner(tm: TextMeasurer, c: Offset, r: Float, parts: Pair<String, String>, vs: TextStyle, us: TextStyle) {
    val lv = tm.measure(parts.first, vs)
    val y = c.y - r * 0.06f
    drawText(lv, topLeft = Offset(c.x - lv.size.width / 2f, y))
    if (parts.second.isNotEmpty()) {
        val lu = tm.measure(parts.second, us)
        drawText(lu, topLeft = Offset(c.x - lu.size.width / 2f, y + lv.size.height - 1.dp.toPx()))
    }
}

// ---- Symbole (Strichzeichnung, Mittelpunkt ic, halbe Größe u) ---------------- //
private fun DrawScope.line(sw: Float) = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Solarmodul mit Sonne darüber */
private fun DrawScope.drawSolar(ic: Offset, u: Float, col: Color) {
    val st = line(1.5.dp.toPx())
    // Sonne: Halbkreis mit fünf Strahlen
    val sc = Offset(ic.x, ic.y - 0.15f * u)
    val sr = 0.32f * u
    drawArc(col, 180f, 180f, false, Offset(sc.x - sr, sc.y - sr), Size(2 * sr, 2 * sr), style = st)
    for (k in 0..4) {
        val a = PI + k * PI / 4
        val cx = cos(a).toFloat()
        val sy = sin(a).toFloat()
        drawLine(col, Offset(sc.x + cx * 0.52f * u, sc.y + sy * 0.52f * u),
            Offset(sc.x + cx * 0.78f * u, sc.y + sy * 0.78f * u), st.width, cap = StrokeCap.Round)
    }
    // Modul in Schrägansicht mit Zellen
    val y0 = ic.y + 0.10f * u
    val y1 = ic.y + u
    val panel = Path().apply {
        moveTo(ic.x - 0.62f * u, y0); lineTo(ic.x + 0.62f * u, y0)
        lineTo(ic.x + u, y1); lineTo(ic.x - u, y1); close()
    }
    drawPath(panel, col, style = st)
    val ym = (y0 + y1) / 2
    drawLine(col, Offset(ic.x - 0.81f * u, ym), Offset(ic.x + 0.81f * u, ym), st.width * 0.8f)
    for (t in listOf(1f / 3, 2f / 3)) {
        drawLine(col, Offset(ic.x - 0.62f * u + 1.24f * u * t, y0), Offset(ic.x - u + 2 * u * t, y1), st.width * 0.8f)
    }
}

/** Strommast */
private fun DrawScope.drawPylon(ic: Offset, u: Float, col: Color) {
    val st = line(1.5.dp.toPx())
    fun p(x: Float, y: Float) = Offset(ic.x + x * u, ic.y + y * u)
    drawPath(Path().apply { moveTo(p(-0.6f, 1f).x, p(-0.6f, 1f).y); lineTo(p(0f, -1f).x, p(0f, -1f).y); lineTo(p(0.6f, 1f).x, p(0.6f, 1f).y) },
        col, style = st)
    // Querträger mit Isolatoren
    for ((y, x) in listOf(-0.55f to 0.78f, -0.12f to 0.56f)) {
        drawLine(col, p(-x, y), p(x, y), st.width, cap = StrokeCap.Round)
        drawLine(col, p(-x, y), p(-x, y + 0.18f), st.width * 0.8f, cap = StrokeCap.Round)
        drawLine(col, p(x, y), p(x, y + 0.18f), st.width * 0.8f, cap = StrokeCap.Round)
    }
    // Streben
    drawLine(col, p(-0.26f, -0.12f), p(0.6f, 1f), st.width * 0.7f)
    drawLine(col, p(0.26f, -0.12f), p(-0.6f, 1f), st.width * 0.7f)
}

/** Liegende Batterie, Füllung nach Ladestand, darin der Ladestand in % */
private fun DrawScope.drawBattery(tm: TextMeasurer, ic: Offset, u: Float, soc: Double?, col: Color, fill: Color) {
    val st = line(1.5.dp.toPx())
    val bw = 2.3f * u
    val bh = 1.15f * u
    val tl = Offset(ic.x - bw / 2 - 0.08f * u, ic.y - bh / 2)
    val corner = CornerRadius(2.5.dp.toPx())
    if (soc != null) {
        val f = (soc / 100).coerceIn(0.0, 1.0).toFloat()
        if (f > 0) drawRoundRect(fill.copy(alpha = 0.28f), tl, Size(bw * f, bh), corner)
    }
    drawRoundRect(col, tl, Size(bw, bh), corner, style = st)
    drawRoundRect(col, Offset(tl.x + bw, ic.y - 0.26f * u), Size(0.16f * u, 0.52f * u), CornerRadius(1.dp.toPx()))
    val s = soc?.let { String.format(Locale.GERMANY, "%.0f%%", it) } ?: "–"
    val l = tm.measure(s, TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = col))
    drawText(l, topLeft = Offset(tl.x + bw / 2 - l.size.width / 2f, ic.y - l.size.height / 2f))
}

/** Haus mit Blitz */
private fun DrawScope.drawHouse(ic: Offset, u: Float, col: Color) {
    val st = line(1.5.dp.toPx())
    fun p(x: Float, y: Float) = Offset(ic.x + x * u, ic.y + y * u)
    fun poly(q: List<Offset>, closed: Boolean = false) = Path().apply {
        moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; if (closed) close()
    }
    drawPath(poly(listOf(p(-1f, -0.1f), p(0f, -1f), p(1f, -0.1f))), col, style = st)                      // Dach
    drawPath(poly(listOf(p(-0.72f, -0.35f), p(-0.72f, 1f), p(0.72f, 1f), p(0.72f, -0.35f))), col, style = st) // Wände
    drawPath(poly(listOf(p(0.12f, -0.35f), p(-0.22f, 0.3f), p(0.04f, 0.3f), p(-0.12f, 0.82f),
        p(0.26f, 0.12f), p(0f, 0.12f)), closed = true), col, style = line(1.2.dp.toPx()))          // Blitz
}

/** Wechselrichter: Kasten mit Sinuswelle */
private fun DrawScope.drawInverter(c: Offset, r: Float, col: Color) {
    val st = line(1.5.dp.toPx())
    val bw = r * 1.0f
    val bh = r * 0.78f
    drawRoundRect(col, Offset(c.x - bw / 2, c.y - bh / 2), Size(bw, bh), CornerRadius(4.dp.toPx()), style = st)
    val wave = Path()
    val x0 = c.x - bw * 0.34f
    val steps = 24
    for (i in 0..steps) {
        val x = x0 + bw * 0.68f * i / steps
        val y = c.y - sin(i * 2 * PI / steps).toFloat() * bh * 0.22f
        if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
    }
    drawPath(wave, col, style = st)
}

// ---- Texte ------------------------------------------------------------------ //
/** Text zentriert ab "at" nach unten; gibt die Zeilenhöhe zurück */
private fun DrawScope.centered(tm: TextMeasurer, at: Offset, s: String, style: TextStyle): Float {
    val l = tm.measure(s, style)
    drawText(l, topLeft = Offset(at.x - l.size.width / 2f, at.y))
    return l.size.height.toFloat()
}

/** Bezeichnung über einem Kreis (Abstand dist vom Mittelpunkt) */
private fun DrawScope.labelAbove(tm: TextMeasurer, c: Offset, dist: Float, s: String, style: TextStyle) {
    val l = tm.measure(s, style)
    drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - dist - l.size.height))
}

/** Kleine Textzeilen zentriert ab "at" nach unten gestapelt */
private fun DrawScope.lines(tm: TextMeasurer, at: Offset, texts: List<String>, style: TextStyle) {
    var y = at.y
    for (s in texts) {
        val l = tm.measure(s, style)
        drawText(l, topLeft = Offset(at.x - l.size.width / 2f, y))
        y += l.size.height
    }
}
