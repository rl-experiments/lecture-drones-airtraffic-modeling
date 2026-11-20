package com.example.djihellodrone

import kotlin.math.cos
import kotlin.math.sin

// Pure geometry, no DJI SDK: the unit tests fly these tips against a fake drone.

// Upright, the star's top is at bottom + 1.81 × radius: 3.0 m, under the 5 m allowed.
internal const val FIGURE_DIAMETER_M = 2.0   // the circle the tips lie on
internal const val FIGURE_BOTTOM_ALT_M = 1.2 // upright: the lowest tips, about take-off height
internal const val FIGURE_FLAT_ALT_M = 1.2   // flat: the whole figure, at take-off height

/**
 * The tips of [figure] in [plane] as north, east, up, in the order they are flown: the
 * first tip again at the end closes it. [h] is the heading in radians at the start.
 */
fun figureTips(figure: Figure, plane: Plane, h: Double): List<Triple<Double, Double, Double>> {
    val r = FIGURE_DIAMETER_M / 2
    val centreAlt = FIGURE_BOTTOM_ALT_M + r * cos(Math.PI / figure.lines)
    return (0..figure.lines).map { k ->
        val a = Math.toRadians(figure.stepDeg * k)
        when (plane) {
            // Sideways is the drone's right, so north/east = side × (-sin h, cos h).
            Plane.UPRIGHT -> {
                val side = r * sin(a)
                Triple(-side * sin(h), side * cos(h), centreAlt + r * cos(a))
            }
            Plane.FLAT -> Triple(r * cos(h + a), r * sin(h + a), FIGURE_FLAT_ALT_M)
        }
    }
}
