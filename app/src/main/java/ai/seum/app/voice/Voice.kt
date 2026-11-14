package ai.seum.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * Pleos on-device TTS and STT. Speech never leaves the car (the contest forbids sending in-car audio
 * to an external recogniser). If Pleos TTS fails we fall back to Android TTS so the warning is still heard.
 */
class Voice(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var androidTts: AndroidTts? = null
    private var pendingText: String? = null
    private var onSpoken: (() -> Unit)? = null

    private var stt: SpeechToText? = null
    private var sttReady = false
    private var onHeard: ((String?) -> Unit)? = null
    private var lastPartial: String? = null

    private val _status = MutableStateFlow("음성 준비 중")
    val status: StateFlow<String> = _status

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    fun start() {
        if (tts == null) initTts()
        if (stt == null) initStt()
    }

    private fun initTts() {
        try {
            val t = TextToSpeech(app, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    t.addEventListener(object : EventListener {
                        override fun onReady() = Unit
                        override fun onStart() = Unit
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onStop() = Unit
                        override fun onDone() { main.post { finishSpeaking() } }
                        override fun onError(errorType: ErrorType) {
                            Log.w(TAG, "tts error $errorType")
                            main.post { finishSpeaking() }
                        }
                    })
                    runCatching { t.setLocale(Locale.KOREA) }
                    ttsReady = true
                    _status.value = "Pleos 음성 준비됨"
                    pendingText?.let { pendingText = null; speakNow(it) }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "tts init failed $errorType")
                    useAndroidTts()
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "tts create failed: ${e.message}")
            useAndroidTts()
        }
    }

    private fun useAndroidTts() {
        if (androidTts != null) return
        _status.value = "안드로이드 기본 음성 사용"
        androidTts = AndroidTts(app) { ok ->
            if (ok == AndroidTts.SUCCESS) {
                androidTts?.language = Locale.KOREA
                androidTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { main.post { finishSpeaking() } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { finishSpeaking() } }
                })
                pendingText?.let { pendingText = null; speakNow(it) }
            }
        }
    }

    private fun initStt() {
        try {
            val s = SpeechToText(app, SttMode.ON_DEVICE)
            stt = s
            s.addListener(object : ResultListener {
                override fun onReady() = Unit
                override fun onStartedRecognition() = Unit
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onUpdated(stt: String, completed: Boolean) {
                    if (stt.isNotBlank()) lastPartial = stt
                    if (completed) main.post { deliver(stt) }
                }
                override fun onEndedRecognition() { main.post { deliver(lastPartial) } }
                override fun onError(errorType: SttError) {
                    Log.w(TAG, "stt error $errorType")
                    main.post { deliver(null) }
                }
            })
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    runCatching { s.setLocale(Locale.KOREA) }
                    sttReady = true
                }
                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "stt init failed $errorType")
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "stt create failed: ${e.message}")
        }
    }

    /** Speaks [text]; [then] runs when speech is done (or immediately if nothing can speak). */
    fun speak(text: String, then: (() -> Unit)? = null) {
        onSpoken = then
        when {
            ttsReady || androidTts != null -> speakNow(text)
            else -> pendingText = text
        }
        // Safety net: never leave the flow hanging if a completion callback is lost.
        main.removeCallbacks(spokenTimeout)
        main.postDelayed(spokenTimeout, 4000L + text.length * 120L)
    }

    private val spokenTimeout = Runnable { finishSpeaking() }

    private fun speakNow(text: String) {
        if (ttsReady) {
            runCatching { tts?.stop(); tts?.speak(text, true) }.onFailure { useAndroidTts(); pendingText = text }
        } else {
            androidTts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "seum")
        }
    }

    private fun finishSpeaking() {
        main.removeCallbacks(spokenTimeout)
        val cb = onSpoken
        onSpoken = null
        cb?.invoke()
    }

    val canListen: Boolean get() = sttReady

    /** Listens once for a short answer. [onResult] gets the text, or null on silence/timeout/error. */
    fun listen(timeoutMs: Long = 8000, onResult: (String?) -> Unit) {
        val s = stt
        if (s == null || !sttReady) { onResult(null); return }
        lastPartial = null
        onHeard = onResult
        _listening.value = true
        main.postDelayed({ runCatching { s.request() }.onFailure { deliver(null) } }, 300)
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, timeoutMs)
    }

    private val listenTimeout = Runnable {
        runCatching { stt?.stop() }
        deliver(lastPartial)
    }

    private fun deliver(text: String?) {
        val cb = onHeard ?: return
        onHeard = null
        _listening.value = false
        main.removeCallbacks(listenTimeout)
        cb(text?.takeIf { it.isNotBlank() })
    }

    fun stop() {
        runCatching { tts?.stop(); tts?.release() }
        runCatching { stt?.release() }
        androidTts?.shutdown()
        tts = null; stt = null; androidTts = null
        ttsReady = false; sttReady = false
    }

    companion object {
        private const val TAG = "Seum/Voice"
    }
}
