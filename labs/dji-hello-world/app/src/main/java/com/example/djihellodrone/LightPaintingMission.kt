package com.example.djihellodrone

import com.example.djihellodrone.Drone.Companion.APPROACH_GAIN
import com.example.djihellodrone.Drone.Companion.CONTROL_PERIOD_MS
import com.example.djihellodrone.Drone.Companion.STICK_PER_MPS_HORIZONTAL
import com.example.djihellodrone.Drone.Companion.STICK_PER_MPS_VERTICAL
import com.example.djihellodrone.Drone.Companion.toStick
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val LETTER_HEIGHT_M = 14.0    // capitals; the small letters are a third of that
private const val BOTTOM_ALT_M = 10.0       // upright: altitude of the figure's bottom edge
// Flat: the altitude of the whole figure. A camera on the ground with a ~75° lens
// pointing straight up sees about 75 m across at this height.
private const val FLAT_ALT_M = 50.0
private const val BOX_MARGIN_M = 8.0        // leave the figure by more than this: stop and hover

/**
 * What mission 2 draws: the whole figure, or [only] one of its lines on its own, standing
 * upright in front of the camera or flat at [FLAT_ALT_M], for a camera on the ground
 * looking straight up. Sizes are in metres, in the figure's own x and y.
 */
class Drawing(val plane: Plane, val only: Int?) {
    val lines = FigurePath.lines(LETTER_HEIGHT_M, only)
    private val points = lines.flatten().flatten()
    val widthM = points.maxOf { it.x }
    val topM = points.maxOf { it.y }
    val bottomM = points.minOf { it.y }
    val depthM = topM - bottomM
    /** The highest point of the flight above the take-off spot. */
    val topAltM = if (plane == Plane.FLAT) FLAT_ALT_M else BOTTOM_ALT_M + topM
    // The drone's own limits must leave room for the whole drawing. Flat, the farthest
    // corner of the flight box is diagonal from the take-off spot.
    val minHeightLimitM = topAltM.roundToInt() + 5
    val minDistanceLimitM = when (plane) {
        Plane.UPRIGHT -> widthM + 10
        Plane.FLAT -> hypot(widthM + BOX_MARGIN_M, topM + BOX_MARGIN_M) + 2
    }.roundToInt()
    /** How the flight log names it, for example `flat line_2`. */
    val logName = "${plane.name.lowercase(Locale.US)} ${only?.let { "line_${it + 1}" } ?: "full"}"
}

/**
 * Mission 2 (light painting, outdoors): draws [drawing] for a long-exposure photo. Where
 * the pen lifts, the lights go off; between lines the pilot covers the lens. Steered on
 * GPS only: if the drone leaves the box around the drawing or loses its position, it
 * stops and hovers, because it may be far from the pilot and should not land blind.
 */
class LightPaintingMission(private val drone: Drone, private val drawing: Drawing) : Mission {

    private companion object {
        const val SPEED_MPS = 1.5
        const val LENS_PAUSE_MS = 5000L     // hover before and after each line
        const val LIGHT_SWITCH_MS = 300L    // hover while the lights switch

        // Steering. Tune these in the simulator, not in the air.
        const val LOOKAHEAD_M = 1.0         // how far ahead on the line the drone aims
        const val ARRIVE_M = 0.8            // "reached the last point"
        const val CORNER_DEG = 60.0         // a sharper turn than this is flown as a corner…
        const val CORNER_M = 0.4            // …the drone goes this close to it before turning…
        const val CORNER_MIN_SPEED_MPS = 0.5    // …and slows down to this on the way in
        const val FOLLOW_TIMEOUT_MS = 6 * 60_000L
        const val MAX_MISSED_READS = 5
        const val CLIMB_MPS = 2.0           // flat: climb to the drawing plane at most this fast
        const val CLIMB_ARRIVE_M = 0.5
        const val CLIMB_TIMEOUT_MS = 90_000L

        const val MIN_SATELLITES = 10
        // The full picture is ~800 m, about 12 minutes: from less than a full battery the drone
        // can reach its low-battery level and fly home on its own in the middle of the figure.
        const val MIN_BATTERY_PERCENT = 90
        const val METERS_PER_DEG = 111_320.0
    }

    override val name = "Mission 2"
    override val hoverOnFailure = true

