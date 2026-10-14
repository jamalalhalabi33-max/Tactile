package com.example.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

object ProtobufWire {

    fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = input.read()
            if (b == -1) throw java.io.EOFException("Unexpected EOF reading varint")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Varint too long")
    }

    fun writeTag(out: OutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    fun writeInt32Field(out: OutputStream, fieldNumber: Int, value: Int) {
        writeTag(out, fieldNumber, 0)
        writeVarint(out, value.toLong())
    }

    fun writeStringField(out: OutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(out, fieldNumber, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    fun writeBytesField(out: OutputStream, fieldNumber: Int, bytes: ByteArray) {
        writeTag(out, fieldNumber, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    fun writeMessageField(out: OutputStream, fieldNumber: Int, messageBytes: ByteArray) {
        writeTag(out, fieldNumber, 2)
        writeVarint(out, messageBytes.size.toLong())
        out.write(messageBytes)
    }

    fun encodeRemoteKeyInject(keyCode: Int, direction: Int = 3): ByteArray {
        val keyInjectBos = ByteArrayOutputStream()
        writeInt32Field(keyInjectBos, 1, keyCode)
        writeInt32Field(keyInjectBos, 2, direction) // 3 = SHORT

        val msgBos = ByteArrayOutputStream()
        writeMessageField(msgBos, 10, keyInjectBos.toByteArray()) // field 10 = remote_key_inject
        return msgBos.toByteArray()
    }

    fun encodeRemoteConfigure(model: String = "Tactile", make: String = "Tactile"): ByteArray {
        val devInfoBos = ByteArrayOutputStream()
        writeStringField(devInfoBos, 1, model)
        writeStringField(devInfoBos, 2, make)
        writeStringField(devInfoBos, 3, "1.0.0")

        val configBos = ByteArrayOutputStream()
        writeInt32Field(configBos, 1, 622)
        writeMessageField(configBos, 2, devInfoBos.toByteArray())

        val msgBos = ByteArrayOutputStream()
        writeMessageField(msgBos, 1, configBos.toByteArray()) // field 1 = remote_configure
        return msgBos.toByteArray()
    }

    fun encodeRemotePingResponse(val1: Int): ByteArray {
        val pingRespBos = ByteArrayOutputStream()
        writeInt32Field(pingRespBos, 1, val1)

        val msgBos = ByteArrayOutputStream()
        writeMessageField(msgBos, 9, pingRespBos.toByteArray()) // field 9 = remote_ping_response
        return msgBos.toByteArray()
    }

    fun encodePairingRequest(serviceName: String = "atvremote", clientName: String = "Tactile"): ByteArray {
        val reqBos = ByteArrayOutputStream()
        writeStringField(reqBos, 1, serviceName)
        writeStringField(reqBos, 2, clientName)

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2) // protocol_version = 2
        writeInt32Field(msgBos, 2, 1) // status = 1 (STATUS_OK)
        writeMessageField(msgBos, 10, reqBos.toByteArray()) // field 10 = pairing_request
        return msgBos.toByteArray()
    }

    fun encodePairingOption(): ByteArray {
        val encBos = ByteArrayOutputStream()
        writeInt32Field(encBos, 1, 1) // ENCODING_HEXADECIMAL = 1
        writeInt32Field(encBos, 2, 6) // symbol_length = 6

        val optBos = ByteArrayOutputStream()
        writeMessageField(optBos, 1, encBos.toByteArray()) // input_encodings
        writeMessageField(optBos, 2, encBos.toByteArray()) // output_encodings
        writeInt32Field(optBos, 3, 1) // preferred_role = 1 (ROLE_INPUT)

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2) // protocol_version = 2
        writeInt32Field(msgBos, 2, 1) // status = 1 (STATUS_OK)
        writeMessageField(msgBos, 20, optBos.toByteArray()) // field 20 = pairing_option
        return msgBos.toByteArray()
    }

    fun encodePairingConfiguration(): ByteArray {
        val encBos = ByteArrayOutputStream()
        writeInt32Field(encBos, 1, 1) // ENCODING_HEXADECIMAL = 1
        writeInt32Field(encBos, 2, 6) // symbol_length = 6

        val cfgBos = ByteArrayOutputStream()
        writeMessageField(cfgBos, 1, encBos.toByteArray())
        writeInt32Field(cfgBos, 2, 1) // client_role = 1

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2)
        writeInt32Field(msgBos, 2, 1)
        writeMessageField(msgBos, 30, cfgBos.toByteArray()) // field 30 = pairing_configuration
        return msgBos.toByteArray()
    }

    fun encodePairingSecret(secretBytes: ByteArray): ByteArray {
        val secBos = ByteArrayOutputStream()
        writeBytesField(secBos, 1, secretBytes)

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2)
        writeInt32Field(msgBos, 2, 1)
        writeMessageField(msgBos, 40, secBos.toByteArray()) // field 40 = pairing_secret
        return msgBos.toByteArray()
    }

    fun writeFramedMessage(out: OutputStream, payload: ByteArray) {
        // Android TV Remote v2 uses a 1-byte length prefix (for < 128) or varint
        writeVarint(out, payload.size.toLong())
        out.write(payload)
        out.flush()
    }

    fun readFramedMessage(input: InputStream): ByteArray {
        val len = readVarint(input).toInt()
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val r = input.read(buf, offset, len - offset)
            if (r == -1) throw java.io.EOFException("Premature EOF in framed message")
            offset += r
        }
        return buf
    }
}
