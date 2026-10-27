# Infinite Painter: Pattern tools (multiplication)

Source: User Guide /painter/patterns/*. For the Pattern project see 01. For Pattern fill see 09. For Screentone brushes see 03/04. For the Halftone and Manga filters see 18.

- Editing Tools → Patterns. Source: the content of the current layer or a selection. Small isolated fragments work best.

## Symmetry pattern
- Radial multiplication of a ready-made fragment around a center (default: the canvas center).
- **Planes** 1–100 (copies + the original).
- **Fan angle** (100% = 360°).
- **Reflect** + the angle of the reflected set (a handle on the canvas).
- Real-time, Cancel/Confirm.

## Path pattern ("the leaf generator")
- Copies along a drawn path, they shrink toward the end.
- Copies 1–100 (changed after drawing), **Size jitter**, **Angular jitter**.
- The path cannot be edited, only redrawn. You can draw with guides and shapes.

## Quilt pattern
- A rectangular area is filled with a grid of copies.
- Handles:
  - base point;
  - vertical divisions;
  - horizontal divisions;
  - the area.
- More than 100 copies along each axis. It remembers the last values.
- **Reflect H / V** (alternation, default off).
- Put the original in a corner of the area.

## Tile pattern
- A control rectangle (separate handles + drag) → seamless tiling that **replaces the layer**.
- 2 reflection switches (H/V).
- **Save pattern** → the library (Imported) for Pattern fill. It also saves the symmetry settings. You can save without applying.
