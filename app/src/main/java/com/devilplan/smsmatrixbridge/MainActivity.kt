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
    private lateinit var switchEnabled: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var checkboxReceiveOnly: CheckBox
    private lateinit var checkboxMultiRoom: CheckBox
    private lateinit var editSpaceId: EditText
    private lateinit var btnCreateSpace: Button
    private lateinit var btnSave: Button
    private lateinit var btnTestConnection: Button
    private lateinit var textStatus: TextView
    private lateinit var textContactCount: TextView

    private var testJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editHomeserver = findViewById(R.id.edit_homeserver)
        editAccessToken = findViewById(R.id.edit_access_token)
        editRoomId = findViewById(R.id.edit_room_id)
        switchEnabled = findViewById(R.id.switch_enabled)
        checkboxReceiveOnly = findViewById(R.id.checkbox_receive_only)
        checkboxMultiRoom = findViewById(R.id.checkbox_multi_room)
        editSpaceId = findViewById(R.id.edit_space_id)
        btnCreateSpace = findViewById(R.id.btn_create_space)
        btnSave = findViewById(R.id.btn_save)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        textStatus = findViewById(R.id.text_status)
        textContactCount = findViewById(R.id.text_contact_count)

        Config.load(this)

        editHomeserver.setText(Config.homeserverUrl)
        editAccessToken.setText(Config.accessToken)
        editRoomId.setText(Config.roomId)
        switchEnabled.isChecked = Config.enabled
        checkboxReceiveOnly.isChecked = Config.receiveOnly
        checkboxMultiRoom.isChecked = Config.multiRoom
        editSpaceId.setText(Config.spaceId)

        updateSpaceIdVisibility()

        checkboxMultiRoom.setOnCheckedChangeListener { _, _ -> updateSpaceIdVisibility() }
        btnCreateSpace.setOnClickListener { createSpace() }
        btnSave.setOnClickListener { saveAndApply() }
        btnTestConnection.setOnClickListener { testConnection() }

        requestPermissions()
        updateStatus()
        updateContactCount()
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
    }

    private fun updateSpaceIdVisibility() {
        val multiRoomEnabled = checkboxMultiRoom.isChecked
        editSpaceId.visibility = if (multiRoomEnabled) EditText.VISIBLE else EditText.GONE
        btnCreateSpace.visibility = if (multiRoomEnabled) Button.VISIBLE else Button.GONE
        findViewById<TextView>(R.id.label_space_id).visibility = if (multiRoomEnabled) TextView.VISIBLE else TextView.GONE
    }

    private fun createSpace() {
        btnCreateSpace.isEnabled = false
        btnCreateSpace.text = "Creating..."

        testJob?.cancel()
        testJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Config.homeserverUrl = editHomeserver.text.toString().trim().trimEnd('/')
                Config.accessToken = editAccessToken.text.toString().trim()

                val client = MatrixClient(this@MainActivity)
                val spaceId = client.createSpace("SMS")

                withContext(Dispatchers.Main) {
                    if (spaceId != null) {
                        editSpaceId.setText(spaceId)
                        Config.spaceId = spaceId
                        Config.save(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Space created: $spaceId", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to create space", Toast.LENGTH_LONG).show()
                    }
                    btnCreateSpace.isEnabled = true
                    btnCreateSpace.text = "Create SMS Space"
                    updateStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    btnCreateSpace.isEnabled = true
                    btnCreateSpace.text = "Create SMS Space"
                }
            }
        }
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
        Config.multiRoom = checkboxMultiRoom.isChecked
        Config.spaceId = editSpaceId.text.toString().trim()
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
        updateContactCount()
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        btnSave.isEnabled = false
        btnTestConnection.isEnabled = false
        textStatus.text = "Testing connection..."

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
            Config.receiveOnly && Config.isMultiRoomConfigured() -> "📥 Receive-only + multi-room (SMS → per-contact rooms)"
            Config.receiveOnly -> "📥 Receive-only mode (SMS → Matrix)"
            Config.isMultiRoomConfigured() -> "🔄 Full bridge + multi-room (SMS ↔ Matrix, per-contact rooms)"
            else -> "🔄 Full bridge (SMS ↔ Matrix)"
        }
    }

    private fun updateContactCount() {
        if (Config.isMultiRoomConfigured()) {
            val db = RoomDatabase(this)
            val count = db.getMappingCount()
            textContactCount.text = "📱 $count contact room(s) mapped"
            textContactCount.visibility = TextView.VISIBLE
        } else {
            textContactCount.visibility = TextView.GONE
        }
    }
}