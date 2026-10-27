# File formats

All our own formats = ZIP (store for binary tiles, deflate for JSON) with `manifest.json` and a `"schema": <int>` field. Reading: unknown fields are ignored, a newer schema is read with a migration.

## Project `.pnt`
```
manifest.json      { schema:1, app:"painter", id, name, type, width, height, dpi, colorMode, created, modified,
                     paper{...}, nodes:[...node tree, see document-model...], selection?, guides, references[],
                     palettes[], recentBrushes[], stats{}, timelapse{enabled, quality, followNav} }
thumb.png          512 px on the long side
layers/<nodeId>/tiles.idx      binary index: [u16 tx, u16 ty, u32 offset, u32 len] × N
layers/<nodeId>/tiles.bin      tiles: half-float RGBA premultiplied, 256×256, LZ4-frame per tile
masks/<nodeId>/tiles.*         R8, LZ4
vector/<nodeId>.json           shapes (M4)
refs/<id>.png|jpg              references
history/<n>/...                save points (a full copy of layers+manifest, without history)
timelapse/frames.mp4|chunks/   H.264 segments + index.json
```
- In-app storage: an unpacked directory `files/projects/<id>/` (fast). `.pnt` is only for export and sync.
- Atomic write: we write to `<id>.tmp/`, then rename. Crash backup = the command journal since the last save (`journal.bin`).

## Brush `.pbrush` / pack `.pbrushes`
- `.pbrush`: `brush.json` (see brush-engine.md §6) + `head.png`, `texture.png`, `particle.png` (8-bit gray or RGBA).
- `.pbrushes`: `manifest.json {name, brushes:[file]}` + nested `.pbrush` files.

## Palette `.ppal`
JSON: `{schema:1, name, colors:[{"hex":"#RRGGBBAA","name"?}]}`. Import also from GIMP `.gpl` and `.ase` (public specifications), M5.

## Settings `.pcfg`
JSON dump of the settings + the list of user brushes, palettes, shortcuts.

## PSD (M3)
Per the Adobe Photoshop File Format Specification (public).
- Export: 8-bit RGB (CMYK 8-bit in M4).
  - raster layers;
  - groups (lsct): normal / passthrough;
  - opacity, blend (mapping below), clipping, layer masks;
  - text → raster + name;
  - adjustment → raster (baked as a separate layer with its mode) if there is no PS equivalent.
  - Paper → a bottom layer named "Paper".
- Import:
  - raster layers, groups, masks, clipping, opacity, blend;
  - everything else is rasterized from the layer's composite preview or skipped.
- Blend mapping:
  - normal→norm, darken→dark, multiply→mul, color_burn→idiv, linear_burn→lbrn, darker_color→dkCl;
  - lighten→lite, screen→scrn, color_dodge→div, linear_dodge→lddg, lighter_color→lgCl;
  - overlay→over, soft_light→sLit, hard_light→hLit, vivid_light→vLit, linear_light→lLit, pin_light→pLit, hard_mix→hMix;
  - difference→diff, exclusion→smud, subtract→fsub, divide→fdiv;
  - hue→hue, saturation→sat, color→colr, luminosity→lum;
  - add→lddg (approximation);
  - negative, invert, erase, mask, line_art → baked.

## Raster
- PNG: 8-bit RGBA / RGB, optionally 16-bit.
- JPG: quality 1–100.
- WebP: lossy or lossless.
- Gray 8-bit, 1-bit PNG (M4).
- ZIP of layers: a PNG per layer + `layers.json` (order, opacity, blend).
