package app.opentreadmill

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Exists only so Android lets this app see other apps' media sessions (Spotify's song and
 * play state). Grant it once: adb shell cmd notification allow_listener app.opentreadmill/app.opentreadmill.MediaListener
 */
class MediaListener : NotificationListenerService()

data class MusicState(
    /** False until notification access is granted; the buttons still work without it. */
    val canSeeSessions: Boolean = false,
    val app: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val spotifyInstalled: Boolean = false,
)

/** Controls whatever music app is playing (Spotify on the console) without leaving the treadmill screen. */
class Music(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(MusicState(spotifyInstalled = launchIntent() != null))
    val state: StateFlow<MusicState> = _state

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it ?: emptyList()) }

    /** Starts watching media sessions. Safe to call again, e.g. after access is granted. */
    fun start() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            pick(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            _state.value = MusicState(canSeeSessions = false, spotifyInstalled = launchIntent() != null)
        }
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback); controller = null
    }

    /** Prefer the session that is playing, then Spotify, then any. */
    private fun pick(list: List<MediaController>) {
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.packageName == SPOTIFY } ?: list.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val m = c?.metadata
        _state.value = MusicState(
            canSeeSessions = true,
            app = c?.packageName,
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING,
            spotifyInstalled = launchIntent() != null,
        )
    }

    fun playPause() {
        val c = controller
        if (c != null) { if (_state.value.playing) c.transportControls.pause() else c.transportControls.play() }
        else key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun next() = controller?.transportControls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = controller?.transportControls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /**
     * Android's media volume, with its own on-screen display. Returns true when [up] was pressed
     * at the maximum: louder than that is the treadmill's own amplifier, on its physical buttons.
     */
    fun volume(up: Boolean): Boolean {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val wasMax = audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= max
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return up && wasMax
    }

    /** Without session access, a media key still reaches the app that last played. */
    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun launchIntent(): Intent? = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)

    /** Opens Spotify. Only offered when the belt is stopped, so STOP is never hidden. */
    fun openSpotify() {
        launchIntent()?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object { const val SPOTIFY = "com.spotify.music" }
}
