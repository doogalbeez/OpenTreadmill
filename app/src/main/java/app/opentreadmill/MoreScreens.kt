package app.opentreadmill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import app.opentreadmill.core.Segment
import app.opentreadmill.core.Stats
import app.opentreadmill.core.Workout
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PadButton("‹  Back", Modifier.width(140.dp).fillMaxHeight(), fontSize = 20.sp, onClick = onBack)
        Text(title, color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

// ---- builder ------------------------------------------------------------------------

private val stepNames = listOf("Warm up", "Walk", "Brisk walk", "Jog", "Run", "Sprint", "Climb", "Recover", "Cool down")

@Composable
fun BuilderScreen(initial: Workout, s: UiState, vm: TreadmillViewModel, onClose: () -> Unit) {
    val miles = s.useMiles
    var name by remember { mutableStateOf(initial.name) }
    val segs = remember { mutableStateListOf<Segment>().apply { addAll(initial.segments) } }
    val speedStep = if (miles) 0.2 * Stats.KPH_PER_MPH else 0.5
    val lim = s.limits
    fun current() = Workout(name.trim().ifEmpty { "My workout" }, segs.toList(), WorkoutLibrary.MINE, initial.description, initial.id ?: UUID.randomUUID().toString())
    fun update(i: Int, f: (Segment) -> Segment) { segs[i] = f(segs[i]) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Build a workout", onClose) {
            PadButton("Save", Modifier.width(140.dp).fillMaxHeight(), enabled = segs.isNotEmpty(), fontSize = 22.sp) { vm.saveWorkout(current()); onClose() }
            PadButton("Save & start", Modifier.width(200.dp).fillMaxHeight(), color = Go, textColor = OnAccent, enabled = segs.isNotEmpty(), fontSize = 22.sp) {
                val w = current(); vm.saveWorkout(w); onClose(); vm.startWorkout(w)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(Modifier.width(380.dp).height(190.dp)) {
                OutlinedTextField(name, { name = it.take(32) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                val w = current()
                Text("${fmtTime(w.totalSeconds)}  ·  ${fmtDistance(w.plannedMeters, miles)} ${distanceUnit(miles)}  ·  ${segs.size} steps", color = TextHi, fontSize = 18.sp)
                Text("Up to ${fmtSpeed(w.maxKph, miles)} ${speedUnit(miles)} and ${"%.1f".format(w.maxIncline)}%", color = Muted, fontSize = 15.sp)
            }
            Tile(Modifier.weight(1f).height(190.dp)) { ProfileChart(current(), Modifier.fillMaxSize()) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(segs) { i, seg ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${i + 1}", color = Muted, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                    // Tap the name to cycle through common step names.
                    PadButton(seg.label.ifEmpty { "Step" }, Modifier.width(150.dp).height(48.dp), fontSize = 17.sp) {
                        update(i) { it.copy(label = stepNames[(stepNames.indexOf(it.label) + 1) % stepNames.size]) }
                    }
                    Stepper("Time", fmtTime(seg.seconds), { update(i) { it.copy(seconds = (it.seconds - 30).coerceAtLeast(30)) } },
                        { update(i) { it.copy(seconds = (it.seconds + 30).coerceAtMost(3600)) } }, Modifier.weight(1f))
                    Stepper("Speed", fmtSpeed(seg.kph, miles), { update(i) { it.copy(kph = (it.kph - speedStep).coerceAtLeast(lim.minKph)) } },
                        { update(i) { it.copy(kph = (it.kph + speedStep).coerceAtMost(lim.maxKph)) } }, Modifier.weight(1f), color = SpeedColor)
                    Stepper("Incline", "%.1f".format(seg.inclinePct), { update(i) { it.copy(inclinePct = (it.inclinePct - 0.5).coerceAtLeast(lim.minGrade)) } },
                        { update(i) { it.copy(inclinePct = (it.inclinePct + 0.5).coerceAtMost(lim.maxGrade)) } }, Modifier.weight(1f), color = InclineColor)
                    PadButton("Copy", Modifier.width(80.dp).height(48.dp), fontSize = 16.sp) { segs.add(i + 1, segs[i]) }
                    PadButton("✕", Modifier.width(52.dp).height(48.dp), fontSize = 18.sp, enabled = segs.size > 1) { segs.removeAt(i) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadButton("+ Add step", Modifier.width(220.dp).height(60.dp), fontSize = 20.sp) {
                        segs.add(segs.lastOrNull()?.copy() ?: Segment(300, 4.0 * Stats.KPH_PER_MPH, 0.0, "Walk"))
                    }
                    PadButton("+ Add 5 intervals", Modifier.width(260.dp).height(60.dp), fontSize = 20.sp) {
                        val base = segs.lastOrNull()?.kph ?: (3.5 * Stats.KPH_PER_MPH)
                        repeat(5) {
                            segs.add(Segment(60, (base + 2.5 * Stats.KPH_PER_MPH).coerceAtMost(lim.maxKph), 1.0, "Run"))
                            segs.add(Segment(90, base.coerceAtLeast(lim.minKph), 1.0, "Recover"))
                        }
                    }
                }
            }
        }
    }
}

// ---- history ------------------------------------------------------------------------

@Composable
fun HistoryScreen(s: UiState, vm: TreadmillViewModel, onClose: () -> Unit) {
    val miles = s.useMiles
    val entries = remember(s.profile.id) { vm.history.all() }
    var exported by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("${s.profile.name}'s history", onClose) {
            PadButton("Export CSV", Modifier.width(180.dp).fillMaxHeight(), fontSize = 20.sp) { exported = vm.history.exportCsv().absolutePath }
        }
        exported?.let { Text("Saved to $it", color = Muted, fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "Workouts" to "${entries.size}",
                "Distance" to "${fmtDistance(entries.sumOf { it.meters }, miles)} ${distanceUnit(miles)}",
                "Time" to fmtTime(entries.sumOf { it.seconds }),
                "Calories" to "%.0f".format(entries.sumOf { it.kcal }),
                "Climb" to if (miles) "%.0f ft".format(entries.sumOf { it.climbM } * 3.28084) else "%.0f m".format(entries.sumOf { it.climbM }),
            ).forEach { (l, v) -> Tile(Modifier.weight(1f)) { Caps(l, size = 13.sp); Text(v, color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Bold) } }
        }
        if (entries.isEmpty()) Text("No workouts yet. Finished sessions of 30 seconds or more show up here.", color = Muted, fontSize = 18.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.workout ?: "Manual", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.startedAt)), color = Muted, fontSize = 15.sp)
                    }
                    Text("${fmtTime(e.seconds)}   ·   ${fmtDistance(e.meters, miles)} ${distanceUnit(miles)}   ·   ${"%.0f".format(e.kcal)} kcal   ·   ${fmtSpeed(e.avgKph, miles)} ${speedUnit(miles)} avg",
                        color = TextHi, fontSize = 17.sp)
                }
            }
        }
    }
}

