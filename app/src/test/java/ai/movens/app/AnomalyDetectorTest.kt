package ai.movens.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyDetectorTest {
    private fun quiet() = SoundFrame(dbfs = -40.0 + (Math.random() - 0.5), zcr = 0.10, crest = 3.0)
    private fun loud() = SoundFrame(dbfs = -25.0, zcr = 0.10, crest = 3.0)

    @Test fun learnsThenFlagsSustainedLoudness() {
        val d = AnomalyDetector(learnFrames = 20)
        repeat(20) { assertNull(d.feed(quiet())) }
        assertTrue(!d.learning)
        assertNull(d.feed(loud()))            // 1번은 아직 아님
        assertNotNull(d.feed(loud()))         // 2번 이어지면 이상
        assertNull(d.feed(loud()))            // 쿨다운
    }

    @Test fun impulsesThreeTimesInTenSeconds() {
        val d = AnomalyDetector(learnFrames = 5)
        repeat(5) { d.feed(quiet()) }
        val bang = SoundFrame(dbfs = -38.0, zcr = 0.10, crest = 12.0)
        assertNull(d.feed(bang)); assertNull(d.feed(quiet())); assertNull(d.feed(bang))
        assertNotNull(d.feed(bang))
    }

    @Test fun quietStaysQuiet() {
        val d = AnomalyDetector(learnFrames = 5)
        repeat(40) { assertNull(d.feed(quiet())) }
    }
}
