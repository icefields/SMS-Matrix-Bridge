package com.devilplan.smsmatrixbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.util.Log

/**
 * SQLite database for phone number → Matrix room ID mapping.
 * Used in multi-room mode to route SMS to per-contact rooms.
 */
class RoomDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "RoomDatabase"
        private const val DATABASE_NAME = "sms_rooms.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "phone_rooms"
        private const val COL_PHONE = "phone"
        private const val COL_ROOM_ID = "room_id"
        private const val COL_ROOM_NAME = "room_name"
        private const val COL_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                $COL_PHONE TEXT PRIMARY KEY,
                $COL_ROOM_ID TEXT NOT NULL,
                $COL_ROOM_NAME TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
        if (oldVersion < newVersion) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    /**
     * Store a phone → room mapping.
     */
    fun addMapping(phone: String, roomId: String, roomName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PHONE, phone)
            put(COL_ROOM_ID, roomId)
            put(COL_ROOM_NAME, roomName)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.d(TAG, "Mapped $phone → $roomId ($roomName)")
    }

    /**
     * Look up the room ID for a phone number.
     * Returns null if no mapping exists.
     */
    fun getRoomId(phone: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ROOM_ID),
            "$COL_PHONE = ?",
            arrayOf(phone),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndexOrThrow(COL_ROOM_ID))
            }
        }
        return null
    }

    /**
     * Get all phone → room mappings.
     * Returns a list of (phone, roomId, roomName) triples.
     */
    fun getAllMappings(): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_PHONE, COL_ROOM_ID, COL_ROOM_NAME),
            null, null, null, null,
            "$COL_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val phone = it.getString(it.getColumnIndexOrThrow(COL_PHONE))
                val roomId = it.getString(it.getColumnIndexOrThrow(COL_ROOM_ID))
                val roomName = it.getString(it.getColumnIndexOrThrow(COL_ROOM_NAME))
                result.add(Triple(phone, roomId, roomName))
            }
        }
        return result
    }

    /**
     * Look up phone number by room ID.
     * Used when detecting which phone number a Matrix message should be sent to.
     */
    fun getPhoneByRoomId(roomId: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_PHONE),
            "$COL_ROOM_ID = ?",
            arrayOf(roomId),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndexOrThrow(COL_PHONE))
            }
        }
        return null
    }

    /**
     * Get all room IDs that the bridge should sync.
     * Used to build the sync filter for multi-room mode.
     */
    fun getAllRoomIds(): List<String> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ROOM_ID),
            null, null, null, null, null
        )
        val ids = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(it.getColumnIndexOrThrow(COL_ROOM_ID)))
            }
        }
        return ids
    }

    /**
     * Remove a phone → room mapping.
     */
    fun removeMapping(phone: String) {
        writableDatabase.delete(TABLE_NAME, "$COL_PHONE = ?", arrayOf(phone))
        Log.d(TAG, "Removed mapping for $phone")
    }

    /**
     * Get the count of mapped rooms.
     */
    fun getMappingCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }
}