# ibisPaint: layers

Source: ibispaint.com/lecture №5, 24, 29–32, 34, 95, 96, 135, 153, 156, 163, 173, 180, 191, 195.

## Layer Window
- Layer row: visibility, **Reorder handle** (drag), opacity.
- **Selection Layer** (pink, always on top): the selection is drawn with a brush and an eraser and is shown in light blue. Color is unavailable on it.
- Buttons:
  - **Clear Layer** (with a selection: only the selected area);
  - **Invert Layer Color** (negative; with a selection: only the selected area);
  - **Move Layer** (transform);
  - **Flip Layer H / V**;
  - Merge Down;
  - Delete;
  - Edit Adjustment Layer;
  - Add Layer;
  - **Import from Photo Library**;
  - **Flip Canvas H / V** (all layers);
  - **Clipping**;
  - **Alpha Lock** (not for folders and vector layers);
  - Opacity.
- Command menu (︙):
  - **Clear White (Grayscale)**: to gray + white becomes transparent (for a scanned line art);
  - **Clear White (Color)**: white becomes transparent while the color is preserved;
  - **Select Opacity** (selection by alpha);
  - Rename;
  - **Rasterize** (vector → raster);
  - **Save Layer as Transparent PNG** (a layer or a folder into the photo album).
- Add Special Layer:
  - Add Folder;
  - **Add Vector Layer**;
  - Add Adjustment Layer;
  - Duplicate (a layer or a folder);
  - **Add Layer from Folder** (a flattened copy of the folder);
  - **Add Layer from Canvas** (a composite of the whole canvas as a new layer on top).
- Background row:
  - Background Color;
  - **Transparent (Light / Dark)** checkerboard;
  - **Canvas Paper**.
- Blending mode list (+ the **Screentone** item).
- **Floating Layer Window** (tablet/PC): drag by the Move Bar, resize by the corners and edges, Close.
- Settings:
  - Layer Row Height (normal and floating);
  - Drag Left Side to Reorder;
  - **Thumbnail: Whole / Only Contents**;
  - Set Added Layer as Current.
- The number of layers is unlimited (limited by memory).

## Folders
- Add Folder above the selected layer.
- Entering a folder: swipe the reorder handle to the right (only from the layer directly under the folder). Leaving: swipe left (the bottom layer of the folder).
- Tapping the preview = collapse/expand. Rename.
- **Move Folder**: transforms all the layers of the folder together. If there is a vector or adjustment layer inside, a dialog: Yes (rasterize), No (transform only what is possible), Cancel.

## Vector layer
- Lines are stored as vertices and paths ("brush shapes"). They can be scaled without loss, and after drawing you can change the brush, thickness and color.
- Creation: Add Vector Layer, or the Vector Dip Pen / Vector Felt Tip Pen brushes (they create the layer automatically).
- Vector tool:
  - tap = select a shape;
  - drag diagonally = multi-select;
  - tap again = deselect;
  - drag/zoom/rotate the selection;
  - Edit (brush, line type, color).
- Vector Command menu:
  - **Edit Vertices**;
  - Bring to Front / Send to Back / Reorder Shapes;
  - Flip H/V;
  - Deselect;
  - Delete;
  - **Convert to Polyline** (straight lines, rect, polygon from the Drawing Tool);
  - **Convert to Curve** (circle, ellipse);
  - **Break Loop / Make Loop**;
  - **Simplify** (Strength, Angle Threshold, Show Points).
- Edit Vertices:
  - drag vertices;
  - marquee selection;
  - Delete Selected Vertices;
  - long-press on a vertex = handle mode.
  - Handles: **Smooth** (linked, symmetric length), **Normal** (linked, separate lengths), **Angle** (independent).
- The density of the vertices depends on the stroke speed.
- **Vector Eraser**:
  - Mode: **Up to Intersection** / **Entire Line**;
  - Line End: Tapered / Round;
  - tracing a short line over something = delete it.
- The raster eraser on a vector layer creates invisible **eraser shapes** (masking shapes that can be moved). On merge they can hide the lines of the layer below; this is fixed with Bring to Front.
- Unavailable on a vector layer: Blur, Smudge, Alpha Lock, Perspective/Mesh transform, filters. Rasterize is required (irreversible).

## Adjustment layer
- Add Special Layer → Add Adjustment Layer → choose a filter in the Filter Selector → parameters → ✓.
- It acts on all layers below. **Readjust**. The strength = the layer opacity.
- Settings: Save Adjustment Layer Parameters, Reset.
- The list of types = a subset of the filters (Adjust Color and others), see 17/18.

## Clipping and Alpha Lock
- Clipping to the layer below. Alpha Lock preserves the alpha of the pixels.

## Screentone (a layer property)
- The "Screentone" blend mode turns the layer into a halftone screen (a pattern, for example Dot L6). The density of the tone = the brightness of the color.
- It is painted with a brush or a fill. 46 tones (store listing). More in 21.

## Canvas Paper
- A background texture (22+ papers: Cloth, Fabric, Gauze, Steel Plate, Glass, Carpet, Japanese Paper, Mat 1–4, Normal 1–3, Rough 1–4, Streak 1–2, Watercolor 1–2, Crumpled 1–2). Opacity is adjustable. It darkens the canvas.
- The background color of the backing canvas (Settings) is the color of the work area, not of the artwork.

## Contents Layer Selection
- The eyedropper or the quick eyedropper shows a button with the name of the layer on which the pixel under the cursor is painted. A tap = make it current.
- Alt+Shift+drag (Option+Shift on Mac). Disabled in Settings.

## What to add to the IP model
- A vector layer + vector tool + vector eraser.
- Selection Layer as an editable layer (in IP the mask is edited in the workspace, which is the equivalent).
- Clear White, Invert Layer, Flip layer/canvas H/V, Add Layer from Canvas/Folder, Save Layer as PNG.
- Contents Layer Selection.
- Thumbnail: Only Contents.
- Screentone as a layer property.
- Transparent bg light/dark.
