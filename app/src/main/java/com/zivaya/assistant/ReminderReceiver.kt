package com.zivaya.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "रिमाइंडर"
        val id = intent.getIntExtra(EXTRA_ID, 0)

        ensureChannel(context)

        val openApp = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context, id, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("ज़िवाया रिमाइंडर")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // सूचना की अनुमति नहीं मिली है, इसलिए सूचना नहीं दिखेगी
        }
    }

    companion object {
        const val CHANNEL_ID = "zivaya_reminders"
        const val EXTRA_TEXT = "reminder_text"
        const val EXTRA_ID = "reminder_id"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "रिमाइंडर",
                    NotificationManager.IMPORTANCE_HIGH
                )
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }

        // रिमाइंडर लगाता है। triggerAtMillis = कितने बजे बजना है
        fun schedule(context: Context, text: String, triggerAtMillis: Long) {
            val id = (triggerAtMillis % Int.MAX_VALUE).toInt()
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_ID, id)
            }
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pending
            )
        }
    }
}
