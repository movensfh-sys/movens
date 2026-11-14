package ai.movens.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 경고등 색 = 위험도. 색마다 알림이 떠 있는 시간이 다르다 (빨강 10분 / 노랑 5분 / 초록 2분).
 */
enum class Severity(val label: String, val color: Int, val durationMs: Long, val action: String) {
    RED_LV("빨강 · 위험", RED, 10 * 60_000L, "안전한 곳에 정차한 뒤 확인하세요."),
    YELLOW_LV("노랑 · 주의", AMBER, 5 * 60_000L, "가까운 시일 안에 점검을 받으세요."),
    GREEN_LV("초록 · 안내", GREEN, 2 * 60_000L, "작동 상태를 알려 드리는 표시예요."),
}

/** 경고등 그림 종류. 계기판 기호를 단순화해 직접 그린다. */
enum class Glyph { SOUND, BATTERY, OIL, TEMP, ENGINE, TIRE, BRAKE, SEATBELT, AIRBAG, ABS, ESC, STEERING, FUEL, EV, DOOR, WARNING, LAMP, ECO, HOLD }

/** 경고등 한 개: Pleos TelltaleType 이름, 쉬운 이름, 쉬운 설명, 위험도(색), 그림. */
class TelltaleSpec(val key: String, val name: String, val easy: String, val severity: Severity, val glyph: Glyph)

object TelltaleCatalog {
    val all = listOf(
        TelltaleSpec("LOW_VOLT_BATTERY", "배터리 충전 경고", "배터리가 충전되지 않고 있어요. 이대로 달리면 시동이 꺼질 수 있어요.", Severity.RED_LV, Glyph.BATTERY),
        TelltaleSpec("ENGINE_OIL_PRESSURE", "엔진 오일 압력 경고", "엔진 오일이 부족하거나 압력이 낮아요. 계속 달리면 엔진이 손상될 수 있어요.", Severity.RED_LV, Glyph.OIL),
        TelltaleSpec("ENGINE_TEMPERATURE", "엔진 과열 경고", "엔진 온도가 너무 높아요. 냉각수가 부족할 수 있어요.", Severity.RED_LV, Glyph.TEMP),
        TelltaleSpec("PARKING_BRAKE", "브레이크 경고", "주차 브레이크가 걸려 있거나 브레이크액이 부족해요.", Severity.RED_LV, Glyph.BRAKE),
        TelltaleSpec("SEAT_BELT", "안전벨트 미착용", "안전벨트를 매지 않은 자리가 있어요.", Severity.RED_LV, Glyph.SEATBELT),
        TelltaleSpec("AIRBAG", "에어백 경고", "사고가 나도 에어백이 펴지지 않을 수 있어요.", Severity.RED_LV, Glyph.AIRBAG),
        TelltaleSpec("DOOR_OPEN", "문 열림", "제대로 닫히지 않은 문이 있어요.", Severity.RED_LV, Glyph.DOOR),
        TelltaleSpec("MASTER_WARNING", "주요 경고", "확인이 필요한 경고가 있어요. 계기판 메시지를 확인하세요.", Severity.RED_LV, Glyph.WARNING),
        TelltaleSpec("EV_SYSTEM", "전기차 시스템 경고", "구동 장치에 이상이 있어요.", Severity.RED_LV, Glyph.EV),
        TelltaleSpec("ENGINE", "엔진 점검 경고", "엔진이나 배기 장치에 점검이 필요해요.", Severity.YELLOW_LV, Glyph.ENGINE),
        TelltaleSpec("TPMS", "타이어 공기압 경고", "타이어 바람이 부족해요. 공기압을 확인하세요.", Severity.YELLOW_LV, Glyph.TIRE),
        TelltaleSpec("ABS", "ABS 경고", "급브레이크 때 바퀴 잠김을 막는 장치가 작동하지 않을 수 있어요.", Severity.YELLOW_LV, Glyph.ABS),
        TelltaleSpec("ESC", "차체 자세 제어 경고", "미끄러운 길에서 차를 잡아 주는 장치에 이상이 있어요.", Severity.YELLOW_LV, Glyph.ESC),
        TelltaleSpec("MDPS", "조향 장치 경고", "핸들이 평소보다 무거울 수 있어요.", Severity.YELLOW_LV, Glyph.STEERING),
        TelltaleSpec("ELECTRONIC_PARKING_BRAKE", "전자식 주차 브레이크 경고", "전자식 주차 브레이크 장치에 이상이 있어요.", Severity.YELLOW_LV, Glyph.BRAKE),
        TelltaleSpec("POWER_DOWN", "출력 제한", "차를 보호하려고 힘을 줄여 달리고 있어요.", Severity.YELLOW_LV, Glyph.WARNING),
        TelltaleSpec("LOW_FUEL", "연료 부족", "연료가 얼마 남지 않았어요. 주유소에 들르세요.", Severity.YELLOW_LV, Glyph.FUEL),
        TelltaleSpec("LOW_EV_BATTERY", "구동 배터리 부족", "달릴 수 있는 거리가 얼마 남지 않았어요. 충전하세요.", Severity.YELLOW_LV, Glyph.EV),
        TelltaleSpec("TRUNK_OPEN", "트렁크 열림", "트렁크가 열려 있어요.", Severity.YELLOW_LV, Glyph.DOOR),
        TelltaleSpec("AUTO_HOLD", "오토 홀드 작동", "멈춰 있는 동안 브레이크를 자동으로 잡아 주고 있어요.", Severity.GREEN_LV, Glyph.HOLD),
        TelltaleSpec("DRIVE_MODE_ECO", "에코 모드", "연비를 아끼며 달리는 모드예요.", Severity.GREEN_LV, Glyph.ECO),
        TelltaleSpec("HEADLAMP_LOW", "전조등 켜짐", "전조등이 켜져 있어요.", Severity.GREEN_LV, Glyph.LAMP),
    )

