package com.example.djihellodrone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/**
 * Mission 3's star, flown against [FakeDrone] the way FigureMission flies it: to the first tip,
 * a second's pause, then the five lines, each from the planned tip before.
 *
 * Measured on where the fake drone really was, not on what the app believed. The bounds are a
 * regression guard set just above today's result (20 Nov 2026): a change that makes the simulated
 * star worse fails here before it flies. Lower them when a change makes it better. They are not
 * a promise about the real drone.
 *
 * Today's result is poor: about 0.3 m off the lines on average, 0.65 m at the worst tip.
 * [whatCostsTheMost] shows why: almost all of it comes from the 0.1 m/s velocity steps, near the
 * tips the drone creeps slower than that, reports 0, and the app loses track of where it is.
 */
class StarSimulationTest {

    data class Quality(val meanOffM: Double, val maxOffM: Double, val worstTipM: Double, val driftM: Double, val gaveUp: Int)

    private fun fly(plane: Plane, drone: FakeDrone = FakeDrone(), name: String = ""): Quality {
        val tips = figureTips(Figure.STAR, plane, Math.toRadians(FakeFlight.HEADING_DEG))
        val flight = FakeFlight(drone)
        flight.hold(1.0)
        flight.flyTo(tips[0])
        flight.hold(1.0)
        val offs = ArrayList<Double>()
        var worstTip = 0.0
        for (k in 1..Figure.STAR.lines) {
            val from = flight.track.size
            flight.flyTo(tips[k], from = tips[k - 1])
            for (p in flight.track.subList(from, flight.track.size)) offs.add(toSegment(p, tips[k - 1], tips[k]))
            worstTip = maxOf(worstTip, dist(flight.track.last(), tips[k]))
        }
        val d = flight.drone
        val drift = dist(flight.position(), Triple(d.north, d.east, d.up))
        return Quality(offs.average(), offs.max(), worstTip, drift, flight.gaveUp).also {
            println(String.format(Locale.US, "%s star%s: mean off line %.3f m, max %.3f m, worst tip %.3f m, " +
                "app vs real at the end %.3f m, legs given up %d", plane, name, it.meanOffM, it.maxOffM, it.worstTipM, it.driftM, it.gaveUp))
        }
    }

    @Test fun uprightStarGetsNoWorse() {
        val q = fly(Plane.UPRIGHT)
        assertEquals("every line reached its tip", 0, q.gaveUp)
        assertTrue("mean ${q.meanOffM}", q.meanOffM < 0.40)
        assertTrue("max ${q.maxOffM}", q.maxOffM < 0.75)
        assertTrue("tip ${q.worstTipM}", q.worstTipM < 0.75)
    }

    @Test fun flatStarGetsNoWorse() {
        val q = fly(Plane.FLAT)
        assertEquals("every line reached its tip", 0, q.gaveUp)
        assertTrue("mean ${q.meanOffM}", q.meanOffM < 0.40)
        assertTrue("max ${q.maxOffM}", q.maxOffM < 0.75)
        assertTrue("tip ${q.worstTipM}", q.worstTipM < 0.75)
    }

    /** Not a check: prints what each of the drone's habits costs the star. */
    @Test fun whatCostsTheMost() {
        fly(Plane.UPRIGHT, FakeDrone(coarseReports = false, deadTimeScale = 0.0), " ideal drone")
        fly(Plane.UPRIGHT, FakeDrone(coarseReports = false), " only dead time")
        fly(Plane.UPRIGHT, FakeDrone(deadTimeScale = 0.0), " only 0.1 m/s steps")
    }

    @Test fun theFakeReportsACreepAsStandingStill() {
        val drone = FakeDrone()
        drone.setSticks(0, 0, 8)            // ~0.07 m/s up
        drone.advance(3.0)
        assertTrue("it climbed ${drone.up - FIGURE_BOTTOM_ALT_M} m", drone.up - FIGURE_BOTTOM_ALT_M > 0.1)
        assertTrue("but reports nothing", drone.isStill)
    }

    @Test fun theFakeAnswersOnlyAfterItsDeadTime() {
        val drone = FakeDrone()
        drone.setSticks(30, 0, 0)
        drone.advance(FakeDrone.D[0] - 0.05)
        assertEquals(0.0, drone.north, 1e-9)
        drone.advance(1.0)
        assertTrue(drone.north > 0.3)
    }

    private fun dist(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>) =
        sqrt(sq(a.first - b.first) + sq(a.second - b.second) + sq(a.third - b.third))

    private fun toSegment(p: Triple<Double, Double, Double>, a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        val ab = Triple(b.first - a.first, b.second - a.second, b.third - a.third)
        val len2 = sq(ab.first) + sq(ab.second) + sq(ab.third)
        val s = (((p.first - a.first) * ab.first + (p.second - a.second) * ab.second + (p.third - a.third) * ab.third) / len2)
            .coerceIn(0.0, 1.0)
        return dist(p, Triple(a.first + ab.first * s, a.second + ab.second * s, a.third + ab.third * s))
    }
}
