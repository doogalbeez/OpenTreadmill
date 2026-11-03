package app.opentreadmill.core

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
        val body = byteArrayOf(4, 9, 2, 2, 0xF4.toByte(), 0x01, 0xD4.toByte(), 0xFE.toByte())  // 5.00 kph, -3.00 %
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

class StartAndStatsTest {
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
        repeat(3600) { s.add(1.0, 10.0, 0.0); s.addMeters(10_000.0 / 3600) }
        assertEquals(10_000.0, s.meters, 0.001)
        assertEquals(10.0, s.avgKph, 0.001)
        assertEquals(0.0, s.climbM)
    }

    @Test fun climbCountsOnlyUphill() {
        val s = SessionStats { 70.0 }
        repeat(100) { s.addMeters(10.0, 5.0) }    // 1 km at 5 %
        repeat(100) { s.addMeters(10.0, -3.0) }   // 1 km downhill
        assertEquals(50.0, s.climbM, 0.001)
        assertEquals(2000.0, s.meters, 0.001)
    }

    @Test fun scalingKeepsWarmUpAndCoolDown() {
        val w = Workout("t", listOf(Segment(300, 5.0, 0.0, "Warm up"), Segment(600, 8.0, 1.0, "Run"), Segment(600, 6.0, 2.0, "Jog"),
            Segment(180, 4.0, 0.0, "Cool down")))
        val longer = w.scaledTo(w.totalSeconds + 600)
        assertEquals(300, longer.segments.first().seconds)
        assertEquals(180, longer.segments.last().seconds)
        assertEquals(w.totalSeconds + 600, longer.totalSeconds)
        assertEquals(longer.segments[1].seconds, longer.segments[2].seconds)   // in proportion
        val tiny = w.scaledTo(60)
        assertEquals(w.minScaledSeconds, tiny.totalSeconds)
        assertTrue(tiny.segments.all { it.seconds >= 30 })
    }

    @Test fun workoutSegmentLookup() {
        val w = Workout("t", listOf(Segment(60, 5.0, 0.0, "a"), Segment(30, 8.0, 2.0, "b")))
        assertEquals("a", w.segmentAt(59)?.label)
        assertEquals("b", w.segmentAt(60)?.label)
        assertNull(w.segmentAt(90))
        assertEquals(8.0, w.maxKph)
        assertEquals(60 * 5.0 / 3.6 + 30 * 8.0 / 3.6, w.plannedMeters, 0.001)
    }
}