    fun byKey(key: String) = all.find { it.key == key }
}

/** 경고등 기호를 캔버스에 그린다 (정사각형 rect 안에). */
object GlyphPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, g: Glyph, r: RectF, color: Int) {
        val s = r.width()
        fun x(f: Float) = r.left + f * s
        fun y(f: Float) = r.top + f * s
        p.reset(); p.isAntiAlias = true; p.color = color
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.07f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        val text = Paint(fill).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        fun bang(cx: Float, top: Float, h: Float) {   // 느낌표
            c.drawLine(x(cx), y(top), x(cx), y(top + h * 0.62f), p)
            c.drawCircle(x(cx), y(top + h * 0.9f), s * 0.045f, fill)
        }
        fun parens() {   // 양옆 괄호 모양 호
            c.drawArc(RectF(x(0.05f), y(0.18f), x(0.35f), y(0.82f)), 110f, 140f, false, p)
            c.drawArc(RectF(x(0.65f), y(0.18f), x(0.95f), y(0.82f)), -70f, 140f, false, p)
        }
        when (g) {
            Glyph.SOUND -> {   // 소리 파형
                val hs = floatArrayOf(0.18f, 0.36f, 0.56f, 0.30f, 0.66f, 0.40f, 0.22f)
                hs.forEachIndexed { i, h ->
                    val cx = 0.14f + i * 0.12f
                    c.drawLine(x(cx), y(0.5f - h / 2), x(cx), y(0.5f + h / 2), p)
                }
            }
            Glyph.BATTERY -> {
                c.drawRoundRect(RectF(x(0.12f), y(0.32f), x(0.88f), y(0.82f)), s * 0.05f, s * 0.05f, p)
                c.drawRect(RectF(x(0.22f), y(0.22f), x(0.36f), y(0.32f)), fill)
                c.drawRect(RectF(x(0.64f), y(0.22f), x(0.78f), y(0.32f)), fill)
                c.drawLine(x(0.22f), y(0.55f), x(0.38f), y(0.55f), p)
                c.drawLine(x(0.62f), y(0.55f), x(0.78f), y(0.55f), p)
                c.drawLine(x(0.70f), y(0.47f), x(0.70f), y(0.63f), p)
            }
            Glyph.OIL -> {
                val body = Path().apply {
                    moveTo(x(0.12f), y(0.48f)); lineTo(x(0.58f), y(0.48f)); lineTo(x(0.86f), y(0.36f))
                    lineTo(x(0.62f), y(0.74f)); lineTo(x(0.12f), y(0.74f)); close()
                }
                c.drawPath(body, p)
                c.drawLine(x(0.30f), y(0.48f), x(0.30f), y(0.38f), p)
                c.drawLine(x(0.22f), y(0.36f), x(0.40f), y(0.36f), p)
                c.drawCircle(x(0.86f), y(0.66f), s * 0.05f, fill)
            }
            Glyph.TEMP -> {
                c.drawLine(x(0.5f), y(0.12f), x(0.5f), y(0.58f), p)
                c.drawCircle(x(0.5f), y(0.64f), s * 0.08f, fill)
                for (i in 0..2) c.drawLine(x(0.56f), y(0.22f + i * 0.12f), x(0.68f), y(0.22f + i * 0.12f), p)
                for (row in 0..1) {
                    val yy = 0.80f + row * 0.1f
                    val wave = Path().apply {
                        moveTo(x(0.14f), y(yy))
                        cubicTo(x(0.26f), y(yy - 0.06f), x(0.38f), y(yy + 0.06f), x(0.5f), y(yy))
                        cubicTo(x(0.62f), y(yy - 0.06f), x(0.74f), y(yy + 0.06f), x(0.86f), y(yy))
                    }
                    c.drawPath(wave, p)
                }
            }
            Glyph.ENGINE -> {
                val e = Path().apply {
                    moveTo(x(0.10f), y(0.46f)); lineTo(x(0.20f), y(0.46f)); lineTo(x(0.20f), y(0.36f))
                    lineTo(x(0.34f), y(0.36f)); lineTo(x(0.34f), y(0.28f)); lineTo(x(0.62f), y(0.28f))
                    lineTo(x(0.62f), y(0.36f)); lineTo(x(0.74f), y(0.36f)); lineTo(x(0.80f), y(0.46f))
                    lineTo(x(0.90f), y(0.46f)); lineTo(x(0.90f), y(0.70f)); lineTo(x(0.80f), y(0.70f))
                    lineTo(x(0.74f), y(0.80f)); lineTo(x(0.30f), y(0.80f)); lineTo(x(0.20f), y(0.70f))
                    lineTo(x(0.10f), y(0.70f)); close()
                }
                c.drawPath(e, p)
            }
            Glyph.TIRE -> {
                c.drawArc(RectF(x(0.14f), y(0.16f), x(0.86f), y(0.88f)), -25f, 230f, false, p)
                for (i in 0..3) {
                    val fx = 0.30f + i * 0.13f
                    c.drawLine(x(fx), y(0.86f), x(fx), y(0.94f), p)
                }
                bang(0.5f, 0.30f, 0.40f)
            }
            Glyph.BRAKE -> {
                c.drawCircle(x(0.5f), y(0.5f), s * 0.26f, p)
                parens(); bang(0.5f, 0.32f, 0.36f)
            }
            Glyph.ABS -> {
                c.drawCircle(x(0.5f), y(0.5f), s * 0.28f, p)
                parens()
                text.textSize = s * 0.2f
                c.drawText("ABS", x(0.5f), y(0.57f), text)
            }
            Glyph.SEATBELT -> {
                c.drawCircle(x(0.46f), y(0.20f), s * 0.09f, fill)
                val body = Path().apply {
                    moveTo(x(0.30f), y(0.90f)); lineTo(x(0.30f), y(0.42f))
                    quadTo(x(0.30f), y(0.34f), x(0.40f), y(0.34f)); lineTo(x(0.54f), y(0.34f))
                    quadTo(x(0.64f), y(0.34f), x(0.64f), y(0.44f)); lineTo(x(0.64f), y(0.90f))
                }
                c.drawPath(body, p)
                val belt = Paint(p).apply { strokeWidth = s * 0.1f }
                c.drawLine(x(0.30f), y(0.42f), x(0.74f), y(0.80f), belt)
            }
            Glyph.AIRBAG -> {
                c.drawCircle(x(0.30f), y(0.24f), s * 0.08f, fill)
                val body = Path().apply {
                    moveTo(x(0.20f), y(0.88f)); lineTo(x(0.24f), y(0.42f)); lineTo(x(0.42f), y(0.58f)); lineTo(x(0.42f), y(0.88f))
                }
                c.drawPath(body, p)
                c.drawCircle(x(0.70f), y(0.42f), s * 0.18f, p)
            }
            Glyph.ESC -> {
                c.drawRoundRect(RectF(x(0.22f), y(0.16f), x(0.78f), y(0.46f)), s * 0.08f, s * 0.08f, p)
                for (fx in listOf(0.34f, 0.66f)) {
                    val sq = Path().apply {
                        moveTo(x(fx), y(0.54f))
                        cubicTo(x(fx - 0.12f), y(0.64f), x(fx + 0.12f), y(0.76f), x(fx - 0.02f), y(0.90f))
                    }
                    c.drawPath(sq, p)
                }
            }
            Glyph.STEERING -> {
                c.drawCircle(x(0.42f), y(0.5f), s * 0.30f, p)
                c.drawCircle(x(0.42f), y(0.5f), s * 0.07f, fill)
                c.drawLine(x(0.14f), y(0.46f), x(0.70f), y(0.46f), p)
                c.drawLine(x(0.42f), y(0.52f), x(0.42f), y(0.80f), p)
                bang(0.88f, 0.30f, 0.40f)
            }
            Glyph.FUEL -> {
                c.drawRoundRect(RectF(x(0.18f), y(0.16f), x(0.56f), y(0.86f)), s * 0.04f, s * 0.04f, p)
                c.drawRect(RectF(x(0.25f), y(0.24f), x(0.49f), y(0.42f)), fill)
                val hose = Path().apply {
                    moveTo(x(0.56f), y(0.36f)); lineTo(x(0.74f), y(0.36f)); lineTo(x(0.80f), y(0.46f))
                    lineTo(x(0.80f), y(0.72f)); quadTo(x(0.80f), y(0.80f), x(0.72f), y(0.80f)); lineTo(x(0.66f), y(0.80f)); lineTo(x(0.66f), y(0.58f))
                }
                c.drawPath(hose, p)
            }
            Glyph.EV -> {
                c.drawRoundRect(RectF(x(0.14f), y(0.30f), x(0.82f), y(0.78f)), s * 0.05f, s * 0.05f, p)
                c.drawRect(RectF(x(0.82f), y(0.44f), x(0.90f), y(0.64f)), fill)
                val bolt = Path().apply {
                    moveTo(x(0.52f), y(0.34f)); lineTo(x(0.34f), y(0.56f)); lineTo(x(0.48f), y(0.56f))
                    lineTo(x(0.42f), y(0.74f)); lineTo(x(0.62f), y(0.50f)); lineTo(x(0.48f), y(0.50f)); close()
                }
                c.drawPath(bolt, fill)
            }
            Glyph.DOOR -> {
                c.drawRoundRect(RectF(x(0.38f), y(0.08f), x(0.66f), y(0.92f)), s * 0.08f, s * 0.08f, p)
                c.drawLine(x(0.38f), y(0.36f), x(0.16f), y(0.52f), p)
                c.drawLine(x(0.66f), y(0.36f), x(0.86f), y(0.52f), p)
            }
            Glyph.WARNING -> {
                val tri = Path().apply {
                    moveTo(x(0.5f), y(0.12f)); lineTo(x(0.90f), y(0.84f)); lineTo(x(0.10f), y(0.84f)); close()
                }
                c.drawPath(tri, p)
                bang(0.5f, 0.38f, 0.38f)
            }
            Glyph.LAMP -> {
                val d = Path().apply {
                    moveTo(x(0.52f), y(0.24f)); quadTo(x(0.90f), y(0.24f), x(0.90f), y(0.50f))
                    quadTo(x(0.90f), y(0.76f), x(0.52f), y(0.76f)); close()
                }
                c.drawPath(d, p)
                for (i in 0..3) {
                    val yy = 0.30f + i * 0.13f
                    c.drawLine(x(0.10f), y(yy + 0.04f), x(0.40f), y(yy), p)
                }
            }
            Glyph.ECO -> {
                text.textSize = s * 0.34f
                c.drawText("ECO", x(0.5f), y(0.62f), text)
            }
            Glyph.HOLD -> {
                c.drawCircle(x(0.5f), y(0.5f), s * 0.30f, p)
                text.textSize = s * 0.30f
                c.drawText("A", x(0.5f), y(0.61f), text)
                parens()
            }
        }
    }

    fun bitmap(g: Glyph, sizePx: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), g, RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), color)
        return b
    }
}

