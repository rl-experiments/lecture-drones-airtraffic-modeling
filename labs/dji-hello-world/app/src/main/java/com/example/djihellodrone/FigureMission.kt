package com.example.djihellodrone

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.flightassistant.AuxiliaryLightMode
import dji.v5.manager.aircraft.perception.data.ObstacleData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** The figures of mission 3: [lines] lines close the figure, each jumping [stepDeg] round the circle. */
enum class Figure(val label: String, val lines: Int, val stepDeg: Double) {
    STAR("star", 5, 144.0),         // each line jumps two tips on
    TRIANGLE("triangle", 3, 120.0),
}

/**
 * Mission 3: takes off, draws a star or a triangle with its tips on a circle of
 * [diameter] metres and lands. Steered on the [Tracker] like mission 1, so it flies
 * indoors and outdoors; the lights are on while the figure is drawn. [Plane.UPRIGHT]
 * stands above the take-off spot across the nose, its lowest tips at [FIGURE_BOTTOM_ALT_M],
 * so a camera in front of the nose sees it whole. [Plane.FLAT] lies at [FIGURE_FLAT_ALT_M]
 * centred above the take-off spot, one tip ahead of the nose. With [heightCorrection]
 * the height follows the downward sensor, which needs a flat floor. With [videoFix] a
 * second phone films the figure from the front and sends where the light really is; the
 * tracked position follows it while the lights are on, which takes out the drift the
 * tracker cannot see. Upright only: from the front a flat figure is seen edge-on. With
 * [advancedSticks] it steers in the advanced virtual-stick mode: velocities in m/s that the
 * drone holds itself, instead of stick positions.
 */
