package ai.movens.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 시연용 합성 엔진 소리 (16 kHz mono). 평소에는 낮게 웅웅거리는 소리,
 * knocking = true 이면 불규칙한 '딱딱' 충격음(노킹 같은 이상 소리)이 섞인다.
 * 마이크 대신 이 소리를 같은 분석 경로(SoundMonitor → AnomalyDetector)에 넣는다.
 */
class EngineSynth(private val rate: Int = 16_000, seed: Int = 7) {
    @Volatile var speedKmh = 0f
    @Volatile var knocking = false
    private val rnd = Random(seed)
    private var t = 0.0
    private var phase = 0.0
    private var nextKnock = 0.0
    private var knockStart = -1.0

    fun fill(buf: ShortArray) {
        val dt = 1.0 / rate
        val sp = speedKmh.coerceIn(0f, 120f)
        val f = 50.0 + sp * 0.05                                    // 음 높이는 거의 그대로 (평소 음색이 흔들리지 않게)
        val amp = 0.12 * (1 + 0.25 * min(sp, 80f) / 80f)            // 빨리 달리면 조금 커짐 (+2 dB 이내)
        for (i in buf.indices) {
            t += dt
            phase += 2 * PI * f * dt
            var v = amp * (0.25 * sin(2 * phase) + 0.30 * sin(3 * phase + 1) + 0.25 * sin(4 * phase + 2) +
                0.15 * sin(6 * phase + 0.5) + 0.08 * sin(8 * phase)) + 0.01 * (rnd.nextDouble() * 2 - 1)
            if (knocking && t >= nextKnock) { knockStart = t; nextKnock = t + 0.28 + rnd.nextDouble() * 0.17 }
            if (knockStart >= 0) {
                val d = t - knockStart
                if (d < 0.08) {
                    val env = exp(-d * 60)
                    v += 0.85 * env * (0.6 * sin(2 * PI * 2300 * d) + 0.4 * sin(2 * PI * 3700 * d) + 0.3 * (rnd.nextDouble() * 2 - 1))
                }
            }
            buf[i] = (v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
        }
    }
}

/**
 * 주행 시뮬레이션 진행표. 순수 Kotlin (DriveScenarioTest).
 * - 시작 후 learnMs 동안은 평소 엔진 소리만 낸다 (기준선 학습).
 * - 그다음부터 minGapMs~maxGapMs 간격으로 무작위 사건: 엔진 이상 소리(soundChance), 비(rainChance, 와이퍼 → 소리 판단 잠시 중지), 경고등.
 * - 차량 신호(Virtual Console) 속도가 없으면 스스로 가속·정속·감속을 반복하는 속도를 만든다.
 */
class DriveScenario(
    private val rnd: Random = Random.Default,
    val learnMs: Long = 20_000,
    private val minGapMs: Long = 12_000,
    private val maxGapMs: Long = 22_000,
    private val soundChance: Double = 0.45,
    private val rainChance: Double = 0.15,
    val knockMs: Long = 8_000,
    val rainMs: Long = 20_000,
) {
    enum class Event { ENGINE_SOUND, TELLTALE, RAIN }

    var running = false; private set
    var startedAt = 0L; private set
    var nextEventAt = 0L; private set
    var knockUntil = 0L; private set
    var rainUntil = 0L; private set

    fun start(now: Long) {
        running = true; startedAt = now; knockUntil = 0L
        nextEventAt = now + learnMs + 5_000 + rnd.nextLong(0, 6_000)
    }

    fun stop() { running = false; knockUntil = 0L; rainUntil = 0L }

    fun learning(now: Long) = running && now - startedAt < learnMs
    fun knocking(now: Long) = running && now < knockUntil
    fun raining(now: Long) = running && now < rainUntil

    /** 0.5초마다 호출. 사건이 일어날 때가 되면 돌려준다. */
    fun tick(now: Long): Event? {
        if (!running || now < nextEventAt) return null
        nextEventAt = now + rnd.nextLong(minGapMs, maxGapMs)
        val r = rnd.nextDouble()
        var e = when { r < soundChance -> Event.ENGINE_SOUND; r < soundChance + rainChance -> Event.RAIN; else -> Event.TELLTALE }
        if (raining(now) && e != Event.TELLTALE) e = Event.TELLTALE      // 비 오는 동안은 소리 판단이 멈춰 있으므로 소리 사건을 내지 않음
        when (e) {
            Event.ENGINE_SOUND -> knockUntil = now + knockMs
            Event.RAIN -> rainUntil = now + rainMs
            Event.TELLTALE -> {}
        }
        return e
    }

    /** 바로 이상 소리를 낸다 (시연 버튼). */
    fun forceKnock(now: Long, ms: Long = knockMs) { rainUntil = 0L; knockUntil = now + ms; nextEventAt = maxOf(nextEventAt, now + minGapMs) }

    /** 지금 주행 단계의 페달 (가속 %, 브레이크 %). Virtual Console에 함께 보낸다. */
    fun pedals(now: Long): Pair<Int, Int> {
        val s = ((now - startedAt) % 50_000) / 1000f
        return when { s < 8f -> 40 to 0; s < 25f -> 18 to 0; s < 31f -> 0 to 35; else -> 12 to 0 }
    }

    /** 스스로 만드는 속도 (km/h)와 감속 중인지. 50초 주기: 가속 → 정속 → 감속 → 정속 → 가속… */
    fun profile(now: Long): Pair<Float, Boolean> {
        val s = ((now - startedAt) % 50_000) / 1000f
        return when {
            s < 8f -> 20f + s * 5f to false             // 20 → 60 가속
            s < 25f -> 60f to false                      // 정속
            s < 31f -> 60f - (s - 25f) * 6.7f to true    // 60 → 20 감속 (브레이크)
            else -> 20f to false                         // 저속 정속
        }
    }
}
