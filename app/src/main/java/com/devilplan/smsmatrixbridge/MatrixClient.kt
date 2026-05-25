package com.devilplan.smsmatrixbridge

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Matrix Client-Server API wrapper.
 * Uses a shared OkHttpClient to avoid leaking connection pools.
 * All methods are suspend — callers must provide their own coroutine scope.
 */
object MatrixClientHolder {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val gson: Gson = Gson()
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
}

class MatrixClient(private val context: Context) {

    companion object {
        private const val TAG = "MatrixClient"
        private val txnCounter = AtomicInteger(0)
        // Cached user ID to avoid re-fetching
        @Volatile var cachedUserId: String? = null
    }

    private val httpClient = MatrixClientHolder.client
    private val gson = MatrixClientHolder.gson

    /**
     * Send an SMS notification to the Matrix room (fire-and-forget from BroadcastReceiver).
     * Uses GlobalScope deliberately — the BroadcastReceiver is short-lived and we need
     * the network call to outlive it.
     */
    fun sendSmsNotice(sender: String, body: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val text = "📱 SMS from $sender:\n$body"
                sendTextMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS notice to Matrix", e)
            }
        }
    }

    /**
     * Send a text message to the configured Matrix room.
     * Must be called from a coroutine (IO dispatcher).
     */
    suspend fun sendTextMessage(text: String) {
        Config.load(context)
        if (!Config.isConfigured()) return

        val txnId = "sms_${System.currentTimeMillis()}_${txnCounter.incrementAndGet()}"
        val url = "${Config.homeserverUrl}/_matrix/client/v3/rooms/${Config.roomId}/send/m.room.message/$txnId"

        val body = mapOf(
            "msgtype" to "m.text",
            "body" to text
        )

        val request = Request.Builder()
            .url(url)
            .put(gson.toJson(body).toRequestBody(MatrixClientHolder.jsonMediaType))
            .addHeader("Authorization", "Bearer ${Config.accessToken}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Matrix send failed: ${response.code} ${response.body?.string()}")
            } else {
                Log.d(TAG, "Message sent to Matrix successfully")
            }
        }
    }

    /**
     * Poll Matrix for new messages in the room using /sync.
     * Returns the nextBatch token for subsequent calls.
     */
    suspend fun pollForMessages(sinceBatch: String?): String? {
        Config.load(context)
        if (!Config.isConfigured()) return null

        val url = buildString {
            append("${Config.homeserverUrl}/_matrix/client/v3/sync")
            append("?timeout=30000")
            append("&filter=${createFilter()}")
            if (sinceBatch != null) append("&since=$sinceBatch")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${Config.accessToken}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Matrix sync failed: ${response.code}")
                    return sinceBatch
                }

                val responseBody = response.body?.string() ?: return sinceBatch
                val json = JsonParser.parseString(responseBody).asJsonObject

                val nextBatch = json.get("next_batch")?.asString ?: return sinceBatch

                val rooms = json.getAsJsonObject("rooms") ?: return nextBatch
                val join = rooms.getAsJsonObject("join") ?: return nextBatch
                val roomData = join.getAsJsonObject(Config.roomId) ?: return nextBatch

                val timeline = roomData.getAsJsonObject("timeline") ?: return nextBatch
                val events = timeline.getAsJsonArray("events") ?: return nextBatch

                val smsSender = SmsSender(context)

                for (event in events) {
                    val evt = event.asJsonObject
                    val type = evt.get("type")?.asString
                    if (type != "m.room.message") continue

                    val content = evt.getAsJsonObject("content") ?: continue
                    val msgtype = content.get("msgtype")?.asString
                    if (msgtype != "m.text") continue

                    val sender = evt.get("sender")?.asString ?: continue

                    // Skip own messages
                    if (sender == cachedUserId) continue

                    val text = content.get("body")?.asString ?: continue

                    if (text.startsWith("!sms ", ignoreCase = true)) {
                        val args = text.removePrefix("!sms ").trim()
                        val phoneRegex = Regex("^\\+?\\d+\\s+")
                        val match = phoneRegex.find(args)
                        if (match != null) {
                            val phone = match.value.trim()
                            val message = args.removePrefix(match.value).trim()
                            Log.i(TAG, "Sending SMS to $phone: $message")
                            smsSender.sendSms(phone, message)
                            sendTextMessage("✅ SMS sent to $phone")
                        }
                    }
                }

                return nextBatch
            }
        } catch (e: Exception) {
            Log.e(TAG, "Matrix sync error", e)
            return sinceBatch
        }
    }

    private fun createFilter(): String {
        val filter = mapOf(
            "room" to mapOf(
                "rooms" to listOf(Config.roomId),
                "timeline" to mapOf(
                    "types" to listOf("m.room.message"),
                    "limit" to 10
                )
            )
        )
        return java.net.URLEncoder.encode(gson.toJson(filter), "UTF-8")
    }

    /**
     * Get the user's own Matrix ID from the whoami endpoint.
     * Caches the result for self-message filtering.
     */
    suspend fun getUserId(): String? {
        Config.load(context)
        val url = "${Config.homeserverUrl}/_matrix/client/v3/account/whoami"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${Config.accessToken}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val userId = JsonParser.parseString(responseBody).asJsonObject.get("user_id")?.asString
                if (userId != null) {
                    cachedUserId = userId
                }
                userId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user ID", e)
            null
        }
    }
}