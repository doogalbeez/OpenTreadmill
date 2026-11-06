package app.opentreadmill.core

import app.opentreadmill.core.FitPro.Field
import app.opentreadmill.core.FitPro.Mode
import kotlin.math.abs

/** Raw 64-byte frame transport (USB HID on the console, [SimulatedBoard] in tests and demo mode). */
interface FrameTransport : AutoCloseable {
    fun exchange(request: ByteArray): ByteArray
}

data class Limits(
    val minKph: Double = 1.6, val maxKph: Double = 19.3,
    val minGrade: Double = -3.0, val maxGrade: Double = 12.0,
)

data class Snapshot(
    val boardOk: Boolean = false,
    val mode: Mode = Mode.UNKNOWN,
    /** Latched: the board reports the key in bursts, so this stays set until the next Start. */
    val keyOut: Boolean = false,
    val beltKph: Double = 0.0,
    val targetKph: Double = 0.0,
    val goalKph: Double = 0.0,
    val incline: Double = 0.0,
    val targetIncline: Double = 0.0,
    val odometerM: Int = 0,
    val wantRunning: Boolean = false,
    val stopping: Boolean = false,
    val rejects: Int = 0,
    val lastStatus: String = "",
    /** Set when a command the person gave could not be carried out, e.g. the board wouldn't start. */
    val problem: String? = null,
)

/**
 * Drives a FitPro board. Call [tick] about 5 times a second, always from one thread;
 * call the commands from that same thread between ticks.
 *
 * Every tick sends one frame: the next queued write (if any) plus the telemetry reads.
 * A queued write is only dropped once the board answers it with status Done, so one bad
 * frame can't lose a command. Lessons from real FitPro boards (via strideApp):
 * - Pause is the only way out of Running. Idle is ignored while the belt moves.
 * - Speed writes are ignored unless the mode is Running.
 * - ActualKph may read 0 at every speed, so belt motion is judged from the odometer too.
 *
 * Safety net: whenever we don't want the belt running and it is moving, stop is re-sent.
 */
