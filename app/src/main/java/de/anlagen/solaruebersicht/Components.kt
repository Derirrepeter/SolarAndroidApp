package de.anlagen.solaruebersicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

// ---- Farben ----------------------------------------------------------------- //
object C {
    val GREEN = Color(0xFF2E9E5B)
    val ORANGE = Color(0xFFE8871E)
    val RED = Color(0xFFD64541)
    val YELLOW = Color(0xFFF2A900)
    val BLUE = Color(0xFF2F7FD1)
    val VIOLET = Color(0xFF6A5ACD)
    val GREY = Color(0xFF9AA0A6)
}

/** Ladestand-Farben: unter 30 % rot, ab 30 % orange, über 75 % grün */
const val SOC_ORANGE_FROM = 30.0
const val SOC_GREEN_ABOVE = 75.0

fun socColor(v: Double) = when {
    v > SOC_GREEN_ABOVE -> C.GREEN
    v >= SOC_ORANGE_FROM -> C.ORANGE
    else -> C.RED
}

fun gaugeColor(metric: String, v: Double, lim: Limits): Color {
    val m = metric.lowercase(Locale.ROOT)
    return when {
        metric == M.GRID -> if (v > 0) C.RED else C.GREEN          // Bezug rot, Einspeisung grün
        metric == M.BATT -> if (v >= 0) C.GREEN else C.ORANGE      // laden grün, entladen orange
        "state of charge" in m -> socColor(v)
        "temperature" in m -> {
            val f = (v - lim.lo) / (lim.hi - lim.lo)
            if (f < 0.6) C.GREEN else if (f < 0.8) C.ORANGE else C.RED
        }
        m.startsWith("pv") -> C.YELLOW
        m.startsWith("load") -> C.BLUE
        else -> C.VIOLET
    }
}

