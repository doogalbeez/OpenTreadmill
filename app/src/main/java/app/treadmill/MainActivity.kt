package app.treadmill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.treadmill.TreadmillViewModel.Companion.distanceUnit
import app.treadmill.TreadmillViewModel.Companion.fmtDistance
import app.treadmill.TreadmillViewModel.Companion.fmtSpeed
import app.treadmill.TreadmillViewModel.Companion.fmtTime
import app.treadmill.TreadmillViewModel.Companion.speedUnit
import app.treadmill.core.Stats
import app.treadmill.core.Workout
import java.text.DateFormat
import java.util.Date

private val Bg = Color(0xFF0E1116)
private val Panel = Color(0xFF1A1F27)
private val Accent = Color(0xFF3DDC97)
private val StopRed = Color(0xFFE5484D)
private val Muted = Color(0xFF8B949E)

class MainActivity : ComponentActivity() {
    private val vm: TreadmillViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
                val s by vm.ui.collectAsStateWithLifecycle()
                Console(s, vm)
            }
        }
    }
}

@Composable
private fun Console(s: UiState, vm: TreadmillViewModel) {
    val miles = s.useMiles
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        StatusBar(s, vm)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Speed column
            ControlColumn(
                title = "Speed", value = fmtSpeed(if (s.running) s.board.targetKph else s.board.beltKph, miles), unit = speedUnit(miles),
                target = "belt ${fmtSpeed(s.board.beltKph, miles)}" +
                    if (s.running && s.board.goalKph != s.board.targetKph) " · going to ${fmtSpeed(s.board.goalKph, miles)}" else "",
                step = if (miles) 0.1 * Stats.KPH_PER_MPH else 0.1, presets = if (miles) listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0) else listOf(3.0, 5.0, 6.0, 8.0, 10.0, 12.0),
                presetToKph = { if (miles) it * Stats.KPH_PER_MPH else it },
                onBy = vm::speedBy, onTo = vm::speedTo, enabled = s.running && !s.keyOut,
                modifier = Modifier.weight(1f),
            )
            // Centre: stats and start/stop
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatGrid(s)
                WorkoutProgress(s, vm)
                Spacer(Modifier.weight(1f))
                MainButtons(s, vm)
            }
            ControlColumn(
                title = "Incline", value = "%.1f".format(s.board.incline), unit = "%",
                target = "target %.1f".format(s.board.targetIncline),
                step = 0.5, presets = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), presetToKph = { it },
                onBy = vm::inclineBy, onTo = vm::inclineTo, enabled = !s.keyOut,
                modifier = Modifier.weight(1f),
            )
            SidePanel(s, vm, Modifier.weight(1.2f))
        }
    }
}

@Composable
private fun StatusBar(s: UiState, vm: TreadmillViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Treadmill", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(s.connection + " · " + s.mode.name.lowercase().replace('_', ' '), color = if (s.demo) Color(0xFFF5A623) else Muted)
        if (s.keyOut) { Spacer(Modifier.width(16.dp)); Text("SAFETY KEY OUT: put it back, then press Start", color = StopRed, fontWeight = FontWeight.Bold) }
        if (s.board.stopping) { Spacer(Modifier.width(16.dp)); Text("Stopping…", color = StopRed, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.weight(1f))
        s.error?.let { Text(it, color = Color(0xFFF5A623), modifier = Modifier.padding(end = 12.dp)) }
        if (s.demo) TextButton(onClick = vm::connect) { Text("Retry USB") }
    }
}

