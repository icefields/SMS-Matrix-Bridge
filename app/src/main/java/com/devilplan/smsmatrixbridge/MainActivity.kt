package com.devilplan.smsmatrixbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var editHomeserver: EditText
    private lateinit var editAccessToken: EditText
    private lateinit var editRoomId: EditText
    private lateinit var switchEnabled: Switch
    private lateinit var checkboxReceiveOnly: CheckBox
    private lateinit var btnSave: Button
    private lateinit var btnTestConnection: Button
    private lateinit var textStatus: TextView

    // Scoped coroutine that gets cancelled when Activity is destroyed
    private var testJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editHomeserver = findViewById(R.id.edit_homeserver)
        editAccessToken = findViewById(R.id.edit_access_token)
        editRoomId = findViewById(R.id.edit_room_id)
        switchEnabled = findViewById(R.id.switch_enabled)
        checkboxReceiveOnly = findViewById(R.id.checkbox_receive_only)
        btnSave = findViewById(R.id.btn_save)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        textStatus = findViewById(R.id.text_status)

        Config.load(this)

        editHomeserver.setText(Config.homeserverUrl)
        editAccessToken.setText(Config.accessToken)
        editRoomId.setText(Config.roomId)
        switchEnabled.isChecked = Config.enabled
        checkboxReceiveOnly.isChecked = Config.receiveOnly

        btnSave.setOnClickListener { saveAndApply() }
        btnTestConnection.setOnClickListener { testConnection() }

        requestPermissions()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun saveAndApply() {
        Config.homeserverUrl = editHomeserver.text.toString().trim().trimEnd('/')
        Config.accessToken = editAccessToken.text.toString().trim()
        Config.roomId = editRoomId.text.toString().trim()
        Config.enabled = switchEnabled.isChecked
        Config.receiveOnly = checkboxReceiveOnly.isChecked
        Config.save(this)

        if (Config.enabled && Config.isConfigured()) {
            if (!Config.receiveOnly) {
                startSyncService()
            } else {
                stopSyncService()
            }
        } else {
            stopSyncService()
        }

        updateStatus()
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        btnSave.isEnabled = false
        btnTestConnection.isEnabled = false
        textStatus.text = "Testing connection..."

        // Cancel any previous test
        testJob?.cancel()
        testJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Config.homeserverUrl = editHomeserver.text.toString().trim().trimEnd('/')
                Config.accessToken = editAccessToken.text.toString().trim()

                val client = MatrixClient(this@MainActivity)
                val userId = client.getUserId()

                withContext(Dispatchers.Main) {
                    if (userId != null) {
                        textStatus.text = "✅ Connected as $userId"
                    } else {
                        textStatus.text = "❌ Connection failed — check URL and token"
                    }
                    btnSave.isEnabled = true
                    btnTestConnection.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    textStatus.text = "❌ Error: ${e.message}"
                    btnSave.isEnabled = true
                    btnTestConnection.isEnabled = true
                }
            }
        }
    }

    private fun startSyncService() {
        val intent = Intent(this, MatrixSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSyncService() {
        stopService(Intent(this, MatrixSyncService::class.java))
    }

    private fun updateStatus() {
        textStatus.text = when {
            !Config.isConfigured() -> "⚠️ Not configured"
            !Config.enabled -> "⏸ Bridge disabled"
            Config.receiveOnly -> "📥 Receive-only mode (SMS → Matrix)"
            else -> "🔄 Full bridge (SMS ↔ Matrix)"
        }
    }
}