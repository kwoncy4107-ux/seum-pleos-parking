package ai.seum.app.ui

import ai.seum.engine.Feature
import ai.seum.engine.Geo
import ai.seum.engine.Finding
import ai.seum.engine.LatLon
import ai.seum.engine.Layer
import ai.seum.engine.LocalProjection
import ai.seum.engine.ParkingSuggestion
import ai.seum.engine.Rule
import ai.seum.engine.Shape
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Rule features around the parked car, read from the bundle right after the verdict. */
data class SiteData(val center: LatLon, val features: List<Feature>, val tickets: List<ai.seum.engine.EnforcementPoint> = emptyList())

private val Bg = Color(0xFFF2F2F2)
private val Ring = Color(0xFFB9B9B9)
private val Ink = Color(0xFF000000)
private val Muted = Color(0xFF8A8A8A)
private val NoStop = Color(0xFFE5302F)   // 주정차 금지
private val NoPark = Color(0xFFE09A00)   // 5분 정차만 / 근처일 수 있음
private val Bus = Color(0xFF1F6FEB)
private val Car = Color(0xFF000000)
private val Ticket = Color(0xFF6A3FB5)   // 단속 기록

/**
 * "Range view": the car is fixed at the centre with 10/20/30 m distance rings, and every rule is drawn at
 * its legal range — not a circle around the car, because the law measures from the hydrant, the crosswalk,
 * the painted line. Red = no stopping or parking, amber = 5-minute stop only or "may be near".
 */
