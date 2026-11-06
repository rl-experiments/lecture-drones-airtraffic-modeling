package com.example.djihellodrone

import com.example.djihellodrone.Drone.Companion.APPROACH_GAIN
import com.example.djihellodrone.Drone.Companion.CONTROL_PERIOD_MS
import com.example.djihellodrone.Drone.Companion.STICK_PER_MPS_HORIZONTAL
import com.example.djihellodrone.Drone.Companion.STICK_PER_MPS_VERTICAL
import com.example.djihellodrone.Drone.Companion.toStick
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val LEG_MAX_MPS = 0.6         // top speed on a leg
private const val LEG_ARRIVE_M = 0.15       // "reached the point"
private const val LEG_TIMEOUT_MS = 15_000L
private const val HEIGHT_FIRM_BAND_M = 0.3  // a smaller height error gets its full correction

/**
 * Where the drone is, in metres north and east of where tracking started and up
 * from [startUp]: its own velocity added up over time. The flight controller measures
 * that velocity on GPS or on the vision sensors, so this also works indoors. It
 * drifts slowly, which is fine for a flight of a few metres.
 */
class Tracker(startUp: Double = 0.0) {
    private var north = 0.0
    private var east = 0.0
    private var up = startUp
    private var last: Velocity3D? = null
    private var lastNs = 0L
    @Volatile var updates = 0
        private set

    fun start() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this,
            object : CommonCallbacks.KeyListener<Velocity3D> {
                override fun onValueChange(oldValue: Velocity3D?, newValue: Velocity3D?) {
                    if (newValue != null) add(newValue)
                }
            })
    }

    fun stop() = KeyManager.getInstance().cancelListen(this)

    // The key only reports changes, so the last velocity holds until the next one.
    @Synchronized private fun add(v: Velocity3D) {
        val now = System.nanoTime()
        advance(now)
        last = v
        lastNs = now
        updates++
    }

    /** The velocity last reported by the drone. */
    @Synchronized fun velocity(): Velocity3D? = last

    /** Moves the height a share [gain] of the way to [measured]. Returns the new height. */
    @Synchronized fun correctUp(measured: Double, gain: Double): Double {
        advance(System.nanoTime())
        up += (measured - up) * gain
        return up
    }

    /** North, east, up. */
    @Synchronized fun position(): Triple<Double, Double, Double> {
        advance(System.nanoTime())
        return Triple(north, east, up)
    }

    private fun advance(now: Long) {
        val v = last ?: return
        val dt = (now - lastNs) / 1e9
        north += (v.x ?: 0.0) * dt
        east += (v.y ?: 0.0) * dt
        up -= (v.z ?: 0.0) * dt   // z points down
        lastNs = now
    }
}

/**
 * Flies to ([north], [east]) on the [tracker]'s position, slowing down on the way in.
 * With [up] it also climbs or sinks to that height, on a straight line. With [firmHeight]
 * a small height error gets its full correction even early on a long level line, where
 * the straight-line share of the stick would be too small to move the drone. Gives up
 * after [LEG_TIMEOUT_MS] where it is; every control step goes to the flight log.
 */
fun Drone.driveTo(tracker: Tracker, north: Double, east: Double, up: Double? = null, firmHeight: Boolean = false) {
    val deadline = System.currentTimeMillis() + LEG_TIMEOUT_MS
    var h = heading() ?: 0.0
    var lastStepMs = System.currentTimeMillis()
    flightLog?.line(String.format(Locale.US, "leg target n=%.2f e=%.2f u=%s", north, east, up?.let { "%.2f".format(Locale.US, it) }))
    try {
        while (true) {
            checkAbort()
            val (n, e, u) = tracker.position()
            val dn = north - n
            val de = east - e
            val du = if (up != null) up - u else 0.0
            val dist = sqrt(dn * dn + de * de + du * du)
            if (dist < LEG_ARRIVE_M) {
                flightLog?.line(String.format(Locale.US, "leg arrived dist=%.2f", dist))
                return
            }
            if (System.currentTimeMillis() > deadline) {
                log(String.format(Locale.US, "Gave up %.2f m short of the point", dist))
                flightLog?.line(String.format(Locale.US, "leg gave_up dist=%.2f", dist))
                return
            }
            h = heading() ?: h
            val r = Math.toRadians(h)
            val scale = min(LEG_MAX_MPS, APPROACH_GAIN * dist) / dist
            // North/east to the drone's own forward/right.
            val forward = (dn * cos(r) + de * sin(r)) * scale
            val right = (-dn * sin(r) + de * cos(r)) * scale
            val stickForward = toStick(forward * STICK_PER_MPS_HORIZONTAL)
            val stickRight = toStick(right * STICK_PER_MPS_HORIZONTAL)
            val climb = if (firmHeight && abs(du) < HEIGHT_FIRM_BAND_M) du * APPROACH_GAIN else du * scale
            val stickUp = toStick(climb * STICK_PER_MPS_VERTICAL)
            sticks.rightStick.verticalPosition = stickForward
            sticks.rightStick.horizontalPosition = stickRight
            sticks.leftStick.verticalPosition = stickUp
            val v = tracker.velocity()
            val now = System.currentTimeMillis()
            flightLog?.line(String.format(Locale.US,
                "step dt_ms=%d n=%.2f e=%.2f u=%.2f dist=%.2f heading=%.1f stick_fwd=%d stick_right=%d stick_up=%d vn=%.2f ve=%.2f vd=%.2f updates=%d",
                now - lastStepMs, n, e, u, dist, h, stickForward, stickRight, stickUp,
                v?.x ?: Double.NaN, v?.y ?: Double.NaN, v?.z ?: Double.NaN, tracker.updates))
            lastStepMs = now
            Thread.sleep(CONTROL_PERIOD_MS)
        }
    } finally {
        zeroSticks()
    }
}
