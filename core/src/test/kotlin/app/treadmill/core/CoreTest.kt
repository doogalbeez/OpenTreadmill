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
    }
}
