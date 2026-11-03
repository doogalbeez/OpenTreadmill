package app.opentreadmill

import android.content.Context
import app.opentreadmill.core.Segment
import app.opentreadmill.core.Stats
import app.opentreadmill.core.Workout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private fun mph(v: Double) = v * Stats.KPH_PER_MPH
private fun seg(min: Double, speedMph: Double, incline: Double, label: String) = Segment((min * 60).toInt(), mph(speedMph), incline, label)

/** Built-in workouts. Speeds are written in mph and stored in km/h. */
object WorkoutLibrary {
    const val WALK = "Walk"
    const val HILLS = "Hills"
    const val INTERVALS = "Intervals"
    const val RUN = "Run"
    const val MINE = "My workouts"
    val categories = listOf(WALK, HILLS, INTERVALS, RUN)

    private val warmWalk = seg(4.0, 2.8, 0.0, "Warm up")
    private val coolWalk = seg(3.0, 2.5, 0.0, "Cool down")

    fun builtIn(): List<Workout> = listOf(
        // ---- Walk
        Workout("Easy walk", listOf(seg(3.0, 2.5, 0.0, "Warm up"), seg(14.0, 3.2, 1.0, "Walk"), seg(3.0, 2.5, 0.0, "Cool down")),
            WALK, "A gentle 20 minutes at a comfortable pace."),
        Workout("Brisk walk", listOf(warmWalk, seg(23.0, 3.8, 1.0, "Brisk walk"), coolWalk),
            WALK, "Thirty minutes at a pace that gets you breathing."),
        Workout("Steep incline walk", listOf(seg(4.0, 2.8, 2.0, "Warm up"), seg(24.0, 3.0, 12.0, "Climb"), seg(4.0, 2.5, 0.0, "Cool down")),
            WALK, "A steady walk at 12% incline. Hard work without running."),
        Workout.intervals("Power walk intervals", warmWalk, seg(2.0, 4.0, 2.0, "Power walk"), seg(2.0, 3.0, 1.0, "Easy walk"), 5, coolWalk,
            WALK, "Five rounds of fast and easy walking."),
        Workout("Walk to jog", buildList {
            add(warmWalk)
            repeat(6) { add(seg(1.0, 4.5, 0.0, "Jog")); add(seg(2.0, 3.2, 0.0, "Walk")) }
            add(coolWalk)
        }, WALK, "Short jogs between walks, for building up to running."),

        // ---- Hills
        Workout("Hill climb", buildList {
            add(seg(5.0, 3.0, 0.0, "Warm up"))
            listOf(2.0, 4.0, 6.0, 8.0, 6.0, 4.0, 8.0, 10.0, 6.0, 2.0).forEach { add(seg(2.0, 3.3, it, "Hill ${it.toInt()}%")) }
            add(seg(5.0, 2.8, 0.0, "Cool down"))
        }, HILLS, "Ten two-minute hills, up to 10%."),
        Workout("Rolling hills", buildList {
            add(warmWalk)
            repeat(4) { listOf(3.0, 6.0, 9.0, 6.0, 3.0, 1.0).forEach { add(seg(1.0, 3.4, it, "Hill ${it.toInt()}%")) } }
            add(coolWalk)
        }, HILLS, "Up and down, four times over."),
        Workout("Mountain pyramid", buildList {
            add(warmWalk)
            val up = (1..12).map { it.toDouble() }
            (up + up.reversed().drop(1)).forEach { add(seg(1.5, 3.0, it, "Grade ${it.toInt()}%")) }
            add(coolWalk)
        }, HILLS, "One percent steeper every 90 seconds to 12%, then back down."),
        Workout("Incline ladder", buildList {
            add(warmWalk)
            listOf(2.0, 4.0, 6.0, 8.0, 10.0).forEach { g -> add(seg(3.0, 3.3, g, "Climb ${g.toInt()}%")); add(seg(1.0, 3.0, 0.0, "Recover")) }
            add(coolWalk)
        }, HILLS, "Climbs that get steeper, with a flat minute between each."),

        // ---- Intervals
        Workout.intervals("Run intervals 8 × 1 min", seg(5.0, 3.5, 1.0, "Warm up"), seg(1.0, 7.0, 1.0, "Run"), seg(1.5, 3.5, 1.0, "Recover"), 8,
            seg(5.0, 3.0, 0.0, "Cool down"), INTERVALS, "One-minute runs with walking recoveries."),
        Workout.intervals("Sprints 10 × 30 s", seg(5.0, 3.5, 0.0, "Warm up"), seg(0.5, 8.0, 1.0, "Sprint"), seg(1.5, 3.5, 0.0, "Recover"), 10,
            seg(5.0, 3.0, 0.0, "Cool down"), INTERVALS, "Short, fast efforts. The belt ramps up gently, so each sprint builds."),
        Workout("Pyramid intervals", buildList {
            add(seg(5.0, 3.5, 0.0, "Warm up"))
            listOf(1.0, 2.0, 3.0, 2.0, 1.0).forEach { m -> add(seg(m, 6.5, 1.0, "Run ${m.toInt()} min")); add(seg(2.0, 3.5, 0.0, "Recover")) }
            add(seg(5.0, 3.0, 0.0, "Cool down"))
        }, INTERVALS, "Runs of 1, 2, 3, 2 and 1 minutes."),
        Workout("Fartlek", listOf(
            seg(5.0, 3.5, 0.0, "Warm up"), seg(3.0, 5.0, 1.0, "Jog"), seg(1.0, 6.5, 1.0, "Push"), seg(2.0, 5.0, 1.0, "Jog"),
            seg(0.5, 7.5, 1.0, "Fast"), seg(3.0, 4.5, 1.0, "Easy jog"), seg(2.0, 6.0, 2.0, "Strong"), seg(2.0, 5.0, 1.0, "Jog"),
            seg(1.0, 7.0, 1.0, "Fast"), seg(3.0, 4.5, 0.0, "Easy jog"), seg(5.0, 3.0, 0.0, "Cool down"),
        ), INTERVALS, "Mixed-speed play: no two efforts the same."),

        // ---- Run
        Workout("Steady jog", listOf(seg(5.0, 3.5, 0.0, "Warm up"), seg(20.0, 5.5, 1.0, "Jog"), seg(5.0, 3.0, 0.0, "Cool down")),
            RUN, "Twenty minutes at an easy, even pace."),
        Workout("Tempo run", listOf(seg(5.0, 3.5, 0.0, "Warm up"), seg(5.0, 5.5, 1.0, "Easy"), seg(15.0, 6.5, 1.0, "Tempo"),
            seg(5.0, 5.0, 1.0, "Easy"), seg(5.0, 3.0, 0.0, "Cool down")), RUN, "Fifteen minutes comfortably hard."),
        Workout("Progression run", buildList {
            add(seg(5.0, 3.5, 0.0, "Warm up"))
            listOf(5.0, 5.5, 6.0, 6.5, 7.0).forEach { add(seg(5.0, it, 1.0, "$it mph")) }
            add(seg(5.0, 3.0, 0.0, "Cool down"))
        }, RUN, "Half a mile an hour faster every five minutes."),
        Workout("5K", listOf(seg(5.0, 3.5, 0.0, "Warm up"), seg(31.0, 6.0, 1.0, "5K at 6 mph"), seg(5.0, 3.0, 0.0, "Cool down")),
            RUN, "About 3.1 miles at 6 mph, plus warm up and cool down."),
        Workout("Long easy run", listOf(seg(5.0, 3.5, 0.0, "Warm up"), seg(40.0, 5.2, 1.0, "Easy run"), seg(5.0, 3.0, 0.0, "Cool down")),
            RUN, "Fifty minutes, mostly at a relaxed pace."),
    )

