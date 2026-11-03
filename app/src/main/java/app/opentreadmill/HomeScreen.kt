package app.opentreadmill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentreadmill.TreadmillViewModel.Companion.distanceUnit
import app.opentreadmill.TreadmillViewModel.Companion.fmtDistance
import app.opentreadmill.TreadmillViewModel.Companion.fmtSpeed
import app.opentreadmill.TreadmillViewModel.Companion.speedUnit
import app.opentreadmill.core.Workout
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun HomeScreen(s: UiState, vm: TreadmillViewModel, onEdit: (Workout) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    var category by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<Workout?>(null) }
    var choosingProfile by remember { mutableStateOf(false) }
    var choosingApp by remember { mutableStateOf(false) }
    LaunchedEffect(s.appListRequested) { if (s.appListRequested) { choosingApp = true; vm.requestAppList(false) } }
    val all = remember(s.customWorkouts) { s.customWorkouts + WorkoutLibrary.builtIn() }
    val shown = when (category) {
        "All" -> all
        else -> all.filter { it.category == category }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Header(s, onProfile = { choosingProfile = true }, onSpotify = vm::openSpotify, onApps = { choosingApp = true }, onHistory = onHistory, onSettings = onSettings)
            if (s.keyOut) Banner("SAFETY KEY OUT  ·  Put it back before starting", StopRed)
            else if (s.demo) Banner("Simulator: the motor board isn't connected", Warn)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigTile("Manual", "Just start the belt and go", Go, Modifier.weight(1.3f), onClick = vm::start)
                    BigTile("Create workout", "Build your own, step by step", PanelHi, Modifier.weight(1f), textColor = TextHi) { onEdit(WorkoutLibrary.blank()) }
                    WeekTile(s, vm, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("All") + WorkoutLibrary.categories + WorkoutLibrary.MINE) { c ->
                            Pill(c, category == c, color = if (c == "All") TextHi else categoryColor(c)) { category = c }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (shown.isEmpty()) {
                        Text("No workouts here yet. Tap Create workout to make one.", color = Muted, fontSize = 18.sp)
                    }
                    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shown, key = { (it.id ?: "") + it.name }) { w -> WorkoutCard(w, s.useMiles) { open = w } }
                    }
                }
            }
        }
        open?.let { w ->
            WorkoutDetail(w, s.useMiles,
                onStart = { scaled -> open = null; vm.startWorkout(scaled) },
                onEdit = {
                    open = null
                    onEdit(if (w.id != null) w else w.copy(name = "${w.name} (copy)", category = WorkoutLibrary.MINE, id = UUID.randomUUID().toString()))
                },
                onDelete = w.id?.let { id -> { open = null; vm.deleteWorkout(id) } },
                onClose = { open = null })
        }
        if (choosingProfile) ProfilePicker(s, vm) { choosingProfile = false }
        if (choosingApp) AppPicker(vm) { choosingApp = false }
    }
}

@Composable
private fun Header(s: UiState, onProfile: () -> Unit, onSpotify: () -> Unit, onApps: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.background(Panel, RoundedCornerShape(50)).clickable(onClick = onProfile).padding(6.dp).padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.profile, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(s.profile.name, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Switch profile ▾", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(SimpleDateFormat(if (s.use24h) "EEE d MMM  ·  HH:mm" else "EEE d MMM  ·  h:mm a", Locale.getDefault()).format(now), color = TextHi, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Text(s.connection, color = if (s.demo) Warn else Muted, fontSize = 16.sp)
        Spacer(Modifier.width(16.dp))
        if (s.music.spotifyInstalled) {
            Pill(s.music.title?.let { "♪ ${it.take(18)}" } ?: "♪ Spotify", false, color = Go, onClick = onSpotify)
            Spacer(Modifier.width(8.dp))
        }
        Pill("Apps", false, onClick = onApps)
        Spacer(Modifier.width(8.dp))
        Pill("History", false, onClick = onHistory)
        Spacer(Modifier.width(8.dp))
        Pill("Settings", false, onClick = onSettings)
    }
}