@Composable
fun SiteMap(
    site: SiteData, findings: List<Finding>, parking: ParkingSuggestion?, modifier: Modifier = Modifier,
    radiusM: Double = 32.0,
    /** Direction the car was driving when it pulled in (0 = north). Null: aligned to the nearest line, front unknown. */
    headingDeg: Double? = null,
) {
    val proj = remember(site.center) { LocalProjection(site.center) }
    val hit = remember(findings) { findings.mapNotNull { it.feature?.id }.toSet() }
    val text = remember { Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD } }

    Canvas(modifier.fillMaxSize()) {
        val s = min(size.width, size.height) / (2 * radiusM).toFloat() // px per metre
        val c = Offset(size.width / 2, size.height / 2)
        // Heading-up like a navigation map: when the driving direction is known the map is turned so the
        // car's front points up; otherwise it stays north-up.
        val known = headingDeg != null
        val up = Math.toRadians(headingDeg ?: 0.0)
        val cosU = kotlin.math.cos(up).toFloat(); val sinU = kotlin.math.sin(up).toFloat()
        fun turn(x: Float, y: Float) = Offset(x * cosU - y * sinU, x * sinU + y * cosU) // metres, y = forward
        fun P(p: LatLon): Offset {
            val t = turn(proj.x(p).toFloat(), proj.y(p).toFloat())
            return Offset(c.x + t.x * s, c.y - t.y * s)
        }
        fun path(pts: List<LatLon>, close: Boolean) = Path().apply {
            pts.forEachIndexed { i, p -> val o = P(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            if (close) close()
        }

        drawRect(Bg)
        // distance rings
        text.textSize = 15f; text.color = android.graphics.Color.rgb(120, 120, 120)
        for (r in listOf(10f, 20f, 30f)) {
            drawCircle(Ring, r * s, c, style = Stroke(1.5f))
            drawContext.canvas.nativeCanvas.drawText("${r.toInt()}m", c.x + r * s * 0.71f + 4f, c.y - r * s * 0.71f - 4f, text)
        }

        // B. "실제 단속 구간": where tickets cluster (8+ within 10 m), a merged purple area 5 m around each
        // spot. It shows enforced curbs that have no painted line or facility on record.
        run {
            val pts = site.tickets.map { it to P(it.pos) }
            val zone = android.graphics.Path()
            pts.forEach { (t, o) ->
                val n = site.tickets.filter { Geo.distanceM(it.pos, t.pos) <= 10.0 }.sumOf { it.total }
                if (n >= 8) {
                    val c = android.graphics.Path(); c.addCircle(o.x, o.y, 5f * s, android.graphics.Path.Direction.CW)
                    zone.op(c, android.graphics.Path.Op.UNION)
                }
            }
            val nc0 = drawContext.canvas.nativeCanvas
            nc0.drawPath(zone, android.graphics.Paint().apply { isAntiAlias = true; color = Ticket.copy(alpha = 0.16f).toArgb() })
            nc0.drawPath(zone, android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f
                color = Ticket.copy(alpha = 0.55f).toArgb()
            })
        }

        // zones at their legal range (band = the statutory distance on each side of the line / around the point).
        // Bands of the same kind are merged into one flat shape with one outline, so overlaps do not stack up
        // into darker blotches; red (no stopping) wins over blue (bus stop) over amber (5-minute stop).
        val feats = site.features
        val zc = drawContext.canvas.nativeCanvas
        val stroker = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        fun aPath(pts: List<LatLon>, close: Boolean) = android.graphics.Path().apply {
            pts.forEachIndexed { i, p -> val o = P(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            if (close) close()
        }
        val zones = mapOf(NoStop to android.graphics.Path(), Bus to android.graphics.Path(), NoPark to android.graphics.Path())
        val kinds = mutableListOf<Pair<Feature, Color>>()
        feats.forEach { f ->
            val rule = runCatching { Rule.forLayer(f.layer) }.getOrNull() ?: return@forEach
            val col = when (f.layer) {
                Layer.NO_PARK_LINE, Layer.INTERSECTION, Layer.JUNCTION -> NoPark
                Layer.BUS_STOP -> Bus
                Layer.CCTV, Layer.SCHOOL_ZONE -> return@forEach // drawn as markers below
                else -> NoStop
            }
            kinds += f to col
            val r = rule.limitM.toFloat() * s
            val area = android.graphics.Path()
            when (val sh = f.shape) {
                is Shape.Line -> { stroker.strokeWidth = 2f * r; stroker.getFillPath(aPath(sh.pts, false), area) }
                is Shape.Polygon -> {
                    val ring = aPath(sh.ring, true)
                    stroker.strokeWidth = 2f * r; stroker.getFillPath(ring, area)
                    area.op(ring, android.graphics.Path.Op.UNION)
                }
                is Shape.Point -> { val o = P(sh.p); area.addCircle(o.x, o.y, r, android.graphics.Path.Direction.CW) }
            }
            zones.getValue(col).op(area, android.graphics.Path.Op.UNION)
        }
        zones.getValue(Bus).op(zones.getValue(NoStop), android.graphics.Path.Op.DIFFERENCE)
        zones.getValue(NoPark).op(zones.getValue(NoStop), android.graphics.Path.Op.DIFFERENCE)
        zones.getValue(NoPark).op(zones.getValue(Bus), android.graphics.Path.Op.DIFFERENCE)
        val fillPaint = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL }
        val edgePaint = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f }
        for (col in listOf(NoPark, Bus, NoStop)) {
            fillPaint.color = col.copy(alpha = 0.2f).toArgb()
            zc.drawPath(zones.getValue(col), fillPaint)
            edgePaint.color = col.copy(alpha = 0.7f).toArgb()
            zc.drawPath(zones.getValue(col), edgePaint)
        }
        // where tickets were written last year (~3 m spots): shows which curbs are actually enforced,
        // including stretches that have no painted line or crosswalk on record. Bigger = more tickets.
        site.tickets.forEach { t ->
            val r = (2.5f + 1.8f * kotlin.math.ln(t.total.toFloat() + 1f) / kotlin.math.ln(2f)).coerceAtMost(10f)
            drawCircle(Ticket.copy(alpha = if (t.total >= 2) 0.6f else 0.35f), r, P(t.pos))
        }

        // the things themselves: painted lines, crosswalk outlines, point markers
        kinds.forEach { (f, col) ->
            val strong = f.id in hit
            when (val sh = f.shape) {
                is Shape.Line -> drawPath(path(sh.pts, false), col, style = Stroke(if (strong) 5f else 3f, cap = StrokeCap.Round))
                is Shape.Polygon -> {
                    val ring = path(sh.ring, true)
                    if (f.layer == Layer.CROSSWALK) drawZebra(ring, sh.ring.map { P(it) }, s)
                    drawPath(ring, Ink.copy(alpha = 0.55f), style = Stroke(2f))
                }
                is Shape.Point -> drawCircle(col, 6f, P(sh.p))
            }
        }
        // CCTV markers (and their 50 m area when it applies here)
        feats.filter { it.layer == Layer.CCTV }.forEach { f ->
            val o = P((f.shape as? Shape.Point)?.p ?: return@forEach)
            if (f.id in hit) {
                // A. the camera's enforcement area (about 50 m), as a dashed red edge (a fill would cover the map)
                val rr = Rule.CCTV.limitM.toFloat() * s
                drawCircle(NoStop.copy(alpha = 0.7f), rr, o, style = Stroke(2.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            }
            drawRoundRect(Color.White, Offset(o.x - 12f, o.y - 10f), Size(24f, 20f), CornerRadius(4f))
            drawRoundRect(Ink, Offset(o.x - 12f, o.y - 10f), Size(24f, 20f), CornerRadius(4f), style = Stroke(2f))
            drawCircle(Ink, 4.5f, o)
        }

        // the car: north-up map, so it is drawn at its real compass heading with an arrow at the front.
        // Without a heading it is aligned to the nearest painted line and the front is not claimed.
        val carDeg = if (known) 0f else lineHeadingDeg(feats, proj)
        drawCar(c, s, carDeg, known)
        // name tag behind the car (opposite the travel arrow), so the two never overlap
        text.textSize = 18f; text.color = android.graphics.Color.WHITE
        val tag = "내 차"; val tw = text.measureText(tag)
        val rad = Math.toRadians(carDeg.toDouble())
        val back = 3.6f * s + 18f
        val tc = Offset(c.x - (kotlin.math.sin(rad) * back).toFloat(), c.y + (kotlin.math.cos(rad) * back).toFloat())
        drawRect(Car, Offset(tc.x - tw / 2 - 8f, tc.y - 13f), Size(tw + 16f, 26f))
        drawContext.canvas.nativeCanvas.drawText(tag, tc.x - tw / 2, tc.y + 6f, text)

        // Direction to the suggested lot: it is usually hundreds of metres away, off this 30 m map, so a
        // "P 550m" tag sits on the map's outer edge and points toward it (it does not mark the lot itself).
        parking?.let { ps ->
            val t = turn(proj.x(ps.lot.pos).toFloat(), proj.y(ps.lot.pos).toFloat())
            val len = hypot(t.x, t.y).coerceAtLeast(0.01f)
            val ux = t.x / len; val uy = -t.y / len // screen direction
            val label = "P  ${distLabel(ps.distanceM)}"
            text.textSize = 20f; text.color = android.graphics.Color.WHITE
            val tw = text.measureText(label); val bw = tw + 28f; val bh = 38f
            // where the ray from the car meets the edge box (kept clear of the legend and the compass)
            val left = 14f + bw / 2; val right = size.width - 14f - bw / 2
            val top = 14f + bh / 2; val bottom = size.height - 54f - bh / 2
            val kx = if (ux > 0) (right - c.x) / ux else if (ux < 0) (left - c.x) / ux else Float.MAX_VALUE
            val ky = if (uy > 0) (bottom - c.y) / uy else if (uy < 0) (top - c.y) / uy else Float.MAX_VALUE
            val k = min(kx, ky)
            var bx = c.x + ux * k; val by = c.y + uy * k
            if (bx < 92f + bw / 2 && by < 84f) bx = 92f + bw / 2 // not over the compass
            val box = Offset(bx - bw / 2, by - bh / 2)
            // pointer: a triangle on the box side facing the lot
            val edgeK = min(if (ux != 0f) (bw / 2) / kotlin.math.abs(ux) else Float.MAX_VALUE, if (uy != 0f) (bh / 2) / kotlin.math.abs(uy) else Float.MAX_VALUE)
            val e = Offset(bx + ux * edgeK, by + uy * edgeK)
            val nx = -uy; val ny = ux
            val tri = Path().apply {
                moveTo(e.x + ux * 12f, e.y + uy * 12f)
                lineTo(e.x + nx * 9f - ux * 2f, e.y + ny * 9f - uy * 2f)
                lineTo(e.x - nx * 9f - ux * 2f, e.y - ny * 9f - uy * 2f)
                close()
            }
            drawPath(tri, Bus)
            drawRoundRect(Bus, box, Size(bw, bh), CornerRadius(bh / 2))
            drawContext.canvas.nativeCanvas.drawText(label, bx - tw / 2, by + 7f, text)
        }

        // north arrow, top-left
        val nc = Offset(40f, 44f)
        drawCircle(Color.White, 26f, nc)
        drawCircle(Ring, 26f, nc, style = Stroke(1.5f))
        val na = Path().apply { moveTo(nc.x, nc.y - 20f); lineTo(nc.x + 9f, nc.y + 2f); lineTo(nc.x - 9f, nc.y + 2f); close() }
        // the compass turns with the map: its arrow always points to real north
        text.textSize = 16f; text.color = android.graphics.Color.BLACK
        rotate(-(headingDeg ?: 0.0).toFloat(), pivot = nc) {
            drawPath(na, Ink)
            drawContext.canvas.nativeCanvas.drawText("N", nc.x - 5.5f, nc.y + 20f, text)
        }
        if (!known) {
            text.textSize = 16f; text.color = android.graphics.Color.rgb(110, 110, 110)
            drawContext.canvas.nativeCanvas.drawText("차 방향 추정 · 앞뒤 미확인", 78f, 50f, text)
        }

        // legend
        text.textSize = 16f
        var x = 16f
        val y = size.height - 16f
        for ((col, label) in listOf(NoStop to "주정차 금지", NoPark to "5분 정차만·근처", Bus to "버스정류장 10m", Ticket to "작년 단속 구간")) {
            drawRoundRect(col.copy(alpha = 0.6f), Offset(x, y - 13f), Size(16f, 16f), CornerRadius(3f))
            text.color = android.graphics.Color.rgb(70, 70, 70)
            drawContext.canvas.nativeCanvas.drawText(label, x + 22f, y, text)
            x += 22f + text.measureText(label) + 22f
        }
    }
}

/**
 * White zebra bars inside a crosswalk outline, like the road paint: bars ~0.5 m wide every ~1 m,
 * laid across the crossing (perpendicular to the polygon's longest side).
 */
private fun DrawScope.drawZebra(ring: Path, pts: List<Offset>, s: Float) {
    if (pts.size < 3) return
    var best = 0f; var ang = 0f
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len > best) { best = len; ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat() }
    }
    val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
    val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
    val reach = pts.maxOf { hypot(it.x - cx, it.y - cy) } + 4f
    val period = 1.0f * s; val bar = 0.5f * s
    clipPath(ring) {
        rotate(ang, pivot = Offset(cx, cy)) {
            var x = -reach
            while (x < reach) {
                drawRect(Color.White.copy(alpha = 0.9f), Offset(cx + x, cy - reach), Size(bar, 2 * reach))
                x += period
            }
        }
    }
}

