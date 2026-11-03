package app.opentreadmill

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView

/** What the floating bar needs to know about the treadmill app, which runs in the same process. */
object Bridge {
    /** True whenever the belt is running, starting or still slowing down. */
    @Volatile var beltActive = false
    /** STOP, as pressed on the treadmill screen. Set by the view model. */
    @Volatile var stop: (() -> Unit)? = null
}

/**
 * A small floating bar shown over every other app (Spotify, Aurora, Settings): Home, Back and
 * Apps, so nobody needs the hidden system navigation. While the belt is moving it also shows a
 * red STOP, so the belt can always be stopped from any app.
 *
 * Enable once: adb shell settings put secure enabled_accessibility_services app.opentreadmill/app.opentreadmill.NavService
 *              adb shell settings put secure accessibility_enabled 1
 */
class NavService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var bar: LinearLayout? = null
    private var stopButton: TextView? = null
    private var showing = false
    private var foreground: String? = null

    override fun onServiceConnected() {
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun button(label: String, bg: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bg) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(64)).apply { setMargins(0, dp(5), 0, dp(5)) }
        }
        val dark = Color.argb(230, 34, 40, 49)
        val stop = button("STOP", Color.rgb(229, 72, 77)) {
            Bridge.stop?.invoke()
            goHome()                                      // back to the treadmill screen
        }.apply { textSize = 24f; visibility = View.GONE }
        stopButton = stop
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(140, 0, 0, 0)) }
            addView(stop)
            addView(button("⌂  Home", dark) { goHome() })
            addView(button("◁  Back", dark) { global("back", GLOBAL_ACTION_BACK) })
            addView(button("▢  Apps", dark) { showApps() })
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(8) }
        wm.addView(bar, lp)
        main.post(refresh)
        android.util.Log.i("NavService", "connected, bar added")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore transient system windows (volume panel, notification shade): they don't change the app in front.
        if (pkg == "com.android.systemui") return
        // Our own floating bar appearing is reported as a window change too; only our activity counts.
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
        foreground = pkg
        android.util.Log.i("NavService", "front: $pkg (${event.className})")
        update()
    }

    /** Re-checked twice a second so STOP appears as soon as the belt starts. */
    private val refresh = object : Runnable {
        override fun run() { update(); main.postDelayed(this, 500) }
    }

    private fun update() {
        // Just (re)started with an app already in front: no window event will come, so ask.
        if (foreground == null) foreground = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val other = foreground != null && foreground != packageName
        bar?.visibility = if (other) View.VISIBLE else View.GONE
        stopButton?.visibility = if (other && Bridge.beltActive) View.VISIBLE else View.GONE
        showing = other
    }

    private fun global(name: String, action: Int) {
        val ok = performGlobalAction(action)
        android.util.Log.i("NavService", "$name pressed, global action ok=$ok")
    }

    /**
     * Back to the treadmill screen. The system Home action is instant (the treadmill app is the
     * home screen); starting the activity directly is the fallback.
     */
    private fun goHome() {
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) { openTreadmill(showApps = false); return }
        // Android ignores Home while it thinks first-time setup isn't finished (as iFit leaves it).
        main.postDelayed({ if (foreground != packageName) openTreadmill(showApps = false) }, 800)
    }

    /** This console has no recent-apps screen, so Apps opens the treadmill app's own list. */
    private fun showApps() = openTreadmill(showApps = true)

    private fun openTreadmill(showApps: Boolean) {
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_SHOW_APPS, showApps))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        bar?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        bar = null
        super.onDestroy()
    }
}