/** 경고등 그림 뷰: 색 원 배경 + 기호. */
class GlyphView(ctx: Context, var glyph: Glyph, var color: Int, private val filled: Boolean = true) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val left = (width - s) / 2; val top = (height - s) / 2
        bg.color = if (filled) color.tint(0x33) else 0
        c.drawCircle(left + s / 2, top + s / 2, s / 2, bg)
        val pad = s * 0.16f
        GlyphPainter.draw(c, glyph, RectF(left + pad, top + pad, left + s - pad, top + s - pad), color)
    }
}

/**
 * 경고등 알림 센터. 경고등이 켜지면
 *  1) 휴대폰 알림처럼 화면 오른쪽 위에 배너를 띄우고 (색별 유지 시간 동안, 남은 시간 표시)
 *  2) 안드로이드 시스템 알림도 같은 시간 동안 올리고 (setTimeoutAfter)
 *  3) 알림음 + 음성으로 쉬운 설명을 읽어 준다.
 */
class TelltaleCenter(private val ctx: Context, private val speaker: Speaker, private val onShown: (TelltaleSpec) -> Unit) {

    class Active(val spec: TelltaleSpec, val start: Long, val source: String) {
        val end get() = start + spec.severity.durationMs
        fun remainMs(now: Long) = (end - now).coerceAtLeast(0)
    }