    override val briefing: String get() {
        val what = when (drawing.only) {
            null -> "\"Albstadt\", \"Informatik\" and a heart, one line under the other"
            FigurePath.names.lastIndex -> "a heart"
            else -> "\"${FigurePath.names[drawing.only]}\""
        }
        val width = drawing.widthM.roundToInt()
        val where = when (drawing.plane) {
            Plane.FLAT -> "The drone will TAKE OFF, climb to ${FLAT_ALT_M.roundToInt()} m and draw $what lying flat: " +
                "about $width m across and ${drawing.depthM.roundToInt()} m deep, mirrored so that it reads correctly from below. " +
                "Then it flies back above the take-off spot and lands.\n\n" +
                "The figure lies ahead of the nose and to the drone's left. Set the camera on the ground under its middle, " +
                "about ${((drawing.topM + drawing.bottomM) / 2).roundToInt()} m ahead of the drone and ${width / 2} m to its left, " +
                "pointing straight up, with the top of the picture towards where the nose points."
            Plane.UPRIGHT -> "The drone will TAKE OFF and draw $what in the air, standing upright: " +
                "about $width m wide, up to ${drawing.topAltM.roundToInt()} m high. Then it flies back and lands.\n\n" +
                "Place the drone with its nose pointing at the camera. Seen from the camera, " +
                "the figure appears above that spot and to the right of it."
        }
        return "$where\n\nCover and uncover the lens when the status tells you to.\n\n" +
            "You need an open field, good GPS and a charged battery."
    }

    override fun run() = with(drone) {
        log("Drawing: ${drawing.logName}, ${drawing.widthM.roundToInt()} × ${drawing.depthM.roundToInt()} m")
        flightLog?.line("drawing ${drawing.logName}")
        waitForAircraft()
        checkPreconditions()

        takeOff()
        // The take-off spot is under the figure's bottom-left corner as the camera sees it.
        val origin = pull(FlightControllerKey.KeyAircraftLocation3D)
        val originLat = origin?.latitude
        val originLon = origin?.longitude
        val heading = heading()
        if (originLat == null || originLon == null || heading == null) {
            throw IllegalStateException("No position or heading from the drone after takeoff.")
        }
        val frame = FigureFrame(originLat, originLon, heading, drawing.plane)

        takeSticks()
        sleep(500)
        if (drawing.plane == Plane.FLAT) {
            step("Climbing to ${FLAT_ALT_M.roundToInt()} m…")
            climbToPlane(frame)
        }

        // A line of the figure is its pieces. Each piece is drawn with the lights on,
        // and the drone flies from one piece to the next with them off, like a pen
        // that lifts. The lens is covered while it flies from one line to the next.
        val lines = drawing.lines
        try {
            for ((index, pieces) in lines.withIndex()) {
                val line = drawing.only?.let { FigurePath.names[it] } ?: "line ${index + 1} of ${lines.size}"

                step("Flying to the start of $line…")
                setLights(false)
                follow(frame, listOf(pieces.first().first()))

                // The drone holds its own position while the sticks are centred.
                step("UNCOVER THE LENS.\nDrawing $line in ${LENS_PAUSE_MS / 1000} s.")
                sleep(LENS_PAUSE_MS)

                step("Drawing $line…")
                for ((n, piece) in pieces.withIndex()) {
                    follow(frame, listOf(piece.first()))
                    setLights(true)
                    sleep(LIGHT_SWITCH_MS)
                    follow(frame, piece) { percent -> progress("Drawing $line, part ${n + 1} of ${pieces.size}… $percent %") }
                    setLights(false)
                    sleep(LIGHT_SWITCH_MS)
                }

                step("COVER THE LENS.\nMoving on in ${LENS_PAUSE_MS / 1000} s.")
                sleep(LENS_PAUSE_MS)
            }
        } finally {
            restoreLights()
        }

        // Upright it stays at the height where the drawing ended; flat it stays at FLAT_ALT_M.
        step("Flying back to the take-off spot…")
        val endY = if (drawing.plane == Plane.FLAT) 0.0 else lines.last().last().last().y
        follow(frame, listOf(PathPoint(0.0, endY)))
        sleep(1_500)   // brake & settle
        land(pull(FlightControllerKey.KeyAircraftLocation3D)?.altitude ?: drawing.topAltM)
    }