// ---- settings -----------------------------------------------------------------------

@Composable
fun SettingsScreen(s: UiState, vm: TreadmillViewModel, onClose: () -> Unit) {
    var name by remember(s.profile.id) { mutableStateOf(s.profile.name) }
    var confirmDelete by remember(s.profile.id) { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Settings", onClose)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.fillMaxWidth()) {
                    Caps("Profile")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Avatar(s.profile, 64.dp)
                        OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
                        PadButton("Rename", Modifier.width(130.dp).height(56.dp), fontSize = 18.sp, enabled = name.isNotBlank() && name != s.profile.name) { vm.renameProfile(name) }
                    }
                    Spacer(Modifier.height(12.dp))
                    ColorChoices(s.profile.color, vm::setProfileColor)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use miles", color = TextHi, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Switch(checked = s.useMiles, onCheckedChange = vm::setUseMiles)
                    }
                    val lb = s.weightKg * 2.20462
                    Stepper("Weight (for calories)", if (s.useMiles) "%.0f lb".format(lb) else "%.0f kg".format(s.weightKg),
                        { vm.setWeightKg((s.weightKg - if (s.useMiles) 5 / 2.20462 else 2.0).coerceAtLeast(30.0)) },
                        { vm.setWeightKg((s.weightKg + if (s.useMiles) 5 / 2.20462 else 2.0).coerceAtMost(250.0)) })
                    Spacer(Modifier.height(14.dp))
                    if (s.profiles.size > 1) {
                        PadButton(if (confirmDelete) "Tap again to delete ${s.profile.name} and their history" else "Delete this profile",
                            Modifier.fillMaxWidth().height(56.dp), color = if (confirmDelete) StopRed else PanelHi,
                            textColor = if (confirmDelete) Color.White else TextHi, fontSize = 17.sp) {
                            if (confirmDelete) vm.deleteProfile(s.profile.id) else confirmDelete = true
                        }
                    }
                }
                ClockTile(s, vm)
                Tile(Modifier.fillMaxWidth()) {
                    Caps("Theme")
                    Spacer(Modifier.height(10.dp))
                    Palettes.all.chunked(3).forEach { row ->
                        Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { p -> ThemeSwatch(p, p.name == s.profile.theme, Modifier.weight(1f)) { vm.setTheme(p.name) } }
                        }
                    }
                }
                Tile(Modifier.fillMaxWidth()) {
                    Caps("Factory reset", color = StopRed)
                    Text("Erase this console back to the stock iFit software. Removes OpenTreadmill, its profiles and history, and every app you added.",
                        color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    PadButton(if (s.sessionActive) "Stop your workout first" else "Factory reset…", Modifier.fillMaxWidth().height(56.dp),
                        color = StopRed, textColor = Color.White, enabled = !s.sessionActive && !s.running, fontSize = 18.sp) { resetOpen = true }
                }
            }
            Tile(Modifier.weight(1f).fillMaxHeight()) {
                Caps("Machine")
                Text("Speed ${fmtSpeed(s.limits.minKph, s.useMiles)}–${fmtSpeed(s.limits.maxKph, s.useMiles)} ${speedUnit(s.useMiles)}  ·  " +
                    "incline ${"%.1f".format(s.limits.minGrade)} to ${"%.1f".format(s.limits.maxGrade)}%", color = TextHi, fontSize = 16.sp)
                Text(s.connection, color = if (s.demo) Warn else Muted, fontSize = 15.sp)
                if (s.demo) {
                    PadButton("Retry USB", Modifier.width(160.dp).height(48.dp), fontSize = 17.sp) { vm.connect() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Simulated safety key inserted", color = Muted, modifier = Modifier.weight(1f))
                        Switch(checked = !s.keyOut, onCheckedChange = vm::simulateSafetyKey)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Caps("Board details")
                val b = s.board
                Text("mode ${b.mode.name.lowercase()} · reply ${b.lastStatus} · refused ${b.rejects} · odometer ${b.odometerM} m", color = Muted, fontSize = 13.sp)
                Text("If something goes wrong, take a photo of this panel.", color = Muted, fontSize = 13.sp)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) { items(s.log) { Text(it, color = Muted, fontSize = 12.sp) } }
            }
        }
    }
    if (resetOpen) FactoryResetDialog(s, vm) { resetOpen = false }
    }
}

