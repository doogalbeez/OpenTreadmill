package app.treadmill.core

import app.treadmill.core.FitPro.Mode
import kotlin.test.*

/** Runs a Controller against the quirky SimulatedBoard in fake time. */
class Rig(reject: Int = 0, actual: Boolean = false) {
    var now = 1_000L
    val board = SimulatedBoard(rejectEvery = reject, reportsActualKph = actual)
    val log = mutableListOf<String>()
    val c = Controller(board, clock = { now }, log = { log += it }).apply { connect() }

    fun run(seconds: Double) {
        val steps = (seconds * 5).toInt()
        repeat(steps) { now += 200; board.tick(0.2); c.tick() }
    }
}

class ControllerTest {
    @Test fun stopActuallyStopsTheBelt() {
        // Regression: v0.1 sent Idle, which a running board ignores, so STOP did nothing.
        val r = Rig()
        r.c.start(5.0); r.run(10.0)
        assertEquals(Mode.RUNNING, r.board.mode)
        assertTrue(r.board.beltKph > 3.0)
        r.c.stop(); r.run(6.0)
        assertEquals(0.0, r.board.beltKph)
        assertEquals(Mode.IDLE, r.board.mode, r.log.joinToString("\n"))
    }

    @Test fun startAndStopSurviveRefusedFrames() {
        val r = Rig(reject = 2)   // every other frame is refused
        r.c.start(4.0); r.run(10.0)
        assertEquals(Mode.RUNNING, r.board.mode)
        r.c.stop(); r.run(6.0)
        assertEquals(0.0, r.board.beltKph)
    }

    @Test fun startsGentlyAndRampsToGoal() {
        val r = Rig()
        r.c.start(10.0); r.run(1.0)
        assertTrue(r.c.snap.targetKph <= 4.5, "target ${r.c.snap.targetKph}")
        r.run(15.0)
        assertEquals(10.0, r.c.snap.targetKph, 0.01)
    }

    @Test fun beltSpeedComesFromTheOdometerWhenActualKphReadsZero() {
        val r = Rig(actual = false)
        r.c.start(6.0); r.run(15.0)
        assertEquals(6.0, r.c.snap.beltKph, 0.8)
    }

    @Test fun safetyKeyLatchesAndRefusesSpeed() {
        val r = Rig()
        r.c.start(5.0); r.run(8.0)
        r.board.keyIn = false; r.run(2.0)
        assertTrue(r.c.snap.keyOut)
        assertFalse(r.c.setSpeed(8.0))
        assertEquals(0.0, r.board.beltKph)
        // Key back in, Start again: runs.
        r.board.keyIn = true
        r.c.start(4.0); r.run(6.0)
        assertFalse(r.c.snap.keyOut)
        assertEquals(Mode.RUNNING, r.board.mode)
    }

    @Test fun pauseThenResume() {
        val r = Rig()
        r.c.start(5.0); r.run(8.0)
        r.c.pause(); r.run(4.0)
        assertEquals(Mode.PAUSE, r.board.mode)
        assertEquals(0.0, r.board.beltKph)
        r.c.start(5.0); r.run(8.0)
        assertEquals(Mode.RUNNING, r.board.mode)
        assertTrue(r.board.beltKph > 3.0)
    }

    @Test fun inclineMovesWithoutRunning() {
        val r = Rig()
        r.c.setIncline(4.0); r.run(10.0)
        assertEquals(4.0, r.board.grade, 0.01)
    }

    @Test fun workoutRunsToTheEndAndStops() {
        val r = Rig()
        val w = Workout.intervals("2x", Segment(30, 4.0, 1.0), Segment(30, 8.0, 2.0), Segment(30, 5.0, 0.0), 2, Segment(30, 4.0, 0.0))
        val wr = WorkoutRunner(r.c, w)
        wr.begin()
        var maxBelt = 0.0
        while (!wr.finished) { r.run(1.0); if (r.c.snap.mode == Mode.RUNNING) wr.tick(); maxBelt = maxOf(maxBelt, r.board.beltKph) }
        r.run(8.0)
        assertTrue(maxBelt > 7.0)
        assertEquals(0.0, r.board.beltKph)
        assertEquals(Mode.IDLE, r.board.mode)
    }
}

class CodecStatusTest {
    @Test fun refusedResponsesCarryNoValues() {
        val frame = FitPro.response(4, 4, listOf(FitPro.Field.ACTUAL_KPH to 500))
        val r = FitPro.decode(frame, listOf(FitPro.Field.ACTUAL_KPH))
        assertFalse(r.ok)
        assertTrue(r.values.isEmpty())
    }

    @Test fun requestRoundTrips() {
        val req = FitPro.readWriteRequest(mapOf(FitPro.Field.KPH to 0, FitPro.Field.WORKOUT_MODE to 3), Controller.READS)
        val (w, rd) = FitPro.parseRequest(req)
        assertEquals(mapOf(FitPro.Field.KPH to 0, FitPro.Field.WORKOUT_MODE to 3), w)
        assertEquals(Controller.READS.sortedBy { it.id }, rd)
    }
}
