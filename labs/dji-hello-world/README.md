# Drone Hello: DJI Mini 4 Pro "Hello World"

Android app (DJI Mobile SDK v5.18) for the **DJI Mini 4 Pro** with a **DJI RC-N3**
controller. Five buttons each run a fixed mission:

- **Mission 1 (demo):** take off → fly ~1.5 m forward → rotate 180° → fly ~1.5 m back → land.
- **Mission 2 (light painting, outdoors):** draw "Albstadt", "Informatik" and a heart for a
  long-exposure photo, about 61 m wide and up to 73 m high.
- **Mission 3 (star, vertical):** draw a 2 m star standing upright above the take-off spot.
- **Mission 4 (star, horizontal):** draw the same star lying flat at ~1.2 m.
- **Mission 5 (triangle, vertical):** draw an upright triangle, with or without a height
  correction from the downward sensor.

Start with Mission 1. It proves the full chain works end to end: SDK registration,
aircraft connection, takeoff, virtual-stick movement, a closed-loop yaw turn and an
auto-landing. Every mission writes a flight log.

> [!WARNING]
> This is a teaching demo, not production software, and it commands real hardware. Read
> [Safety](#safety) and [Known limitations](#known-limitations) before the first flight,
> and keep a hand on the RC sticks. Fly at your own risk.

## Requirements

| | |
|---|---|
| Drone | DJI Mini 4 Pro |
| Controller | DJI RC-N3 or RC-N2. **The RC 2 with built-in screen is not supported by MSDK.** |
| Phone | Android 7.0+ (arm64) with a USB-C port. An emulator will not work: the SDK ships arm64 libraries only and an emulator has no link to the controller. |
| Phone app | [DJI Fly](https://www.dji.com/de/downloads/djiapp/dji-fly) (not on Google Play), for the connection check, firmware updates and RC linking |
| Build machine | JDK 17+, Android SDK (compileSdk 35); the Gradle wrapper is included |

## Setup order

The toolchain and firmware downloads are the steps most likely to stall a session, so do
them first.

1. Install the JDK and the Android SDK (Android Studio includes both).
2. [Configure the DJI App Key](#1-configure-the-dji-app-key) and run the first
   [build](#2-build).
3. Install DJI Fly on the phone, [connect it to the RC](#3-connect-the-drone) and confirm
   that it connects. If DJI Fly cannot connect, Drone Hello will not either.
4. Power on the drone and finish every firmware update DJI Fly offers. This needs internet
   and a charged battery; an outdated drone goes no further.
5. Link the RC to the drone in DJI Fly, then force-stop DJI Fly.
6. Install Drone Hello and [fly Mission 1](#4-fly).

## 1. Configure the DJI App Key

The app cannot connect without a DJI App Key tied to this exact package name.

1. Create a free account at <https://developer.dji.com>.
2. Under <https://developer.dji.com/user/apps>, select **CREATE APP**:
   - App Type: **Mobile SDK**
   - Software Platform: **Android**
   - Package Name: `com.example.djihellodrone` (must match exactly)
   - App Name, Category, Description: any value
3. Confirm the activation e-mail and copy the **App Key**.
4. Create your local config and add the key:
   ```bash
   cp gradle.properties.template gradle.properties     # Git Bash, macOS, Linux
   copy gradle.properties.template gradle.properties   # Windows CMD
   ```
   ```properties
   DJI_API_KEY=your_app_key_here
   ```

`gradle.properties` is git-ignored, so the key is never committed.

## 2. Build

The first build downloads Gradle, the Android platform and the DJI SDK. If it ends in a
connection timeout, the network is the likely cause; switch to a mobile hotspot and retry.

```bash
./gradlew assembleDebug          # macOS, Linux, Git Bash
.\gradlew.bat assembleDebug      # Windows PowerShell, CMD
```

Install the APK by copying it to the phone, or with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` is in your Android SDK under `platform-tools/`.

## 3. Connect the drone

The connection chain is **phone → USB-C → RC-N3 → radio → drone**.

1. **First launch needs internet:** the SDK registers the App Key online once.
2. **Link the RC to the drone.** The RC-N3 has no screen, so this one-time step runs in DJI
   Fly: camera view → **Control → Re-pair to Aircraft**, then hold the drone's power button
   until it beeps. Then **force-stop DJI Fly**, because only one app can hold the controller.
3. **Use the right port.** Plug the phone into the USB-C port next to the phone clamp (the
   top data port), not the charging port on the bottom edge. The DJI cable is directional:
   the end with the controller logo goes into the controller.
4. Power on the drone and RC, open Drone Hello and wait for **`FC connected: true`** in the
   **DRONE STATUS** block.

## 4. Fly

### Simulator first

Test on the desk with the **propellers removed**. Connect the drone and tick **Simulator**
above the mission buttons; the log confirms *Simulator ON* and the status block shows
`Simulator: ON`. The flight controller then runs takeoff, virtual sticks and landing
virtually, using `SimulatorManager`, and reports telemetry as in real flight. The simulator
switches off when the drone disconnects.

### Mission 1 (demo)

1. Clear ~4 m ahead, with nothing overhead and no people or pets nearby.
2. Put the drone flat on the ground, not on metal or in your hand.
3. Press **Mission 1 (demo)** and confirm.
4. Keep a thumb near the RC sticks and the red **STOP + LAND** button.

Both legs steer on a measured position: the drone's velocity, added up from the take-off
spot. This works on GPS outdoors and on the vision sensors indoors. The way back aims at the
take-off spot, so errors in the turn do not add up. Expect to land ~10–30 cm off. If the
drone reports no velocity, the app falls back to timed legs (`FORWARD_STICK`, `FORWARD_MS`).

### Mission 2 (light painting, outdoors)

The words are traced from the script font Agatha Bergman. Where the pen lifts, the drone
turns its lights off, flies to the next piece and turns them on again. The flight covers
about 800 m and takes roughly twelve minutes.

| Pre-flight check, enforced by the app | Required |
|---|---|
| Satellites | at least 10 |
| Battery | at least 90 % (the simulator only warns) |
| Height limit | at least 78 m |
| Distance limit, if enabled | at least 71 m |

1. **Fly it in the simulator first.** The stick directions and speed constants in
   `MainActivity.kt` have only been checked against a model, not on the drone.
2. **Check the lights.** With the drone connected on the table, untick **Drone lights on**:
   the LEDs go dark and the log says *Switching the lights: ok*. If they stay on, the hops
   between pieces show in the photo as thin lines.
3. Put the camera on a tripod, framing ~70 m across and ~75 m up.
4. Place the drone with its **nose pointing at the camera**. That spot is under the
   bottom-left corner of the figure, as the camera sees it.
5. Press the button and type `yes, fly` to unlock the flight.
6. Keep the shutter open for the whole flight. Uncover the lens at **UNCOVER THE LENS** and
   cover it at **COVER THE LENS**; the drone hovers 5 s at each and moves between lines
   while the lens is covered. After the heart, it flies back and lands.

This mission steers on GPS position. If it leaves the box around the figure by more than
8 m or loses its position, it stops and **hovers**: take over with the RC or press
**STOP + LAND**. The figure is defined in `FigurePath.kt`.

### Mission 3 (star, vertical)

A five-pointed star across the nose, 1 m to each side, with the lowest tips at ~1.2 m and
the top at 3.0 m. Place the drone with its nose pointing at the camera. It needs a ceiling
above 3.7 m and flies indoors and outdoors. Tune with `STAR_DIAMETER_M` and
`STAR_BOTTOM_ALT_M`. See the [planned path](docs/mission3-path.svg).

### Mission 4 (star, horizontal)

The same star, flat at ~1.2 m and centred above the take-off spot, with the first tip 1 m
ahead of the nose. Keep 1 m clear in every direction, plus a margin. It flies indoors and
outdoors. Film it from above or below, because from the side it is only a line. Tune with
`STAR_DIAMETER_M` and `STAR_FLAT_ALT_M`.

### Mission 5 (triangle, vertical)

A triangle across the nose, with sides of about 1.7 m, ~0.9 m to each side, the bottom line
at ~1.2 m and the top corner at ~2.7 m. Place the drone with its nose pointing at the camera.
It needs a ceiling above 3.4 m.

The start dialog offers **Fly with height correction** and **Fly without height correction**.
With correction, the tracked height follows the downward sensor, so it needs a **flat floor**
with no tables or stairs below; a reading that jumps by more than 0.4 m is dropped. The
flight log records each correction and the on-screen log reports readings used and dropped.

### Outdoors and indoors

- **Outdoors** with good GPS is the safest first real flight after the simulator. Check
  [DiPUL](https://maptool-dipul.dfs.de) for geo-zones and stay under the **120 m**
  open-category ceiling. This app shows none of the warnings DJI Fly gives. The drone's
  no-fly zones and height limit still apply, but any app can raise the height limit.
- **Indoors or without GPS**, the drone holds position on its downward vision sensors. This
  needs good lighting and a textured floor; expect some drift. Mission 2 cannot fly this way.

## Safety

- **Propeller guards on for every indoor flight.** The drone weighs under 250 g, but the
  propellers spin at ~14,000 rpm and can cut skin and eyes.
- **Nobody stands under, behind or within ~2 m of the drone** during takeoff, landing or a
  virtual-stick test. Brief everyone in the room out loud before you arm.
- **STOP + LAND** aborts and auto-lands at any time.
- **Moving the RC sticks takes control back immediately**; the RC pause button also cancels
  app control. The app then stops the mission and sends nothing more. **It will not land the
  drone after you take over, so land it yourself.**
- **If a mission fails**, Missions 1, 3, 4 and 5 try to land. Mission 2 hovers instead, because
  it may be far from the take-off spot.

## Known limitations

From the flights of Missions 3 to 5 on 31 October 2026:

- **Horizontal position is unverified.** It is velocity added up, reported in 0.1 m/s steps,
  and has not been checked from outside. A creep of ~0.08 m/s reads as 0.
- **The figures overshoot.** The drone reached up to 1.57 m/s against the 0.6 m/s planned and
  overshot star tips by up to ~0.5 m, with a response lag of ~0.4 s. Keep a margin.
- **Flying slower made it worse.** With slower tuning all five legs gave up and the height was
  ~1.3 m off at the top of the star, so that tuning was reverted. At normal speed the height
  stays within ~10–17 cm of the downward sensor.
- **The height correction has a small effect.** Across three flights each, the bottom line of
  the triangle was about equally straight, so it was not transferred to Missions 3 and 4.
- **Obstacle avoidance is not set by the app.** It stays as configured on the drone (`BRAKE`
  in these flights), and each flight log records the setting.

## How it works

The flight logic is in `app/src/main/java/com/example/djihellodrone/MainActivity.kt`.

- **Bootstrap:** `HelloDroneApplication` calls `com.cySdkyc.clx.Helper.install(this)` in
  `attachBaseContext`. MSDK v5 requires this and crashes without it.
- **Registration:** `SDKManager.init()`, then `registerApp()` with the App Key.
- **Readiness gate:** a mission waits until the flight controller is connected before takeoff
  (see `request_handler_not_found` under [Troubleshooting](#troubleshooting)).
- **Takeoff:** `KeyStartTakeoff` to ~1.2 m, with a small retry loop.
- **Movement:** basic virtual stick. The app writes emulated stick positions (−660 to 660).
- **180° turn:** closed-loop on `KeyCompassHeading`, stopping within ±12° of the target.
- **Reads:** MSDK v5's synchronous `getValue()` returns an empty cache until something
  subscribes, so status reads use an async query with a latch (`pull()`).
- **Landing:** `KeyStartAutoLanding`, plus `KeyConfirmLanding` to clear the landing-protection
  prompt ~0.7 m above ground.
- **Diagnostics:** while idle, the screen shows connection, product type, firmware, flight
  mode, motors and GPS. The on-screen log shows SDK events, each link coming up or dropping and
  the error code of failed reads. **Share log** sends it as text, and logcat carries the same
  lines under the tag `DroneHello`.

### Flight logs and analysis

Each mission writes one text file, `mission-<n>-<timestamp>.log`, with lines starting at the
milliseconds since the mission began. It holds the obstacle-avoidance setting and obstacle
readings; every mission except Mission 2 also records each control step. Logs stay out of the
repo, so fetch them into the git-ignored `output/` directory at the repo root:

```bash
mkdir -p ../../output/flight-logs
adb pull /sdcard/Android/data/com.example.djihellodrone/files/. ../../output/flight-logs/
```

`tools/flight-analysis/` turns logs into statistics and SVG plots. `slide.py` and `slower.py`
read the 31 October files by name, and those files are not in the repo; the other scripts take
log paths as arguments.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Build or SDK download fails with **connection timeout** | The network blocks or throttles the downloads, as school networks often do. Switch to a **mobile hotspot** and rebuild. |
| DJI Fly asks for a **firmware update** and goes no further | The firmware is outdated. Finish the update (internet, charged battery), link the RC and force-stop DJI Fly. |
| App shows an error after "Initializing DJI SDK" | Registration or init failed, and the exception is printed on screen. A missing App Key or no internet is the usual cause. |
| Phone plugged into RC, **nothing happens** | Wrong USB port or reversed cable. Use the top data port; the controller-logo end goes into the controller. |
| Stuck at "Waiting for drone…" | The RC-N3 is not linked to the drone. [Link it in DJI Fly](#3-connect-the-drone), then force-stop DJI Fly. |
| `request_handler_not_found` on takeoff | The flight controller is not connected: `DRONE STATUS` shows `FC connected: null`. The drone is usually off or asleep. Power-cycle it, wait for `FC connected: true` and confirm the firmware is current. |
| "Low GPS (0 sat)" | You are indoors. Mission 1 proceeds on vision positioning, so make sure the room is well lit. |
| `compass_large_mode` or another compass error | Magnetic interference. Move away from cars, metal, rebar and keys. If it persists, calibrate the **compass** (not the gimbal) in DJI Fly, force-stop it and retry. |
| App cannot see the drone at all | DJI Fly still holds the controller. Force-stop it under Settings → Apps → DJI Fly. |

## Hardware compatibility

- **Supported controllers:** RC-N3 and RC-N2 (no built-in screen). The RC 2 is not usable
  with MSDK.
- **Not programmable:** DJI Neo, Neo 2 and Mini 5 Pro (see the Module 3 shopping list). The Neo
  series is absent from MSDK v5's supported aircraft, so although the Neo 2 uses the same RC-N3,
  the SDK has no flight-control handlers for it.
- **Alternatives to the Mini 4 Pro:** the Air 3, Air 3S and Mavic 3 series.

Verify SDK support before buying a drone, because the model name does not tell you (see
Module 3).

## Project layout

```
dji-hello-world/
├─ app/
│  ├─ build.gradle                 # module config, DJI SDK dependencies
│  └─ src/main/
│     ├─ AndroidManifest.xml        # USB-accessory intent, API_KEY placeholder
│     ├─ java/.../HelloDroneApplication.kt
│     ├─ java/.../MainActivity.kt   # flight logic
│     ├─ java/.../FigurePath.kt     # the points of the Mission 2 figure
│     ├─ java/.../FlightLog.kt      # one log file per mission
│     ├─ java/.../LogPanel.kt       # the on-screen log
│     └─ res/                       # layouts, accessory_filter.xml
├─ docs/                            # mission3-path.svg
├─ tools/flight-analysis/           # scripts that read the flight logs
├─ gradle.properties.template       # copy to gradle.properties, add your key
├─ build.gradle · settings.gradle · gradlew · gradlew.bat
└─ README.md
```

Dependencies are in `app/build.gradle`: `com.dji:dji-sdk-v5-aircraft`, `-aircraft-provided`
(compileOnly), `-networkImp` (runtimeOnly), `androidx.appcompat` and `androidx.core-ktx` (the
SDK references AndroidX classes at runtime).

## References

- [DJI Mobile SDK v5 sample](https://github.com/dji-sdk/Mobile-SDK-Android-V5): the
  full-featured reference app, and the next step when you outgrow this lab.
- [MSDK v5 documentation](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)

## License

See the repository [LICENSE](../../LICENSE).
