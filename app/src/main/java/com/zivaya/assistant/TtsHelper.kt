package com.zivaya.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsHelper(
    context: Context,
    private val prefs: Prefs,
    private val onStatus: (ready: Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)

    var isReady = false
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyVoice()
        } else {
            isReady = false
            onStatus(false)
        }
    }

    // भाषा और आवाज़ दोबारा लागू करता है (सेटिंग्स बदलने के बाद भी चलता है)
    fun applyVoice() {
        val locale = Locale.forLanguageTag(prefs.localeTag())
        val result = tts.setLanguage(locale)
        isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED

        if (isReady) {
            try {
                // अगर फ़ोन में "female" नाम की आवाज़ मिले तो वही चुनो
                val female = tts.voices?.firstOrNull { v ->
                    v.locale.language == locale.language &&
                            v.name.contains("female", ignoreCase = true)
                }
                if (female != null) {
                    tts.voice = female
                }
            } catch (e: Exception) {
                // आवाज़ चुनने में दिक्कत हुई तो फ़ोन की डिफ़ॉल्ट आवाज़ चलेगी
            }
        }
        onStatus(isReady)
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        tts.setSpeechRate(prefs.speechRate())
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zivaya_utterance")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
