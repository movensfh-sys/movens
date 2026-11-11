package ai.movens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PedalRuleTest {
    /** 0.5초 간격으로 속도를 넣고 마지막 결과를 돌려준다. */
    private fun run(rule: PedalRule, speeds: List<Float>, brake: Boolean, gearRD: Boolean = true, cruise: Boolean = false): PedalRule.Alert? =
        speeds.withIndex().map { (i, s) -> rule.update(i * 500L, gearRD, s, brake, cruise) }.last()

    @Test fun reverseWithoutBrake_isBrakeAlert() =
        assertEquals(PedalRule.Alert.BRAKE, run(PedalRule(), listOf(2f, 5f, 9f, 12f), brake = false))

    @Test fun acceleratingWhileBraking_isStopAlert() =
        assertEquals(PedalRule.Alert.STOP, run(PedalRule(), listOf(2f, 4f, 6f, 8f), brake = true))

    @Test fun cruiseIsIgnored() = assertNull(run(PedalRule(), listOf(2f, 5f, 9f, 12f), brake = false, cruise = true))

    @Test fun parkGearIsIgnored() = assertNull(run(PedalRule(), listOf(2f, 5f, 9f, 12f), brake = false, gearRD = false))

    @Test fun alreadyFast_isNotLowSpeedCase() = assertNull(run(PedalRule(), listOf(40f, 44f, 48f, 52f), brake = false))

    @Test fun slowCreep_isNormal() = assertNull(run(PedalRule(), listOf(2f, 3f, 4f, 5f), brake = false))

    @Test fun cooldownSuppressesRepeat() {
        val rule = PedalRule()
        assertEquals(PedalRule.Alert.BRAKE, run(rule, listOf(2f, 5f, 9f, 12f), brake = false))
        // 3초 뒤 같은 패턴: 쿨다운(10초) 안이라 조용해야 한다
        val again = listOf(2f, 5f, 9f, 12f).withIndex().map { (i, s) -> rule.update(3_000L + i * 500L, true, s, false, false) }.last()
        assertNull(again)
    }
}
