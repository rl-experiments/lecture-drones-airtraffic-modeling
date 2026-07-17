# Drone Hello — DJI Mini 4 Pro "Hello World"

Minimal Android app (DJI Mobile SDK v5.18) for the **DJI Mini 4 Pro** flown with a
**DJI RC-N3** controller. One button runs a fixed mission:

> **take off → fly ~1.5 m forward → rotate 180° → fly ~1.5 m back → land**

It exists to prove the full chain works end to end — SDK registration, aircraft
connection, takeoff, virtual-stick movement, a closed-loop yaw turn, and an
auto-landing — in the smallest amount of code that actually flies.

> ⚠️ **Be careful — this was vibe-coded in a couple of hours.**
> It's a teaching demo, not production-grade software, and it commands **real
> hardware that leaves the ground**. Treat it as a starting point: read the safety
> notes below, test outdoors with plenty of clear space, keep a hand on the RC
> sticks, and never rely on it flying exactly as written. Fly at your own risk.

---

## Requirements

| | |
|---|---|
| Drone | DJI Mini 4 Pro |
| Controller | DJI RC-N3 (or RC-N2). **RC 2 with built-in screen is not supported by MSDK.** |
| Phone | Android 7.0+ (arm64), with a USB-C port to plug into the controller |
| Build machine | JDK 17+, Android SDK (compileSdk 35), the bundled Gradle wrapper |

---

## 1. Configure your DJI App Key (required, ~5 min)

The app will not connect to the drone without a valid DJI App Key tied to this
exact package name.

1. Create a free account at <https://developer.dji.com>.
2. Go to <https://developer.dji.com/user/apps> → **CREATE APP**:
   - App Type: **Mobile SDK**
   - Software Platform: **Android**
   - Package Name: `com.example.djihellodrone` &nbsp;← **must match exactly**
   - App Name / Category / Description: anything
3. Confirm the activation e-mail, then copy the **App Key**.
4. Create your local config from the template and paste the key in:
   ```bash
   cp gradle.properties.template gradle.properties     # Git Bash / macOS / Linux
   # or on Windows CMD:  copy gradle.properties.template gradle.properties
   ```
   Then edit `gradle.properties`:
   ```
   DJI_API_KEY=your_app_key_here
   ```

> `gradle.properties` is git-ignored so your key is never committed. Everyone who
> clones the repo starts from `gradle.properties.template`.

---

## 2. Build

