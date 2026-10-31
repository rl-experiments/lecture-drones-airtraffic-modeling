# Drone Hello — DJI Mini 4 Pro "Hello World"

Minimal Android app (DJI Mobile SDK v5.18) for the **DJI Mini 4 Pro** flown with a
**DJI RC-N3** controller. One button runs a fixed mission, **Mission 1 (demo)**:

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
| Phone app | **DJI Fly**, from <https://www.dji.com/de/downloads/djiapp/dji-fly> (it is not on Google Play) — needed to check the phone-to-RC connection, and for firmware and RC linking |
| Build machine | JDK 17+, Android SDK (compileSdk 35), the bundled Gradle wrapper |

---

## Before the lab — in this order

This is the order that worked in class. The two big downloads (toolchain and
firmware) come first, because they are the steps that stall a session.

1. **Install the JDK and the Android SDK** on the build machine (Android Studio
   brings both).
2. **Run the first build on a network that works** (step 2 below). The first build
   downloads Gradle, the Android platform and the DJI SDK. School and campus
   networks often block or throttle these and the build ends in a
   **connection timeout** — switch the laptop to a **mobile hotspot** and run it again.
3. **Install DJI Fly** on the Android phone from
   <https://www.dji.com/de/downloads/djiapp/dji-fly>.
4. **Check the connection in DJI Fly (required).** Plug the phone into the RC's data
   port (step 3 below), power on the RC and open DJI Fly. It must connect: that proves
   the phone is connected to the RC. If it does not, fix this first, because
   **Drone Hello** will not connect either.
5. **Update the aircraft firmware in DJI Fly.** Power on the drone and let DJI Fly
   finish every update it offers. An outdated drone will not get past this screen,
   and the update needs internet and a charged battery.
6. **Link the RC to the drone** in DJI Fly (step 3 below), confirm it connects, then
   **force-stop DJI Fly**.
7. Install **Drone Hello** and run the demo (steps 3 and 4 below).

An emulator is not an option: the SDK ships arm64 libraries only and the emulator
has no link to the controller. Use a real phone.

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
3. Press **Mission 1 (demo)** and confirm.
4. Keep a thumb near the RC sticks and the red **STOP + LAND** button.

The status text walks through each stage: *Waiting for aircraft → Checking GPS →
Taking off → Flying forward → Rotating → Flying back → Landing.*

### Simulator first

Before any flight, test your code with the aircraft on the desk and the
**propellers removed**:

```kotlin
SimulatorManager.getInstance().enableSimulator(
    InitializationSettings.createInstance(LocationCoordinate2D(lat, lng), satelliteCount),
    callback
)
```

The flight controller then executes takeoff, virtual-stick input and landing
entirely virtually, reporting simulated GPS, altitude and attitude back through
`KeyManager` exactly as in real flight — so a runaway control loop costs you
nothing. The Mini 4 Pro supports this: `SimulatorManager` is declared in the
SDK's own `assets/ProductCapability/DJIMini4Pro/DJIMini4ProCapability.json`.

The app calls it from the **Simulator** checkbox above the mission buttons. Tick it
once the drone is connected; the log confirms *Simulator ON* and the status block
shows `Simulator: ON`. It switches itself off when the drone disconnects.

### Mission 2 (light painting)

The second button draws three lines in the air for a long-exposure photo:
**"Albstadt"**, under it **"Informatik"**, under that a **heart**. The words are
traced from the script font Agatha Bergman. Where the pen of the font lifts, the drone
switches its lights off, flies to the next piece, and switches them on again. The
figure is about 61 m wide and reaches 73 m; the whole flight is about 800 m and takes
roughly twelve minutes.

1. Camera on a tripod, far enough back to frame ~70 m of sky across and ~75 m up. The
   drone's own lights draw the lines, so the drone stays stock and under 250 g.
2. Put the drone down with its **nose pointing at the camera**. That spot is under the
   bottom-left corner of the figure, as the camera sees it.
3. Press **Mission 2 (light painting)** and type `yes, fly` to unlock the flight.
4. Open the shutter and keep it open for the whole flight. Take the cover off the lens
   when the status says **UNCOVER THE LENS** and put it back at **COVER THE LENS**: the
   drone hovers 5 s at each, and flies from one line to the next while the lens is
   covered. After the heart it flies back and lands.

