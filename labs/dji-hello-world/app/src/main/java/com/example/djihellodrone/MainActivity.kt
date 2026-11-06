package com.example.djihellodrone

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import java.util.concurrent.atomic.AtomicReference
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthority
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import dji.v5.manager.interfaces.SDKManagerCallback
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mission 1 (demo): take off, fly ~1.5 m forward, rotate 180°, fly ~1.5 m back, land.
 *
 * Movement uses the *basic* virtual-stick API: emulated stick positions with the
 * exact semantics of the physical RC in mode 2 (right stick up = forward,
 * left stick sideways = rotate). Both legs steer on a measured position: the drone's
 * own velocity, added up from the take-off spot. That works on GPS outdoors and on the
 * vision sensors indoors. The 180° turn is closed-loop against the compass heading.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "DroneHello"

        // Stick deflection is -660..660 (= full stick). Keep values small!
        private const val FORWARD_STICK = 70        // ~10 % forward stick, gentle
        private const val FORWARD_MS = 1000L        // timed fallback only: ~1 s powered, coasts to ~1.5 m
        private const val LEG_M = 1.5               // length of each leg
        private const val LEG_MAX_MPS = 0.6         // top speed on a leg
        private const val LEG_ARRIVE_M = 0.15       // "reached the point"
        private const val LEG_TIMEOUT_MS = 15_000L
        private const val OBSTACLE_LOG_PERIOD_MS = 200L

        // Mission 3: a star or a triangle, upright across the nose or flat above the take-off
        // spot. Upright, the star's top is at bottom + 1.81 × radius: 3.0 m, under the 5 m allowed.
        private const val STAR_DIAMETER_M = 2.0     // the circle the tips lie on, for both figures
        private const val STAR_BOTTOM_ALT_M = 1.2   // upright: the lowest tips, about take-off height
        private const val STAR_FLAT_ALT_M = 1.2     // flat: the whole figure, at take-off height
        private const val STAR_STEP_DEG = 144.0     // each line of a star jumps two tips on
        private const val TRIANGLE_STEP_DEG = 120.0

        // Height correction: the height comes from the downward sensor, so it needs a flat floor.
        private const val HEIGHT_TIME_CONSTANT_S = 1.0  // how fast the tracked height follows the sensor
        private const val HEIGHT_MAX_JUMP_M = 0.4       // a bigger step is a table or a stair, not the drone
        private const val HEIGHT_MAX_SENSOR_M = 30.0    // readings above this mean "nothing measured"
        private const val HEIGHT_FIRM_BAND_M = 0.3      // a smaller height error gets its full correction
        private const val YAW_STICK = 200           // ~30 % rotation stick
        private const val YAW_TOLERANCE_DEG = 12.0
        private const val YAW_TIMEOUT_MS = 15000L

        // Mission 2 only starts after the pilot types this phrase.
        private const val CONFIRM_PHRASE = "yes, fly"

        // Mission 2 figure. The take-off spot is under its bottom-left corner.
        private const val LETTER_HEIGHT_M = 14.0    // capitals; the small letters are a third of that
        private const val BOTTOM_ALT_M = 10.0       // altitude of the figure's bottom edge
        private val FIGURE_WIDTH_M = FigurePath.widthInLetterHeights * LETTER_HEIGHT_M
        private val FIGURE_HEIGHT_M = FigurePath.heightInLetterHeights * LETTER_HEIGHT_M
        private const val SPEED_MPS = 1.5
        private const val LENS_PAUSE_MS = 5000L     // hover before and after each line
        private const val LIGHT_SWITCH_MS = 300L    // hover while the lights switch

        // Mission 2 steering. Tune these in the simulator, not in the air.
        private const val LOOKAHEAD_M = 1.0         // how far ahead on the line the drone aims
        private const val ARRIVE_M = 0.8            // "reached the last point"
        private const val CORNER_DEG = 60.0         // a sharper turn than this is flown as a corner…
        private const val CORNER_M = 0.4            // …the drone goes this close to it before turning…
        private const val CORNER_MIN_SPEED_MPS = 0.5    // …and slows down to this on the way in
        private const val APPROACH_GAIN = 0.8       // m/s per metre left to the last point
        private const val STICK_PER_MPS_HORIZONTAL = 55.0   // 660 = full stick ≈ 12 m/s
        private const val STICK_PER_MPS_VERTICAL = 130.0    // 660 = full stick ≈ 5 m/s
        private const val MAX_STICK = 300
        private const val CONTROL_PERIOD_MS = 100L
        private const val FOLLOW_TIMEOUT_MS = 6 * 60_000L
        private const val MAX_MISSED_READS = 5

        // Mission 2 safety.
        private const val MIN_SATELLITES = 10
        // The flight is ~800 m, about 12 minutes: from less than a full battery the drone
        // can reach its low-battery level and fly home on its own in the middle of the figure.
        private const val MIN_BATTERY_PERCENT = 90
        private const val BOX_MARGIN_M = 8.0        // leave the figure by more than this: stop and hover
        // The drone's own limits must leave room for the whole figure (top ~73 m, ~61 m across).
        private val MIN_HEIGHT_LIMIT_M = (BOTTOM_ALT_M + FIGURE_HEIGHT_M).roundToInt() + 5
        private val MIN_DISTANCE_LIMIT_M = FIGURE_WIDTH_M.roundToInt() + 10

        private const val METERS_PER_DEG = 111_320.0

        // Where the simulated drone stands (Albstadt-Ebingen).
        private const val SIM_LATITUDE = 48.2112
        private const val SIM_LONGITUDE = 9.0293
        private const val SIM_SATELLITES = 15
    }

    private lateinit var statusView: TextView
    private lateinit var goButton: Button
    private lateinit var mission2Button: Button
    private lateinit var mission3Button: Button
    private lateinit var simulatorCheck: CheckBox
    private lateinit var lightsCheck: CheckBox
    private lateinit var stopButton: Button
    private lateinit var logPanel: LogPanel

    private val lastOutcome = ConcurrentHashMap<String, String>()

    @Volatile private var flightLog: FlightLog? = null
    @Volatile private var obstacleUpdates = 0
    @Volatile private var lastObstacleLogMs = 0L
    private var lastObstacleLine = ""
    private val obstacleListener = ObstacleDataListener { data ->
        correctHeight(data)
        logObstacles(data)
    }

    // The tracker that follows the downward sensor; null when no flight uses it.
    @Volatile private var heightTracker: Tracker? = null
    private var lastHeightM = Double.NaN
    private var lastHeightNs = 0L
    @Volatile private var heightAccepted = 0
    @Volatile private var heightRejected = 0
    @Volatile private var watching = false

    @Volatile private var aborted = false
    @Volatile private var missionRunning = false
    @Volatile private var showDiagnostics = false
    @Volatile private var simulatorOn = false
    private val landingLock = Any()
    @Volatile private var recovering = false

    // The app steers only while the flight controller lets it. When the pilot moves a
    // stick or presses pause, or the drone decides to come home, control goes back and
    // the mission has to stop without commanding anything more, landing included.
    @Volatile private var appHoldsSticks = false
    @Volatile private var appWasInControl = false
    @Volatile private var takenBack: String? = null

    private class ControlTakenBack(why: String) : RuntimeException(why)

    private val stickListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            val appInControl = state.isVirtualStickEnable &&
                state.currentFlightControlAuthorityOwner == FlightControlAuthority.MSDK
            if (appInControl) appWasInControl = true
            // Only after the app has been seen in control: the first updates after
            // enabling can still show the old owner.
            else if (appHoldsSticks && appWasInControl) {
                noteTakenBack("${state.currentFlightControlAuthorityOwner} has control")
            }
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            logLine("Flight control changed hands: $reason")
            if (appHoldsSticks && reason != FlightControlAuthorityChangeReason.MSDK_REQUEST) {
                noteTakenBack(reason.toString())
            }
        }
    }

    private fun noteTakenBack(why: String) {
        if (takenBack == null) {
            takenBack = why
            logLine("Control taken back from the app: $why")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        goButton = findViewById(R.id.goButton)
        stopButton = findViewById(R.id.stopButton)
        logPanel = findViewById(R.id.logPanel)
        mission2Button = findViewById(R.id.mission2Button)
        mission3Button = findViewById(R.id.mission3Button)
        simulatorCheck = findViewById(R.id.simulatorCheck)
        simulatorCheck.setOnCheckedChangeListener { _, checked -> if (checked != simulatorOn) setSimulator(checked) }
        // A check on the table, before mission 2 relies on it: do the drone's lights obey?
        lightsCheck = findViewById(R.id.lightsCheck)
        lightsCheck.setOnCheckedChangeListener { _, checked -> setLights(checked) }
        setControlsEnabled(false)

        goButton.setOnClickListener { confirmAndFly() }
        mission2Button.setOnClickListener { confirmMission2() }
        mission3Button.setOnClickListener { confirmMission3() }
        stopButton.setOnClickListener { emergencyStop() }

        logLine("Drone Hello on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        // Surface ANY crash (any thread) on screen instead of silently closing.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "UNCAUGHT", e)
            val trace = e.stackTraceToString().take(1500)
            logLine("CRASH: $trace")
            runOnUiThread { statusView.text = "CRASH:\n$trace" }
            // keep the process alive long enough to read the screen
            try { Thread.sleep(60_000) } catch (_: InterruptedException) {}
        }

        setStatus("Initializing DJI SDK…")
        try {
            initSdk()
        } catch (e: Throwable) {
            Log.e(TAG, "initSdk failed", e)
            setStatus("SDK INIT FAILED:\n${e.stackTraceToString().take(1500)}")
        }

        startDiagnosticsLoop()
    }

    /** Continuously shows the aircraft's real state on screen while idle, so we can
     *  see why takeoff is refused. Pauses while a mission is running. */
    private fun startDiagnosticsLoop() {
        thread(name = "diagnostics", isDaemon = true) {
            while (true) {
                if (showDiagnostics && !missionRunning && !recovering) {
                    try { show(readDiagnostics()) } catch (_: Exception) {}
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun readDiagnostics(): String {
        val rcConn = describe(RemoteControllerKey.KeyConnection, 1000)
        val linkConn = describe(AirLinkKey.KeyConnection, 1000)
        val fcConn = describe(FlightControllerKey.KeyConnection, 1000)
        val type = describe(ProductKey.KeyProductType, 1000)
        val name = describe(FlightControllerKey.KeyAircraftName, 1000)
        val fw = describe(FlightControllerKey.KeyFirmwareVersion, 1000)
        val mode = describe(FlightControllerKey.KeyFlightModeString, 1000)
        val flying = describe(FlightControllerKey.KeyIsFlying, 1000)
        val motors = describe(FlightControllerKey.KeyAreMotorsOn, 1000)
        val sats = describe(FlightControllerKey.KeyGPSSatelliteCount, 1000)
        return "DRONE STATUS\n" +
            "RC connected: $rcConn\n" +
            "Air link: $linkConn\n" +
            "FC connected: $fcConn\n" +
            "Product type: $type\n" +
            "Aircraft: $name\n" +
            "Firmware: $fw\n" +
            "Flight mode: $mode\n" +
            "In air: $flying   Motors: $motors\n" +
            "GPS sats: $sats\n" +
            "Simulator: ${if (simulatorOn) "ON" else "off"}\n\n" +
            "Press a mission button to attempt takeoff."
    }

    // ---------------------------------------------------------------- SDK setup

    private fun initSdk() {
        SDKManager.getInstance().init(applicationContext, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                logLine("SDK init: $event ($totalProcess)")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    logLine("MSDK version ${SDKManager.getInstance().sdkVersion}")
                    setStatus("SDK initialized, registering app…")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                setStatus("App registered ✓\nWaiting for drone…\n(RC plugged in, drone on?)")
                watchLinks()
            }

            override fun onRegisterFailure(error: IDJIError?) {
                setStatus("Registration FAILED:\n$error\n\nCheck the API key in gradle.properties and internet connection.")
            }

            override fun onProductConnect(productId: Int) {
                logLine("Product connected, id=$productId")
                showDiagnostics = true
                setControlsEnabled(true)
                thread(name = "link-snapshot") { logLinkSnapshot("after product connect") }
            }

            override fun onProductDisconnect(productId: Int) {
                logLine("Product disconnected, id=$productId")
                showDiagnostics = false
                setStatus("Drone disconnected.")
                setControlsEnabled(false)
                // The simulator does not survive a reconnect: fall back to "real flight".
                simulatorOn = false
                runOnUiThread { simulatorCheck.isChecked = false }
            }

            override fun onProductChanged(productId: Int) {
                logLine("Product changed, id=$productId")
            }
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }

    // ---------------------------------------------------------------- mission

    private fun confirmAndFly() {
        if (missionRunning) return
        AlertDialog.Builder(this)
            .setTitle("Start mission 1 (demo)?")
            .setMessage("The drone will TAKE OFF, fly ~1.5 m forward, turn 180°, fly ~1.5 m back and land.\n\nMake sure you have ~4 m of clear space and GPS signal. Keep hands off the RC sticks (moving them takes control back).")
            .setPositiveButton("Fly") { _, _ -> startMission("Mission 1", hoverOnFailure = false) { runMission() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Mission 2 (light painting): the pilot must type [CONFIRM_PHRASE] before Fly unlocks. */
    private fun confirmMission2() {
        if (missionRunning) return
        val input = EditText(this).apply {
            hint = CONFIRM_PHRASE
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Start mission 2 (light painting, outdoors)?")
            .setMessage("The drone will TAKE OFF and draw \"Albstadt\", \"Informatik\" and a heart in the air, " +
                "one line under the other: about ${FIGURE_WIDTH_M.roundToInt()} m wide, " +
                "up to ${(BOTTOM_ALT_M + FIGURE_HEIGHT_M).roundToInt()} m high. Then it flies back and lands.\n\n" +
                "Place the drone with its nose pointing at the camera. Seen from the camera, " +
                "the figure appears above that spot and to the right of it.\n\n" +
                "Cover and uncover the lens when the status tells you to.\n\n" +
                "You need an open field, good GPS and a charged battery.\n\n" +
                (if (simulatorOn) "SIMULATOR IS ON: nothing really flies.\n\n" else "") +
                "Type \"$CONFIRM_PHRASE\" to unlock the flight.")
            .setView(input)
            .setPositiveButton("Fly") { _, _ -> startMission("Mission 2", hoverOnFailure = true) { runMission2() } }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        val fly = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fly.isEnabled = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Keyboards capitalise the first letter, so compare without case.
                fly.isEnabled = s.toString().trim().equals(CONFIRM_PHRASE, ignoreCase = true)
            }
        })
    }

    /** The figures of mission 3. Each line jumps [stepDeg] on; [lines] lines close the figure. */
    private enum class Figure(val label: String, val stepDeg: Double, val lines: Int) {
        STAR("star", STAR_STEP_DEG, 5),
        TRIANGLE("triangle", TRIANGLE_STEP_DEG, 3),
    }

    /** Mission 3: the pilot picks the figure, upright or flat, and the height correction. */
    private fun confirmMission3() {
        if (missionRunning) return
        val pad = (16 * resources.displayMetrics.density).roundToInt()
        fun radio(label: String) = RadioButton(this).apply { id = View.generateViewId(); text = label }
        fun group(vararg buttons: RadioButton) = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            buttons.forEach { addView(it) }
            check(buttons[0].id)
        }
        val star = radio("Star")
        val triangle = radio("Triangle")
        val upright = radio("Upright")
        val flat = radio("Flat")
        val heightCheck = CheckBox(this).apply { text = "Height correction from the downward sensor" }
        val message = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        fun figure() = if (triangle.isChecked) Figure.TRIANGLE else Figure.STAR
        fun update() { message.text = mission3Message(figure(), upright.isChecked, heightCheck.isChecked) }
        val shapes = group(star, triangle).apply { setOnCheckedChangeListener { _, _ -> update() } }
        val planes = group(upright, flat).apply { setOnCheckedChangeListener { _, _ -> update() } }
        heightCheck.setOnCheckedChangeListener { _, _ -> update() }
        update()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(shapes)
            addView(planes)
            addView(heightCheck)
            addView(message)
        }
        AlertDialog.Builder(this)
            .setTitle("Start mission 3 (star or triangle)?")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Fly") { _, _ ->
                val f = figure()
                val up = upright.isChecked
                val correction = heightCheck.isChecked
                startMission("Mission 3", hoverOnFailure = false) { runMission3(f, up, correction) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mission3Message(figure: Figure, upright: Boolean, heightCorrection: Boolean): String {
        val r = STAR_DIAMETER_M / 2
        val name = figure.label
        val text = StringBuilder()
        if (upright) {
            val top = STAR_BOTTOM_ALT_M + r * cos(Math.PI / figure.lines) + r
            val side = (0 until figure.lines).maxOf { k -> abs(r * sin(Math.toRadians(figure.stepDeg * k))) }
            text.append("The drone will TAKE OFF and draw a ${STAR_DIAMETER_M.roundToInt()} m $name standing upright " +
                "above the take-off spot, up to ${"%.1f".format(Locale.US, top)} m high. Then it flies back down and lands.\n\n" +
                "Place the drone with its nose pointing at the camera. The $name spreads about ${"%.1f".format(Locale.US, side)} m " +
                "to each side, and the drone can overshoot its tips: keep that clear, plus room to spare, and nothing overhead. ")
        } else {
            text.append("The drone will TAKE OFF and draw a ${STAR_DIAMETER_M.roundToInt()} m $name lying flat " +
                "at about ${"%.1f".format(Locale.US, STAR_FLAT_ALT_M)} m, centred above the take-off spot. Then it flies back and lands.\n\n" +
                "The $name spreads ${"%.0f".format(Locale.US, r)} m in every direction, and the drone can overshoot its tips: " +
                "keep that clear, plus room to spare. Film it from above or below. ")
        }
        if (heightCorrection) {
            text.append("With height correction, the drone holds its height on the downward sensor: " +
                "fly over a flat floor, with no tables or stairs below. ")
        }
        text.append("Stay out of the way at this height. Keep hands off the RC sticks (moving them takes control back).")
        return text.toString()
    }

    /** The mission buttons and the checkboxes: usable while connected and no mission flies. */
    private fun setControlsEnabled(enabled: Boolean) {
        runOnUiThread {
            goButton.isEnabled = enabled
            mission2Button.isEnabled = enabled
            mission3Button.isEnabled = enabled
            simulatorCheck.isEnabled = enabled
            lightsCheck.isEnabled = enabled
        }
    }

    /**
     * Runs [mission] on its own thread. If it fails, mission 1 lands on the spot;
     * with [hoverOnFailure] the drone stops and hovers instead, because it may be
     * far from the pilot and should not land blind.
     */
    private fun startMission(name: String, hoverOnFailure: Boolean, mission: () -> Unit) {
        missionRunning = true
        aborted = false
        takenBack = null
        setControlsEnabled(false)

        thread(name = "mission") {
            try {
                startFlightLog(name)
                mission()
                setStatus(if (aborted) "$name aborted — landed." else "$name complete ✓")
            } catch (e: ControlTakenBack) {
                // The pilot or the drone itself is flying now: let go and send nothing more.
                zeroSticks()
                disableVirtualStickQuietly()
                setStatus("$name stopped: control was taken back (${e.message}).\n" +
                    "The app has let go and will NOT land the drone. Fly it with the RC.")
            } catch (e: Exception) {
                Log.e(TAG, "$name failed", e)
                if (aborted) {
                    // STOP's own thread is normally landing the drone already; this waits for
                    // it. It still lands when STOP came during takeoff, before the drone
                    // counted as flying.
                    tryRecoverAndLand()
                } else if (hoverOnFailure) {
                    zeroSticks()
                    disableVirtualStickQuietly()
                    setStatus("$name FAILED: ${e.message}\n" +
                        "If the drone is in the air it is HOVERING: take over with the RC, or press STOP + LAND.")
                } else {
                    setStatus("$name FAILED: ${e.message}\nTrying to land…")
                    tryRecoverAndLand()
                }
            } finally {
                stopFlightLog()
                missionRunning = false
                setControlsEnabled(true)
            }
        }
    }

    // ---------------------------------------------------------------- flight log

    /** Opens the mission's log file, records the avoidance setting and listens to the obstacle data. */
    private fun startFlightLog(name: String) {
        obstacleUpdates = 0
        lastObstacleLogMs = 0L
        lastObstacleLine = ""
        val log = FlightLog(getExternalFilesDir(null), name.lowercase(Locale.US).replace(' ', '-'))
        flightLog = log
        // Logging must never stop a flight.
        try {
            val perception = PerceptionManager.getInstance()
            val type = readSetting<Any> { perception.getObstacleAvoidanceType(it.asCallback()) }
            val horizontal = readSetting<Any> {
                perception.getObstacleAvoidanceEnabled(PerceptionDirection.HORIZONTAL, it.asCallback())
            }
            logLine("Obstacle avoidance: type $type, horizontal $horizontal")
            log.line("avoidance type=$type horizontal=$horizontal")
            perception.addObstacleDataListener(obstacleListener)
        } catch (e: Exception) {
            logLine("Obstacle avoidance could not be read: $e")
        }
    }

    private fun stopFlightLog() {
        try {
            PerceptionManager.getInstance().removeObstacleDataListener(obstacleListener)
        } catch (e: Exception) {
            Log.w(TAG, "Removing the obstacle listener failed: ${e.message}")
        }
        val log = flightLog ?: return
        flightLog = null
        log.close()
        logLine("Obstacle data updates: $obstacleUpdates. Flight log: ${log.path ?: "could not be written"}")
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

    /**
     * Pulls the tracked height towards the downward sensor. The tracker misses slow climbs,
     * because the drone reports speeds under ~0.1 m/s as zero. A reading that jumps away
     * from the last good one is dropped until the sensor comes back to it.
     */
    private fun correctHeight(data: ObstacleData) {
        val tracker = heightTracker ?: return
        val measured = data.downwardObstacleDistance / 1000.0
        if (measured <= 0.0 || measured >= HEIGHT_MAX_SENSOR_M) return
        val reference = if (lastHeightM.isNaN()) tracker.position().third else lastHeightM
        if (abs(measured - reference) > HEIGHT_MAX_JUMP_M) {
            heightRejected++
            flightLog?.line(String.format(Locale.US, "height rejected sensor=%.2f reference=%.2f", measured, reference))
            return
        }
        val now = System.nanoTime()
        if (lastHeightNs != 0L) {
            val dt = (now - lastHeightNs) / 1e9
            val before = tracker.position().third
            val after = tracker.correctUp(measured, 1 - exp(-dt / HEIGHT_TIME_CONSTANT_S))
            flightLog?.line(String.format(Locale.US, "height sensor=%.2f before=%.2f after=%.2f", measured, before, after))
        }
        lastHeightM = measured
        lastHeightNs = now
        heightAccepted++
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

    /** Best-effort wait for the flight-controller link (fresh read, not the cache).
     *  We never hard-fail here: takeoffWithRetry() is the real guard against the
     *  "request_handler_not_found" that happens if takeoff is sent too early. */
    private fun waitForAircraft() {
        step("Waiting for aircraft…")
        val readyDeadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < readyDeadline) {
            checkAbort()
            if (pull(FlightControllerKey.KeyConnection) == true) break
            Thread.sleep(300)
        }
    }

    private fun runMission() {
        waitForAircraft()

        logLinkSnapshot("before takeoff")

        step("Checking GPS…")
        val sats = pull(FlightControllerKey.KeyGPSSatelliteCount) ?: -1
        Log.i(TAG, "GPS satellites: $sats")
        // Low/zero GPS (e.g. indoors): the Mini holds position on its downward vision
        // sensors, exactly like DJI Fly does. Proceed, but warn — expect some drift.
        if (sats in 0..5) {
            step("Low GPS ($sats sat). Flying on vision positioning — keep it well-lit, hands ready on the sticks.")
            sleepChecked(3_000)
        }

        step("Taking off…")
        takeoffWithRetry()
        waitUntil(20_000, "waiting for takeoff") { isFlying() }
        sleepChecked(6_000)   // let auto-takeoff finish its climb to ~1.2 m and settle

        step("Enabling virtual stick…")
        enableVirtualStick()
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        zeroSticks()

        // From here on, (0, 0) is the spot above the take-off point.
        val tracker = Tracker()
        tracker.start()
        try {
            sleepChecked(1_000)
            val start = heading()
            if (tracker.updates == 0 || start == null) {
                logLine("No velocity or heading from the drone: flying the legs on time instead.")
                flyTimedLegs()
            } else {
                val h = Math.toRadians(start)
                step("Flying ${LEG_M} m forward…")
                driveTo(tracker, LEG_M * cos(h), LEG_M * sin(h))
                sleepChecked(1_500)   // brake & settle

                step("Rotating 180°…")
                rotate180()
                sleepChecked(1_500)

                step("Flying back to the take-off spot…")
                driveTo(tracker, 0.0, 0.0)
                sleepChecked(1_000)
                val (n, e) = tracker.position()
                logLine("Back above the take-off spot, %.2f m off by the drone's own count".format(hypot(n, e)))
            }
        } finally {
            tracker.stop()
        }

        step("Landing…")
        disableVirtualStickQuietly()
        performAction(FlightControllerKey.KeyStartAutoLanding, "start landing")
        awaitLanded()
    }

    /** The old open-loop legs, for when the drone reports no velocity. */
    private fun flyTimedLegs() {
        step("Flying ~1.5 m forward…")
        VirtualStickManager.getInstance().rightStick.verticalPosition = FORWARD_STICK
        sleepChecked(FORWARD_MS)
        zeroSticks()
        sleepChecked(1_500)   // brake & settle

        step("Rotating 180°…")
        rotate180()
        sleepChecked(1_500)

        step("Flying ~1.5 m back…")
        VirtualStickManager.getInstance().rightStick.verticalPosition = FORWARD_STICK
        sleepChecked(FORWARD_MS)
        zeroSticks()
        sleepChecked(1_000)
    }

    /**
     * Where the drone is, in metres north and east of where tracking started and up
     * from [startUp]: its own velocity added up over time. The flight controller measures
     * that velocity on GPS or on the vision sensors, so this also works indoors. It
     * drifts slowly, which is fine for a flight of a few metres.
     */
    private class Tracker(startUp: Double = 0.0) {
        private var north = 0.0
        private var east = 0.0
        private var up = startUp
        private var last: Velocity3D? = null
        private var lastNs = 0L
        @Volatile var updates = 0

        fun start() {
            KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this,
                object : CommonCallbacks.KeyListener<Velocity3D> {
                    override fun onValueChange(oldValue: Velocity3D?, newValue: Velocity3D?) {
                        if (newValue != null) add(newValue)
                    }
                })
        }

        fun stop() = KeyManager.getInstance().cancelListen(this)

        // The key only reports changes, so the last velocity holds until the next one.
        @Synchronized private fun add(v: Velocity3D) {
            val now = System.nanoTime()
            advance(now)
            last = v
            lastNs = now
            updates++
        }

        /** The velocity last reported by the drone. */
        @Synchronized fun velocity(): Velocity3D? = last

        /** Moves the height a share [gain] of the way to [measured]. Returns the new height. */
        @Synchronized fun correctUp(measured: Double, gain: Double): Double {
            advance(System.nanoTime())
            up += (measured - up) * gain
            return up
        }

        /** North, east, up. */
        @Synchronized fun position(): Triple<Double, Double, Double> {
            advance(System.nanoTime())
            return Triple(north, east, up)
        }

        private fun advance(now: Long) {
            val v = last ?: return
            val dt = (now - lastNs) / 1e9
            north += (v.x ?: 0.0) * dt
            east += (v.y ?: 0.0) * dt
            up -= (v.z ?: 0.0) * dt   // z points down
            lastNs = now
        }
    }

    /**
     * Flies to ([north], [east]) on the tracked position, slowing down on the way in.
     * With [up] it also climbs or sinks to that height, on a straight line. With [firmHeight]
     * a small height error gets its full correction even early on a long level line, where
     * the straight-line share of the stick would be too small to move the drone.
     */
    private fun driveTo(tracker: Tracker, north: Double, east: Double, up: Double? = null, firmHeight: Boolean = false) {
        val deadline = System.currentTimeMillis() + LEG_TIMEOUT_MS
        val sticks = VirtualStickManager.getInstance()
        var h = heading() ?: 0.0
        var lastStepMs = System.currentTimeMillis()
        flightLog?.line(String.format(Locale.US, "leg target n=%.2f e=%.2f u=%s", north, east, up?.let { "%.2f".format(Locale.US, it) }))
        try {
            while (true) {
                checkAbort()
                val (n, e, u) = tracker.position()
                val dn = north - n
                val de = east - e
                val du = if (up != null) up - u else 0.0
                val dist = sqrt(dn * dn + de * de + du * du)
                if (dist < LEG_ARRIVE_M) {
                    flightLog?.line(String.format(Locale.US, "leg arrived dist=%.2f", dist))
                    return
                }
                if (System.currentTimeMillis() > deadline) {
                    logLine("Gave up %.2f m short of the point".format(dist))
                    flightLog?.line(String.format(Locale.US, "leg gave_up dist=%.2f", dist))
                    return
                }
                h = heading() ?: h
                val r = Math.toRadians(h)
                val scale = min(LEG_MAX_MPS, APPROACH_GAIN * dist) / dist
                // North/east to the drone's own forward/right.
                val forward = (dn * cos(r) + de * sin(r)) * scale
                val right = (-dn * sin(r) + de * cos(r)) * scale
                val stickForward = toStick(forward * STICK_PER_MPS_HORIZONTAL)
                val stickRight = toStick(right * STICK_PER_MPS_HORIZONTAL)
                val climb = if (firmHeight && abs(du) < HEIGHT_FIRM_BAND_M) du * APPROACH_GAIN else du * scale
                val stickUp = toStick(climb * STICK_PER_MPS_VERTICAL)
                sticks.rightStick.verticalPosition = stickForward
                sticks.rightStick.horizontalPosition = stickRight
                sticks.leftStick.verticalPosition = stickUp
                val v = tracker.velocity()
                val now = System.currentTimeMillis()
                flightLog?.line(String.format(Locale.US,
                    "step dt_ms=%d n=%.2f e=%.2f u=%.2f dist=%.2f heading=%.1f stick_fwd=%d stick_right=%d stick_up=%d vn=%.2f ve=%.2f vd=%.2f updates=%d",
                    now - lastStepMs, n, e, u, dist, h, stickForward, stickRight, stickUp,
                    v?.x ?: Double.NaN, v?.y ?: Double.NaN, v?.z ?: Double.NaN, tracker.updates))
                lastStepMs = now
                Thread.sleep(CONTROL_PERIOD_MS)
            }
        } finally {
            zeroSticks()
        }
    }

    /** Closed-loop yaw: rotate until compass heading has moved ~180° from start. */
    private fun rotate180() {
        val start = heading() ?: run {
            // No heading available — fall back to a timed turn (~4 s at ~45°/s)
            VirtualStickManager.getInstance().leftStick.horizontalPosition = YAW_STICK
            sleepChecked(4_000)
            zeroSticks()
            return
        }
        val target = normalizeDeg(start + 180.0)
        VirtualStickManager.getInstance().leftStick.horizontalPosition = YAW_STICK
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

    private fun awaitLanded(timeoutMs: Long = 40_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Landing protection may ask for confirmation ~0.7 m above ground
            val needsConfirm = pull(FlightControllerKey.KeyIsLandingConfirmationNeeded) == true
            if (needsConfirm) {
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

    // ---------------------------------------------------------------- mission 3 (figures)

    /**
     * Mission 3: a star or a triangle with its tips on a circle of [STAR_DIAMETER_M].
     * [upright], it stands above the take-off spot in the plane across the nose, so a camera
     * in front of the nose sees it whole; its lowest tips are at [STAR_BOTTOM_ALT_M]. Flat,
     * it lies at [STAR_FLAT_ALT_M] centred above the take-off spot, with one tip ahead of
     * the nose, and a camera has to look down on it or up at it. With [heightCorrection]
     * the height follows the downward sensor; the log records all three choices.
     */
    private fun runMission3(figure: Figure, upright: Boolean, heightCorrection: Boolean) {
        val r = STAR_DIAMETER_M / 2
        val plane = if (upright) "upright" else "flat"
        val correction = if (heightCorrection) "on" else "off"
        logLine("Figure: ${figure.label}, $plane, height correction $correction")
        flightLog?.line("figure ${figure.label} $plane")
        flightLog?.line("height_correction $correction")
        if (upright) {
            val centreAlt = STAR_BOTTOM_ALT_M + r * cos(Math.PI / figure.lines)
            // Sideways is the drone's right, so north/east = side × (-sin h, cos h).
            runFigure(figure.label, figure.stepDeg, figure.lines, STAR_BOTTOM_ALT_M, heightFromSensor = heightCorrection) { h, a ->
                val side = r * sin(a)
                Triple(-side * sin(h), side * cos(h), centreAlt + r * cos(a))
            }
        } else {
            runFigure(figure.label, figure.stepDeg, figure.lines, STAR_FLAT_ALT_M, heightFromSensor = heightCorrection) { h, a ->
                Triple(r * cos(h + a), r * sin(h + a), STAR_FLAT_ALT_M)
            }
        }
    }

    /**
     * Takes off, draws a closed figure and lands. Steered on the [Tracker] like mission 1,
     * so it flies indoors and outdoors. The lights are on while the figure is drawn. Each
     * of the [lines] jumps [stepDeg] on. [tip] gives north, east and height of a point from
     * the start heading and the point's angle, both in radians; the figure ends at
     * [endAlt] above the take-off spot. With [heightFromSensor] the height follows the
     * downward sensor while the figure is flown, which only suits a flat figure.
     */
    private fun runFigure(
        name: String, stepDeg: Double, lines: Int, endAlt: Double, heightFromSensor: Boolean = false,
        tip: (Double, Double) -> Triple<Double, Double, Double>,
    ) {
        waitForAircraft()
        logLinkSnapshot("before takeoff")

        step("Taking off…")
        takeoffWithRetry()
        waitUntil(20_000, "waiting for takeoff") { isFlying() }
        sleepChecked(6_000)   // let auto-takeoff finish its climb to ~1.2 m and settle

        step("Enabling virtual stick…")
        enableVirtualStick()
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        zeroSticks()

        // Height above the take-off spot, from the barometer; the tracker carries it on.
        val tracker = Tracker(pull(FlightControllerKey.KeyAltitude) ?: STAR_BOTTOM_ALT_M)
        tracker.start()
        if (heightFromSensor) {
            lastHeightM = Double.NaN
            lastHeightNs = 0L
            heightAccepted = 0
            heightRejected = 0
            heightTracker = tracker
        }
        try {
            sleepChecked(1_000)
            val start = heading()
            if (tracker.updates == 0 || start == null) {
                throw IllegalStateException("No velocity or heading from the drone: cannot draw the $name.")
            }
            val h = Math.toRadians(start)
            val tips = (0..lines).map { k -> tip(h, Math.toRadians(stepDeg * k)) }

            step("Flying to the first point of the $name…")
            setLights(false)
            driveTo(tracker, tips[0].first, tips[0].second, tips[0].third, heightFromSensor)
            sleepChecked(1_000)

            setLights(true)
            sleepChecked(LIGHT_SWITCH_MS)
            for (k in 1..lines) {
                step("Drawing the $name, line $k of $lines…")
                driveTo(tracker, tips[k].first, tips[k].second, tips[k].third, heightFromSensor)
            }
            setLights(false)
            sleepChecked(LIGHT_SWITCH_MS)

            step("Flying back to the take-off spot…")
            driveTo(tracker, 0.0, 0.0, endAlt, heightFromSensor)
            sleepChecked(1_000)
            val (n, e) = tracker.position()
            logLine("Back above the take-off spot, %.2f m off by the drone's own count".format(hypot(n, e)))
        } finally {
            if (heightFromSensor) {
                heightTracker = null
                logLine("Height sensor readings: $heightAccepted used, $heightRejected dropped")
            }
            tracker.stop()
            setLights(true)   // also when the mission fails: the pilot has to see the drone
            runOnUiThread { lightsCheck.isChecked = true }
        }

        step("Landing…")
        disableVirtualStickQuietly()
        performAction(FlightControllerKey.KeyStartAutoLanding, "start landing")
        awaitLanded()
    }

    // ---------------------------------------------------------------- mission 2 (light painting)

    private fun runMission2() {
        waitForAircraft()
        logLinkSnapshot("before takeoff")

        // Unlike mission 1 this flight has no vision fallback: it is steered on GPS only.
        step("Checking GPS and battery…")
        val sats = pull(FlightControllerKey.KeyGPSSatelliteCount) ?: -1
        if (sats < MIN_SATELLITES) throw IllegalStateException("GPS too weak: $sats satellites, need $MIN_SATELLITES.")
        val battery = pull(BatteryKey.KeyChargeRemainingInPercent) ?: -1
        if (battery < MIN_BATTERY_PERCENT) {
            // The simulator spends no battery, so a test run on the desk needs no full one.
            if (simulatorOn) logLine("Battery $battery %: fine for the simulator, a real flight needs $MIN_BATTERY_PERCENT %.")
            else throw IllegalStateException("Battery too low: $battery %, need $MIN_BATTERY_PERCENT %.")
        }
        logLine("GPS $sats satellites, battery $battery %")

        // The drone's own limits stop it short of the figure, and the app would keep
        // pushing against them. A failed read only warns: the setting may simply be unreadable.
        step("Checking the height and distance limits…")
        val heightLimit = pull(FlightControllerKey.KeyHeightLimit)
        if (heightLimit == null) logLine("WARNING: could not read the height limit, need $MIN_HEIGHT_LIMIT_M m")
        else if (heightLimit < MIN_HEIGHT_LIMIT_M) {
            throw IllegalStateException("Height limit is $heightLimit m, the figure needs $MIN_HEIGHT_LIMIT_M m. Raise it in DJI Fly.")
        }
        if (pull(FlightControllerKey.KeyDistanceLimitEnabled) == true) {
            val distanceLimit = pull(FlightControllerKey.KeyDistanceLimit)
            if (distanceLimit == null) logLine("WARNING: could not read the distance limit, need $MIN_DISTANCE_LIMIT_M m")
            else if (distanceLimit < MIN_DISTANCE_LIMIT_M) {
                throw IllegalStateException("Distance limit is $distanceLimit m, the figure needs $MIN_DISTANCE_LIMIT_M m. Raise or switch it off in DJI Fly.")
            }
        }
        logLine("Height limit ${heightLimit ?: "?"} m")

        step("Taking off…")
        takeoffWithRetry()
        waitUntil(20_000, "waiting for takeoff") { isFlying() }
        sleepChecked(6_000)   // let auto-takeoff finish its climb to ~1.2 m and settle

        // The take-off spot is under the figure's bottom-left corner; the nose points at the camera.
        val origin = pull(FlightControllerKey.KeyAircraftLocation3D)
        val originLat = origin?.latitude
        val originLon = origin?.longitude
        val heading = heading()
        if (originLat == null || originLon == null || heading == null) {
            throw IllegalStateException("No position or heading from the drone after takeoff.")
        }
        val frame = FigureFrame(originLat, originLon, heading)

        step("Enabling virtual stick…")
        enableVirtualStick()
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        zeroSticks()
        sleepChecked(500)

        // A line of the figure is its pieces. Each piece is drawn with the lights on,
        // and the drone flies from one piece to the next with them off, like a pen
        // that lifts. The lens is covered while it flies from one line to the next.
        val lines = FigurePath.lines(LETTER_HEIGHT_M)
        try {
            for ((index, pieces) in lines.withIndex()) {
                val line = "line ${index + 1} of ${lines.size}"

                step("Flying to the start of $line…")
                setLights(false)
                follow(frame, listOf(pieces.first().first()))

                // The drone holds its own position while the sticks are centred.
                step("UNCOVER THE LENS.\nDrawing $line in ${LENS_PAUSE_MS / 1000} s.")
                sleepChecked(LENS_PAUSE_MS)

                step("Drawing $line…")
                for ((n, piece) in pieces.withIndex()) {
                    follow(frame, listOf(piece.first()))
                    setLights(true)
                    sleepChecked(LIGHT_SWITCH_MS)
                    follow(frame, piece) { percent -> show("Drawing $line, part ${n + 1} of ${pieces.size}… $percent %") }
                    setLights(false)
                    sleepChecked(LIGHT_SWITCH_MS)
                }

                step("COVER THE LENS.\nMoving on in ${LENS_PAUSE_MS / 1000} s.")
                sleepChecked(LENS_PAUSE_MS)
            }
        } finally {
            setLights(true)   // also when the mission fails: the pilot has to see the drone
            runOnUiThread { lightsCheck.isChecked = true }
        }

        step("Flying back to the take-off spot…")
        follow(frame, listOf(PathPoint(0.0, lines.last().last().last().y)))
        sleepChecked(1_500)   // brake & settle

        step("Landing…")
        disableVirtualStickQuietly()
        performAction(FlightControllerKey.KeyStartAutoLanding, "start landing")
        awaitLanded()
    }

    /** Where the drone is in the figure: [x] and [alt] in the drawing plane, [depth] out of it. */
    private class Position(val x: Double, val alt: Double, val depth: Double)

    /**
     * The figure stands in a vertical plane through the take-off spot, seen from the
     * camera the nose points at: x runs to the camera's right (the drone's left),
     * depth is towards the camera, altitude is above the take-off spot.
     */
    private class FigureFrame(val originLat: Double, val originLon: Double, headingDeg: Double) {
        private val h = Math.toRadians(headingDeg)

        fun locate(lat: Double, lon: Double, alt: Double): Position {
            val north = (lat - originLat) * METERS_PER_DEG
            val east = (lon - originLon) * METERS_PER_DEG * cos(Math.toRadians(originLat))
            val forward = north * cos(h) + east * sin(h)
            val right = -north * sin(h) + east * cos(h)
            return Position(x = -right, alt = alt, depth = forward)
        }
    }

    /**
     * Flies along [points], aiming a short distance ahead on the line and steering
     * on the measured position, not on time. Returns at the last point.
     */
    private fun follow(frame: FigureFrame, points: List<PathPoint>, onProgress: (Int) -> Unit = {}) {
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
                if (aim == points.lastIndex && left < ARRIVE_M) return
                // Constant speed along the line; slow down into corners and for the last point.
                val speed = when {
                    aim == points.lastIndex -> min(SPEED_MPS, APPROACH_GAIN * left)
                    isCorner(points, aim) -> min(SPEED_MPS, max(CORNER_MIN_SPEED_MPS, APPROACH_GAIN * left))
                    else -> SPEED_MPS
                }
                steer(here, points[aim], speed)
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
    private fun readPosition(frame: FigureFrame): Position {
        repeat(MAX_MISSED_READS) {
            val loc = pull(FlightControllerKey.KeyAircraftLocation3D, 400)
            val lat = loc?.latitude
            val lon = loc?.longitude
            val alt = loc?.altitude
            if (lat != null && lon != null && alt != null) {
                val here = frame.locate(lat, lon, alt)
                val inside = here.x in -BOX_MARGIN_M..(FIGURE_WIDTH_M + BOX_MARGIN_M) &&
                    abs(here.depth) <= BOX_MARGIN_M &&
                    here.alt <= BOTTOM_ALT_M + FIGURE_HEIGHT_M + BOX_MARGIN_M
                if (!inside) {
                    throw IllegalStateException(
                        "left the flight box (x %.1f m, depth %.1f m, altitude %.1f m)".format(here.x, here.depth, here.alt))
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
        hypot(target.x - here.x, BOTTOM_ALT_M + target.y - here.alt)

    /** One control step: sets the sticks to move towards [target] at [speedMps]. */
    private fun steer(here: Position, target: PathPoint, speedMps: Double) {
        val dx = target.x - here.x
        val dz = BOTTOM_ALT_M + target.y - here.alt
        val dist = hypot(dx, dz)
        val scale = if (dist > 0.01) speedMps / dist else 0.0
        val sticks = VirtualStickManager.getInstance()
        // x grows to the drone's LEFT (the camera's right), so +x is a negative right-stick move.
        sticks.rightStick.horizontalPosition = toStick(-dx * scale * STICK_PER_MPS_HORIZONTAL)
        sticks.leftStick.verticalPosition = toStick(dz * scale * STICK_PER_MPS_VERTICAL)
        // Over a long flight the drone creeps towards or away from the camera; push it back gently.
        sticks.rightStick.verticalPosition =
            toStick((-APPROACH_GAIN * here.depth).coerceIn(-1.0, 1.0) * STICK_PER_MPS_HORIZONTAL)
    }

    private fun toStick(value: Double): Int = value.roundToInt().coerceIn(-MAX_STICK, MAX_STICK)

    // ---------------------------------------------------------------- lights

    /**
     * Switches the drone's own LEDs, the "pen" of mission 2. If the drone refuses,
     * the flight carries on: the hops between the pieces then show in the photo.
     */
    private fun setLights(on: Boolean) {
        KeyManager.getInstance().setValue(
            KeyTools.createKey(FlightControllerKey.KeyLEDsSettings),
            LEDsSettings(on, on, on, on),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = logLightsOutcome("ok")
                override fun onFailure(e: IDJIError) = logLightsOutcome("failed: ${e.errorCode() ?: e}")
            })
    }

    /** The lights switch many times per flight, so only log when the outcome changes. */
    private fun logLightsOutcome(outcome: String) {
        if (lastOutcome.put("lights", outcome) != outcome) logLine("Switching the lights: $outcome")
    }

    // ---------------------------------------------------------------- simulator

    /** Turns the DJI simulator on or off. With it on, the drone "flies" on the desk, props off. */
    private fun setSimulator(on: Boolean) {
        val callback = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                simulatorOn = on
                logLine("Simulator ${if (on) "ON: flights are simulated" else "OFF: flights are REAL"}")
            }
            override fun onFailure(e: IDJIError) {
                logLine("Simulator ${if (on) "on" else "off"} failed: $e")
                runOnUiThread { simulatorCheck.isChecked = simulatorOn }
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

    // ---------------------------------------------------------------- stop / recovery

    private fun emergencyStop() {
        aborted = true
        zeroSticks()
        // Also lands a drone that mission 2 left hovering after a failure.
        thread {
            if (isFlying()) {
                setStatus("STOP pressed — landing…")
                tryRecoverAndLand()
            }
        }
    }

    /**
     * Lands where the drone is. STOP and the failing mission may both call it: the second
     * caller waits for the first, then finds the drone landed. Waiting keeps the mission
     * "running", so the buttons stay off and the diagnostics leave the status alone.
     */
    private fun tryRecoverAndLand() {
        synchronized(landingLock) {
            recovering = true
            try {
                zeroSticks()
                disableVirtualStickQuietly()
                if (isFlying()) {
                    // Mission 2 can be 70 m up: allow about 1 m/s of descent on top of the usual 40 s.
                    val altitude = pull(FlightControllerKey.KeyAircraftLocation3D)?.altitude ?: BOTTOM_ALT_M + FIGURE_HEIGHT_M
                    performAction(FlightControllerKey.KeyStartAutoLanding, "emergency landing")
                    awaitLanded(40_000 + (max(0.0, altitude) * 1000).toLong())
                    setStatus("Landed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed", e)
                setStatus("Auto-landing failed: ${e.message}\nTAKE OVER WITH THE RC!")
            } finally {
                recovering = false
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Fresh, blocking read of a key. MSDK v5's synchronous getValue() only returns
     * cached values (null until something subscribes), so we use the async getValue
     * with a latch to actually query the aircraft.
     */
    private fun <T> pull(keyInfo: DJIKeyInfo<T>, timeoutMs: Long = 2500): T? = query(keyInfo, timeoutMs).first

    /** Like pull(), but shows why a read failed instead of a bare null. */
    private fun <T> describe(keyInfo: DJIKeyInfo<T>, timeoutMs: Long = 2500): String {
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
        val name = keyInfo.identifier
        val outcome = failure.get() ?: "ok"
        if (lastOutcome.put(name, outcome) != outcome) logLine("read $name: $outcome")
        return result.get() to failure.get()
    }

    /** One log line with every link in the chain phone → RC → air link → flight controller. */
    private fun logLinkSnapshot(why: String) {
        logLine("Link snapshot ($why): " +
            "product=${describe(ProductKey.KeyConnection, 1000)}, " +
            "type=${describe(ProductKey.KeyProductType, 1000)}, " +
            "RC=${describe(RemoteControllerKey.KeyConnection, 1000)}, " +
            "airlink=${describe(AirLinkKey.KeyConnection, 1000)}, " +
            "FC=${describe(FlightControllerKey.KeyConnection, 1000)}, " +
            "firmware=${describe(ProductKey.KeyFirmwareVersion, 1000)}")
    }

    /** Logs every connect/disconnect of the individual links as it happens. */
    private fun watchLinks() {
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
                    logLine("$label: $oldValue → $newValue")
                }
            })
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
                logLine("Takeoff attempt ${attempt + 1} failed: ${e.message}")
                setStatus("Takeoff not ready, retrying (${attempt + 1}/5)…")
                sleepChecked(2_000)
            }
        }
        logLinkSnapshot("takeoff failed")
        val motorErr = describe(FlightControllerKey.KeyMotorStartFailureError, 1500)
        val mode = describe(FlightControllerKey.KeyFlightModeString, 1500)
        throw IllegalStateException(
            "Takeoff failed after 5 tries.\nReason from drone: $motorErr\nFlight mode: $mode\n${last?.message}")
    }

    private fun isFlying(): Boolean = pull(FlightControllerKey.KeyIsFlying) == true

    private fun heading(): Double? = pull(FlightControllerKey.KeyCompassHeading, 1000)

    private fun zeroSticks() {
        VirtualStickManager.getInstance().leftStick.horizontalPosition = 0
        VirtualStickManager.getInstance().leftStick.verticalPosition = 0
        VirtualStickManager.getInstance().rightStick.horizontalPosition = 0
        VirtualStickManager.getInstance().rightStick.verticalPosition = 0
    }

    private fun normalizeDeg(a: Double): Double {
        var d = a % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private fun enableVirtualStick() {
        val latch = CountDownLatch(1)
        var error: IDJIError? = null
        appWasInControl = false
        VirtualStickManager.getInstance().setVirtualStickStateListener(stickListener)
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(e: IDJIError) { error = e; latch.countDown() }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("enable virtual stick: timeout")
        error?.let { throw IllegalStateException("enable virtual stick: $it") }
        appHoldsSticks = true
    }

    private fun disableVirtualStickQuietly() {
        appHoldsSticks = false   // from here on, losing control is our own doing
        val latch = CountDownLatch(1)
        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(e: IDJIError) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
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

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            checkAbort()
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("$what: timeout")
            Thread.sleep(200)
        }
    }

    private fun sleepChecked(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            checkAbort()
            Thread.sleep(50)
        }
    }

    private fun checkAbort() {
        if (aborted) {
            zeroSticks()
            throw IllegalStateException("aborted by user")
        }
        takenBack?.let { throw ControlTakenBack(it) }
    }

    private fun step(msg: String) {
        checkAbort()
        setStatus(msg)
    }

    private fun setStatus(msg: String) {
        logLine(msg)
        show(msg)
    }

    /** Updates the status text without adding to the log (used by the 2 s diagnostics refresh). */
    private fun show(msg: String) {
        runOnUiThread { statusView.text = msg }
    }

    private fun logLine(msg: String) {
        Log.i(TAG, msg)
        logPanel.append(msg)
    }

    override fun onDestroy() {
        KeyManager.getInstance().cancelListen(this)
        VirtualStickManager.getInstance().removeVirtualStickStateListener(stickListener)
        super.onDestroy()
    }
}
