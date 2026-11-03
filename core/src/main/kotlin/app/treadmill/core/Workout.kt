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

/** Drives a SafeTreadmill through a workout. Call [tick] about once a second. */
class WorkoutRunner(private val tm: SafeTreadmill, val workout: Workout) {
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

    fun tick(seconds: Int = 1) {
        if (finished) return
        val i = currentIndex()
        if (i >= workout.segments.size) { finished = true; tm.stop(); return }
        val s = workout.segments[i]
        if (!tm.running && elapsed == 0) tm.start(s.kph)
        // Re-issue each tick until reached: SafeTreadmill may step or rate-limit changes.
        val wantKph = s.kph.coerceIn(tm.limits.minKph, tm.limits.maxKph)
        val wantGrade = s.inclinePct.coerceIn(tm.limits.minGrade, tm.limits.maxGrade)
        if (tm.targetKph != wantKph) tm.setSpeed(wantKph)
        if (tm.targetGrade != wantGrade) tm.setIncline(wantGrade)
        elapsed += seconds
    }
}
