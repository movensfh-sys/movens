package ai.movens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 후진 주차 시뮬레이터의 동작 규칙. 순수 Kotlin (ReverseSimModelTest).
 *
 * - 기어 R에서 가속 페달을 한 번 누를 때마다 속도가 1.5 km/h 붙고(최대 6 km/h), 떼면 서서히 멈춘다. 브레이크를 밟으면 바로 멈춘다.
 * - 가속 페달을 rapidWindowMs 안에 rapidTaps번 이상 연타(광클)하면 "급발진 의심 상황"으로 본다.
 *   그 순간부터는 운전자가 브레이크를 밟을 때까지 차가 스스로 가속한다(급발진 재현).
 * - 후방 차량과의 거리가 0.2 m 이하가 되면 충돌.
 */
class ReverseSimModel(
    private val startGapM: Float = 5.0f,
    val rapidTaps: Int = 5,
    val rapidWindowMs: Long = 1_500,
) {
    enum class Gear { P, R, N, D }
    enum class Phase { READY, SUA, STOPPED, CRASHED }
    enum class Zone(val label: String) { SAFE("안전"), CAUTION("주의"), DANGER("위험") }

    class Sample(val t: Long, val speed: Float, val gap: Float, val throttle: Float, val brake: Boolean)

    var gear = Gear.P; private set
    var gapM = startGapM; private set
    var speedKmh = 0f; private set
    var throttle = 0f; private set
    var brake = false; private set
    var phase = Phase.READY; private set
    var peakSpeed = 0f; private set
    var minGap = startGapM; private set
    var suaAt = -1L; private set
    var brakeAt = -1L; private set
    var gapAtSua = startGapM; private set
    var tapsAtSua = 0; private set
    private val taps = ArrayDeque<Long>()
    val timeline = ArrayList<Sample>()

    val recentTaps get() = taps.size
    val zone get() = when { gapM < 1f -> Zone.DANGER; gapM < 2f -> Zone.CAUTION; else -> Zone.SAFE }

    fun setGear(g: Gear) { if (speedKmh < 0.5f && phase == Phase.READY) gear = g }

    /** 가속 페달 한 번 누름. 급발진 의심 상황이 시작되면 true. */
    fun tapAccel(now: Long): Boolean {
        if (gear != Gear.R || phase == Phase.CRASHED || phase == Phase.STOPPED) return false
        taps.addLast(now)
        while (taps.isNotEmpty() && taps.first() < now - rapidWindowMs) taps.removeFirst()
        throttle = 1f                                       // 화면 표시용 (페달이 눌린 정도)
        if (phase == Phase.READY) speedKmh = min(6f, speedKmh + 1.5f)   // 한 번 누를 때마다 살짝 힘이 실림
        if (phase == Phase.READY && taps.size >= rapidTaps) {
            phase = Phase.SUA; suaAt = now; gapAtSua = gapM; tapsAtSua = taps.size; brakeAt = -1L
            return true
        }
        return false
    }

    fun setBrake(pressed: Boolean, now: Long) {
        brake = pressed
        if (pressed && phase == Phase.SUA && brakeAt < 0) brakeAt = now
    }

    /** dtMs마다 호출해 속도·거리를 갱신한다. */
    fun step(now: Long, dtMs: Long) {
        val dt = dtMs / 1000f
        while (taps.isNotEmpty() && taps.first() < now - rapidWindowMs) taps.removeFirst()
        throttle = max(0f, throttle - 2f * dt)
        if (gear != Gear.R || phase == Phase.CRASHED) { speedKmh = 0f; record(now); return }

        val accel = when {                       // km/h per s
            brake -> -30f
            phase == Phase.SUA -> 6f             // 급발진 재현: 페달과 상관없이 계속 가속
            else -> -1.2f                        // 페달에서 발을 떼면 서서히 멈춤
        }
        val cap = if (phase == Phase.SUA) 30f else 6f   // 평소 주차 속도는 6 km/h까지
        speedKmh = (speedKmh + accel * dt).coerceIn(0f, cap)
        gapM -= speedKmh / 3.6f * dt
        peakSpeed = max(peakSpeed, speedKmh)
        if (gapM <= 0.2f) { gapM = 0.2f; speedKmh = 0f; phase = Phase.CRASHED }
        minGap = min(minGap, gapM)
        if (phase == Phase.SUA && brake && speedKmh == 0f) phase = Phase.STOPPED
        record(now)
    }

    private fun record(now: Long) {
        timeline.add(Sample(now, speedKmh, gapM, throttle, brake))
        if (timeline.size > 600) timeline.removeAt(0)     // 50 ms 간격이면 약 30초
    }

    /** 브레이크 반응 시간 (경고 시작 → 브레이크), 아직 없으면 null. */
    val reactionMs get() = if (suaAt >= 0 && brakeAt >= suaAt) brakeAt - suaAt else null

    fun reset() {
        gear = Gear.P; gapM = startGapM; speedKmh = 0f; throttle = 0f; brake = false
        phase = Phase.READY; peakSpeed = 0f; minGap = startGapM; suaAt = -1L; brakeAt = -1L
        gapAtSua = startGapM; tapsAtSua = 0; taps.clear(); timeline.clear()
    }
}

