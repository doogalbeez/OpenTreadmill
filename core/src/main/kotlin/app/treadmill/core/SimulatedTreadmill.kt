package app.treadmill.core

/** In-memory treadmill for development off the machine. Ramps toward targets like a real belt. */
class SimulatedTreadmill(
    private val limits: Limits = Limits(),
    private val kphPerSecond: Double = 1.0,
    private val gradePerSecond: Double = 0.5,
) : TreadmillDriver {
    var mode = FitPro.Mode.IDLE; private set
    var safetyKeyIn = true
    private var targetKph = 0.0
    private var targetGrade = 0.0
    private var kph = 0.0
    private var grade = 0.0
    private var distanceM = 0.0

    /** Advance simulated time. */
    fun tick(seconds: Double) {
        if (!safetyKeyIn) { mode = FitPro.Mode.SAFETY_KEY_OUT; targetKph = 0.0 }
        val tk = if (mode == FitPro.Mode.RUNNING) targetKph else 0.0
        kph = approach(kph, tk, kphPerSecond * seconds)
        grade = approach(grade, targetGrade, gradePerSecond * seconds)
        distanceM += kph / 3.6 * seconds
    }

    private fun approach(v: Double, t: Double, step: Double) = if (v < t) minOf(t, v + step) else maxOf(t, v - step)

    override fun readLimits() = limits
    override fun poll() = TreadmillState(mode, kph, grade, distanceM.toInt(), safetyKeyIn)
    override fun setMode(mode: FitPro.Mode) { if (safetyKeyIn) this.mode = mode }
    override fun setSpeed(kph: Double) { if (mode == FitPro.Mode.RUNNING) targetKph = kph }  // ignored when idle, like the real board
    override fun setIncline(pct: Double) { targetGrade = pct }
    override fun close() {}
}
