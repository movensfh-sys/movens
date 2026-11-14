package ai.movens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DriveScenarioTest {
    /** 합성 엔진 소리를 1초씩 만들어 실제 탐지기에 넣는다. 이상 판정이 난 초(second) 목록을 돌려준다. */
    private fun drive(seconds: Int, knockFrom: Int = -1, knockFor: Int = 8): List<Int> {
        val synth = EngineSynth(seed = 3)
        val det = AnomalyDetector()
        val sc = DriveScenario(rnd = Random(1))
        sc.start(0)
        val buf = ShortArray(16_000)
        val hits = ArrayList<Int>()
        for (s in 0 until seconds) {
            synth.speedKmh = sc.profile(s * 1000L).first                  // 가속·정속·감속 반복
            synth.knocking = knockFrom >= 0 && s >= knockFrom && s < knockFrom + knockFor
            synth.fill(buf)
            if (det.feed(SoundFrame.of(buf, buf.size)) != null) hits += s
        }
        return hits
    }

    @Test fun normalDriving_noFalseAlarms() = assertEquals(emptyList<Int>(), drive(150))

    @Test fun knockingAfterLearning_isDetected() {
        val hits = drive(60, knockFrom = 30)
        println("knock hits=$hits"); assertTrue(hits.isNotEmpty() && hits.first() in 30..38)
    }

    @Test fun threeKnockEvents_giveThreeDetections() {
        val synth = EngineSynth(seed = 5); val det = AnomalyDetector(); val sc = DriveScenario(rnd = Random(2)); sc.start(0)
        val buf = ShortArray(16_000); var hits = 0
        for (s in 0 until 140) {
            synth.speedKmh = sc.profile(s * 1000L).first
            synth.knocking = s in 30..37 || s in 65..72 || s in 100..107
            synth.fill(buf)
            if (det.feed(SoundFrame.of(buf, buf.size)) != null) hits++
        }
        assertEquals(3, hits)
    }

    @Test fun scenario_waitsForLearning_thenProducesEvents() {
        val sc = DriveScenario(rnd = Random(9)); sc.start(0)
        var t = 0L; val events = ArrayList<Pair<Long, DriveScenario.Event>>()
        while (t < 180_000) { t += 500; sc.tick(t)?.let { events += t to it } }
        assertTrue(events.isNotEmpty())
        assertTrue(events.first().first >= sc.learnMs)
        assertNull(DriveScenario().tick(0))      // 시작 전에는 아무 일도 없음
        val e = events.first { it.second == DriveScenario.Event.ENGINE_SOUND }
        assertTrue(sc.knocking(e.first) || e.first + sc.knockMs < t)
        assertNotNull(e)
    }
}

class DriveScenarioRainTest {
    @Test fun noEngineSoundWhileRaining_andAllEventKindsAppear() {
        val sc = DriveScenario(rnd = Random(4)); sc.start(0)
        var t = 0L; val kinds = HashSet<DriveScenario.Event>()
        while (t < 1_200_000) {
            t += 500
            val wasRaining = sc.raining(t)
            val e = sc.tick(t) ?: continue
            kinds += e
            if (wasRaining) assertEquals(DriveScenario.Event.TELLTALE, e)
        }
        assertEquals(3, kinds.size)
    }
}

/** 앱의 재확인 규칙(의심 뒤 5초 안에 충격음·경고선 초과가 2번 이상이면 확정)을 합성 소리로 검증. */
class ConfirmRuleTest {
    private fun run(knockSec: Int): Pair<Boolean, Int> {   // (의심 났는지, 재확인 중 다시 들린 횟수)
        val synth = EngineSynth(seed = 11); val det = AnomalyDetector(); val buf = ShortArray(16_000)
        var suspectAt = -1; var hits = 0
        for (s in 0 until 45) {
            synth.knocking = s in 25 until 25 + knockSec
            synth.fill(buf)
            val f = SoundFrame.of(buf, buf.size)
            if (suspectAt >= 0 && s <= suspectAt + 5 && (f.crest >= 8 || (!det.learning && f.dbfs > det.threshold))) hits++
            if (det.feed(f) != null && suspectAt < 0) suspectAt = s
        }
        return (suspectAt >= 0) to hits
    }

    @Test fun longKnock_isConfirmed() { val (s, h) = run(8); println("long: suspect=$s hits=$h"); assertTrue(s && h >= 2) }
    @Test fun shortKnock_isRejected() { val (s, h) = run(2); println("short: suspect=$s hits=$h"); assertTrue(s && h < 2) }
}
