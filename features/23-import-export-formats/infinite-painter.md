# Infinite Painter: import, export, formats

Source: User Guide /painter/data/*.

## Import (Options → Import)
- Destinations:
  - a new project;
  - a layer (JPG/PNG/WebP only, one file at a time, above the active one or on top, fitted into the canvas, immediately in Transform);
  - a reference;
  - a pattern tile;
  - a brush tip or texture;
  - a paper texture;
  - PNTR;
  - assets (brushes, palettes).
- Sources:
  - **Gallery** (the system picker);
  - **Camera**;
  - **Files** (the cloud; PSD, PNTR, PRBR, PRZP only from here);
  - **Search** (Pixabay: type, orientation, category, sort order, editor's choice, safe search; previews of a limited resolution);
  - **Clipboard** (the internal one).
- **PSD import**: RGB bitmap layers only. Vectors, 3D, animation, effects, incompatible adjustments are ignored. Always a new project named after the file.

## Export (Options → Export)
- The panel:
  - a name (a timestamp is added automatically);
  - the format;
  - the Background toggle (PNG/WebP: transparency);
  - Export;
  - Share (the system share sheet).
- Formats:
  - **PNG**: bg off = hide Paper, RGBA; bg on = flat with Paper;
  - **JPG**: quality;
  - **WebP**: quality + transparency;
  - **PSD**: layers, adjustment layers, masks (the Paper texture may be lost, not for Pattern projects);
  - **ZIP**: every layer as a separate PNG, without masks and adjustments;
  - **PNTR**: optionally with the version history and the timelapse.
- Android paths: Pictures/Infinite Painter (raster), Documents/Infinite Painter (PSD, ZIP), …/Brushes, …/Brush Packs, …/Palettes.

## Own formats
| Extension | What |
|---|---|
| .pntr | the whole project (history, timelapse, references), cross-platform iOS↔Android |
| .prbr | a single brush |
| .przp | a brush pack (a folder), the pack name is immutable |
| .clrs | a palette (shared with Infinite Design) |
- The asset names are internal: renaming a file changes nothing. The timestamp in the file name prevents overwriting.
- For the clean room: we design our own formats ourselves. Compatibility with .pntr/.prbr is not needed and we do not reverse-engineer them.

## Clipboard (the internal library)
- A permanent store of RGBA PNGs: fragments, layers, icons, patterns, brush tips.
- From the Selection toolbar → Clipboard. Import through the Clipboard source.
- Long-press → Delete. It is lost on reinstall.

## Moving between devices
- Via PNTR (Share in the gallery or Export).
- There is no cloud, the projects are local.
