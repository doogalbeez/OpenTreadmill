package app.opentreadmill.core

import app.opentreadmill.core.FitPro.Field
import app.opentreadmill.core.FitPro.Mode

/**
 * A fake FitPro board that speaks real frames and copies the real board's quirks:
 * - speed writes are ignored unless the mode is Running,
 * - writing Idle while Running is ignored (Pause is the only way out of Running),
 * - ActualKph reads 0 at every speed (watch the Distance odometer instead),
 * - pulling the safety key stops the belt and reports Dmk in short bursts,
 * - frames can be refused at random, like a busy board.
 */
class SimulatedBoard(
    val minKph: Double = 1.6, val maxKph: Double = 19.3,
    val minGrade: Double = -3.0, val maxGrade: Double = 12.0,
    var reportsActualKph: Boolean = false,
    var rejectEvery: Int = 0,
    private val accelKphPerSec: Double = 1.5,
    private val gradePerSec: Double = 0.5,
    /** A board that has re-locked itself, as it does across a power cycle. */
    startLocked: Boolean = false,
    /** Locked boards are documented to refuse reads too; the console's board was seen answering them. */
    private val lockedRefusesReads: Boolean = true,
    private val softwareVersion: Int = 80,
) : FrameTransport {
    var mode = Mode.IDLE; private set
    var beltKph = 0.0; private set
    var grade = 0.0; private set
    var odometerM = 0.0; private set
    var keyIn = true
        set(v) { if (!v && field) { mode = Mode.SAFETY_KEY_OUT; dmkBurst = 3; targetKph = 0.0 }; field = v }
    private var targetKph = 0.0
    private var targetGrade = 0.0
    private var dmkBurst = 0
    private var frames = 0
    /** Frames failed for a write the real board refuses. The controller should never cause one. */
    var rejectedFrames = 0; private set
    /** Test hook: a board that won't start at all. */
    var refuseRunning = false
    var locked = startLocked
    /** Test hook: a board that won't accept any unlock. */
    var refuseUnlock = false
    var unlockAttempts = 0; private set
    private val serial = 0x0BADF00D
    private val model = 41234
    private val part = 370001
    private val library = 3

    // The console's own buttons, which talk to the board without going through the app.
    fun consoleSpeed(kph: Double) { if (mode == Mode.RUNNING) targetKph = kph.coerceIn(minKph, maxKph) }
    fun consoleIncline(pct: Double) { targetGrade = pct.coerceIn(minGrade, maxGrade) }
    fun consoleStart() { if (keyIn && mode in setOf(Mode.IDLE, Mode.PAUSE)) { mode = Mode.RUNNING; targetKph = minKph } }
    fun consoleStop() { if (mode == Mode.RUNNING) { mode = Mode.PAUSE; targetKph = 0.0 } }

    /** What the real board does after sitting in Pause for about ten minutes. */
    fun timeOutToResults() { if (mode == Mode.PAUSE) mode = Mode.RESULTS }

    fun tick(seconds: Double) {
        val tk = if (mode == Mode.RUNNING && keyIn) targetKph else 0.0
        val step = (if (!keyIn) 10.0 else accelKphPerSec) * seconds
        beltKph = if (beltKph < tk) minOf(tk, beltKph + step) else maxOf(tk, beltKph - step)
        grade = if (grade < targetGrade) minOf(targetGrade, grade + gradePerSec * seconds) else maxOf(targetGrade, grade - gradePerSec * seconds)
        odometerM += beltKph / 3.6 * seconds
    }

    override fun exchange(request: ByteArray): ByteArray {
        identityReply(request)?.let { return it }
        frames++
        val (writes, reads) = FitPro.parseRequest(request)
        if (locked && (lockedRefusesReads || writes.isNotEmpty())) return FitPro.response(request[0].toInt(), FitPro.STATUS_SECURITY_BLOCK, emptyList())
        if (rejectEvery > 0 && frames % rejectEvery == 0) return FitPro.response(request[0].toInt(), 4, reads.map { it to 0 })
        // Like the real board: a Kph outside its limits (Kph 0 included) fails the whole frame.
        writes[Field.KPH]?.let { if (it / 100.0 !in minKph..maxKph) { rejectedFrames++; return FitPro.response(request[0].toInt(), 4, reads.map { r -> r to 0 }) } }
        // Also like the real board: Idle from Pause, or any mode write with the key out, fails the whole frame.
        if (writes[Field.WORKOUT_MODE] == Mode.IDLE.code && mode == Mode.PAUSE ||
            writes[Field.WORKOUT_MODE] == Mode.RUNNING.code && (mode == Mode.RESULTS || refuseRunning) ||
            Field.WORKOUT_MODE in writes && mode == Mode.SAFETY_KEY_OUT) { rejectedFrames++; return FitPro.response(request[0].toInt(), 4, reads.map { r -> r to 0 }) }
        for ((f, v) in writes) when (f) {
            Field.KPH -> if (mode == Mode.RUNNING) targetKph = v / 100.0
            Field.GRADE -> targetGrade = (v / 100.0).coerceIn(minGrade, maxGrade)
            Field.WORKOUT_MODE -> applyMode(Mode.of(v))
            else -> {}
        }
        val values = reads.map { f -> f to read(f) }
        // Dmk shows up in bursts, then the board reports Pause while the key is still out.
        if (mode == Mode.SAFETY_KEY_OUT && --dmkBurst <= 0) mode = Mode.PAUSE
        return FitPro.response(request[0].toInt(), FitPro.STATUS_DONE, values)
    }

    private fun le32(v: Int) = ByteArray(4) { i -> (v ushr (8 * i)).toByte() }

    private fun identityReply(request: ByteArray): ByteArray? {
        val cmd = request[2].toInt() and 0xFF
        val data = when (cmd) {
            FitPro.CMD_DEVICE_INFO -> byteArrayOf(softwareVersion.toByte(), 0) + le32(serial)
            FitPro.CMD_SYSTEM_INFO -> byteArrayOf(0, 0, 0) + le32(model) + le32(part)
            FitPro.CMD_VERSION_INFO -> byteArrayOf(library.toByte())
            FitPro.CMD_VERIFY_SECURITY -> {
                unlockAttempts++
                val want = FitPro.verifySecurityRequest(FitPro.securityHash(serial, part, model), library)
                if (refuseUnlock || !request.copyOf(want.size).contentEquals(want)) return FitPro.frame(request[0].toInt(), cmd, byteArrayOf(4))
                locked = false
                byteArrayOf(1)
            }
            else -> return null
        }
        return FitPro.frame(request[0].toInt(), cmd, byteArrayOf(FitPro.STATUS_DONE.toByte()) + data)
    }

    private fun applyMode(m: Mode) {
        if (!keyIn) return
        when (m) {
            Mode.RUNNING -> if (mode in setOf(Mode.IDLE, Mode.PAUSE)) mode = Mode.RUNNING
            Mode.PAUSE -> if (mode == Mode.RUNNING) { mode = Mode.PAUSE; targetKph = 0.0 }
            Mode.IDLE -> if (mode != Mode.RUNNING) { mode = Mode.IDLE; targetKph = 0.0 }  // ignored while running
            else -> {}
        }
    }

    fun read(f: Field): Int = when (f) {
        Field.KPH -> (targetKph * 100).toInt()
        Field.GRADE -> (targetGrade * 100).toInt()
        Field.DISTANCE -> odometerM.toInt()
        Field.WORKOUT_MODE -> mode.code
        Field.ACTUAL_KPH -> if (reportsActualKph) (beltKph * 100).toInt() else 0
        Field.ACTUAL_INCLINE -> (grade * 100).toInt()
        Field.MAX_GRADE -> (maxGrade * 100).toInt()
        Field.MIN_GRADE -> (minGrade * 100).toInt()
        Field.MAX_KPH -> (maxKph * 100).toInt()
        Field.MIN_KPH -> (minKph * 100).toInt()
        Field.SAFETY -> if (keyIn) 0 else 1
    }

    override fun close() {}
}
