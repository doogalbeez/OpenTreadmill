package app.treadmill

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.treadmill.core.Controller
import app.treadmill.core.FitPro
import app.treadmill.core.FrameTransport
import app.treadmill.core.Limits
import app.treadmill.core.SessionStats
import app.treadmill.core.SimulatedBoard
import app.treadmill.core.Snapshot
import app.treadmill.core.Stats
import app.treadmill.core.Workout
import app.treadmill.core.WorkoutRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
) {
    val mode get() = board.mode
    val running get() = board.wantRunning
    val paused get() = !board.wantRunning && board.mode == FitPro.Mode.PAUSE
    val keyOut get() = board.keyOut
}

class TreadmillViewModel(app: Application) : AndroidViewModel(app) {
    /** Every board call runs on this one thread, in order. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "treadmill-io") }.asCoroutineDispatcher()
    private val settings = Settings(app)
    val history = HistoryStore(app)

    private val _ui = MutableStateFlow(UiState(useMiles = settings.useMiles, weightKg = settings.weightKg))
    val ui: StateFlow<UiState> = _ui

    private var controller: Controller? = null
    private var sim: SimulatedBoard? = null
    private var pollJob: Job? = null
    private val session = SessionStats { settings.weightKg }
    private var runner: WorkoutRunner? = null
    private var sessionStart = 0L
    private var resumeKph = 0.0
    private var lastOdo = -1

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast("${timeFmt.format(Date())} $line")
            while (logLines.size > 40) logLines.removeFirst()
        }
    }

    init { connect() }

    fun connect() = viewModelScope.launch {
        pollJob?.cancel()
        controller?.let { old -> withContext(io) { old.stop(); repeat(3) { runCatching { old.tick() } }; old.close() } }
        controller = null; sim = null
        _ui.value = _ui.value.copy(connection = "Connecting…", error = null)
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = UsbHidTransport.findBoard(usb)
        val transport: FrameTransport = try {
            when {
                device == null -> demo("Motor board not found, running the simulator")
                !UsbHidTransport.ensurePermission(ctx, usb, device) -> demo("USB permission was declined, running the simulator")
                else -> withContext(io) { UsbHidTransport.open(usb, device) }
            }
        } catch (e: Exception) {
            demo(e.message ?: "Could not open the motor board")
        }
        val c = Controller(transport, log = ::log)
        withContext(io) { c.connect() }
        controller = c
        _ui.value = _ui.value.copy(connection = if (sim != null) "Simulator" else "Connected", demo = sim != null, limits = c.limits)
        pollJob = pollLoop(c)
    }

    private fun demo(reason: String): FrameTransport {
        _ui.value = _ui.value.copy(error = reason)
        log(reason)
        return SimulatedBoard().also { sim = it }
    }

    private fun pollLoop(c: Controller) = viewModelScope.launch {
        var last = System.nanoTime()
        var workoutAcc = 0.0
        while (isActive) {
            val now = System.nanoTime()
            val dt = (now - last) / 1e9; last = now
            val s = withContext(io) { sim?.tick(dt); c.tick() }

            // Distance straight from the board's odometer while a session is on.
            if (s.boardOk) {
                if (lastOdo >= 0 && s.odometerM >= lastOdo && sessionStart != 0L) {
                    session.addMeters((s.odometerM - lastOdo).toDouble())
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

            val r = runner
            _ui.value = _ui.value.copy(
                board = s,
                error = if (!s.boardOk && sim == null) "Board not answering (${s.lastStatus})" else _ui.value.error.takeUnless { it?.startsWith("Board not") == true },
                seconds = session.seconds.toInt(), meters = session.meters, kcal = session.kcal,
                segmentIndex = r?.currentIndex() ?: 0, segmentSecondsLeft = r?.secondsLeftInSegment() ?: 0,
                workoutElapsed = r?.elapsed ?: 0,
                log = synchronized(logLines) { logLines.toList().asReversed() },
            )
            delay(200)
        }
    }

    private fun onIo(block: Controller.() -> Unit) = viewModelScope.launch {
        val c = controller ?: return@launch
        withContext(io) { c.block() }
    }

    fun start() {
        if (sessionStart == 0L) { session.reset(); sessionStart = System.currentTimeMillis() }
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
        _ui.value = _ui.value.copy(workout = w, error = null)
        onIo { r.begin() }
    }

    fun endWorkout() { runner = null; _ui.value = _ui.value.copy(workout = null) }

    private fun finishSession() {
        val w = runner?.workout?.name
        runner = null; resumeKph = 0.0
        if (sessionStart != 0L && session.seconds >= 30) {
            history.add(HistoryEntry(sessionStart, session.seconds.toInt(), session.meters, session.kcal, session.avgKph, w))
        }
        sessionStart = 0L
        _ui.value = _ui.value.copy(workout = null)
    }

    fun setUseMiles(v: Boolean) { settings.useMiles = v; _ui.value = _ui.value.copy(useMiles = v) }
    fun setWeightKg(v: Double) { settings.weightKg = v; _ui.value = _ui.value.copy(weightKg = v) }

    fun simulateSafetyKey(inserted: Boolean) { viewModelScope.launch(io) { sim?.keyIn = inserted } }

    override fun onCleared() {
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
