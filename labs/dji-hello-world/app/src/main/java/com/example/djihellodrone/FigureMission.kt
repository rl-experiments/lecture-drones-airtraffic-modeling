package com.example.djihellodrone

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.manager.aircraft.perception.data.ObstacleData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The figures of mission 3: [lines] lines close the figure, each jumping [stepDeg] round the circle. */
enum class Figure(val label: String, val lines: Int, val stepDeg: Double) {
    STAR("star", 5, 144.0),         // each line jumps two tips on
    TRIANGLE("triangle", 3, 120.0),
}

/**
 * Mission 3: takes off, draws a star or a triangle with its tips on a circle of
 * [FIGURE_DIAMETER_M] and lands. Steered on the [Tracker] like mission 1, so it flies
 * indoors and outdoors; the lights are on while the figure is drawn. [Plane.UPRIGHT]
 * stands above the take-off spot across the nose, its lowest tips at [FIGURE_BOTTOM_ALT_M],
 * so a camera in front of the nose sees it whole. [Plane.FLAT] lies at [FIGURE_FLAT_ALT_M]
 * centred above the take-off spot, one tip ahead of the nose. With [heightCorrection]
 * the height follows the downward sensor, which needs a flat floor.
 */
class FigureMission(
    private val drone: Drone,
    private val figure: Figure,
    private val plane: Plane,
    private val heightCorrection: Boolean,
) : Mission {

    private companion object {
        // Upright, the star's top is at bottom + 1.81 × radius: 3.0 m, under the 5 m allowed.
        const val FIGURE_DIAMETER_M = 2.0   // the circle the tips lie on
        const val FIGURE_BOTTOM_ALT_M = 1.2 // upright: the lowest tips, about take-off height
        const val FIGURE_FLAT_ALT_M = 1.2   // flat: the whole figure, at take-off height
        const val LIGHT_SWITCH_MS = 300L    // hover while the lights switch

        // Height correction: the height comes from the downward sensor, so it needs a flat floor.
        const val HEIGHT_TIME_CONSTANT_S = 1.0  // how fast the tracked height follows the sensor
        const val HEIGHT_MAX_JUMP_M = 0.4       // a bigger step is a table or a stair, not the drone
        const val HEIGHT_MAX_SENSOR_M = 30.0    // readings above this mean "nothing measured"
    }

    override val name = "Mission 3"
    override val hoverOnFailure = false

    override val briefing: String get() {
        val r = FIGURE_DIAMETER_M / 2
        val shape = figure.label
        val size = "${FIGURE_DIAMETER_M.roundToInt()} m $shape"
        val text = StringBuilder("INDOORS OR OUTDOORS: without GPS the drone holds position on its downward cameras, " +
            "so indoors keep the room well lit and the floor textured.\n\n")
        when (plane) {
            Plane.UPRIGHT -> {
                val top = FIGURE_BOTTOM_ALT_M + r * cos(Math.PI / figure.lines) + r
                val side = (0 until figure.lines).maxOf { k -> abs(r * sin(Math.toRadians(figure.stepDeg * k))) }
                text.append("The drone will TAKE OFF and draw a $size standing upright above the take-off spot, " +
                    "up to ${"%.1f".format(Locale.US, top)} m high. Then it flies back down and lands.\n\n" +
                    "Place the drone with its nose pointing at the camera. The $shape spreads about ${"%.1f".format(Locale.US, side)} m " +
                    "to each side, and the drone can overshoot its tips: keep that clear, plus room to spare, and nothing overhead. ")
            }
            Plane.FLAT -> text.append("The drone will TAKE OFF and draw a $size lying flat " +
                "at about ${"%.1f".format(Locale.US, FIGURE_FLAT_ALT_M)} m, centred above the take-off spot. Then it flies back and lands.\n\n" +
                "The $shape spreads ${"%.0f".format(Locale.US, r)} m in every direction, and the drone can overshoot its tips: " +
                "keep that clear, plus room to spare. Film it from above or below. ")
        }
        if (heightCorrection) {
            text.append("With height correction, the drone holds its height on the downward sensor: " +
                "fly over a flat floor, with no tables or stairs below. ")
        }
        text.append("Stay out of the way at this height. Keep hands off the RC sticks (moving them takes control back).")
        return text.toString()
    }

    override fun run() = with(drone) {
        val shape = figure.label
        val planeName = plane.name.lowercase(Locale.US)
        val correction = if (heightCorrection) "on" else "off"
        log("Figure: $shape, $planeName, height correction $correction")
        flightLog?.line("figure $shape $planeName")
        flightLog?.line("height_correction $correction")

        waitForAircraft()
        takeOff()
        takeSticks()

        // Height above the take-off spot, from the barometer; the tracker carries it on.
        val tracker = Tracker(pull(FlightControllerKey.KeyAltitude) ?: FIGURE_BOTTOM_ALT_M)
        tracker.start()
        val sensor = if (heightCorrection) HeightCorrection(tracker).also { onObstacle = it::onReading } else null
        try {
            sleep(1_000)
            val start = heading()
            if (tracker.updates == 0 || start == null) {
                throw IllegalStateException("No velocity or heading from the drone: cannot draw the $shape.")
            }
            val tips = tips(Math.toRadians(start))
            fun flyTo(p: Triple<Double, Double, Double>) = driveTo(tracker, p.first, p.second, p.third, heightCorrection)

            step("Flying to the first point of the $shape…")
            setLights(false)
            flyTo(tips[0])
            sleep(1_000)

            setLights(true)
            sleep(LIGHT_SWITCH_MS)
            for (k in 1..figure.lines) {
                step("Drawing the $shape, line $k of ${figure.lines}…")
                flyTo(tips[k])
            }
            setLights(false)
            sleep(LIGHT_SWITCH_MS)

            step("Flying back to the take-off spot…")
            val endAlt = if (plane == Plane.FLAT) FIGURE_FLAT_ALT_M else FIGURE_BOTTOM_ALT_M
            flyTo(Triple(0.0, 0.0, endAlt))
            sleep(1_000)
            val (n, e) = tracker.position()
            log(String.format(Locale.US, "Back above the take-off spot, %.2f m off by the drone's own count", hypot(n, e)))
        } finally {
            if (sensor != null) {
                onObstacle = null
                log("Height sensor readings: ${sensor.accepted} used, ${sensor.rejected} dropped")
            }
            tracker.stop()
            restoreLights()
        }
        land()
    }

    /** North, east and height of each tip, from the first back to the first, for the start heading [h] in radians. */
    private fun tips(h: Double): List<Triple<Double, Double, Double>> {
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

    /**
     * Pulls the tracked height towards the downward sensor. The tracker misses slow climbs,
     * because the drone reports speeds under ~0.1 m/s as zero. A reading that jumps away
     * from the last good one is dropped until the sensor comes back to it.
     */
    private inner class HeightCorrection(private val tracker: Tracker) {
        private var lastM = Double.NaN
        private var lastNs = 0L
        @Volatile var accepted = 0
            private set
        @Volatile var rejected = 0
            private set

        fun onReading(data: ObstacleData) {
            val measured = data.downwardObstacleDistance / 1000.0
            if (measured <= 0.0 || measured >= HEIGHT_MAX_SENSOR_M) return
            val reference = if (lastM.isNaN()) tracker.position().third else lastM
            if (abs(measured - reference) > HEIGHT_MAX_JUMP_M) {
                rejected++
                drone.flightLog?.line(String.format(Locale.US, "height rejected sensor=%.2f reference=%.2f", measured, reference))
                return
            }
            val now = System.nanoTime()
            if (lastNs != 0L) {
                val dt = (now - lastNs) / 1e9
                val before = tracker.position().third
                val after = tracker.correctUp(measured, 1 - exp(-dt / HEIGHT_TIME_CONSTANT_S))
                drone.flightLog?.line(String.format(Locale.US, "height sensor=%.2f before=%.2f after=%.2f", measured, before, after))
            }
            lastM = measured
            lastNs = now
            accepted++
        }
    }
}
