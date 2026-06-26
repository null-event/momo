# Gyroscopic Aquarium Live Wallpaper

A self-contained Android **Live Wallpaper** rendering a 3D, gyroscopic aquarium
scene with OpenGL ES 3.0. The camera looks horizontally across a waterline that
sits near the top of the screen: a thin strip of gradient sky above `y = 0`, and
below it an exposed root system, an uneven vertex-colored lakebed with rocks and
swaying seaweed, animated caustics, and schools of fish that flock via a Boids
simulation and chase food you drop by tapping.

Everything — geometry, shaders, and math — is generated in code. No external
models, textures, libraries, or game engines are used.

## Features

- **Underwater composition** — reconstructed sky/murk backdrop from the inverse
  view-projection, so the horizon responds correctly to camera pitch and yaw.
- **Procedural geometry** (`GeometryBuilders.kt`): rooted tree with gaps for fish
  to swim through, 4–6 randomly chosen fish species (Minnow, Trout, Bass,
  Catfish, Barracuda, Perch), a 96×24 subdivided water plane, an uneven
  vertex-colored bed with rocks, seaweed blades, and food spheres.
- **GLSL ES 3.00 shaders** (`ShaderUtils.kt`): wave-displaced water with
  analytic normals, Fresnel reflection/refraction blend with foam; Phong fish
  with eat-flash; murk-fogged terrain/roots; swaying instanced plants; sky
  gradient; glowing food.
- **Boids fish AI** (`FishSystem.kt`): 40–80 instanced fish with seek /
  separation / alignment / cohesion weights `2.0 / 1.5 / 0.5 / 0.3`, schooling by
  species, cross-school disruption, bounded to the underwater volume; tap (or an
  automatic 30–60 s timer) spawns food at a **randomized** Z depth (−5…−30) via a
  screen-to-world ray; the first fish in range eats it and flashes.
- **Plants & roots** (`PlantSystem.kt`): instanced seaweed field and tree
  transforms with shader-driven sway.
- **Gyroscopic parallax** — `TYPE_ACCELEROMETER` drives clamped camera pitch
  (±15°) and yaw (±40°).
- **User settings** (`SettingsActivity.kt` + `res/xml/aquarium_prefs.xml`): fish
  varieties & counts, swim speed, water color, surface speed, choppiness,
  murkiness, root size, tree count, seaweed density, and plant sway — applied
  live via `SharedPreferences`.
- **Battery-aware** — continuous render pauses in `onVisibilityChanged(false)`;
  all GL/sensor resources released in `onDestroy()`; well under 30 draw calls per
  frame.

## Project layout

```
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    java/com/nullevent/aquarium/
      AquariumWallpaperService.kt  # WallpaperService + Engine + sensors + lifecycle + settings model
      LakeRenderer.kt              # GLSurfaceView.Renderer, draw loop, cleanup
      GeometryBuilders.kt          # procedural meshes
      ShaderUtils.kt               # shader compile/link + GLSL sources
      MathUtils.kt                 # matrix/vector helpers
      FishSystem.kt                # Boids + food + instance data
      PlantSystem.kt               # roots/plants + instance data
      SettingsActivity.kt          # live settings screen
    res/xml/wallpaper.xml          # wallpaper metadata (+ settingsActivity)
    res/xml/aquarium_prefs.xml     # settings UI
    res/drawable/wallpaper_thumb.xml
    res/values/{strings,arrays,themes}.xml
build.gradle, settings.gradle, gradle.properties   # project scaffolding
```

## Build & install

1. Open the project in Android Studio (or `./gradlew :app:installDebug`).
2. On the device: **Settings → Wallpaper → Live Wallpapers → Gyroscopic Aquarium**.
3. Tilt the device to look around; tap to feed the fish. Use the wallpaper
   picker's settings button to customize the scene.

Targets `minSdk 24`, `targetSdk 34`, `compileSdk 34`. Requires OpenGL ES 3.0.
