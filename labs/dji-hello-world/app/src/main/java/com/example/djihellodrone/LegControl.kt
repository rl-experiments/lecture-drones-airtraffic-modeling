package com.example.djihellodrone

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Pure maths, no DJI SDK: everything here runs in a plain unit test (app/src/test).

internal const val LEG_MAX_MPS = 0.6         // top speed on a leg
internal const val LEG_MIN_MPS = 0.2         // slower, the drone reports 0 m/s and the tracker loses it
internal const val LEG_BRAKE_MPS2 = 0.5      // planned slow-down, so the drone stops at the point
// The drone answers the sticks ~0.4 s late (31 Oct logs), but steering on where it will be in
// 0.4 s over-predicts: in simulations of both a smooth and a dead-time lag, 0.2 s did best.
internal const val LEG_LAG_S = 0.2
internal const val LEG_LOOKAHEAD_M = 1.0     // aims this far ahead on the line: pulls it back onto it
internal const val LEG_ARRIVE_M = 0.05       // "reached the point", on the predicted position
// Stick per m/s on a leg, measured in the 31 Oct flights. Drone's general factors (55 and
// 130) made the legs about 1.7 times too fast: 0.6 m/s planned, up to 1.57 m/s flown.
internal const val LEG_STICK_PER_MPS_HORIZONTAL = 33.0
internal const val LEG_STICK_PER_MPS_VERTICAL = 100.0
internal const val HEIGHT_FIRM_BAND_M = 0.3  // a smaller height error gets its full correction

/** A stick value from a wanted deflection: rounded and kept within ±[Drone.MAX_STICK]. */
fun clampStick(value: Double): Int = value.roundToInt().coerceIn(-Drone.MAX_STICK, Drone.MAX_STICK)

/**
 * One leg: the line from ([fromNorth], [fromEast], [fromUp]) to ([north], [east]) and,
 * with [up], to that height; without [up] the line is level and the height plays no part.
 */
class Leg(
    val fromNorth: Double, val fromEast: Double, val fromUp: Double,
    val north: Double, val east: Double, val up: Double?,
    val firmHeight: Boolean = false,
) {
    internal val toUp = up ?: fromUp
    internal val ln = north - fromNorth
    internal val le = east - fromEast
    internal val lu = toUp - fromUp
    internal val len = sqrt(ln * ln + le * le + lu * lu)
}

/**
 * What one control step decided, or [arrived] (then everything is 0): the sticks for the
 * basic virtual sticks, and the same wish as velocities in m/s north, east and up for the
 * advanced mode, where the drone itself holds the velocity.
 */
data class LegCommand(
    val arrived: Boolean,
    val dist: Double,
    val stickForward: Int = 0,
    val stickRight: Int = 0,
    val stickUp: Int = 0,
    val cross: Double = 0.0,
    val speed: Double = 0.0,
    val velocityNorth: Double = 0.0,
    val velocityEast: Double = 0.0,
    val velocityUp: Double = 0.0,
)

/**
 * One control step of [leg], from the tracked position ([n], [e], [u]), the velocity
 * ([vn], [ve], [vd], z pointing down) and the heading in degrees.
 *
 * - It steers towards a point [LEG_LOOKAHEAD_M] ahead on the line, so a drone pushed off
 *   the line flies back onto it instead of taking a new straight line to the point.
 * - It steers and brakes on where the drone will be in [LEG_LAG_S], since it answers late.
 * - Its speed falls with the distance left, so it reaches the point slowly.
 *
 * With [Leg.firmHeight] a small height error gets its full correction even at low speed,
 * where the line's share of the stick would be too small to move the drone.
 */