    /**
     * GPS, battery and the drone's own limits, before takeoff. A limit that cannot be read
     * only warns: the setting may simply be unreadable. A limit that is too low would stop
     * the drone short of the figure while the app keeps pushing against it.
     */
    private fun checkPreconditions() = with(drone) {
        // Unlike missions 1 and 3 this flight has no vision fallback: it is steered on GPS only.
        step("Checking GPS and battery…")
        val sats = pull(FlightControllerKey.KeyGPSSatelliteCount) ?: -1
        if (sats < MIN_SATELLITES) throw IllegalStateException("GPS too weak: $sats satellites, need $MIN_SATELLITES.")
        val battery = pull(BatteryKey.KeyChargeRemainingInPercent) ?: -1
        if (battery < MIN_BATTERY_PERCENT) {
            // The simulator spends no battery, so a test run on the desk needs no full one.
            if (simulatorOn) log("Battery $battery %: fine for the simulator, a real flight needs $MIN_BATTERY_PERCENT %.")
            else throw IllegalStateException("Battery too low: $battery %, need $MIN_BATTERY_PERCENT %.")
        }
        log("GPS $sats satellites, battery $battery %")

        step("Checking the height and distance limits…")
        val heightLimit = pull(FlightControllerKey.KeyHeightLimit)
        val needHeight = drawing.minHeightLimitM
        if (heightLimit == null) log("WARNING: could not read the height limit, need $needHeight m")
        else if (heightLimit < needHeight) {
            throw IllegalStateException("Height limit is $heightLimit m, the figure needs $needHeight m. Raise it in DJI Fly.")
        }
        if (pull(FlightControllerKey.KeyDistanceLimitEnabled) == true) {
            val distanceLimit = pull(FlightControllerKey.KeyDistanceLimit)
            val needDistance = drawing.minDistanceLimitM
            if (distanceLimit == null) log("WARNING: could not read the distance limit, need $needDistance m")
            else if (distanceLimit < needDistance) {
                throw IllegalStateException("Distance limit is $distanceLimit m, the figure needs $needDistance m. Raise or switch it off in DJI Fly.")
            }
        }
        log("Height limit ${heightLimit ?: "?"} m")
    }

    /** Where the drone is in the figure: [x] and [y] in the drawing plane, [depth] out of it. */
    private class Position(val x: Double, val y: Double, val depth: Double)

    /**
     * The drawing plane, seen from the camera. x runs to the camera's right, which is the
     * drone's left in both planes. Upright, the plane stands across the nose: y is the height
     * above [BOTTOM_ALT_M] and depth is towards the camera. Flat, the plane lies at
     * [FLAT_ALT_M] and the camera looks up at it, with the top of its picture where the nose
     * points: y runs ahead of the nose and depth is the height above the plane. The camera's
     * right is then the drone's left, so the figure reads correctly from below.
     */
    private class FigureFrame(val originLat: Double, val originLon: Double, headingDeg: Double, val plane: Plane) {
        private val h = Math.toRadians(headingDeg)

        fun locate(lat: Double, lon: Double, alt: Double): Position {
            val north = (lat - originLat) * METERS_PER_DEG
            val east = (lon - originLon) * METERS_PER_DEG * cos(Math.toRadians(originLat))
            val forward = north * cos(h) + east * sin(h)
            val right = -north * sin(h) + east * cos(h)
            return when (plane) {
                Plane.UPRIGHT -> Position(x = -right, y = alt - BOTTOM_ALT_M, depth = forward)
                Plane.FLAT -> Position(x = -right, y = forward, depth = alt - FLAT_ALT_M)
            }
        }
    }

    /** Flat drawings: climbs on the spot until the drone is in the drawing plane. */
    private fun climbToPlane(frame: FigureFrame) = with(drone) {
        val deadline = System.currentTimeMillis() + CLIMB_TIMEOUT_MS
        try {
            while (true) {
                checkAbort()
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("the climb took too long")
                val here = readPosition(frame)
                if (abs(here.depth) < CLIMB_ARRIVE_M) return@with
                // The other sticks stay centred, so the drone holds its place over the take-off spot.
                val rate = (-APPROACH_GAIN * here.depth).coerceIn(-CLIMB_MPS, CLIMB_MPS)
                sticks.leftStick.verticalPosition = toStick(rate * STICK_PER_MPS_VERTICAL)
                Thread.sleep(CONTROL_PERIOD_MS)
            }
        } finally {
            zeroSticks()
        }
    }

