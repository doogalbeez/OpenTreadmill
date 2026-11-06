package app.opentreadmill.core

/**
 * Frame codec for the FitPro (Gen 6) motor board protocol, spoken over USB HID
 * (VID 0x213C, PID 0x0002, 64-byte frames).
 *
 * Frame: [device][length incl. checksum][command][payload...][checksum]
 * Checksum: low byte of the sum of every byte before it.
 *
 * Based on the public interoperability notes in strideApp (Apache-2.0).
 */
object FitPro {
    const val VENDOR_ID = 0x213C
    const val PRODUCT_ID = 0x0002
    const val FRAME_SIZE = 64

    const val DEVICE_TREADMILL: Int = 4
    const val CMD_READ_WRITE_DATA: Int = 2
    /** Some boards answer identity and unlock here instead of on [DEVICE_TREADMILL]. */
    const val DEVICE_MAIN: Int = 2
    const val CMD_DEVICE_INFO: Int = 129
    const val CMD_SYSTEM_INFO: Int = 130
    const val CMD_VERSION_INFO: Int = 132
    const val CMD_VERIFY_SECURITY: Int = 144
    /** Below this software version the board has no security and no VerifySecurity. */
    const val SECURITY_MIN_SOFTWARE: Int = 76

    /** Commands that can reset or brick the board. The codec refuses to build them. */
    val FORBIDDEN_COMMANDS = setOf(9, 56)

    enum class Field(val id: Int, val size: Int, val signed: Boolean = false) {
        KPH(0, 2),
        GRADE(1, 2, signed = true),
        DISTANCE(6, 4),          // size/units not confirmed on hardware yet
        WORKOUT_MODE(12, 1),
        ACTUAL_KPH(16, 2),
        ACTUAL_INCLINE(17, 2, signed = true),
        MAX_GRADE(27, 2, signed = true),
        MIN_GRADE(28, 2, signed = true),
        MAX_KPH(30, 2),
        MIN_KPH(31, 2),
        SAFETY(75, 1);           // size not confirmed on hardware yet

        companion object {
            private val byId = entries.associateBy { it.id }
            fun of(id: Int) = byId[id]
        }
    }

    enum class Mode(val code: Int) {
        UNKNOWN(0), IDLE(1), RUNNING(2), PAUSE(3), RESULTS(4), DEBUG(5), LOG(6),
        MAINTENANCE(7), SAFETY_KEY_OUT(8), DEMO(9), WARM_UP(10), COOL_DOWN(11),
        SLEEP(12), RESUME(13), LOCKED(14), PAUSE_OVERRIDE(20);

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /** Response status byte. Only DONE carries real data; anything else fills the fields with 0xFF. */
    const val STATUS_DONE = 2
    const val STATUS_SECURITY_BLOCK = 8

    fun statusName(v: Int) = when (v) {
        0 -> "devNotSupported"; 1 -> "cmdNotSupported"; 2 -> "done"; 3 -> "inProgress"; 4 -> "failed"
        5 -> "timeLeft"; 7 -> "unknownFailure"; 8 -> "securityBlock"; 9 -> "commFailed"; else -> "status$v"
    }

