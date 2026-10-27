# Infinite Painter: Clone tool

Source: User Guide /painter/cloning/*.

## General
- Creative Tools → Clone. It works as a 4th brush mode: any brush will do. The Clone icon on the main toolbar opens the mode's options.
- Target = always the active layer.
- Source = any layer (Layer options → source). Default: the active layer at start.
- **Offset mode**:
  - the source crosshair is dragged;
  - the offset is constant;
  - **Align on**: the source moves with the brush, a new stroke continues the clone;
  - **Align off**: every stroke starts from the original point.
- Offset off = cloning "in place" between layers.
- Guides, shapes and symmetries work, except Smart. Blend and Erase work.

## Modes
1. **Photo**: an exact copy of the pixels through the tip and the brush texture. Retouching, photobashing. Default: offset on, the same layer.
2. **Artistic**: the source = a palette of colors in the brush tip. Options **Blur**, **Pull** (as in the Paint tab, they are saved into the brush).
3. **Smart**: the algorithm itself lays many short strokes along the source's contours while you move the brush.
   - Stroke length (0 = dabs);
   - Mix-in;
   - Smart rotation (follow the contours);
   - Edge contrast (−100…+100);
   - Complete strokes (look-ahead, "more painterly").
   - Some brushes behave unexpectedly.