/** 저장된 급발진 경험 한 건. */
class SuaEvent(
    val time: Long,
    val outcome: String,          // 정지 / 충돌
    val note: String,             // 운전자가 고른 기억 (페달을 잘못 밟음 등)
    val gapAtSua: Float,
    val minGap: Float,
    val peakSpeed: Float,
    val reactionMs: Long?,
    val taps: Int,
    val timeline: List<FloatArray>,   // [초(경고 기준), 속도, 거리, 브레이크 0/1]
) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("outcome", outcome).put("note", note)
        .put("gapAtSua", gapAtSua.toDouble()).put("minGap", minGap.toDouble()).put("peakSpeed", peakSpeed.toDouble())
        .put("reactionMs", reactionMs ?: JSONObject.NULL).put("taps", taps)
        .put("timeline", JSONArray().apply { timeline.forEach { s -> put(JSONArray().apply { s.forEach { put(it.toDouble()) } }) } })

    companion object {
        fun fromJson(o: JSONObject): SuaEvent {
            val tl = o.getJSONArray("timeline")
            return SuaEvent(
                o.getLong("time"), o.getString("outcome"), o.getString("note"),
                o.getDouble("gapAtSua").toFloat(), o.getDouble("minGap").toFloat(), o.getDouble("peakSpeed").toFloat(),
                if (o.isNull("reactionMs")) null else o.getLong("reactionMs"), o.getInt("taps"),
                List(tl.length()) { i -> val a = tl.getJSONArray(i); FloatArray(a.length()) { j -> a.getDouble(j).toFloat() } },
            )
        }

        /** 시뮬레이터 상태로 기록을 만든다. 타임라인은 경고 3초 전부터. */
        fun of(m: ReverseSimModel, note: String): SuaEvent {
            val t0 = m.suaAt
            val tl = m.timeline.filter { it.t >= t0 - 3_000 }.filterIndexed { i, _ -> i % 2 == 0 }
                .map { floatArrayOf((it.t - t0) / 1000f, it.speed, it.gap, if (it.brake) 1f else 0f) }
            return SuaEvent(System.currentTimeMillis(), if (m.phase == ReverseSimModel.Phase.CRASHED) "충돌" else "정지",
                note, m.gapAtSua, m.minGap, m.peakSpeed, m.reactionMs, m.tapsAtSua, tl)
        }
    }
}

/** 급발진 경험은 차량 안(앱 내부 저장소)에만 쌓인다. 한 줄에 한 건(JSON Lines). */
class SuaStore(ctx: Context) {
    private val file = File(ctx.filesDir, "sua_events.jsonl")
    val all: MutableList<SuaEvent> = if (file.exists()) file.readLines().mapNotNull { runCatching { SuaEvent.fromJson(JSONObject(it)) }.getOrNull() }.toMutableList() else mutableListOf()

    fun add(e: SuaEvent) { all.add(0, e); file.appendText(e.toJson().toString() + "\n") }
    fun clear() { all.clear(); file.delete() }
}