class Controller(
    private val io: FrameTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** The belt never starts faster than this; it ramps from here. */
    private val startKphCap: Double = 3.2,
    private val rampUpKphPerSec: Double = 1.0,
    private val rampDownKphPerSec: Double = 2.0,
) : AutoCloseable {
    var limits = Limits(); private set
    var snap = Snapshot(); private set

    private val queue = ArrayDeque<Map<Field, Int>>()
    private var tries = 0
    private var rejects = 0
    private var wantRunning = false
    private var goalKph = 0.0
    private var targetKph = 0.0
    private var targetGrade = 0.0
    private var keyLatched = false
    private var lastTick = 0L
    private var lastNetAt = 0L
    private var lastWakeAt = 0L
    private val odo = ArrayDeque<Pair<Long, Int>>()
    private var mode = Mode.UNKNOWN
    private var actualKph = 0.0
    /** After a refused frame, the next one is read-only so telemetry never goes stale. */
    private var readOnlyNext = false
    // Following the console's own buttons: when we last wrote each setting, when we last stopped,
    // and whether the board's speed/incline readings can be trusted: once non-zero, or once they echo our own write.
    private var lastSpeedWriteAt = 0L
    private var lastGradeWriteAt = 0L
    private var lastStopAt = 0L
    private var kphEchoed = false
    private var gradeEchoed = false
    private var problem: String? = null

    val running get() = mode == Mode.RUNNING && wantRunning

    /** Read the machine's limits. Falls back to safe defaults for any the board won't give. */
    fun connect() {
        fun one(f: Field): Double? = runCatching {
            val r = FitPro.decode(io.exchange(FitPro.readWriteRequest(reads = listOf(f))), listOf(f))
            if (r.ok) r.values.getValue(f) / 100.0 else null
        }.getOrNull()
        val d = Limits()
        limits = Limits(
            minKph = one(Field.MIN_KPH)?.takeIf { it in 0.5..5.0 } ?: d.minKph,
            maxKph = one(Field.MAX_KPH)?.takeIf { it in 5.0..30.0 } ?: d.maxKph,
            minGrade = one(Field.MIN_GRADE)?.takeIf { it in -10.0..0.0 } ?: d.minGrade,
            maxGrade = one(Field.MAX_GRADE)?.takeIf { it in 1.0..50.0 } ?: d.maxGrade,
        )
        log("limits $limits")
    }

    // ---- commands ----------------------------------------------------------------

    fun start(kph: Double = limits.minKph) {
        keyLatched = false            // the person says the key is back; the board will tell us if not
        wantRunning = true
        goalKph = clampKph(kph)
        targetKph = clampKph(minOf(goalKph, startKphCap))
        problem = null
        queue.clear()
        queue += RUN_FRAME
        queue += mapOf(Field.KPH to centi(targetKph))
        log("start → goal ${"%.1f".format(goalKph)}")
    }

    /** Sets where the belt should get to; it ramps there. Ignored unless running. */
    fun setSpeed(kph: Double): Boolean {
        if (!wantRunning || keyLatched) return false
        goalKph = clampKph(kph)
        return true
    }

    fun setIncline(pct: Double) {
        targetGrade = pct.coerceIn(limits.minGrade, limits.maxGrade)
        queue.removeAll { it.keys == setOf(Field.GRADE) }
        queue += mapOf(Field.GRADE to centi(targetGrade))
    }

    /** Belt to a halt, ready to resume. */
    fun pause() {
        wantRunning = false; goalKph = 0.0; targetKph = 0.0
        lastStopAt = clock()
        queue.clear()
        queue += STOP_FRAME
        log("pause")
    }

    /**
     * Belt to a halt, then the deck back down to 0%. The stop goes first; the incline write is
     * queued behind it. The board then stays in Pause: the real board refuses Idle from Pause,
     * and Start works from Pause. (Pause alone keeps the incline, so Resume carries on.)
     */
    fun stop() {
        pause()
        targetGrade = 0.0.coerceIn(limits.minGrade, limits.maxGrade)
        queue += mapOf(Field.GRADE to centi(targetGrade))
        log("stop")
    }

    // ---- the loop -------------------------------------------------------------------

    fun tick(): Snapshot {
        val now = clock()
        val dt = if (lastTick == 0L) 0.0 else (now - lastTick) / 1000.0
        lastTick = now

        ramp(dt)
        var writes = if (readOnlyNext) null else queue.firstOrNull()
        readOnlyNext = false
        // The board refuses Running from Results (where it lands after sitting paused a while),
        // so step through Idle first. The Running write stays queued until the board is out of Results.
        val viaIdle = writes == RUN_FRAME && mode == Mode.RESULTS
        if (viaIdle) writes = IDLE_FRAME
        val reads = READS
        val reply = try {
            FitPro.decode(io.exchange(FitPro.readWriteRequest(writes ?: emptyMap(), reads)), reads)
        } catch (e: Exception) {
            log("no reply: ${e.message}")
            snap = snap.copy(boardOk = false, lastStatus = e.message ?: "error")
            return snap
        }
        if (!reply.ok) {
            rejects++
            readOnlyNext = writes != null
            val why = FitPro.statusName(reply.status)
            if (rejects <= 5 || rejects % 50 == 0) log("refused ($why)" + (writes?.let { " write ${describe(it)}" } ?: ""))
            if (writes != null && ++tries > MAX_TRIES) {
                log("giving up on ${describe(writes)}")
                tries = 0
                if (queue.firstOrNull() == RUN_FRAME) startFailed() else queue.removeFirst()
            }
            snap = snap.copy(boardOk = false, lastStatus = why, rejects = rejects, wantRunning = wantRunning, problem = problem)
            return snap
        }
        if (writes != null) {
            if (!viaIdle) queue.removeFirst()
            tries = 0
            if (Field.KPH in writes) lastSpeedWriteAt = now
            if (Field.GRADE in writes) lastGradeWriteAt = now
            log("ok ${describe(writes)}")
        }

        val v = reply.values
        val oldMode = mode
        val newMode = Mode.of(v.getValue(Field.WORKOUT_MODE))
        if (newMode != mode) log("mode ${mode.name.lowercase()} → ${newMode.name.lowercase()}")
        mode = newMode
        followConsole(now, oldMode, v.getValue(Field.KPH) / 100.0, v.getValue(Field.GRADE) / 100.0)
        actualKph = v.getValue(Field.ACTUAL_KPH) / 100.0
        val incline = v.getValue(Field.ACTUAL_INCLINE) / 100.0
        val odometer = v.getValue(Field.DISTANCE)
        odo += now to odometer
        while (odo.size > 2 && now - odo.first().first > ODO_WINDOW_MS) odo.removeFirst()

        if (mode == Mode.SAFETY_KEY_OUT && !keyLatched) {
            keyLatched = true
            log("safety key out")
        }
        if (keyLatched) { wantRunning = false; goalKph = 0.0; targetKph = 0.0 }

        safetyNet(now)

        snap = Snapshot(
            boardOk = true, mode = mode, keyOut = keyLatched, beltKph = beltKph(),
            targetKph = targetKph, goalKph = goalKph, incline = incline, targetIncline = targetGrade,
            odometerM = odometer, wantRunning = wantRunning,
            stopping = !wantRunning && beltMoving(), rejects = rejects, lastStatus = "done", problem = problem,
        )
        return snap
    }

    /** Best evidence of belt speed: the board's own reading, or the odometer if that reads 0. */
    fun beltKph(): Double {
        if (odo.size < 2) return actualKph
        val (t0, m0) = odo.first(); val (t1, m1) = odo.last()
        val est = if (t1 > t0 && m1 >= m0) (m1 - m0) / ((t1 - t0) / 1000.0) * 3.6 else 0.0
        return maxOf(actualKph, est)
    }

    private fun beltMoving() = actualKph >= STOPPED_KPH || (odo.size >= 2 && odo.last().second > odo.first().second)

    private fun safetyNet(now: Long) {
        val moving = beltMoving()
        // We want it stopped but it is moving: keep telling it, about once a second.
        // Not while the key is out: the key has already cut the motor, and the board refuses
        // every mode write in that state.
        if (!wantRunning && moving && mode != Mode.SAFETY_KEY_OUT && queue.isEmpty() && now - lastNetAt > 1000) {
            lastNetAt = now
            log("belt moving while stopped — sending stop again")
            queue += STOP_FRAME
        }
        // The board sleeps on its own timer; Idle wakes it.
        if (mode == Mode.SLEEP && queue.isEmpty() && now - lastWakeAt > 5000) {
            lastWakeAt = now
            queue += mapOf(Field.WORKOUT_MODE to Mode.IDLE.code)
        }
    }


    private fun ramp(dt: Double) {
        if (!wantRunning || mode != Mode.RUNNING || dt <= 0) return
        if (targetKph == goalKph) return
        val next = if (targetKph < goalKph) minOf(goalKph, targetKph + rampUpKphPerSec * dt)
                   else maxOf(goalKph, targetKph - rampDownKphPerSec * dt)
        if (kotlin.math.abs(next - targetKph) < 0.1 && next != goalKph) return
        targetKph = next
        queue.removeAll { it.keys == setOf(Field.KPH) }
        queue += mapOf(Field.KPH to centi(targetKph))
    }

    /**
     * The console's own buttons talk to the board directly. Notice what they did and adopt it,
     * so the screen matches the belt and the next tap in the app starts from the real values.
     */
    private fun followConsole(now: Long, oldMode: Mode, boardKph: Double, boardGrade: Double) {
        val runQueued = queue.any { it == RUN_FRAME }
        // Stop pressed on the console: treated like STOP in the app, so the deck goes back down to 0% too.
        if (wantRunning && oldMode == Mode.RUNNING && mode in setOf(Mode.PAUSE, Mode.IDLE, Mode.RESULTS) && !runQueued) {
            log("stopped on the console")
            wantRunning = false; goalKph = 0.0; targetKph = 0.0
            queue.removeAll { Field.KPH in it.keys || Field.GRADE in it.keys }
            targetGrade = 0.0.coerceIn(limits.minGrade, limits.maxGrade)
            queue += mapOf(Field.GRADE to centi(targetGrade))
        }
        // Start pressed on the console. Not within 3 s of our own stop: then it is the board
        // ignoring us, and the safety net keeps stopping it.
        if (!wantRunning && !keyLatched && oldMode != Mode.RUNNING && oldMode != Mode.UNKNOWN && mode == Mode.RUNNING &&
            now - lastStopAt > CONSOLE_START_GRACE_MS) {
            val k = if (kphEchoed && boardKph > 0) boardKph else limits.minKph
            log("started on the console")
            wantRunning = true; goalKph = k; targetKph = k; problem = null
        }

        // Speed and incline: trusted once the board has echoed one of our own writes back.
        val speedQuiet = queue.none { Field.KPH in it.keys } && now - lastSpeedWriteAt > CONSOLE_SETTLE_MS
        if (!kphEchoed && (boardKph > 0 || lastSpeedWriteAt > 0 && abs(boardKph - targetKph) < 0.05)) kphEchoed = true
        if (kphEchoed && wantRunning && mode == Mode.RUNNING && speedQuiet && boardKph > 0 && abs(boardKph - targetKph) > 0.15) {
            log("speed set on the console: ${"%.1f".format(boardKph)}")
            goalKph = boardKph; targetKph = boardKph
        }
        val gradeQuiet = queue.none { Field.GRADE in it.keys } && now - lastGradeWriteAt > CONSOLE_SETTLE_MS
        if (!gradeEchoed && (boardGrade != 0.0 || lastGradeWriteAt > 0 && abs(boardGrade - targetGrade) < 0.05)) gradeEchoed = true
        if (gradeEchoed && gradeQuiet && abs(boardGrade - targetGrade) > 0.2) {
            log("incline set on the console: ${"%.1f".format(boardGrade)}")
            targetGrade = boardGrade
        }
    }

    /** The board would not go to Running: say so instead of acting as if the belt were on. */
    private fun startFailed() {
        wantRunning = false; goalKph = 0.0; targetKph = 0.0
        queue.clear()
        problem = "The treadmill didn't start (board mode ${mode.name.lowercase().replace('_', ' ')}). Press Start to try again."
        log("start failed in mode ${mode.name.lowercase()}")
    }

    private fun clampKph(k: Double) = k.coerceIn(limits.minKph, limits.maxKph)
    private fun centi(v: Double) = Math.round(v * 100).toInt()
    private fun describe(w: Map<Field, Int>) = w.entries.joinToString(", ") { (f, v) ->
        if (f == Field.WORKOUT_MODE) "mode=${Mode.of(v).name.lowercase()}" else "${f.name.lowercase()}=${v / 100.0}"
    }

    override fun close() = io.close()

    companion object {
        val READS = listOf(Field.DISTANCE, Field.WORKOUT_MODE, Field.ACTUAL_KPH, Field.ACTUAL_INCLINE, Field.KPH, Field.GRADE)
        const val CONSOLE_START_GRACE_MS = 3000L
        const val CONSOLE_SETTLE_MS = 1500L
        /**
         * Pause on its own. The real board fails the whole frame if it carries Kph 0 (below its
         * minimum speed), so v0.2's {Kph 0, Pause} was refused every time and never stopped the belt.
         */
        val STOP_FRAME = mapOf(Field.WORKOUT_MODE to Mode.PAUSE.code)
        val RUN_FRAME = mapOf(Field.WORKOUT_MODE to Mode.RUNNING.code)
        val IDLE_FRAME = mapOf(Field.WORKOUT_MODE to Mode.IDLE.code)
        const val MAX_TRIES = 15
        const val STOPPED_KPH = 0.3
        const val ODO_WINDOW_MS = 4000L
    }
}
