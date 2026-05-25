package com.devilplan.smsmatrixbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

/**
 * Foreground service that long-polls Matrix /sync for incoming messages.
 * When a message with "!sms" prefix is found, sends it as SMS.
 */
class MatrixSyncService : Service() {

    companion object {
        private const val TAG = "MatrixSyncService"
        private const val CHANNEL_ID = "sms_matrix_bridge_sync"
        private const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Config.load(this)

        // Stop if disabled, not configured, or receive-only mode
        if (!Config.enabled || !Config.isConfigured() || Config.receiveOnly) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS-Matrix Bridge")
            .setContentText("Listening for Matrix messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            // Fetch own user ID first so we can skip our own messages
            val matrixClient = MatrixClient(this@MatrixSyncService)
            matrixClient.getUserId()

            var sinceBatch: String? = null

            // Initial sync — skip historical messages
            sinceBatch = matrixClient.pollForMessages(null)

            while (isActive) {
                // Re-check config in case user changed settings
                Config.load(this@MatrixSyncService)
                if (!Config.enabled || Config.receiveOnly) {
                    Log.i(TAG, "Bridge disabled or switched to receive-only, stopping sync")
                    break
                }

                try {
                    sinceBatch = matrixClient.pollForMessages(sinceBatch)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Sync error, retrying in 10s", e)
                    delay(10000)
                }
            }

            // Exit cleanly if loop ends
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        syncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS-Matrix Bridge Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Matrix sync running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}