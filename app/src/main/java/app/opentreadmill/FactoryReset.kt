package app.opentreadmill

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device admin used for one thing only: the factory reset in Settings. Android shows its own
 * activation screen before it can be used, and the app gives it up again if the reset is cancelled.
 */
class ResetAdmin : DeviceAdminReceiver()

/**
 * Erases the console back to how it shipped: the iFit system apps return as stock, and this app,
 * Spotify, Aurora, profiles, history and every adb setting are wiped. Not reversible.
 */
class FactoryReset(private val ctx: Context) {
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(ctx, ResetAdmin::class.java)

    val allowed get() = dpm.isAdminActive(admin)

    /** Opens Android's "activate device admin" screen; the person decides there. */
    fun requestPermission() {
        ctx.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets OpenTreadmill factory-reset this console, only when you confirm it in OpenTreadmill's Settings.")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Gives the permission back, so the app never keeps the power to erase the console. */
    fun dropPermission() { if (allowed) dpm.removeActiveAdmin(admin) }

    /** Erases everything and restarts. The console comes back with the stock iFit software. */
    fun eraseEverything() = dpm.wipeData(0)

    companion object {
        /** What has to be typed to unlock the reset. */
        const val PHRASE = "enter factory reset"
        fun phraseMatches(typed: String) = typed.trim().equals(PHRASE, ignoreCase = true)
    }
}
