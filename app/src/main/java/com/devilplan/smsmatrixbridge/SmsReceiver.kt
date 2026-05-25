package com.devilplan.smsmatrixbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Intercepts incoming SMS and forwards them to Matrix.
 * Uses the shared MatrixClientHolder OkHttpClient to avoid leaks.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsMatrixReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        Config.load(context)
        if (!Config.enabled || !Config.isConfigured()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        // Group by originating address (sender)
        val grouped = messages.groupBy { it.originatingAddress ?: "Unknown" }

        val matrixClient = MatrixClient(context)

        for ((sender, msgs) in grouped) {
            val body = msgs.joinToString("") { it.messageBody ?: "" }
            Log.i(TAG, "SMS from $sender: $body")
            matrixClient.sendSmsNotice(sender, body)
        }
    }
}