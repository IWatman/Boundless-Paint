# Infinite Painter: layers

Source: User Guide /painter/layers/*.

## Model
- A regular layer: RGBA, the size of the canvas. The layer-count limit = memory. It is shown in the canvas size dialog.
- One layer is active. Exception: Transform, where multi-select is possible.
- Special layers (not bitmaps):
  - Adjustment;
  - Filter;
  - Panels (comic frames);
  - Text.
- **Paper**: the bottom background, it is not drawn on and not moved.
- There is no multi-select of layers in the panel.

## Layer panel
- A toggle in the top bar.
- Collapsed (default): a slide left = expand, right = collapse.
  - the Add button;
  - a mini eye;
  - a tap = activate, a second tap = options;
  - long-press + drag = order (it temporarily expands);
  - **pinch on the layers = group them**;
  - the Paper icon.
- Expanded:
  - Add layer (above the active one);
  - Add special layer;
  - New group (from the active one);
  - the Visibility mode indicator (→ Rendering panel);
  - tiles.
- Tile:
  - a preview with badges for the blend mode (if not Normal) and opacity (if <100%);
  - the name;
  - the eye.
  - Active: a red frame. Inactive: a gray one.

## Layer options
- Rename: any characters, emoji, not unique.
- Blending mode (default Normal).
- Opacity 0–100%. At 0 the layer is not editable.
- Hide: a hidden layer is not editable.
- Duplicate: the copy goes above the original, the name stays the same.
- Clear: undoable.
- Lock transparency (alpha lock).
- Merge (down):
  - the result takes the name and blend mode of the lower layer, opacity 100%;
  - merging with an adjustment layer bakes in the correction and removes it from the others;
  - merging with a group below collapses the group.
- Delete: by default NOT undoable.
- Clip (to the layer below).
- Select: a selection by the layer alpha.
- Mask.
- More… → **Hide when recording** (exclude from the timelapse).

## Groups
- Creation:
  - pinch on consecutive layers;
  - the Group button (a group from the active layer) + dragging the rest in.
- Unlimited nesting. More than 5 levels is impractical.
- Collapsed / expanded. A red folder icon = the active layer is inside.
- Group options:
  - Rename;
  - Blending (default **Passthrough**);
  - Opacity;
  - Hide;
  - Duplicate;
  - **Copy Merged** (a new layer = the composite, the group stays intact);
  - **Flatten**;
  - Merge (down);
  - Delete;
  - Clip;
  - Select (by the combined alpha);
  - Ungroup;
  - Mask;
  - Hide when recording.
- **Passthrough vs Normal**:
  - Passthrough: adjustment layers inside the group act on everything below the group;
  - Normal (and the other modes): the group is isolated, the corrections act only inside it.

## Layer masks
- 8-bit grayscale, one per layer. White = visible, black = hidden. A new mask is white.
- If there is an active selection, the mask is created from it.
- Supported: regular, adjustment, filter layers and groups.
- Editing: all brushes and modes, filters, corrections. The mask itself is not visible, only the result is. The active mask is outlined with a red frame.
- Mask options:
  - the name is fixed;
  - Strength (like the mask opacity);
  - Fill (with black);
  - Invert;
  - Clear (to white);
  - Hide;
  - **Apply** (bake it in and delete the mask);
  - Delete;
  - **Linked** (default on: it is transformed together with the layer);
  - Select (a selection from the mask).

## Clipping
- The layer is clipped by the alpha of the layer below (as in PS). A Clip toggle, the indicator = a downward arrow.
- Several layers onto one base, and a whole group can be clipped.
- An adjustment/filter inside a clip acts only on the base layer.
- An adjustment layer cannot be a base (it has no pixels). Panels and Text can.

## Special layers
- **Adjustment layers**: 11 types (see 17-adjustments).
  - The name = the type, it cannot be renamed.
  - **Strength** (default 0 = no effect) separately from opacity.
  - Merge = apply to the layer below. Merging two adjustments = an empty layer.
  - Select transparency is inactive.
- **Filter layers**: 27 filters are available as non-destructive layers, with the same controls.
- **Panels layer**, **Text layer**: editable, Rasterize is irreversible.
- All special layers: order, grouping, duplicate, deletion, blend, opacity, mask.

## Paper layer
- Options:
  - Paper color (a palette of paper shades);
  - Texture on/off;
  - texture selection (+ import);
  - **Texture depth** (the effect on the strokes of all layers);
  - **Texture opacity** (the visual strength);
  - **Texture scale**.
- Only the grayscale channel of the texture is used.
- Hidden Paper = the checkerboard and a transparent export. The texture remains visible in that case.
- PSD may not preserve the exact look of Paper.

## Rendering panel (tap on the visibility mode)
- Color proofing + gamma correction (see 05-color).
- Visibility modes:
  - **Normal**: everything is visible;
  - **Solo**: only the active layer. Tip: a sketchbook "by pages", flipping through the layers with the volume buttons;
  - **Trace**: the active layer is fully visible, the lower ones **progressively** dim. The layer opacities do not change. On exit you are offered to hide the "trace" layers.