    /**
     * Flies along [points], aiming a short distance ahead on the line and steering
     * on the measured position, not on time. Returns at the last point.
     */
    private fun follow(frame: FigureFrame, points: List<PathPoint>, onProgress: (Int) -> Unit = {}) = with(drone) {
        val deadline = System.currentTimeMillis() + FOLLOW_TIMEOUT_MS
        var aim = 0
        try {
            while (true) {
                checkAbort()
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("the path took too long")
                val here = readPosition(frame)
                // Move the aim point along the line while the drone is close behind it. At a
                // sharp corner it has to get much closer first: otherwise short strokes that
                // go out and back (the stem of the d, the arm of the k) would be cut off.
                while (aim < points.lastIndex &&
                    distance(here, points[aim]) < (if (isCorner(points, aim)) CORNER_M else LOOKAHEAD_M)) aim++
                val left = distance(here, points[aim])
                if (aim == points.lastIndex && left < ARRIVE_M) return@with
                // Constant speed along the line; slow down into corners and for the last point.
                val speed = when {
                    aim == points.lastIndex -> min(SPEED_MPS, APPROACH_GAIN * left)
                    isCorner(points, aim) -> min(SPEED_MPS, max(CORNER_MIN_SPEED_MPS, APPROACH_GAIN * left))
                    else -> SPEED_MPS
                }
                steer(frame, here, points[aim], speed)
                onProgress(100 * aim / max(1, points.lastIndex))
                Thread.sleep(CONTROL_PERIOD_MS)
            }
        } finally {
            zeroSticks()
        }
    }

    /** True where the line turns by more than [CORNER_DEG] at point [k]. */
    private fun isCorner(points: List<PathPoint>, k: Int): Boolean {
        if (k == 0 || k >= points.lastIndex) return false
        val ax = points[k].x - points[k - 1].x
        val ay = points[k].y - points[k - 1].y
        val bx = points[k + 1].x - points[k].x
        val by = points[k + 1].y - points[k].y
        val cosTurn = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
        return cosTurn < cos(Math.toRadians(CORNER_DEG))
    }

    /** Fresh position, checked against the flight box. Centres the sticks while the drone stays silent. */
    private fun readPosition(frame: FigureFrame): Position = with(drone) {
        repeat(MAX_MISSED_READS) {
            val loc = pull(FlightControllerKey.KeyAircraftLocation3D, 400)
            val lat = loc?.latitude
            val lon = loc?.longitude
            val alt = loc?.altitude
            if (lat != null && lon != null && alt != null) {
                val here = frame.locate(lat, lon, alt)
                // Upright, the ground bounds the box from below; flat, the climb starts far under the plane.
                val inside = here.x in -BOX_MARGIN_M..(drawing.widthM + BOX_MARGIN_M) &&
                    here.y <= drawing.topM + BOX_MARGIN_M &&
                    here.depth <= BOX_MARGIN_M &&
                    when (drawing.plane) {
                        Plane.UPRIGHT -> here.depth >= -BOX_MARGIN_M
                        Plane.FLAT -> here.y >= drawing.bottomM - BOX_MARGIN_M
                    }
                if (!inside) {
                    throw IllegalStateException(String.format(Locale.US,
                        "left the flight box (x %.1f m, y %.1f m, %.1f m off the plane, altitude %.1f m)",
                        here.x, here.y, here.depth, alt))
                }
                return here
            }
            zeroSticks()   // no position: never keep flying on an old command
            checkAbort()
        }
        throw IllegalStateException("lost the position reading")
    }

    /** Distance to [target] within the drawing plane, in metres. */
    private fun distance(here: Position, target: PathPoint): Double =
        hypot(target.x - here.x, target.y - here.y)

    /** One control step: sets the sticks to move towards [target] at [speedMps]. */
    private fun steer(frame: FigureFrame, here: Position, target: PathPoint, speedMps: Double) {
        val dx = target.x - here.x
        val dy = target.y - here.y
        val dist = hypot(dx, dy)
        val scale = if (dist > 0.01) speedMps / dist else 0.0
        val sticks = drone.sticks
        // x grows to the drone's LEFT (the camera's right), so +x is a negative right-stick move.
        sticks.rightStick.horizontalPosition = toStick(-dx * scale * STICK_PER_MPS_HORIZONTAL)
        // Over a long flight the drone creeps out of the drawing plane; push it back gently.
        val back = (-APPROACH_GAIN * here.depth).coerceIn(-1.0, 1.0)
        when (frame.plane) {
            Plane.UPRIGHT -> {
                sticks.leftStick.verticalPosition = toStick(dy * scale * STICK_PER_MPS_VERTICAL)
                sticks.rightStick.verticalPosition = toStick(back * STICK_PER_MPS_HORIZONTAL)
            }
            Plane.FLAT -> {
                sticks.rightStick.verticalPosition = toStick(dy * scale * STICK_PER_MPS_HORIZONTAL)
                sticks.leftStick.verticalPosition = toStick(back * STICK_PER_MPS_VERTICAL)
            }
        }
    }
}
