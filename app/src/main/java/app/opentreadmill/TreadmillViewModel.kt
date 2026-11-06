package app.opentreadmill

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentreadmill.core.BoardWatch
import app.opentreadmill.core.Controller
import app.opentreadmill.core.FitPro
import app.opentreadmill.core.FrameTransport
import app.opentreadmill.core.Limits
import app.opentreadmill.core.SessionStats
import app.opentreadmill.core.SimulatedBoard
import app.opentreadmill.core.Snapshot
import app.opentreadmill.core.Stats
import app.opentreadmill.core.Workout
import app.opentreadmill.core.WorkoutRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class UiState(
    val connection: String = "Connecting…",
    val demo: Boolean = false,
    val error: String? = null,
    val board: Snapshot = Snapshot(),
    val seconds: Int = 0,
    val meters: Double = 0.0,
    val kcal: Double = 0.0,
    val limits: Limits = Limits(),
    val workout: Workout? = null,
    val segmentIndex: Int = 0,
    val segmentSecondsLeft: Int = 0,
    val workoutElapsed: Int = 0,
    val useMiles: Boolean = true,
    val weightKg: Double = 80.0,
    val log: List<String> = emptyList(),
    val climbM: Double = 0.0,
    val customWorkouts: List<Workout> = emptyList(),
    /** Shown after a session ends, until dismissed. */
    val summary: HistoryEntry? = null,
    /** A session (manual or workout) is under way, running or paused. */
    val sessionActive: Boolean = false,
    val profile: Profile = Profile("default", "Me"),
    val profiles: List<Profile> = emptyList(),
    /** Manual sessions only: stop when this many seconds have run. 0 = no goal. */
    val timeGoal: Int = 0,
    /** The app clock's time zone id, or "" for Android's own. */
    val timeZone: String = "",
    val use24h: Boolean = false,
    val music: MusicState = MusicState(),
    /** The floating Apps button asked for the app list; Home shows it, then clears this. */
    val appListRequested: Boolean = false,
    /** Shown for a few seconds after + is pressed at Android's maximum volume. */
    val volumeNotice: Boolean = false,
) {
    val mode get() = board.mode
    val running get() = board.wantRunning
    val paused get() = !board.wantRunning && board.mode == FitPro.Mode.PAUSE
    val keyOut get() = board.keyOut
}

class TreadmillViewModel(app: Application) : AndroidViewModel(app) {
    /** Every board call runs on this one thread, in order. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "treadmill-io") }.asCoroutineDispatcher()
    private val profiles = ProfileStore(app)
    private var profile = profiles.current()
    var history = HistoryStore(profiles.historyFile(profile.id)); private set
    private var customStore = CustomWorkoutStore(profiles.workoutsFile(profile.id))

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val device = DevicePrefs(app)
    val music = Music(app)

    init {
        applyProfile(profile)
        applyTimeZone(device.timeZone)
        _ui.value = _ui.value.copy(timeZone = device.timeZone, use24h = device.use24h)
        music.start()
        viewModelScope.launch { music.state.collect { _ui.value = _ui.value.copy(music = it) } }
        // The floating bar over other apps can press STOP too.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Bridge.stop = { main.post { stop() } }
    }

    private val reset = FactoryReset(app)

    fun resetAllowed() = reset.allowed
    fun requestResetPermission() { if (sessionStart == 0L && !_ui.value.running) reset.requestPermission() }
    /** Cancelled: give back the device-admin permission so the app can't erase anything. */
    fun cancelFactoryReset() = reset.dropPermission()

    /** Erases the console. Refused while a session is on or the belt is moving. */
    fun factoryReset() {
        val s = _ui.value
        if (sessionStart != 0L || s.running || s.board.stopping || !reset.allowed) return
        log("factory reset confirmed")
        runBlocking(io) { controller?.let { c -> c.stop(); repeat(3) { runCatching { c.tick() } } } }
        reset.eraseEverything()
    }

    private var volumeNoticeJob: Job? = null

    /** Volume buttons on screen; at the top of Android's range, point to the treadmill's own buttons. */
    fun volume(up: Boolean) {
        if (!music.volume(up)) return
        volumeNoticeJob?.cancel()
        _ui.value = _ui.value.copy(volumeNotice = true)
        volumeNoticeJob = viewModelScope.launch { delay(6000); _ui.value = _ui.value.copy(volumeNotice = false) }
    }

