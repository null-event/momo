# Code Review Guide

This file gives Claude (and human reviewers) the context and priorities for
reviewing changes to the **Gyroscopic Lake Live Wallpaper**. It describes what
the project is, what tends to go wrong in it, and what to look for before
approving a change.

## What this project is

A self-contained Android Live Wallpaper that renders a 3D "over-under" lake
scene with OpenGL ES 3.0. All geometry, shaders, and math are generated in
code — there are **no external models, textures, libraries, or game engines**.
Keep it that way: a change that pulls in an asset file or a rendering/3D
dependency contradicts the project's core premise and needs strong
justification.

Language: Kotlin. Build: Gradle (`./gradlew :app:installDebug`).
Targets: `minSdk 24`, `targetSdk 34`, `compileSdk 34`. Requires OpenGL ES 3.0.

## Where things live

| File | Responsibility |
| --- | --- |
| `LakeWallpaperService.kt` | `WallpaperService` + `Engine`, sensor wiring, lifecycle |
| `LakeRenderer.kt` | `GLSurfaceView.Renderer`, draw loop, GL resource cleanup |
| `GeometryBuilders.kt` | Procedural meshes (tree, fish, bird, water, lakebed, forest) |
| `ShaderUtils.kt` | Shader compile/link + GLSL sources |
| `MathUtils.kt` | Matrix/vector helpers |
| `FishSystem.kt` | Boids simulation, food, per-instance data |
| `BirdSystem.kt` | Bird flight paths, per-instance data |

## Review priorities (in order)

### 1. Correctness of GL and lifecycle resource management
This is the highest-risk area for a live wallpaper. Check that:
- Every GL object created (`glGen*` for buffers, VAOs, textures, programs,
  shaders) is deleted on teardown. Leaks here accumulate across
  visibility/config changes.
- GL calls only happen on the GL thread. Sensor and lifecycle callbacks run on
  other threads — they must hand work to the renderer safely, not touch GL
  directly.
- `onVisibilityChanged(false)` actually pauses continuous rendering, and
  `onDestroy()` releases GL **and** sensor resources. Rendering or holding a
  sensor listener while invisible drains battery.
- Listeners/receivers registered in a lifecycle callback are unregistered in
  the matching teardown callback (no register-without-unregister).

### 2. Performance and battery
- The frame budget is tight: the README commits to **≤7 draw calls per frame**.
  Flag new per-frame draw calls, and prefer instancing over more draws.
- No allocations in the per-frame / per-update hot path (the boids update, the
  draw loop). Watch for `FloatArray`/list/object allocation inside frame loops,
  boxing, and `Matrix`/temp allocations that could be reused fields.
- Shader work added to fragment shaders is the most expensive — scrutinize new
  per-pixel math.

### 3. Simulation and math
- Boids weights (`seek 2.0 / separation 1.5 / alignment 0.5 / cohesion 0.3`),
  fish count (80), bird count (22), and the bounds of the underwater volume are
  load-bearing constants. Changing them changes behavior — make sure that's
  intentional and the values are documented where they're set.
- Matrix order and coordinate conventions matter (waterline at `y = 0`, camera
  pitch clamp ±8°, yaw clamp ±12°). Verify column-major MVP composition and
  that clamps still hold.
- Guard against divide-by-zero / NaN in normalization (e.g. zero-length
  velocity vectors in separation/alignment).

### 4. Shader/GLSL hygiene
- Vertex attribute locations, uniform names, and the layout the Kotlin side
  binds must stay in sync with the GLSL. A renamed uniform or reordered
  attribute that isn't updated on both sides compiles fine and renders wrong.
- Precision qualifiers should be present in fragment shaders.
- Confirm any new uniform is actually located (`glGetUniformLocation`) and
  uploaded each frame it's needed.

### 5. Style and consistency
- Match the surrounding Kotlin idiom, naming, and comment density. Don't
  introduce a new formatting or naming convention.
- Keep generated-in-code purity: no new assets, no new third-party rendering/3D
  dependencies.
- Document non-obvious magic numbers (depths, ranges, weights) at their
  definition.

## Verifying a change

- The CI workflow (`.github/workflows/android-build.yml`) builds the debug APK
  on every push and PR. A green build is necessary but **not sufficient** —
  GL/lifecycle bugs compile cleanly. Reason about runtime behavior.
- When feasible, sanity-check on a device: tilt for parallax (camera should
  stay within the pitch/yaw clamps), tap to feed fish (food spawns at a
  randomized Z depth −5…−30; nearest in-range fish eats it), and toggle the
  wallpaper off/on and rotate the device to exercise visibility and surface
  recreation without leaks.

## Review tone

Prefer fewer, high-confidence findings over a long list of nitpicks. Call out
correctness, resource-leak, and battery issues firmly; treat style as
secondary. When you flag something, point at the specific file and line and say
what would go wrong at runtime.
