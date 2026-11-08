package com.example.djihellodrone

import com.example.djihellodrone.Drone.Companion.APPROACH_GAIN
import com.example.djihellodrone.Drone.Companion.CONTROL_PERIOD_MS
import com.example.djihellodrone.Drone.Companion.STICK_PER_MPS_HORIZONTAL
import com.example.djihellodrone.Drone.Companion.STICK_PER_MPS_VERTICAL
import com.example.djihellodrone.Drone.Companion.toStick
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.flightassistant.AuxiliaryLightMode
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
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
class LightPaintingMission(
    private val drone: Drone,
    private val drawing: Drawing,
    private val pen: Pen,
    private val gpsCheck: Boolean,
) : Mission {

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
        // The battery needed grows with the drawing: the full picture is ~800 m, about 12
        // minutes, and needs ~90 %; the heart alone needs much less. Budget a conservative
        // rate per minute and keep a reserve above the drone's own low-battery level, so it
        // does not turn home on its own in the middle of the figure.
        const val BATTERY_RESERVE_PERCENT = 30
        const val BATTERY_PERCENT_PER_MIN = 5.0
        const val TAKEOFF_LANDING_S = 60.0  // take off, settle, descend and land

        // GPS check: over the start of the figure, the drone hovers until GPS is good and its
        // position has stopped jumping. It cannot see slow GPS drift, since it holds its place
        // with that same GPS; but GPS usually gets better in the first minutes of a flight.
        const val GPS_MIN_WAIT_MS = 60_000L
        const val GPS_MIN_WAIT_SIM_MS = 20_000L      // the simulator has nothing to settle
        const val GPS_MAX_WAIT_MS = 180_000L
        const val GPS_WINDOW_MS = 20_000L           // the position in the drawing plane must hold this long…
        const val GPS_STABLE_M = 0.5                // …within this distance of its mean (tune from the logs)
        const val GPS_MIN_SATELLITES = 15
        const val GPS_READ_PERIOD_MS = 2_000L
        val GPS_GOOD_LEVELS = setOf(GPSSignalLevel.LEVEL_4, GPSSignalLevel.LEVEL_5, GPSSignalLevel.LEVEL_10)
        const val SIGNAL_FLASHES = 3
        const val SIGNAL_FLASH_MS = 300L
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
        val light = when (pen) {
            Pen.NONE -> "The drone's own lights stay off during the figure: fix your own light to it."
            Pen.WHITE -> "The white light under the drone draws; the green lights stay off."
            Pen.GREEN -> "The green lights draw; the white light under the drone stays off."
        }
        val gps = if (gpsCheck) "\n\nBefore drawing it hovers over the start for 1 to 3 minutes until GPS is stable, " +
            "then flashes the white light $SIGNAL_FLASHES times. Keep the lens covered until UNCOVER THE LENS." else ""
        return "OUTDOORS ONLY: the drone steers by GPS. You need an open field under open sky, " +
            "and a charged battery.\n\n$where\n\n$light$gps\n\nCover and uncover the lens when the status tells you to."
    }

    // The flight log: what the drone was doing ([phase]) at every control step, and its own
    // velocity added up ([tracker]) next to GPS, to tell GPS drift from steering errors.
    private var phase = "start"
    private val tracker = Tracker()
    private var lastStepMs = 0L

    override fun run() = with(drone) {
        log("Drawing: ${drawing.logName}, ${drawing.widthM.roundToInt()} × ${drawing.depthM.roundToInt()} m, light ${pen.name.lowercase(Locale.US)}")
        flightLog?.line("drawing ${drawing.logName} light ${pen.name.lowercase(Locale.US)} gps_check=$gpsCheck simulator=$simulatorOn")
        logPlan()
        waitForAircraft()
        checkPreconditions()

        tracker.start()
        try {
            fly()
        } finally {
            tracker.stop()
        }
    }

    private fun fly() = with(drone) {
        mark("takeoff")
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
        flightLog?.line(String.format(Locale.US, "frame lat=%.7f lon=%.7f alt=%.2f heading=%.1f plane=%s",
            originLat, originLon, origin.altitude ?: Double.NaN, heading, drawing.plane.name.lowercase(Locale.US)))

        takeSticks()
        sleep(500)
        if (drawing.plane == Plane.FLAT) {
            step("Climbing to ${FLAT_ALT_M.roundToInt()} m…")
            mark("climb")
            climbToPlane(frame)
        }

        // A line of the figure is its pieces. Each piece is drawn with the lights on,
        // and the drone flies from one piece to the next with them off, like a pen
        // that lifts. The lens is covered while it flies from one line to the next.
        val lines = drawing.lines
        try {
            // Both of the drone's lights off for the whole figure; only the pen comes on.
            setLights(false)
            setWhiteLight(AuxiliaryLightMode.OFF)
            for ((index, pieces) in lines.withIndex()) {
                val line = drawing.only?.let { FigurePath.names[it] } ?: "line ${index + 1} of ${lines.size}"

                step("Flying to the start of $line…")
                mark("to_start line=${index + 1}")
                follow(frame, listOf(pieces.first().first()))
                if (gpsCheck && index == 0) waitForStableGps(frame)

                // The drone holds its own position while the sticks are centred.
                step("UNCOVER THE LENS.\nDrawing $line in ${LENS_PAUSE_MS / 1000} s.")
                mark("lens_open line=${index + 1}")
                hold(frame, LENS_PAUSE_MS)

                step("Drawing $line…")
                for ((n, piece) in pieces.withIndex()) {
                    mark("to_piece line=${index + 1} piece=${n + 1}", status = false)
                    follow(frame, listOf(piece.first()))
                    mark("draw line=${index + 1} piece=${n + 1}", status = false)
                    setPen(true)
                    follow(frame, piece) { percent -> progress("Drawing $line, part ${n + 1} of ${pieces.size}… $percent %") }
                    setPen(false)
                }

                step("COVER THE LENS.\nMoving on in ${LENS_PAUSE_MS / 1000} s.")
                mark("lens_close line=${index + 1}")
                hold(frame, LENS_PAUSE_MS)
            }
        } finally {
            restoreLights()
        }

        // Upright it stays at the height where the drawing ended; flat it stays at FLAT_ALT_M.
        step("Flying back to the take-off spot…")
        mark("home")
        val endY = if (drawing.plane == Plane.FLAT) 0.0 else lines.last().last().last().y
        follow(frame, listOf(PathPoint(0.0, endY)))
        mark("settle", status = false)
        hold(frame, 1_500)   // brake & settle
        mark("land")
        land(pull(FlightControllerKey.KeyAircraftLocation3D)?.altitude ?: drawing.topAltM)
    }

    /**
     * Hovers until GPS is good and the position has held within [GPS_STABLE_M] for
     * [GPS_WINDOW_MS], at least [GPS_MIN_WAIT_MS]; then flashes the white light. After
     * [GPS_MAX_WAIT_MS] it gives up, and the mission stops with the drone hovering.
     */
    private fun waitForStableGps(frame: FigureFrame) = with(drone) {
        step("Waiting for stable GPS…")
        mark("gps_check")
        val start = System.currentTimeMillis()
        val minWait = if (simulatorOn) GPS_MIN_WAIT_SIM_MS else GPS_MIN_WAIT_MS
        val window = ArrayDeque<Pair<Long, Position>>()
        var sats = -1
        var level: GPSSignalLevel? = null
        var lastRead = 0L
        zeroSticks()
        while (true) {
            checkAbort()
            val now = System.currentTimeMillis()
            if (now - lastRead >= GPS_READ_PERIOD_MS) {
                sats = pull(FlightControllerKey.KeyGPSSatelliteCount, 500) ?: -1
                level = pull(FlightControllerKey.KeyGPSSignalLevel, 500)
                lastRead = now
            }
            val here = readPosition(frame)
            logStep(here, null, null, 0.0)
            window.addLast(now to here)
            while (now - window.first().first > GPS_WINDOW_MS) window.removeFirst()
            // Only the drawing plane counts: out of it, the drone is pushed back while drawing.
            val mx = window.sumOf { it.second.x } / window.size
            val my = window.sumOf { it.second.y } / window.size
            val spread = window.maxOf { (_, p) -> hypot(p.x - mx, p.y - my) }
            // The simulator's satellites and signal level say nothing about the sky.
            val goodSignal = simulatorOn || (sats >= GPS_MIN_SATELLITES && level in GPS_GOOD_LEVELS)
            val elapsed = now - start
            val state = String.format(Locale.US, "%d s, %d satellites, level %s, moves %.1f m",
                elapsed / 1000, sats, level?.value() ?: "?", spread)
            progress("Waiting for stable GPS… $state")
            if (elapsed >= max(minWait, GPS_WINDOW_MS) && spread <= GPS_STABLE_M && goodSignal) {
                log("GPS stable: $state")
                flightLog?.line(String.format(Locale.US, "gps_check ok elapsed_s=%d sats=%d level=%s spread=%.2f",
                    elapsed / 1000, sats, level, spread))
                signalReady()
                return@with
            }
            if (elapsed > GPS_MAX_WAIT_MS) {
                flightLog?.line(String.format(Locale.US, "gps_check failed sats=%d level=%s spread=%.2f", sats, level, spread))
                throw IllegalStateException("GPS not stable after ${GPS_MAX_WAIT_MS / 60_000} min ($state)")
            }
            Thread.sleep(CONTROL_PERIOD_MS)
        }
    }

    /** "GPS stable, drawing soon": flashes the white light while the lens is still covered. */
    private fun signalReady() = with(drone) {
        flightLog?.line("light signal flashes=$SIGNAL_FLASHES")
        repeat(SIGNAL_FLASHES) {
            setWhiteLight(AuxiliaryLightMode.ON)
            sleep(SIGNAL_FLASH_MS)
            setWhiteLight(AuxiliaryLightMode.OFF)
            sleep(SIGNAL_FLASH_MS)
        }
    }

    /** Every planned point once, before the flight: the analysis needs no copy of the app's figure. */
    private fun logPlan() {
        val log = drone.flightLog ?: return
        for ((i, line) in drawing.lines.withIndex()) for ((j, piece) in line.withIndex()) {
            log.line("plan line=${i + 1} piece=${j + 1} pts=" +
                piece.joinToString(" ") { String.format(Locale.US, "%.2f,%.2f", it.x, it.y) })
        }
    }

    /**
     * A new [phase] in the flight log. With [status] also what changes slowly and takes too
     * long to read at every step: satellites, GPS level, wind, heading, compass and battery.
     * Between the pieces of a line it is left out, so the drone does not hover longer there.
     */
    private fun mark(name: String, status: Boolean = true) = with(drone) {
        phase = name.substringBefore(' ')
        val log = flightLog ?: return@with
        log.line("phase $name")
        if (status) log.line("status sats=${pull(FlightControllerKey.KeyGPSSatelliteCount, 500)}" +
            " gps_level=${pull(FlightControllerKey.KeyGPSSignalLevel, 500)}" +
            " wind_speed_dmps=${pull(FlightControllerKey.KeyWindSpeed, 500)}" +
            " wind_dir=${pull(FlightControllerKey.KeyWindDirection, 500)}" +
            " wind_warning=${pull(FlightControllerKey.KeyWindWarning, 500)}" +
            " heading=${pull(FlightControllerKey.KeyCompassHeading, 500)}" +
            " compass_error=${pull(FlightControllerKey.KeyCompassHasError, 500)}" +
            " battery=${pull(BatteryKey.KeyChargeRemainingInPercent, 500)}")
    }

    /**
     * One control step in the flight log: where GPS puts the drone in the figure and on the
     * globe, the point it aims at ([aim], [target]; null while climbing or hovering), the
     * speed it was asked for, the sticks, and its own velocity and velocity added up.
     */
    private fun logStep(here: Position, aim: Int?, target: PathPoint?, speed: Double) {
        val log = drone.flightLog ?: return
        val now = System.currentTimeMillis()
        val v = tracker.velocity()
        val (tn, te, tu) = tracker.position()
        val s = drone.sticks
        log.line(String.format(Locale.US,
            "step phase=%s dt_ms=%d x=%.2f y=%.2f depth=%.2f lat=%.7f lon=%.7f alt=%.2f " +
                "aim=%s aim_x=%.2f aim_y=%.2f speed=%.2f stick_rh=%d stick_rv=%d stick_lv=%d " +
                "vn=%.2f ve=%.2f vd=%.2f tn=%.2f te=%.2f tu=%.2f",
            phase, if (lastStepMs == 0L) 0 else now - lastStepMs, here.x, here.y, here.depth, here.lat, here.lon, here.alt,
            aim?.toString() ?: "-", target?.x ?: Double.NaN, target?.y ?: Double.NaN, speed,
            s.rightStick.horizontalPosition, s.rightStick.verticalPosition, s.leftStick.verticalPosition,
            v?.x ?: Double.NaN, v?.y ?: Double.NaN, v?.z ?: Double.NaN, tn, te, tu))
        lastStepMs = now
    }

    /** Hovers for [ms] with the sticks centred, logging where the drone drifts meanwhile. */
    private fun hold(frame: FigureFrame, ms: Long) = with(drone) {
        val end = System.currentTimeMillis() + ms
        zeroSticks()
        while (System.currentTimeMillis() < end) {
            checkAbort()
            logStep(readPosition(frame), null, null, 0.0)
            Thread.sleep(CONTROL_PERIOD_MS)
        }
    }

    /** Switches the [pen] light and hovers while it switches; with [Pen.NONE] it does nothing. */
    private fun setPen(on: Boolean) {
        when (pen) {
            Pen.NONE -> return
            Pen.WHITE -> drone.setWhiteLight(if (on) AuxiliaryLightMode.ON else AuxiliaryLightMode.OFF)
            Pen.GREEN -> drone.setLights(on)
        }
        drone.flightLog?.line("light pen=${pen.name.lowercase(Locale.US)} on=$on")
        drone.sleep(LIGHT_SWITCH_MS)
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
        if (sats < MIN_SATELLITES) throw IllegalStateException("GPS too weak: $sats satellites, need $MIN_SATELLITES. " +
            "Mission 2 flies outdoors only, under open sky.")
        val battery = pull(BatteryKey.KeyChargeRemainingInPercent) ?: -1
        val minutes = estimatedFlightMinutes()
        val needBattery = min(100, (BATTERY_RESERVE_PERCENT + BATTERY_PERCENT_PER_MIN * minutes).roundToInt())
        if (battery < needBattery) {
            // The simulator spends no battery, so a test run on the desk needs no full one.
            if (simulatorOn) log("Battery $battery %: fine for the simulator, a real flight needs $needBattery %.")
            else throw IllegalStateException(String.format(Locale.US,
                "Battery too low: $battery %%, this drawing needs $needBattery %% (about %.0f min of flight).", minutes))
        }
        log(String.format(Locale.US, "GPS $sats satellites, battery $battery %% (need $needBattery %%, ~%.1f min)", minutes))

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
        flightLog?.line(String.format(Locale.US, "pre sats=%d battery=%d need_battery=%d est_min=%.1f height_limit=%s",
            sats, battery, needBattery, minutes, heightLimit))
    }

    /**
     * Rough flight time of [drawing], in minutes: the path [run] flies (in the plane, with
     * the lights on and off, and back), at [SPEED_MPS], plus the pauses, the climb and
     * take-off and landing. Corners are slower than this, so it errs on the short side;
     * the reserve covers that.
     */
    private fun estimatedFlightMinutes(): Double {
        var pathM = 0.0
        var at = PathPoint(0.0, 0.0)
        var pieces = 0
        for (line in drawing.lines) for (piece in line) {
            for (p in piece) { pathM += hypot(p.x - at.x, p.y - at.y); at = p }
            pieces++
        }
        val endY = if (drawing.plane == Plane.FLAT) 0.0 else at.y
        pathM += hypot(at.x, at.y - endY)
        val climbS = if (drawing.plane == Plane.FLAT) 2 * FLAT_ALT_M / CLIMB_MPS else 0.0
        val pausesS = drawing.lines.size * 2 * LENS_PAUSE_MS / 1000.0 + pieces * 2 * LIGHT_SWITCH_MS / 1000.0
        val gpsS = if (gpsCheck) GPS_MAX_WAIT_MS / 1000.0 else 0.0
        return (pathM / SPEED_MPS + climbS + pausesS + gpsS + TAKEOFF_LANDING_S) / 60.0
    }

    /**
     * Where the drone is in the figure: [x] and [y] in the drawing plane, [depth] out of it;
     * and where GPS puts it on the globe.
     */
    private class Position(val x: Double, val y: Double, val depth: Double, val lat: Double, val lon: Double, val alt: Double)

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
                Plane.UPRIGHT -> Position(x = -right, y = alt - BOTTOM_ALT_M, depth = forward, lat, lon, alt)
                Plane.FLAT -> Position(x = -right, y = forward, depth = alt - FLAT_ALT_M, lat, lon, alt)
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
                logStep(here, null, null, rate)
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
                logStep(here, aim, points[aim], speed)
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
