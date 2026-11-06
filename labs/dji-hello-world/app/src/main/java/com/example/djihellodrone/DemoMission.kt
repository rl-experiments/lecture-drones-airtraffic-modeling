package com.example.djihellodrone

import com.example.djihellodrone.Drone.Companion.TAG
import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Mission 1 (demo): take off, fly ~1.5 m forward, rotate 180°, fly ~1.5 m back, land.
 *
 * Both legs steer on the [Tracker]: the drone's own velocity, added up from the take-off
 * spot. That works on GPS outdoors and on the vision sensors indoors. The way back aims at
 * the take-off spot, so errors in the turn do not add up. The 180° turn is closed-loop
 * against the compass heading.
 */
class DemoMission(private val drone: Drone) : Mission {

    private companion object {
        const val LEG_M = 1.5
        const val YAW_STICK = 200           // ~30 % rotation stick
        const val YAW_TOLERANCE_DEG = 12.0
        const val YAW_TIMEOUT_MS = 15_000L
    }

    override val name = "Mission 1"
    override val hoverOnFailure = false
    override val briefing = "The drone will TAKE OFF, fly ~1.5 m forward, turn 180°, fly ~1.5 m back and land.\n\n" +
        "Make sure you have ~4 m of clear space and GPS signal. Keep hands off the RC sticks (moving them takes control back)."

    override fun run() = with(drone) {
        waitForAircraft()

        step("Checking GPS…")
        val sats = pull(FlightControllerKey.KeyGPSSatelliteCount) ?: -1
        Log.i(TAG, "GPS satellites: $sats")
        // Low/zero GPS (e.g. indoors): the Mini holds position on its downward vision
        // sensors, exactly like DJI Fly does. Proceed, but warn — expect some drift.
        if (sats in 0..5) {
            step("Low GPS ($sats sat). Flying on vision positioning — keep it well-lit, hands ready on the sticks.")
            sleep(3_000)
        }

        takeOff()
        takeSticks()

        // From here on, (0, 0) is the spot above the take-off point.
        val tracker = Tracker()
        tracker.start()
        try {
            sleep(1_000)
            val start = heading()
            if (tracker.updates == 0 || start == null) {
                throw IllegalStateException("No velocity or heading from the drone: cannot fly the legs.")
            }
            val h = Math.toRadians(start)
            step("Flying $LEG_M m forward…")
            driveTo(tracker, LEG_M * cos(h), LEG_M * sin(h))
            sleep(1_500)   // brake & settle

            step("Rotating 180°…")
            rotate180()
            sleep(1_500)

            step("Flying back to the take-off spot…")
            driveTo(tracker, 0.0, 0.0)
            sleep(1_000)
            val (n, e) = tracker.position()
            log(String.format(Locale.US, "Back above the take-off spot, %.2f m off by the drone's own count", hypot(n, e)))
        } finally {
            tracker.stop()
        }
        land()
    }

    /** Closed-loop yaw: rotates until the compass heading has moved ~180° from the start. */
    private fun rotate180() = with(drone) {
        val start = heading() ?: throw IllegalStateException("No heading from the drone: cannot turn.")
        val target = normalizeDeg(start + 180.0)
        sticks.leftStick.horizontalPosition = YAW_STICK
        val deadline = System.currentTimeMillis() + YAW_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                checkAbort()
                val h = heading()
                if (h != null && abs(normalizeDeg(h - target)) < YAW_TOLERANCE_DEG) break
                Thread.sleep(50)
            }
        } finally {
            zeroSticks()
        }
    }

    private fun normalizeDeg(a: Double): Double {
        var d = a % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
