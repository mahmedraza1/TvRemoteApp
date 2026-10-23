package com.mark.tvremote.core

import java.io.ByteArrayOutputStream

/** Minimal protobuf encoder; just enough for the Polo pairing and Android TV remote messages. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Int) = apply { tag(field, 0); varint(value.toLong()) }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray) = apply {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
    }

    fun message(field: Int, value: ProtoWriter) = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

class ProtoField(val number: Int, val wireType: Int, val varint: Long, val data: ByteArray)

/** Parses a protobuf message into its flat list of fields; nested messages are parsed on demand. */
class ProtoMessage(private val fields: List<ProtoField>) {
    fun has(number: Int) = fields.any { it.number == number }
    fun int(number: Int, default: Int = 0) = fields.lastOrNull { it.number == number }?.varint?.toInt() ?: default
    fun string(number: Int) = fields.lastOrNull { it.number == number }?.data?.toString(Charsets.UTF_8) ?: ""
    fun bytes(number: Int) = fields.lastOrNull { it.number == number }?.data
    fun message(number: Int): ProtoMessage? = bytes(number)?.let { parse(it) }

    /** Human-readable dump for protocol debugging: nested messages are guessed heuristically. */
    fun describe(): String = fields.joinToString(" ") { f ->
        val body = when (f.wireType) {
            0 -> f.varint.toString()
            2 -> {
                val text = f.data.toString(Charsets.UTF_8)
                val printable = f.data.isNotEmpty() && text.all { it.code in 32..126 || it.code > 160 }
                val nested = if (printable) null else runCatching { parse(f.data).describe() }.getOrNull()
                when {
                    f.data.isEmpty() -> "{}"
                    nested != null -> "{$nested}"
                    printable -> "\"$text\""
                    else -> f.data.joinToString("") { "%02x".format(it) }
                }
            }
            else -> "?"
        }
        "${f.number}:$body"
    }

    companion object {
        fun parse(buf: ByteArray): ProtoMessage {
            val fields = ArrayList<ProtoField>()
            var pos = 0

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < buf.size && shift < 64) { "Malformed varint" }
                    val b = buf[pos++].toInt()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }

            while (pos < buf.size) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    0 -> fields += ProtoField(number, wire, readVarint(), ByteArray(0))
                    1 -> { fields += ProtoField(number, wire, 0, buf.copyOfRange(pos, pos + 8)); pos += 8 }
                    5 -> { fields += ProtoField(number, wire, 0, buf.copyOfRange(pos, pos + 4)); pos += 4 }
                    2 -> {
                        val len = readVarint().toInt()
                        require(len >= 0 && pos + len <= buf.size) { "Truncated field" }
                        fields += ProtoField(number, wire, 0, buf.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    else -> throw IllegalArgumentException("Unsupported wire type $wire")
                }
            }
            return ProtoMessage(fields)
        }
    }
}
