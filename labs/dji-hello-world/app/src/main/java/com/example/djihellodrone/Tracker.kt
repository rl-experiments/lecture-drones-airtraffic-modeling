package com.example.djihellodrone

import com.example.djihellodrone.Drone.Companion.CONTROL_PERIOD_MS
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import java.util.Locale
import kotlin.math.sqrt

private const val LEG_STOP_MS = 2_000L      // at the point: waits at most this long to stand still
private const val LEG_TIMEOUT_MS = 15_000L

/**
 * Where the drone is, in metres north and east of where tracking started and up
 * from [startUp]: its own velocity added up over time. The flight controller measures
 * that velocity on GPS or on the vision sensors, so this also works indoors. It
 * drifts slowly, which is fine for a flight of a few metres.
 */
class Tracker(startUp: Double = 0.0) {
    private val reckoning = DeadReckoning(startUp)
    private var last: Velocity3D? = null
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

    @Synchronized private fun add(v: Velocity3D) {
        reckoning.add(v.x ?: 0.0, v.y ?: 0.0, v.z ?: 0.0, System.nanoTime())
        last = v
        updates++
    }

    /** The velocity last reported by the drone. */
    @Synchronized fun velocity(): Velocity3D? = last

    /** Moves the height a share [gain] of the way to [measured]. Returns the new height. */
    @Synchronized fun correctUp(measured: Double, gain: Double): Double {
        reckoning.advance(System.nanoTime())
        reckoning.correctUp(measured, gain)
        return reckoning.up
    }

    /** Moves the position by [dn] north, [de] east and [du] up, for a correction from outside. */
    @Synchronized fun shift(dn: Double, de: Double, du: Double) {
        reckoning.advance(System.nanoTime())
        reckoning.shift(dn, de, du)
    }

    /** North, east, up. */
    @Synchronized fun position(): Triple<Double, Double, Double> {
        reckoning.advance(System.nanoTime())
        return Triple(reckoning.north, reckoning.east, reckoning.up)
    }
}

/**
 * Flies along the line from [from] to ([north], [east]) on the [tracker]'s position and
 * stops at the point. Without [from] the line starts where the drone is. With [up] it also
 * climbs or sinks to that height on the same line; without, the height is left alone.
 *
 * - It steers towards a point [LEG_LOOKAHEAD_M] ahead on the line, so a drone pushed off
 *   the line flies back onto it instead of taking a new straight line to the point.
 * - It steers and brakes on where it will be in [LEG_LAG_S], since the drone answers late.
 * - Its speed falls with the distance left, so it reaches the point slowly; then it centres
 *   the sticks and waits until the drone stands still, so sharp corners stay sharp.
 *
 * With [firmHeight] a small height error gets its full correction even at low speed,
 * where the line's share of the stick would be too small to move the drone. Gives up
 * after [LEG_TIMEOUT_MS] where it is; every control step goes to the flight log.
 */
fun Drone.driveTo(
    tracker: Tracker, north: Double, east: Double, up: Double? = null,
    firmHeight: Boolean = false, from: Triple<Double, Double, Double>? = null,
) {
    val deadline = System.currentTimeMillis() + LEG_TIMEOUT_MS
    var h = heading() ?: 0.0
    var lastStepMs = System.currentTimeMillis()
    val (an, ae, au) = from ?: tracker.position()
    flightLog?.line(String.format(Locale.US, "leg target n=%.2f e=%.2f u=%s from_n=%.2f from_e=%.2f from_u=%.2f",
        north, east, up?.let { "%.2f".format(Locale.US, it) }, an, ae, au))
    val leg = Leg(an, ae, au, north, east, up, firmHeight)
    try {
        while (true) {
            checkAbort()
            val (n, e, u) = tracker.position()
            val v = tracker.velocity()
            h = heading() ?: h
            val cmd = legStep(leg, n, e, u, v?.x ?: 0.0, v?.y ?: 0.0, v?.z ?: 0.0, h)
            if (cmd.arrived) {
                flightLog?.line(String.format(Locale.US, "leg arrived dist=%.2f", cmd.dist))
                stopAt(tracker, north, east, up)
                return
            }
            if (System.currentTimeMillis() > deadline) {
                log(String.format(Locale.US, "Gave up %.2f m short of the point", cmd.dist))
                flightLog?.line(String.format(Locale.US, "leg gave_up dist=%.2f", cmd.dist))
                return
            }
            sticks.rightStick.verticalPosition = cmd.stickForward
            sticks.rightStick.horizontalPosition = cmd.stickRight
            sticks.leftStick.verticalPosition = cmd.stickUp
            val now = System.currentTimeMillis()
            flightLog?.line(String.format(Locale.US,
                "step dt_ms=%d n=%.2f e=%.2f u=%.2f dist=%.2f heading=%.1f stick_fwd=%d stick_right=%d stick_up=%d " +
                    "vn=%.2f ve=%.2f vd=%.2f updates=%d cross=%.2f speed=%.2f",
                now - lastStepMs, n, e, u, cmd.dist, h, cmd.stickForward, cmd.stickRight, cmd.stickUp,
                v?.x ?: Double.NaN, v?.y ?: Double.NaN, v?.z ?: Double.NaN, tracker.updates, cmd.cross, cmd.speed))
            lastStepMs = now
            Thread.sleep(CONTROL_PERIOD_MS)
        }
    } finally {
        zeroSticks()
    }
}

/**
 * Centres the sticks at the point and waits until the drone stands still, at most
 * [LEG_STOP_MS]. The flight log gets how far from the point it came to rest.
 */
private fun Drone.stopAt(tracker: Tracker, north: Double, east: Double, up: Double?) {
    zeroSticks()
    val startMs = System.currentTimeMillis()
    while (System.currentTimeMillis() - startMs < LEG_STOP_MS) {
        checkAbort()
        val v = tracker.velocity() ?: break
        if ((v.x ?: 0.0) == 0.0 && (v.y ?: 0.0) == 0.0 && (v.z ?: 0.0) == 0.0) break
        Thread.sleep(CONTROL_PERIOD_MS)
    }
    val (n, e, u) = tracker.position()
    val off = sqrt(sq(north - n) + sq(east - e) + if (up != null) sq(up - u) else 0.0)
    flightLog?.line(String.format(Locale.US, "stop ms=%d off=%.2f", System.currentTimeMillis() - startMs, off))
}

