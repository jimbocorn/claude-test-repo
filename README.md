# Cóndor Andino 🦅

A Flappy-Bird-style Android game starring the **Andean (Chilean) condor** — the
soaring national bird of Chile. Tap to flap the condor's broad wings and climb;
gravity pulls it back down. Thread it through the gaps between snow-capped Andes
rock columns to rack up points. One collision ends the run.

## Gameplay

- **Tap anywhere** to flap and gain altitude.
- Fly through the gap between each pair of rock columns. Each one cleared = +1.
- Hitting a column or the ground ends the game. Tap to play again.
- Your best score is saved on the device.

## Built with

- **Kotlin** + a custom `SurfaceView` with a dedicated render thread.
- **No bitmap assets** — the condor, mountains, clouds, columns and launcher
  icon are all drawn procedurally with `Canvas` / vector drawables, so the game
  scales crisply to any screen size and the repo stays light.
- Physics, scroll speed and obstacle spacing are all scaled to the device's
  screen dimensions, so it plays the same on a phone or a tablet.

## Project layout

```
app/src/main/
  java/com/servicerocket/condorandino/
    MainActivity.kt   # single-activity host, wires up lifecycle
    GameView.kt       # the whole game: loop, physics, rendering, state
  res/
    drawable/         # adaptive launcher-icon vectors (condor + Andes)
    mipmap*/          # launcher icon (vector, API 21+)
    values/           # strings + theme
  AndroidManifest.xml
```

## Build & run

You need the Android SDK (and `JAVA_HOME` set to a JDK 17). Point the build at
your SDK with a `local.properties` file or the `ANDROID_HOME` env var.

```bash
# Debug build
./gradlew assembleDebug

# Install on a connected device / emulator
./gradlew installDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

- **minSdk:** 21 (Android 5.0)  ·  **targetSdk / compileSdk:** 34
- **Orientation:** portrait, fullscreen
