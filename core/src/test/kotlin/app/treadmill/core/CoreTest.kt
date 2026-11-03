package app.treadmill.core

import kotlin.test.*

class FitProCodecTest {
    @Test fun matchesDocumentedReadSpeedAndInclineFrame() {
        val req = FitPro.readWriteRequest(reads = listOf(FitPro.Field.ACTUAL_INCLINE, FitPro.Field.ACTUAL_KPH))
        assertContentEquals(byteArrayOf(0x04, 0x09, 0x02, 0x00, 0x03, 0x00, 0x00, 0x03, 0x15), req)
    }

    @Test fun refusesBrickingCommands() {
        assertFailsWith<IllegalArgumentException> { FitPro.frame(2, 9, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { FitPro.frame(4, 56, byteArrayOf()) }
    }

    @Test fun decodesSignedIncline() {
        val body = byteArrayOf(4, 9, 2, 0, 0xF4.toByte(), 0x01, 0xD4.toByte(), 0xFE.toByte())  // 5.00 kph, -3.00 %
        val frame = body + FitPro.checksum(body, body.size)
        val r = FitPro.decode(frame, listOf(FitPro.Field.ACTUAL_KPH, FitPro.Field.ACTUAL_INCLINE))
        assertEquals(500, r.values[FitPro.Field.ACTUAL_KPH])
        assertEquals(-300, r.values[FitPro.Field.ACTUAL_INCLINE])
    }

    @Test fun rejectsBadChecksum() {
        assertFailsWith<FitPro.ProtocolException> { FitPro.decode(byteArrayOf(4, 6, 2, 0, 1, 0), emptyList()) }
    }

    @Test fun encodesWriteSection() {
        val req = FitPro.readWriteRequest(writes = mapOf(FitPro.Field.KPH to 500))
        // write mask: 1 group, bit0; value 0x01F4 LE; empty read section
        assertContentEquals(byteArrayOf(4, 9, 2, 1, 1, 0xF4.toByte(), 1, 0), req.copyOf(8))
    }
}

class SafeTreadmillTest {
    private var now = 0L
    private fun make(sim: SimulatedTreadmill = SimulatedTreadmill()) = sim to SafeTreadmill(sim, clock = { now })

    @Test fun clampsAndStepsSpeed() {
        val (_, tm) = make()
        tm.start(3.0); tm.poll()
        now += 1000
        assertTrue(tm.setSpeed(50.0))
        assertEquals(5.0, tm.targetKph)  // max 2 kph step
    }

    @Test fun rateLimits() {
        val (_, tm) = make()
        tm.start(3.0); tm.poll()
        now += 100
        assertFalse(tm.setSpeed(4.0))
    }

    @Test fun refusesSpeedWithSafetyKeyOut() {
        val (sim, tm) = make()
        tm.start(3.0); sim.safetyKeyIn = false; sim.tick(1.0); tm.poll()
        now += 1000
        assertFalse(tm.setSpeed(6.0))
        assertEquals(0.0, tm.targetKph)
    }

    @Test fun stopsBeltOnDriverError() {
        val sim = SimulatedTreadmill()
        var fault: Throwable? = null
        val broken = object : TreadmillDriver by sim {
            var fail = false
            override fun setSpeed(kph: Double) { if (fail && kph > 0) error("io") ; sim.setSpeed(kph) }
        }
        val tm = SafeTreadmill(object : TreadmillDriver by broken {
            override fun setSpeed(kph: Double) { if (kph > 0 && now > 0) throw java.io.IOException("usb gone"); sim.setSpeed(kph) }
        }, clock = { now }, onFault = { fault = it })
        tm.start(3.0); tm.poll(); now += 1000
        assertFalse(tm.setSpeed(4.0))
        assertNotNull(fault)
        assertEquals(FitPro.Mode.IDLE, sim.mode)
    }
}

class WorkoutRunnerTest {
    @Test fun runsIntervalsToTheEnd() {
        var now = 0L
        val sim = SimulatedTreadmill()
        val tm = SafeTreadmill(sim, clock = { now })
        val w = Workout.intervals("4x1",
            Segment(60, 5.0, 1.0), Segment(60, 10.0, 2.0), Segment(60, 6.0, 0.0), 4, Segment(60, 4.0, 0.0))
        val r = WorkoutRunner(tm, w)
        val seen = mutableSetOf<Double>()
        while (!r.finished) { r.tick(); now += 1000; sim.tick(1.0); tm.poll(); seen += tm.targetKph }
        assertTrue(10.0 in seen)
        assertEquals(FitPro.Mode.IDLE, sim.mode)
        assertEquals(w.totalSeconds, r.elapsed)
    }
}

class StartAndStatsTest {
    @Test fun startIsGentleEvenForFastTargets() {
        val tm = SafeTreadmill(SimulatedTreadmill(), clock = { 0L })
        tm.start(12.0)
        assertEquals(3.2, tm.targetKph)
    }

    @Test fun caloriesAreInAPlausibleRange() {
        // 70 kg walking 5 km/h flat: roughly 4 kcal/min
        val w = Stats.kcalPerMinute(5.0, 0.0, 70.0)
        assertTrue(w in 3.0..5.0, "walk $w")
        // 70 kg running 10 km/h flat: roughly 12 kcal/min
        val r = Stats.kcalPerMinute(10.0, 0.0, 70.0)
        assertTrue(r in 10.0..14.0, "run $r")
    }

    @Test fun pace() {
        assertEquals(360, Stats.paceSeconds(10.0, perMile = false))
        assertEquals(579, Stats.paceSeconds(10.0, perMile = true))
        assertNull(Stats.paceSeconds(0.0, perMile = false))
    }

    @Test fun sessionAccumulates() {
        val s = SessionStats { 70.0 }
        repeat(3600) { s.add(1.0, 10.0, 0.0) }
        assertEquals(10_000.0, s.meters, 0.001)
        assertEquals(10.0, s.avgKph, 0.001)
    }
}
