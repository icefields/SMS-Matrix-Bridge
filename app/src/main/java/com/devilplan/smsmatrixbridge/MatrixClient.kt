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
        @Volatile var cachedUserId: String? = null
    }

    private val httpClient = MatrixClientHolder.client
    private val gson = MatrixClientHolder.gson

    // ================================================================
    // SMS → Matrix (incoming SMS forwarding)
    // ================================================================

    /**
     * Send an SMS notification to the appropriate Matrix room.
     * In multi-room mode: routes to (or creates) a per-contact room.
     * In single-room mode: sends to the configured single room.
     */
    fun sendSmsNotice(sender: String, body: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Config.load(context)
                if (Config.isMultiRoomConfigured()) {
                    val roomId = getOrCreateContactRoom(sender)
                    if (roomId != null) {
                        val text = "📱 SMS from $sender:\n$body"
                        sendTextMessageToRoom(roomId, text)
                    } else {
                        Log.e(TAG, "Failed to get/create room for $sender, falling back to single room")
                        val text = "📱 SMS from $sender:\n$body"
                        sendTextMessage(text)
                    }
                } else {
                    val text = "📱 SMS from $sender:\n$body"
                    sendTextMessage(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS notice to Matrix", e)
            }
        }
    }

    // ================================================================
    // Matrix API: Send messages
    // ================================================================

    /**
     * Send a text message to the configured Matrix room (single-room mode).
     */
    suspend fun sendTextMessage(text: String) {
        Config.load(context)
        if (!Config.isConfigured()) return
        sendTextMessageToRoom(Config.roomId, text)
    }

    /**
     * Send a text message to a specific Matrix room.
     * Caller must ensure Config is loaded and homeserver/token are set.
     */
    suspend fun sendTextMessageToRoom(roomId: String, text: String) {
        if (Config.homeserverUrl.isBlank() || Config.accessToken.isBlank()) return

        val txnId = "sms_${System.currentTimeMillis()}_${txnCounter.incrementAndGet()}"
        val url = "${Config.homeserverUrl}/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId"

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
                Log.e(TAG, "Matrix send failed to $roomId: ${response.code} ${response.body?.string()}")
            } else {
                Log.d(TAG, "Message sent to room $roomId successfully")
            }
        }
    }

    // ================================================================
    // Matrix API: Room & Space creation
    // ================================================================

    /**
     * Create a Matrix space for SMS rooms.
     * Returns the space's room ID on success, null on failure.
     */
    suspend fun createSpace(name: String): String? {
        Config.load(context)
        if (Config.homeserverUrl.isBlank() || Config.accessToken.isBlank()) return null

        val url = "${Config.homeserverUrl}/_matrix/client/v3/createRoom"

        val body = mapOf(
            "name" to name,
            "visibility" to "private",
            "preset" to "private_chat",
            "creation_content" to mapOf(
                "type" to "m.space"
            ),
            "initial_state" to listOf(
                mapOf(
                    "type" to "m.room.guest_access",
                    "state_key" to "",
                    "content" to mapOf("guest_access" to "forbidden")
                )
            )
        )

        val request = Request.Builder()
            .url(url)
            .put(gson.toJson(body).toRequestBody(MatrixClientHolder.jsonMediaType))
            .addHeader("Authorization", "Bearer ${Config.accessToken}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Space creation failed: ${response.code} ${response.body?.string()}")
                    return null
                }
                val responseBody = response.body?.string() ?: return null
                val json = JsonParser.parseString(responseBody).asJsonObject
                val spaceId = json.get("room_id")?.asString
                Log.i(TAG, "Created space: $spaceId")
                spaceId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Space creation error", e)
            null
        }
    }

    /**
     * Create a per-contact Matrix room and add it to the SMS space.
     * Returns the room ID on success, null on failure.
     */
    suspend fun createContactRoom(phoneNumber: String): String? {
        Config.load(context)
        if (Config.homeserverUrl.isBlank() || Config.accessToken.isBlank()) return null

        val roomName = "📱 $phoneNumber"
        val url = "${Config.homeserverUrl}/_matrix/client/v3/createRoom"

        val body = mutableMapOf<String, Any>(
            "name" to roomName,
            "visibility" to "private",
            "preset" to "private_chat",
            "topic" to "SMS conversation with $phoneNumber"
        )

        // If a space is configured, add the room as a child of the space
        if (Config.spaceId.isNotBlank()) {
            body["initial_state"] = listOf(
                mapOf(
                    "type" to "m.space.parent",
                    "state_key" to Config.spaceId,
                    "content" to mapOf(
                        "via" to listOf(extractServerName(Config.spaceId)),
                        "canonical" to true
                    )
                )
            )
        }

        val request = Request.Builder()
            .url(url)
            .put(gson.toJson(body).toRequestBody(MatrixClientHolder.jsonMediaType))
            .addHeader("Authorization", "Bearer ${Config.accessToken}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Contact room creation failed: ${response.code} ${response.body?.string()}")
                    return null
                }
                val responseBody = response.body?.string() ?: return null
                val json = JsonParser.parseString(responseBody).asJsonObject
                val roomId = json.get("room_id")?.asString

                if (roomId != null && Config.spaceId.isNotBlank()) {
                    // Add the space as a parent of the room (back-reference)
                    addSpaceChild(Config.spaceId, roomId)
                }

                Log.i(TAG, "Created contact room for $phoneNumber: $roomId")
                roomId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact room creation error", e)
            null
        }
    }

    /**
     * Add a room as a child of a space.
     * This sets the m.space.child state event on the space room.
     */
    suspend fun addSpaceChild(spaceId: String, childRoomId: String): Boolean {
        Config.load(context)
        if (Config.homeserverUrl.isBlank() || Config.accessToken.isBlank()) return false

        val url = "${Config.homeserverUrl}/_matrix/client/v3/rooms/$spaceId/state/m.space.child/$childRoomId"

        val body = mapOf(
            "via" to listOf(extractServerName(childRoomId)),
            "suggested" to false
        )

        val request = Request.Builder()
            .url(url)
            .put(gson.toJson(body).toRequestBody(MatrixClientHolder.jsonMediaType))
            .addHeader("Authorization", "Bearer ${Config.accessToken}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Added $childRoomId as child of space $spaceId")
                    true
                } else {
                    Log.e(TAG, "Failed to add space child: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Add space child error", e)
            false
        }
    }

    /**
     * Get or create a Matrix room for a phone number.
     * Checks the local database first, creates the room if it doesn't exist.
     * Returns the room ID, or null if creation failed.
     */
    private suspend fun getOrCreateContactRoom(phoneNumber: String): String? {
        val db = RoomDatabase(context)

        // Check if we already have a room for this number
        val existingRoomId = db.getRoomId(phoneNumber)
        if (existingRoomId != null) {
            return existingRoomId
        }

        // Create a new room
        val roomId = createContactRoom(phoneNumber) ?: return null

        // Store the mapping
        val roomName = "📱 $phoneNumber"
        db.addMapping(phoneNumber, roomId, roomName)

        return roomId
    }

    // ================================================================
    // Matrix API: Sync
    // ================================================================

    /**
     * Poll Matrix for new messages using /sync.
     * In multi-room mode: syncs all contact rooms from the database plus the main room.
     * In single-room mode: syncs only the main room.
     * Returns the nextBatch token for subsequent calls.
     */
    suspend fun pollForMessages(sinceBatch: String?): String? {
        Config.load(context)
        if (!Config.isConfigured()) return null

        val roomIds = buildSyncRoomList()
        val url = buildString {
            append("${Config.homeserverUrl}/_matrix/client/v3/sync")
            append("?timeout=30000")
            append("&filter=${createFilter(roomIds)}")
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

                // Initial sync (sinceBatch == null): skip all events, just get the bookmark
                if (sinceBatch == null) {
                    Log.d(TAG, "Initial sync complete, got next_batch token. Skipping historical events.")
                    return nextBatch
                }

                val rooms = json.getAsJsonObject("rooms") ?: return nextBatch
                val join = rooms.getAsJsonObject("join") ?: return nextBatch

                val smsSender = SmsSender(context)
                val db = RoomDatabase(context)

                for ((roomId, roomData) in join.entrySet()) {
                    val timeline = roomData.asJsonObject.getAsJsonObject("timeline") ?: continue
                    val events = timeline.asJsonObject.getAsJsonArray("events") ?: continue

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

                        // Multi-room mode: messages in contact rooms are sent directly as SMS
                        if (Config.isMultiRoomConfigured() && roomId != Config.roomId) {
                            val phone = db.getPhoneByRoomId(roomId)
                            if (phone != null) {
                                // Skip !sms commands in contact rooms — just send the text directly
                                if (!text.startsWith("!sms ", ignoreCase = true)) {
                                    Log.i(TAG, "Sending SMS to $phone from room $roomId: $text")
                                    smsSender.sendSms(phone, text)
                                    sendTextMessageToRoom(roomId, "✅ SMS sent to $phone")
                                }
                            }
                        } else {
                            // Single-room mode or main room: parse !sms commands
                            if (text.startsWith("!sms ", ignoreCase = true)) {
                                val args = text.removePrefix("!sms ").trim()
                                val phoneRegex = Regex("^\\+?\\d+\\s+")
                                val match = phoneRegex.find(args)
                                if (match != null) {
                                    val phone = match.value.trim()
                                    val message = args.removePrefix(match.value).trim()
                                    Log.i(TAG, "Sending SMS to $phone: $message")
                                    smsSender.sendSms(phone, message)
                                    sendTextMessageToRoom(Config.roomId, "✅ SMS sent to $phone")
                                }
                            }
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

    /**
     * Build the list of room IDs to include in the sync filter.
     * In multi-room mode: includes all contact rooms plus the main room.
     * In single-room mode: just the main room.
     */
    private fun buildSyncRoomList(): List<String> {
        val rooms = mutableListOf(Config.roomId)
        if (Config.isMultiRoomConfigured()) {
            val db = RoomDatabase(context)
            rooms.addAll(db.getAllRoomIds())
        }
        return rooms.distinct()
    }

    private fun createFilter(roomIds: List<String>): String {
        val filter = mapOf(
            "room" to mapOf(
                "rooms" to roomIds,
                "timeline" to mapOf(
                    "types" to listOf("m.room.message"),
                    "limit" to 10
                )
            )
        )
        return java.net.URLEncoder.encode(gson.toJson(filter), "UTF-8")
    }

    // ================================================================
    // Matrix API: Identity
    // ================================================================

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

    // ================================================================
    // Utilities
    // ================================================================

    /**
     * Extract the server name from a Matrix ID (room or user).
     * E.g., "!abc:example.com" → "example.com"
     */
    fun extractServerName(matrixId: String): String {
        val parts = matrixId.split(":")
        return if (parts.size > 1) parts.drop(1).joinToString(":") else ""
    }
}