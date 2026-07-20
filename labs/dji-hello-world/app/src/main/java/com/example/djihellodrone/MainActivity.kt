package com.example.djihellodrone

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.ProductKey
import java.util.concurrent.atomic.AtomicReference
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.interfaces.SDKManagerCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Hello-world mission: take off, fly ~1.5 m forward, rotate 180°, fly ~1.5 m back, land.
 *
 * Movement uses the *basic* virtual-stick API: emulated stick positions with the
 * exact semantics of the physical RC in mode 2 (right stick up = forward,
 * left stick sideways = rotate). Distance is timed; the 180° turn is closed-loop
 * against the compass heading.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "DroneHello"

        // Stick deflection is -660..660 (= full stick). Keep values small!
        private const val FORWARD_STICK = 70        // ~10 % forward stick, gentle
        private const val FORWARD_MS = 1000L        // ~1 s powered; coasts to ~1.5 m incl. braking. Keep short for indoor safety.
        private const val YAW_STICK = 200           // ~30 % rotation stick
        private const val YAW_TOLERANCE_DEG = 12.0
        private const val YAW_TIMEOUT_MS = 15000L
    }

    private lateinit var statusView: TextView
    private lateinit var goButton: Button
    private lateinit var stopButton: Button

    @Volatile private var aborted = false
    @Volatile private var missionRunning = false
    @Volatile private var showDiagnostics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        goButton = findViewById(R.id.goButton)
        stopButton = findViewById(R.id.stopButton)
        goButton.isEnabled = false

        goButton.setOnClickListener { confirmAndFly() }
        stopButton.setOnClickListener { emergencyStop() }

        // Surface ANY crash (any thread) on screen instead of silently closing.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "UNCAUGHT", e)
            val trace = e.stackTraceToString().take(1500)
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
                if (showDiagnostics && !missionRunning) {
                    try { setStatus(readDiagnostics()) } catch (_: Exception) {}
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun readDiagnostics(): String {
        val fcConn = pull(FlightControllerKey.KeyConnection, 1000)
        val type = pull(ProductKey.KeyProductType, 1000)
        val name = pull(FlightControllerKey.KeyAircraftName, 1000)
        val fw = pull(FlightControllerKey.KeyFirmwareVersion, 1000)
        val mode = pull(FlightControllerKey.KeyFlightModeString, 1000)
        val flying = pull(FlightControllerKey.KeyIsFlying, 1000)
        val motors = pull(FlightControllerKey.KeyAreMotorsOn, 1000)
        val sats = pull(FlightControllerKey.KeyGPSSatelliteCount, 1000)
        return "DRONE STATUS\n" +
            "FC connected: $fcConn\n" +
            "Product type: $type\n" +
            "Aircraft: $name\n" +
            "Firmware: $fw\n" +
            "Flight mode: $mode\n" +
            "In air: $flying   Motors: $motors\n" +
            "GPS sats: $sats\n\n" +
            "Press FLY MISSION to attempt takeoff."
    }

    // ---------------------------------------------------------------- SDK setup

    private fun initSdk() {
        SDKManager.getInstance().init(applicationContext, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    setStatus("SDK initialized, registering app…")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                setStatus("App registered ✓\nWaiting for drone…\n(RC plugged in, drone on?)")
            }

            override fun onRegisterFailure(error: IDJIError?) {
                setStatus("Registration FAILED:\n$error\n\nCheck the API key in gradle.properties and internet connection.")
            }

            override fun onProductConnect(productId: Int) {
                showDiagnostics = true
                runOnUiThread { goButton.isEnabled = true }
            }

            override fun onProductDisconnect(productId: Int) {
                showDiagnostics = false
                setStatus("Drone disconnected.")
                runOnUiThread { goButton.isEnabled = false }
            }

            override fun onProductChanged(productId: Int) {}
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }

    // ---------------------------------------------------------------- mission

    private fun confirmAndFly() {
        if (missionRunning) return
        AlertDialog.Builder(this)
            .setTitle("Start mission?")
            .setMessage("The drone will TAKE OFF, fly ~1.5 m forward, turn 180°, fly ~1.5 m back and land.\n\nMake sure you have ~4 m of clear space and GPS signal. Keep hands off the RC sticks (moving them takes control back).")
            .setPositiveButton("FLY") { _, _ -> startMission() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMission() {
        missionRunning = true
        aborted = false
        runOnUiThread { goButton.isEnabled = false }

        thread(name = "mission") {
            try {
                runMission()
                setStatus(if (aborted) "Mission aborted — landed." else "Mission complete ✓")
            } catch (e: Exception) {
                Log.e(TAG, "Mission failed", e)
                setStatus("Mission FAILED: ${e.message}\nTrying to land…")
                tryRecoverAndLand()
            } finally {
                missionRunning = false
                runOnUiThread { goButton.isEnabled = true }
            }
        }
    }

    private fun runMission() {
        // Best-effort wait for the flight-controller link (fresh read, not the cache).
        // We never hard-fail here: takeoffWithRetry() is the real guard against the
        // "request_handler_not_found" that happens if takeoff is sent too early.
        step("Waiting for aircraft…")
        val readyDeadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < readyDeadline) {
            checkAbort()
            if (pull(FlightControllerKey.KeyConnection) == true) break
            Thread.sleep(300)
        }

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
        sleepChecked(500)

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

        step("Landing…")
        disableVirtualStickQuietly()
        performAction(FlightControllerKey.KeyStartAutoLanding, "start landing")
        awaitLanded()
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

    private fun awaitLanded() {
        val deadline = System.currentTimeMillis() + 40_000
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
        throw IllegalStateException("Landing did not complete within 40 s")
    }

    // ---------------------------------------------------------------- stop / recovery

    private fun emergencyStop() {
        aborted = true
        zeroSticks()
        if (missionRunning) {
            setStatus("STOP pressed — landing…")
            thread { tryRecoverAndLand() }
        }
    }

    private fun tryRecoverAndLand() {
        try {
            zeroSticks()
            disableVirtualStickQuietly()
            if (isFlying()) {
                performAction(FlightControllerKey.KeyStartAutoLanding, "emergency landing")
                awaitLanded()
                setStatus("Landed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed", e)
            setStatus("Auto-landing failed: ${e.message}\nTAKE OVER WITH THE RC!")
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Fresh, blocking read of a key. MSDK v5's synchronous getValue() only returns
     * cached values (null until something subscribes), so we use the async getValue
     * with a latch to actually query the aircraft.
     */
    private fun <T> pull(keyInfo: DJIKeyInfo<T>, timeoutMs: Long = 2500): T? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>(null)
        KeyManager.getInstance().getValue(
            KeyTools.createKey(keyInfo),
            object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(t: T?) { result.set(t); latch.countDown() }
                override fun onFailure(e: IDJIError) { latch.countDown() }
            })
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
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
                Log.w(TAG, "Takeoff attempt ${attempt + 1} failed: ${e.message}")
                setStatus("Takeoff not ready, retrying (${attempt + 1}/5)…")
                sleepChecked(2_000)
            }
        }
        val motorErr = pull(FlightControllerKey.KeyMotorStartFailureError, 1500)
        val mode = pull(FlightControllerKey.KeyFlightModeString, 1500)
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
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(e: IDJIError) { error = e; latch.countDown() }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("enable virtual stick: timeout")
        error?.let { throw IllegalStateException("enable virtual stick: $it") }
    }

    private fun disableVirtualStickQuietly() {
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
    }

    private fun step(msg: String) {
        checkAbort()
        setStatus(msg)
    }

    private fun setStatus(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { statusView.text = msg }
    }
}
