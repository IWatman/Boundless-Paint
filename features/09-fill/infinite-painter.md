# Infinite Painter: fills

Source: User Guide /painter/fills/*.

## General
- 5 fill tools, a shared workspace. Several fills of different types can be made in a row.
- Gradient: one icon, three sub-types.
- Fill targets:
  - the whole layer;
  - an area of similar color;
  - an enclosed area (a cell of the line art);
  - the selection (as a mask).
- Toolbar:
  - the type selector;
  - Sampling mode;
  - the controls of the type;
  - Confirm.
- Solid has no Cancel: confirm + undo.
- **Tolerance** 0–100%. In Tool settings (the gear). Shared by all the fills and the Wand. 100% on a tap = the whole layer.
- **Sampling**:
  - Multiple layer (default): all visible layers;
  - Single layer: only the current one.
- **Source layer**: any layer as the source of boundaries. The "current layer as the source" button in Layer options. An icon on the toolbar if source ≠ active. The scenario: line art on transparency as the source, fills on a layer below.
- Gap closing, expand and antialias are not described in the doc. ibisPaint has them (see ibispaint.md), so we take them from there.

## Solid fill (bucket)
- The current color. A tap = fill up to the boundary.
- **Live Tolerance**: drag from the point. The distance = tolerance 0–100% (it ignores the setting), the area grows and shrinks in real time.

## Gradient (Linear / Radial / Sweep)
- Drag: start (must be inside the area) → end (anywhere). Real-time preview.
- The colors are edited AFTER it has been applied. There are no presets.
- The first time a gradient comes from the current color, afterwards from the last one used.
- Editable until Confirm (✓) or closing. Cancel (✗) undoes only the current one.
- Handles:
  - start / end stop (drag = move, tap = color);
  - axis (drag = move the whole thing, tap = a new stop);
  - unlimited stops, dragging one off the axis = delete it.
- The stop picker: the full Color panel + **opacity per stop**.
- Options:
  - Repeat;
  - Reflect;
  - Reverse;
  - Repeat + Reflect can be used together.
  - Default: off.
- **Radial**: from the center, the drag direction does not matter. A preview circle is visible during the drag.
- **Sweep (conical)**:
  - always 360°, a sector is made with transparent stops;
  - the drag length does not matter;
  - **Repetitions** with a circular slider, up to 100;
  - **Reflect default ON**: the axis = 180°, a new stop gives 2 symmetric sectors;
  - Reflect OFF: the axis = 360°, repeat without reflect = "rays".

## Pattern fill
- 30+ built-in tiles, import your own (an Imported section). Transparency is supported. Seamless PNGs are better.
- Drag from the center. The first time the pattern is "cloud", afterwards the last one.
- Handles:
  - move (the start point);
  - end (scale + rotation);
  - the axis = the bottom edge of the tile;
  - the outline of a single tile.
- Color controls: Hue, Saturation, Brightness, Contrast, Opacity. They apply to the current fill, the values are not reset.
- Sampling mode as for the others.

## Fill brushes
- The Fills folder: Solid, Screentone, Debris, Grunge, Scratch, Checker.
- You draw a contour → the area gets filled. They are not created from scratch, only by duplicating an existing one.
- With the Rectangle, Circle, Path, Arc shapes they produce filled shapes.
- **Draw preview** (global): the fill is visible during the stroke.