class FigureMission(
    private val drone: Drone,
    private val figure: Figure,
    private val plane: Plane,
    private val heightCorrection: Boolean,
    private val videoFix: VideoFix? = null,
    private val advancedSticks: Boolean = false,
    private val diameter: Double = FIGURE_DIAMETER_M,
) : Mission {

    private companion object {
        const val LIGHT_SWITCH_MS = 300L    // hover while the lights switch

        // Height correction: the height comes from the downward sensor, so it needs a flat floor.
        const val HEIGHT_TIME_CONSTANT_S = 1.0  // how fast the tracked height follows the sensor
        const val HEIGHT_MAX_JUMP_M = 0.4       // a bigger step is a table or a stair, not the drone
        const val HEIGHT_MAX_SENSOR_M = 30.0    // readings above this mean "nothing measured"

        // Video correction: positions from a second phone that films the figure ([VideoFix]).
        const val VIDEO_CHECK_MS = 2_000L        // before take-off, listen this long…
        const val VIDEO_MIN_PER_S = 10           // …and want at least this many lines a second
        const val VIDEO_DELAY_MS = 200L          // a position describes the drone this long ago (tune from the logs)
        const val VIDEO_TIME_CONSTANT_S = 1.0    // how fast the tracked position follows the video
        const val VIDEO_MAX_JUMP_M = 0.3         // a bigger jump between frames is a reflection, not the drone…
        const val VIDEO_MAX_MPS = 2.0            // …on top of what the drone can fly between them
        const val VIDEO_MAX_REJECTS = 10         // this many jumps in a row: the light really is there
        const val VIDEO_SETTLE_MS = 2_000L       // lights on at the first tip: let the video correct first
        const val VIDEO_HISTORY_MS = 2_000L
    }

    override val name = "Mission 3"
    override val hoverOnFailure = false

    override val briefing: String get() {
        val r = diameter / 2
        val shape = figure.label
        val size = "${"%.1f".format(Locale.US, diameter)} m $shape"
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
                "The $shape spreads ${"%.1f".format(Locale.US, r)} m in every direction, and the drone can overshoot its tips: " +
                "keep that clear, plus room to spare. Film it from above or below. ")
        }
        if (heightCorrection) {
            text.append("With height correction, the drone holds its height on the downward sensor: " +
                "fly over a flat floor, with no tables or stairs below. ")
        }
        if (videoFix != null && diameter != FIGURE_DIAMETER_M) {
            text.append("VIDEO CORRECTION WORKS ONLY AT ${"%.0f".format(Locale.US, FIGURE_DIAMETER_M)} M: the video app expects the tips marked for that size. " +
                "Switch it off or choose ${"%.0f".format(Locale.US, FIGURE_DIAMETER_M)} m. ")
        } else if (videoFix != null && plane == Plane.FLAT) {
            text.append("VIDEO CORRECTION WORKS ONLY UPRIGHT: from the front the phone sees a flat $shape edge-on. " +
                "Switch it off or choose upright. ")
        } else if (videoFix != null) {
            text.append("With video correction, a second phone on a tripod films the $shape and sends the drone's position: " +
                "it must be on this phone's hotspot with the video app running, or the drone will not take off. " +
                "At the first tip the drone hovers lit for ${VIDEO_SETTLE_MS / 1000} s while the video corrects it. ")
        }
        if (advancedSticks) {
            text.append("With advanced steering, the app asks for speeds in m/s instead of stick positions. " +
                "If the drone flies another way than asked, it stops and lands. ")
        }
        text.append("Stay out of the way at this height. Keep hands off the RC sticks (moving them takes control back).")
        return text.toString()
    }

    override fun run() = with(drone) {
        val shape = figure.label
        val planeName = plane.name.lowercase(Locale.US)
        val correction = if (heightCorrection) "on" else "off"
        log("Figure: $shape, $planeName, ${"%.1f".format(Locale.US, diameter)} m, height correction $correction")
        flightLog?.line("figure $shape $planeName")
        flightLog?.line(String.format(Locale.US, "diameter %.2f", diameter))
        flightLog?.line("height_correction $correction")
        flightLog?.line("video_correction ${if (videoFix != null) "on" else "off"}")
        flightLog?.line("steering ${if (advancedSticks) "advanced" else "sticks"}")

        if (videoFix != null && plane == Plane.FLAT) {
            throw IllegalStateException("Video correction works only upright: from the front the phone sees a flat figure edge-on.")
        }
        if (videoFix != null && diameter != FIGURE_DIAMETER_M) {
            throw IllegalStateException("Video correction works only at ${"%.0f".format(Locale.US, FIGURE_DIAMETER_M)} m: " +
                "the video app expects the tips marked for that size.")
        }
        waitForAircraft()
        if (videoFix != null) checkVideo(videoFix)
        try {
            fly()
        } finally {
            videoFix?.stop()
        }
    }

    /** Before take-off: the video app must be sending, or the correction would silently be missing. */
    private fun checkVideo(video: VideoFix) = with(drone) {
        step("Listening for the video app…")
        video.start()
        val before = video.received
        try {
            sleep(VIDEO_CHECK_MS)
        } catch (e: Exception) {
            video.stop()
            throw e
        }
        val perSecond = (video.received - before) * 1000 / VIDEO_CHECK_MS
        flightLog?.line("video check per_s=$perSecond malformed=${video.malformed}")
        if (perSecond < VIDEO_MIN_PER_S) {
            video.stop()
            throw IllegalStateException("Video correction is on, but only $perSecond positions a second arrive " +
                "(need $VIDEO_MIN_PER_S). Put the second phone on this phone's hotspot and start the video app, " +
                "or fly with video correction off.")
        }
        log("Video app: $perSecond positions a second")
    }

    private fun fly() = with(drone) {
        val shape = figure.label
        takeOff()
        takeSticks()
        if (advancedSticks) useAdvancedSticks()

        // Height above the take-off spot, from the barometer; the tracker carries it on.
        val tracker = Tracker(pull(FlightControllerKey.KeyAltitude) ?: FIGURE_BOTTOM_ALT_M)
        tracker.start()
        val sensor = if (heightCorrection) HeightCorrection(tracker).also { onObstacle = it::onReading } else null
        var video: VideoCorrection? = null
        try {
            sleep(1_000)
            val start = heading()
            if (tracker.updates == 0 || start == null) {
                throw IllegalStateException("No velocity or heading from the drone: cannot draw the $shape.")
            }
            val tips = tips(Math.toRadians(start))
            if (videoFix != null) video = VideoCorrection(tracker, Math.toRadians(start)).also { videoFix.onFix = it::onFix }
            // [from]: the planned line starts at the planned tip, not where the drone ended up.
            fun flyTo(p: Triple<Double, Double, Double>, from: Triple<Double, Double, Double>? = null) =
                driveTo(tracker, p.first, p.second, p.third, heightCorrection, from)

            step("Flying to the first point of the $shape…")
            setLights(false)
            // The white light under the drone lights up the floor in a dim room: the video
            // would take that bright patch for the drone.
            if (video != null) setWhiteLight(AuxiliaryLightMode.OFF)
            flyTo(tips[0])
            sleep(1_000)

            setLights(true)
            sleep(LIGHT_SWITCH_MS)
            if (video != null) {
                // The camera sees the drone only from now on. Let it correct the position, then
                // fly the rest of the way to the first tip, so the figure starts where it should.
                step("Video correction: settling at the first point…")
                video.active = true
                sleep(VIDEO_SETTLE_MS)
                flyTo(tips[0])
            }
            for (k in 1..figure.lines) {
                step("Drawing the $shape, line $k of ${figure.lines}…")
                flyTo(tips[k], from = tips[k - 1])
            }
            video?.active = false
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
            if (video != null) {
                videoFix?.onFix = null
                log("Video positions: ${video.accepted} used, ${video.rejected} dropped, ${video.unseen} without the light")
            }
            tracker.stop()
            restoreLights()
        }
        land()
    }

    /** North, east and height of each tip, from the first back to the first, for the start heading [h] in radians. */
    private fun tips(h: Double) = figureTips(figure, plane, h, diameter)

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

    /**
     * Pulls the tracked position towards where the second phone sees the light ([VideoFix]).
     * The video is [VIDEO_DELAY_MS] old when it arrives, so it is compared with where the
     * tracker had the drone back then. The camera in front of the nose sees sideways and the
     * height, so it corrects those (the height not with [heightCorrection], which owns it);
     * the depth towards the camera stays as the tracker has it. A jump the drone cannot fly
     * between two frames is a reflection and is dropped.
     */
    private inner class VideoCorrection(private val tracker: Tracker, private val h: Double) {
        @Volatile var active = false
        @Volatile var accepted = 0
            private set
        @Volatile var rejected = 0
            private set
        @Volatile var unseen = 0
            private set
        private val history = ArrayDeque<Pair<Long, Triple<Double, Double, Double>>>()
        private var last: VideoFix.Fix? = null
        private var rejectsInARow = 0
        private var lastAppliedNs = 0L
        private val r = FIGURE_DIAMETER_M / 2
        private val centreAlt = FIGURE_BOTTOM_ALT_M + r * cos(Math.PI / figure.lines)

        /** North, east, up to the camera's x and y (see [VideoFix]): its right is the drone's left. */
        private fun toCamera(p: Triple<Double, Double, Double>): Pair<Double, Double> {
            val (n, e, u) = p
            val right = -n * sin(h) + e * cos(h)
            return Pair(-right, u - centreAlt)
        }

        /** Where the tracker had the drone at [ns], from the samples taken as positions came in. */
        private fun trackedAt(ns: Long): Triple<Double, Double, Double>? =
            history.minByOrNull { abs(it.first - ns) }?.second

        @Synchronized fun onFix(fix: VideoFix.Fix) {
            val now = fix.atNs
            history.addLast(now to tracker.position())
            while (history.isNotEmpty() && now - history.first().first > VIDEO_HISTORY_MS * 1_000_000) history.removeFirst()
            if (!active) return
            if (!fix.ok) { unseen++; return }
            val before = last
            if (before != null && before.ok) {
                val dt = (now - before.atNs) / 1e9
                if (hypot(fix.x - before.x, fix.y - before.y) > VIDEO_MAX_JUMP_M + VIDEO_MAX_MPS * dt &&
                    ++rejectsInARow < VIDEO_MAX_REJECTS) {
                    rejected++
                    drone.flightLog?.line(String.format(Locale.US, "video rejected seq=%d x=%.2f y=%.2f", fix.seq, fix.x, fix.y))
                    return   // keep the last good one, so a single reflection cannot drag the reference away
                }
            }
            rejectsInARow = 0
            last = fix
            val then = trackedAt(now - VIDEO_DELAY_MS * 1_000_000) ?: return
            val (tx, ty) = toCamera(then)
            val ex = fix.x - tx
            val ey = fix.y - ty
            val dt = if (lastAppliedNs == 0L) 0.0 else ((now - lastAppliedNs) / 1e9).coerceAtMost(0.2)
            lastAppliedNs = now
            val gain = 1 - exp(-dt / VIDEO_TIME_CONSTANT_S)
            // Camera x and y back to north, east, up.
            val dRight = -ex * gain
            val dn = -dRight * sin(h)
            val de = dRight * cos(h)
            val du = if (heightCorrection) 0.0 else ey * gain
            tracker.shift(dn, de, du)
            // The past positions move too, or the next frame would correct the same error again.
            for (i in history.indices) {
                val (t, p) = history[i]
                history[i] = t to Triple(p.first + dn, p.second + de, p.third + du)
            }
            accepted++
            drone.flightLog?.line(String.format(Locale.US,
                "video fix seq=%d x=%.2f y=%.2f tracked_x=%.2f tracked_y=%.2f err_x=%.2f err_y=%.2f gain=%.3f",
                fix.seq, fix.x, fix.y, tx, ty, ex, ey, gain))
        }
    }
}