Unlike Mission 1 it steers on the measured GPS position, not on time, so it refuses to
start with fewer than 10 satellites, less than 90 % battery (the simulator only warns),
or a height limit below 78 m (or a distance limit below 71 m, if one is on). If it
leaves the box around the figure by more than 8 m, or loses its position, it stops and **hovers** —
take over with the RC or press **STOP + LAND**.

**Check the lights first.** With the drone connected on the table, untick **Drone lights
on**: its LEDs should go dark, and the log says *Switching the lights: ok*. If they stay
on, the flight still works, but the hops between the pieces show in the photo as thin
lines.

**Fly it in the simulator first.** The stick directions and the speed constants at the
top of `MainActivity.kt` have only been checked against a model, not against the drone.
The figure itself is the lists of points in `FigurePath.kt`.

### Mission 3 (star)

Draws a five-pointed star, **2 m across**, standing upright above the take-off spot:
the lowest tips at ~1.2 m, the top at 3.0 m (under 5 m). Place the drone with its nose
pointing at the camera; the star stands across the nose, 1 m to each side. The lights
are on while the star is drawn. Like Mission 1, it steers on the drone's measured
velocity, so it flies indoors and outdoors. You need a ceiling above 3.7 m. Tune with
`STAR_DIAMETER_M` / `STAR_BOTTOM_ALT_M`; the path is drawn in `mission3-path.svg`.

### Outdoors vs. indoors

- **Outdoors** with good GPS is the safest first *real* flight (after the
  simulator) — the drone holds position precisely and lands cleanly. Check
  [DiPUL](https://maptool-dipul.dfs.de) for geo-zones first, and stay under the
  **120 m** open-category ceiling — note that your own app shows none of the
  warnings DJI Fly gives. The drone's own height limit and no-fly zones still
  apply, but the height limit is a setting any app can raise.
- **Indoors / no GPS** works too: the Mini holds position on its downward vision
  sensors (exactly like DJI Fly). The app warns but proceeds. Needs **good lighting**
  and a **textured floor**; expect a little drift.

### Safety

- **Propeller guards on for every indoor flight**, no exceptions. The Mini 4 Pro is
  under 250 g but the props still spin at ~14,000 rpm and will cut skin and eyes.
- **Nobody stands under, behind, or within ~2 m of the drone** during takeoff, landing
  or a virtual-stick test — that is exactly where a bug in your code puts it. Brief
  everyone in the room before you arm, out loud.
- **STOP + LAND** aborts and auto-lands at any time.
- **Moving the physical RC sticks instantly takes control back** from the app — that
  is always your manual override. The RC pause button also cancels app control.
  The app notices this, stops the mission and sends nothing more: **it will not land
  the drone after you have taken over.** You are flying; land it yourself.
- Both legs steer on a **measured position**: the drone's own velocity, added up from
  the take-off spot. That works on GPS outdoors and on the vision sensors indoors, and
  the way back aims at the take-off spot, so errors in the turn don't add up. Expect
  ~10–30 cm off after landing; the log shows the drone's own count. If the drone
  reports no velocity, the app falls back to the old timed legs (`FORWARD_STICK` /
  `FORWARD_MS`).

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
- **On-screen log** — a timestamped log under the status shows every SDK event, each
  link coming up or dropping (RC, air link, flight controller) and the error code of
  any failed read. **Share log** sends it as text; the same lines go to logcat under
  the tag `DroneHello`.

---

## Troubleshooting

| Symptom | Cause & fix |
|---|---|
| Build or SDK download fails with **connection timeout** | The network blocks or throttles the downloads (common on school networks). Switch the laptop to a **mobile hotspot** and rebuild. |
| DJI Fly asks for a **firmware update** and won't go further | The drone's firmware is outdated. Let DJI Fly finish the update (internet, charged battery), then link the RC and force-stop DJI Fly. |
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

---

## References

- **Official DJI Mobile SDK v5 sample** — <https://github.com/dji-sdk/Mobile-SDK-Android-V5>
  The full-featured reference app (UX SDK, all components, tutorials). This lab is a
  stripped-down alternative; when you outgrow it, the official sample is the next step.
- **MSDK v5 documentation** — <https://developer.dji.com/doc/mobile-sdk-tutorial/en/>
