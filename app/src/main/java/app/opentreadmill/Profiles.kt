package app.opentreadmill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One person using the treadmill. History and saved workouts are kept per profile. */
data class Profile(
    val id: String,
    val name: String,
    val weightKg: Double = 80.0,
    val useMiles: Boolean = true,
    val theme: String = "Midnight",
    /** Index into [Profiles.avatarColors]. */
    val color: Int = 0,
) {
    val initial get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Settings for the console itself, shared by every profile. */
class DevicePrefs(context: Context) {
    private val p = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    /** IANA time zone id for the app's clock, or "" to follow Android's setting. */
    var timeZone: String
        get() = p.getString("timeZone", "") ?: ""
        set(v) = p.edit().putString("timeZone", v).apply()
    var use24h: Boolean
        get() = p.getBoolean("use24h", false)
        set(v) = p.edit().putBoolean("use24h", v).apply()

    companion object {
        /** Zones offered in Settings, west to east. */
        val zones = listOf(
            "Pacific/Honolulu" to "Hawaii", "America/Anchorage" to "Alaska", "America/Los_Angeles" to "Pacific",
            "America/Phoenix" to "Arizona", "America/Denver" to "Mountain", "America/Chicago" to "Central",
            "America/New_York" to "Eastern", "America/Halifax" to "Atlantic", "America/St_Johns" to "Newfoundland",
            "Europe/London" to "London", "Europe/Paris" to "Central Europe", "Europe/Athens" to "Eastern Europe",
            "Asia/Kolkata" to "India", "Asia/Tokyo" to "Japan", "Australia/Sydney" to "Sydney", "Pacific/Auckland" to "New Zealand",
        )
    }
}

object Profiles {
    val avatarColors = listOf(0xFF3DB8F5, 0xFF2FC57A, 0xFFFF9F43, 0xFFB57BFF, 0xFFFF6B9A, 0xFFE6C84A, 0xFF4FD1C5, 0xFFFF7A59).map { it.toInt() }
}

/** Profiles live in SharedPreferences as a JSON list; each profile's files carry its id. */
class ProfileStore(private val context: Context) {
    private val p = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    init { if (all().isEmpty()) migrate() }

    fun all(): List<Profile> = runCatching {
        val a = JSONArray(p.getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Profile(o.getString("id"), o.getString("name"), o.optDouble("weightKg", 80.0), o.optBoolean("useMiles", true),
                o.optString("theme", "Midnight"), o.optInt("color", 0))
        }
    }.getOrDefault(emptyList())

    fun current(): Profile {
        val list = all()
        return list.firstOrNull { it.id == p.getString("current", null) } ?: list.first()
    }

    fun setCurrent(id: String) = p.edit().putString("current", id).apply()

    fun save(profile: Profile) {
        val list = all()
        write(if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile)
    }

    fun add(name: String, color: Int): Profile {
        val prof = Profile(UUID.randomUUID().toString(), name.trim().ifEmpty { "Runner" }, color = color, theme = current().theme)
        save(prof)
        return prof
    }

    /** Deletes the profile and its history and workouts. The last profile can't be deleted. */
    fun delete(id: String): Boolean {
        val list = all()
        if (list.size <= 1) return false
        write(list.filter { it.id != id })
        historyFile(id).delete(); workoutsFile(id).delete()
        if (p.getString("current", null) == id) setCurrent(all().first().id)
        return true
    }

    fun historyFile(id: String) = File(context.filesDir, "history-$id.jsonl")
    fun workoutsFile(id: String) = File(context.filesDir, "workouts-$id.json")

    private fun write(list: List<Profile>) {
        val a = JSONArray()
        list.forEach { prof ->
            a.put(JSONObject().apply {
                put("id", prof.id); put("name", prof.name); put("weightKg", prof.weightKg); put("useMiles", prof.useMiles)
                put("theme", prof.theme); put("color", prof.color)
            })
        }
        p.edit().putString("list", a.toString()).apply()
    }

    /** First run with profiles: turn the old single-user settings, history and workouts into profile one. */
    private fun migrate() {
        val old = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val first = Profile("default", "Me", old.getFloat("weightKg", 80f).toDouble(), old.getBoolean("useMiles", true))
        File(context.filesDir, "history.jsonl").takeIf { it.exists() }?.renameTo(historyFile(first.id))
        File(context.filesDir, "workouts.json").takeIf { it.exists() }?.renameTo(workoutsFile(first.id))
        write(listOf(first))
        setCurrent(first.id)
    }
}