/**
 * Two confirmations before anything is erased: type the phrase, then "Are you sure?".
 * Android then asks for its own device-admin permission the first time.
 */
@Composable
private fun FactoryResetDialog(s: UiState, vm: TreadmillViewModel, onClose: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    var typed by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf(vm.resetAllowed()) }
    var asked by remember { mutableStateOf(false) }
    // Android's permission screen is another activity: notice when the person comes back from it.
    LaunchedEffect(step) { while (step == 2) { allowed = vm.resetAllowed(); kotlinx.coroutines.delay(1000) } }
    val cancel = { vm.cancelFactoryReset(); onClose() }
    Overlay(cancel) {
        Tile(Modifier.width(820.dp), color = PanelHi, padding = 28.dp) {
            Caps("Factory reset", color = StopRed, size = 15.sp)
            if (step == 1) {
                Text("This erases the console", color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                listOf(
                    "The console goes back to the stock iFit software, as it shipped.",
                    "OpenTreadmill is removed, with every profile, workout and history entry.",
                    "Spotify, Aurora Store and anything else you installed are removed.",
                    "Wi-Fi, time zone and every other setting go back to factory defaults.",
                    "iFit may update itself afterwards, which can block privileged mode.",
                ).forEach { Text("•  $it", color = TextHi, fontSize = 17.sp) }
                Spacer(Modifier.height(16.dp))
                Text("To continue, type:  ${FactoryReset.PHRASE}", color = Muted, fontSize = 16.sp)
                OutlinedTextField(typed, { typed = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PadButton("Cancel", Modifier.weight(1f).height(64.dp), color = Panel, fontSize = 20.sp, onClick = cancel)
                    PadButton("Continue", Modifier.weight(1f).height(64.dp), color = StopRed, textColor = Color.White, fontSize = 20.sp,
                        enabled = FactoryReset.phraseMatches(typed)) { step = 2 }
                }
            } else {
                Text("Are you sure?", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Everything on this console will be erased and it will restart with the iFit software. This can't be undone.",
                    color = TextHi, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                if (!allowed) Text(
                    if (asked) "Allow it on Android's screen (\"Activate\"), then come back and tap Erase everything again."
                    else "Android will first ask you to allow OpenTreadmill to erase the console.",
                    color = Warn, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PadButton("Cancel", Modifier.weight(1f).height(72.dp), color = Panel, fontSize = 22.sp, onClick = cancel)
                    PadButton("Erase everything", Modifier.weight(1f).height(72.dp), color = StopRed, textColor = Color.White, fontSize = 22.sp,
                        enabled = !s.sessionActive && !s.running) {
                        if (vm.resetAllowed()) vm.factoryReset() else { asked = true; vm.requestResetPermission() }
                    }
                }
            }
        }
    }
}

/** Time zone and 12/24-hour clock for the whole console (not per profile). */
@Composable
private fun ClockTile(s: UiState, vm: TreadmillViewModel) {
    val zones = listOf("" to "Android setting") + DevicePrefs.zones
    val i = zones.indexOfFirst { it.first == s.timeZone }.coerceAtLeast(0)
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); kotlinx.coroutines.delay(10_000) } }
    // Re-read after a change so the preview shows the new zone straight away.
    val shown = remember(now, s.timeZone, s.use24h) {
        java.text.SimpleDateFormat(if (s.use24h) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a", java.util.Locale.getDefault()).format(now)
    }
    Tile(Modifier.fillMaxWidth()) {
        Caps("Clock")
        Spacer(Modifier.height(6.dp))
        Text(shown, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(if (s.timeZone.isEmpty()) "Following Android (${java.util.TimeZone.getDefault().id})" else s.timeZone, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Time zone", color = Muted, fontSize = 16.sp, modifier = Modifier.weight(1f))
            PadButton("◀", Modifier.size(52.dp), fontSize = 20.sp, enabled = i > 0) { vm.setTimeZone(zones[i - 1].first) }
            Text(zones[i].second, color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(170.dp))
            PadButton("▶", Modifier.size(52.dp), fontSize = 20.sp, enabled = i < zones.size - 1) { vm.setTimeZone(zones[i + 1].first) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("24-hour clock", color = TextHi, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Switch(checked = s.use24h, onCheckedChange = vm::setUse24h)
        }
    }
}

@Composable
private fun ThemeSwatch(p: Palette, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(p.bg, RoundedCornerShape(12.dp)).border(3.dp, if (selected) p.go else p.line, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(12.dp)) {
        Text(p.name, color = p.textHi, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(p.panel, p.speed, p.incline, p.go).forEach { Box(Modifier.size(26.dp).background(it, RoundedCornerShape(6.dp))) }
        }
    }
}