/** 위에서 내려다본 주차장. 위쪽에 서 있는 후방 차량, 아래에서 후진해 올라가는 내 차, 후방 감지 거리. */
class ParkingSceneView(ctx: Context, private val m: ReverseSimModel) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    var flash = false

    private fun car(c: Canvas, r: RectF, body: Int, rearAtTop: Boolean) {
        p.style = Paint.Style.FILL; p.color = body
        c.drawRoundRect(r, r.width() * 0.22f, r.width() * 0.22f, p)
        p.color = 0xFF1B2330.toInt()   // 유리
        val gw = r.width() * 0.72f; val gx = r.centerX() - gw / 2
        val front = if (rearAtTop) r.bottom - r.height() * 0.30f else r.top + r.height() * 0.12f
        val rear = if (rearAtTop) r.top + r.height() * 0.12f else r.bottom - r.height() * 0.30f
        c.drawRoundRect(RectF(gx, front, gx + gw, front + r.height() * 0.18f), 8 * d, 8 * d, p)
        c.drawRoundRect(RectF(gx, rear, gx + gw, rear + r.height() * 0.13f), 8 * d, 8 * d, p)
        // 미등(빨강) 위치: 후방 쪽
        p.color = 0xFFFF4040.toInt()
        val ly = if (rearAtTop) r.top + 2 * d else r.bottom - 8 * d
        c.drawRoundRect(RectF(r.left + 6 * d, ly, r.left + 26 * d, ly + 6 * d), 3 * d, 3 * d, p)
        c.drawRoundRect(RectF(r.right - 26 * d, ly, r.right - 6 * d, ly + 6 * d), 3 * d, 3 * d, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = 0xFF2A2F38.toInt()
        c.drawRoundRect(RectF(0f, 0f, w, h), 20 * d, 20 * d, p)
        // 주차 칸 선
        p.color = 0x66FFFFFF; p.strokeWidth = 3 * d; p.style = Paint.Style.STROKE
        val laneW = w * 0.36f; val lx = (w - laneW) / 2
        c.drawLine(lx, 0f, lx, h, p); c.drawLine(lx + laneW, 0f, lx + laneW, h, p)
        c.drawLine(lx - laneW, h * 0.04f, lx - laneW, h * 0.4f, p)
        c.drawLine(lx + laneW * 2, h * 0.04f, lx + laneW * 2, h * 0.4f, p)

        val carW = laneW * 0.72f; val carH = carW * 2.0f
        val scale = (h * 0.62f) / 5f                    // 5 m = 화면 높이의 62 %
        val otherTop = h * 0.03f
        val other = RectF(w / 2 - carW / 2, otherTop, w / 2 + carW / 2, otherTop + carH * 0.55f)  // 뒤 차량은 앞부분만 보임
        car(c, RectF(other.left, other.top - carH * 0.45f, other.right, other.bottom), 0xFF8C96A6.toInt(), rearAtTop = false)
        val myTop = other.bottom + m.gapM * scale
        val mine = RectF(w / 2 - carW / 2, myTop, w / 2 + carW / 2, myTop + carH)
        car(c, mine, 0xFF4C8DFF.toInt(), rearAtTop = true)

        // 후방 감지 부채꼴
        if (m.gear == ReverseSimModel.Gear.R) {
            val zc = when (m.zone) { ReverseSimModel.Zone.SAFE -> GREEN; ReverseSimModel.Zone.CAUTION -> AMBER; else -> RED }
            p.style = Paint.Style.STROKE; p.strokeWidth = 4 * d; p.color = zc
            for (i in 1..3) {
                val rr = carW * (0.35f + i * 0.18f)
                p.alpha = 255 - i * 60
                c.drawArc(RectF(mine.centerX() - rr, mine.top - rr * 0.6f, mine.centerX() + rr, mine.top + rr * 0.6f), 200f, 140f, false, p)
            }
            p.alpha = 255
            // 거리 화살표 + 글자
            p.strokeWidth = 2 * d; p.pathEffect = DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f); p.color = TEXT
            c.drawLine(w / 2 + carW * 0.75f, other.bottom, w / 2 + carW * 0.75f, mine.top, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL; p.textSize = 30 * d; p.typeface = Typeface.DEFAULT_BOLD; p.color = zc
            c.drawText("%.1f m".format(m.gapM), w / 2 + carW * 0.85f, (other.bottom + mine.top) / 2 + 10 * d, p)
            p.textSize = 16 * d; p.color = MUTED; p.typeface = Typeface.DEFAULT
            c.drawText("후방 차량 감지", w / 2 + carW * 0.85f, (other.bottom + mine.top) / 2 + 34 * d, p)
        }
        if (m.phase == ReverseSimModel.Phase.CRASHED) {
            p.style = Paint.Style.FILL; p.color = RED; p.textSize = 34 * d; p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            c.drawText("충돌!", w / 2, other.bottom + 40 * d, p)
            p.textAlign = Paint.Align.LEFT
        }
        if (flash) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 10 * d; p.color = RED
            c.drawRoundRect(RectF(5 * d, 5 * d, w - 5 * d, h - 5 * d), 20 * d, 20 * d, p)
        }
    }
}