/** Same rounding as the button and the voice (Speech.distanceWords), written short: 150m, 1.2km. */
private fun distLabel(m: Double): String =
    ai.seum.engine.Speech.distanceWords(m).replace("킬로미터", "km").replace("미터", "m")

private fun DrawScope.drawCar(c: Offset, s: Float, headingDeg: Float, frontKnown: Boolean) {
    val w = 1.9f * s; val h = 4.6f * s
    rotate(headingDeg, pivot = c) {
        if (frontKnown) {
            // travel arrow just ahead of the bonnet
            val tip = c.y - h / 2 - 2.6f * s
            val a = Path().apply {
                moveTo(c.x, tip); lineTo(c.x + 1.1f * s, tip + 1.5f * s); lineTo(c.x - 1.1f * s, tip + 1.5f * s); close()
            }
            drawPath(a, Car)
        }
        drawRoundRect(Color.Black.copy(alpha = 0.15f), Offset(c.x - w / 2 + 3f, c.y - h / 2 + 4f), Size(w, h), CornerRadius(0.5f * s))
        drawRoundRect(Car, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(0.5f * s))
        drawRoundRect(Color.White, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(0.5f * s), style = Stroke(2.5f))
        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(c.x - w / 2 + 0.2f * s, c.y - h / 2 + 0.6f * s), Size(w - 0.4f * s, 0.9f * s), CornerRadius(0.3f * s))
    }
}

/** Screen rotation for the car: direction of the closest painted line segment (0 = north-up). */
private fun lineHeadingDeg(features: List<Feature>, proj: LocalProjection): Float {
    var best = Double.MAX_VALUE
    var ang = 0.0
    features.mapNotNull { (it.shape as? Shape.Line)?.pts }.forEach { pts ->
        for (i in 0 until pts.size - 1) {
            val ax = proj.x(pts[i]); val ay = proj.y(pts[i]); val bx = proj.x(pts[i + 1]); val by = proj.y(pts[i + 1])
            val d = hypot((ax + bx) / 2, (ay + by) / 2)
            if (d < best && hypot(bx - ax, by - ay) > 2) { best = d; ang = Math.toDegrees(atan2(bx - ax, by - ay)) }
        }
    }
    return ang.toFloat()
}