@Composable
private fun ControlColumn(
    title: String, value: String, unit: String, target: String, step: Double,
    presets: List<Double>, presetToKph: (Double) -> Double,
    onBy: (Double) -> Unit, onTo: (Double) -> Unit, enabled: Boolean, modifier: Modifier,
) {
    Column(modifier.fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Muted, fontSize = 18.sp)
        Text(value, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Text("$unit · $target", color = Muted)
        BigButton("▲", enabled) { onBy(step) }
        BigButton("▼", enabled) { onBy(-step) }
        presets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    OutlinedButton(onClick = { onTo(presetToKph(p)) }, enabled = enabled, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(if (p % 1.0 == 0.0) p.toInt().toString() else p.toString(), fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(12.dp)) {
        Text(label, fontSize = 30.sp)
    }
}

@Composable
private fun StatGrid(s: UiState) {
    val miles = s.useMiles
    val pace = Stats.paceSeconds(s.board.beltKph, miles)?.let { fmtTime(it) } ?: "--"
    val tiles = listOf(
        "Time" to fmtTime(s.seconds),
        "Distance (${distanceUnit(miles)})" to fmtDistance(s.meters, miles),
        "Pace (/${distanceUnit(miles)})" to pace,
        "Calories" to "%.0f".format(s.kcal),
    )
    tiles.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (label, v) ->
                Column(Modifier.weight(1f).background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text(label, color = Muted)
                    Text(v, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WorkoutProgress(s: UiState, vm: TreadmillViewModel) {
    val w = s.workout ?: return
    val seg = w.segments.getOrNull(s.segmentIndex)
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::endWorkout) { Text("End workout") }
        }
        LinearProgressIndicator(progress = { s.workoutElapsed.toFloat() / w.totalSeconds }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Spacer(Modifier.height(8.dp))
        if (seg != null) {
            val next = w.segments.getOrNull(s.segmentIndex + 1)
            Text("${seg.label}: ${fmtSpeed(seg.kph, s.useMiles)} ${speedUnit(s.useMiles)} at ${"%.1f".format(seg.inclinePct)}% · ${fmtTime(s.segmentSecondsLeft)} left",
                color = Color.White, fontSize = 18.sp)
            next?.let { Text("Next: ${it.label}, ${fmtSpeed(it.kph, s.useMiles)} at ${"%.1f".format(it.inclinePct)}%", color = Muted) }
        }
    }
}

@Composable
private fun MainButtons(s: UiState, vm: TreadmillViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.running) {
            Button(onClick = vm::pause, modifier = Modifier.weight(1f).height(96.dp), shape = RoundedCornerShape(16.dp)) { Text("Pause", fontSize = 28.sp) }
        } else {
            Button(onClick = vm::start, modifier = Modifier.weight(1f).height(96.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (s.paused) "Resume" else "Start", fontSize = 28.sp)
            }
        }
        // Stop is always enabled.
        Button(onClick = vm::stop, colors = ButtonDefaults.buttonColors(containerColor = StopRed),
            modifier = Modifier.weight(1f).height(96.dp), shape = RoundedCornerShape(16.dp)) { Text("STOP", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SidePanel(s: UiState, vm: TreadmillViewModel, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp)) {
        TabRow(selectedTabIndex = tab, containerColor = Panel) {
            listOf("Workouts", "History", "Settings").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> WorkoutsTab(s, vm)
            1 -> HistoryTab(s, vm)
            else -> SettingsTab(s, vm)
        }
    }
}

@Composable
private fun WorkoutsTab(s: UiState, vm: TreadmillViewModel) {
    var work by remember { mutableDoubleStateOf(6.0) }
    var rest by remember { mutableDoubleStateOf(3.5) }
    var rounds by remember { mutableIntStateOf(6) }
    var workSec by remember { mutableIntStateOf(60) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(WorkoutLibrary.builtIn()) { w -> WorkoutRow(w, enabled = s.workout == null) { vm.startWorkout(w) } }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Build intervals (mph)", color = Color.White, fontWeight = FontWeight.Bold)
                Stepper("Work speed", "%.1f".format(work), { work = (work - 0.5).coerceAtLeast(1.0) }, { work = (work + 0.5).coerceAtMost(12.0) })
                Stepper("Recover speed", "%.1f".format(rest), { rest = (rest - 0.5).coerceAtLeast(1.0) }, { rest = (rest + 0.5).coerceAtMost(8.0) })
                Stepper("Work length", "${workSec}s", { workSec = (workSec - 15).coerceAtLeast(15) }, { workSec = (workSec + 15).coerceAtMost(600) })
                Stepper("Rounds", "$rounds", { rounds = (rounds - 1).coerceAtLeast(1) }, { rounds = (rounds + 1).coerceAtMost(30) })
                Button(onClick = { vm.startWorkout(WorkoutLibrary.customIntervals(work, rest, workSec, 90, rounds, 1.0)) },
                    enabled = s.workout == null, modifier = Modifier.fillMaxWidth()) { Text("Start custom intervals") }
            }
        }
    }
}

@Composable
private fun WorkoutRow(w: Workout, enabled: Boolean, onStart: () -> Unit) {
    OutlinedButton(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(w.name, color = Color.White)
            Text("${w.totalSeconds / 60} min · ${w.segments.size} steps", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("–", fontSize = 22.sp) }
        Text(value, color = Color.White, modifier = Modifier.width(56.dp))
        TextButton(onClick = onPlus) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun HistoryTab(s: UiState, vm: TreadmillViewModel) {
    val entries = remember(s.workout, s.mode) { vm.history.all() }
    var exported by remember { mutableStateOf<String?>(null) }
    Column {
        TextButton(onClick = { exported = vm.history.exportCsv().absolutePath }) { Text("Export CSV") }
        exported?.let { Text("Saved to $it", color = Muted, fontSize = 12.sp) }
        if (entries.isEmpty()) Text("No workouts yet.", color = Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.startedAt)), color = Color.White)
                    Text("${fmtTime(e.seconds)} · ${fmtDistance(e.meters, s.useMiles)} ${distanceUnit(s.useMiles)} · ${"%.0f".format(e.kcal)} kcal" +
                        (e.workout?.let { " · $it" } ?: ""), color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(s: UiState, vm: TreadmillViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use miles", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = s.useMiles, onCheckedChange = vm::setUseMiles)
        }
        val lb = s.weightKg * 2.20462
        Stepper("Your weight", if (s.useMiles) "%.0f lb".format(lb) else "%.0f kg".format(s.weightKg),
            { vm.setWeightKg((s.weightKg - if (s.useMiles) 5 / 2.20462 else 2.0).coerceAtLeast(30.0)) },
            { vm.setWeightKg((s.weightKg + if (s.useMiles) 5 / 2.20462 else 2.0).coerceAtMost(250.0)) })
        Text("Used for the calorie estimate.", color = Muted, fontSize = 12.sp)
        Text("Machine limits: ${fmtSpeed(s.limits.minKph, s.useMiles)}–${fmtSpeed(s.limits.maxKph, s.useMiles)} ${speedUnit(s.useMiles)}, " +
            "${"%.1f".format(s.limits.minGrade)} to ${"%.1f".format(s.limits.maxGrade)}% incline", color = Muted, fontSize = 12.sp)
        if (s.demo) {
            Text("Simulator", color = Color.White, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Safety key inserted", color = Muted, modifier = Modifier.weight(1f))
                Switch(checked = !s.keyOut, onCheckedChange = vm::simulateSafetyKey)
            }
        }
        Text("Board details", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        val b = s.board
        Text("mode ${b.mode.name.lowercase()} · reply ${b.lastStatus} · refused ${b.rejects} · odometer ${b.odometerM} m",
            color = Muted, fontSize = 12.sp)
        Text("If something goes wrong, take a photo of this panel.", color = Muted, fontSize = 12.sp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
            items(s.log) { Text(it, color = Muted, fontSize = 11.sp) }
        }
    }
}
