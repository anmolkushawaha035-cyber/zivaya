package com.zivaya.assistant

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("zivaya_settings", Context.MODE_PRIVATE)

    // भाषा: "hi" = हिंदी, "en" = English
    var language: String
        get() = sp.getString("language", "hi") ?: "hi"
        set(value) = sp.edit().putString("language", value).apply()

    // जवाब बोलकर सुनाना है या नहीं
    var speakReplies: Boolean
        get() = sp.getBoolean("speak_replies", true)
        set(value) = sp.edit().putBoolean("speak_replies", value).apply()

    // बोलने की रफ़्तार (0 से 150)
    var speedProgress: Int
        get() = sp.getInt("speed_progress", 50)
        set(value) = sp.edit().putInt("speed_progress", value).apply()

    // AI सर्वर का पता (इसमें कोई गुप्त key नहीं होती)
    var backendUrl: String
        get() = sp.getString("backend_url", "") ?: ""
        set(value) = sp.edit().putString("backend_url", value.trim()).apply()

    fun speechRate(): Float = 0.5f + speedProgress / 100f

    fun localeTag(): String = if (language == "hi") "hi-IN" else "en-IN"
}
