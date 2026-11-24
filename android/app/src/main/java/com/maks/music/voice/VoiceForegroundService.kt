package com.maks.music.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maks.music.MainActivity
import com.maks.music.MaksApplication

/**
 * ForegroundService (type="microphone") для фоновой прослушки «Макс» и горячих слов
 * при свёрнутом приложении или выключенном экране.
 */
class VoiceForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as MaksApplication).voiceEngine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        (application as MaksApplication).voiceEngine.startAlwaysListening()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as MaksApplication).voiceEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Голосовое управление Макса",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Оффлайн-прослушка команды «Макс» и управления музыкой"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Макс слушает (оффлайн)")
            .setContentText("Скажите «Макс...» или «дальше / пауза / тише»")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.maks.music.VOICE_START"
        const val ACTION_STOP = "com.maks.music.VOICE_STOP"
        private const val CHANNEL_ID = "maks_voice_channel"
        private const val NOTIFICATION_ID = 4201
    }
}
