# Infinite Painter: projects and gallery

Source: the public User Guide (docs.infinitestudio.art/painter/projects/, /home/). Version 7.x.
A retelling of the functionality in our own words. This is not a copy of the text.

## Project model
- A project contains:
  - the artwork (all layers within the canvas bounds);
  - version history;
  - a timelapse (optional);
  - references (stored inside the project);
  - metadata;
  - data specific to the project type.
- Canvas: a rectangle of a fixed size. Only it is exported. The size can be changed at any time (Resize).
- Layer: usually RGBA, the size of the canvas. At least one layer per project.
- There is always a single page. Multi-page documents are made with layers or separate projects.
- Project types are set at creation and cannot be changed:
  - **Regular**: a single image.
  - **Pattern**: a seamless tile.
  - **Challenge**: a given exercise.
- Projects are stored inside the app as open directories and are not accessible from outside. A project is bound to the device.
- Only one project is open in Studio at a time.

## Saving
- Autosave when leaving Studio: the Home button, New, Open, leaving the app.
- Manual Save (Options menu). It adds a new state to the history and overwrites nothing.
- There is no Save As. Duplicate in the gallery takes its place.
- Crash backup: the current state is backed up while you work. After a crash the work continues from the same place.
- The "Save changes prompt" setting (off by default). When enabled, it asks on exit: Discard changes / Save.

## Version history
- A version is created: when a project is created, when an empty canvas is set up, during sessions, on a manual save, on autosave, and as intermediate archive versions.
- Versions are unnamed and identified by timestamp.
- Global limit (Settings > Saving):
  - None: only the current version;
  - 1–10 most recent: the oldest is deleted on overflow;
  - Unlimited.
- After lowering the limit, the extra old versions are wiped on the next project save.
- Restore UI (long-press on the preview → Restore):
  - a list of versions, newest at the top;
  - **Restore**: a new project from the selected version and all earlier ones; the original is left untouched;
  - **Delete**: wipes the selected version and all earlier ones, irreversibly.

## New Project creator
- Sources:
  - Blank canvas;
  - From image;
  - Pattern;
  - Challenge.
- Also reachable through "+" in the gallery and New in Options.
- Fields:
  - name;
  - orientation (Portrait / Landscape / Square, by default the current device orientation);
  - paper color and texture;
  - size;
  - preview;
  - the Create button.
- Default size: the device screen or the last one used.
- Size dialog:
  - units: px, mm, cm, in;
  - W/H, swap, lock ratio;
  - a DPI field (only for physical units);
  - display of the resulting size in px;
  - display of the maximum number of layers for that size.
- The size and layer-count limits depend on the device RAM. A canvas that is too large: a warning and automatic downscaling.
- Presets:
  - Saved (user-defined, the Save button);
  - Screen size;
  - Photography (13);
  - Print ISO/US (15);
  - Social (3);
  - Video/film (4).
- The DPI of any preset can be edited. Preset sections collapse.
- 7.1: the timelapse and color-profile settings for proofing moved to the global Settings.

### From image
- Sources:
  - Gallery;
  - Camera;
  - Files (including cloud, PNTR and PSD);
  - Search (stock search via Pixabay);
  - the internal Clipboard.
- Modes:
  - **Image**: an editable layer;
  - **Reference**: an empty project + a reference;
  - **Trace**: a layer in trace mode, which can later become a normal one.
- The pixel size of the image is preserved. When the canvas is enlarged without lock ratio, the picture is not distorted; empty space is added.

### DPI reference
- Presets:
  - 600 dpi: high-quality print;
  - 300 dpi: medium;
  - 72 dpi: screen, the default for JPG/PNG.
- Internal storage is in px.

## Pattern project
- The canvas repeats in all directions in real time.
- The inner rectangle = the tile, and it is what gets exported. The outer one = the canvas.
- The tile = 1/2 of the canvas dimensions (a 1024² tile requires a 2048² canvas). The tile may be non-square.
- All brushes, layers, symmetries and Pattern tools work.
- Limitations, which may behave unexpectedly:
  - Crop, Resize, Straighten;
  - Linear gradient, Pattern fill;
  - Liquify;
  - Smart cloning;
  - transformations.
- Export: a single tile (PNG recommended, JPG, WebP) and PNTR. PSD is not possible.
- Uses of the tile: brush texture, pattern fill, paper texture.

## Challenge project
- A given art exercise. The details are not disclosed in the doc.

## Project gallery
- A simple file manager:
  - a folders section at the top;
  - project previews;
  - name and modification date.
- The preview is updated on every save.
- Long-press + drag: reordering. Drop onto a folder: move into it.
- Project context menu:
  - Rename;
  - Duplicate (multi-select);
  - Share (PNTR export);
  - Set as Template / Set as Project;
  - Restore (version history);
  - Ungroup (take out of the folder);
  - Delete (irreversible).
- Folders:
  - unlimited nesting;
  - reordering;
  - Rename, Ungroup (one level up);
  - a folder cannot be dragged into a folder;
  - an "up" arrow for navigation.
- Templates:
  - opening a template creates a copy;
  - a blue star on the preview;
  - they store sizes, paper, layers, recent brushes, swatch palettes;
  - they are not shown in Recent.
- Swipe right: return to Home.

## Home: Discover
- A tab with online content (see 27-cloud-account-ai-online).
