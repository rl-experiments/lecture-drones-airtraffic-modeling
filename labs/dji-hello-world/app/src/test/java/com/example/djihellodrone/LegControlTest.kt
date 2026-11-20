package com.example.djihellodrone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** The control law of one leg, without phone or drone: position and velocity in, sticks out. */
class LegControlTest {

    private val northLeg = Leg(0.0, 0.0, 1.0, north = 4.0, east = 0.0, up = null)

    @Test fun farFromThePointItFliesAtTopSpeedStraightAhead() {
        val cmd = legStep(northLeg, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, headingDeg = 0.0)
        assertFalse(cmd.arrived)
        assertEquals(clampStick(LEG_MAX_MPS * LEG_STICK_PER_MPS_HORIZONTAL), cmd.stickForward)
        assertEquals(0, cmd.stickRight)
        assertEquals(0, cmd.stickUp)
    }

    @Test fun northIsToTheLeftWhenTheNosePointsEast() {
        val cmd = legStep(northLeg, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, headingDeg = 90.0)
        assertEquals(0, cmd.stickForward)
        assertEquals(-clampStick(LEG_MAX_MPS * LEG_STICK_PER_MPS_HORIZONTAL), cmd.stickRight)
    }

    @Test fun itSlowsDownNearThePoint() {
        val far = legStep(northLeg, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        val near = legStep(northLeg, 3.8, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        assertTrue(near.stickForward < far.stickForward)
        assertTrue(near.stickForward > 0)
    }

    @Test fun itArrivesOnWhereTheDroneWillBeNotWhereItIs() {
        // 10 cm short, but at 0.5 m/s it is there by the time the sticks act.
        val cmd = legStep(northLeg, 3.9, 0.0, 1.0, 0.5, 0.0, 0.0, 0.0)
        assertTrue(cmd.arrived)
        assertEquals(0, cmd.stickForward)
    }

    @Test fun pushedOffTheLineItSteersBackOntoIt() {
        val cmd = legStep(northLeg, 2.0, 0.5, 1.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0.5, cmd.cross, 1e-9)
        assertTrue("still forward", cmd.stickForward > 0)
        assertTrue("back to the west", cmd.stickRight < 0)
    }

    @Test fun aLevelLegLeavesTheHeightAlone() {
        val cmd = legStep(northLeg, 1.0, 0.0, 3.0, 0.0, 0.0, -1.0, 0.0)
        assertEquals(0, cmd.stickUp)
    }

    @Test fun aClimbOnlyLegUsesTheLeftStick() {
        val climb = Leg(0.0, 0.0, 1.0, north = 0.0, east = 0.0, up = 2.0)
        val cmd = legStep(climb, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0, cmd.stickForward)
        assertEquals(clampStick(LEG_MAX_MPS * LEG_STICK_PER_MPS_VERTICAL), cmd.stickUp)
    }

    @Test fun sticksNeverExceedTheLimit() {
        assertEquals(Drone.MAX_STICK, clampStick(10_000.0))
        assertEquals(-Drone.MAX_STICK, clampStick(-10_000.0))
    }

    /**
     * Closed loop against a fake drone: it answers the sticks with a lag, like the real one
     * (~0.4 s in the 31 Oct logs). Not a proof the real drone does this, but every change
     * to the control law has to keep this test green before it flies.
     */
    @Test fun aLaggingFakeDroneReachesThePointAndStopsNearIt() {
        val dt = Drone.CONTROL_PERIOD_MS / 1000.0
        val lagS = 0.4
        var n = 0.0; var e = 0.0; var vn = 0.0; var ve = 0.0
        var t = 0.0
        var arrived = false
        while (t < 15.0) {
            val cmd = legStep(northLeg, n, e, 1.0, vn, ve, 0.0, 0.0)
            if (cmd.arrived) { arrived = true; break }
            val wantN = cmd.stickForward / LEG_STICK_PER_MPS_HORIZONTAL
            val wantE = cmd.stickRight / LEG_STICK_PER_MPS_HORIZONTAL
            vn += (wantN - vn) * dt / lagS; ve += (wantE - ve) * dt / lagS
            n += vn * dt; e += ve * dt; t += dt
        }
        assertTrue("arrived within the 15 s leg timeout, t=$t", arrived)
        // Sticks centred: the drone coasts to a stop on its lag.
        repeat(50) { vn -= vn * dt / lagS; ve -= ve * dt / lagS; n += vn * dt; e += ve * dt }
        val off = hypot(4.0 - n, e)
        assertTrue("came to rest $off m from the point", off < 0.1)
        assertTrue("no sideways wander", abs(e) < 0.01)
    }
}

class DeadReckoningTest {
    private val s = 1_000_000_000L

    @Test fun nothingMovesBeforeTheFirstVelocity() {
        val r = DeadReckoning(startUp = 1.0)
        r.advance(5 * s)
        assertEquals(0.0, r.north, 0.0)
        assertEquals(1.0, r.up, 0.0)
    }

    @Test fun theLastVelocityHoldsUntilTheNext() {
        val r = DeadReckoning()
        r.add(1.0, 0.0, 0.0, 0)
        r.add(0.0, 1.0, -0.5, 2 * s)   // z points down: -0.5 is climbing
        r.advance(4 * s)
        assertEquals(2.0, r.north, 1e-9)
        assertEquals(2.0, r.east, 1e-9)
        assertEquals(1.0, r.up, 1e-9)
    }

    @Test fun aCorrectionMovesPartOfTheWay() {
        val r = DeadReckoning(startUp = 1.0)
        r.correctUp(measured = 2.0, gain = 0.25)
        assertEquals(1.25, r.up, 1e-9)
    }
}
