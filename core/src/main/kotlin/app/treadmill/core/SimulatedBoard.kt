package app.treadmill.core

import app.treadmill.core.FitPro.Field
import app.treadmill.core.FitPro.Mode

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

    fun tick(seconds: Double) {
        val tk = if (mode == Mode.RUNNING && keyIn) targetKph else 0.0
        val step = (if (!keyIn) 10.0 else accelKphPerSec) * seconds
        beltKph = if (beltKph < tk) minOf(tk, beltKph + step) else maxOf(tk, beltKph - step)
        grade = if (grade < targetGrade) minOf(targetGrade, grade + gradePerSec * seconds) else maxOf(targetGrade, grade - gradePerSec * seconds)
        odometerM += beltKph / 3.6 * seconds
    }

    override fun exchange(request: ByteArray): ByteArray {
        frames++
        val (writes, reads) = FitPro.parseRequest(request)
        if (rejectEvery > 0 && frames % rejectEvery == 0) return FitPro.response(request[0].toInt(), 4, reads.map { it to 0 })
        for ((f, v) in writes) when (f) {
            Field.KPH -> if (mode == Mode.RUNNING) targetKph = (v / 100.0).let { if (it <= 0.0) 0.0 else it.coerceIn(minKph, maxKph) }
            Field.GRADE -> targetGrade = (v / 100.0).coerceIn(minGrade, maxGrade)
            Field.WORKOUT_MODE -> applyMode(Mode.of(v))
            else -> {}
        }
        val values = reads.map { f -> f to read(f) }
        // Dmk shows up in bursts, then the board reports Pause while the key is still out.
        if (mode == Mode.SAFETY_KEY_OUT && --dmkBurst <= 0) mode = Mode.PAUSE
        return FitPro.response(request[0].toInt(), FitPro.STATUS_DONE, values)
    }

    private fun applyMode(m: Mode) {
        if (!keyIn) return
        when (m) {
            Mode.RUNNING -> if (mode in setOf(Mode.IDLE, Mode.PAUSE, Mode.RESULTS)) mode = Mode.RUNNING
            Mode.PAUSE -> if (mode == Mode.RUNNING) { mode = Mode.PAUSE; targetKph = 0.0 }
            Mode.IDLE -> if (mode != Mode.RUNNING) { mode = Mode.IDLE; targetKph = 0.0 }  // ignored while running
            else -> {}
        }
    }

    private fun read(f: Field): Int = when (f) {
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