    fun checksum(bytes: ByteArray, until: Int): Byte {
        var sum = 0
        for (i in 0 until until) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    /** Bitmask section: [mask byte count][mask bytes...]; empty section is a single 0x00. */
    internal fun mask(fields: Collection<Field>): ByteArray {
        if (fields.isEmpty()) return byteArrayOf(0)
        val groups = fields.maxOf { it.id } / 8 + 1
        val out = ByteArray(groups + 1)
        out[0] = groups.toByte()
        for (f in fields) {
            val idx = 1 + f.id / 8
            out[idx] = (out[idx].toInt() or (1 shl (f.id % 8))).toByte()
        }
        return out
    }

    private fun encodeValue(field: Field, value: Int): ByteArray {
        val out = ByteArray(field.size)
        for (i in 0 until field.size) out[i] = ((value shr (8 * i)) and 0xFF).toByte()
        return out
    }

    /**
     * Build a ReadWriteData request. Writes and reads are each sorted by field id,
     * as the board expects.
     */
    fun readWriteRequest(
        writes: Map<Field, Int> = emptyMap(),
        reads: Collection<Field> = emptyList(),
        device: Int = DEVICE_TREADMILL,
    ): ByteArray = frame(device, CMD_READ_WRITE_DATA, buildList {
        val w = writes.toSortedMap(compareBy { it.id })
        addAll(mask(w.keys).toList())
        w.forEach { (f, v) -> addAll(encodeValue(f, v).toList()) }
        addAll(mask(reads.sortedBy { it.id }).toList())
    }.toByteArray())

    fun frame(device: Int, command: Int, payload: ByteArray): ByteArray {
        require(command !in FORBIDDEN_COMMANDS) { "Refusing to build command $command: it can reset or brick the board" }
        val len = 3 + payload.size + 1
        require(len <= FRAME_SIZE) { "Frame too long: $len" }
        val out = ByteArray(len)
        out[0] = device.toByte(); out[1] = len.toByte(); out[2] = command.toByte()
        payload.copyInto(out, 3)
        out[len - 1] = checksum(out, len - 1)
        return out
    }

    /** The 32-byte VerifySecurity hash, from numbers the board reports. All arithmetic truncates to a byte. */
    fun securityHash(serial: Int, partNumber: Int, model: Int): ByteArray = ByteArray(32) { b ->
        var v = (b + 1) and 0xFF
        if ((serial ushr b) and 1 == 1) {
            val p = if (b < 16) ((partNumber shl 16) or (partNumber ushr 16)) ushr b else partNumber ushr b
            v = v xor (p and 0xFF)
        } else {
            v = v xor ((v * (b + model)) and 0xFF)
        }
        v.toByte()
    }

    /** VerifySecurity: the hash, then 8 x the master library version as a little-endian int32. */
    fun verifySecurityRequest(hash: ByteArray, masterLibraryVersion: Int, device: Int): ByteArray {
        require(hash.size == 32) { "hash must be 32 bytes, was ${hash.size}" }
        val key = 8 * masterLibraryVersion
        return frame(device, CMD_VERIFY_SECURITY, hash + ByteArray(4) { i -> (key ushr (8 * i)).toByte() })
    }

    /** The board's identity, read straight from it. These numbers unlock the board: never log them. */
    class Identity(val softwareVersion: Int, val serial: Int)
    class SystemIdentity(val model: Int, val partNumber: Int)

    /** The frame if it is intact, status Done and holds [need] bytes of data; else null, never a guess. */
    private fun done(bytes: ByteArray?, need: Int): ByteArray? {
        if (bytes == null || bytes.size < 5) return null
        val len = bytes[1].toInt() and 0xFF
        if (len < 5 || len > bytes.size || checksum(bytes, len - 1) != bytes[len - 1]) return null
        if (bytes[3].toInt() and 0xFF != STATUS_DONE || len < 4 + need + 1) return null
        return bytes
    }

    private fun u32(b: ByteArray, at: Int) = (0 until 4).fold(0) { acc, i -> acc or ((b[at + i].toInt() and 0xFF) shl (8 * i)) }

    fun parseDeviceInfo(bytes: ByteArray?): Identity? =
        done(bytes, 6)?.let { Identity(it[4].toInt() and 0xFF, u32(it, 6)) }

    fun parseSystemInfo(bytes: ByteArray?): SystemIdentity? = done(bytes, 11)?.let {
        val model = u32(it, 7)
        val part = u32(it, 11)
        // One production run reports a part number that does not match its own hash.
        SystemIdentity(model, if (part == 370357 && model == 39915) 374677 else part)
    }

    fun parseMasterLibraryVersion(bytes: ByteArray?): Int? = done(bytes, 1)?.let { it[4].toInt() and 0xFF }

    fun isDone(bytes: ByteArray?): Boolean = done(bytes, 0) != null

    class Response(val device: Int, val command: Int, val status: Int, val values: Map<Field, Int>) {
        val ok get() = status == STATUS_DONE
    }

    class ProtocolException(msg: String) : Exception(msg)

    /** Decode a response to a request that read [reads]. Values are scaled raw integers. */
    fun decode(bytes: ByteArray, reads: Collection<Field>): Response {
        if (bytes.size < 5) throw ProtocolException("Short frame (${bytes.size} bytes)")
        val len = bytes[1].toInt() and 0xFF
        if (len < 5 || len > bytes.size) throw ProtocolException("Bad length $len")
        if (checksum(bytes, len - 1) != bytes[len - 1]) throw ProtocolException("Checksum mismatch")
        val status = bytes[3].toInt() and 0xFF
        val values = linkedMapOf<Field, Int>()
        if (status != STATUS_DONE) return Response(bytes[0].toInt() and 0xFF, bytes[2].toInt() and 0xFF, status, values)
        var pos = 4
        for (f in reads.sortedBy { it.id }) {
            if (pos + f.size > len - 1) throw ProtocolException("Response too short for ${f.name}")
            var v = 0
            for (i in 0 until f.size) v = v or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
            if (f.signed && f.size == 2) v = v.toShort().toInt()
            values[f] = v
            pos += f.size
        }
        return Response(bytes[0].toInt() and 0xFF, bytes[2].toInt() and 0xFF, status, values)
    }

    /** Parse a ReadWriteData request back into its writes and reads (used by the simulator). */
    fun parseRequest(bytes: ByteArray): Pair<Map<Field, Int>, List<Field>> {
        val len = bytes[1].toInt() and 0xFF
        var pos = 3
        fun section(): List<Field> {
            val groups = bytes[pos++].toInt() and 0xFF
            val out = mutableListOf<Field>()
            for (g in 0 until groups) {
                val m = bytes[pos++].toInt() and 0xFF
                for (b in 0 until 8) if (m and (1 shl b) != 0) {
                    out += Field.of(g * 8 + b) ?: throw ProtocolException("Unknown field ${g * 8 + b}")
                }
            }
            return out.sortedBy { it.id }
        }
        val writeFields = section()
        val writes = linkedMapOf<Field, Int>()
        for (f in writeFields) {
            var v = 0
            for (i in 0 until f.size) v = v or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
            if (f.signed && f.size == 2) v = v.toShort().toInt()
            writes[f] = v; pos += f.size
        }
        val reads = section()
        if (pos != len - 1) throw ProtocolException("Trailing bytes in request")
        return writes to reads
    }

    /** Build a response frame (used by the simulator). */
    fun response(device: Int, status: Int, values: List<Pair<Field, Int>>): ByteArray {
        val body = ArrayList<Byte>()
        body += status.toByte()
        for ((f, v) in values) for (i in 0 until f.size) body += ((if (status == STATUS_DONE) v shr (8 * i) else 0xFF) and 0xFF).toByte()
        val len = 3 + body.size + 1
        val out = ByteArray(len)
        out[0] = device.toByte(); out[1] = len.toByte(); out[2] = CMD_READ_WRITE_DATA.toByte()
        body.toByteArray().copyInto(out, 3)
        out[len - 1] = checksum(out, len - 1)
        return out
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
