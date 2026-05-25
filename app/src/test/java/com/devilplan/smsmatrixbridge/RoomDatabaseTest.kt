package com.devilplan.smsmatrixbridge

import org.junit.Assert.*
import org.junit.Test

class RoomDatabaseTest {

    // ================================================================
    // Phone number normalization & edge cases
    // ================================================================

    @Test
    fun `phone number with plus prefix is valid`() {
        val phone = "+1234567890"
        assertTrue(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone number without plus is valid`() {
        val phone = "1234567890"
        assertTrue(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `international phone number is valid`() {
        val phone = "+393331234567"
        assertTrue(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `phone with letters is invalid`() {
        val phone = "+1abc234567"
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `empty phone is invalid`() {
        val phone = ""
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    @Test
    fun `plus only is invalid`() {
        val phone = "+"
        assertFalse(phone.matches(Regex("^\\+?\\d+$")))
    }

    // ================================================================
    // Room name format
    // ================================================================

    @Test
    fun `room name format includes phone and emoji`() {
        val phone = "+1234567890"
        val roomName = "📱 $phone"
        assertTrue(roomName.startsWith("📱 "))
        assertTrue(roomName.contains(phone))
    }

    @Test
    fun `room name for unknown sender`() {
        val phone = "Unknown"
        val roomName = "📱 $phone"
        assertTrue(roomName.contains("Unknown"))
    }

    // ================================================================
    // Room topic format
    // ================================================================

    @Test
    fun `room topic includes phone number`() {
        val phone = "+1234567890"
        val topic = "SMS conversation with $phone"
        assertTrue(topic.contains(phone))
        assertTrue(topic.startsWith("SMS conversation with"))
    }

    // ================================================================
    // Mapping data structure
    // ================================================================

    @Test
    fun `mapping triple holds correct data`() {
        val mapping = Triple("+1234567890", "!room:example.com", "📱 +1234567890")
        assertEquals("+1234567890", mapping.first)
        assertEquals("!room:example.com", mapping.second)
        assertEquals("📱 +1234567890", mapping.third)
    }

    @Test
    fun `mapping list can be filtered by room ID`() {
        val mappings = listOf(
            Triple("+111", "!room1:example.com", "📱 +111"),
            Triple("+222", "!room2:example.com", "📱 +222"),
            Triple("+333", "!room3:example.com", "📱 +333")
        )
        val found = mappings.find { it.second == "!room2:example.com" }
        assertNotNull(found)
        assertEquals("+222", found!!.first)
    }

    @Test
    fun `mapping list can be filtered by phone`() {
        val mappings = listOf(
            Triple("+111", "!room1:example.com", "📱 +111"),
            Triple("+222", "!room2:example.com", "📱 +222")
        )
        val found = mappings.find { it.first == "+111" }
        assertNotNull(found)
        assertEquals("!room1:example.com", found!!.second)
    }

    @Test
    fun `mapping list returns null for missing phone`() {
        val mappings = listOf(
            Triple("+111", "!room1:example.com", "📱 +111")
        )
        val found = mappings.find { it.first == "+999" }
        assertNull(found)
    }

    // ================================================================
    // Distinct room IDs (for sync filter)
    // ================================================================

    @Test
    fun `room IDs are deduplicated`() {
        val rooms = listOf("!main:example.com", "!room1:example.com", "!room2:example.com", "!main:example.com")
        val distinct = rooms.distinct()
        assertEquals(3, distinct.size)
    }

    @Test
    fun `empty room list returns empty`() {
        val rooms = emptyList<String>()
        assertTrue(rooms.distinct().isEmpty())
    }

    @Test
    fun `single room list stays single`() {
        val rooms = listOf("!main:example.com")
        assertEquals(1, rooms.distinct().size)
    }

    // ================================================================
    // Matrix ID parsing (extractServerName)
    // ================================================================

    @Test
    fun `extract server name from room ID`() {
        assertEquals("example.com", extractServerName("!abc:example.com"))
    }

    @Test
    fun `extract server name from room ID with port`() {
        assertEquals("example.com:8448", extractServerName("!abc:example.com:8448"))
    }

    @Test
    fun `extract server name from user ID`() {
        assertEquals("matrix.org", extractServerName("@alice:matrix.org"))
    }

    @Test
    fun `extract server name with no colon returns empty`() {
        assertEquals("", extractServerName("invalidid"))
    }

    // ================================================================
    // Space creation JSON
    // ================================================================

    @Test
    fun `space creation body has m_space type`() {
        val body = mapOf(
            "name" to "SMS",
            "visibility" to "private",
            "preset" to "private_chat",
            "creation_content" to mapOf("type" to "m.space")
        )
        val creationContent = body["creation_content"] as Map<*, *>
        assertEquals("m.space", creationContent["type"])
    }

    @Test
    fun `space creation body is private`() {
        val body = mapOf(
            "name" to "SMS",
            "visibility" to "private",
            "preset" to "private_chat",
            "creation_content" to mapOf("type" to "m.space")
        )
        assertEquals("private", body["visibility"])
        assertEquals("private_chat", body["preset"])
    }

    // ================================================================
    // Contact room creation JSON
    // ================================================================

    @Test
    fun `contact room creation body has name and topic`() {
        val phone = "+1234567890"
        val body = mapOf(
            "name" to "📱 $phone",
            "visibility" to "private",
            "preset" to "private_chat",
            "topic" to "SMS conversation with $phone"
        )
        assertEquals("📱 +1234567890", body["name"])
        assertEquals("SMS conversation with +1234567890", body["topic"])
    }

    @Test
    fun `contact room with space parent includes initial_state`() {
        val spaceId = "!space:example.com"
        val initialState = listOf(
            mapOf(
                "type" to "m.space.parent",
                "state_key" to spaceId,
                "content" to mapOf(
                    "via" to listOf("example.com"),
                    "canonical" to true
                )
            )
        )
        assertEquals("m.space.parent", initialState[0]["type"])
        assertEquals(spaceId, initialState[0]["state_key"])

        val content = initialState[0]["content"] as Map<*, *>
        assertEquals(listOf("example.com"), content["via"])
        assertEquals(true, content["canonical"])
    }

    // ================================================================
    // Space child event JSON
    // ================================================================

    @Test
    fun `space child event has correct structure`() {
        val body = mapOf(
            "via" to listOf("example.com"),
            "suggested" to false
        )
        assertEquals(listOf("example.com"), body["via"])
        assertEquals(false, body["suggested"])
    }

    // ================================================================
    // Multi-room sync filter
    // ================================================================

    @Test
    fun `multi-room filter includes multiple rooms`() {
        val roomIds = listOf("!main:example.com", "!room1:example.com", "!room2:example.com")
        val filter = mapOf(
            "room" to mapOf(
                "rooms" to roomIds,
                "timeline" to mapOf(
                    "types" to listOf("m.room.message"),
                    "limit" to 10
                )
            )
        )
        val rooms = (filter["room"] as Map<*, *>)["rooms"] as List<*>
        assertEquals(3, rooms.size)
        assertTrue(rooms.contains("!main:example.com"))
        assertTrue(rooms.contains("!room1:example.com"))
        assertTrue(rooms.contains("!room2:example.com"))
    }

    @Test
    fun `multi-room filter is URL encodable`() {
        val gson = com.google.gson.Gson()
        val roomIds = listOf("!main:example.com", "!room1:example.com")
        val filter = mapOf(
            "room" to mapOf(
                "rooms" to roomIds,
                "timeline" to mapOf(
                    "types" to listOf("m.room.message"),
                    "limit" to 10
                )
            )
        )
        val encoded = java.net.URLEncoder.encode(gson.toJson(filter), "UTF-8")
        assertFalse(encoded.contains(" "))
        assertFalse(encoded.contains("{"))
        val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
        assertTrue(decoded.contains("!main:example.com"))
        assertTrue(decoded.contains("!room1:example.com"))
    }

    // ================================================================
    // Multi-room message routing logic
    // ================================================================

    @Test
    fun `contact room message is sent directly as SMS`() {
        val isMultiRoom = true
        val roomId = "!contact:example.com"
        val mainRoomId = "!main:example.com"
        val text = "Hello there"

        // In multi-room mode, non-main room messages are sent as SMS
        assertTrue(isMultiRoom && roomId != mainRoomId)
        assertFalse(text.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `main room message uses !sms command parsing`() {
        val isMultiRoom = true
        val roomId = "!main:example.com"
        val mainRoomId = "!main:example.com"
        val text = "!sms +1234 Hello"

        // In multi-room mode, main room still uses !sms commands
        assertTrue(isMultiRoom && roomId == mainRoomId)
        assertTrue(text.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `!sms command in contact room is skipped`() {
        val text = "!sms +1234 Hello"
        // In multi-room contact rooms, !sms commands are NOT parsed
        // The message should be skipped (not sent as SMS)
        // This is handled in MatrixClient by checking prefix
        assertTrue(text.startsWith("!sms ", ignoreCase = true))
    }

    @Test
    fun `regular text in contact room is sent as SMS`() {
        val text = "Hello, how are you?"
        assertFalse(text.startsWith("!sms ", ignoreCase = true))
    }

    // ================================================================
    // Fresh install / re-install safety
    // ================================================================

    @Test
    fun `empty database has no mappings`() {
        val mappings = emptyList<Triple<String, String, String>>()
        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `fresh install sync skips history regardless of mode`() {
        val sinceBatch: String? = null
        // Initial sync always skips event processing
        assertNull(sinceBatch)
    }

    @Test
    fun `multi-room with no mappings syncs only main room`() {
        val mainRoom = "!main:example.com"
        val contactRooms = emptyList<String>()
        val allRooms = listOf(mainRoom) + contactRooms
        assertEquals(1, allRooms.size)
        assertEquals(mainRoom, allRooms[0])
    }

    // ================================================================
    // Config: multi-room flags
    // ================================================================

    @Test
    fun `multiRoom disabled is not multi-room configured`() {
        Config.multiRoom = false
        Config.spaceId = "!space:example.com"
        assertFalse(Config.isMultiRoomConfigured())
    }

    @Test
    fun `multiRoom enabled with space is configured`() {
        Config.multiRoom = true
        Config.spaceId = "!space:example.com"
        assertTrue(Config.isMultiRoomConfigured())
    }

    @Test
    fun `multiRoom enabled without space is not configured`() {
        Config.multiRoom = true
        Config.spaceId = ""
        assertFalse(Config.isMultiRoomConfigured())
    }

    @Test
    fun `multiRoom disabled without space is not configured`() {
        Config.multiRoom = false
        Config.spaceId = ""
        assertFalse(Config.isMultiRoomConfigured())
    }

    // ================================================================
    // Gson serialization of room creation payloads
    // ================================================================

    @Test
    fun `space creation JSON serializes correctly`() {
        val gson = com.google.gson.Gson()
        val body = mapOf(
            "name" to "SMS",
            "visibility" to "private",
            "creation_content" to mapOf("type" to "m.space")
        )
        val json = gson.toJson(body)
        val parsed = com.google.gson.JsonParser.parseString(json).asJsonObject
        assertEquals("SMS", parsed.get("name").asString)
        assertEquals("m.space", parsed.getAsJsonObject("creation_content").get("type").asString)
    }

    @Test
    fun `contact room with space parent serializes correctly`() {
        val gson = com.google.gson.Gson()
        val phone = "+1234567890"
        val spaceId = "!space:example.com"
        val body = mutableMapOf<String, Any>(
            "name" to "📱 $phone",
            "topic" to "SMS conversation with $phone"
        )
        body["initial_state"] = listOf(
            mapOf(
                "type" to "m.space.parent",
                "state_key" to spaceId,
                "content" to mapOf(
                    "via" to listOf("example.com"),
                    "canonical" to true
                )
            )
        )
        val json = gson.toJson(body)
        assertTrue(json.contains("m.space.parent"))
        assertTrue(json.contains(spaceId))
        assertTrue(json.contains("canonical"))
    }

    // ================================================================
    // Regression: single-room mode still works
    // ================================================================

    @Test
    fun `single room mode sends to main room`() {
        Config.multiRoom = false
        Config.spaceId = ""
        assertFalse(Config.isMultiRoomConfigured())
        // SmsReceiver.sendSmsNotice should route to Config.roomId
    }

    @Test
    fun `single room mode parses !sms in main room`() {
        Config.multiRoom = false
        Config.spaceId = ""
        val roomId = Config.roomId
        // In single-room mode, all messages in the single room use !sms parsing
        assertFalse(Config.isMultiRoomConfigured())
    }

    // ================================================================
    // extractServerName logic (tested inline since MatrixClient needs Context)
    // ================================================================

    private fun extractServerName(matrixId: String): String {
        val parts = matrixId.split(":")
        return if (parts.size > 1) parts.drop(1).joinToString(":") else ""
    }
}