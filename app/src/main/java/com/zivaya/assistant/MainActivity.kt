package com.zivaya.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.zivaya.assistant.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsHelper
    private lateinit var adapter: MessageAdapter

    private val messages = mutableListOf<ChatMessage>()
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var resumedOnce = false
    private var pendingReminder: Pair<String, Long>? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                addBot(
                    "माइक की अनुमति नहीं मिली, इसलिए मैं सुन नहीं सकती। आप चाहें तो लिखकर पूछ सकते हैं, या फ़ोन की Settings → Apps → Zivaya → Permissions में माइक चालू कर सकते हैं।",
                    false
                )
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = pendingReminder
            pendingReminder = null
            if (p != null) {
                if (granted) {
                    ReminderReceiver.schedule(this, p.first, p.second)
                    addBot("ठीक है, रिमाइंडर लगा दिया।")
                } else {
                    addBot("सूचना की अनुमति नहीं मिली, इसलिए रिमाइंडर नहीं लगाया।", false)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        tts = TtsHelper(this, prefs) { ready ->
            if (!ready) setStatus("आवाज़ तैयार नहीं है")
        }

        adapter = MessageAdapter(messages)
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerMessages.adapter = adapter

        binding.btnSend.setOnClickListener { sendFromInput() }
        binding.btnMic.setOnClickListener { toggleMic() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        addBot(
            "नमस्ते! मैं ज़िवाया हूँ। आप लिखकर या 🎤 दबाकर बोलकर पूछ सकते हैं। मैं ऐप खोल सकती हूँ और रिमाइंडर लगा सकती हूँ।",
            false
        )
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce) tts.applyVoice()
        resumedOnce = true
    }

    override fun onPause() {
        if (isListening) stopListening()
        tts.stop()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts.shutdown()
        super.onDestroy()
    }

    // ---------- संदेश दिखाना ----------

    private fun setStatus(text: String) {
        binding.txtStatus.text = text
    }

    private fun addMessage(msg: ChatMessage) {
        adapter.addMessage(msg)
        binding.recyclerMessages.scrollToPosition(messages.size - 1)
    }

    private fun addUser(text: String) = addMessage(ChatMessage(text, true))

    private fun addBot(text: String, speak: Boolean = true) {
        addMessage(ChatMessage(text, false))
        if (speak && prefs.speakReplies) tts.speak(text)
    }

    private fun sendFromInput() {
        val text = binding.editMessage.text.toString().trim()
        if (text.isEmpty()) return
        binding.editMessage.setText("")
        tts.stop()
        handleUserText(text)
    }

    // ---------- आदेश समझना ----------

    private fun handleUserText(text: String) {
        addUser(text)
        val lower = text.lowercase()
        when {
            isMessageSendRequest(lower) -> addBot(
                "मैं आपकी तरफ़ से WhatsApp, Instagram या SMS संदेश अपने-आप नहीं भेज सकती, यह Android के नियमों के अनुसार सुरक्षित नहीं है। मैं वह ऐप खोल सकती हूँ, फिर आप खुद संदेश भेज सकते हैं। जैसे बोलें: \"व्हाट्सएप खोलो\"।"
            )
            tryReminder(text, lower) -> {}
            tryOpenApp(lower) -> {}
            else -> askBackend()
        }
    }

    private fun isMessageSendRequest(lower: String): Boolean {
        val send = lower.contains("भेज") || lower.contains("send")
        val target = listOf(
            "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "instagram",
            "इंस्टाग्राम", "message", "मैसेज", "संदेश", "sms", "एसएमएस"
        ).any { lower.contains(it) }
        return send && target
    }

    // ---------- रिमाइंडर ----------

    private fun tryReminder(original: String, lower: String): Boolean {
        val wantsReminder = lower.contains("याद") || lower.contains("remind") ||
                lower.contains("रिमाइंडर")
        if (!wantsReminder) return false

        val match = Regex("(\\d+)\\s*(मिनट|मिनिट|minutes?|mins?|min|घंटे|घंटा|hours?|hrs?|hr)")
            .find(lower)
        if (match == null) {
            addBot("समय बताइए। जैसे: \"10 मिनट में पानी पीने की याद दिलाओ\"।")
            return true
        }

        val number = match.groupValues[1].toLongOrNull() ?: 0L
        val unit = match.groupValues[2]
        val isHour = unit.startsWith("घ") || unit.startsWith("h")
        val minutes = if (isHour) number * 60 else number

        if (minutes <= 0L || minutes > 1440L) {
            addBot("मैं 1 मिनट से 24 घंटे के बीच का रिमाइंडर लगा सकती हूँ।")
            return true
        }

        val whenText = if (isHour) "$number घंटे बाद" else "$number मिनट बाद"
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L

        AlertDialog.Builder(this)
            .setTitle("रिमाइंडर लगाऊँ?")
            .setMessage("\"$original\"\n\n$whenText सूचना आएगी।")
            .setPositiveButton("हाँ") { _, _ -> scheduleWithPermission(original, triggerAt) }
            .setNegativeButton("नहीं") { _, _ -> addBot("ठीक है, रिमाइंडर नहीं लगाया।") }
            .show()
        return true
    }

    private fun scheduleWithPermission(text: String, triggerAt: Long) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            pendingReminder = Pair(text, triggerAt)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderReceiver.schedule(this, text, triggerAt)
            addBot("ठीक है, रिमाइंडर लगा दिया।")
        }
    }

    // ---------- ऐप खोलना ----------

    private val openWords = listOf(
        "खोलो", "खोलिए", "खोल दो", "चालू करो", "चलाओ", "open ", "launch "
    )

    private val removeWords = listOf(
        "ऐप", "app", "को", "जी", "ज़रा", "ज़रा", "please", "कृपया",
        "zivaya", "ज़िवाया", "ज़िवाया"
    )

    private val aliases = listOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp",
        "वॉट्सऐप" to "whatsapp", "वॉट्सएप" to "whatsapp",
        "इंस्टाग्राम" to "instagram", "यूट्यूब" to "youtube",
        "क्रोम" to "chrome", "जीमेल" to "gmail", "मैप" to "maps",
        "टेलीग्राम" to "telegram", "फेसबुक" to "facebook",
        "स्पॉटिफाई" to "spotify", "कैमरा" to "camera",
        "गैलरी" to "gallery", "कैलकुलेटर" to "calculator",
        "घड़ी" to "clock", "सेटिंग" to "settings",
        "फोनपे" to "phonepe", "फोन" to "phone"
    )

    private fun tryOpenApp(lower: String): Boolean {
        if (openWords.none { lower.contains(it) }) return false

        var name = lower
        for (w in openWords) name = name.replace(w, " ")
        for (w in removeWords) name = name.replace(w, " ")
        name = name.trim()
        if (name.isEmpty()) {
            addBot("कौन-सा ऐप खोलूँ? जैसे बोलें: \"यूट्यूब खोलो\"।")
            return true
        }

        var search = name
        for ((hindi, english) in aliases) {
            if (name.contains(hindi)) {
                search = english
                break
            }
        }
        search = search.trim()

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = packageManager.queryIntentActivities(launcher, 0).firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString().lowercase()
            label.contains(search) || (label.length >= 3 && search.contains(label))
        }

        if (found == null) {
            addBot("\"$name\" नाम का ऐप मुझे इस फ़ोन में नहीं मिला।")
            return true
        }

        val label = found.loadLabel(packageManager).toString()
        val pkg = found.activityInfo.packageName

        AlertDialog.Builder(this)
            .setTitle("ऐप खोलूँ?")
            .setMessage("क्या मैं $label खोलूँ?")
            .setPositiveButton("हाँ") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    startActivity(launch)
                } else {
                    addBot("यह ऐप खोला नहीं जा सका।")
                }
            }
            .setNegativeButton("नहीं") { _, _ -> addBot("ठीक है, ऐप नहीं खोला।") }
            .show()
        return true
    }

    // ---------- AI सर्वर से बात ----------

    private fun askBackend() {
        val url = prefs.backendUrl
        if (url.isEmpty()) {
            addBot(
                "AI से जवाब पाने के लिए सर्वर का पता चाहिए, जो अभी सेट नहीं है। यह हम बाद के चरण में बनाएँगे। तब तक मैं ऐप खोल सकती हूँ और रिमाइंडर लगा सकती हूँ।",
                false
            )
            return
        }

        val history = JSONArray()
        messages.takeLast(10).dropWhile { !it.isUser }.forEach {
            history.put(
                JSONObject()
                    .put("role", if (it.isUser) "user" else "assistant")
                    .put("content", it.text)
            )
        }
        val json = JSONObject()
            .put("language", prefs.language)
            .put("messages", history)
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = try {
            Request.Builder().url(url).post(body).build()
        } catch (e: IllegalArgumentException) {
            addBot("सर्वर का पता सही नहीं है। कृपया सेटिंग्स में जाँचें।", false)
            return
        }

        setStatus("सोच रही हूँ...")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setStatus("तैयार")
                    addBot("सर्वर से जुड़ नहीं पाई। कृपया इंटरनेट देखें और फिर कोशिश करें।", false)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                var reply: String? = null
                var errorText: String? = null
                response.use { r ->
                    val text = r.body?.string() ?: ""
                    if (r.isSuccessful) {
                        try {
                            reply = JSONObject(text).optString("reply").trim()
                        } catch (e: Exception) {
                            errorText = "सर्वर का जवाब समझ नहीं आया।"
                        }
                    } else {
                        errorText = "सर्वर ने गड़बड़ बताई (कोड ${r.code})।"
                    }
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setStatus("तैयार")
                    val finalReply = reply
                    if (!finalReply.isNullOrEmpty()) {
                        addBot(finalReply)
                    } else {
                        addBot(errorText ?: "सर्वर से खाली जवाब आया।", false)
                    }
                }
            }
        })
    }

    // ---------- बोलकर सुनना ----------

    private fun toggleMic() {
        if (isListening) stopListening() else ensureMicThenListen()
    }

    private fun ensureMicThenListen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addBot(
                "इस फ़ोन में बोलकर पहचानने की सुविधा नहीं मिली। कृपया Google ऐप अपडेट करें, या लिखकर पूछें।",
                false
            )
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        tts.stop()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.localeTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        setListeningUi(true)
        setStatus("सुन रही हूँ...")
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        recognizer?.stopListening()
        setListeningUi(false)
    }

    private fun setListeningUi(on: Boolean) {
        isListening = on
        binding.btnMic.text = if (on) "⏹" else "🎤"
        if (!on) setStatus("तैयार")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) binding.editMessage.setText(partial)
        }

        override fun onResults(results: Bundle?) {
            setListeningUi(false)
            binding.editMessage.setText("")
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) handleUserText(text.trim())
        }

        override fun onError(error: Int) {
            setListeningUi(false)
            binding.editMessage.setText("")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    setStatus("कुछ सुनाई नहीं दिया, फिर से 🎤 दबाएँ")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER ->
                    addBot("आवाज़ पहचानने के लिए इंटरनेट की दिक्कत आ रही है।", false)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    addBot("माइक की अनुमति चाहिए। फ़ोन की Settings में Zivaya को माइक की अनुमति दें।", false)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    setStatus("पहचान सेवा व्यस्त है, थोड़ी देर बाद कोशिश करें")
                SpeechRecognizer.ERROR_AUDIO ->
                    addBot("माइक से आवाज़ लेने में दिक्कत हुई।", false)
                SpeechRecognizer.ERROR_CLIENT -> setStatus("तैयार")
                else -> setStatus("आवाज़ पहचानने में दिक्कत हुई (कोड $error)")
            }
        }
    }
}
