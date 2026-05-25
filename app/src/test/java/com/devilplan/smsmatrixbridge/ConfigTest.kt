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
}