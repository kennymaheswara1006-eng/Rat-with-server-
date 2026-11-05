package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object DeviceConnector {
    suspend fun registerDevice(context: Context, serverUrl: String, telegramId: String, brand: String) {
        withContext(Dispatchers.IO) {
            try {
                // Ensure URL uses http/https
                val endpoint = if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl"
                val url = URL("$endpoint/api/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val jsonParam = JSONObject().apply {
                    put("deviceId", android.os.Build.SERIAL.takeIf { it != android.os.Build.UNKNOWN } ?: android.os.Build.MODEL)
                    put("brand", brand)
                    put("telegramId", telegramId)
                }

                conn.outputStream.use { os ->
                    val input = jsonParam.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = conn.responseCode
                Log.d("DeviceConnector", "Registration response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("DeviceConnector", "Failed to connect to server: ${e.message}", e)
            }
        }
    }
}
