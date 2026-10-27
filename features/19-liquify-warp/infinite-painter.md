# Infinite Painter: Liquify

Source: User Guide /painter/liquify/*.

## Model
- The deformations are written into an **invisible displacement grid**, the source is not damaged. The grid is editable until the tool is closed.
- Inside a session the regular undo does not work: the steps are not undone one by one. There are Restore and Rewind. After closing, the whole session = one undo step.
- Confirm applies and discards the grid.
- Works with Symmetry.
- Works on a selection mask (in the Selection workspace).

## Toolbar
- Cancel.
- Show/Hide grid (visible only while the tool is running).
- **Size** (max = the canvas size). In Rewind the slider = the degree of rollback.
- Hide effect.
- Confirm.

## Tools
- Deformation:
  - **Move (push)**: the universal one, "sculpting";
  - **Pinch**;
  - **Bloat**;
  - **Swirl CCW**;
  - **Swirl CW**.
- Restoration:
  - **Restore**: locally smooths the grid to 0 (fast);
  - **Rewind**: globally weakens the whole deformation of the session (a slider from "no deformation" to "current").
- Strength, pressure, falloff are not described in the docs. In ibisPaint the Liquify Pen has more parameters, see ibispaint.md.

## Other deformations
- Warp and Distort in Transform (16). Fisheye as a filter (18).
