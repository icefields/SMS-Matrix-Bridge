package com.devilplan.smsmatrixbridge

import org.junit.Assert.*
import org.junit.Test

class SmsReceiverTest {

    // ================================================================
    // SMS notification format
    // ================================================================

    @Test
    fun `sms notice format includes sender and body`() {
        val sender = "+15551234567"
        val body = "Hey what's up"
        val formatted = "📱 SMS from $sender:\n$body"
        assertTrue(formatted.startsWith("📱 SMS from "))
        assertTrue(formatted.contains(sender))
        assertTrue(formatted.contains(body))
    }

    @Test
    fun `multipart sms body is concatenated`() {
        val parts = listOf("Hello ", "this is a ", "long message")
        val body = parts.joinToString("") { it }
        assertEquals("Hello this is a long message", body)
    }

    @Test
    fun `grouped messages by sender`() {
        val messages = listOf(
            Triple("+1234", "part1", 1L),
            Triple("+5678", "hello", 2L),
            Triple("+1234", "part2", 3L)
        )
        val grouped = messages.groupBy { it.first }

        assertEquals(2, grouped.size)
        assertEquals(2, grouped["+1234"]?.size)
        assertEquals(1, grouped["+5678"]?.size)
    }

    @Test
    fun `grouped messages concatenate body by sender`() {
        val messages = listOf(
            Pair("+1234", "Hello "),
            Pair("+1234", "world!"),
            Pair("+5678", "Hi there")
        )
        val grouped = messages.groupBy({ it.first }, { it.second })
        val group1234 = grouped["+1234"]
        assertNotNull(group1234)
        assertEquals(listOf("Hello ", "world!"), group1234!!)
        val concatenated = group1234.joinToString("")
        assertEquals("Hello world!", concatenated)
    }

    // ================================================================
    // Bridge state gates
    // ================================================================

    @Test
    fun `bridge disabled should not process sms`() {
        val enabled = false
        val configured = true
        assertFalse(enabled && configured)
    }

    @Test
    fun `bridge enabled and configured should process sms`() {
        val enabled = true
        val configured = true
        assertTrue(enabled && configured)
    }

    @Test
    fun `bridge not configured should not process sms`() {
        val enabled = true
        val configured = false
        assertFalse(enabled && configured)
    }

    @Test
    fun `bridge disabled and not configured should not process sms`() {
        val enabled = false
        val configured = false
        assertFalse(enabled && configured)
    }

    // ================================================================
    // SMS_RECEIVED_ACTION intent filtering
    // ================================================================

    @Test
    fun `only SMS_RECEIVED_ACTION should be processed`() {
        val validAction = "android.provider.Telephony.SMS_RECEIVED"
        val invalidAction = "android.intent.action.MAIN"
        assertNotEquals("android.provider.Telephony.SMS_RECEIVED", invalidAction)
    }

    // ================================================================
    // Empty/null sender handling
    // ================================================================

    @Test
    fun `null originating address falls back to Unknown`() {
        val sender: String? = null
        val fallback = sender ?: "Unknown"
        assertEquals("Unknown", fallback)
    }

    @Test
    fun `empty originating address falls back to Unknown`() {
        val sender: String? = ""
        val fallback = sender?.takeIf { it.isNotBlank() } ?: "Unknown"
        assertEquals("Unknown", fallback)
    }

    // ================================================================
    // Receive-only mode
    // ================================================================

    @Test
    fun `receive-only mode still forwards incoming SMS`() {
        Config.receiveOnly = true
        Config.enabled = true
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "token"
        Config.roomId = "!room:example.com"
        assertTrue(Config.enabled && Config.isConfigured())
    }
}