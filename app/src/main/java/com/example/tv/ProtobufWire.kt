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

    fun encodeRemoteImeBatchEdit(text: String, imeCounter: Int = 1, fieldCounter: Int = 1): ByteArray {
        val paramVal = (text.length - 1).coerceAtLeast(0)
        val imeObjBos = ByteArrayOutputStream()
        writeInt32Field(imeObjBos, 1, paramVal) // start = 1
        writeInt32Field(imeObjBos, 2, paramVal) // end = 2
        writeStringField(imeObjBos, 3, text)     // value = 3

        val editInfoBos = ByteArrayOutputStream()
        writeInt32Field(editInfoBos, 1, 1) // insert = 1
        writeMessageField(editInfoBos, 2, imeObjBos.toByteArray()) // text_field_status = 2

        val batchEditBos = ByteArrayOutputStream()
        writeInt32Field(batchEditBos, 1, imeCounter)   // ime_counter = 1
        writeInt32Field(batchEditBos, 2, fieldCounter) // field_counter = 2
        writeMessageField(batchEditBos, 3, editInfoBos.toByteArray()) // edit_info = 3

        val msgBos = ByteArrayOutputStream()
        writeMessageField(msgBos, 21, batchEditBos.toByteArray()) // field 21 = remote_ime_batch_edit
        return msgBos.toByteArray()
    }

    fun parseOuterMessageStatus(data: ByteArray): Int {
        var idx = 0
        var status = 200
        while (idx < data.size) {
            var tag = 0L
            var shift = 0
            while (idx < data.size) {
                val b = data[idx++].toInt() and 0xFF
                tag = tag or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            if (wireType == 0) { // Varint
                var v = 0L
                shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (fieldNum == 2) {
                    status = v.toInt()
                }
            } else if (wireType == 2) { // Length-delimited
                var len = 0
                shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                idx += len
            } else if (wireType == 1) { // 64-bit fixed
                idx += 8
            } else if (wireType == 5) { // 32-bit fixed
                idx += 4
            } else {
                break
            }
        }
        return status
    }

    fun encodePairingRequest(serviceName: String = "atvremote", clientName: String = "Tactile"): ByteArray {
        val reqBos = ByteArrayOutputStream()
        writeStringField(reqBos, 1, serviceName) // field 1 = service_name
        writeStringField(reqBos, 2, clientName)  // field 2 = client_name

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2)   // protocol_version = 2
        writeInt32Field(msgBos, 2, 200) // status = 200 (STATUS_OK)
        writeMessageField(msgBos, 10, reqBos.toByteArray()) // field 10 = pairing_request
        return msgBos.toByteArray()
    }

    fun encodePairingOption(): ByteArray {
        val encBos = ByteArrayOutputStream()
        writeInt32Field(encBos, 1, 3) // ENCODING_TYPE_HEXADECIMAL = 3
        writeInt32Field(encBos, 2, 6) // symbol_length = 6

        val optBos = ByteArrayOutputStream()
        writeMessageField(optBos, 1, encBos.toByteArray()) // field 1 = input_encodings
        writeInt32Field(optBos, 3, 1)                      // field 3 = preferred_role (ROLE_TYPE_INPUT = 1)

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2)   // protocol_version = 2
        writeInt32Field(msgBos, 2, 200) // status = 200 (STATUS_OK)
        writeMessageField(msgBos, 20, optBos.toByteArray()) // field 20 = options
        return msgBos.toByteArray()
    }

    fun encodePairingConfiguration(): ByteArray {
        val encBos = ByteArrayOutputStream()
        writeInt32Field(encBos, 1, 3) // ENCODING_TYPE_HEXADECIMAL = 3
        writeInt32Field(encBos, 2, 6) // symbol_length = 6

        val cfgBos = ByteArrayOutputStream()
        writeMessageField(cfgBos, 1, encBos.toByteArray()) // field 1 = encoding
        writeInt32Field(cfgBos, 2, 1)                      // field 2 = client_role (ROLE_TYPE_INPUT = 1)

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2)   // protocol_version = 2
        writeInt32Field(msgBos, 2, 200) // status = 200 (STATUS_OK)
        writeMessageField(msgBos, 30, cfgBos.toByteArray()) // field 30 = configuration
        return msgBos.toByteArray()
    }

    fun encodePairingSecret(secretBytes: ByteArray): ByteArray {
        val secBos = ByteArrayOutputStream()
        writeBytesField(secBos, 1, secretBytes) // field 1 = secret

        val msgBos = ByteArrayOutputStream()
        writeInt32Field(msgBos, 1, 2)   // protocol_version = 2
        writeInt32Field(msgBos, 2, 200) // status = 200 (STATUS_OK)
        writeMessageField(msgBos, 40, secBos.toByteArray()) // field 40 = secret
        return msgBos.toByteArray()
    }

    fun writeFramedMessage(out: OutputStream, payload: ByteArray) {
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