    fun requestAppList(v: Boolean) { _ui.value = _ui.value.copy(appListRequested = v && sessionStart == 0L) }

    data class AppEntry(val label: String, val pkg: String, val icon: android.graphics.Bitmap?)

    /** Apps that can be opened from the console, for the Apps button (this app left out). */
    fun launchableApps(): List<AppEntry> {
        val pm = getApplication<Application>().packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != getApplication<Application>().packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, runCatching { drawableToBitmap(it.loadIcon(pm)) }.getOrNull()) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    /** Opening another app hides STOP, so it is refused while a session is under way. */
    fun openApp(pkg: String) {
        if (sessionStart != 0L || _ui.value.running) return
        val ctx = getApplication<Application>()
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        val size = 96
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        d.setBounds(0, 0, size, size); d.draw(c)
        return b
    }

    /** Opening Spotify hides this app, so it is refused while a session is under way. */
    fun openSpotify() { if (sessionStart == 0L && !_ui.value.running) music.openSpotify() }

    /** The whole app formats times in this zone ("" = Android's zone). */
    private fun applyTimeZone(id: String) {
        java.util.TimeZone.setDefault(if (id.isEmpty()) null else java.util.TimeZone.getTimeZone(id))
    }

    fun setTimeZone(id: String) {
        device.timeZone = id
        applyTimeZone(id)
        _ui.value = _ui.value.copy(timeZone = id)
    }

    fun setUse24h(v: Boolean) { device.use24h = v; _ui.value = _ui.value.copy(use24h = v) }

    /** Loads a profile's settings, theme, history and workouts. */
    private fun applyProfile(p: Profile) {
        profile = p
        profiles.setCurrent(p.id)
        history = HistoryStore(profiles.historyFile(p.id))
        customStore = CustomWorkoutStore(profiles.workoutsFile(p.id))
        palette = Palettes.named(p.theme)
        _ui.value = _ui.value.copy(profile = p, profiles = profiles.all(), useMiles = p.useMiles, weightKg = p.weightKg,
            customWorkouts = customStore.all(), summary = null)
    }

    private fun updateProfile(change: (Profile) -> Profile) {
        val p = change(profile)
        profiles.save(p)
        profile = p
        palette = Palettes.named(p.theme)
        _ui.value = _ui.value.copy(profile = p, profiles = profiles.all(), useMiles = p.useMiles, weightKg = p.weightKg)
    }

    /** Switching is only allowed between sessions, so a workout is never saved to the wrong person. */
    fun switchProfile(id: String) {
        if (sessionStart != 0L) return
        profiles.all().firstOrNull { it.id == id }?.let(::applyProfile)
    }

    fun addProfile(name: String, color: Int) {
        if (sessionStart != 0L) return
        applyProfile(profiles.add(name, color))
    }

    fun renameProfile(name: String) { if (name.isNotBlank()) updateProfile { it.copy(name = name.trim()) } }
    fun setProfileColor(color: Int) = updateProfile { it.copy(color = color) }
    fun setTheme(name: String) = updateProfile { it.copy(theme = name) }

    fun deleteProfile(id: String) {
        if (sessionStart != 0L) return
        if (profiles.delete(id)) applyProfile(profiles.current())
    }

