package app.treadmill

import android.content.Context
import app.treadmill.core.Segment
import app.treadmill.core.Stats
import app.treadmill.core.Workout
import org.json.JSONObject
import java.io.File

private fun mph(v: Double) = v * Stats.KPH_PER_MPH

/** Built-in workouts. Speeds are written in mph and stored in km/h. */
object WorkoutLibrary {
    fun builtIn(): List<Workout> = listOf(
        Workout("Easy walk, 20 min", listOf(
            Segment(180, mph(2.5), 0.0, "Warm up"),
            Segment(840, mph(3.2), 1.0, "Walk"),
            Segment(180, mph(2.5), 0.0, "Cool down"),
        )),
        Workout("Hill climb, 30 min", buildList {
            add(Segment(300, mph(3.0), 0.0, "Warm up"))
            listOf(2.0, 4.0, 6.0, 8.0, 6.0, 4.0, 8.0, 10.0, 6.0, 2.0).forEach { add(Segment(120, mph(3.3), it, "Hill $it%")) }
            add(Segment(300, mph(2.8), 0.0, "Cool down"))
        }),
        Workout.intervals("Run intervals 8 × 1 min",
            Segment(300, mph(3.5), 1.0, "Warm up"),
            Segment(60, mph(7.0), 1.0, "Run"),
            Segment(90, mph(3.5), 1.0, "Recover"), 8,
            Segment(300, mph(3.0), 0.0, "Cool down")),
        Workout("Steady jog, 30 min", listOf(
            Segment(300, mph(3.5), 0.0, "Warm up"),
            Segment(1200, mph(5.5), 1.0, "Jog"),
            Segment(300, mph(3.0), 0.0, "Cool down"),
        )),
    )

    fun customIntervals(workMph: Double, restMph: Double, workSec: Int, restSec: Int, rounds: Int, inclinePct: Double) =
        Workout.intervals("Custom intervals",
            Segment(300, mph(minOf(restMph, 3.5)), 0.0, "Warm up"),
            Segment(workSec, mph(workMph), inclinePct, "Work"),
            Segment(restSec, mph(restMph), inclinePct, "Recover"), rounds,
            Segment(180, mph(minOf(restMph, 3.0)), 0.0, "Cool down"))
}

data class HistoryEntry(val startedAt: Long, val seconds: Int, val meters: Double, val kcal: Double, val avgKph: Double, val workout: String?)

/** Finished sessions, one JSON object per line in the app's private storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.jsonl")

    fun add(e: HistoryEntry) {
        file.appendText(JSONObject().apply {
            put("startedAt", e.startedAt); put("seconds", e.seconds); put("meters", e.meters)
            put("kcal", e.kcal); put("avgKph", e.avgKph); put("workout", e.workout ?: JSONObject.NULL)
        }.toString() + "\n")
    }

    fun all(): List<HistoryEntry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        runCatching {
            val o = JSONObject(line)
            HistoryEntry(o.getLong("startedAt"), o.getInt("seconds"), o.getDouble("meters"), o.getDouble("kcal"),
                o.getDouble("avgKph"), if (o.isNull("workout")) null else o.getString("workout"))
        }.getOrNull()
    }.sortedByDescending { it.startedAt }

    /** CSV export, written next to the history file. */
    fun exportCsv(): File = File(file.parentFile, "history.csv").apply {
        writeText("started_at,seconds,km,kcal,avg_kph,workout\n" + all().joinToString("") {
            "${it.startedAt},${it.seconds},${"%.3f".format(it.meters / 1000)},${"%.0f".format(it.kcal)},${"%.2f".format(it.avgKph)},${it.workout ?: ""}\n"
        })
    }
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var useMiles: Boolean
        get() = p.getBoolean("useMiles", true)
        set(v) = p.edit().putBoolean("useMiles", v).apply()
    var weightKg: Double
        get() = p.getFloat("weightKg", 80f).toDouble()
        set(v) = p.edit().putFloat("weightKg", v.toFloat()).apply()
}
