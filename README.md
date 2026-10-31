# Painter

Bitmap painting editor for Android tablets. The feature set is the union of Infinite Painter and ibisPaint; the interface follows the Infinite Painter scheme. The implementation is our own, from scratch, under clean-room rules ([00-clean-room/PROCESS.md](00-clean-room/PROCESS.md)).

The app is completely free: no subscriptions, ads, watermarks or paid features.

[![Release](https://img.shields.io/github/v/release/IWatman/Painter?label=release)](https://github.com/IWatman/Painter/releases)
[![CI](https://github.com/IWatman/Painter/actions/workflows/ci.yml/badge.svg)](https://github.com/IWatman/Painter/actions/workflows/ci.yml)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF)

<p>
  <img src="docs/screenshots/studio.png" width="49%" alt="Studio: canvas, layers, toolbar">
  <img src="docs/screenshots/update.png" width="49%" alt="In-app update">
</p>

> **Status:** M2 is complete (October 2026) and M3 has started. The app has the full brush engine (texture, paint mixing, watercolor, particles, harmony, 3D, filter and clone brushes), a catalog of 91 presets in 17 categories, the brush editor, stylus settings with calibration, layers with masks and adjustment layers, selections, transform, fills, gradients, symmetry, guides, perspective grids and shapes.

## Features

**In the app**
- Canvases: screen sizes and print presets, project gallery (rename, copy, delete)
- Brushes: 91 presets in 17 categories (pencils, pens, markers, paints, watercolor, chalk, charcoal, airbrushes, textures, 3D, harmony, particles, manga, stamps, effects, outline, erasers); stylus pressure and tilt, velocity, stabilizer, point prediction, rejection of accidental stylus touches
- Brush editor: every engine parameter with a live preview; user brushes
- Stylus: pressure curve presets, per-device calibration wizard, pen buttons, eraser end, finger/palm rules
- Layers: groups, layer and group masks, all 33 blend modes, clipping, alpha lock, color tags, lock, reference layer
- Adjustment layers: curves, levels, gradient map, hue/saturation and others, non-destructive
- Selection: rectangle, ellipse, lasso, magic wand; invert, expand, feather; marching ants
- Transform: move, scale and rotate a layer or the selection, with a live preview
- Fill: bucket fill with gap closing and live recolor; gradients (linear, radial, symmetric, angular) with color stops, repeat/reflect, reverse and opacity
- Symmetry: mirror (vertical, horizontal, both), radial and kaleidoscope
- Guides: ruler, ellipse guide, lazy stroke; perspective grids (1, 2 and 3 points, isometric, 2D grid)
- Shapes: line, rectangle, ellipse, polygon, arc, drawn with the current brush; outline, fill, stamp
- Color: HSB wheel, sliders, hex, palette, eyedropper (long press)
- Undo and redo with buttons and gestures (two- and three-finger tap), two-finger navigation
- Autosave 2 seconds after a change, PNG/JPG export with quality and transparency options
- In-app update from GitHub Releases

**Ready in the core, UI in progress**
- Transform modes beyond the basic one: distort, perspective, warp
- Path guide, hatching, shape detection
- Filters (M3)

**In progress (M3)**
- Filters and filter layers, liquify, text, PSD import/export, timelapse, canvas crop/resize/straighten
- The full catalog of about 236 presets and brush import/export ([spec/tools/brush-import.md](spec/tools/brush-import.md))

Full plan: [spec/ROADMAP.md](spec/ROADMAP.md), the finished M2 plan: [spec/PLAN-M2.md](spec/PLAN-M2.md), known issues: [spec/STATUS.md](spec/STATUS.md).

## Installation

1. Download `painter-X.Y.Z.apk` from the [latest release](https://github.com/IWatman/Painter/releases).
2. Open the file on the device and allow installation from this source. Android 10 or newer and OpenGL ES 3 support are required.
3. Further updates: the "Updates" button on the app's home screen.

> Releases from 0.1.1 on are signed with the same key and update each other in place. Version 0.1.0 used a different key: if it is installed, uninstall it once.

## Building from source

Windows, JDK 21 and the Android SDK (platform 34) are required. All other tools (Gradle, emulator, Python environment) live locally in the project folder, in `.tools/` and `.venv/`.

```powershell
. .\tools\env.ps1                                   # session environment variables
.\gradlew.bat :core:test                            # JVM tests of the core
.\gradlew.bat :app:assembleDebug                    # debug APK
.\gradlew.bat :app:connectedDebugAndroidTest        # GPU tests on a device or emulator
.\.venv\Scripts\python tools\gen_assets.py --check  # check the procedural brush assets
```

Releases are built by GitHub Actions: bump the default `appVersion` in `app/build.gradle.kts`, commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`. The release workflow signs the APK with the key from the repository secrets and publishes it on GitHub Releases, where the in-app updater finds it. Local release builds read the key from `~/.painter/keystore.properties` (or the path in `PAINTER_KEYSTORE_PROPERTIES`); it never enters the repository.

## Project layout

| Module | What is inside |
|---|---|
| `core/` | Pure Kotlin, no Android. Document model, 256² tiles in half float, history, brush engine, blend modes, adjustments, selections, transforms, fills, guides, project format. Covered by JVM tests |
| `app/` | Android app: canvas on OpenGL ES 3 (layer compositing, masks, adjustments), input and gesture handling, UI on Jetpack Compose, updates |
| `.github/` | CI (unit tests and debug build on every push) and the release workflow (signed APK on a `v*` tag) |
| `tools/` | Environment, QA helper for the emulator, procedural asset generator and brush catalog (`tools/catalog`) |
| `features/` | Infinite Painter and ibisPaint functionality across 27 areas, described in our own words |
| `spec/` | Specifications: UI shell, feature map, decisions, brush engine, formats, tool specs by milestone |

Key decisions:
- **Raster:** brush dabs and raster operations are computed on the CPU in `core`, in parallel by tiles; the GPU handles compositing and previews. A dedicated test checks that GPU compositing matches the CPU reference.
- **Project format:** a folder with `manifest.json` and tiles in LZ4, export to `.pnt` (ZIP).
- **Assets:** all brush heads and textures are generated by code ([tools/gen_assets.py](tools/gen_assets.py)); there are no third-party images.

## Clean-room

The project reproduces the functionality of existing apps, but not their code or resources. Feature descriptions are gathered from public sources ([00-clean-room/SOURCES.md](00-clean-room/SOURCES.md)) and retold in our own words. Names, parameters, algorithms and assets are our own. Decompilation and copying are forbidden.
