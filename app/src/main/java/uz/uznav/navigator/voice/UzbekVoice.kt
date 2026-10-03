package uz.uznav.navigator.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * O'zbekcha ovozli yo'riqnoma (Text-To-Speech).
 *
 * Ovoz tanlash tartibi:
 *  1. O'zbek tili (uz-UZ) — agar telefonda o'zbekcha ovoz paketi bo'lsa,
 *     eng tabiiy natija shu.
 *  2. Turkcha ovoz — o'zbek lotin alifbosidagi matnni deyarli to'g'ri
 *     talaffuz qiladi (tovushlar juda yaqin).
 *  3. Tizimning standart ovozi — oxirgi chora.
 *
 * Matnlardagi raqamlar oldindan o'zbekcha so'zga aylantirilgani uchun
 * (masalan "uch yuz metr") ovoz paketi o'zbek tilini bilmasa ham
 * ko'rsatma tushunarli eshitiladi.
 */
class UzbekVoice(private val context: Context) {

    /** Ovoz tizimining holati. */
    enum class VoiceState {
        /** Ishga tushmoqda. */
        YUKLANMOQDA,

        /** O'zbekcha ovoz topildi. */
        OZBEKCHA,

        /** O'zbekcha yo'q — turkcha (yaqin talaffuz) ishlatilmoqda. */
        TURKCHA_ORNIGA,

        /** Faqat standart ovoz mavjud. */
        STANDART,

        /** Ovoz tizimi ishlamadi. */
        ISHLAMAYDI
    }

    private val _state = MutableStateFlow(VoiceState.YUKLANMOQDA)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Ovozli yo'riqnoma yoqilganmi (foydalanuvchi sozlamasi). */
    @Volatile
    var enabled: Boolean = true

    private var tts: TextToSpeech? = null
    private var ready = false
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** Ovoz tizimini ishga tushiradi. */
    fun initialize(onReady: ((VoiceState) -> Unit)? = null) {
        if (tts != null) {
            onReady?.invoke(_state.value)
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                _state.value = VoiceState.ISHLAMAYDI
                Log.w(TAG, "TTS ishga tushmadi: $status")
                onReady?.invoke(_state.value)
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            _state.value = selectLanguage(engine)
            ready = _state.value != VoiceState.ISHLAMAYDI

            engine.setSpeechRate(0.98f)
            engine.setPitch(1.0f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = abandonFocus()

                @Deprecated("Eski Android versiyalari uchun")
                override fun onError(utteranceId: String?) = abandonFocus()
            })
            onReady?.invoke(_state.value)
        }
    }

    /** Mos ovoz tilini tanlaydi. */
    private fun selectLanguage(engine: TextToSpeech): VoiceState {
        val candidates = listOf(
            Locale("uz", "UZ") to VoiceState.OZBEKCHA,
            Locale("uz") to VoiceState.OZBEKCHA,
            Locale("tr", "TR") to VoiceState.TURKCHA_ORNIGA,
            Locale("tr") to VoiceState.TURKCHA_ORNIGA
        )
        for ((locale, state) in candidates) {
            val result = try {
                engine.isLanguageAvailable(locale)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                val setResult = engine.setLanguage(locale)
                if (setResult >= TextToSpeech.LANG_AVAILABLE) {
                    Log.i(TAG, "Ovoz tili tanlandi: $locale")
                    return state
                }
            }
        }
        // Hech qaysi mos kelmasa — standart ovoz
        return VoiceState.STANDART
    }

    /**
     * Matnni ovoz bilan aytadi.
     *
     * @param flush true bo'lsa, oldingi gap to'xtatiladi (muhim ogohlantirishlar uchun)
     */
    fun speak(text: String, flush: Boolean = false) {
        if (!enabled || !ready || text.isBlank()) return
        val engine = tts ?: return
        requestFocus()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "uznav-" + System.nanoTime()
        val result = engine.speak(text, mode, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Ovoz chiqarib bo'lmadi: $text")
            abandonFocus()
        }
    }

    /** Gapirishni darhol to'xtatadi. */
    fun stopSpeaking() {
        runCatching { tts?.stop() }
        abandonFocus()
    }

    /** Resurslarni bo'shatadi (ilova yopilganda). */
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        abandonFocus()
    }

    /** Foydalanuvchiga ovoz holati haqida o'zbekcha izoh. */
    fun stateMessage(): String = when (_state.value) {
        VoiceState.YUKLANMOQDA -> "Ovoz tizimi tayyorlanmoqda…"
        VoiceState.OZBEKCHA -> "O'zbekcha ovoz ishlamoqda"
        VoiceState.TURKCHA_ORNIGA ->
            "O'zbekcha ovoz paketi topilmadi. Vaqtincha turkcha ovoz ishlatilmoqda " +
                    "(talaffuzi o'zbekchaga yaqin)."
        VoiceState.STANDART ->
            "O'zbekcha ovoz paketi topilmadi. Standart ovoz ishlatilmoqda."
        VoiceState.ISHLAMAYDI -> "Ovoz tizimi ishlamadi"
    }

    // --- Audio fokus: musiqa/radio ovozini vaqtincha pasaytirish ---

    private fun requestFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
            }
            focusRequest?.let { runCatching { manager.requestAudioFocus(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.abandonAudioFocus(null) }
        }
    }

    companion object {
        private const val TAG = "UzNav/Ovoz"
    }
}
