package app.opentreadmill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentreadmill.TreadmillViewModel.Companion.distanceUnit
import app.opentreadmill.TreadmillViewModel.Companion.fmtDistance
import app.opentreadmill.TreadmillViewModel.Companion.fmtSpeed
import app.opentreadmill.TreadmillViewModel.Companion.fmtTime
import app.opentreadmill.TreadmillViewModel.Companion.speedUnit
import app.opentreadmill.core.Stats

/** The screen while a session is under way: stats on top, incline left, speed right, STOP always at hand. */
@Composable
fun RunScreen(s: UiState, vm: TreadmillViewModel) {
    val miles = s.useMiles
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Banners(s)
            StatBar(s)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SideControl(
                    title = "Incline", color = InclineColor, value = "%.1f".format(s.board.incline), unit = "%",
                    sub = "target %.1f".format(s.board.targetIncline),
                    onUp = { vm.inclineBy(0.5) }, onDown = { vm.inclineBy(-0.5) },
                    presets = listOf(0.0, 2.0, 4.0, 6.0, 10.0, 12.0), onPreset = vm::inclineTo, enabled = !s.keyOut,
                )
                Centre(s, vm, Modifier.weight(1f).fillMaxHeight())
                val shown = if (s.running) s.board.targetKph else s.board.beltKph
                SideControl(
                    title = "Speed", color = SpeedColor, value = fmtSpeed(shown, miles), unit = speedUnit(miles),
                    sub = if (s.running && s.board.goalKph != s.board.targetKph) "going to ${fmtSpeed(s.board.goalKph, miles)}" else "belt ${fmtSpeed(s.board.beltKph, miles)}",
                    onUp = { vm.speedBy(if (miles) 0.1 * Stats.KPH_PER_MPH else 0.1) },
                    onDown = { vm.speedBy(if (miles) -0.1 * Stats.KPH_PER_MPH else -0.1) },
                    presets = if (miles) listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0) else listOf(3.0, 5.0, 6.0, 8.0, 10.0, 12.0),
                    onPreset = { vm.speedTo(if (miles) it * Stats.KPH_PER_MPH else it) }, enabled = s.running && !s.keyOut,
                )
            }
            BottomBar(s, vm)
        }
        s.summary?.takeIf { !s.running && !s.board.stopping }?.let { Summary(it, miles, vm::dismissSummary) }
    }
}

@Composable
private fun Banners(s: UiState) {
    when {
        s.keyOut -> Banner("SAFETY KEY OUT  ·  Put it back, then press Start", StopRed)
        s.board.stopping -> Banner("Stopping…", StopRed)
        s.error != null && !s.demo -> Banner(s.error, Warn)
        s.demo -> Banner("Simulator: the motor board isn't connected", Warn)
    }
}

