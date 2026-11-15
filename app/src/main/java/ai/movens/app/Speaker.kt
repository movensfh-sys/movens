package ai.movens.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import java.util.Locale

/** 짧은 음성 한 문장 + 경고음. Pleos TextToSpeech SDK(ON_DEVICE: 앱 등록·네트워크 불필요)를 쓴다. */
class Speaker(ctx: Context) {
    private val tts = TextToSpeech(ctx, Mode.ON_DEVICE)
    // 오디오가 아직 준비되지 않은 기기에서는 ToneGenerator 생성이 RuntimeException("Init failed")을 던져
    // 앱이 실행되자마자 꺼졌다 → 경고음만 빼고 음성 안내는 그대로 동작하게 한다.
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
    @Volatile var status = "초기화 중"; private set

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() { tts.setLocale(Locale.KOREA); status = "준비됨" }
            override fun onFailure(errorType: ErrorType) { status = "실패: $errorType"; Log.w("movens", "TTS init failed: $errorType") }
        })
    }

    fun say(text: String) { if (status == "준비됨") tts.speak(text) }

    /** 경고음 0.6초 뒤 음성. 화면 문구는 호출한 쪽이 띄운다. */
    fun alarm(text: String) { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600); say(text) }

    fun release() { tts.release(); tone?.release() }
}
