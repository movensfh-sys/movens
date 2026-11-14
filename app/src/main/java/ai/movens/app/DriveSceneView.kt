package ai.movens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 주행 장면 (원근 3D 도로). 내 차가 3차선 도로 가운데 차로를 달리고, 차선·가로수·앞차가 속도에 맞춰 다가온다.
 * 브레이크 → 브레이크등, 비 → 빗줄기·와이퍼, 엔진 이상 소리 → 차체 떨림·소리 표시, 켜진 경고등 → 화면 위 계기판 줄.
 * 30 fps로 스스로 다시 그린다. 값은 MainActivity가 0.5초마다 넣어 준다.
 */
class DriveSceneView(ctx: Context) : View(ctx) {
    // ---- 바깥에서 넣는 상태 ----
    var targetSpeed = 0f
    var braking = false
    var raining = false
    var knocking = false
    var running = false
    var gearText = "P"
    var caption = ""
    var alertColor: Int? = null                     // 테두리 경고색 (엔진 이상·빨간 경고등)
    var telltaleIcons: List<Pair<Glyph, Int>> = emptyList()

    // ---- 내부 ----
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private val rnd = Random(3)
    private var speed = 0f                          // 화면에 보이는 속도 (부드럽게 따라감)
    private var traveled = 0f                       // m
    private var lastFrame = 0L
    private var wiperPhase = 0f
    private class Other(var lane: Int, var z: Float, var speedKmh: Float, val color: Int)
    private val others = ArrayList<Other>()
    private val drops = Array(70) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat()) }

    private val laneW = 3.5f        // m
    private val near = 4f           // 화면 맨 아래가 보는 거리 (m)
    private val far = 160f

    init {
        val colors = intArrayOf(0xFFE6E6E6.toInt(), 0xFF2E3A4F.toInt(), 0xFFB23A3A.toInt(), 0xFF6B7280.toInt(), 0xFF1F2937.toInt())
        for (i in 0 until 4) others += Other(i % 3, 30f + i * 35f, 0f, colors[i % colors.size])
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0L; postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = now
        update(dt)

        val w = width.toFloat(); val h = height.toFloat()
        val horizon = h * 0.36f
        val pxPerM = (w * 0.46f) / (1.5f * laneW)            // 가까운 거리(near)에서 1 m의 픽셀
        fun sy(z: Float) = horizon + (h - horizon) * (near / z)
        fun sx(xm: Float, z: Float) = w / 2 + xm * pxPerM * (near / z)
        val shake = if (knocking && running) (rnd.nextFloat() - 0.5f) * 5 * d else 0f

        c.save()
        c.clipRect(0f, 0f, w, h)
        // 하늘
        p.shader = LinearGradient(0f, 0f, 0f, horizon,
            if (raining) 0xFF39424F.toInt() else 0xFF5B8FD6.toInt(), if (raining) 0xFF6B7684.toInt() else 0xFFBFD8F2.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizon, p); p.shader = null
        // 먼 산
        p.color = if (raining) 0xFF4A5563.toInt() else 0xFF7FA3C7.toInt()
        val hills = Path().apply {
            moveTo(0f, horizon)
            for (i in 0..8) { val x = w * i / 8f; lineTo(x, horizon - h * (0.04f + 0.035f * ((i * 37) % 5) / 4f)) }
            lineTo(w, horizon); close()
        }
        c.drawPath(hills, p)
        // 땅
        p.color = if (raining) 0xFF3E5A3E.toInt() else 0xFF5E8F4E.toInt()
        c.drawRect(0f, horizon, w, h, p)
        // 도로
        val road = Path().apply {
            moveTo(sx(-1.5f * laneW - 0.6f, far), sy(far)); lineTo(sx(1.5f * laneW + 0.6f, far), sy(far))
            lineTo(sx(1.5f * laneW + 0.6f, near * 0.8f), h + 2); lineTo(sx(-1.5f * laneW - 0.6f, near * 0.8f), h + 2); close()
        }
        p.color = if (raining) 0xFF2B2F36.toInt() else 0xFF3C4048.toInt()
        c.drawPath(road, p)
        // 가장자리 실선
        p.color = 0xFFEDEDED.toInt()
        for (side in listOf(-1f, 1f)) quad(c, side * 1.5f * laneW, 0.18f, near * 0.8f, far, ::sx, ::sy)
        // 차선 점선 (12 m 간격, 5 m 길이)
        p.color = 0xFFF5F5F5.toInt()
        val off = traveled % 12f
        var z = near - off
        while (z < far) {
            val z1 = max(z, near * 0.8f); val z2 = z + 5f
            if (z2 > near * 0.8f) for (side in listOf(-0.5f, 0.5f)) quad(c, side * laneW, 0.15f, z1, min(z2, far), ::sx, ::sy)
            z += 12f
        }
        // 가로수 (25 m 간격, 먼 것부터)
        val toff = traveled % 25f
        val trees = ArrayList<Float>()
        var tz = near - toff; while (tz < far) { if (tz > near * 0.9f) trees += tz; tz += 25f }
        for (zz in trees.sortedDescending()) for (side in listOf(-1f, 1f)) {
            val x = sx(side * (1.5f * laneW + 4f), zz); val y = sy(zz); val s = pxPerM * (near / zz)
            p.color = 0xFF5B4632.toInt(); c.drawRect(x - 0.25f * s, y - 2.2f * s, x + 0.25f * s, y, p)
            p.color = if (raining) 0xFF2F5A34.toInt() else 0xFF3E7D3A.toInt(); c.drawCircle(x, y - 3f * s, 1.5f * s, p)
        }
        // 앞차들 (먼 것부터)
        for (o in others.sortedByDescending { it.z }) {
            if (o.z < near || o.z > far) continue
            val s = pxPerM * (near / o.z)
            val cx = sx((o.lane - 1) * laneW, o.z); val by = sy(o.z)
            val cw = 1.8f * s; val ch = 1.35f * s
            p.color = 0x55000000; c.drawOval(RectF(cx - cw * 0.55f, by - 0.12f * s, cx + cw * 0.55f, by + 0.12f * s), p)
            p.color = o.color; c.drawRoundRect(RectF(cx - cw / 2, by - ch, cx + cw / 2, by - 0.15f * s), 0.25f * s, 0.25f * s, p)
            p.color = 0xFF1B2330.toInt(); c.drawRoundRect(RectF(cx - cw * 0.38f, by - ch * 0.95f, cx + cw * 0.38f, by - ch * 0.62f), 0.15f * s, 0.15f * s, p)
            p.color = 0xFFE03A3A.toInt()
            c.drawRect(cx - cw * 0.46f, by - ch * 0.5f, cx - cw * 0.28f, by - ch * 0.4f, p)
            c.drawRect(cx + cw * 0.28f, by - ch * 0.5f, cx + cw * 0.46f, by - ch * 0.4f, p)
        }
        // 내 차 (뒤에서 본 모습)
        drawMyCar(c, w / 2 + shake, h - 6 * d, w * 0.30f, shake)
        // 비
        if (raining) {
            p.color = 0x99CFE3FF.toInt(); p.strokeWidth = 2 * d
            for (dr in drops) {
                val x = dr[0] * w; val y = dr[1] * h
                c.drawLine(x, y, x - 6 * d, y + 22 * d, p)
            }
            // 와이퍼 (앞유리 위를 쓸고 지나가는 호)
            p.color = 0xCC111111.toInt(); p.strokeWidth = 5 * d; p.strokeCap = Paint.Cap.ROUND
            val ang = Math.toRadians((200 + 140 * kotlin.math.abs(kotlin.math.sin(wiperPhase.toDouble()))).toDouble())
            val px = w * 0.30f; val py = h * 1.02f; val len = h * 0.55f
            c.drawLine(px, py, (px + len * kotlin.math.cos(ang)).toFloat(), (py + len * kotlin.math.sin(ang)).toFloat(), p)
            p.strokeCap = Paint.Cap.BUTT
        }
        c.restore()

        drawHud(c, w, h)

        alertColor?.let {
            p.style = Paint.Style.STROKE; p.strokeWidth = 8 * d; p.color = it
            if ((System.currentTimeMillis() / 400) % 2 == 0L) c.drawRoundRect(RectF(4 * d, 4 * d, w - 4 * d, h - 4 * d), 18 * d, 18 * d, p)
            p.style = Paint.Style.FILL
        }
        if (isShown) postInvalidateDelayed(33)
    }

    private fun update(dt: Float) {
        val target = if (running) targetSpeed else 0f
        speed += (target - speed) * min(1f, dt * 2.5f)
        if (speed < 0.05f) speed = 0f
        val v = speed / 3.6f
        traveled += v * dt
        // 앞차: 내 차보다 조금 느리거나 빠르게. 지나가면 뒤쪽/앞쪽에서 다시 나타난다.
        for (o in others) {
            if (o.speedKmh == 0f || !running) o.speedKmh = if (running) max(0f, target + rnd.nextInt(-18, 12)) else 0f
            o.z += (o.speedKmh / 3.6f - v) * dt
            if (o.z < near * 0.7f) { o.z = far - rnd.nextInt(0, 30); o.lane = rnd.nextInt(0, 3); o.speedKmh = max(0f, target + rnd.nextInt(-15, -3)) }
            if (o.z > far + 10) { o.z = 40f + rnd.nextInt(0, 40); o.lane = listOf(0, 2).random(rnd); o.speedKmh = max(0f, target + rnd.nextInt(-12, 0)) }
            if (o.lane == 1 && o.z < 18f) o.lane = if (rnd.nextBoolean()) 0 else 2      // 내 차로 바로 앞은 비워 둔다
        }
        if (raining) for (dr in drops) { dr[1] += dt * 2.2f; dr[0] -= dt * 0.35f; if (dr[1] > 1f) { dr[1] = 0f; dr[0] = rnd.nextFloat() * 1.1f } }
        wiperPhase += dt * 2.2f
    }

    /** 원근 사다리꼴 띠 (도로 위 선). xm=가로 위치(m), wm=폭(m). */
    private fun quad(c: Canvas, xm: Float, wm: Float, z1: Float, z2: Float, sx: (Float, Float) -> Float, sy: (Float) -> Float) {
        val path = Path().apply {
            moveTo(sx(xm - wm / 2, z1), sy(z1)); lineTo(sx(xm + wm / 2, z1), sy(z1))
            lineTo(sx(xm + wm / 2, z2), sy(z2)); lineTo(sx(xm - wm / 2, z2), sy(z2)); close()
        }
        c.drawPath(path, p)
    }

    private fun drawMyCar(c: Canvas, cx: Float, bottom: Float, cw: Float, shake: Float) {
        val ch = cw * 0.62f
        val top = bottom - ch
        p.color = 0x66000000; c.drawOval(RectF(cx - cw * 0.58f, bottom - ch * 0.08f, cx + cw * 0.58f, bottom + ch * 0.06f), p)
        // 바퀴
        p.color = 0xFF111111.toInt()
        c.drawRoundRect(RectF(cx - cw * 0.47f, bottom - ch * 0.22f, cx - cw * 0.30f, bottom), 6 * d, 6 * d, p)
        c.drawRoundRect(RectF(cx + cw * 0.30f, bottom - ch * 0.22f, cx + cw * 0.47f, bottom), 6 * d, 6 * d, p)
        // 차체
        p.color = 0xFFC9CED6.toInt()
        c.drawRoundRect(RectF(cx - cw / 2, top + ch * 0.32f, cx + cw / 2, bottom - ch * 0.12f), 18 * d, 18 * d, p)
        // 지붕·뒷유리
        val roof = Path().apply {
            moveTo(cx - cw * 0.36f, top + ch * 0.36f); lineTo(cx - cw * 0.28f, top); lineTo(cx + cw * 0.28f, top); lineTo(cx + cw * 0.36f, top + ch * 0.36f); close()
        }
        p.color = 0xFFAEB4BE.toInt(); c.drawPath(roof, p)
        p.color = 0xFF1C2532.toInt()
        c.drawRoundRect(RectF(cx - cw * 0.27f, top + ch * 0.06f, cx + cw * 0.27f, top + ch * 0.32f), 8 * d, 8 * d, p)
        // 일자 리어램프 (브레이크 때 밝게)
        p.color = if (braking && running) 0xFFFF2B2B.toInt() else 0xFF8E1B1B.toInt()
        c.drawRoundRect(RectF(cx - cw * 0.46f, top + ch * 0.44f, cx + cw * 0.46f, top + ch * 0.52f), 4 * d, 4 * d, p)
        if (braking && running) {
            p.color = 0x55FF2B2B; c.drawRoundRect(RectF(cx - cw * 0.52f, top + ch * 0.38f, cx + cw * 0.52f, top + ch * 0.58f), 12 * d, 12 * d, p)
        }
        // 번호판
        p.color = 0xFFF2F2F2.toInt(); c.drawRoundRect(RectF(cx - cw * 0.12f, top + ch * 0.62f, cx + cw * 0.12f, top + ch * 0.74f), 3 * d, 3 * d, p)
        p.color = 0xFF222222.toInt(); p.textAlign = Paint.Align.CENTER; p.textSize = ch * 0.09f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("movens", cx, top + ch * 0.715f, p); p.textAlign = Paint.Align.LEFT
        // 엔진 이상 소리: 차 위에 소리 파형 표시
        if (knocking && running) {
            GlyphPainter.draw(c, Glyph.SOUND, RectF(cx - 22 * d + shake, top - 52 * d, cx + 22 * d + shake, top - 8 * d), AMBER)
        }
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        // 왼쪽 위: 기어 + 속도
        p.color = 0xB30E1116.toInt(); c.drawRoundRect(RectF(12 * d, 12 * d, 230 * d, 92 * d), 16 * d, 16 * d, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = if (gearText == "D") BLUE else TEXT; p.textSize = 40 * d; c.drawText(gearText, 26 * d, 70 * d, p)
        val st = "%.0f".format(speed)
        p.color = TEXT; p.textSize = 44 * d; c.drawText(st, 74 * d, 72 * d, p)
        val sw = p.measureText(st)
        p.color = MUTED; p.textSize = 16 * d; p.typeface = Typeface.DEFAULT
        c.drawText("km/h", 74 * d + sw + 6 * d, 72 * d, p)
        // 아래 자막
        if (caption.isNotEmpty()) {
            p.textSize = 17 * d; p.typeface = Typeface.DEFAULT_BOLD
            val tw = p.measureText(caption)
            p.color = 0xB30E1116.toInt(); c.drawRoundRect(RectF(12 * d, h - 48 * d, 12 * d + tw + 28 * d, h - 12 * d), 12 * d, 12 * d, p)
            p.color = TEXT; c.drawText(caption, 26 * d, h - 24 * d, p)
        }
        // 오른쪽 위: 켜진 경고등 (계기판처럼)
        if (telltaleIcons.isNotEmpty()) {
            val sz = 46 * d; val gap = 8 * d
            val n = min(6, telltaleIcons.size)
            val left = w - 12 * d - n * (sz + gap) - gap
            p.color = 0xCC0E1116.toInt(); c.drawRoundRect(RectF(left, 12 * d, w - 12 * d, 12 * d + sz + 2 * gap), 16 * d, 16 * d, p)
            telltaleIcons.take(n).forEachIndexed { i, (g, col) ->
                val x = left + gap + i * (sz + gap)
                GlyphPainter.draw(c, g, RectF(x, 12 * d + gap, x + sz, 12 * d + gap + sz), col)
            }
        }
        if (!running) {
            p.textSize = 22 * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
            p.color = 0xCC0E1116.toInt(); val tw = p.measureText("▶ 주행 시뮬레이션 시작을 누르세요")
            c.drawRoundRect(RectF(w / 2 - tw / 2 - 20 * d, h * 0.42f - 34 * d, w / 2 + tw / 2 + 20 * d, h * 0.42f + 14 * d), 14 * d, 14 * d, p)
            p.color = TEXT; c.drawText("▶ 주행 시뮬레이션 시작을 누르세요", w / 2, h * 0.42f, p)
            p.textAlign = Paint.Align.LEFT
        }
    }
}
