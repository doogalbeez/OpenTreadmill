package app.opentreadmill.core

/** One step of a workout: hold this speed and incline for this long. */
data class Segment(val seconds: Int, val kph: Double, val inclinePct: Double, val label: String = "")

data class Workout(
    val name: String,
    val segments: List<Segment>,
    val category: String = "",
    val description: String = "",
    /** Set for workouts the person built on the console; built-ins have none. */
    val id: String? = null,
) {
    val totalSeconds get() = segments.sumOf { it.seconds }
    val maxKph get() = segments.maxOfOrNull { it.kph } ?: 0.0
    val maxIncline get() = segments.maxOfOrNull { it.inclinePct } ?: 0.0
    /** Planned distance in metres if every segment is held at its speed. */
    val plannedMeters get() = segments.sumOf { it.kph / 3.6 * it.seconds }

    /** The segment running at [elapsed] seconds, or null once finished. */
    fun segmentAt(elapsed: Int): Segment? {
        var t = elapsed
        for (s in segments) { if (t < s.seconds) return s; t -= s.seconds }
        return null
    }

    private val fixedHead get() = if (segments.size > 2 && segments.first().label.startsWith("Warm")) 1 else 0
    private val fixedTail get() = if (segments.size > 2 && segments.last().label.startsWith("Cool")) 1 else 0

    /** Shortest length [scaledTo] can make: warm-up and cool-down plus 30 s per main step. */
    val minScaledSeconds get() = segments.take(fixedHead).sumOf { it.seconds } + segments.takeLast(fixedTail).sumOf { it.seconds } +
        30 * (segments.size - fixedHead - fixedTail)

    /**
     * The same workout made about [targetSeconds] long. Warm-up and cool-down keep their length;
     * the main steps stretch or shrink in proportion, in whole 10-second units.
     */
    fun scaledTo(targetSeconds: Int): Workout {
        if (targetSeconds == totalSeconds) return this
        val head = segments.take(fixedHead)
        val tail = segments.takeLast(fixedTail)
        val main = segments.subList(fixedHead, segments.size - fixedTail)
        val fixed = head.sumOf { it.seconds } + tail.sumOf { it.seconds }
        val mainTotal = main.sumOf { it.seconds }.coerceAtLeast(1)
        val f = (targetSeconds.coerceAtLeast(minScaledSeconds) - fixed).toDouble() / mainTotal
        val scaled = main.map { it.copy(seconds = (Math.round(it.seconds * f / 10.0) * 10).toInt().coerceAtLeast(30)) }
        return copy(segments = head + scaled + tail)
    }

    companion object {
        fun intervals(name: String, warmup: Segment, work: Segment, rest: Segment, rounds: Int, cooldown: Segment,
                      category: String = "", description: String = "") =
            Workout(name, listOf(warmup) + List(rounds) { listOf(work, rest) }.flatten() + cooldown, category, description)
    }
}

/** Drives a [Controller] through a workout. Call [tick] once per second of running. */
class WorkoutRunner(private val tm: Controller, workout: Workout) {
    var workout = workout; private set
    var elapsed = 0; private set
    var finished = false; private set

    /**
     * Lengthen or shorten the step that is running now by [deltaSeconds]. It can't be cut
     * below 10 seconds from now. Returns false once the workout has finished.
     */
    fun adjustCurrentStep(deltaSeconds: Int): Boolean {
        val i = currentIndex()
        if (finished || i >= workout.segments.size) return false
        val s = workout.segments[i]
        val startOfStep = workout.segments.take(i).sumOf { it.seconds }
        val into = elapsed - startOfStep
        val newLen = (s.seconds + deltaSeconds).coerceIn(into + 10, 3 * 3600)
        workout = workout.copy(segments = workout.segments.toMutableList().also { it[i] = s.copy(seconds = newLen) })
        return true
    }

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

    private var appliedIndex = 0

    /**
     * Speed and incline are set when a step begins. Changes made during a step (in the app or on
     * the console's buttons) are kept until the next step, instead of being overwritten every second.
     */
    fun tick(seconds: Int = 1) {
        if (finished) return
        elapsed += seconds
        val i = currentIndex()
        if (i >= workout.segments.size) { finished = true; tm.stop(); return }
        if (i == appliedIndex) return
        appliedIndex = i
        val s = workout.segments[i]
        tm.setSpeed(s.kph)
        tm.setIncline(s.inclinePct)
    }
}
