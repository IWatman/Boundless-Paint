# Decisions (close spec/GAPS.md, section A)

Date: 2026-10-26. Developer: Claude (from the specs in `features/` and `spec/`; has not seen the originals' source code).

| # | Decision |
|---|---|
| A1 Platform | **Android only** (tablets and phones, stylus). Other platforms later; we write the core to be portable |
| A1 Stack | **Kotlin + Jetpack Compose** (UI) + **OpenGL ES 3.2** (canvas rendering, `GLSurfaceView`). minSdk 29 (Android 10), target 36. No NDK at the start. Heavy CPU algorithms (flood fill, morphology) in Kotlin on background coroutines, moving to C++/NDK later if needed |
| A2 Pipeline | Layers = 256×256 **RGBA16F** tiles (premultiplied). Compositing on the GPU. gamma-correct toggle (blending in linear space). Display in sRGB 8 bit |
| A3 Document | Tiled model + undo via snapshots of changed tiles (copy-on-write). Undo limit by memory (default 100 steps). Virtual transform: the layer stores source + affine/mesh until the first pixel edit |
| A4 Vector | Vector layer = a list of path objects (points + pressure + brush parameters), rendered by the same stamp engine. P2 |
| A5 Animation | A frame = its own layer stack. A shared background layer is optional. P2 |
| A6 Pages | **None.** One page = one project. Comics are kept in project folders |
| A7 Formats | Project `.pnt` = ZIP: `manifest.json` + `layers/<id>/<tx>_<ty>.bin` (compressed half-float tiles) + `thumb.png` + `history/` + `timelapse/`. Brush `.pbrush` = ZIP (`brush.json` + bitmaps). Pack `.pbrushes`. Palette `.ppal` (JSON). Settings `.pcfg` (JSON) |
| A8 Compatibility | PNG/JPG/WebP in/out (P0). PSD in/out (P1, per Adobe's public specification). ORA (P2). No ABR |
| A9 AI | **Postponed until after v1** |
| A10 Online | **Local + sync with Google Drive** (Drive REST API, app folder, sync of `.pnt` projects and assets). Conflict → a copy with a date suffix. P2. No galleries, communities, Discover |
| A11 Languages | RU + EN (strings.xml) |
| A12 Monetization | **None.** Everything free and open |

## Section B (numbers and behavior): approach
We do not clone the exact behavior of the originals. We clone **features**. Therefore:
- ranges, defaults and algorithms are **designed by us** (`spec/brush-engine.md`, `spec/blend-and-color.md`, etc.);
- where "similarity of feel" matters (stabilizer, smudge, watercolor), we tune parameters on a test corpus of our own reference strokes;
- this way we sidestep both the legal question and the lack of access to the apps.

## Section C (assets)
- Brush heads and textures are generated procedurally (noise, circles, bristles) by a script from the repository.
- Fonts: several OFL fonts from Google Fonts, licenses stored next to them.
- Icons: Material Symbols (Apache 2.0) + our own.

## Development tools
- **The whole environment is local in the project folder:**
  - `.venv`: Python for asset generation scripts and test references;
  - `.tools/gradle`: GRADLE_USER_HOME;
  - `.tools/`: other downloads (emulator, system image, NDK if needed).
  - The existing Android SDK in `%LOCALAPPDATA%\Android\Sdk` is used read-only (platforms, build-tools).
- Gradle wrapper 8.x, AGP 8.x, Kotlin 2.x, Compose BOM.
- Tests:
  - JVM unit tests for the core (document model, undo, blend formulas against a CPU reference, flood fill, curves, stabilizer);
  - instrumented only when an emulator or device is available.
- Emulator: Android Emulator + system image API 34 x86_64 (installed via sdkmanager, ~3 GB). Needed to verify GL.
