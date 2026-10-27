# ibisPaint: Liquify Pen, Mesh/Perspective Form

Source: ibispaint.com/lecture №35, 36, 138, 154.

## Liquify Pen (Special Pen)
- Parameters: **Thickness**, **Strength**.
- Modes:
  - **Drag** (push);
  - **Shrink** (pinch);
  - **Expand** (bloat);
  - **Smoothen** (smooth out the deformation);
  - **Revert** (restore).
- It works like a regular brush tool (without a separate workspace).

## Transform → Perspective Form
- 4 corners.
- Perspective on/off.
- **Repeat**:
  - Off;
  - Inner (tiling inside the quad);
  - Horizon (inside and outside, up to the horizon);
  - Full (like Horizon, alternating mirrored).
- **Repeat X/Y** (the count), **Phase X/Y** (the offset).
- Interpolation on/off.
- Use case: a floor texture in perspective.

## Transform → Mesh Form
- **Division X/Y** (the grid).
- **Smoothness**.
- **Draw Order** (the order in which the cells are overlaid).
- Use case: skirt folds and so on.

## Comparison with IP
- IP Liquify: Move, Pinch, Bloat, Swirl CW/CCW, Restore, Rewind + the grid + Symmetry.
- To add from ibis:
  - Strength;
  - Smoothen (an analogue of Rewind, but local);
  - a quick mode of the Liquify brush without entering the workspace (optionally).
- IP Warp (3×3 Bézier) versus ibis Mesh (N×M + smoothness + draw order): **merge** = Warp with a configurable grid.
- Perspective Form Repeat (Inner/Horizon/Full) — IP does not have it. **Add** it to Distort as a tiling option.