/** 페달 그림 버튼. 가속 페달(세로로 긴 판)과 브레이크 페달(넓은 판). */
class PedalView(ctx: Context, private val accel: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    var down = false; set(v) { field = v; invalidate() }
    var onDown: () -> Unit = {}
    var onUp: () -> Unit = {}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); down = true; onDown() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; onUp() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val inset = if (down) 6 * d else 0f
        val pw = if (accel) min(w * 0.62f, h * 0.42f) else min(w * 0.9f, h * 0.9f)
        val ph = if (accel) h * 0.92f else h * 0.62f
        val left = (w - pw) / 2 + inset / 2; val top = (h - ph) / 2 + inset
        val r = RectF(left, top, left + pw - inset, top + ph - inset)
        // 그림자
        p.style = Paint.Style.FILL; p.color = 0x55000000
        c.drawRoundRect(RectF(r.left + 6 * d, r.top + 8 * d, r.right + 6 * d, r.bottom + 8 * d), 18 * d, 18 * d, p)
        // 페달 판 (위가 살짝 좁은 사다리꼴)
        val narrow = r.width() * 0.08f
        val plate = Path().apply {
            moveTo(r.left + narrow, r.top); lineTo(r.right - narrow, r.top)
            lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close()
        }
        p.color = if (down) (if (accel) 0xFF2F6FE0.toInt() else 0xFFE04A4F.toInt()) else 0xFF3A414D.toInt()
        c.drawPath(plate, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d
        p.color = if (accel) BLUE else RED
        c.drawPath(plate, p)
        // 미끄럼 방지 홈
        p.color = 0x88FFFFFF.toInt(); p.strokeWidth = 4 * d; p.strokeCap = Paint.Cap.ROUND
        val n = if (accel) 7 else 4
        for (i in 1..n) {
            val yy = r.top + r.height() * i / (n + 1)
            c.drawLine(r.left + r.width() * 0.22f, yy, r.right - r.width() * 0.22f, yy, p)
        }
        // 글자
        p.style = Paint.Style.FILL; p.color = TEXT; p.textAlign = Paint.Align.CENTER
        p.textSize = 20 * d; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(if (accel) "가속" else "브레이크", r.centerX(), r.bottom - 14 * d, p)
    }
}

/** 급발진 경험 타임라인: 속도(파랑), 후방 거리(초록), 경고 시작(빨간 세로선), 브레이크 구간(빨간 바닥 띠). */
class SuaTimelineView(ctx: Context, private val e: SuaEvent) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = SURFACE2
        c.drawRoundRect(RectF(0f, 0f, w, h), 10 * d, 10 * d, p)
        val tl = e.timeline
        if (tl.size < 2) return
        val t0 = tl.first()[0]; val t1 = max(tl.last()[0], t0 + 0.1f)
        val maxSpeed = max(10f, tl.maxOf { it[1] })
        fun x(t: Float) = (t - t0) / (t1 - t0) * w
        // 브레이크 구간
        p.color = RED.tint(0x55)
        tl.forEachIndexed { i, s -> if (s[3] > 0.5f && i + 1 < tl.size) c.drawRect(x(s[0]), h - 8 * d, x(tl[i + 1][0]), h, p) }
        // 경고 시작선
        p.color = RED; c.drawRect(x(0f) - d, 0f, x(0f) + d, h, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d
        fun line(idx: Int, maxV: Float, color: Int) {
            val path = Path()
            tl.forEachIndexed { i, s ->
                val yy = h - 10 * d - (s[idx] / maxV) * (h - 20 * d)
                if (i == 0) path.moveTo(x(s[0]), yy) else path.lineTo(x(s[0]), yy)
            }
            p.color = color; c.drawPath(path, p)
        }
        line(2, 5f, GREEN); line(1, maxSpeed, BLUE)
        p.style = Paint.Style.FILL; p.textSize = 13 * d; p.color = BLUE
        c.drawText("속도 (최대 %.0f km/h)".format(tl.maxOf { it[1] }), 10 * d, 18 * d, p)
        p.color = GREEN; c.drawText("후방 거리", 10 * d, 36 * d, p)
        p.color = RED; c.drawText("경고", x(0f) + 6 * d, 18 * d, p)
    }
}
