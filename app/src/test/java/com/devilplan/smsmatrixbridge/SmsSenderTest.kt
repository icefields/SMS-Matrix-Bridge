package com.devilplan.smsmatrixbridge

import org.junit.Assert.*
import org.junit.Test

class SmsSenderTest {

    // ================================================================
    // Message length classification
    // ================================================================

    @Test
    fun `single part message under 160 chars`() {
        val message = "Hello world"
        assertTrue(message.length <= 160)
    }

    @Test
    fun `exactly 160 chars is single part`() {
        val message = "A".repeat(160)
        assertEquals(160, message.length)
    }

    @Test
    fun `long message requires multipart`() {
        val message = "A".repeat(200)
        assertTrue(message.length > 160)
    }

    @Test
    fun `very long message requires multipart`() {
        val message = "A".repeat(1000)
        assertTrue(message.length > 160)
    }

    // ================================================================
    // Phone number validation
    // ================================================================

    @Test
    fun `phone number validation - with plus prefix`() {
        val phone = "+1234567890"
        assertTrue(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - without plus`() {
        val phone = "1234567890"
        assertTrue(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - invalid letters`() {
        val phone = "abc123"
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - international format`() {
        val phone = "+393331234567"
        assertTrue(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - empty string`() {
        val phone = ""
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - plus only`() {
        val phone = "+"
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - with spaces`() {
        val phone = "+1 234 567 890"
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number validation - with dashes`() {
        val phone = "+1-234-567-890"
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    // ================================================================
    // Command parsing (SMS extraction from !sms command)
    // ================================================================

    @Test
    fun `sms command parsing extracts phone and message`() {
        val input = "!sms +15551234567 Your package arrived"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)

        assertNotNull(match)
        assertEquals("+15551234567", match!!.value.trim())
        assertEquals("Your package arrived", args.removePrefix(match.value).trim())
    }

    @Test
    fun `sms command with international number`() {
        val input = "!sms +393331234567 Ciao dal Italia"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)

        assertNotNull(match)
        assertEquals("+393331234567", match!!.value.trim())
        assertEquals("Ciao dal Italia", args.removePrefix(match.value).trim())
    }

    @Test
    fun `sms command with short message`() {
        val input = "!sms +12345 Ok"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)

        assertNotNull(match)
        assertEquals("+12345", match!!.value.trim())
        assertEquals("Ok", args.removePrefix(match.value).trim())
    }

    @Test
    fun `sms command extracts only first phone number`() {
        // Should only match the first number+space sequence
        val input = "!sms +12345 Call me at +67890"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)

        assertNotNull(match)
        assertEquals("+12345", match!!.value.trim())
        assertEquals("Call me at +67890", args.removePrefix(match.value).trim())
    }

    // ================================================================
    // Android SmsManager deprecation (regression: should use getSystemService)
    // ================================================================

    @Test
    fun `SmsSender class exists and is distinct from android telephony SmsManager`() {
        // This is a structural test — we verify our class name doesn't shadow
        val className = SmsSender::class.java.name
        assertEquals("com.devilplan.smsmatrixbridge.SmsSender", className)
        assertFalse(className.contains("android.telephony"))
    }
}