package app.treadmill.core

/**
 * The only object the UI talks to. Every command goes through here:
 * - clamps to limits the board reports,
 * - rate-limits target changes,
 * - refuses speed while the safety key is out,
 * - stops the belt on any driver error.
 * The physical safety key is always the stop of record.
 */
class SafeTreadmill(
    private val driver: TreadmillDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minCommandGapMs: Long = 250,
    private val maxKphStep: Double = 2.0,
    /** The belt never starts faster than this; higher targets ramp up from here. */
    private val startKphCap: Double = 3.2,
    private val onFault: (Throwable) -> Unit = {},
) {
    val limits: Limits = driver.readLimits()
    var state = TreadmillState(); private set
    var targetKph = 0.0; private set
    var targetGrade = 0.0; private set
    private var lastSpeedCmd = 0L
    private var lastGradeCmd = 0L

    val running get() = state.mode == FitPro.Mode.RUNNING

    fun poll(): TreadmillState = guarded {
        state = driver.poll()
        if (!state.safetyKeyIn) targetKph = 0.0
        state
    } ?: state

    fun start(kph: Double = limits.minKph) = guarded {
        check(state.safetyKeyIn) { "Safety key is out" }
        driver.setMode(FitPro.Mode.RUNNING)   // mode change in its own frame
        targetKph = clampKph(minOf(kph, startKphCap))
        driver.setSpeed(targetKph)
        lastSpeedCmd = clock()
    }

    fun setSpeed(kph: Double): Boolean = guarded {
        if (!state.safetyKeyIn || !running) return@guarded false
        val now = clock()
        if (now - lastSpeedCmd < minCommandGapMs) return@guarded false
        val wanted = clampKph(kph)
        targetKph = wanted.coerceIn(targetKph - maxKphStep, targetKph + maxKphStep)
        driver.setSpeed(targetKph); lastSpeedCmd = now
        true
    } ?: false

    fun setIncline(pct: Double): Boolean = guarded {
        val now = clock()
        if (now - lastGradeCmd < minCommandGapMs) return@guarded false
        targetGrade = pct.coerceIn(limits.minGrade, limits.maxGrade)
        driver.setIncline(targetGrade); lastGradeCmd = now
        true
    } ?: false

    /** Wake a board that has gone to sleep on its own idle timer. */
    fun wake() = guarded { driver.setMode(FitPro.Mode.IDLE) }

    fun pause() = guarded { driver.setSpeed(0.0); driver.setMode(FitPro.Mode.PAUSE); targetKph = 0.0 }

    /** Never rate-limited, never blocked. */
    fun stop() {
        targetKph = 0.0
        runCatching { driver.setSpeed(0.0) }
        runCatching { driver.setMode(FitPro.Mode.IDLE) }
    }

    private fun clampKph(k: Double) = k.coerceIn(limits.minKph, limits.maxKph)

    private fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        stop(); onFault(e); null
    }
}
