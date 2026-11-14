package ai.movens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

// ---- 화면 색 (어두운 차량용 테마, 고대비) ----
val BG = 0xFF0E1116.toInt()
val SURFACE = 0xFF181C23.toInt()
val SURFACE2 = 0xFF242A35.toInt()
val TEXT = 0xFFF2F4F7.toInt()
val TEXT_SUB = 0xFFC9D1DC.toInt()
val MUTED = 0xFF8B95A5.toInt()
val GREEN = 0xFF3DDC97.toInt()
val BLUE = 0xFF4C8DFF.toInt()
val AMBER = 0xFFFFB547.toInt()
val RED = 0xFFFF5A5F.toInt()
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 색을 반투명하게 (배지·칩 배경용). */
fun Int.tint(alpha: Int = 0x33) = (this and 0x00FFFFFF) or (alpha shl 24)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 2) = GradientDrawable().apply {
    setColor(color); cornerRadius = dpf(radiusDp.toFloat()); stroke?.let { setStroke(dp(strokeDp), it) }
}

fun Context.label(text: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.pill(text: String, color: Int, bg: Int = SURFACE2, size: Float = 16f) = label(text, size, color, true).apply {
    background = rounded(bg, 999); setPadding(dp(16), dp(8), dp(16), dp(8)); gravity = Gravity.CENTER
}

fun Context.hspace(dpW: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(dpW), 1) }
fun Context.vspace(dpH: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(dpH)) }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.hbox(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    views.forEach { addView(it) }
}

fun Context.vbox(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    views.forEach { addView(it) }
}

/** 둥근 카드 상자. */
fun Context.card(padH: Int = 24, padV: Int = 20, bg: Int = SURFACE) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rounded(bg, 20)
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

/** 카드 머리줄: 제목 + 오른쪽 보조 뷰. */
fun Context.cardHeader(title: String, right: View? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    addView(label(title, 20f, TEXT, true), lp(0, WRAP, 1f))
    right?.let { addView(it) }
}

/** 최근 60초 음량 그래프. 막대=1초 음량(초록, 경고선 넘으면 주황, 이상 판정 프레임은 빨강), 흰 선=평소 기준선, 빨간 선=경고선. */
class MonitorGraph(ctx: Context) : View(ctx) {
    private var data: List<Pair<Double, Boolean>> = emptyList()
    private var base: Double? = null
    private var limit: Double? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    fun update(data: List<Pair<Double, Boolean>>, base: Double?, limit: Double?) {
        this.data = data; this.base = base; this.limit = limit; invalidate()
    }

    private fun y(v: Double) = (height * (1 - (v.coerceIn(-90.0, 0.0) + 90.0) / 90.0)).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = SURFACE2
        c.drawRoundRect(0f, 0f, w, h, 10 * d, 10 * d, paint)
        val slot = w / 60f; val gap = slot * 0.25f
        val start = 60 - data.size
        data.forEachIndexed { i, (v, anomaly) ->
            paint.color = when { anomaly -> RED; limit?.let { v > it } == true -> AMBER; else -> GREEN }
            val x = (start + i) * slot
            c.drawRect(x + gap / 2, y(v), x + slot - gap / 2, h, paint)
        }
        base?.let { paint.color = TEXT; val by = y(it); c.drawRect(0f, by - d, w, by + d, paint) }
        limit?.let { paint.color = RED; val ly = y(it); c.drawRect(0f, ly - d, w, ly + d, paint) }
    }
}

/** 저장된 소리 클립의 작은 파형. */
class ClipWave(ctx: Context, private val peaks: FloatArray, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        if (peaks.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2
        val slot = w / peaks.size
        paint.color = color
        peaks.forEachIndexed { i, p ->
            val half = max(1f, p * mid)
            c.drawRect(i * slot, mid - half, i * slot + slot * 0.7f, mid + half, paint)
        }
    }
}
