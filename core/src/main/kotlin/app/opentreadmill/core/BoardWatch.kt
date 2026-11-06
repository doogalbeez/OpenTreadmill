package app.opentreadmill.core

/**
 * Decides when the USB link to the motor board has to be opened again. The console and the board
 * power up separately, and a board that goes away and comes back shows up as a new USB device, so
 * the old connection never works again. Boards are identified by their USB device name.
 */
object BoardWatch {
    const val CHECK_EVERY_MS = 3000L
    const val UNRESPONSIVE_MS = 5000L

    /**
     * @param connected the board we have open, or null when running the simulator
     * @param present the board USB can see now, or null if there is none
     * @param declined the board the person refused USB permission for: not asked about again
     * @param unresponsiveMs how long the open board has gone without one good reply
     */
    fun shouldReconnect(connected: String?, present: String?, declined: String?, unresponsiveMs: Long): Boolean =
        if (connected == null) present != null && present != declined
        else present != connected || unresponsiveMs >= UNRESPONSIVE_MS
}
