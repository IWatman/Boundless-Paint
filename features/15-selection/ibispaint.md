# ibisPaint: selections

Source: ibispaint.com/lecture №9, 24, 52, 162, 169.

- Tools: **Lasso**, **Magic Wand** (settings like Bucket: strength, expansion, gap, reference layer, boundary), **Selection Layer** (drawing a selection with a brush and an eraser), **Color Range**, Select Opacity (by the layer's alpha).
- Modes: **Set** (replace), **Add**, **Subtract**.
- Commands: Invert Selection, Clear.
- Selection Area window:
  - **Expand/Contract** (a slider);
  - **Cut**;
  - **Copy** (of all layers, merged);
  - **Paste**;
  - Remove Selection Area.
- **Color Range**:
  - up to **5 reference points** (movable);
  - **Fuzziness**;
  - **Range**;
  - Show Selection.
- Display: a dashed outline + a light blue fill on the Selection Layer.
- Transforming the selection: drag, pinch, Rotate ON.
- Settings: "Save Selection Area" (the selection is preserved after going back to the gallery).

## What to add to IP
- A Set mode (an explicit replace) in addition to Add/Subtract. Intersect exists in IP's boolean.
- Color Range with several reference points + Fuzziness/Range (IP has one tap + tolerance).
- Copy/Cut/Paste to the system clipboard (IP has a permanent Clipboard library, there is no regular copy-paste). **Both are needed.**
- Keeping the selection between sessions.
