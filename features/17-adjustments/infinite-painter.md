# Infinite Painter: color correction

Source: User Guide /painter/adjustments/*.

## Tools (Editing Tools → Adjustments)
- Destructive, only the active layer or the selected fragment.
- Non-destructively: Adjustment layers (11 types: these 9 + Curves + 1 more, the exact list to be confirmed against the app).

| Group | Correction | Behavior |
|---|---|---|
| Light | Brightness | −100…+100%. −100: brightness −50% (white → 50% gray), +100: black → 50% gray |
| Light | Contrast | — |
| Light | Exposure | "natural" lightness, black stays black |
| Light | Gamma | midtones |
| Color | Saturation | −100% = B/W |
| Color | Vibrance | saturates muted colors |
| Color | Temperature | ≈ the Lab b* axis (blue ↔ yellow) |
| Color | Tint | ≈ the Lab a* axis (green ↔ magenta) |
| Color | Hue | ±100% = ±180° on the HSB wheel |

- Shared controls:
  - a −100…+100% slider, default 0;
  - Masking menu;
  - Invert mask (it also inverts the global selection);
  - Hide effect (before/after);
  - Cancel, Confirm.
- Within one session you can change several parameters, the mask is shared.
- Remembers the last correction.

## Quick masks (shared by Adjustments and Filters)
1. **No Mask** (respects the global selection).
2. **Focus**: radial and soft. The center, the inner ring (100%), the outer ring (0%). The rings together = a hard edge.
3. **Tilt-shift**: a linear band. The center (it is also the pivot), the inner/outer edges. Rotation by dragging between the edges, 45° snap.
4. **Highlights**, 5. **Midtones**, 6. **Shadows**: tonal ranges.
7. **Saturation**: the strength ∝ the pixel's saturation.
- All of them are invertible and combine with a selection.

## Tonal Curve (Curves)
- Channels:
  - γ (the RGB composite, it opens on it);
  - R, G, B;
  - **A (alpha)**: cleans up the blurred edges of layers and selections.
- Nodes: a tap = add, a drag = move, a drag onto a neighbor = delete. Removing all nodes = a linear reset.
- Hide preview, Cancel, Confirm.
- Examples from the docs:
  - inverting only γ = a "gamma negative" (the hues are preserved);
  - inverting R, G, B = a true negative.
