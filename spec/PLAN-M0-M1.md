# M0–M1 implementation plan

Goal of M1: on an Android tablet you can create a canvas, draw with a brush and an eraser with a stylus (pressure), work with layers, change color, undo, save, open from the gallery and export PNG/JPG.

## 0. Environment (everything inside `Painter/`)
| Step | Action | Size |
|---|---|---|
| 0.1 | `.venv` (done) | — |
| 0.2 | Gradle 8.11.1 → `.tools/gradle-8.11.1/`, `GRADLE_USER_HOME=.tools/gradle` | ~140 MB + cache ~1.5 GB |
| 0.3 | Android SDK for building: we use the existing `%LOCALAPPDATA%\Android\Sdk` (platform 34, build-tools 35) read-only. Path in `local.properties` | 0 |
| 0.4 | Emulator: a separate SDK root `.tools/android-sdk` (cmdline-tools → emulator + system-images;android-34;google_apis;x86_64), AVD in `.tools/avd` (`ANDROID_AVD_HOME`). Tablet profile 2560×1600 | ~4 GB |
| 0.5 | Script `tools/env.ps1`: sets the environment variables for the session | — |
| 0.6 | `git init` + `.gitignore` (.venv, .tools, build). Commits only on your command | — |
- Alternative to 0.4: your Android tablet over USB or Wi-Fi debugging, then the emulator is not needed.

## 1. Skeleton (M0)
| Step | Result | Check |
|---|---|---|
| 1.1 | `settings.gradle.kts`, version catalog, modules `:core` (kotlin-jvm), `:app` (android). AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, compileSdk/targetSdk 34, minSdk 29 | `gradlew :core:test`, `:app:assembleDebug` green |
| 1.2 | `:app`: MainActivity, Compose navigation Home ↔ Studio, theme (ui-components.md) | APK launches |
| 1.3 | Studio skeleton: top bar (Home, Tools ×2, Layers, Options), main toolbar, undo/redo, empty layer panel | visual check |
| 1.4 | `CanvasView : GLSurfaceView` (GLES 3.0+), rendering of the checkerboard and the canvas rectangle, view matrix (pan, zoom, rotate, fit) | gestures work, 60 fps |

## 2. Core (`:core`, JVM-testable)
| Step | Component | Tests |
|---|---|---|
| 2.1 | `Half` (float16 ↔ float), `Tile` (256², FloatArray RGBA premult on the CPU), `TileGrid` (sparse map) | round-trip, bounds |
| 2.2 | `Document`, `RasterLayer`, `GroupNode` (root only), Paper | structure |
| 2.3 | `BlendModes` CPU reference (12 M1 modes, all 33 in M2) | formula table |
| 2.4 | `Command`, `History` (tile snapshots, limit) | undo/redo hashes |
| 2.5 | `InputPoint`, `Stabilizer` (EMA), `StrokeResampler` (spacing, taper) | spacing ±1%, noise ↓ |
| 2.6 | `BrushParams` (M1 subset), `DabGenerator` (size/flow from pressure) | curve values |
| 2.7 | `CpuDabRenderer` (reference: dab into a tile buffer) + `StrokeCompositor` (cap opacity, erase) | golden PNG of the stroke |
| 2.8 | `ProjectIO` (project directory: manifest.json + tiles LZ4), PNG export via an encoder abstraction | save→load hashes |
| 2.9 | Color: HSB/RGB/hex conversions | round-trip |

## 3. GPU rendering (`:app/render`)
| Step | Result |
|---|---|
| 3.1 | Pool of RGBA16F 256² textures, upload of dirty tiles from `:core` |
| 3.2 | Compositing of the layer stack (blend shader for 12 modes, opacity, clip, alpha lock) into the canvas framebuffer → screen with the view matrix |
| 3.3 | Rendering of dabs on the GPU (instanced quads) into the stroke buffer. Compositing of the stroke buffer over the active layer in the preview. Pen-up: baking into tiles and readback into `:core` for history |
| 3.4 | `MotionEvent` handling: stylus/finger, point history, pressure, predicted (androidx.input), 2/3-finger gestures |

## 4. UI M1
| Step | Result |
|---|---|
| 4.1 | Main toolbar: modes, size and opacity virtual sliders, popups, HUD |
| 4.2 | Color panel (wheel, sliders, hex, palette, recent) |
| 4.3 | Layer panel (T8) |
| 4.4 | Home / New canvas / Projects gallery (T9) |
| 4.5 | Options: Save, Export PNG/JPG (T10), Settings stub (pressure curve, palm mode) |

## 5. M1 acceptance
- All JVM tests green.
- The APK builds and installs on an emulator or device.
- Manual checklist from tools/m0-m1-tools.md: every "Acceptance" item.
- Report to you: what works, what does not, screenshots from the emulator.

## Risks
- The emulator handles GLES 3 + RGBA16F render targets poorly. Fallback: RGBA8 + dithering on the emulator, half-float on the device.
- Stylus and pressure cannot be tested on the emulator; a real device is needed.
- Tile readback performance after a stroke: we do it asynchronously (PBO).
