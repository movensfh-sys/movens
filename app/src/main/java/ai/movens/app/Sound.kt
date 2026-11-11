package ai.movens.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 1초 프레임의 소리 특징. dBFS=상대 음량, zcr=영교차율(음색 대용), crest=피크/RMS(충격음 지표). */
class SoundFrame(val dbfs: Double, val zcr: Double, val crest: Double) {
    companion object {
        fun of(buf: ShortArray, n: Int): SoundFrame {
            var sum = 0.0; var peak = 0; var zc = 0
            for (i in 0 until n) {
                val v = buf[i].toInt()
                sum += v.toDouble() * v; peak = max(peak, abs(v))
                if (i > 0 && (v >= 0) != (buf[i - 1] >= 0)) zc++
            }
            val rms = sqrt(sum / n)
            return SoundFrame(
                dbfs = if (rms < 1) -100.0 else 20 * log10(rms / Short.MAX_VALUE),
                zcr = zc.toDouble() / n,
                crest = if (rms < 1) 0.0 else peak / rms,
            )
        }
    }
}

/**
 * 평소 소리를 기준선으로 학습하고, 기준선에서 벗어난 소리가 이어지면 이상 후보로 올린다. 순수 Kotlin (AnomalyDetectorTest).
 *
 * - learnFrames 동안은 학습만 한다 (기획서: 누적 2시간. 시연용 기본값은 20초).
 * - 음량이 평균+k·표준편차(최소 +6 dB)를 넘는 프레임이 2번 이어지거나,
 *   충격음(crest ≥ 8)이 10초 안에 3번 나오면 이상.
 * - 이상 후 cooldownFrames 동안은 다시 올리지 않는다.
 * ponytail: 대역별 에너지 대신 영교차율을 쓴다. 오탐이 많으면 FFT 대역 에너지로 올린다.
 */
class AnomalyDetector(private val learnFrames: Int = 20, private val k: Double = 2.5, private val cooldownFrames: Int = 10) {
    class Baseline {
        var n = 0; private var mean = 0.0; private var m2 = 0.0
        fun add(x: Double) { n++; val d = x - mean; mean += d / n; m2 += d * (x - mean) }
        val avg get() = mean
        val std get() = if (n < 2) 0.0 else sqrt(m2 / (n - 1))
    }

    val level = Baseline()
    val tone = Baseline()
    private var loudStreak = 0
    private var impulses = ArrayDeque<Int>()
    private var frameNo = 0
    private var lastAnomalyFrame = -1_000

    val learning get() = level.n < learnFrames

    /** 1초마다 호출. 이상이면 "평소 대비 +x dB" 같은 설명을, 아니면 null을 돌려준다. */
    fun feed(f: SoundFrame): String? {
        frameNo++
        if (learning) { level.add(f.dbfs); tone.add(f.zcr); return null }
        val loud = f.dbfs > level.avg + max(k * level.std, 6.0)
        val odd = tone.std > 0 && abs(f.zcr - tone.avg) > k * tone.std
        loudStreak = if (loud || odd) loudStreak + 1 else 0
        if (f.crest >= 8) impulses.addLast(frameNo)
        while (impulses.isNotEmpty() && impulses.first() < frameNo - 10) impulses.removeFirst()

        val reason = when {
            loudStreak >= 2 -> "평소 대비 %+.0f dB".format(f.dbfs - level.avg)
            impulses.size >= 3 -> "충격음 ${impulses.size}회"
            else -> { level.add(f.dbfs); tone.add(f.zcr); null }  // 정상 프레임은 기준선에 계속 반영
        }
        if (reason == null || frameNo - lastAnomalyFrame < cooldownFrames) return null
        lastAnomalyFrame = frameNo; loudStreak = 0; impulses.clear()
        return reason
    }
}

/** 차량 마이크를 1초씩 읽어 detector에 넣고, 최근 10초를 메모리에만 들고 있다가 이상 때 clip으로 넘긴다. */
class SoundMonitor(
    private val detector: AnomalyDetector,
    private val onFrame: (SoundFrame) -> Unit,
    private val onAnomaly: (reason: String, clip: ShortArray) -> Unit,
) {
    @Volatile var suppressed = false   // 창문 열림, 와이퍼, 송풍 강할 때 true → 판단하지 않음
    @Volatile private var running = false
    private val rate = 16_000
    private val ring = ShortArray(rate * 10)
    private var ringPos = 0

    @SuppressLint("MissingPermission")  // 호출 전에 RECORD_AUDIO 확인함
    fun start(onError: (String) -> Unit) {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return onError("마이크 지원 안 됨")
        running = true
        Thread {
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate * 2))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@Thread onError("마이크 초기화 실패") }
            rec.startRecording()
            val buf = ShortArray(rate)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { onError("마이크 읽기 실패 ($n)"); break }
                for (i in 0 until n) { ring[ringPos] = buf[i]; ringPos = (ringPos + 1) % ring.size }
                val frame = SoundFrame.of(buf, n)
                onFrame(frame)
                if (suppressed) continue
                detector.feed(frame)?.let { onAnomaly(it, ring.copyOfRange(ringPos, ring.size) + ring.copyOfRange(0, ringPos)) }
            }
            rec.stop(); rec.release()
        }.start()
    }

    fun stop() { running = false }
}