```bash
./gradlew assembleDebug          # macOS / Linux / Git Bash
.\gradlew.bat assembleDebug      # Windows PowerShell / CMD
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

Install it on the phone — either copy the APK over and tap it, or via ADB with
USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(ADB lives in your Android SDK under `platform-tools/adb`.)

---

## 3. Connect the drone

The connection chain is **phone → (USB-C) → RC-N3 → (radio) → drone**. All three
links must be up.

1. **First launch needs internet** — the SDK registers the App Key once online.
2. **The RC-N3 must be linked (paired) to the drone.** The RC-N3 has no screen, so
   linking is only possible through the **DJI Fly** app (one-time):
   DJI Fly → camera view → **Control → Re-pair to Aircraft**, then hold the drone's
   power button until it beeps. Once linked (and confirmed flying in DJI Fly),
   **force-stop DJI Fly** — only one app may hold the controller at a time.
3. **Cable:** plug the phone into the **USB-C port next to the RC-N3's phone clamp**
   (the top data port), **not** the charging port on the bottom edge. The DJI cable
   is directional — the end marked with the controller logo goes into the controller.
4. Power on the drone and RC, open **Drone Hello**, and wait for the on-screen
   **DRONE STATUS** block to show **`FC connected: true`**.

---

## 4. Fly

1. Clear space: **~4 m** in the forward direction, nothing overhead, no people/pets.
2. Put the drone flat on the ground (not on metal or in your hand).
3. Press **FLY MISSION** and confirm.
4. Keep a thumb near the RC sticks and the red **STOP + LAND** button.

The status text walks through each stage: *Waiting for aircraft → Checking GPS →
Taking off → Flying forward → Rotating → Flying back → Landing.*

### Outdoors vs. indoors

- **Outdoors** with good GPS is the safest first test — the drone holds position
  precisely and lands cleanly.
- **Indoors / no GPS** works too: the Mini holds position on its downward vision
  sensors (exactly like DJI Fly). The app warns but proceeds. Needs **good lighting**
  and a **textured floor**; expect a little drift.

### Safety

- **STOP + LAND** aborts and auto-lands at any time.
- **Moving the physical RC sticks instantly takes control back** from the app — that
  is always your manual override. The RC pause button also cancels app control.
- The forward legs are **timed and open-loop** (~10 % stick for 1 s ≈ 1.5 m once
  braking is included), so distance is approximate and overshoots — test with margin
  indoors. Tune `FORWARD_STICK` / `FORWARD_MS` in `MainActivity.kt`, or add closed-loop
  distance control for an exact stop.

---

## How it works

All flight logic is in `app/src/main/java/com/example/djihellodrone/MainActivity.kt`.

- **`HelloDroneApplication`** calls `com.cySdkyc.clx.Helper.install(this)` in
  `attachBaseContext` — the mandatory MSDK v5 bootstrap; the SDK crashes without it.
- **Registration** — `SDKManager.init()` then `registerApp()` using the App Key.
- **Readiness gate** — the mission waits for the flight controller to actually be
  connected before commanding takeoff (see the pitfalls below).
- **Takeoff** — `KeyStartTakeoff` auto-takeoff to ~1.2 m, with a small retry loop.
- **Movement** — *basic* virtual stick: the app writes emulated stick positions
  (range −660…660). Right stick vertical = forward/back, left stick horizontal = yaw.
- **The 180° turn is closed-loop** — it reads `KeyCompassHeading` and stops rotating
  when the heading has moved 180° (±12°), rather than turning for a fixed time.
- **Reads use fresh async queries** — MSDK v5's synchronous `getValue()` returns an
  empty cache until something subscribes, so every status read goes through the
  async `getValue`-with-latch helper (`pull()`).
- **Landing** — `KeyStartAutoLanding`, plus `KeyConfirmLanding` to clear the
  landing-protection prompt that appears ~0.7 m above ground.
- **Live diagnostics** — while idle, the screen shows the drone's real state
  (FC connected, product type, firmware, flight mode, motors, GPS) so problems are
  visible without a PC.

---

## Troubleshooting

| Symptom | Cause & fix |
|---|---|
| App closes right after "Initializing DJI SDK" | Registration/init crash. The app now prints the exception on screen instead of closing — read it. A missing App Key or no internet is the usual cause. |
| Plugged phone into RC, **no popup, nothing happens** | Wrong USB port or reversed cable. Use the **top** data port by the clamp; the controller-logo end of the cable goes into the controller. |
| Status stuck at "Waiting for drone…" | RC-N3 not linked to the drone. Link once via **DJI Fly** (see step 3), then force-stop DJI Fly. |
| `request_handler_not_found` on takeoff | The drone's flight controller isn't actually connected — `DRONE STATUS` shows `FC connected: null`. Usually the **drone powered off / went to sleep**; power-cycle it and wait for `FC connected: true`. (Also confirm firmware is current in DJI Fly.) |
| "Weak GPS (0 satellites)" | You're indoors. The app now proceeds on vision positioning — just make sure the room is well lit. |
| `compass_large_mode` / compass error | Magnetic interference. Move to open ground away from cars, metal, rebar, keys. If it persists, calibrate the **compass** (not gimbal) in DJI Fly, then force-stop it and retry. |
| App can't see the drone at all | DJI Fly is still running and holding the controller. **Force-stop** it (Settings → Apps → DJI Fly → Force stop). |

---

## Hardware compatibility

- **Supported controllers:** RC-N3, RC-N2 (no built-in screen). The **DJI RC 2**
  (with screen) is **not** usable with MSDK.
- **DJI Neo / Neo 2 and Mini 5 Pro are NOT programmable** (Module 3 shopping list).
  The Neo series is absent from MSDK v5's supported-aircraft list, so even though the
  Neo 2 uses the same RC-N3, the SDK provides no flight-control handlers for it. For
  programmatic flight, stay on the course's recommended **DJI Mini 4 Pro**; other
  clearly MSDK-capable options are the Air 3 / 3S and Mavic 3 series.

> **Verify SDK support before buying any drone** — model names alone tell you nothing.
> This is the single most expensive mistake to avoid (see Module 3).

---

## Project layout

```
DJI-mini/
├─ app/
│  ├─ build.gradle                 # module config + DJI SDK dependencies
│  └─ src/main/
│     ├─ AndroidManifest.xml        # USB-accessory intent, API_KEY placeholder
│     ├─ java/.../HelloDroneApplication.kt
│     ├─ java/.../MainActivity.kt   # all flight logic
│     └─ res/                       # layout, accessory_filter.xml
├─ gradle.properties.template       # copy to gradle.properties, add your key
├─ build.gradle · settings.gradle · gradlew · gradlew.bat
└─ README.md
```

Dependencies (see `app/build.gradle`):
`com.dji:dji-sdk-v5-aircraft`, `-aircraft-provided` (compileOnly),
`-networkImp` (runtimeOnly), plus `androidx.appcompat` / `androidx.core-ktx`
(the SDK references AndroidX classes at runtime).
