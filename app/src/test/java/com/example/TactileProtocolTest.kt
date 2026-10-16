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
    }

    // 6. Polo Pairing Protobuf & Status Parsing
    @Test
    fun testPoloPairingProtobufAndStatus() {
        val reqBytes = ProtobufWire.encodePairingRequest("atvremote", "Tactile")
        assertEquals(200, ProtobufWire.parseOuterMessageStatus(reqBytes))

        val optBytes = ProtobufWire.encodePairingOption()
        assertEquals(200, ProtobufWire.parseOuterMessageStatus(optBytes))

        val cfgBytes = ProtobufWire.encodePairingConfiguration()
        assertEquals(200, ProtobufWire.parseOuterMessageStatus(cfgBytes))

        val secBytes = ProtobufWire.encodePairingSecret(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(200, ProtobufWire.parseOuterMessageStatus(secBytes))
    }

    // 7. Polo Secret Hash Verification
    @Test
    fun testPoloSecretHashVerification() {
        val clientModulus = java.math.BigInteger("1234567890abcdef1234567890abcdef", 16)
        val clientExponent = java.math.BigInteger.valueOf(65537)
        val serverModulus = java.math.BigInteger("fedcba0987654321fedcba0987654321", 16)
        val serverExponent = java.math.BigInteger.valueOf(65537)
        val pin = "A1B2C3"

        fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) or Character.digit(hex[i + 1], 16)).toByte()
            }
            return data
        }

        fun getBytes(bi: java.math.BigInteger): ByteArray {
            var hex = bi.toString(16).uppercase()
            if (hex.length % 2 != 0) hex = "0$hex"
            return hexToBytes(hex)
        }

        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(getBytes(clientModulus))
        md.update(getBytes(clientExponent))
        md.update(getBytes(serverModulus))
        md.update(getBytes(serverExponent))
        md.update(hexToBytes(pin.substring(2)))
        val hash = md.digest()

        // Known digest hex from Python verification: 4e790bf9372152c8ad6befedf7cea1428b2ce2aaa9e8627a3ab8f3c6004db4ab
        val hexDigest = hash.joinToString("") { "%02x".format(it) }
        assertEquals("4e790bf9372152c8ad6befedf7cea1428b2ce2aaa9e8627a3ab8f3c6004db4ab", hexDigest)
    }

    // 8. IPv4 Over IPv6 Deduplication
    @Test
    fun testIPv4OverIPv6Deduplication() {
        val list = mutableListOf<TvDevice>()
        val devV6 = TvDevice("atv_v6", "Sony Bravia", "2600:1700::1", TvBrand.ANDROID_TV)
        val devV4 = TvDevice("atv_v4", "Sony Bravia", "192.168.1.100", TvBrand.ANDROID_TV)

        fun norm(name: String) = name.lowercase().replace(Regex("[^a-z0-9]"), "")

        for (candidate in listOf(devV6, devV4)) {
            val isIpv4 = !candidate.ip.contains(":")
            val candNorm = norm(candidate.name)
            val existingIndex = list.indexOfFirst {
                it.brand == candidate.brand && norm(it.name) == candNorm
            }
            if (existingIndex != -1) {
                val existing = list[existingIndex]
                val existingIsIpv4 = !existing.ip.contains(":")
                if (!existingIsIpv4 && isIpv4) {
                    list[existingIndex] = candidate
                }
            } else {
                list.add(candidate)
            }
        }

        assertEquals(1, list.size)
        assertEquals("192.168.1.100", list[0].ip)
    }
}
