package app.opentreadmill

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import app.opentreadmill.core.FitPro
import app.opentreadmill.core.FrameTransport
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Raw 64-byte HID frames to the FitPro board over its interrupt endpoints. */
class UsbHidTransport private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : FrameTransport {

    override fun exchange(request: ByteArray): ByteArray {
        val out = request.copyOf(FitPro.FRAME_SIZE)
        if (conn.bulkTransfer(outEp, out, out.size, TIMEOUT_MS) < 0) throw IOException("USB write failed")
        Thread.sleep(READ_DELAY_MS)
        val buf = ByteArray(FitPro.FRAME_SIZE)
        // Skip any stale frame that doesn't answer this request.
        repeat(3) {
            val n = conn.bulkTransfer(inEp, buf, buf.size, TIMEOUT_MS)
            if (n < 0) throw IOException("USB read timed out")
            if (buf[0] == request[0] && buf[2] == request[2]) return buf.copyOf(n)
        }
        throw IOException("No matching response from the motor board")
    }

    override fun close() {
        runCatching { conn.releaseInterface(intf) }
        conn.close()
    }

    companion object {
        private const val TIMEOUT_MS = 1000
        private const val READ_DELAY_MS = 80L
        private const val ACTION_PERMISSION = "app.opentreadmill.USB_PERMISSION"

        fun findBoard(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull {
            it.vendorId == FitPro.VENDOR_ID && it.productId == FitPro.PRODUCT_ID
        }

        /** Ask for USB permission if we don't have it yet. Returns true when granted. */
        suspend fun ensurePermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
            if (usb.hasPermission(device)) return true
            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                val filter = IntentFilter(ACTION_PERMISSION)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
                usb.requestPermission(device, pi)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            }
        }

        fun open(usb: UsbManager, device: UsbDevice): UsbHidTransport {
            val intf = device.getInterface(0)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            val conn = usb.openDevice(device) ?: throw IOException("Could not open the motor board")
            if (inEp == null || outEp == null) { conn.close(); throw IOException("Motor board endpoints not found") }
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("Motor board is in use by another app. Disable the iFit apps first.")
            }
            return UsbHidTransport(conn, intf, inEp, outEp)
        }
    }
}
