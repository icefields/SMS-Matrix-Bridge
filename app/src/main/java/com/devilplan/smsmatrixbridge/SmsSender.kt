package com.devilplan.smsmatrixbridge

import android.content.Context
import android.os.Build
import android.telephony.SmsManager as AndroidSmsManager
import android.util.Log

/**
 * Wrapper around Android SmsManager for sending SMS messages.
 * Uses the modern getSystemService API on API 31+.
 */
class SmsSender(private val appContext: Context) {

    companion object {
        private const val TAG = "SmsSender"
    }

    private val smsManager: AndroidSmsManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(AndroidSmsManager::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            AndroidSmsManager.getDefault()
        }
    }

    fun sendSms(phoneNumber: String, message: String) {
        try {
            val parts = smsManager.divideMessage(message)

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }

            Log.i(TAG, "SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
        }
    }
}