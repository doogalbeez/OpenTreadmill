package app.treadmill.core

/** One step of a workout: hold this speed and incline for this long. */
data class Segment(val seconds: Int, val kph: Double, val inclinePct: Double, val label: String = "")

data class Workout(val name: String, val segments: List<Segment>) {
    val totalSeconds get() = segments.sumOf { it.seconds }

    companion object {
        fun intervals(name: String, warmup: Segment, work: Segment, rest: Segment, rounds: Int, cooldown: Segment) =
            Workout(name, listOf(warmup) + List(rounds) { listOf(work, rest) }.flatten() + cooldown)
    }
}

/** Drives a [Controller] through a workout. Call [tick] once per second of running. */
class WorkoutRunner(private val tm: Controller, val workout: Workout) {
    var elapsed = 0; private set
    var finished = false; private set

    fun currentIndex(): Int {
        var t = elapsed
        workout.segments.forEachIndexed { i, s -> if (t < s.seconds) return i; t -= s.seconds }
        return workout.segments.size
    }

    fun secondsLeftInSegment(): Int {
        var t = elapsed
        for (s in workout.segments) { if (t < s.seconds) return s.seconds - t; t -= s.seconds }
        return 0
    }

    /** Begin: starts the belt toward the first segment. */
    fun begin() {
        val s = workout.segments.firstOrNull() ?: return
        tm.start(s.kph)
        tm.setIncline(s.inclinePct)
    }

    fun tick(seconds: Int = 1) {
        if (finished) return
        elapsed += seconds
        val i = currentIndex()
        if (i >= workout.segments.size) { finished = true; tm.stop(); return }
        val s = workout.segments[i]
        tm.setSpeed(s.kph)
        if (tm.snap.targetIncline != s.inclinePct.coerceIn(tm.limits.minGrade, tm.limits.maxGrade)) tm.setIncline(s.inclinePct)
    }
}
