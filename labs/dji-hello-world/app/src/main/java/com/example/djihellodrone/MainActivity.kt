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
import com.example.djihellodrone.Drone.Companion.TAG
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The one screen: a button per mission, STOP + LAND, the status and the log. The flying
 * happens in the [Mission]s on their own thread, through [Drone]; this class only starts
 * them, shows what they report and lets the pilot stop them.
 */
class MainActivity : Activity(), Display {

    private companion object {
        // Mission 2 only starts after the pilot types this phrase.
        const val CONFIRM_PHRASE = "yes, fly"
    }

    private lateinit var statusView: TextView
    private lateinit var mission1Button: Button
    private lateinit var mission2Button: Button
    private lateinit var mission3Button: Button
    private lateinit var simulatorCheck: CheckBox
    private lateinit var lightsCheck: CheckBox
    private lateinit var logPanel: LogPanel

    private val drone = Drone(this)
    @Volatile private var missionRunning = false
    @Volatile private var showDiagnostics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        logPanel = findViewById(R.id.logPanel)
        mission1Button = findViewById(R.id.mission1Button)
        mission2Button = findViewById(R.id.mission2Button)
        mission3Button = findViewById(R.id.mission3Button)
        simulatorCheck = findViewById(R.id.simulatorCheck)
        simulatorCheck.setOnCheckedChangeListener { _, checked ->
            if (checked != drone.simulatorOn) {
                drone.setSimulator(checked) { ok -> if (!ok) runOnUiThread { simulatorCheck.isChecked = drone.simulatorOn } }
            }
        }
        // A check on the table, before mission 2 relies on it: do the drone's lights obey?
        lightsCheck = findViewById(R.id.lightsCheck)
        lightsCheck.setOnCheckedChangeListener { _, checked -> drone.setLights(checked) }
        setControlsEnabled(false)

        mission1Button.setOnClickListener { confirmMission1() }
        mission2Button.setOnClickListener { confirmMission2() }
        mission3Button.setOnClickListener { confirmMission3() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { emergencyStop() }

        log("Drone Hello on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        // Surface ANY crash (any thread) on screen instead of silently closing.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "UNCAUGHT", e)
            val trace = e.stackTraceToString().take(1500)
            log("CRASH: $trace")
            runOnUiThread { statusView.text = "CRASH:\n$trace" }
            // keep the process alive long enough to read the screen
            try { Thread.sleep(60_000) } catch (_: InterruptedException) {}
        }

        status("Initializing DJI SDK…")
        try {
            initSdk()
        } catch (e: Throwable) {
            Log.e(TAG, "initSdk failed", e)
            status("SDK INIT FAILED:\n${e.stackTraceToString().take(1500)}")
        }

        startDiagnosticsLoop()
    }