    private var controller: Controller? = null
    private var sim: SimulatedBoard? = null
    private var pollJob: Job? = null
    private val session = SessionStats { profile.weightKg }
    private var runner: WorkoutRunner? = null
    private var sessionStart = 0L
    private var resumeKph = 0.0
    private var lastOdo = -1
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connecting = false
    /** USB name of the board we have open; null while on the simulator. */
    private var connectedBoard: String? = null
    private var declinedBoard: String? = null

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) {
        android.util.Log.i("OpenTreadmill", line)   // also in logcat: adb logcat -s OpenTreadmill
        synchronized(logLines) {
            logLines.addLast("${timeFmt.format(Date())} $line")
            while (logLines.size > 40) logLines.removeFirst()
        }
    }

    init { connect() }

    /**
     * Opens the board, or the simulator if there is none. Also called by the poll loop when the board
     * comes or goes. The new Controller starts stopped, so reconnecting never moves the belt by itself.
     */
    fun connect() {
        if (connecting) return
        connecting = true
        viewModelScope.launch {
            try {
                pollJob?.cancel()
                controller?.let { old -> withContext(io) { old.stop(); repeat(3) { runCatching { old.tick() } }; old.close() } }
                controller = null; sim = null; connectedBoard = null
                // A running workout holds the old controller, and the belt lost power anyway.
                if (sessionStart != 0L) finishSession()
                lastOdo = -1
                _ui.value = _ui.value.copy(connection = "Connecting…", error = null)
                val ctx = getApplication<Application>()
                val device = UsbHidTransport.findBoard(usb)
                val transport: FrameTransport = try {
                    when {
                        device == null -> demo("Motor board not found, running the simulator")
                        !UsbHidTransport.ensurePermission(ctx, usb, device) -> {
                            declinedBoard = device.deviceName
                            demo("USB permission was declined, running the simulator")
                        }
                        else -> withContext(io) { UsbHidTransport.open(usb, device) }.also { connectedBoard = device.deviceName }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    demo(e.message ?: "Could not open the motor board")
                }
                val c = Controller(transport, log = ::log)
                withContext(io) { c.connect() }
                controller = c
                _ui.value = _ui.value.copy(connection = if (sim != null) "Simulator" else "Connected", demo = sim != null, limits = c.limits)
                log(if (sim != null) "using the simulator" else "connected to the motor board")
                pollJob = pollLoop(c)
            } finally {
                connecting = false
            }
        }
    }

    private fun demo(reason: String): FrameTransport {
        _ui.value = _ui.value.copy(error = reason)
        log(reason)
        return SimulatedBoard().also { sim = it }
    }

    private fun pollLoop(c: Controller) = viewModelScope.launch {
        var last = System.nanoTime()
        var workoutAcc = 0.0
        var lastBoardCheck = System.currentTimeMillis()
        var lastGoodReply = lastBoardCheck
        while (isActive) {
            val now = System.nanoTime()
            val dt = (now - last) / 1e9; last = now
            val s = withContext(io) { sim?.tick(dt); c.tick() }
            Bridge.beltActive = s.wantRunning || s.stopping || s.beltKph > 0.3
            // Started from the console's own Start button: begin a session so the screen (and STOP) follow.
            if (s.wantRunning && sessionStart == 0L) { session.reset(); sessionStart = System.currentTimeMillis(); _ui.value = _ui.value.copy(summary = null) }

            // Distance straight from the board's odometer while a session is on.
            if (s.boardOk) {
                if (lastOdo >= 0 && s.odometerM >= lastOdo && sessionStart != 0L) {
                    session.addMeters((s.odometerM - lastOdo).toDouble(), s.incline)
                }
                lastOdo = s.odometerM
            }
            if (sessionStart != 0L && (s.wantRunning || s.beltKph > 0.3)) session.add(dt, s.beltKph, s.incline)

            runner?.let { r ->
                if (s.mode == FitPro.Mode.RUNNING && s.wantRunning) {
                    workoutAcc += dt
                    while (workoutAcc >= 1.0) { withContext(io) { r.tick() }; workoutAcc -= 1.0 }
                }
                if (r.finished) finishSession()
            }
            if (s.keyOut && runner != null) { runner = null; _ui.value = _ui.value.copy(workout = null) }

            // Manual time goal reached: stop like a finished workout.
            val goal = _ui.value.timeGoal
            if (runner == null && goal > 0 && sessionStart != 0L && s.wantRunning && session.seconds >= goal) {
                log("time goal ${fmtTime(goal)} reached")
                stop()
            }

            val r = runner
            _ui.value = _ui.value.copy(
                board = s,
                workout = r?.workout,
                error = s.problem ?: if (!s.boardOk && sim == null) "Board not answering (${s.lastStatus})" else _ui.value.error.takeUnless { it?.startsWith("Board not") == true },
                seconds = session.seconds.toInt(), meters = session.meters, kcal = session.kcal, climbM = session.climbM,
                sessionActive = sessionStart != 0L,
                segmentIndex = r?.currentIndex() ?: 0, segmentSecondsLeft = r?.secondsLeftInSegment() ?: 0,
                workoutElapsed = r?.elapsed ?: 0,
                log = synchronized(logLines) { logLines.toList().asReversed() },
            )

            // The board was switched off, came back, or turned up after the app started: open it again.
            val clock = System.currentTimeMillis()
            if (s.boardOk) lastGoodReply = clock
            if (clock - lastBoardCheck >= BoardWatch.CHECK_EVERY_MS) {
                lastBoardCheck = clock
                val present = UsbHidTransport.findBoard(usb)?.deviceName
                if (BoardWatch.shouldReconnect(connectedBoard, present, declinedBoard, clock - lastGoodReply)) {
                    log("board link changed (was ${connectedBoard ?: "simulator"}, now ${present ?: "none"}), reconnecting")
                    connect()
                    return@launch
                }
            }
            delay(200)
        }
    }

    private fun onIo(block: Controller.() -> Unit) = viewModelScope.launch {
        val c = controller ?: return@launch
        withContext(io) { c.block() }
    }

    fun start() {
        if (sessionStart == 0L) { session.reset(); sessionStart = System.currentTimeMillis() }
        _ui.value = _ui.value.copy(summary = null)
        val kph = if (resumeKph > 0) resumeKph else _ui.value.limits.minKph
        _ui.value = _ui.value.copy(error = null)
        onIo { start(kph) }
    }

    fun pause() { resumeKph = _ui.value.board.goalKph; onIo { pause() } }

    /** STOP: always goes straight to the board thread, never blocked by anything else. */
    fun stop() {
        onIo { stop() }
        finishSession()
    }

    fun speedBy(deltaKph: Double) = onIo { setSpeed(snap.goalKph + deltaKph) }
    fun speedTo(kph: Double) = onIo { setSpeed(kph) }
    fun inclineBy(delta: Double) = onIo { setIncline(snap.targetIncline + delta) }
    fun inclineTo(pct: Double) = onIo { setIncline(pct) }

    fun startWorkout(w: Workout) {
        val c = controller ?: return
        session.reset(); sessionStart = System.currentTimeMillis(); resumeKph = 0.0
        val r = WorkoutRunner(c, w)
        runner = r
        _ui.value = _ui.value.copy(workout = w, error = null, summary = null)
        onIo { r.begin() }
    }

    /** Leave the workout program but keep the belt as it is (manual control from here). */
    fun endWorkout() { runner = null; _ui.value = _ui.value.copy(workout = null) }

    /** Lengthen or shorten the workout step that is running now. */
    fun adjustStep(deltaSeconds: Int) {
        val r = runner ?: return
        viewModelScope.launch {
            withContext(io) { r.adjustCurrentStep(deltaSeconds) }
            _ui.value = _ui.value.copy(workout = r.workout, segmentSecondsLeft = r.secondsLeftInSegment())
        }
    }

    /** Manual sessions: stop after [seconds] of running (0 = no goal). */
    fun setTimeGoal(seconds: Int) { _ui.value = _ui.value.copy(timeGoal = seconds.coerceIn(0, 3 * 3600)) }

    private fun finishSession() {
        val w = runner?.workout?.name
        runner = null; resumeKph = 0.0
        var entry: HistoryEntry? = null
        if (sessionStart != 0L && session.seconds >= 30) {
            entry = HistoryEntry(sessionStart, session.seconds.toInt(), session.meters, session.kcal, session.avgKph, w, session.climbM)
            history.add(entry)
        }
        sessionStart = 0L
        _ui.value = _ui.value.copy(workout = null, summary = entry, sessionActive = false, timeGoal = 0)
    }

    fun dismissSummary() { _ui.value = _ui.value.copy(summary = null) }

    fun saveWorkout(w: Workout) {
        customStore.save(w)
        _ui.value = _ui.value.copy(customWorkouts = customStore.all())
    }

    fun deleteWorkout(id: String) {
        customStore.delete(id)
        _ui.value = _ui.value.copy(customWorkouts = customStore.all())
    }

    fun setUseMiles(v: Boolean) = updateProfile { it.copy(useMiles = v) }
    fun setWeightKg(v: Double) = updateProfile { it.copy(weightKg = v) }

    fun simulateSafetyKey(inserted: Boolean) { viewModelScope.launch(io) { sim?.keyIn = inserted } }

    override fun onCleared() {
        music.stop()
        // Never leave the belt running if the app goes away.
        runBlocking(io) { controller?.let { c -> c.stop(); repeat(5) { runCatching { c.tick() } }; c.close() } }
        io.close()
    }

    companion object {
        fun fmtSpeed(kph: Double, miles: Boolean) = "%.1f".format(if (miles) kph / Stats.KPH_PER_MPH else kph)
        fun speedUnit(miles: Boolean) = if (miles) "mph" else "km/h"
        fun fmtDistance(m: Double, miles: Boolean) = "%.2f".format(if (miles) m / 1609.344 else m / 1000)
        fun distanceUnit(miles: Boolean) = if (miles) "mi" else "km"
        fun fmtTime(sec: Int) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    }
}