// ---- Gauge ------------------------------------------------------------------ //
@Composable
fun Gauge(metric: String, value: Double?, limits: Limits?, dim: Boolean, modifier: Modifier = Modifier) {
    val unit = M.unit(metric)
    val track = MaterialTheme.colorScheme.outlineVariant
    val needle = MaterialTheme.colorScheme.onSurface
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(M.label(metric), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().aspectRatio(1.7f)) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = size.width * 0.10f
                val r = minOf(size.width / 2 - stroke / 2, size.height - stroke / 2) - 1f
                val cx = size.width / 2
                val cy = size.height - 2f
                val topLeft = Offset(cx - r, cy - r)
                val arcSize = Size(2 * r, 2 * r)
                drawArc(track, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke))
                if (limits == null) return@Canvas
                val span = (limits.hi - limits.lo).takeIf { it > 0 } ?: 1.0
                fun ang(v: Double) = 180f + 180f * ((v - limits.lo) / span).coerceIn(0.0, 1.0).toFloat()
                limits.redTo?.let { red ->
                    if (red > limits.lo) drawArc(Color(0xFFF6CFCB), 180f, ang(red) - 180f, false, topLeft, arcSize, style = Stroke(stroke))
                }
                if (value != null) {
                    val a0 = ang(minOf(maxOf(0.0, limits.lo), limits.hi))
                    val av = ang(value)
                    val col = if (dim) C.GREY else gaugeColor(metric, value, limits)
                    if (abs(av - a0) >= 0.5f) drawArc(col, minOf(a0, av), abs(av - a0), false, topLeft, arcSize, style = Stroke(stroke))
                    radialLine(cx, cy, r, av, stroke * 0.9f, if (dim) C.GREY else needle, 3.dp.toPx())
                }
                if (limits.lo < 0 && limits.hi > 0) radialLine(cx, cy, r, ang(0.0), stroke * 0.7f, C.GREY, 1.dp.toPx())
            }
            Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(value), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = if (dim) C.GREY else MaterialTheme.colorScheme.onSurface)
                Text(unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (limits != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shortNum(limits.lo, unit), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(shortNum(limits.hi, unit), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun DrawScope.radialLine(cx: Float, cy: Float, r: Float, angleDeg: Float, half: Float, color: Color, width: Float) {
    val rad = Math.toRadians(angleDeg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    drawLine(color, Offset(cx + (r - half) * c, cy + (r - half) * s), Offset(cx + (r + half) * c, cy + (r + half) * s), width)
}

// ---- Ladestand als Batteriesymbol ----------------------------------------- //
@Composable
fun BatterySoc(value: Double?, minSoc: Double?, dim: Boolean, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = MaterialTheme.colorScheme.surface
    val marker = MaterialTheme.colorScheme.onSurface
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ladestand", style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().aspectRatio(2.2f).padding(horizontal = 6.dp, vertical = 4.dp)) {
            val tip = size.width * 0.07f
            val bodyW = size.width - tip
            val line = 3.dp.toPx()
            val pad = 4.dp.toPx()
            drawRect(outline, Offset(bodyW, size.height * 0.3f), Size(tip, size.height * 0.4f))
            drawRoundRect(empty, Offset.Zero, Size(bodyW, size.height), CornerRadius(4.dp.toPx()))
            drawRoundRect(outline, Offset.Zero, Size(bodyW, size.height), CornerRadius(4.dp.toPx()), style = Stroke(line))
            if (value != null) {
                val f = (value / 100).coerceIn(0.0, 1.0).toFloat()
                val innerW = bodyW - 2 * pad
                if (f > 0) drawRect(if (dim) C.GREY else socColor(value), Offset(pad, pad), Size(innerW * f, size.height - 2 * pad))
                // Mindest-Ladestand (Entladestopp aus den Einstellungen) gestrichelt
                minSoc?.let {
                    val x = pad + innerW * (it / 100).coerceIn(0.0, 1.0).toFloat()
                    drawLine(marker, Offset(x, line), Offset(x, size.height - line), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                }
            }
        }
        Text(if (value == null) "–" else String.format(Locale.GERMANY, "%.0f %% SOC", value),
            fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (dim) C.GREY else MaterialTheme.colorScheme.onSurface)
    }
}

// ---- Kachel für Zahlen ohne Skala (z. B. kWh heute) ------------------------ //
@Composable
fun ValueTile(metric: String, value: Double?, dim: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(M.label(metric), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        Text(fmt(value), fontWeight = FontWeight.Bold, fontSize = 22.sp,
            color = if (dim) C.GREY else MaterialTheme.colorScheme.onSurface)
        Text(M.unit(metric), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Weiße Karte für Diagramme und Tabellen ------------------------------- //
@Composable
fun ChartCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { content() }
    }
}

// ---- Auswahlliste ------------------------------------------------------------ //
@Composable
fun Selector(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: $selected", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Text(" $label", fontSize = 11.sp)
            }
        }
    }
}

// ---- Liniendiagramm über die Zeit ------------------------------------------ //
data class ChartLine(
    val label: String,
    val points: Series,
    val color: Color,
    val fill: Boolean = false,
    val negColor: Color? = null,    // gesetzt: Fläche oberhalb 0 in color, unterhalb in negColor
)

@Composable
fun TimeChart(
    title: String, unit: String, lines: List<ChartLine>, start: Instant, end: Instant,
    scale: Double = 1.0, yRange: Pair<Double, Double>? = null, height: Dp = 170.dp,
) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (unit.isNotEmpty()) "$title [$unit]" else title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Legend(lines.filter { it.label.isNotEmpty() }.flatMap { l ->
                if (l.negColor != null) listOf("laden" to l.color, "entladen" to l.negColor) else listOf(l.label to l.color)
            })
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val left = 40.dp.toPx()
            val bottom = 18.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom
            val t0 = start.epochSecond.toDouble()
            val t1 = end.epochSecond.toDouble()
            val all = lines.flatMap { l -> l.points.mapNotNull { it.second?.times(scale) } }
            var (lo, hi) = yRange ?: ((all.minOrNull() ?: 0.0).coerceAtMost(0.0) to (all.maxOrNull() ?: 1.0).coerceAtLeast(0.0))
            if (hi - lo < 1e-9) hi = lo + 1
            val ticks = niceTicks(lo, hi)
            lo = minOf(lo, ticks.first()); hi = maxOf(hi, ticks.last())
            fun x(t: Double) = left + ((t - t0) / (t1 - t0) * w).toFloat()
            fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()

            val paint = android.graphics.Paint().apply { color = textColor; textSize = 10.dp.toPx(); isAntiAlias = true }
            ticks.forEach { v ->
                drawLine(gridColor, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
                paint.textAlign = android.graphics.Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(tickLabel(v), left - 4.dp.toPx(), y(v) + 3.dp.toPx(), paint)
            }
            if (lo < 0 && hi > 0) drawLine(zeroColor, Offset(left, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            paint.textAlign = android.graphics.Paint.Align.CENTER
            timeTicks(t0, t1).filter { it.first < t1 - (t1 - t0) * 0.03 }.forEach { (t, label) ->
                drawLine(gridColor, Offset(x(t), 0f), Offset(x(t), h), 1f)
                drawContext.canvas.nativeCanvas.drawText(label, x(t), size.height - 3.dp.toPx(), paint)
            }

            for (l in lines) {
                val pts = l.points.map { (t, v) -> t to v?.times(scale) }
                if (l.negColor != null) {
                    drawArea(pts.map { (t, v) -> t to v?.let { maxOf(it, 0.0) } }, ::x, ::y, l.color.copy(alpha = 0.55f), lo, hi)
                    drawArea(pts.map { (t, v) -> t to v?.let { minOf(it, 0.0) } }, ::x, ::y, l.negColor.copy(alpha = 0.55f), lo, hi)
                    continue
                }
                if (l.fill) drawArea(pts, ::x, ::y, l.color.copy(alpha = 0.25f), lo, hi)
                val path = Path()
                var pen = false
                for ((t, v) in pts) {
                    if (v == null) { pen = false; continue }
                    if (!pen) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                    pen = true
                }
                drawPath(path, l.color, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawArea(pts: Series, x: (Double) -> Float, y: (Double) -> Float, color: Color, lo: Double, hi: Double) {
    val base = y(0.0.coerceIn(lo, hi))
    var seg = mutableListOf<Pair<Float, Float>>()
    fun flush() {
        if (seg.size > 1) {
            val p = Path()
            p.moveTo(seg.first().first, base)
            seg.forEach { (px, py) -> p.lineTo(px, py) }
            p.lineTo(seg.last().first, base)
            p.close()
            drawPath(p, color)
        }
        seg = mutableListOf()
    }
    for ((t, v) in pts) if (v == null) flush() else seg.add(x(t) to y(v))
    flush()
}

private fun niceTicks(lo: Double, hi: Double, count: Int = 4): List<Double> {
    val raw = (hi - lo) / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val first = floor(lo / step) * step
    val last = ceil(hi / step) * step
    val out = mutableListOf<Double>()
    var v = first
    while (v <= last + step / 2) { out.add(v); v += step }
    return out
}

private fun tickLabel(v: Double) =
    if (abs(v) >= 10 || v == 0.0) String.format(Locale.GERMANY, "%.0f", v) else String.format(Locale.GERMANY, "%.1f", v)

private fun timeTicks(t0: Double, t1: Double): List<Pair<Double, String>> {
    // Beschriftung an runden Zeitpunkten (volle 3 Stunden, Mitternacht, Monatsanfang)
    val days = (t1 - t0) / 86400
    val zone = ZoneId.systemDefault()
    val first = Instant.ofEpochSecond(t0.toLong()).atZone(zone)
    val out = mutableListOf<Pair<Double, String>>()
    fun add(z: java.time.ZonedDateTime, f: DateTimeFormatter) {
        val t = z.toEpochSecond().toDouble()
        if (t >= t0 && t <= t1) out.add(t to z.format(f))
    }
    val midnight = first.toLocalDate().plusDays(1).atStartOfDay(zone)
    when {
        days <= 1.1 -> {
            val f = DateTimeFormatter.ofPattern("HH:mm")
            var z = first.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
            while (z.hour % 3 != 0) z = z.plusHours(1)
            while (z.toEpochSecond() <= t1) { add(z, f); z = z.plusHours(3) }
        }
        days <= 8 -> {
            val f = DateTimeFormatter.ofPattern("EE d.", Locale.GERMANY)
            var z = midnight
            while (z.toEpochSecond() <= t1) { add(z, f); z = z.plusDays(1) }
        }
        days <= 32 -> {
            val f = DateTimeFormatter.ofPattern("d.M.")
            var z = midnight
            while (z.toEpochSecond() <= t1) { add(z, f); z = z.plusDays(5) }
        }
        else -> {
            val f = DateTimeFormatter.ofPattern("MMM yy", Locale.GERMANY)
            var z = first.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone)
            while (z.toEpochSecond() <= t1) { add(z, f); z = z.plusMonths(2) }
        }
    }
    return out
}

// ---- Balkendiagramm (Summen) ------------------------------------------------ //
@Composable
fun BarChart(title: String, labels: List<String>, groups: List<Pair<String, Color>>, values: List<DoubleArray>, height: Dp = 180.dp) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Legend(groups)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val left = 34.dp.toPx()
            val bottom = 18.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom
            val maxV = values.maxOfOrNull { it.maxOrNull() ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
            val ticks = niceTicks(0.0, maxV)
            val top = ticks.last()
            fun y(v: Double) = (h - v / top * h).toFloat()
            val paint = android.graphics.Paint().apply { color = textColor; textSize = 10.dp.toPx(); isAntiAlias = true }
            ticks.forEach { v ->
                drawLine(gridColor, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
                paint.textAlign = android.graphics.Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(tickLabel(v), left - 4.dp.toPx(), y(v) + 3.dp.toPx(), paint)
            }
            val slot = w / maxOf(1, labels.size)
            val barW = slot * 0.8f / groups.size
            paint.textAlign = android.graphics.Paint.Align.CENTER
            labels.forEachIndexed { i, label ->
                val x0 = left + i * slot + slot * 0.1f
                groups.forEachIndexed { g, (_, color) ->
                    val v = values.getOrNull(i)?.getOrNull(g) ?: 0.0
                    drawRect(color, Offset(x0 + g * barW, y(v)), Size(barW * 0.92f, h - y(v)))
                }
                if (i % maxOf(1, labels.size / 6) == 0)
                    drawContext.canvas.nativeCanvas.drawText(label, left + i * slot + slot / 2, size.height - 3.dp.toPx(), paint)
            }
        }
    }
}

// ---- Tabelle (Summen) -------------------------------------------------------- //
@Composable
fun KwhTable(first: String, columns: List<String>, rows: List<Pair<String, DoubleArray>>, withSum: Boolean = true) {
    val sums = DoubleArray(columns.size)
    rows.forEach { (_, v) -> v.forEachIndexed { i, x -> sums[i] += x } }
    val head = MaterialTheme.colorScheme.primaryContainer
    val zebra = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val sum = MaterialTheme.colorScheme.tertiaryContainer
    Column(Modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
        TableRow(first, columns, bold = true, header = true, bg = head)
        rows.forEachIndexed { i, (label, v) ->
            TableRow(label, v.map { fmtKwh(it) }, bg = if (i % 2 == 1) zebra else Color.Transparent)
        }
        if (withSum && rows.isNotEmpty()) TableRow("Summe", sums.map { fmtKwh(it) }, bold = true, bg = sum)
    }
}

@Composable
private fun TableRow(label: String, cells: List<String>, bold: Boolean = false, header: Boolean = false, bg: Color = Color.Transparent) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.background(bg, RoundedCornerShape(8.dp)).padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(label, Modifier.width(112.dp), fontWeight = weight, fontSize = 13.sp)
        cells.forEach {
            Text(if (header) "$it\n[kWh]" else it, Modifier.width(92.dp), textAlign = TextAlign.End,
                fontWeight = weight, fontSize = if (header) 11.sp else 13.sp)
        }
    }
}
