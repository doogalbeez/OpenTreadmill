package app.opentreadmill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opentreadmill.core.Workout

class MainActivity : ComponentActivity() {
    private val vm: TreadmillViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_SHOW_APPS, false) == true) vm.requestAppList(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val p = palette
            val scheme = if (p.light) lightColorScheme(primary = p.go, background = p.bg, surface = p.panel, onSurface = p.textHi, onBackground = p.textHi)
                         else darkColorScheme(primary = p.go, background = p.bg, surface = p.panel, onSurface = p.textHi, onBackground = p.textHi)
            MaterialTheme(colorScheme = scheme) {
                val s by vm.ui.collectAsStateWithLifecycle()
                App(s, vm)
            }
        }
    }

    /** Back from Spotify or Settings: pick up the music session again. */
    override fun onResume() {
        super.onResume()
        vm.music.start()
    }

    /** The floating Apps button opens this activity asking for the app list. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_APPS, false)) vm.requestAppList(true)
    }

    companion object { const val EXTRA_SHOW_APPS = "showApps" }
}

private enum class Screen { HOME, BUILDER, HISTORY, SETTINGS }

@Composable
private fun App(s: UiState, vm: TreadmillViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var editing by remember { mutableStateOf<Workout?>(null) }
    val home = { screen = Screen.HOME }

    // Whenever a session is under way (or the belt is still slowing), the run screen is shown,
    // so STOP is always on screen while the belt can move.
    val onRun = s.sessionActive || s.running || s.board.stopping || s.summary != null
    BackHandler(enabled = !onRun && screen != Screen.HOME, onBack = home)

    if (onRun) { RunScreen(s, vm); return }
    when (screen) {
        Screen.HOME -> HomeScreen(s, vm,
            onEdit = { editing = it; screen = Screen.BUILDER },
            onHistory = { screen = Screen.HISTORY }, onSettings = { screen = Screen.SETTINGS })
        Screen.BUILDER -> editing?.let { BuilderScreen(it, s, vm) { editing = null; home() } } ?: home()
        Screen.HISTORY -> HistoryScreen(s, vm, home)
        Screen.SETTINGS -> SettingsScreen(s, vm, home)
    }
}