@Composable
fun Avatar(p: Profile, size: Dp) {
    Box(Modifier.size(size).background(Color(Profiles.avatarColors[p.color % Profiles.avatarColors.size]), CircleShape), contentAlignment = Alignment.Center) {
        Text(p.initial, color = OnAccent, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigTile(title: String, sub: String, color: Color, modifier: Modifier, textColor: Color = OnAccent, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().background(color, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(20.dp),
        verticalArrangement = Arrangement.Bottom) {
        Text(title, color = textColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = textColor.copy(alpha = 0.75f), fontSize = 16.sp)
    }
}

@Composable
private fun WeekTile(s: UiState, vm: TreadmillViewModel, modifier: Modifier) {
    val week = remember(s.profile.id, s.summary) {
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        vm.history.all().filter { it.startedAt >= since }
    }
    Tile(modifier.fillMaxWidth()) {
        Caps("Last 7 days", size = 14.sp)
        Spacer(Modifier.weight(1f))
        Text("${week.size} workout${if (week.size == 1) "" else "s"}", color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${fmtDistance(week.sumOf { it.meters }, s.useMiles)} ${distanceUnit(s.useMiles)}  ·  ${week.sumOf { it.seconds } / 60} min  ·  ${"%.0f".format(week.sumOf { it.kcal })} kcal",
            color = Muted, fontSize = 15.sp)
    }
}

@Composable
private fun WorkoutCard(w: Workout, miles: Boolean, onClick: () -> Unit) {
    val c = categoryColor(w.category)
    Column(Modifier.fillMaxWidth().height(200.dp).background(Panel, RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(c, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)))
        Column(Modifier.padding(14.dp)) {
            Caps(w.category, color = c, size = 12.sp)
            Text(w.name, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${w.totalSeconds / 60} min  ·  ${fmtDistance(w.plannedMeters, miles)} ${distanceUnit(miles)}  ·  up to ${fmtSpeed(w.maxKph, miles)} ${speedUnit(miles)}",
                color = Muted, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            ProfileChart(w, Modifier.fillMaxWidth().height(86.dp), gap = 1.dp)
        }
    }
}

@Composable
private fun WorkoutDetail(base: Workout, miles: Boolean, onStart: (Workout) -> Unit, onEdit: () -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var length by remember { mutableIntStateOf(base.totalSeconds) }
    val w = remember(length) { base.scaledTo(length) }
    Overlay(onClose) {
        Tile(Modifier.width(940.dp), color = PanelHi, padding = 26.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caps(w.category, color = categoryColor(w.category), size = 14.sp)
                    Text(w.name, color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                PadButton("✕", Modifier.size(56.dp), color = Panel, onClick = onClose)
            }
            if (w.description.isNotBlank()) Text(w.description, color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(18.dp))
            ProfileChart(w, Modifier.fillMaxWidth().height(200.dp))
            Row(Modifier.padding(top = 6.dp)) {
                Text("■ Speed", color = SpeedColor, fontSize = 14.sp); Spacer(Modifier.width(16.dp)); Text("■ Incline", color = InclineColor, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                // Length: the main part stretches or shrinks; warm-up and cool-down stay as they are.
                Column {
                    Caps("Length", size = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PadButton("–", Modifier.size(52.dp), color = Panel, enabled = w.totalSeconds > base.minScaledSeconds) {
                            length = (w.totalSeconds - 300).coerceAtLeast(base.minScaledSeconds)
                        }
                        Text("${(w.totalSeconds + 30) / 60} min", color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        PadButton("+", Modifier.size(52.dp), color = Panel, enabled = w.totalSeconds < 3 * 3600) { length = w.totalSeconds + 300 }
                    }
                }
                DetailStat("Distance", "${fmtDistance(w.plannedMeters, miles)} ${distanceUnit(miles)}")
                DetailStat("Top speed", "${fmtSpeed(w.maxKph, miles)} ${speedUnit(miles)}")
                DetailStat("Top incline", "%.1f%%".format(w.maxIncline))
                DetailStat("Steps", "${w.segments.size}")
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PadButton("Start", Modifier.weight(2f).height(76.dp), color = Go, textColor = OnAccent, fontSize = 30.sp) { onStart(w) }
                PadButton(if (w.id != null) "Edit" else "Copy & edit", Modifier.weight(1f).height(76.dp), color = Panel, fontSize = 22.sp, onClick = onEdit)
                onDelete?.let {
                    PadButton(if (confirmDelete) "Tap to delete" else "Delete", Modifier.weight(1f).height(76.dp),
                        color = if (confirmDelete) StopRed else Panel, textColor = if (confirmDelete) Color.White else TextHi, fontSize = 22.sp) {
                        if (confirmDelete) it() else confirmDelete = true
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Column { Caps(label, size = 13.sp); Text(value, color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
}

/** A dimmed full-screen layer; tapping outside the content closes it. */
@Composable
fun Overlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Box(Modifier.clickable(enabled = false) {}) { content() }
    }
}

@Composable
private fun ProfilePicker(s: UiState, vm: TreadmillViewModel, onClose: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf((s.profiles.size) % Profiles.avatarColors.size) }
    Overlay(onClose) {
        Tile(Modifier.width(860.dp), color = PanelHi, padding = 26.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Who's running?", color = TextHi, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                PadButton("✕", Modifier.size(56.dp), color = Panel, onClick = onClose)
            }
            Spacer(Modifier.height(20.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                items(s.profiles, key = { it.id }) { p ->
                    Column(Modifier.width(120.dp).clickable { vm.switchProfile(p.id); onClose() }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.border(4.dp, if (p.id == s.profile.id) TextHi else Color.Transparent, CircleShape).padding(6.dp)) { Avatar(p, 92.dp) }
                        Spacer(Modifier.height(8.dp))
                        Text(p.name, color = TextHi, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    Column(Modifier.width(120.dp).clickable { adding = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(6.dp).size(92.dp).border(2.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                            Text("+", color = Muted, fontSize = 44.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Add", color = Muted, fontSize = 18.sp)
                    }
                }
            }
            if (adding) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
                    PadButton("Create", Modifier.width(160.dp).height(60.dp), color = Go, textColor = OnAccent, enabled = name.isNotBlank()) {
                        vm.addProfile(name, color); onClose()
                    }
                }
                Spacer(Modifier.height(12.dp))
                ColorChoices(color) { color = it }
            }
        }
    }
}

/** Every app on the console, big enough to tap. Use Home on the floating bar to come back. */
@Composable
private fun AppPicker(vm: TreadmillViewModel, onClose: () -> Unit) {
    val apps = remember { vm.launchableApps() }
    Overlay(onClose) {
        Tile(Modifier.width(980.dp), color = PanelHi, padding = 26.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Apps", color = TextHi, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("Tap ⌂ Home on the floating buttons to come back here.", color = Muted, fontSize = 16.sp)
                }
                PadButton("✕", Modifier.size(56.dp), color = Panel, onClick = onClose)
            }
            Spacer(Modifier.height(18.dp))
            LazyVerticalGrid(GridCells.Fixed(6), Modifier.heightIn(max = 520.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(apps, key = { it.pkg }) { a ->
                    Column(Modifier.background(Panel, RoundedCornerShape(14.dp)).clickable { onClose(); vm.openApp(a.pkg) }.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        a.icon?.let { androidx.compose.foundation.Image(it.asImageBitmap(), a.label, Modifier.size(64.dp)) }
                            ?: Box(Modifier.size(64.dp).background(Line, RoundedCornerShape(14.dp)))
                        Spacer(Modifier.height(8.dp))
                        Text(a.label, color = TextHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun ColorChoices(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Profiles.avatarColors.forEachIndexed { i, c ->
            Box(Modifier.size(48.dp).border(3.dp, if (i == selected) TextHi else Color.Transparent, CircleShape).padding(5.dp)
                .background(Color(c), CircleShape).clickable { onPick(i) })
        }
    }
}
