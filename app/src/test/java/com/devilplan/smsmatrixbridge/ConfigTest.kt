package com.devilplan.smsmatrixbridge

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class ConfigTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        `when`(mockContext.getSharedPreferences("sms_matrix_bridge", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        `when`(mockEditor.apply()).then { }
    }

    // --- Defaults ---

    @Test
    fun `load defaults when nothing saved`() {
        `when`(mockPrefs.getString("homeserver_url", "")).thenReturn("")
        `when`(mockPrefs.getString("access_token", "")).thenReturn("")
        `when`(mockPrefs.getString("room_id", "")).thenReturn("")
        `when`(mockPrefs.getBoolean("enabled", false)).thenReturn(false)
        `when`(mockPrefs.getBoolean("receive_only", true)).thenReturn(true)

        Config.load(mockContext)

        assertEquals("", Config.homeserverUrl)
        assertEquals("", Config.accessToken)
        assertEquals("", Config.roomId)
        assertFalse(Config.enabled)
        assertTrue(Config.receiveOnly)
    }

    // --- Load saved values ---

    @Test
    fun `load saved values`() {
        `when`(mockPrefs.getString("homeserver_url", "")).thenReturn("https://matrix.example.com")
        `when`(mockPrefs.getString("access_token", "")).thenReturn("syt_abc123")
        `when`(mockPrefs.getString("room_id", "")).thenReturn("!room:example.com")
        `when`(mockPrefs.getBoolean("enabled", false)).thenReturn(true)
        `when`(mockPrefs.getBoolean("receive_only", true)).thenReturn(false)

        Config.load(mockContext)

        assertEquals("https://matrix.example.com", Config.homeserverUrl)
        assertEquals("syt_abc123", Config.accessToken)
        assertEquals("!room:example.com", Config.roomId)
        assertTrue(Config.enabled)
        assertFalse(Config.receiveOnly)
    }

    // --- isConfigured ---

    @Test
    fun `isConfigured returns true when all fields set`() {
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "syt_abc123"
        Config.roomId = "!room:example.com"
        assertTrue(Config.isConfigured())
    }

    @Test
    fun `isConfigured returns false when homeserverUrl empty`() {
        Config.homeserverUrl = ""
        Config.accessToken = "syt_abc123"
        Config.roomId = "!room:example.com"
        assertFalse(Config.isConfigured())
    }

    @Test
    fun `isConfigured returns false when accessToken empty`() {
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = ""
        Config.roomId = "!room:example.com"
        assertFalse(Config.isConfigured())
    }

    @Test
    fun `isConfigured returns false when roomId empty`() {
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "syt_abc123"
        Config.roomId = ""
        assertFalse(Config.isConfigured())
    }

    @Test
    fun `isConfigured returns false when all fields empty`() {
        Config.homeserverUrl = ""
        Config.accessToken = ""
        Config.roomId = ""
        assertFalse(Config.isConfigured())
    }

    @Test
    fun `isConfigured returns false with whitespace-only fields`() {
        Config.homeserverUrl = "   "
        Config.accessToken = "   "
        Config.roomId = "   "
        assertFalse(Config.isConfigured())
    }

    // --- Save persistence ---

    @Test
    fun `save persists all fields including receiveOnly`() {
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "syt_abc123"
        Config.roomId = "!room:example.com"
        Config.enabled = true
        Config.receiveOnly = false

        Config.save(mockContext)

        verify(mockEditor).putString("homeserver_url", "https://matrix.example.com")
        verify(mockEditor).putString("access_token", "syt_abc123")
        verify(mockEditor).putString("room_id", "!room:example.com")
        verify(mockEditor).putBoolean("enabled", true)
        verify(mockEditor).putBoolean("receive_only", false)
        verify(mockEditor).apply()
    }

    @Test
    fun `save persists receiveOnly as true`() {
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "token"
        Config.roomId = "!room:example.com"
        Config.enabled = true
        Config.receiveOnly = true

        Config.save(mockContext)

        verify(mockEditor).putBoolean("receive_only", true)
    }

    // --- Thread safety (basic smoke test) ---

    @Test
    fun `volatile fields are directly readable`() {
        Config.homeserverUrl = "https://test.com"
        Config.accessToken = "token123"
        Config.roomId = "!abc:test.com"
        Config.enabled = true
        Config.receiveOnly = false

        // Volatile fields should be immediately visible
        assertEquals("https://test.com", Config.homeserverUrl)
        assertEquals("token123", Config.accessToken)
        assertEquals("!abc:test.com", Config.roomId)
        assertTrue(Config.enabled)
        assertFalse(Config.receiveOnly)
    }

    // --- URL edge cases ---

    @Test
    fun `homeserverUrl with trailing slash should be trimmed by caller`() {
        Config.homeserverUrl = "https://matrix.example.com/"
        val url = Config.homeserverUrl.trimEnd('/')
        assertEquals("https://matrix.example.com", url)
    }

    @Test
    fun `homeserverUrl with path should work`() {
        Config.homeserverUrl = "https://matrix.example.com:8448"
        Config.accessToken = "token"
        Config.roomId = "!room:example.com"
        assertTrue(Config.isConfigured())
    }

    // --- Multi-room configuration ---

    @Test
    fun `isMultiRoomConfigured returns true when multiRoom enabled and spaceId set`() {
        Config.multiRoom = true
        Config.spaceId = "!space:example.com"
        assertTrue(Config.isMultiRoomConfigured())
    }

    @Test
    fun `isMultiRoomConfigured returns false when multiRoom disabled`() {
        Config.multiRoom = false
        Config.spaceId = "!space:example.com"
        assertFalse(Config.isMultiRoomConfigured())
    }

    @Test
    fun `isMultiRoomConfigured returns false when spaceId empty`() {
        Config.multiRoom = true
        Config.spaceId = ""
        assertFalse(Config.isMultiRoomConfigured())
    }

    @Test
    fun `isMultiRoomConfigured returns false when both disabled`() {
        Config.multiRoom = false
        Config.spaceId = ""
        assertFalse(Config.isMultiRoomConfigured())
    }

    @Test
    fun `isMultiRoomConfigured returns false when spaceId is whitespace`() {
        Config.multiRoom = true
        Config.spaceId = "   "
        assertFalse(Config.isMultiRoomConfigured())
    }

    @Test
    fun `save persists multiRoom and spaceId`() {
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "token"
        Config.roomId = "!room:example.com"
        Config.multiRoom = true
        Config.spaceId = "!space:example.com"

        Config.save(mockContext)

        verify(mockEditor).putBoolean("multi_room", true)
        verify(mockEditor).putString("space_id", "!space:example.com")
    }

    @Test
    fun `load defaults multiRoom to false and spaceId to empty`() {
        `when`(mockPrefs.getString("homeserver_url", "")).thenReturn("")
        `when`(mockPrefs.getString("access_token", "")).thenReturn("")
        `when`(mockPrefs.getString("room_id", "")).thenReturn("")
        `when`(mockPrefs.getBoolean("enabled", false)).thenReturn(false)
        `when`(mockPrefs.getBoolean("receive_only", true)).thenReturn(true)
        `when`(mockPrefs.getString("since_batch", null)).thenReturn(null)
        `when`(mockPrefs.getBoolean("multi_room", false)).thenReturn(false)
        `when`(mockPrefs.getString("space_id", "")).thenReturn("")

        Config.load(mockContext)

        assertFalse(Config.multiRoom)
        assertEquals("", Config.spaceId)
    }

    // --- sinceBatch persistence edge cases ---

    @Test
    fun `sinceBatch null is preserved after load`() {
        `when`(mockPrefs.getString("since_batch", null)).thenReturn(null)

        Config.load(mockContext)

        assertNull(Config.sinceBatch)
    }

    @Test
    fun `sinceBatch empty string is treated as null after load`() {
        `when`(mockPrefs.getString("since_batch", null)).thenReturn("")

        Config.load(mockContext)

        // Empty string should be treated as null to prevent invalid sync URL
        assertNull(Config.sinceBatch)
    }

    @Test
    fun `sinceBatch valid token is preserved after load`() {
        `when`(mockPrefs.getString("since_batch", null)).thenReturn("s12345_67890")

        Config.load(mockContext)

        assertEquals("s12345_67890", Config.sinceBatch)
    }

    @Test
    fun `sinceBatch null saves as empty string`() {
        Config.sinceBatch = null
        Config.save(mockContext)

        verify(mockEditor).putString("since_batch", "")
    }

    @Test
    fun `sinceBatch valid token saves correctly`() {
        Config.sinceBatch = "s12345_67890"
        Config.save(mockContext)

        verify(mockEditor).putString("since_batch", "s12345_67890")
    }
}