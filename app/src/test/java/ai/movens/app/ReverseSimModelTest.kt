package ai.movens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseSimModelTest {
    /** now 시각부터 ms 동안 50 ms 간격으로 진행한다. */
    private fun run(m: ReverseSimModel, from: Long, ms: Long): Long {
        var t = from
        while (t < from + ms) { t += 50; m.step(t, 50) }
        return t
    }

    @Test fun parkGear_ignoresAccelerator() {
        val m = ReverseSimModel()
        repeat(10) { assertFalse(m.tapAccel(it * 100L)) }
        run(m, 1_000, 1_000)
        assertEquals(5.0f, m.gapM, 0.001f)
    }

    @Test fun slowTaps_reverseSlowly_noSua() {
        val m = ReverseSimModel(); m.setGear(ReverseSimModel.Gear.R)
        var t = 0L
        repeat(4) { assertFalse(m.tapAccel(t)); t = run(m, t, 1_000) }     // 1초에 한 번
        assertEquals(ReverseSimModel.Phase.READY, m.phase)
        assertTrue(m.gapM < 5.0f)          // 조금 움직였다
        assertTrue(m.peakSpeed < 8f)       // 천천히
    }

    @Test fun rapidTaps_triggerSua_thenBrakeStops() {
        val m = ReverseSimModel(); m.setGear(ReverseSimModel.Gear.R)
        var sua = false
        for (i in 0 until 5) sua = m.tapAccel(i * 200L) || sua            // 1초 안에 5번
        assertTrue(sua)
        assertEquals(ReverseSimModel.Phase.SUA, m.phase)
        var t = run(m, 1_000, 600)                                          // 페달을 떼도 계속 가속
        assertTrue(m.speedKmh > 5f)
        m.setBrake(true, t)
        t = run(m, t, 1_500)
        assertEquals(ReverseSimModel.Phase.STOPPED, m.phase)
        assertEquals(0f, m.speedKmh, 0.001f)
        assertNotNull(m.reactionMs)
        assertTrue(m.gapM > 0.2f)
        val e = SuaEvent.of(m, "테스트")
        assertEquals("정지", e.outcome)
        assertTrue(e.timeline.isNotEmpty())
    }

    @Test fun sua_withoutBrake_crashes() {
        val m = ReverseSimModel(startGapM = 3f); m.setGear(ReverseSimModel.Gear.R)
        for (i in 0 until 5) m.tapAccel(i * 100L)
        run(m, 500, 5_000)
        assertEquals(ReverseSimModel.Phase.CRASHED, m.phase)
        assertEquals("충돌", SuaEvent.of(m, "").outcome)
    }

    @Test fun reset_restoresStart() {
        val m = ReverseSimModel(); m.setGear(ReverseSimModel.Gear.R)
        for (i in 0 until 5) m.tapAccel(i * 100L)
        run(m, 500, 1_000); m.reset()
        assertEquals(ReverseSimModel.Gear.P, m.gear); assertEquals(5.0f, m.gapM, 0.001f)
        assertEquals(ReverseSimModel.Phase.READY, m.phase)
    }
}

