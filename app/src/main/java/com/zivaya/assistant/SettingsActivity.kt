package com.zivaya.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zivaya.assistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        tts = TtsHelper(this, prefs)

        // पुरानी सेटिंग्स स्क्रीन पर दिखाओ
        if (prefs.language == "hi") {
            binding.radioHindi.isChecked = true
        } else {
            binding.radioEnglish.isChecked = true
        }
        binding.switchSpeak.isChecked = prefs.speakReplies
        binding.seekSpeed.progress = prefs.speedProgress
        binding.editBackendUrl.setText(prefs.backendUrl)

        // आवाज़ टेस्ट करने का बटन
        binding.btnTestVoice.setOnClickListener {
            saveVoiceChoices()
            tts.applyVoice()
            if (!tts.isReady) {
                Toast.makeText(
                    this,
                    "इस भाषा की आवाज़ अभी तैयार नहीं है। फ़ोन की Settings में Text-to-speech देखें।",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val sample = if (prefs.language == "hi")
                    "नमस्ते, मैं ज़िवाया हूँ।"
                else
                    "Hello, I am Zivaya."
                tts.speak(sample)
            }
        }

        // सेव करने का बटन
        binding.btnSaveSettings.setOnClickListener {
            val url = binding.editBackendUrl.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("https://")) {
                Toast.makeText(
                    this,
                    "सर्वर का पता https:// से शुरू होना चाहिए",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            saveVoiceChoices()
            prefs.backendUrl = url
            Toast.makeText(this, "सेटिंग्स सेव हो गईं", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveVoiceChoices() {
        prefs.language = if (binding.radioHindi.isChecked) "hi" else "en"
        prefs.speakReplies = binding.switchSpeak.isChecked
        prefs.speedProgress = binding.seekSpeed.progress
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
