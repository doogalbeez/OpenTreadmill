package app.treadmill.core

/** A snapshot of what the treadmill reports. */
data class TreadmillState(
    val mode: FitPro.Mode = FitPro.Mode.UNKNOWN,
    val speedKph: Double = 0.0,
    val inclinePct: Double = 0.0,
    val distanceRaw: Int = 0,
    val safetyKeyIn: Boolean = true,
)

data class Limits(
    val minKph: Double = 0.8, val maxKph: Double = 19.3,
    val minGrade: Double = -3.0, val maxGrade: Double = 12.0,
)

/** Low-level access to a motor board. One implementation per board type, plus a simulator. */
interface TreadmillDriver : AutoCloseable {
    fun readLimits(): Limits
    fun poll(): TreadmillState
    fun setMode(mode: FitPro.Mode)
    fun setSpeed(kph: Double)
    fun setIncline(pct: Double)
}

/** Raw 64-byte frame transport (USB HID on the console, a fake in tests). */
interface FrameTransport : AutoCloseable {
    fun exchange(request: ByteArray): ByteArray
}

/** FitPro driver on top of any frame transport. */
class FitProDriver(private val io: FrameTransport) : TreadmillDriver {
    private fun rw(writes: Map<FitPro.Field, Int> = emptyMap(), reads: List<FitPro.Field> = emptyList()): FitPro.Response {
        val resp = FitPro.decode(io.exchange(FitPro.readWriteRequest(writes, reads)), reads)
        if (resp.status == FitPro.STATUS_SECURITY_BLOCK) throw FitPro.ProtocolException("Board is locked (security block)")
        return resp
    }

    override fun readLimits(): Limits {
        // One field per frame keeps us safe on boards that reject unknown fields.
        fun one(f: FitPro.Field) = rw(reads = listOf(f)).values.getValue(f) / 100.0
        return Limits(
            minKph = one(FitPro.Field.MIN_KPH), maxKph = one(FitPro.Field.MAX_KPH),
            minGrade = one(FitPro.Field.MIN_GRADE), maxGrade = one(FitPro.Field.MAX_GRADE),
        )
    }

    override fun poll(): TreadmillState {
        val fields = listOf(FitPro.Field.DISTANCE, FitPro.Field.WORKOUT_MODE, FitPro.Field.ACTUAL_KPH, FitPro.Field.ACTUAL_INCLINE)
        val v = rw(reads = fields).values
        val mode = FitPro.Mode.of(v.getValue(FitPro.Field.WORKOUT_MODE))
        return TreadmillState(
            mode = mode,
            speedKph = v.getValue(FitPro.Field.ACTUAL_KPH) / 100.0,
            inclinePct = v.getValue(FitPro.Field.ACTUAL_INCLINE) / 100.0,
            distanceRaw = v.getValue(FitPro.Field.DISTANCE),
            safetyKeyIn = mode != FitPro.Mode.SAFETY_KEY_OUT,
        )
    }

    override fun setMode(mode: FitPro.Mode) { rw(writes = mapOf(FitPro.Field.WORKOUT_MODE to mode.code)) }
    override fun setSpeed(kph: Double) { rw(writes = mapOf(FitPro.Field.KPH to Math.round(kph * 100).toInt())) }
    override fun setIncline(pct: Double) { rw(writes = mapOf(FitPro.Field.GRADE to Math.round(pct * 100).toInt())) }
    override fun close() = io.close()
}
