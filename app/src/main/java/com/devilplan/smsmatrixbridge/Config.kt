package com.devilplan.smsmatrixbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app configuration stored in SharedPreferences.
 * Thread-safe via synchronized access.
 */
object Config {
    private const val PREFS_NAME = "sms_matrix_bridge"
    private const val KEY_HOMESERVER_URL = "homeserver_url"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RECEIVE_ONLY = "receive_only"
    private const val KEY_SINCE_BATCH = "since_batch"
    private const val KEY_MULTI_ROOM = "multi_room"
    private const val KEY_SPACE_ID = "space_id"

    @Volatile var homeserverUrl: String = ""
    @Volatile var accessToken: String = ""
    @Volatile var roomId: String = ""
    @Volatile var enabled: Boolean = false
    @Volatile var receiveOnly: Boolean = true
    @Volatile var sinceBatch: String? = null
    @Volatile var multiRoom: Boolean = false
    @Volatile var spaceId: String = ""

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context) {
        synchronized(lock) {
            val p = prefs(context)
            homeserverUrl = p.getString(KEY_HOMESERVER_URL, "") ?: ""
            accessToken = p.getString(KEY_ACCESS_TOKEN, "") ?: ""
            roomId = p.getString(KEY_ROOM_ID, "") ?: ""
            enabled = p.getBoolean(KEY_ENABLED, false)
            receiveOnly = p.getBoolean(KEY_RECEIVE_ONLY, true)
            val storedBatch = p.getString(KEY_SINCE_BATCH, null)
            sinceBatch = if (storedBatch.isNullOrEmpty()) null else storedBatch
            multiRoom = p.getBoolean(KEY_MULTI_ROOM, false)
            spaceId = p.getString(KEY_SPACE_ID, "") ?: ""
        }
    }

    fun save(context: Context) {
        synchronized(lock) {
            prefs(context).edit().apply {
                putString(KEY_HOMESERVER_URL, homeserverUrl)
                putString(KEY_ACCESS_TOKEN, accessToken)
                putString(KEY_ROOM_ID, roomId)
                putBoolean(KEY_ENABLED, enabled)
                putBoolean(KEY_RECEIVE_ONLY, receiveOnly)
                putString(KEY_SINCE_BATCH, sinceBatch ?: "")
                putBoolean(KEY_MULTI_ROOM, multiRoom)
                putString(KEY_SPACE_ID, spaceId)
                apply()
            }
        }
    }

    fun isConfigured(): Boolean =
        homeserverUrl.isNotBlank() && accessToken.isNotBlank() && roomId.isNotBlank()

    /**
     * Check if multi-room mode is properly configured.
     * Requires multiRoom enabled and a space ID set.
     */
    fun isMultiRoomConfigured(): Boolean =
        multiRoom && spaceId.isNotBlank()
}