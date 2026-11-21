package com.example.djihellodrone

import android.util.Log
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightassistant.AuxiliaryLightMode
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthority
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.interfaces.IVirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the drone reports to: the on-screen log, the status line and the lights checkbox. */
interface Display {
    fun log(msg: String)
    /** Shows [msg] as the status and logs it. */
    fun status(msg: String)
    /** Shows [msg] as the status without logging it, for updates many times a second. */
    fun progress(msg: String)
    fun lightsRestored()
}

/** The pilot or the drone itself took control: the mission must stop and command nothing more. */
class ControlTakenBack(why: String) : RuntimeException(why)

/**
 * The aircraft as the missions see it: blocking reads and actions on top of MSDK v5's
 * callbacks, the virtual sticks, and the safety state every mission checks between steps
 * (STOP pressed, control taken back). Mission calls block, so run them off the UI thread.
 *
 * Movement uses the *basic* virtual-stick API: emulated stick positions with the exact
 * semantics of the physical RC in mode 2 (right stick up = forward, left stick sideways = rotate).
 * Mission 3 can switch to the *advanced* mode instead ([useAdvancedSticks]): velocities in m/s
 * that the drone holds itself.
 */
class Drone(private val display: Display) {

    companion object {
        const val TAG = "DroneHello"

        // Stick deflection is -660..660 (= full stick). Keep values small.
        const val STICK_PER_MPS_HORIZONTAL = 55.0   // 660 = full stick ≈ 12 m/s
        const val STICK_PER_MPS_VERTICAL = 130.0    // 660 = full stick ≈ 5 m/s
        const val MAX_STICK = 300
        const val CONTROL_PERIOD_MS = 100L
        const val APPROACH_GAIN = 0.8               // m/s per metre left to the target

        private const val OBSTACLE_LOG_PERIOD_MS = 200L
        private const val EXTRAS_PROBE_MS = 500L     // per key, once when the log opens
        private const val EXTRAS_FAST_MS = 100L      // position, attitude, ultrasonic: at most 10 lines a second
        // An unreadable altitude during an emergency landing: assume the 120 m open-category ceiling.
        private const val UNKNOWN_ALTITUDE_M = 120.0
        private const val LANDING_ATTEMPTS = 5      // landing requests, 0.5 s apart
        private const val LANDING_STALL_MS = 5_000L // not sinking this long: ask for the landing again
        private const val LANDING_SETTLE_MS = 1_000L // after handing back the sticks, before asking to land

        // Where the simulated drone stands (Albstadt-Ebingen).
        private const val SIM_LATITUDE = 48.2112
        private const val SIM_LONGITUDE = 9.0293
        private const val SIM_SATELLITES = 15

        fun toStick(value: Double): Int = clampStick(value)
    }

    val sticks: IVirtualStickManager get() = VirtualStickManager.getInstance()

    @Volatile var simulatorOn = false
        private set
    @Volatile var recovering = false
        private set

    @Volatile private var aborted = false
    private val landingLock = Any()
    private val lastOutcome = ConcurrentHashMap<String, String>()
    @Volatile private var watching = false

    // The app steers only while the flight controller lets it. When the pilot moves a
    // stick or presses pause, or the drone decides to come home, control goes back and
    // the mission has to stop without commanding anything more, landing included.
    @Volatile private var appHoldsSticks = false
    @Volatile private var appWasInControl = false
    @Volatile private var takenBack: String? = null