    override fun onDestroy() {
        drone.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- display

    override fun log(msg: String) {
        Log.i(TAG, msg)
        logPanel.append(msg)
    }

    override fun status(msg: String) {
        log(msg)
        progress(msg)
    }

    override fun progress(msg: String) {
        runOnUiThread { statusView.text = msg }
    }

    override fun lightsRestored() {
        runOnUiThread { lightsCheck.isChecked = true }
    }

    /** Shows the aircraft's real state while idle, so the pilot can see why takeoff is refused. */
    private fun startDiagnosticsLoop() {
        thread(name = "diagnostics", isDaemon = true) {
            while (true) {
                if (showDiagnostics && !missionRunning && !drone.recovering) {
                    try { progress(drone.diagnostics()) } catch (_: Exception) {}
                }
                Thread.sleep(2000)
            }
        }
    }

    // ---------------------------------------------------------------- SDK setup

    private fun initSdk() {
        SDKManager.getInstance().init(applicationContext, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                log("SDK init: $event ($totalProcess)")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    log("MSDK version ${SDKManager.getInstance().sdkVersion}")
                    status("SDK initialized, registering app…")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                status("App registered ✓\nWaiting for drone…\n(RC plugged in, drone on?)")
                drone.watchLinks()
            }

            override fun onRegisterFailure(error: IDJIError?) {
                status("Registration FAILED:\n$error\n\nCheck the API key in gradle.properties and internet connection.")
            }

            override fun onProductConnect(productId: Int) {
                log("Product connected, id=$productId")
                showDiagnostics = true
                setControlsEnabled(true)
                thread(name = "link-snapshot") { drone.logLinkSnapshot("after product connect") }
            }

            override fun onProductDisconnect(productId: Int) {
                log("Product disconnected, id=$productId")
                showDiagnostics = false
                status("Drone disconnected.")
                setControlsEnabled(false)
                drone.disconnected()
                runOnUiThread { simulatorCheck.isChecked = false }
            }

            override fun onProductChanged(productId: Int) {
                log("Product changed, id=$productId")
            }
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }

    // ---------------------------------------------------------------- start dialogs

    private fun confirmMission1() {
        if (missionRunning) return
        val mission = DemoMission(drone)
        AlertDialog.Builder(this)
            .setTitle("Start mission 1 (demo)?")
            .setMessage(mission.briefing)
            .setPositiveButton("Fly") { _, _ -> startMission(mission) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Mission 2 (light painting): the pilot picks upright or flat and the whole picture or
     * one line, and must type [CONFIRM_PHRASE] before Fly unlocks.
     */
    private fun confirmMission2() {
        if (missionRunning) return
        val upright = radioButton("Upright")
        val flat = radioButton("Flat (camera below)")
        val full = radioButton("Full picture")
        val single = FigurePath.names.map { radioButton(it.replaceFirstChar { c -> c.uppercase() }) }
        val message = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        fun mission() = LightPaintingMission(drone, Drawing(
            if (flat.isChecked) Plane.FLAT else Plane.UPRIGHT,
            single.indexOfFirst { it.isChecked }.takeIf { it >= 0 }))
        fun update() {
            message.text = mission().briefing + "\n\n" +
                (if (drone.simulatorOn) "SIMULATOR IS ON: nothing really flies.\n\n" else "") +
                "Type \"$CONFIRM_PHRASE\" to unlock the flight."
        }
        val planes = radioGroup(RadioGroup.HORIZONTAL, listOf(upright, flat)) { update() }
        val parts = radioGroup(RadioGroup.VERTICAL, listOf(full) + single) { update() }
        update()
        val input = EditText(this).apply {
            hint = CONFIRM_PHRASE
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Start mission 2 (light painting, outdoors)?")
            .setView(dialogBody(planes, parts, message, input))
            .setPositiveButton("Fly") { _, _ -> startMission(mission()) }
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

    /** Mission 3: the pilot picks the figure, upright or flat, and the height correction. */
    private fun confirmMission3() {
        if (missionRunning) return
        val star = radioButton("Star")
        val triangle = radioButton("Triangle")
        val upright = radioButton("Upright")
        val flat = radioButton("Flat")
        val heightCheck = CheckBox(this).apply { text = "Height correction from the downward sensor" }
        val message = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        fun mission() = FigureMission(drone,
            if (triangle.isChecked) Figure.TRIANGLE else Figure.STAR,
            if (flat.isChecked) Plane.FLAT else Plane.UPRIGHT,
            heightCheck.isChecked)
        fun update() { message.text = mission().briefing }
        val shapes = radioGroup(RadioGroup.HORIZONTAL, listOf(star, triangle)) { update() }
        val planes = radioGroup(RadioGroup.HORIZONTAL, listOf(upright, flat)) { update() }
        heightCheck.setOnCheckedChangeListener { _, _ -> update() }
        update()

        AlertDialog.Builder(this)
            .setTitle("Start mission 3 (star or triangle)?")
            .setView(dialogBody(shapes, planes, heightCheck, message))
            .setPositiveButton("Fly") { _, _ -> startMission(mission()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun radioButton(label: String) = RadioButton(this).apply { id = View.generateViewId(); text = label }

    /** A group of [buttons] with the first one checked; [onChange] runs on every new choice. */
    private fun radioGroup(orientation: Int, buttons: List<RadioButton>, onChange: () -> Unit) =
        RadioGroup(this).apply {
            this.orientation = orientation
            buttons.forEach { addView(it) }
            check(buttons[0].id)
            setOnCheckedChangeListener { _, _ -> onChange() }
        }

    /** The dialog body: [views] one under the other, scrolling when the screen is small. */
    private fun dialogBody(vararg views: View): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            views.forEach { addView(it) }
        }
        return ScrollView(this).apply { addView(content) }
    }

    // ---------------------------------------------------------------- running a mission

    /** The mission buttons and the checkboxes: usable while connected and no mission flies. */
    private fun setControlsEnabled(enabled: Boolean) {
        runOnUiThread {
            mission1Button.isEnabled = enabled
            mission2Button.isEnabled = enabled
            mission3Button.isEnabled = enabled
            simulatorCheck.isEnabled = enabled
            lightsCheck.isEnabled = enabled
        }
    }

    /** Runs [mission] on its own thread; on failure it lands, or hovers if [Mission.hoverOnFailure]. */
    private fun startMission(mission: Mission) {
        val name = mission.name
        missionRunning = true
        drone.arm()
        setControlsEnabled(false)

        thread(name = "mission") {
            try {
                drone.openFlightLog(getExternalFilesDir(null), name)
                mission.run()
                status(if (drone.isAborted) "$name aborted — landed." else "$name complete ✓")
            } catch (e: ControlTakenBack) {
                // The pilot or the drone itself is flying now: let go and send nothing more.
                drone.letGo()
                status("$name stopped: control was taken back (${e.message}).\n" +
                    "The app has let go and will NOT land the drone. Fly it with the RC.")
            } catch (e: Exception) {
                Log.e(TAG, "$name failed", e)
                if (drone.isAborted) {
                    // STOP's own thread is normally landing the drone already; this waits for
                    // it. It still lands when STOP came during takeoff, before the drone
                    // counted as flying.
                    drone.recoverAndLand()
                } else if (mission.hoverOnFailure) {
                    drone.letGo()
                    status("$name FAILED: ${e.message}\n" +
                        "If the drone is in the air it is HOVERING: take over with the RC, or press STOP + LAND.")
                } else {
                    status("$name FAILED: ${e.message}\nTrying to land…")
                    drone.recoverAndLand()
                }
            } finally {
                drone.closeFlightLog()
                missionRunning = false
                setControlsEnabled(true)
            }
        }
    }

    /** STOP + LAND: stops the mission and lands, also a drone that mission 2 left hovering. */
    private fun emergencyStop() {
        drone.abort()
        thread {
            if (drone.isFlying()) {
                status("STOP pressed — landing…")
                drone.recoverAndLand()
            }
        }
    }
}
