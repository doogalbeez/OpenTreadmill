package app.treadmill

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.treadmill.core.FitPro
import app.treadmill.core.FitProDriver
import app.treadmill.core.Limits
import app.treadmill.core.SafeTreadmill
import app.treadmill.core.SessionStats
import app.treadmill.core.SimulatedTreadmill
import app.treadmill.core.Stats
import app.treadmill.core.TreadmillDriver
import app.treadmill.core.Workout
import app.treadmill.core.WorkoutRunner
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val connection: String = "Connecting…",
    val demo: Boolean = false,
    val error: String? = null,
    val mode: FitPro.Mode = FitPro.Mode.UNKNOWN,
    val safetyKeyIn: Boolean = true,
    val kph: Double = 0.0,
    val targetKph: Double = 0.0,
    val incline: Double = 0.0,
    val targetIncline: Double = 0.0,
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
) {
    val running get() = mode == FitPro.Mode.RUNNING
    val paused get() = mode == FitPro.Mode.PAUSE
}

class TreadmillViewModel(app: Application) : AndroidViewModel(app) {
    /** Every driver call runs on this one thread, in order. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "treadmill-io") }.asCoroutineDispatcher()
    private val settings = Settings(app)
    val history = HistoryStore(app)

    private val _ui = MutableStateFlow(UiState(useMiles = settings.useMiles, weightKg = settings.weightKg))
    val ui: StateFlow<UiState> = _ui

    private var driver: TreadmillDriver? = null
    private var sim: SimulatedTreadmill? = null
    private var tm: SafeTreadmill? = null
    private val session = SessionStats { settings.weightKg }
    private var runner: WorkoutRunner? = null
    private var sessionStart = 0L
    private var resumeKph = 0.0

    init { connect() }

    fun connect() = viewModelScope.launch {
        _ui.value = _ui.value.copy(connection = "Connecting…", error = null)
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = UsbHidTransport.findBoard(usb)
        val d: TreadmillDriver = try {
            when {
                device == null -> demo("Motor board not found, running the simulator")
                !UsbHidTransport.ensurePermission(ctx, usb, device) -> demo("USB permission was declined, running the simulator")
                else -> withContext(io) { FitProDriver(UsbHidTransport.open(usb, device)) }
            }
        } catch (e: Exception) {
            demo(e.message ?: "Could not open the motor board")
        }
        driver = d
        tm = withContext(io) { SafeTreadmill(d, onFault = ::fault) }
        _ui.value = _ui.value.copy(
            connection = if (sim != null) "Simulator" else "Connected",
            demo = sim != null, limits = tm!!.limits,
        )
        pollLoop()
    }

    private fun demo(reason: String): TreadmillDriver {
        _ui.value = _ui.value.copy(error = reason)
        return SimulatedTreadmill().also { sim = it }
    }

    private fun fault(e: Throwable) {
        runner = null
        _ui.value = _ui.value.copy(error = "Stopped for safety: ${e.message}", workout = null)
    }

    private fun pollLoop() = viewModelScope.launch {
        var last = System.nanoTime()
        var workoutAcc = 0.0
        while (isActive) {
            val t = tm ?: break
            val now = System.nanoTime()
            val dt = (now - last) / 1e9; last = now
            val s = withContext(io) {
                sim?.tick(dt)
                val st = t.poll()
                if (st.mode == FitPro.Mode.SLEEP) t.wake()
                st
            }
            session.add(dt, s.speedKph, s.inclinePct)
            runner?.let { r ->
                workoutAcc += dt
                while (workoutAcc >= 1.0 && s.mode == FitPro.Mode.RUNNING) { withContext(io) { r.tick() }; workoutAcc -= 1.0 }
                if (r.finished) finishSession()
            }
            val r = runner
            _ui.value = _ui.value.copy(
                mode = s.mode, safetyKeyIn = s.safetyKeyIn, kph = s.speedKph, incline = s.inclinePct,
                targetKph = t.targetKph, targetIncline = t.targetGrade,
                seconds = session.seconds.toInt(), meters = session.meters, kcal = session.kcal,
                segmentIndex = r?.currentIndex() ?: 0, segmentSecondsLeft = r?.secondsLeftInSegment() ?: 0,
                workoutElapsed = r?.elapsed ?: 0,
            )
            delay(500)
        }
    }

    private fun onIo(block: SafeTreadmill.() -> Unit) = viewModelScope.launch {
        val t = tm ?: return@launch
        try { withContext(io) { t.block() } } catch (e: IllegalStateException) {
            _ui.value = _ui.value.copy(error = e.message)
        }
    }

    fun start() {
        if (sessionStart == 0L) { session.reset(); sessionStart = System.currentTimeMillis() }
        val kph = if (resumeKph > 0) resumeKph else _ui.value.limits.minKph.coerceAtLeast(1.6)
        _ui.value = _ui.value.copy(error = null)
        onIo { start(kph) }
    }

    fun pause() { resumeKph = tm?.targetKph ?: 0.0; onIo { pause() } }

    fun stop() {
        viewModelScope.launch { tm?.let { t -> withContext(io) { t.stop() } }; finishSession() }
    }

    fun speedBy(deltaKph: Double) = onIo { setSpeed(targetKph + deltaKph) }
    fun speedTo(kph: Double) = onIo { setSpeed(kph) }
    fun inclineBy(delta: Double) = onIo { setIncline(targetGrade + delta) }
    fun inclineTo(pct: Double) = onIo { setIncline(pct) }

    fun startWorkout(w: Workout) {
        val t = tm ?: return
        session.reset(); sessionStart = System.currentTimeMillis(); resumeKph = 0.0
        runner = WorkoutRunner(t, w)
        _ui.value = _ui.value.copy(workout = w, error = null)
        onIo { runner?.tick(0) }
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

    fun simulateSafetyKey(inserted: Boolean) { sim?.safetyKeyIn = inserted }

    override fun onCleared() {
        // Never leave the belt running if the app goes away.
        tm?.stop()
        driver?.close()
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
