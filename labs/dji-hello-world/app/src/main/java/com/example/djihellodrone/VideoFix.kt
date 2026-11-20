package com.example.djihellodrone

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Positions from the video app on a second phone, which films the upright figure from the
 * front, facing the drone's nose.
 * That phone joins this phone's Wi-Fi hotspot and broadcasts one UDP line per video frame
 * to [PORT]:
 *
 *     seq=1234 x=0.42 y=-0.31 ok=1
 *
 * [x] and [y] are metres in the figure plane as the camera sees it, from the centre of the
 * circle the tips lie on: x to the camera's right (the drone's left), y up. A tip at angle a round the circle is at x = -r·sin a, y = r·cos a, so the
 * marked tips in the room calibrate the camera. ok=0 means the light was not found.
 */
class VideoFix(context: Context) {
    companion object {
        const val PORT = 5005
    }

    /** One position: [x], [y] as above, received at [atNs] (System.nanoTime). */
    class Fix(val seq: Long, val x: Double, val y: Double, val ok: Boolean, val atNs: Long)

    @Volatile var latest: Fix? = null
        private set
    @Volatile var received = 0
        private set
    @Volatile var malformed = 0
        private set
    /** Runs on the receiving thread for every well-formed line. */
    @Volatile var onFix: ((Fix) -> Unit)? = null

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val s: DatagramSocket
        try {
            // Some phones drop broadcasts to save power unless an app holds this lock.
            lock = wifi.createMulticastLock("videofix").apply { setReferenceCounted(false); acquire() }
            s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 500
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            stop()
            throw IllegalStateException("Cannot listen for the video app on port $PORT: ${e.message}", e)
        }
        socket = s
        thread = Thread({
            val buf = ByteArray(256)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break   // closed by stop()
                }
                val fix = parse(String(packet.data, 0, packet.length, Charsets.US_ASCII), System.nanoTime())
                if (fix == null) { malformed++; continue }
                if (fix.seq == latest?.seq) continue   // the same line again, sent both to us and as broadcast
                received++
                latest = fix
                onFix?.invoke(fix)
            }
        }, "video-fix").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        onFix = null
        socket?.close()
        socket = null
        thread = null
        lock?.release()
        lock = null
    }

    private fun parse(line: String, now: Long): Fix? {
        val kv = line.trim().split(' ').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i > 0) part.substring(0, i) to part.substring(i + 1) else null
        }.toMap()
        val seq = kv["seq"]?.toLongOrNull() ?: return null
        val x = kv["x"]?.toDoubleOrNull()
        val y = kv["y"]?.toDoubleOrNull()
        val ok = kv["ok"] == "1" && x != null && y != null && x.isFinite() && y.isFinite()
        return Fix(seq, x ?: Double.NaN, y ?: Double.NaN, ok, now)
    }

    override fun toString() = String.format(Locale.US, "VideoFix(received=%d, malformed=%d)", received, malformed)
}