    private val stickListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            val appInControl = state.isVirtualStickEnable &&
                state.currentFlightControlAuthorityOwner == FlightControlAuthority.MSDK
            flightLog?.line("stick_state enabled=${state.isVirtualStickEnable} " +
                "advanced=${state.isVirtualStickAdvancedModeEnabled} owner=${state.currentFlightControlAuthorityOwner}")
            if (appInControl) appWasInControl = true
            // Only after the app has been seen in control: the first updates after
            // enabling can still show the old owner.
            else if (appHoldsSticks && appWasInControl) {
                noteTakenBack("${state.currentFlightControlAuthorityOwner} has control")
            }
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            display.log("Flight control changed hands: $reason")
            flightLog?.line("control_change reason=$reason")
            if (appHoldsSticks && reason != FlightControlAuthorityChangeReason.MSDK_REQUEST) {
                noteTakenBack(reason.toString())
            }
        }
    }

    private fun noteTakenBack(why: String) {
        if (takenBack == null) {
            takenBack = why
            display.log("Control taken back from the app: $why")
        }
    }

    // ---------------------------------------------------------------- flight log

    /** The open mission's log file; null between missions. */
    @Volatile var flightLog: FlightLog? = null
        private set
    /** Gets every obstacle reading while a mission flies, for example to correct the height. */
    @Volatile var onObstacle: ((ObstacleData) -> Unit)? = null
    @Volatile private var obstacleUpdates = 0
    @Volatile private var lastObstacleLogMs = 0L
    private var lastObstacleLine = ""
    private val obstacleListener = ObstacleDataListener { data ->
        onObstacle?.invoke(data)
        logObstacles(data)
    }

    /** Opens the mission's log file, records the avoidance setting and listens to the obstacle data. */
    fun openFlightLog(dir: File?, name: String) {
        obstacleUpdates = 0
        lastObstacleLogMs = 0L
        lastObstacleLine = ""
        val log = FlightLog(dir, name.lowercase(Locale.US).replace(' ', '-'))
        flightLog = log
        // Logging must never stop a flight.
        try {
            val perception = PerceptionManager.getInstance()
            val type = readSetting<Any> { perception.getObstacleAvoidanceType(it.asCallback()) }
            val horizontal = readSetting<Any> {
                perception.getObstacleAvoidanceEnabled(PerceptionDirection.HORIZONTAL, it.asCallback())
            }
            display.log("Obstacle avoidance: type $type, horizontal $horizontal")
            log.line("avoidance type=$type horizontal=$horizontal")
            perception.addObstacleDataListener(obstacleListener)
        } catch (e: Exception) {
            display.log("Obstacle avoidance could not be read: $e")
        }
        listenToExtras(log)
    }

    // The drone's own idea of where it is, and how sure it is: logged only, to compare with
    // the tracker after a flight. Not every key is documented for the Mini 4 Pro; the probe
    // at the start shows which ones answer.
    private val extrasHolder = Any()
    private val extrasLastMs = ConcurrentHashMap<String, Long>()
    private val extrasLastText = ConcurrentHashMap<String, String>()

    private fun listenToExtras(log: FlightLog) {
        extrasLastMs.clear()
        extrasLastText.clear()
        try {
            val keys = listOf(
                "takeoff_pos" to FlightControllerKey.KeyRelateToTakeOffPosition,
                "vision_level" to FlightControllerKey.KeyVisionPositioningLevel,
                "fusion_level" to FlightControllerKey.KeyFusionPositioningLevel,
                "position_source" to FlightControllerKey.KeyDronePositionSource,
                "positioning_data" to FlightControllerKey.KeyPositioningDataSource,
                "vision_used" to FlightControllerKey.KeyIsVisionSensorUsed,
                "without_vision" to FlightControllerKey.KeyIsWithoutVisionPosition,
                "ultrasonic" to FlightControllerKey.KeyUltrasonicHeight,
                "attitude" to FlightControllerKey.KeyAircraftAttitude,
                "flight_mode" to FlightControllerKey.KeyFlightMode,
                "fc_flight_mode" to FlightControllerKey.KeyFCFlightMode,
                "wind" to FlightControllerKey.KeyWindWarning,
                "battery" to BatteryKey.KeyChargeRemainingInPercent,
                "gps_satellites" to FlightControllerKey.KeyGPSSatelliteCount,
                "gps_level" to FlightControllerKey.KeyGPSSignalLevel,
            )
            for ((name, key) in keys) log.line("probe $name ${describe(key, EXTRAS_PROBE_MS).replace(' ', '_')}")

            logChanges("takeoff_pos", FlightControllerKey.KeyRelateToTakeOffPosition, EXTRAS_FAST_MS) {
                String.format(Locale.US, "x=%.3f y=%.3f z=%.3f", it.x ?: Double.NaN, it.y ?: Double.NaN, it.z ?: Double.NaN)
            }
            logChanges("attitude", FlightControllerKey.KeyAircraftAttitude, EXTRAS_FAST_MS) {
                String.format(Locale.US, "pitch=%.2f roll=%.2f yaw=%.2f", it.pitch ?: Double.NaN, it.roll ?: Double.NaN, it.yaw ?: Double.NaN)
            }
            logChanges("ultrasonic", FlightControllerKey.KeyUltrasonicHeight, EXTRAS_FAST_MS) { "raw=$it" }
            logChanges("vision_level", FlightControllerKey.KeyVisionPositioningLevel, 0) { "level=${it.level}" }
            logChanges("fusion_level", FlightControllerKey.KeyFusionPositioningLevel, 0) { "level=${it.level}" }
            logChanges("position_source", FlightControllerKey.KeyDronePositionSource, 0) { "value=$it" }
            logChanges("positioning_data", FlightControllerKey.KeyPositioningDataSource, 0) { "value=$it" }
            logChanges("vision_used", FlightControllerKey.KeyIsVisionSensorUsed, 0) { "value=$it" }
            logChanges("without_vision", FlightControllerKey.KeyIsWithoutVisionPosition, 0) { "value=$it" }
            // Holding position on the cameras or only on attitude (ATTI): the latter drifts away indoors.
            logChanges("flight_mode", FlightControllerKey.KeyFlightMode, 0) { "value=$it" }
            logChanges("fc_flight_mode", FlightControllerKey.KeyFCFlightMode, 0) { "value=$it" }
            logChanges("wind", FlightControllerKey.KeyWindWarning, 0) { "value=$it" }
            logChanges("battery", BatteryKey.KeyChargeRemainingInPercent, 0) { "percent=$it" }
            logChanges("gps_satellites", FlightControllerKey.KeyGPSSatelliteCount, 0) { "count=$it" }
            logChanges("gps_level", FlightControllerKey.KeyGPSSignalLevel, 0) { "value=$it" }
        } catch (e: Exception) {
            display.log("Extra drone values could not be logged: $e")
        }
    }

    /** Writes [name] and the value as [text] when it changes, at most once per [periodMs]. */
    private fun <T> logChanges(name: String, key: DJIKeyInfo<T>, periodMs: Long, text: (T) -> String) {
        KeyManager.getInstance().listen(KeyTools.createKey(key), extrasHolder, object : CommonCallbacks.KeyListener<T> {
            override fun onValueChange(oldValue: T?, newValue: T?) {
                val line = if (newValue == null) "null" else text(newValue)
                if (line == extrasLastText[name]) return
                val now = System.currentTimeMillis()
                if (now - (extrasLastMs[name] ?: 0L) < periodMs) return
                extrasLastText[name] = line
                extrasLastMs[name] = now
                flightLog?.line("$name $line")
            }
        })
    }

    fun closeFlightLog() {
        onObstacle = null
        KeyManager.getInstance().cancelListen(extrasHolder)
        try {
            PerceptionManager.getInstance().removeObstacleDataListener(obstacleListener)
        } catch (e: Exception) {
            Log.w(TAG, "Removing the obstacle listener failed: ${e.message}")
        }
        val log = flightLog ?: return
        flightLog = null
        log.close()
        display.log("Obstacle data updates: $obstacleUpdates. Flight log: ${log.path ?: "could not be written"}")
    }

    /** Writes the nearest obstacle at most five times a second, and only when it changes. */
    private fun logObstacles(data: ObstacleData) {
        obstacleUpdates++
        val now = System.currentTimeMillis()
        if (now - lastObstacleLogMs < OBSTACLE_LOG_PERIOD_MS) return
        val horizontal = data.horizontalObstacleDistance.orEmpty()
        val nearest = horizontal.withIndex().filter { it.value > 0 }.minByOrNull { it.value }
        val line = "obstacle nearest_mm=${nearest?.value} sector_deg=${nearest?.let { it.index * data.horizontalAngleInterval }} " +
            "up_mm=${data.upwardObstacleDistance} down_mm=${data.downwardObstacleDistance}"
        if (line == lastObstacleLine) return
        lastObstacleLine = line
        lastObstacleLogMs = now
        flightLog?.line(line)
    }

    /** A setting read through a callback, as text: the value, or why it could not be read. */
    private fun <T> readSetting(read: (SettingResult<T>) -> Unit): String {
        val result = SettingResult<T>()
        read(result)
        return if (result.latch.await(1000, TimeUnit.MILLISECONDS)) result.text else "timeout"
    }

    private class SettingResult<T> {
        val latch = CountDownLatch(1)
        @Volatile var text = "no answer"

        fun <V> asCallback() = object : CommonCallbacks.CompletionCallbackWithParam<V> {
            override fun onSuccess(t: V?) { text = t.toString(); latch.countDown() }
            override fun onFailure(e: IDJIError) { text = "failed: ${e.errorCode() ?: e}"; latch.countDown() }
        }
    }

    // ---------------------------------------------------------------- mission safety

    /** Clears STOP and any earlier loss of control before a new mission. */
    fun arm() {
        aborted = false
        takenBack = null
    }

    /** STOP: centres the sticks now; the mission throws at its next check. */
    fun abort() {
        aborted = true
        zeroSticks()
    }

    val isAborted: Boolean get() = aborted

    fun checkAbort() {
        if (aborted) {
            zeroSticks()
            throw IllegalStateException("aborted by user")
        }
        takenBack?.let { throw ControlTakenBack(it) }
    }

    /** Shows the next mission step, unless the mission has to stop. */
    fun step(msg: String) {
        checkAbort()
        display.status(msg)
    }

    fun log(msg: String) = display.log(msg)

    fun progress(msg: String) = display.progress(msg)

    fun sleep(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            checkAbort()
            Thread.sleep(50)
        }
    }

    fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            checkAbort()
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("$what: timeout")
            Thread.sleep(200)
        }
    }

    // ---------------------------------------------------------------- flight phases

    /** Best-effort wait for the flight-controller link (fresh read, not the cache).
     *  We never hard-fail here: takeoffWithRetry() is the real guard against the
     *  "request_handler_not_found" that happens if takeoff is sent too early. */
    fun waitForAircraft() {
        step("Waiting for aircraft…")
        val readyDeadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < readyDeadline) {
            checkAbort()
            if (pull(FlightControllerKey.KeyConnection) == true) break
            Thread.sleep(300)
        }
        logLinkSnapshot("before takeoff")
    }

    /** Takes off and waits until auto-takeoff has finished its climb to ~1.2 m and settled. */
    fun takeOff() {
        step("Taking off…")
        takeoffWithRetry()
        waitUntil(20_000, "waiting for takeoff") { isFlying() }
        sleep(6_000)
    }

    /** Hands the sticks to the app, centred: the drone holds its position. */
    fun takeSticks() {
        step("Enabling virtual stick…")
        enableVirtualStick()
        stopAdvancedSticks()
        sticks.setVirtualStickAdvancedModeEnabled(false)
        zeroSticks()
    }

    /** Hands the sticks back and auto-lands, allowing about 1 m/s of descent from [fromAltM]. */
    fun land(fromAltM: Double = 0.0) {
        step("Landing…")
        disableVirtualStickQuietly()
        startLanding("start landing")
        awaitLanded(40_000 + (max(0.0, fromAltM) * 1000).toLong())
    }

    /**
     * Asks for the auto-landing, up to [LANDING_ATTEMPTS] times: right after the sticks are
     * handed back the drone may refuse the first request, and then it just hovers.
     */
    private fun startLanding(what: String) {
        // 21 Nov: a landing asked 0.1 s after the sticks were handed back was dropped by the
        // drone after 0.3 s, and it hovered; with ~1.4 s in between it landed.
        Thread.sleep(LANDING_SETTLE_MS)
        var attempt = 0
        while (true) {
            try {
                performAction(FlightControllerKey.KeyStartAutoLanding, what)
                flightLog?.line("landing_started attempt=${attempt + 1}")
                return
            } catch (e: Exception) {
                if (++attempt >= LANDING_ATTEMPTS || pull(FlightControllerKey.KeyIsFlying) == false) throw e
                flightLog?.line("landing_retry ${e.message}")
                Thread.sleep(500)
            }
        }
    }

    /**
     * Lands where the drone is. STOP and the failing mission may both call it: the second
     * caller waits for the first, then finds the drone landed. [recovering] stays true
     * meanwhile, so the diagnostics leave the status alone.
     */
    fun recoverAndLand() {
        synchronized(landingLock) {
            recovering = true
            try {
                zeroSticks()
                disableVirtualStickQuietly()
                // Only a sure "not flying" skips the landing: a read that fails still lands.
                if (pull(FlightControllerKey.KeyIsFlying) != false) {
                    val altitude = pull(FlightControllerKey.KeyAircraftLocation3D)?.altitude ?: UNKNOWN_ALTITUDE_M
                    startLanding("emergency landing")
                    awaitLanded(40_000 + (max(0.0, altitude) * 1000).toLong())
                    display.status("Landed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed", e)
                display.status("Auto-landing failed: ${e.message}\nTAKE OVER WITH THE RC.")
            } finally {
                recovering = false
            }
        }
    }

    /** Lets go after a failure: centred sticks, then the RC has control again. */
    fun letGo() {
        zeroSticks()
        disableVirtualStickQuietly()
    }

    private fun awaitLanded(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        // A drone that stops sinking (landing cancelled or never begun) gets asked again.
        var lowest = pull(FlightControllerKey.KeyAltitude, 1000) ?: Double.MAX_VALUE
        var lowestAtMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            pull(FlightControllerKey.KeyAltitude, 1000)?.let { alt ->
                if (alt < lowest - 0.1) { lowest = alt; lowestAtMs = System.currentTimeMillis() }
            }
            if (System.currentTimeMillis() - lowestAtMs > LANDING_STALL_MS) {
                flightLog?.line("landing_stalled alt=${"%.2f".format(java.util.Locale.US, lowest)}")
                try { startLanding("landing again") } catch (e: Exception) { Log.w(TAG, "Landing again failed: ${e.message}") }
                lowestAtMs = System.currentTimeMillis()
            }
            // Landing protection may ask for confirmation ~0.7 m above ground
            if (pull(FlightControllerKey.KeyIsLandingConfirmationNeeded) == true) {
                try {
                    performAction(FlightControllerKey.KeyConfirmLanding, "confirm landing")
                } catch (e: Exception) {
                    Log.w(TAG, "Confirm landing failed: ${e.message}")
                }
            }
            if (!isFlying()) return
            Thread.sleep(500)
        }
        throw IllegalStateException("Landing did not complete within ${timeoutMs / 1000} s")
    }

    /** Retry takeoff: right after connect the FC may briefly reject with request_handler_not_found. */
    private fun takeoffWithRetry() {
        var last: Exception? = null
        repeat(5) { attempt ->
            checkAbort()
            try {
                performAction(FlightControllerKey.KeyStartTakeoff, "takeoff")
                return
            } catch (e: Exception) {
                last = e
                display.log("Takeoff attempt ${attempt + 1} failed: ${e.message}")
                display.status("Takeoff not ready, retrying (${attempt + 1}/5)…")
                sleep(2_000)
            }
        }
        logLinkSnapshot("takeoff failed")
        val motorErr = describe(FlightControllerKey.KeyMotorStartFailureError, 1500)
        val mode = describe(FlightControllerKey.KeyFlightModeString, 1500)
        throw IllegalStateException(
            "Takeoff failed after 5 tries.\nReason from drone: $motorErr\nFlight mode: $mode\n${last?.message}")
    }

    // ---------------------------------------------------------------- sticks

    /** Centres the sticks; in the advanced mode, asks for zero velocity: the drone hovers. */
    fun zeroSticks() {
        sticks.leftStick.horizontalPosition = 0
        sticks.leftStick.verticalPosition = 0
        sticks.rightStick.horizontalPosition = 0
        sticks.rightStick.verticalPosition = 0
        synchronized(advancedLock) { if (advancedParam != null) advancedParam = advancedHover() }
    }

    // ---------------------------------------------------------------- advanced virtual sticks

    // The advanced mode wants its command again and again; a sender thread repeats the last
    // one every control period, so the drone also gets it while a mission sleeps between legs.
    @Volatile private var advancedParam: VirtualStickFlightControlParam? = null
    private var advancedSender: Thread? = null
    private val advancedLock = Any()   // STOP's hover must not bring back a mode that just ended

    /** True while the advanced mode steers: [driveTo] then sends velocities, not sticks. */
    val usesAdvancedSticks: Boolean get() = advancedParam != null

    /** After [takeSticks]: switches to the advanced mode and hovers. Ends with [land] or [letGo]. */
    fun useAdvancedSticks() {
        step("Enabling advanced virtual stick…")
        sticks.setVirtualStickAdvancedModeEnabled(true)
        advancedParam = advancedHover()
        advancedSender = thread(name = "advanced sticks", isDaemon = true) {
            // 21 Nov: twice the drone stood still for the rest of the figure with no sign why;
            // the log now shows whether this thread keeps sending, and anything that stops it.
            var sent = 0L
            var failures = 0L
            var why = "mode ended"
            try {
                while (true) {
                    val p = advancedParam ?: break
                    if (takenBack != null) { why = "control taken back"; break }   // command nothing once control is gone
                    if (!appHoldsSticks) { why = "sticks handed back"; break }
                    try {
                        sticks.sendVirtualStickAdvancedParam(p)
                        sent++
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        if (failures++ < 5) flightLog?.line("advanced_send_failed ${e.javaClass.simpleName} ${e.message}")
                    }
                    if (sent % 10 == 0L) flightLog?.line(String.format(java.util.Locale.US,
                        "advanced_sender sent=%d failed=%d pitch=%.2f roll=%.2f throttle=%.2f",
                        sent, failures, p.pitch ?: Double.NaN, p.roll ?: Double.NaN, p.verticalThrottle ?: Double.NaN))
                    Thread.sleep(CONTROL_PERIOD_MS)
                }
            } catch (e: InterruptedException) {
                why = "interrupted"
            } catch (e: Throwable) {
                why = "crashed: $e"
            }
            flightLog?.line("advanced_sender stopped sent=$sent failed=$failures why=$why")
        }
    }

    /**
     * Asks for [north], [east] and [up] m/s over the ground, without turning (yaw rate 0).
     * Axes as MSDK documents them for the ground frame: roll is north, pitch is east.
     * Not checked on a flight yet; [driveTo] stops the mission if the drone flies another way.
     */
    fun sendVelocity(north: Double, east: Double, up: Double) = synchronized(advancedLock) {
        if (advancedParam == null) return
        advancedParam = VirtualStickFlightControlParam(east, north, 0.0, up,
            VerticalControlMode.VELOCITY, RollPitchControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, FlightCoordinateSystem.GROUND)
    }

    private fun advancedHover() = VirtualStickFlightControlParam(0.0, 0.0, 0.0, 0.0,
        VerticalControlMode.VELOCITY, RollPitchControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, FlightCoordinateSystem.GROUND)

    private fun stopAdvancedSticks() {
        if (advancedParam == null && advancedSender == null) return
        synchronized(advancedLock) { advancedParam = null }
        advancedSender?.join(2 * CONTROL_PERIOD_MS)
        advancedSender = null
        sticks.setVirtualStickAdvancedModeEnabled(false)
    }

    private fun enableVirtualStick() {
        val latch = CountDownLatch(1)
        var error: IDJIError? = null
        appWasInControl = false
        sticks.setVirtualStickStateListener(stickListener)
        sticks.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(e: IDJIError) { error = e; latch.countDown() }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("enable virtual stick: timeout")
        error?.let { throw IllegalStateException("enable virtual stick: $it") }
        appHoldsSticks = true
    }

    private fun disableVirtualStickQuietly() {
        stopAdvancedSticks()
        appHoldsSticks = false   // from here on, losing control is our own doing
        val latch = CountDownLatch(1)
        sticks.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(e: IDJIError) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- lights

    /**
     * Switches the drone's own LEDs, the "pen" of the drawing missions. If the drone refuses,
     * the flight carries on: the hops between the pieces then show in the photo.
     */
    fun setLights(on: Boolean) {
        KeyManager.getInstance().setValue(
            KeyTools.createKey(FlightControllerKey.KeyLEDsSettings),
            LEDsSettings(on, on, on, on),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = logOutcome("lights", "Switching the lights", "ok")
                override fun onFailure(e: IDJIError) = logOutcome("lights", "Switching the lights", "failed: ${e.errorCode() ?: e}")
            })
    }

    /**
     * The white light under the drone. [AuxiliaryLightMode.AUTO] is the drone's default: on in
     * the dark below ~5 m, where it helps the downward cameras land.
     */
    fun setWhiteLight(mode: AuxiliaryLightMode) {
        KeyManager.getInstance().setValue(
            KeyTools.createKey(FlightAssistantKey.KeyBottomAuxiliaryLightMode),
            mode,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = logOutcome("white light", "Switching the white light", "ok")
                override fun onFailure(e: IDJIError) = logOutcome("white light", "Switching the white light", "failed: ${e.errorCode() ?: e}")
            })
    }

    /** At the end of a drawing, and when it fails: the pilot has to see the drone. */
    fun restoreLights() {
        setLights(true)
        setWhiteLight(AuxiliaryLightMode.AUTO)
        display.lightsRestored()
    }

    // ---------------------------------------------------------------- simulator

    /** Turns the DJI simulator on or off; [done] gets whether it worked. */
    fun setSimulator(on: Boolean, done: (Boolean) -> Unit) {
        val callback = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                simulatorOn = on
                display.log("Simulator ${if (on) "ON: flights are simulated" else "OFF: flights are REAL"}")
                done(true)
            }
            override fun onFailure(e: IDJIError) {
                display.log("Simulator ${if (on) "on" else "off"} failed: $e")
                done(false)
            }
        }
        if (on) {
            SimulatorManager.getInstance().enableSimulator(
                InitializationSettings.createInstance(LocationCoordinate2D(SIM_LATITUDE, SIM_LONGITUDE), SIM_SATELLITES),
                callback)
        } else {
            SimulatorManager.getInstance().disableSimulator(callback)
        }
    }

    /** The simulator does not survive a reconnect: fall back to "real flight". */
    fun disconnected() {
        simulatorOn = false
    }

    // ---------------------------------------------------------------- reads and actions

    fun isFlying(): Boolean = pull(FlightControllerKey.KeyIsFlying) == true

    fun heading(): Double? = pull(FlightControllerKey.KeyCompassHeading, 1000)

    /**
     * Fresh, blocking read of a key. MSDK v5's synchronous getValue() only returns
     * cached values (null until something subscribes), so we use the async getValue
     * with a latch to actually query the aircraft.
     */
    fun <T> pull(keyInfo: DJIKeyInfo<T>, timeoutMs: Long = 2500): T? = query(keyInfo, timeoutMs).first

    /** Like pull(), but shows why a read failed instead of a bare null. */
    fun <T> describe(keyInfo: DJIKeyInfo<T>, timeoutMs: Long = 2500): String {
        val (value, failure) = query(keyInfo, timeoutMs)
        return failure?.let { "✗ $it" } ?: value.toString()
    }

    /** Value plus failure reason (SDK error code or "timeout"); the reason is null on success. */
    private fun <T> query(keyInfo: DJIKeyInfo<T>, timeoutMs: Long): Pair<T?, String?> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>(null)
        val failure = AtomicReference<String?>(null)
        KeyManager.getInstance().getValue(
            KeyTools.createKey(keyInfo),
            object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(t: T?) { result.set(t); latch.countDown() }
                override fun onFailure(e: IDJIError) { failure.set(e.errorCode() ?: e.toString()); latch.countDown() }
            })
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) failure.set("timeout")
        // Reads run in tight loops, so only log when a key's outcome changes.
        logOutcome(keyInfo.identifier, "read ${keyInfo.identifier}", failure.get() ?: "ok")
        return result.get() to failure.get()
    }

    private fun logOutcome(key: String, what: String, outcome: String) {
        if (lastOutcome.put(key, outcome) != outcome) display.log("$what: $outcome")
    }

    /** Runs a no-argument flight-controller action and waits for its result. */
    private fun performAction(keyInfo: DJIActionKeyInfo<EmptyMsg, EmptyMsg>, what: String) {
        val latch = CountDownLatch(1)
        var error: IDJIError? = null
        KeyManager.getInstance().performAction(
            KeyTools.createKey(keyInfo),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) = latch.countDown()
                override fun onFailure(e: IDJIError) { error = e; latch.countDown() }
            })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("$what: timeout")
        error?.let { throw IllegalStateException("$what: $it") }
    }

    // ---------------------------------------------------------------- diagnostics

    /** The aircraft's state for the idle screen, so the pilot can see why takeoff is refused. */
    fun diagnostics(): String =
        "DRONE STATUS\n" +
            "RC connected: ${describe(RemoteControllerKey.KeyConnection, 1000)}\n" +
            "Air link: ${describe(AirLinkKey.KeyConnection, 1000)}\n" +
            "FC connected: ${describe(FlightControllerKey.KeyConnection, 1000)}\n" +
            "Product type: ${describe(ProductKey.KeyProductType, 1000)}\n" +
            "Aircraft: ${describe(FlightControllerKey.KeyAircraftName, 1000)}\n" +
            "Firmware: ${describe(FlightControllerKey.KeyFirmwareVersion, 1000)}\n" +
            "Flight mode: ${describe(FlightControllerKey.KeyFlightModeString, 1000)}\n" +
            "In air: ${describe(FlightControllerKey.KeyIsFlying, 1000)}   " +
            "Motors: ${describe(FlightControllerKey.KeyAreMotorsOn, 1000)}\n" +
            "GPS sats: ${describe(FlightControllerKey.KeyGPSSatelliteCount, 1000)}\n" +
            "Simulator: ${if (simulatorOn) "ON" else "off"}\n\n" +
            "Press a mission button to attempt takeoff."

    /** One log line with every link in the chain phone → RC → air link → flight controller. */
    fun logLinkSnapshot(why: String) {
        display.log("Link snapshot ($why): " +
            "product=${describe(ProductKey.KeyConnection, 1000)}, " +
            "type=${describe(ProductKey.KeyProductType, 1000)}, " +
            "RC=${describe(RemoteControllerKey.KeyConnection, 1000)}, " +
            "airlink=${describe(AirLinkKey.KeyConnection, 1000)}, " +
            "FC=${describe(FlightControllerKey.KeyConnection, 1000)}, " +
            "firmware=${describe(ProductKey.KeyFirmwareVersion, 1000)}")
    }

    /** Logs every connect/disconnect of the individual links as it happens. */
    fun watchLinks() {
        if (watching) return
        watching = true
        watch(ProductKey.KeyConnection, "product link")
        watch(RemoteControllerKey.KeyConnection, "RC link")
        watch(AirLinkKey.KeyConnection, "air link")
        watch(FlightControllerKey.KeyConnection, "FC link")
        watch(ProductKey.KeyProductType, "product type")
    }

    private fun <T> watch(keyInfo: DJIKeyInfo<T>, label: String) {
        KeyManager.getInstance().listen(KeyTools.createKey(keyInfo), this,
            object : CommonCallbacks.KeyListener<T> {
                override fun onValueChange(oldValue: T?, newValue: T?) {
                    display.log("$label: $oldValue → $newValue")
                }
            })
    }

    /** Stops every listener this class registered. */
    fun release() {
        KeyManager.getInstance().cancelListen(this)
        sticks.removeVirtualStickStateListener(stickListener)
    }
}
