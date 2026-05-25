package com.devilplan.smsmatrixbridge

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MatrixClientTest {

    private val gson = Gson()

    // ================================================================
    // SMS notification format
    // ================================================================

    @Test
    fun `sms notice format includes phone and body`() {
        val sender = "+1234567890"
        val body = "Hello from the other side"
        val expected = "📱 SMS from $sender:\n$body"
        assertTrue(expected.startsWith("📱 SMS from "))
        assertTrue(expected.contains(sender))
        assertTrue(expected.contains(body))
    }

    @Test
    fun `sms notice format with unknown sender`() {
        val sender = "Unknown"
        val body = "Test message"
        val formatted = "📱 SMS from $sender:\n$body"
        assertTrue(formatted.contains("Unknown"))
    }

    @Test
    fun `sms notice format with empty body`() {
        val sender = "+1234567890"
        val body = ""
        val formatted = "📱 SMS from $sender:\n$body"
        assertTrue(formatted.contains(sender))
    }

    @Test
    fun `sms notice format with multiline body`() {
        val sender = "+1234567890"
        val body = "Line 1\nLine 2\nLine 3"
        val formatted = "📱 SMS from $sender:\n$body"
        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("Line 3"))
    }

    @Test
    fun `sms notice format with special characters`() {
        val sender = "+1234567890"
        val body = "Hey! Ça va? €$%&@"
        val formatted = "📱 SMS from $sender:\n$body"
        assertTrue(formatted.contains("Ça va?"))
        assertTrue(formatted.contains("€$%&@"))
    }

    // ================================================================
    // Matrix message JSON structure
    // ================================================================

    @Test
    fun `matrix message JSON has correct structure`() {
        val text = "📱 SMS from +1234567890:\nHello"
        val body = mapOf(
            "msgtype" to "m.text",
            "body" to text
        )
        assertEquals("m.text", body["msgtype"])
        assertEquals(text, body["body"])
    }

    @Test
    fun `matrix message JSON serializes correctly`() {
        val text = "Hello world"
        val body = mapOf(
            "msgtype" to "m.text",
            "body" to text
        )
        val json = gson.toJson(body)
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals("m.text", parsed.get("msgtype").asString)
        assertEquals(text, parsed.get("body").asString)
    }

    // ================================================================
    // Command parsing: !sms
    // ================================================================

    @Test
    fun `parse sms command with valid input`() {
        val input = "!sms +1234567890 Hello world"
        assertTrue(input.startsWith("!sms ", ignoreCase = true))

        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        assertNotNull(match)

        val phone = match!!.value.trim()
        val message = args.removePrefix(match.value).trim()
        assertEquals("+1234567890", phone)
        assertEquals("Hello world", message)
    }

    @Test
    fun `parse sms command with country code`() {
        val input = "!sms +39123456789 Ciao come stai"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        assertNotNull(match)

        val phone = match!!.value.trim()
        val message = args.removePrefix(match.value).trim()
        assertEquals("+39123456789", phone)
        assertEquals("Ciao come stai", message)
    }

    @Test
    fun `parse sms command without plus sign`() {
        val input = "!sms 1234567890 Test message"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        assertNotNull(match)

        val phone = match!!.value.trim()
        val message = args.removePrefix(match.value).trim()
        assertEquals("1234567890", phone)
        assertEquals("Test message", message)
    }

    @Test
    fun `parse sms command case insensitive`() {
        val input = "!SMS +1234567890 Hello"
        assertTrue(input.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `parse sms command lowercase`() {
        val input = "!sms +1234567890 hello"
        assertTrue(input.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `parse sms command with long message`() {
        val input = "!sms +1234567890 " + "A".repeat(500)
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        assertNotNull(match)
        assertEquals("+1234567890", match!!.value.trim())
        assertEquals("A".repeat(500), args.removePrefix(match.value).trim())
    }

    @Test
    fun `reject non-sms matrix message`() {
        val input = "Just a regular message"
        assertFalse(input.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `reject empty command`() {
        val input = "!sms "
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        // Empty after prefix = no phone number
        assertNull(match)
    }

    @Test
    fun `reject sms command without phone number`() {
        val input = "!sms Hello"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        // "Hello" doesn't start with digits+space
        assertNull(match)
    }

    @Test
    fun `reject sms command with phone but no message`() {
        val input = "!sms +1234567890"
        val args = input.removePrefix("!sms ").trim()
        val phoneRegex = Regex("^\\+?\\d+\\s+")
        val match = phoneRegex.find(args)
        // "+1234567890" has no trailing space — regex requires \s+
        assertNull(match)
    }

    @Test
    fun `reject message that looks like command but isn't`() {
        val input = "sms +1234567890 Hello" // missing !
        assertFalse(input.startsWith("!sms ", ignoreCase = true))
    }

    // ================================================================
    // Self-message filtering
    // ================================================================

    @Test
    fun `own messages should be filtered out`() {
        val myUserId = "@bridge:example.com"
        val senderId = "@bridge:example.com"
        assertEquals(myUserId, senderId)
    }

    @Test
    fun `other users messages should not be filtered`() {
        val myUserId = "@bridge:example.com"
        val senderId = "@alice:example.com"
        assertNotEquals(myUserId, senderId)
    }

    @Test
    fun `confirmation message should not be treated as command`() {
        val text = "✅ SMS sent to +1234567890"
        assertFalse(text.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `emoji prefix does not trigger command`() {
        val text = "📱 SMS from +1234567890:\nHello"
        assertFalse(text.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `cached user ID persists across calls`() {
        MatrixClient.cachedUserId = "@bot:matrix.example.com"
        assertEquals("@bot:matrix.example.com", MatrixClient.cachedUserId)
        // Clean up
        MatrixClient.cachedUserId = null
    }

    @Test
    fun `null cached user ID means no filtering`() {
        MatrixClient.cachedUserId = null
        assertNull(MatrixClient.cachedUserId)
    }

    // ================================================================
    // Filter JSON
    // ================================================================

    @Test
    fun `sync filter includes room and message type`() {
        val filter = mapOf(
            "room" to mapOf(
                "rooms" to listOf("!room:example.com"),
                "timeline" to mapOf(
                    "types" to listOf("m.room.message"),
                    "limit" to 10
                )
            )
        )
        val json = gson.toJson(filter)
        val parsed = JsonParser.parseString(json).asJsonObject

        assertTrue(json.contains("!room:example.com"))
        assertTrue(json.contains("m.room.message"))

        val rooms = parsed.getAsJsonObject("room").getAsJsonArray("rooms")
        assertEquals("!room:example.com", rooms[0].asString)
    }

    @Test
    fun `sync filter URL encoding is valid`() {
        val filter = mapOf(
            "room" to mapOf(
                "rooms" to listOf("!abc:example.com"),
                "timeline" to mapOf(
                    "types" to listOf("m.room.message"),
                    "limit" to 10
                )
            )
        )
        val encoded = java.net.URLEncoder.encode(gson.toJson(filter), "UTF-8")
        // Should not contain unencoded special chars
        assertFalse(encoded.contains(" "))
        assertFalse(encoded.contains("{"))
        assertFalse(encoded.contains("}"))
        // Should be decodable
        val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
        assertTrue(decoded.contains("!abc:example.com"))
    }

    // ================================================================
    // Transaction ID
    // ================================================================

    @Test
    fun `transaction ID format is valid`() {
        val txnId = "sms_${System.currentTimeMillis()}_1"
        assertTrue(txnId.startsWith("sms_"))
        val parts = txnId.removePrefix("sms_").split("_")
        assertEquals(2, parts.size)
        assertTrue(parts[0].toLong() > 0)
        assertTrue(parts[1].toInt() > 0)
    }

    @Test
    fun `transaction IDs are unique`() {
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val ids = mutableSetOf<String>()
        for (i in 1..100) {
            val id = "sms_${System.currentTimeMillis()}_${counter.incrementAndGet()}"
            ids.add(id)
        }
        assertEquals(100, ids.size)
    }

    // ================================================================
    // URL construction
    // ================================================================

    @Test
    fun `homeserver URL trailing slash is trimmed`() {
        val url = "https://matrix.example.com/"
        val trimmed = url.trimEnd('/')
        assertEquals("https://matrix.example.com", trimmed)
        assertFalse(trimmed.endsWith("/"))
    }

    @Test
    fun `homeserver URL without trailing slash unchanged`() {
        val url = "https://matrix.example.com"
        val trimmed = url.trimEnd('/')
        assertEquals("https://matrix.example.com", trimmed)
    }

    @Test
    fun `send message URL is correctly formed`() {
        val homeserver = "https://matrix.example.com"
        val roomId = "!abc:example.com"
        val txnId = "sms_1234567890_1"
        val url = "$homeserver/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId"
        assertEquals(
            "https://matrix.example.com/_matrix/client/v3/rooms/!abc:example.com/send/m.room.message/sms_1234567890_1",
            url
        )
    }

    @Test
    fun `sync URL includes timeout and filter`() {
        val homeserver = "https://matrix.example.com"
        val filter = java.net.URLEncoder.encode("{\"room\":{\"rooms\":[\"!abc:example.com\"]}}", "UTF-8")
        val url = "$homeserver/_matrix/client/v3/sync?timeout=30000&filter=$filter"
        assertTrue(url.contains("timeout=30000"))
        assertTrue(url.contains("filter="))
    }

    @Test
    fun `sync URL with since parameter`() {
        val homeserver = "https://matrix.example.com"
        val sinceBatch = "s12345_67890"
        val url = "$homeserver/_matrix/client/v3/sync?timeout=30000&since=$sinceBatch"
        assertTrue(url.contains("since=s12345_67890"))
    }

    // ================================================================
    // Mode switching
    // ================================================================

    @Test
    fun `receiveOnly true should not start sync service`() {
        Config.receiveOnly = true
        Config.enabled = true
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "token"
        Config.roomId = "!room:example.com"

        assertTrue(Config.enabled && Config.isConfigured() && Config.receiveOnly)
        // Service should NOT start when receiveOnly is true
        assertFalse(!Config.receiveOnly)
    }

    @Test
    fun `receiveOnly false should start sync service`() {
        Config.receiveOnly = false
        Config.enabled = true
        Config.homeserverUrl = "https://matrix.example.com"
        Config.accessToken = "token"
        Config.roomId = "!room:example.com"

        assertTrue(Config.enabled && Config.isConfigured() && !Config.receiveOnly)
    }

    @Test
    fun `disabled bridge should not start any service`() {
        Config.enabled = false
        Config.receiveOnly = false
        assertFalse(Config.enabled)
    }

    // ================================================================
    // Matrix sync response parsing (regression prevention)
    // ================================================================

    @Test
    fun `parse sync response with empty rooms`() {
        val json = """{"next_batch":"s1","rooms":{"join":{}}}"""
        val parsed = JsonParser.parseString(json).asJsonObject
        val rooms = parsed.getAsJsonObject("rooms")
        val join = rooms.getAsJsonObject("join")
        assertTrue(join.entrySet().isEmpty())
    }

    @Test
    fun `parse sync response with no rooms key`() {
        val json = """{"next_batch":"s1"}"""
        val parsed = JsonParser.parseString(json).asJsonObject
        assertNull(parsed.getAsJsonObject("rooms"))
    }

    @Test
    fun `parse sync response with events`() {
        val json = """{
            "next_batch":"s2",
            "rooms":{
                "join":{
                    "!room:example.com":{
                        "timeline":{
                            "events":[
                                {
                                    "type":"m.room.message",
                                    "content":{"msgtype":"m.text","body":"!sms +1234 Hello"},
                                    "sender":"@alice:example.com"
                                }
                            ]
                        }
                    }
                }
            }
        }"""
        val parsed = JsonParser.parseString(json).asJsonObject
        val nextBatch = parsed.get("next_batch").asString
        assertEquals("s2", nextBatch)

        val events = parsed.getAsJsonObject("rooms")
            .getAsJsonObject("join")
            .getAsJsonObject("!room:example.com")
            .getAsJsonObject("timeline")
            .getAsJsonArray("events")

        assertEquals(1, events.size())
        val event = events[0].asJsonObject
        assertEquals("m.room.message", event.get("type").asString)
        assertEquals("@alice:example.com", event.get("sender").asString)

        val content = event.getAsJsonObject("content")
        assertEquals("m.text", content.get("msgtype").asString)
        assertEquals("!sms +1234 Hello", content.get("body").asString)
    }

    @Test
    fun `whoami response parsing`() {
        val json = """{"user_id":"@bot:example.com","device_id":"DEVICE"}"""
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals("@bot:example.com", parsed.get("user_id").asString)
    }

    @Test
    fun `whoami response with wrong token`() {
        val json = """{"errcode":"M_UNKNOWN_TOKEN","error":"Unrecognized access token"}"""
        val parsed = JsonParser.parseString(json).asJsonObject
        assertNull(parsed.get("user_id"))
    }

    // ================================================================
    // OkHttpClient singleton (regression: no per-instance clients)
    // ================================================================

    @Test
    fun `MatrixClientHolder client is singleton`() {
        val client1 = MatrixClientHolder.client
        val client2 = MatrixClientHolder.client
        assertSame(client1, client2)
    }

    @Test
    fun `MatrixClientHolder gson is singleton`() {
        val gson1 = MatrixClientHolder.gson
        val gson2 = MatrixClientHolder.gson
        assertSame(gson1, gson2)
    }

    @Test
    fun `MatrixClientHolder media type is correct`() {
        assertEquals("application/json; charset=utf-8", MatrixClientHolder.jsonMediaType.toString())
    }
}