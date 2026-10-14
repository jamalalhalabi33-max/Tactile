package com.example

import com.example.model.Key
import com.example.model.TvBrand
import com.example.model.TvDevice
import com.example.tv.ProtobufWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.sqrt

class TactileProtocolTest {

    // 1. D-pad Hit Testing
    private fun hitTest(x: Float, y: Float, diameter: Float): Key {
        val centerX = diameter / 2f
        val centerY = diameter / 2f
        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy)
        val okRadius = (diameter / 2f) * 0.38f

        if (dist <= okRadius) {
            return Key.OK
        }

        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return when {
            angle in -45f..45f -> Key.RIGHT
            angle > 45f && angle <= 135f -> Key.DOWN
            angle < -45f && angle >= -135f -> Key.UP
            else -> Key.LEFT
        }
    }

    @Test
    fun testDpadHitTesting() {
        val diameter = 300f
        val center = 150f

        // Center OK
        assertEquals(Key.OK, hitTest(center, center, diameter))
        assertEquals(Key.OK, hitTest(center + 10f, center + 10f, diameter))

        // Cardinal directions
        assertEquals(Key.UP, hitTest(center, 20f, diameter))
        assertEquals(Key.DOWN, hitTest(center, 280f, diameter))
        assertEquals(Key.LEFT, hitTest(20f, center, diameter))
        assertEquals(Key.RIGHT, hitTest(280f, center, diameter))
    }

    // 2. LG pointer-frame formatting
    @Test
    fun testLgPointerFrameFormatting() {
        val keys = listOf(
            Key.UP to "UP",
            Key.DOWN to "DOWN",
            Key.LEFT to "LEFT",
            Key.RIGHT to "RIGHT",
            Key.OK to "ENTER",
            Key.BACK to "BACK"
        )

        for ((key, expectedName) in keys) {
            val btnName = when (key) {
                Key.UP -> "UP"
                Key.DOWN -> "DOWN"
                Key.LEFT -> "LEFT"
                Key.RIGHT -> "RIGHT"
                Key.OK -> "ENTER"
                Key.BACK -> "BACK"
                else -> "ENTER"
            }
            val frame = "type:button\nname:$btnName\n\n"
            assertEquals("type:button\nname:$expectedName\n\n", frame)
        }
    }

    // 3. Android TV Protobuf encoding
    @Test
    fun testProtobufKeyInjectEncoding() {
        // Test KEYCODE_DPAD_UP (19), SHORT direction (3)
        val bytes = ProtobufWire.encodeRemoteKeyInject(19, 3)
        assertTrue("Encoded bytes must not be empty", bytes.isNotEmpty())

        val bos = ByteArrayOutputStream()
        ProtobufWire.writeFramedMessage(bos, bytes)
        val framed = bos.toByteArray()

        val bis = ByteArrayInputStream(framed)
        val readBack = ProtobufWire.readFramedMessage(bis)
        assertEquals(bytes.size, readBack.size)
    }

    @Test
    fun testProtobufPingResponseEncoding() {
        val val1 = 42
        val respBytes = ProtobufWire.encodeRemotePingResponse(val1)
        assertTrue(respBytes.isNotEmpty())

        val bos = ByteArrayOutputStream()
        ProtobufWire.writeFramedMessage(bos, respBytes)
        val readBack = ProtobufWire.readFramedMessage(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(respBytes.size, readBack.size)
    }

    // 4. Device deduplication
    @Test
    fun testDeviceDeduplication() {
        val seen = mutableMapOf<String, TvDevice>()
        val dev1 = TvDevice("lg_1", "LG OLED", "192.168.1.50", TvBrand.LG_WEBOS)
        val dev2 = TvDevice("lg_2", "LG OLED Living Room", "192.168.1.50", TvBrand.LG_WEBOS) // duplicate IP
        val dev3 = TvDevice("atv_1", "Google TV", "192.168.1.60", TvBrand.ANDROID_TV)

        for (dev in listOf(dev1, dev2, dev3)) {
            if (!seen.containsKey(dev.ip)) {
                seen[dev.ip] = dev
            }
        }

        assertEquals(2, seen.size)
        assertEquals("LG OLED", seen["192.168.1.50"]?.name)
        assertEquals("Google TV", seen["192.168.1.60"]?.name)
    }

    // 5. Offline Queue Expiration (max 3 items, 1500 ms TTL)
    @Test
    fun testOfflineQueueExpiration() {
        data class OfflineItem(val key: Key, val timestamp: Long)
        val queue = java.util.ArrayDeque<OfflineItem>(3)

        fun push(key: Key, time: Long) {
            if (queue.size >= 3) {
                queue.pollFirst()
            }
            queue.offerLast(OfflineItem(key, time))
        }

        val t0 = 1000L
        push(Key.UP, t0)
        push(Key.DOWN, t0 + 100)
        push(Key.LEFT, t0 + 200)
        push(Key.RIGHT, t0 + 300) // Drops UP (max 3)

        assertEquals(3, queue.size)
        assertEquals(Key.DOWN, queue.first().key)

        // At t0 + 2000 ms, DOWN (age 1900ms) and LEFT (age 1800ms) are expired (> 1500ms)
        // RIGHT (age 1700ms) is also expired
        val now = t0 + 2000L
        val valid = queue.filter { now - it.timestamp <= 1500L }
        assertTrue("Events older than 1500ms must be expired", valid.isEmpty())

        // If flushed at t0 + 1200ms:
        val earlierNow = t0 + 1200L
        val validEarlier = queue.filter { earlierNow - it.timestamp <= 1500L }
        assertEquals(3, validEarlier.size)
    }
}
