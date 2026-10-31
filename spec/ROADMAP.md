# Roadmap (Android)

Each milestone = a working APK build + green JVM tests.

> M0 and M1 were completed 2026-10-26, details and debt in spec/STATUS.md. Plan for M2: spec/PLAN-M2.md (scope changes are also recorded there).
> M2 was completed 2026-10-31: engine S0–S9, 91-preset catalog, brush editor, stylus settings; acceptance in spec/STATUS.md. M3 is in progress.

## M0: Skeleton
- [ ] Gradle project `app/` (Kotlin, Compose, minSdk 29, target 36), local GRADLE_USER_HOME.
- [ ] Modules: `:app` (UI), `:core` (pure Kotlin: document model, math, undo, algorithms, testable on the JVM), `:render` (GLES 3 rendering). For simplicity at the start `:core` and `:app`, rendering inside app.
- [ ] Home / Studio screens (stubs), navigation.
- [ ] GL canvas: pan, zoom, rotate with two fingers, fit.

## M1: Drawing (MVP core)
- [ ] Tiled raster layer (CPU float16 buffers + GPU textures).
- [ ] Stamp brush: size, opacity, flow, spacing, hardness, pressure → size/flow, stabilizer.
- [ ] Erase, undo/redo (2/3-finger gestures), main toolbar (Paint/Blend/Erase, size, color, opacity virtual sliders).
- [ ] Color panel: HSB wheel + sliders + hex + history.
- [ ] Layer panel: add, delete, reorder, visibility, opacity, blend (12 basic), alpha lock, clip, merge.
- [ ] Saving and loading `.pnt`, project gallery, PNG/JPG export.

## M2: Parity with the IP foundation + the full stock brush set
- **Full brush engine** (all of spec/brush-engine.md, including watercolor, particles, harmony, double, color stamp, 3D, filter brushes, screentone snap). GPU dabs.
- **Stock catalog that covers every engine feature** (≈90 brushes: each special engine, texture mode and effect has presets). The full ≈236 stock of spec/brush-catalog.md is filled in M3+ with the brush editor and import (decision 2026-10-30). Brush Editor UI, brush browser with folders, favorites and search. Clone brush. Stylus settings (curve, calibration, buttons).
- Eyedropper (long-press), selection (lasso, rect, ellipse, wand, brush) + operations.
- Transform (basic, anchor, distort, warp, multi-layer), bucket (tolerance, gap, expand, reference layer), gradients.
- Groups, masks, all 33 blend modes, adjustment layers, adjustments, curves.
- Guides (lines, ellipse, lazy, pen, hatching), shapes (incl. regular polygon) + detection, symmetry, perspective grids (1/2/3 point, iso, 2D).

## M3: Expansion
- Filters (P1 set), filter layers, liquify, text, curvilinear perspective, panels/frame divider, crop, resize, straighten.
- PSD in/out, timelapse, settings, stylus (palm rejection, buttons), shortcuts.
- Brush import/export: own .pbrush packs, PNG/JPG heads, Photoshop ABR, Procreate, Krita, GIMP, MyPaint, Clip Studio ([tools/brush-import.md](tools/brush-import.md)).

## M4: ibis additions
- Vector layer, radial/array rulers, rounded rect/polygon/arrow/balloon, lasso fill/eraser, surrounding fill.
- Animation with onion skin, manuscript canvas, screentone layer, document color mode, CMYK export.
- Sync with Google Drive.

## M5: Polish
- Remaining filters, pattern tools, materials, video mode, localization, performance.