    fun customIntervals(workMph: Double, restMph: Double, workSec: Int, restSec: Int, rounds: Int, inclinePct: Double) =
        Workout.intervals("Custom intervals",
            Segment(300, mph(minOf(restMph, 3.5)), 0.0, "Warm up"),
            Segment(workSec, mph(workMph), inclinePct, "Work"),
            Segment(restSec, mph(restMph), inclinePct, "Recover"), rounds,
            Segment(180, mph(minOf(restMph, 3.0)), 0.0, "Cool down"), MINE)

    /** Starting point for the workout builder. */
    fun blank() = Workout("My workout", listOf(seg(5.0, 3.0, 0.0, "Warm up"), seg(20.0, 4.0, 1.0, "Main"), seg(5.0, 2.8, 0.0, "Cool down")),
        MINE, id = UUID.randomUUID().toString())
}

/** Workouts built on the console, as one JSON array in the app's private storage. */
class CustomWorkoutStore(private val file: File) {

    fun all(): List<Workout> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val segs = o.getJSONArray("segments")
            Workout(o.getString("name"), (0 until segs.length()).map { j ->
                val s = segs.getJSONObject(j)
                Segment(s.getInt("seconds"), s.getDouble("kph"), s.getDouble("incline"), s.optString("label"))
            }, WorkoutLibrary.MINE, o.optString("description"), o.getString("id"))
        }
    }.getOrDefault(emptyList())

    fun save(w: Workout) {
        val list = all().filter { it.id != w.id } + w.copy(category = WorkoutLibrary.MINE)
        write(list)
    }

    fun delete(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<Workout>) {
        val a = JSONArray()
        list.forEach { w ->
            a.put(JSONObject().apply {
                put("id", w.id); put("name", w.name); put("description", w.description)
                put("segments", JSONArray().apply {
                    w.segments.forEach { s -> put(JSONObject().apply { put("seconds", s.seconds); put("kph", s.kph); put("incline", s.inclinePct); put("label", s.label) }) }
                })
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }
}

data class HistoryEntry(val startedAt: Long, val seconds: Int, val meters: Double, val kcal: Double, val avgKph: Double, val workout: String?,
                        val climbM: Double = 0.0)

/** Finished sessions, one JSON object per line in the app's private storage. */
class HistoryStore(private val file: File) {

    fun add(e: HistoryEntry) {
        file.appendText(JSONObject().apply {
            put("startedAt", e.startedAt); put("seconds", e.seconds); put("meters", e.meters)
            put("kcal", e.kcal); put("avgKph", e.avgKph); put("workout", e.workout ?: JSONObject.NULL); put("climbM", e.climbM)
        }.toString() + "\n")
    }

    fun all(): List<HistoryEntry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        runCatching {
            val o = JSONObject(line)
            HistoryEntry(o.getLong("startedAt"), o.getInt("seconds"), o.getDouble("meters"), o.getDouble("kcal"),
                o.getDouble("avgKph"), if (o.isNull("workout")) null else o.getString("workout"), o.optDouble("climbM", 0.0))
        }.getOrNull()
    }.sortedByDescending { it.startedAt }

    /** CSV export, written next to the history file. */
    fun exportCsv(): File = File(file.parentFile, file.nameWithoutExtension + ".csv").apply {
        writeText("started_at,seconds,km,kcal,avg_kph,climb_m,workout\n" + all().joinToString("") {
            "${it.startedAt},${it.seconds},${"%.3f".format(it.meters / 1000)},${"%.0f".format(it.kcal)},${"%.2f".format(it.avgKph)},${"%.0f".format(it.climbM)},${it.workout ?: ""}\n"
        })
    }
}