    val active = ArrayList<Active>()     // 최근 것이 앞
    /** 화면 오른쪽 위에 올릴 배너 묶음. MainActivity가 맨 위 레이어에 붙인다. */
    val bannerStack = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** 경고등 탭의 "켜져 있는 경고등" 목록. */
    val activeList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    private val timers = HashMap<String, Pair<TextView, View>>()   // key → (남은 시간 글자, 진행 막대)
    private val listTimers = HashMap<String, TextView>()

    init {
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "경고등 안내", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "계기판 경고등이 켜지면 뜻과 위험도를 알려 줍니다"
        })
        rebuild()
    }

    /**
     * 알림을 띄운다. source는 배너 머리줄에 보이는 출처("경고등 안내", "엔진 소리 안내").
     * 경고등이 아닌 알림(엔진 소리 등)은 log=false, 음성은 호출한 쪽이 직접 낼 때 sound=false.
     */
    fun show(spec: TelltaleSpec, source: String = "경고등 안내", log: Boolean = true, sound: Boolean = true) {
        val now = System.currentTimeMillis()
        active.removeAll { it.spec.key == spec.key }
        active.add(0, Active(spec, now, source))
        postSystemNotification(spec, source)
        if (sound) {
            when (spec.severity) {
                Severity.RED_LV -> tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900)
                Severity.YELLOW_LV -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                Severity.GREEN_LV -> tone?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
            }
            speaker.say("${spec.name}입니다. ${spec.easy} ${spec.severity.action}")
        }
        if (log) onShown(spec)
        rebuild()
    }

    /** 여러 경고등이 한꺼번에 켜졌을 때: 가장 위험한 색의 알림음 한 번 + 음성 한 번으로 묶어 안내. */
    fun announce(specs: List<TelltaleSpec>) {
        if (specs.isEmpty()) return
        val worst = specs.minByOrNull { it.severity.ordinal }!!
        when (worst.severity) {
            Severity.RED_LV -> tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900)
            Severity.YELLOW_LV -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            Severity.GREEN_LV -> tone?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
        }
        speaker.say("경고등 ${specs.size}개가 켜졌습니다. " + specs.joinToString(", ") { it.name } + ". 가장 급한 것은 ${worst.name}입니다. ${worst.severity.action}")
    }

    fun dismiss(key: String) {
        active.removeAll { it.spec.key == key }
        nm?.cancel(notifId(key))
        rebuild()
    }

    /** 0.5초마다 호출: 남은 시간 갱신, 시간이 다 된 알림 제거. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (active.removeAll { it.remainMs(now) == 0L }) { rebuild(); return }
        for (a in active) {
            val left = a.remainMs(now)
            timers[a.spec.key]?.let { (txt, bar) ->
                txt.text = "${a.spec.severity.label}  ·  ${fmt(left)} 남음"
                val frac = left.toFloat() / a.spec.severity.durationMs
                (bar.layoutParams as LinearLayout.LayoutParams).weight = frac
                bar.requestLayout()
            }
            listTimers[a.spec.key]?.text = "${fmt(left)} 남음"
        }
    }

    private fun agoText(ms: Long) = if (ms < 60_000) "방금" else "${ms / 60_000}분 전"

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun rebuild() {
        val now = System.currentTimeMillis()
        bannerStack.removeAllViews(); timers.clear()
        for (a in active.take(3)) bannerStack.addView(banner(a, now), lp(ctx.dp(560), WRAP).apply { bottomMargin = ctx.dp(12) })
        activeList.removeAllViews(); listTimers.clear()
        if (active.isEmpty()) activeList.addView(ctx.label("켜져 있는 경고등이 없습니다.", 17f, MUTED))
        for (a in active) activeList.addView(activeRow(a, now), lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) })
        tick()
    }

    /** 휴대폰 알림처럼 생긴 배너. */
    private fun banner(a: Active, now: Long): View = with(ctx) {
        val sev = a.spec.severity
        val icon = GlyphView(this, a.spec.glyph, sev.color)
        val close = label("✕", 20f, MUTED, true).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4)); setOnClickListener { dismiss(a.spec.key) }
        }
        val head = hbox(
            label("movens · ${a.source} · ${agoText(now - a.start)}", 14f, MUTED).apply { layoutParams = lp(0, WRAP, 1f) },
            close,
        )
        val timeTxt = label("", 14f, sev.color, true)
        val barFill = View(this).apply { background = rounded(sev.color, 999) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(SURFACE2, 999)
            addView(barFill, lp(0, MATCH, 1f))
        }
        timers[a.spec.key] = timeTxt to barFill
        bar.weightSum = 1f   // 채움 막대의 weight(=남은 비율)만큼만 칠해진다
        val body = vbox(
            head,
            label(a.spec.name, 21f, TEXT, true).apply { setPadding(0, dp(2), 0, dp(2)) },
            label(a.spec.easy, 16f, TEXT_SUB),
            label(sev.action, 16f, sev.color, true).apply { setPadding(0, dp(4), 0, dp(8)) },
            timeTxt,
        ).apply { addView(bar, lp(MATCH, dp(6)).apply { topMargin = dp(6) }) }
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            background = rounded(0xF2202631.toInt(), 22, sev.color, 2)
            setPadding(dp(18), dp(16), dp(18), dp(16)); elevation = dpf(12f)
            addView(icon, lp(dp(64), dp(64)).apply { marginEnd = dp(16) })
            addView(body, lp(0, WRAP, 1f))
            setOnClickListener { dismiss(a.spec.key) }
        }
    }

    private fun activeRow(a: Active, now: Long): View = with(ctx) {
        val sev = a.spec.severity
        val left = label("", 16f, sev.color, true)
        listTimers[a.spec.key] = left
        hbox(
            GlyphView(this, a.spec.glyph, sev.color).apply { layoutParams = lp(dp(52), dp(52)).apply { marginEnd = dp(16) } },
            vbox(label(a.spec.name, 19f, TEXT, true), label(a.spec.easy, 15f, MUTED)).apply { layoutParams = lp(0, WRAP, 1f) },
            vbox(pill(sev.label, sev.color, sev.color.tint(), 14f), left.apply { gravity = Gravity.END; setPadding(0, dp(6), 0, 0) }),
        ).apply { background = rounded(SURFACE2, 16); setPadding(dp(16), dp(12), dp(16), dp(12)) }
    }

    private fun postSystemNotification(spec: TelltaleSpec, source: String) {
        val m = nm ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val bmp = GlyphPainter.bitmap(spec.glyph, 96, spec.severity.color)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(Icon.createWithBitmap(GlyphPainter.bitmap(spec.glyph, 96, 0xFFFFFFFF.toInt())))
                .setLargeIcon(bmp)
                .setContentTitle("${spec.name} (${spec.severity.label})")
                .setSubText(source)
                .setContentText(spec.easy)
                .setStyle(Notification.BigTextStyle().bigText("${spec.easy}\n${spec.severity.action}"))
                .setColor(spec.severity.color)
                .setTimeoutAfter(spec.severity.durationMs)     // 빨강 10분 / 노랑 5분 / 초록 2분 뒤 자동으로 사라짐
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            m.notify(notifId(spec.key), n)
        } catch (e: Exception) { android.util.Log.w("movens", "알림 실패: ${e.message}") }
    }

    private fun notifId(key: String) = 1000 + key.hashCode().and(0xFFFF)

    fun release() { tone?.release() }

    companion object { private const val CHANNEL = "telltale" }
}
