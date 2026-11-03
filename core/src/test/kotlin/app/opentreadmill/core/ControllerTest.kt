package app.opentreadmill.core

import app.opentreadmill.core.FitPro.Mode
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
        assertEquals(Mode.PAUSE, r.board.mode, r.log.joinToString("\n"))
        // Regressions: v0.2 sent {Kph 0, Pause} and then Idle; the real board fails both.
        assertEquals(0, r.board.rejectedFrames, r.log.joinToString("\n"))
    }

    @Test fun followsTheConsoleSpeedButtons() {
        // Regression: speed changed on the console's buttons left the app showing its own old target.
        val r = Rig()
        r.c.start(5.0); r.run(10.0)
        r.board.consoleSpeed(19.0); r.run(3.0)
        assertEquals(19.0, r.c.snap.targetKph, 0.01, r.log.joinToString("\n"))
        r.board.consoleSpeed(1.6); r.run(3.0)
        assertEquals(1.6, r.c.snap.targetKph, 0.01)
        // The next tap in the app starts from the console's speed, not the app's stale one.
        assertTrue(r.c.setSpeed(r.c.snap.goalKph + 0.5)); r.run(3.0)
        assertEquals(2.1, r.board.read(FitPro.Field.KPH) / 100.0, 0.05)
        assertEquals(0, r.board.rejectedFrames)
    }

    @Test fun followsTheConsoleInclineButtons() {
        val r = Rig()
        r.c.setIncline(2.0); r.run(3.0)
        r.board.consoleIncline(8.0); r.run(3.0)
        assertEquals(8.0, r.c.snap.targetIncline, 0.01, r.log.joinToString("\n"))
    }

    @Test fun stopLevelsTheDeckButPauseKeepsIt() {
        val r = Rig()
        r.c.start(4.0); r.c.setIncline(6.0); r.run(15.0)
        r.c.pause(); r.run(5.0)
        assertEquals(6.0, r.board.grade, 0.01)        // paused: incline kept for Resume
        r.c.start(4.0); r.run(3.0)
        r.c.stop(); r.run(15.0)
        assertEquals(0.0, r.board.beltKph)
        assertEquals(0.0, r.board.grade, 0.01, r.log.joinToString("\n"))
        assertEquals(Mode.PAUSE, r.board.mode)
        assertEquals(0, r.board.rejectedFrames)
    }

    @Test fun followsConsoleInclineEvenIfTheAppNeverSetOne() {
        // Regression: incline changed only on the console was ignored until the app had set one itself.
        val r = Rig()
        r.c.start(4.0); r.run(5.0)
        r.board.consoleIncline(6.0); r.run(3.0)
        assertEquals(6.0, r.c.snap.targetIncline, 0.01, r.log.joinToString("\n"))
    }

    @Test fun consoleStopAndStartAreFollowed() {
        val r = Rig()
        r.c.start(4.0); r.run(8.0)
        r.board.consoleStop(); r.run(2.0)
        assertFalse(r.c.snap.wantRunning, r.log.joinToString("\n"))
        assertEquals(Mode.PAUSE, r.board.mode)
        r.run(5.0)
        r.board.consoleStart(); r.run(3.0)
        assertTrue(r.c.snap.wantRunning, r.log.joinToString("\n"))
        assertEquals(Mode.RUNNING, r.board.mode)    // not stopped by the safety net
    }

    @Test fun safetyNetStillWinsRightAfterOurStop() {
        val r = Rig()
        r.c.start(4.0); r.run(8.0)
        r.c.stop(); r.run(0.4)
        r.board.consoleStart(); r.run(4.0)            // within 3 s of our STOP: treated as the board ignoring us
        assertFalse(r.c.snap.wantRunning)
        assertEquals(Mode.PAUSE, r.board.mode, r.log.joinToString("\n"))
    }

    @Test fun stepCanBeLengthenedAndShortened() {
        val r = Rig()
        val w = Workout("t", listOf(Segment(60, 4.0, 0.0, "a"), Segment(60, 6.0, 0.0, "b")))
        val wr = WorkoutRunner(r.c, w)
        wr.begin(); r.run(3.0)
        repeat(20) { wr.tick() }                       // 20 s into step a
        assertTrue(wr.adjustCurrentStep(60))
        assertEquals(120, wr.workout.segments[0].seconds)
        assertEquals(100, wr.secondsLeftInSegment())
        wr.adjustCurrentStep(-600)                     // can't cut below 10 s from now
        assertEquals(30, wr.workout.segments[0].seconds)
        assertEquals(10, wr.secondsLeftInSegment())
    }

    @Test fun startsFromResults() {
        // Regression: after ~10 min paused the real board sits in Results and refuses Running.
        val r = Rig()
        r.c.start(4.0); r.run(6.0)
        r.c.stop(); r.run(6.0)
        r.board.timeOutToResults(); r.run(1.0)
        r.c.start(4.0); r.run(6.0)
        assertEquals(Mode.RUNNING, r.board.mode, r.log.joinToString("\n"))
        assertTrue(r.board.beltKph > 1.0)
        assertEquals(0, r.board.rejectedFrames, r.log.joinToString("\n"))
    }

    @Test fun failedStartIsReportedNotFaked() {
        val r = Rig()
        r.board.refuseRunning = true
        r.c.start(4.0); r.run(10.0)
        assertFalse(r.c.snap.wantRunning, r.log.joinToString("\n"))
        assertNotNull(r.c.snap.problem)
        // Telemetry keeps flowing between refused frames.
        assertTrue(r.c.snap.boardOk)
    }

    @Test fun startAgainAfterStop() {
        val r = Rig()
        r.c.start(4.0); r.run(6.0)
        r.c.stop(); r.run(6.0)
        r.c.start(4.0); r.run(6.0)
        assertEquals(Mode.RUNNING, r.board.mode, r.log.joinToString("\n"))
        assertTrue(r.board.beltKph > 1.0)
        assertEquals(0, r.board.rejectedFrames)
    }

    @Test fun pauseFromSpeedIsAcceptedFirstTime() {
        val r = Rig()
        r.c.start(4.83); r.run(10.0)
        r.c.pause(); r.run(0.2)
        assertEquals(Mode.PAUSE, r.board.mode, r.log.joinToString("\n"))
        assertEquals(0, r.board.rejectedFrames)
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
        // Regression: the safety net sent Pause while the key was out; the real board refuses that.
        assertEquals(0, r.board.rejectedFrames, r.log.joinToString("\n"))
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
        assertEquals(Mode.PAUSE, r.board.mode)
        assertEquals(0, r.board.rejectedFrames)
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
