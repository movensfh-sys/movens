package ai.movens.app

/**
 * 저속 구간 페달 오조작 규칙. 순수 Kotlin이라 단위 테스트로 검증한다 (PedalRuleTest).
 *
 * - R/D 기어, 출발 속도가 lowSpeedKmh 이하일 때
 * - windowMs 안에 jumpKmh 이상 속도가 오르는데 브레이크 신호가 없으면 → BRAKE ("브레이크를 밟으세요")
 * - 브레이크를 계속 밟고 있는데도 속도가 오르면 → STOP ("정차하세요", 참고 기록용)
 * - P/N 기어, 크루즈 중에는 판단하지 않고, 경고 후 cooldownMs 동안은 다시 울리지 않는다.
 *
 * ponytail: 임계값은 기획서의 초기값이다. 시뮬레이션 데이터로 보정한다.
 */
class PedalRule(
    private val lowSpeedKmh: Float = 15f,
    private val jumpKmh: Float = 8f,
    private val windowMs: Long = 2_000,
    private val cooldownMs: Long = 10_000,
) {
    enum class Alert(val speech: String) {
        BRAKE("브레이크를 밟으세요"),
        STOP("정차하세요"),
    }

    private class Sample(val t: Long, val speed: Float, val brake: Boolean)

    private val window = ArrayDeque<Sample>()
    private var lastAlertAt = Long.MIN_VALUE / 2

    /** 신호가 바뀔 때마다 호출한다. 위험한 패턴이면 Alert를 돌려준다. */
    fun update(t: Long, gearRD: Boolean, speedKmh: Float, brakePressed: Boolean, cruiseActive: Boolean): Alert? {
        if (!gearRD || cruiseActive) { window.clear(); return null }
        window.addLast(Sample(t, speedKmh, brakePressed))
        while (window.first().t < t - windowMs) window.removeFirst()
        if (t - lastAlertAt < cooldownMs) return null

        val start = window.first()
        if (start.speed > lowSpeedKmh) return null
        val rise = speedKmh - start.speed
        val alert = when {
            rise >= jumpKmh && window.none { it.brake } -> Alert.BRAKE
            rise >= jumpKmh * 0.6f && window.all { it.brake } -> Alert.STOP
            else -> null
        }
        if (alert != null) { lastAlertAt = t; window.clear() }
        return alert
    }
}