fun legStep(
    leg: Leg, n: Double, e: Double, u: Double,
    vn: Double, ve: Double, vd: Double, headingDeg: Double,
): LegCommand = with(leg) {
    // Where the drone will be when these sticks take effect.
    val pn = n + vn * LEG_LAG_S
    val pe = e + ve * LEG_LAG_S
    val pu = if (up != null) u - vd * LEG_LAG_S else fromUp
    val dist = sqrt(sq(north - pn) + sq(east - pe) + sq(toUp - pu))
    if (dist < LEG_ARRIVE_M) return LegCommand(arrived = true, dist = dist)

    // How far along the line the drone is, how far off it, and the point it aims at.
    val along = if (len > 1e-6) ((pn - fromNorth) * ln + (pe - fromEast) * le + (pu - fromUp) * lu) / len else len
    val near = if (len > 1e-6) along.coerceIn(0.0, len) / len else 1.0
    val cross = sqrt(sq(fromNorth + ln * near - pn) + sq(fromEast + le * near - pe) + sq(fromUp + lu * near - pu))
    val aim = if (len > 1e-6) (along.coerceAtLeast(0.0) + LEG_LOOKAHEAD_M).coerceAtMost(len) / len else 1.0
    val dn = fromNorth + ln * aim - pn
    val de = fromEast + le * aim - pe
    val du = fromUp + lu * aim - pu
    // Slow enough to stop at the point, never so slow that the drone creeps unseen.
    val speed = sqrt(2 * LEG_BRAKE_MPS2 * dist).coerceIn(LEG_MIN_MPS, LEG_MAX_MPS)
    val scale = speed / sqrt(dn * dn + de * de + du * du).coerceAtLeast(1e-6)

    // North/east to the drone's own forward/right.
    val r = Math.toRadians(headingDeg)
    val forward = (dn * cos(r) + de * sin(r)) * scale
    val right = (-dn * sin(r) + de * cos(r)) * scale
    val heightError = fromUp + lu * near - pu
    val climb = when {
        up == null -> 0.0
        firmHeight && abs(heightError) < HEIGHT_FIRM_BAND_M &&
            abs(du * scale) < abs(heightError * Drone.APPROACH_GAIN) -> heightError * Drone.APPROACH_GAIN
        else -> du * scale
    }
    LegCommand(
        arrived = false, dist = dist,
        stickForward = clampStick(forward * LEG_STICK_PER_MPS_HORIZONTAL),
        stickRight = clampStick(right * LEG_STICK_PER_MPS_HORIZONTAL),
        stickUp = clampStick(climb * LEG_STICK_PER_MPS_VERTICAL),
        cross = cross, speed = speed,
        velocityNorth = dn * scale, velocityEast = de * scale, velocityUp = climb,
    )
}

// The advanced mode's axes are not checked on a flight yet. If the drone flies another way
// than asked for this long, something is mapped wrong and the mission has to stop.
internal const val DIRECTION_CHECK_MIN_MPS = 0.25   // only judge a clear wish…
internal const val DIRECTION_CHECK_SEEN_MPS = 0.2   // …and a clear movement
internal const val DIRECTION_CHECK_STEPS = 8        // control steps in a row, past the ~0.5 s dead time

/**
 * False when the drone clearly moves another way than wished: horizontally more than
 * about 70° off ([wishN], [wishE] against [vn], [ve]), or vertically the wrong way
 * ([wishUp] against [vd], which points down). True otherwise, also when too slow to judge.
 */
fun movesAsWished(wishN: Double, wishE: Double, wishUp: Double, vn: Double, ve: Double, vd: Double): Boolean {
    val wish = sqrt(wishN * wishN + wishE * wishE)
    val seen = sqrt(vn * vn + ve * ve)
    if (wish >= DIRECTION_CHECK_MIN_MPS && seen >= DIRECTION_CHECK_SEEN_MPS &&
        (wishN * vn + wishE * ve) / (wish * seen) < 0.35) return false
    if (abs(wishUp) >= DIRECTION_CHECK_MIN_MPS && abs(vd) >= DIRECTION_CHECK_SEEN_MPS && wishUp * -vd < 0) return false
    return true
}

/**
 * Position from velocity added up over time: metres north, east and up from where it
 * started. The last velocity holds until the next one, since the drone only reports changes.
 * Times are in nanoseconds and passed in, so a test can play back any timeline.
 */
class DeadReckoning(startUp: Double = 0.0) {
    var north = 0.0; private set
    var east = 0.0; private set
    var up = startUp; private set
    private var vn = 0.0
    private var ve = 0.0
    private var vd = 0.0
    private var lastNs: Long? = null

    /** A new velocity ([vd] points down) reported at [nowNs]. */
    fun add(vn: Double, ve: Double, vd: Double, nowNs: Long) {
        advance(nowNs)
        this.vn = vn; this.ve = ve; this.vd = vd
        lastNs = nowNs
    }

    /** Brings the position up to [nowNs]. */
    fun advance(nowNs: Long) {
        val last = lastNs ?: return
        val dt = (nowNs - last) / 1e9
        north += vn * dt
        east += ve * dt
        up -= vd * dt
        lastNs = nowNs
    }

    fun shift(dn: Double, de: Double, du: Double) {
        north += dn; east += de; up += du
    }

    /** Moves the height a share [gain] of the way to [measured]. */
    fun correctUp(measured: Double, gain: Double) {
        up += (measured - up) * gain
    }
}

internal fun sq(x: Double) = x * x
