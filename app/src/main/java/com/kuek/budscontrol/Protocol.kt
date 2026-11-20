package com.kuek.budscontrol

import java.util.UUID

/**
 * Framing for the Galaxy Buds (Buds+ and later) RFCOMM protocol.
 *
 *   [0xFD] [len lo] [len hi | flags] [msg id] [payload...] [crc16 x2] [0xDD]
 *
 * len = 1 (id) + payload + 2 (crc), 10 bits. Header flag 0x10 = response,
 * 0x20 = fragment. CRC is CRC-16/XMODEM over id + payload.
 */
object Protocol {
    val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    private const val SOM = 0xFD
    private const val EOM = 0xDD

    const val STATUS_UPDATED = 0x60
    const val EXTENDED_STATUS_UPDATED = 0x61
    const val NOISE_CONTROLS_UPDATE = 0x77
    const val NOISE_CONTROLS = 0x78
    const val ANC_WITH_ONE_EARBUD = 0x6F
    const val CUSTOMIZE_AMBIENT = 0x82
    const val NOISE_REDUCTION_LEVEL = 0x83
    const val AMBIENT_VOLUME = 0x84
    const val FIND_START = 0xA0
    const val FIND_STOP = 0xA1

    const val MODE_OFF = 0
    const val MODE_ANC = 1
    const val MODE_AMBIENT = 2

    /** Byte order of the CRC on the wire; flipped automatically if the buds use the other one. */
    @Volatile var crcLittleEndian = true

    class Packet(val id: Int, val payload: ByteArray, val isResponse: Boolean, val crcOk: Boolean)

    fun encode(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = payload.size + 3
        val out = ByteArray(size + 4)
        out[0] = SOM.toByte()
        out[1] = (size and 0xFF).toByte()
        out[2] = ((size shr 8) and 0x03).toByte()
        out[3] = id.toByte()
        payload.copyInto(out, 4)
        val crc = crc16(out, 3, 1 + payload.size)
        val hi = ((crc shr 8) and 0xFF).toByte()
        val lo = (crc and 0xFF).toByte()
        out[4 + payload.size] = if (crcLittleEndian) lo else hi
        out[5 + payload.size] = if (crcLittleEndian) hi else lo
        out[6 + payload.size] = EOM.toByte()
        return out
    }

    /** Stream parser: feed raw socket bytes, get whole packets out. */
    class Parser {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray, n: Int): List<Packet> {
            buf += data.copyOf(n)
            val out = ArrayList<Packet>()
            while (true) {
                val start = buf.indexOfFirst { (it.toInt() and 0xFF) == SOM }
                if (start < 0) { buf = ByteArray(0); break }
                if (start > 0) buf = buf.copyOfRange(start, buf.size)
                if (buf.size < 3) break
                val size = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0x03) shl 8)
                val total = size + 4
                if (size < 3) { buf = buf.copyOfRange(1, buf.size); continue }
                if (buf.size < total) break
                if ((buf[total - 1].toInt() and 0xFF) != EOM) {
                    buf = buf.copyOfRange(1, buf.size); continue
                }
                val id = buf[3].toInt() and 0xFF
                val payload = buf.copyOfRange(4, 4 + size - 3)
                val crc = crc16(buf, 3, size - 2)
                val c0 = buf[total - 3].toInt() and 0xFF
                val c1 = buf[total - 2].toInt() and 0xFF
                val le = (c0 or (c1 shl 8)) == crc
                val be = ((c0 shl 8) or c1) == crc
                if (be && !le) crcLittleEndian = false
                if (le && !be) crcLittleEndian = true
                out += Packet(id, payload, (buf[2].toInt() and 0x10) != 0, le || be)
                buf = buf.copyOfRange(total, buf.size)
            }
            return out
        }
    }

    fun crc16(data: ByteArray, off: Int, len: Int): Int {
        var crc = 0
        for (i in off until off + len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
}
