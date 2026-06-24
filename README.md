# Gyroscopic Lake Live Wallpaper

[![Android CI](https://github.com/null-event/momo/actions/workflows/android-build.yml/badge.svg?branch=main)](https://github.com/null-event/momo/actions/workflows/android-build.yml)

A self-contained Android **Live Wallpaper** rendering a 3D, "over-under" lake
scene with OpenGL ES 3.0. The camera looks horizontally across a waterline at
`y = 0`: above the line are a distant forest, a gradient sky, and looping birds;
below it are an exposed root system, an uneven lakebed, and a school of fish that
school via a Boids simulation and chase food you drop by tapping.

Everything — geometry, shaders, and math — is generated in code. No external
models, textures, libraries, or game engines are used.

## Features

- **Over-under composition** — vertical-gradient sky above, murky underwater
  fog below, split at the screen center.
- **Procedural geometry** (`GeometryBuilders.kt`): rooted tree, fish, bird,
  64×16+ subdivided water plane, uneven vertex-colored lakebed, forest backdrop.
- **GLSL shaders** (`ShaderUtils.kt`): wave-displaced water with recomputed
  normals, Fresnel reflection/refraction blend and foam; Phong fish; murk-fogged
  terrain/roots; sky gradient; flapping unlit birds.
- **Boids fish AI** (`FishSystem.kt`): 80 instanced fish with seek / separation /
  alignment / cohesion weights `2.0 / 1.5 / 0.5 / 0.3`, bounded to the underwater
  volume; tap to spawn food at a **randomized** Z depth (−5…−30); first fish in
  range eats it and gets a brief scale/flash.
- **Birds** (`BirdSystem.kt`): 22 instanced birds on parametric sine/cosine
  loops above `y = 2`.
- **Gyroscopic parallax** — `TYPE_ACCELEROMETER` drives clamped camera pitch
  (±8°) and yaw (±12°).
- **Battery-aware** — continuous render pauses in `onVisibilityChanged(false)`;
  all GL/sensor resources released in `onDestroy()`; ≤7 draw calls per frame.

## Project layout

```
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    java/com/nullevent/lakewallpaper/
      LakeWallpaperService.kt   # WallpaperService + Engine + sensors + lifecycle
      LakeRenderer.kt           # GLSurfaceView.Renderer, draw loop, cleanup
      GeometryBuilders.kt       # procedural meshes
      ShaderUtils.kt            # shader compile/link + GLSL sources
      MathUtils.kt              # matrix/vector helpers
      FishSystem.kt             # Boids + food + instance data
      BirdSystem.kt             # bird paths + instance data
    res/xml/wallpaper.xml       # wallpaper metadata
    res/drawable/wallpaper_thumb.xml
    res/values/strings.xml
build.gradle, settings.gradle, gradle.properties   # project scaffolding
```

## Build & install

1. Open the project in Android Studio (or `./gradlew :app:installDebug`).
2. On the device: **Settings → Wallpaper → Live Wallpapers → Gyroscopic Lake**.
3. Tilt the device to look around; tap to feed the fish.

Targets `minSdk 24`, `targetSdk 34`, `compileSdk 34`. Requires OpenGL ES 3.0.
