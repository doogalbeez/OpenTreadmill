package app.treadmill.core

/** Calorie estimate from the ACSM walking and running equations. */
object Stats {
    const val KPH_PER_MPH = 1.609344

    /** kcal per minute at [kph] and [gradePct] for someone weighing [kg]. */
    fun kcalPerMinute(kph: Double, gradePct: Double, kg: Double): Double {
        if (kph <= 0.0) return 0.0
        val mPerMin = kph * 1000.0 / 60.0
        val grade = maxOf(gradePct, 0.0) / 100.0
        val vo2 = if (kph < 6.4) 0.1 * mPerMin + 1.8 * mPerMin * grade + 3.5
                  else 0.2 * mPerMin + 0.9 * mPerMin * grade + 3.5
        return vo2 * kg / 1000.0 * 5.0
    }

    /** Pace in seconds per km (or per mile when [perMile]). Null when stopped. */
    fun paceSeconds(kph: Double, perMile: Boolean): Int? {
        if (kph < 0.5) return null
        val perKm = 3600.0 / kph
        return (if (perMile) perKm * KPH_PER_MPH else perKm).toInt()
    }
}

/** Accumulates time, distance and calories from polled speed and incline. */
class SessionStats(private val kg: () -> Double) {
    var seconds = 0.0; private set
    var meters = 0.0; private set
    var kcal = 0.0; private set
    var maxKph = 0.0; private set

    fun add(dtSeconds: Double, kph: Double, gradePct: Double) {
        if (kph <= 0.0) return
        seconds += dtSeconds
        meters += kph / 3.6 * dtSeconds
        kcal += Stats.kcalPerMinute(kph, gradePct, kg()) * dtSeconds / 60.0
        maxKph = maxOf(maxKph, kph)
    }

    val avgKph get() = if (seconds > 0) meters / seconds * 3.6 else 0.0

    fun reset() { seconds = 0.0; meters = 0.0; kcal = 0.0; maxKph = 0.0 }
}