@Composable
fun Banner(text: String, color: Color) {
    Box(Modifier.fillMaxWidth().background(color, RoundedCornerShape(10.dp)).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatBar(s: UiState) {
    val miles = s.useMiles
    val w = s.workout
    val pace = Stats.paceSeconds(s.board.beltKph, miles)?.let { fmtTime(it) } ?: "--:--"
    val climb = if (miles) "%.0f".format(s.climbM * 3.28084) else "%.0f".format(s.climbM)
    Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val timeSub = when {
            w != null -> "${fmtTime((w.totalSeconds - s.workoutElapsed).coerceAtLeast(0))} left"
            s.timeGoal > 0 -> "${fmtTime((s.timeGoal - s.seconds).coerceAtLeast(0))} to goal"
            else -> null
        }
        Stat("Time", fmtTime(s.seconds), timeSub, Modifier.weight(1.2f))
        Stat("Distance", fmtDistance(s.meters, miles), distanceUnit(miles), Modifier.weight(1f))
        Stat("Calories", "%.0f".format(s.kcal), "kcal", Modifier.weight(1f))
        Stat("Pace", pace, "per ${distanceUnit(miles)}", Modifier.weight(1f))
        Stat("Climb", climb, if (miles) "ft" else "m", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String?, modifier: Modifier) {
    Tile(modifier.fillMaxHeight(), padding = 14.dp) {
        Caps(label, size = 14.sp)
        BigText(value, 44.sp)
        sub?.let { Text(it, color = Muted, fontSize = 14.sp) }
    }
}

@Composable
private fun SideControl(
    title: String, color: Color, value: String, unit: String, sub: String,
    onUp: () -> Unit, onDown: () -> Unit, presets: List<Double>, onPreset: (Double) -> Unit, enabled: Boolean,
) {
    Tile(Modifier.width(236.dp).fillMaxHeight(), padding = 12.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Caps(title, color = color, size = 17.sp)
            BigText(value, 76.sp)
            Text("$unit · $sub", color = Muted, fontSize = 15.sp, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        PadButton("▲", Modifier.fillMaxWidth().weight(1f), color = color, textColor = OnAccent, enabled = enabled, fontSize = 36.sp, onClick = onUp)
        Spacer(Modifier.height(8.dp))
        PadButton("▼", Modifier.fillMaxWidth().weight(1f), color = color, textColor = OnAccent, enabled = enabled, fontSize = 36.sp, onClick = onDown)
        Spacer(Modifier.height(10.dp))
        presets.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { p ->
                    PadButton(if (p % 1.0 == 0.0) p.toInt().toString() else p.toString(), Modifier.weight(1f).height(52.dp),
                        enabled = enabled, fontSize = 20.sp) { onPreset(p) }
                }
            }
        }
    }
}

@Composable
private fun Centre(s: UiState, vm: TreadmillViewModel, modifier: Modifier) {
    val miles = s.useMiles
    val w = s.workout
    Tile(modifier, padding = 18.dp) {
        if (w == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caps("Manual", size = 16.sp)
                Spacer(Modifier.weight(1f))
                Text("Lap ${(s.meters / 400).toInt() + 1}  ·  400 m track", color = Muted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            TrackChart(s.meters, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(12.dp))
            // Time goal: when it is reached the belt stops and the summary shows.
            Stepper("Time goal", if (s.timeGoal == 0) "Off" else fmtTime(s.timeGoal),
                { vm.setTimeGoal(if (s.timeGoal <= 300) 0 else s.timeGoal - 300) },
                { vm.setTimeGoal(s.timeGoal + 300) })
            Spacer(Modifier.height(6.dp))
            Text(if (s.running) "Use the arrows or a number to change speed and incline." else if (s.paused) "Paused. Resume or STOP." else "Press Start. The belt begins slowly.",
                color = Muted, fontSize = 16.sp)
        } else {
            val seg = w.segments.getOrNull(s.segmentIndex)
            val next = w.segments.getOrNull(s.segmentIndex + 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caps(w.name, color = categoryColor(w.category), size = 16.sp)
                    Text(seg?.label ?: "Done", color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                PadButton("−1 min", Modifier.width(96.dp).height(56.dp), fontSize = 18.sp, enabled = seg != null) { vm.adjustStep(-60) }
                Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigText(fmtTime(s.segmentSecondsLeft), 40.sp)
                    Text("left in this step", color = Muted, fontSize = 14.sp)
                }
                PadButton("+1 min", Modifier.width(96.dp).height(56.dp), fontSize = 18.sp, enabled = seg != null) { vm.adjustStep(60) }
            }
            seg?.let {
                Text("${fmtSpeed(it.kph, miles)} ${speedUnit(miles)}  ·  ${"%.1f".format(it.inclinePct)}% incline", color = Muted, fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            ProfileChart(w, Modifier.fillMaxWidth().weight(1f), elapsed = s.workoutElapsed)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(next?.let { "Next: ${it.label}, ${fmtSpeed(it.kph, miles)} ${speedUnit(miles)} at ${"%.1f".format(it.inclinePct)}%" } ?: "Last step",
                    color = Muted, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("End program", color = Warn, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = vm::endWorkout).padding(8.dp))
            }
        }
    }
}

@Composable
private fun BottomBar(s: UiState, vm: TreadmillViewModel) {
    Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicBar(s.music, s.volumeNotice, vm, Modifier.weight(1.5f).fillMaxHeight())
        when {
            s.running -> PadButton("Pause", Modifier.weight(1f).fillMaxHeight(), color = PanelHi, fontSize = 32.sp, onClick = vm::pause)
            else -> PadButton(if (s.paused && s.sessionActive) "Resume" else "Start", Modifier.weight(1f).fillMaxHeight(),
                color = Go, textColor = OnAccent, fontSize = 32.sp, enabled = !s.board.stopping, onClick = vm::start)
        }
        // STOP is never disabled.
        PadButton("STOP", Modifier.weight(1f).fillMaxHeight(), color = StopRed, textColor = Color.White, fontSize = 38.sp, onClick = vm::stop)
    }
}

/** Now playing and transport buttons for the music app, so it never has to be opened mid-run. */
@Composable
private fun MusicBar(m: MusicState, volumeNotice: Boolean, vm: TreadmillViewModel, modifier: Modifier) {
    Row(modifier.background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            if (volumeNotice) {
                Caps("Max volume", color = Warn, size = 12.sp)
                Text("For louder, use the treadmill's volume buttons below the screen", color = TextHi, fontSize = 15.sp, maxLines = 2)
            } else {
                Caps(if (m.playing) "Now playing" else "Music", size = 12.sp)
                Text(m.title ?: if (m.canSeeSessions) "Nothing playing" else "Play / pause / skip", color = TextHi, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                m.artist?.let { Text(it, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
        PadButton("⏮", Modifier.size(58.dp), fontSize = 22.sp) { vm.music.previous() }
        PadButton(if (m.playing) "⏸" else "▶", Modifier.size(64.dp), color = SpeedColor, textColor = OnAccent, fontSize = 26.sp) { vm.music.playPause() }
        PadButton("⏭", Modifier.size(58.dp), fontSize = 22.sp) { vm.music.next() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PadButton("🔊+", Modifier.width(64.dp).height(40.dp), fontSize = 15.sp) { vm.volume(true) }
            PadButton("–", Modifier.width(64.dp).height(40.dp), fontSize = 18.sp) { vm.volume(false) }
        }
    }
}

@Composable
private fun Summary(e: HistoryEntry, miles: Boolean, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Tile(Modifier.width(720.dp), color = PanelHi, padding = 28.dp) {
            Caps("Workout complete", color = Go, size = 16.sp)
            Text(e.workout ?: "Manual", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                SummaryStat("Time", fmtTime(e.seconds))
                SummaryStat("Distance", "${fmtDistance(e.meters, miles)} ${distanceUnit(miles)}")
                SummaryStat("Calories", "%.0f".format(e.kcal))
                SummaryStat("Avg speed", "${fmtSpeed(e.avgKph, miles)} ${speedUnit(miles)}")
                SummaryStat("Climb", if (miles) "%.0f ft".format(e.climbM * 3.28084) else "%.0f m".format(e.climbM))
            }
            Spacer(Modifier.height(24.dp))
            PadButton("Done", Modifier.fillMaxWidth().height(72.dp), color = Go, textColor = OnAccent, onClick = onDone)
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column { Caps(label, size = 13.sp); Text(value, color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}
