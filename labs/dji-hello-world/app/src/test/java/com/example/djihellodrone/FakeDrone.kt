package com.example.djihellodrone

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * A Mini 4 Pro made of maths, for the unit tests: it answers the virtual sticks the way the
 * real one did in the 31 Oct 2026 flights (fitted by tools/flight-analysis/fit_response.py,
 * 9 logs, ~1,900 control steps per axis, rms error 0.06-0.13 m/s).
 *
 * Per axis (forward, right, up) a stick value s moves the drone at K * s m/s, but only after a
 * dead time D, and then smoothly with the lag TAU. Like the real drone it reports its velocity
 * in 0.1 m/s steps cut towards zero, so a creep under 0.1 m/s is reported as 0 while it moves.
 *
 * It knows where it really is ([north], [east], [up]); the app only sees [reported].
 */
class FakeDrone(
    private val headingDeg: Double = 0.0,
    startUp: Double = FIGURE_BOTTOM_ALT_M,
    /** False: reports its exact velocity, to see what the 0.1 m/s steps cost. */
    private val coarseReports: Boolean = true,
    /** 0: answers at once, to see what the dead time costs. */
    private val deadTimeScale: Double = 1.0,
) {
    companion object {
        val K = doubleArrayOf(0.0290, 0.0248, 0.0082)   // m/s per stick unit: forward, right, up
        val D = doubleArrayOf(0.5, 0.5, 0.4)            // dead time, s
        val TAU = doubleArrayOf(0.15, 0.10, 0.10)       // lag, s
        const val REPORT_STEP_MPS = 0.1
        private const val SUB_STEP_S = 0.01
    }

    var north = 0.0; private set
    var east = 0.0; private set
    var up = startUp; private set
    var t = 0.0; private set
    private val v = DoubleArray(3)                      // true velocity: forward, right, up
    private val history = ArrayList<Pair<Double, IntArray>>().apply { add(0.0 to IntArray(3)) }

    fun setSticks(forward: Int, right: Int, climb: Int) {
        history.add(t to intArrayOf(forward, right, climb))
    }

    /** Flies on for [seconds] of simulated time. */
    fun advance(seconds: Double) {
        val end = t + seconds
        val h = Math.toRadians(headingDeg)
        while (t < end - 1e-9) {
            val dt = minOf(SUB_STEP_S, end - t)
            for (axis in 0..2) {
                val target = K[axis] * stickAt(t - D[axis] * deadTimeScale, axis)
                v[axis] = target + (v[axis] - target) * exp(-dt / TAU[axis])
            }
            north += (v[0] * cos(h) - v[1] * sin(h)) * dt
            east += (v[0] * sin(h) + v[1] * cos(h)) * dt
            up += v[2] * dt
            t += dt
        }
    }

    /** What the drone tells the app: north, east, down in 0.1 m/s steps, cut towards zero. */
    fun reported(): Triple<Double, Double, Double> {
        val h = Math.toRadians(headingDeg)
        return Triple(
            cut(v[0] * cos(h) - v[1] * sin(h)),
            cut(v[0] * sin(h) + v[1] * cos(h)),
            cut(-v[2]),
        )
    }

    val isStill: Boolean get() = reported().let { it.first == 0.0 && it.second == 0.0 && it.third == 0.0 }

    private fun stickAt(time: Double, axis: Int): Int {
        var s = 0
        for ((at, sticks) in history) if (at <= time) s = sticks[axis] else break
        return s
    }

    private fun cut(x: Double): Double {
        if (!coarseReports) return x
        val steps = floor(abs(x) / REPORT_STEP_MPS + 1e-9)
        return Math.copySign(steps * REPORT_STEP_MPS, x).let { if (steps == 0.0) 0.0 else it }
    }
}

/**
 * Flies legs the way Tracker.kt's driveTo and stopAt do, on simulated time: the app's
 * [DeadReckoning] on the drone's reported velocity, [legStep] every control period,
 * centred sticks and a wait for standstill at each point, a timeout per leg.
 */
class FakeFlight(val drone: FakeDrone) {
    private val reckoning = DeadReckoning(drone.up)
    private var lastReport = drone.reported()
    /** Where the drone really was, sampled every control period. */
    val track = ArrayList<Triple<Double, Double, Double>>()
    var gaveUp = 0; private set

    init { reckoning.add(0.0, 0.0, 0.0, 0) }

    fun hold(seconds: Double) {
        val end = drone.t + seconds
        while (drone.t < end - 1e-9) tick()
    }

    fun flyTo(target: Triple<Double, Double, Double>, from: Triple<Double, Double, Double>? = null) {
        val start = from ?: position()
        val leg = Leg(start.first, start.second, start.third, target.first, target.second, target.third)
        val deadline = drone.t + LEG_TIMEOUT_S
        while (true) {
            val (n, e, u) = position()
            val (vn, ve, vd) = lastReport
            val cmd = legStep(leg, n, e, u, vn, ve, vd, HEADING_DEG)
            if (cmd.arrived) {
                drone.setSticks(0, 0, 0)
                val stopEnd = drone.t + STOP_WAIT_S
                while (drone.t < stopEnd && !drone.isStill) tick()
                return
            }
            if (drone.t > deadline) {
                gaveUp++
                drone.setSticks(0, 0, 0)
                return
            }
            drone.setSticks(cmd.stickForward, cmd.stickRight, cmd.stickUp)
            tick()
        }
    }

    /** The app's idea of where the drone is. */
    fun position(): Triple<Double, Double, Double> {
        reckoning.advance(nanos())
        return Triple(reckoning.north, reckoning.east, reckoning.up)
    }

    private fun tick() {
        drone.advance(Drone.CONTROL_PERIOD_MS / 1000.0)
        val r = drone.reported()
        // The key only reports changes.
        if (r != lastReport) {
            reckoning.add(r.first, r.second, r.third, nanos())
            lastReport = r
        }
        track.add(Triple(drone.north, drone.east, drone.up))
    }

    private fun nanos() = (drone.t * 1e9).toLong()

    companion object {
        const val HEADING_DEG = 0.0
        const val LEG_TIMEOUT_S = 15.0   // as LEG_TIMEOUT_MS in Tracker.kt
        const val STOP_WAIT_S = 2.0      // as LEG_STOP_MS in Tracker.kt
    }
}
