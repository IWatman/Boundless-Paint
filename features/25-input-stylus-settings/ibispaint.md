# ibisPaint: settings, stylus, input

Source: ibispaint.com/lecture №81 (the Settings window), 151, 167, 197, 198, 199.

## Gesture
- Undo with 2 fingers, Redo with 3 fingers.
- **Quick Eyedropper** + its own delay (a slider).
- View Rotation (on/off).

## User Interface
- Reference Window.
- Window Color (the theme).
- **Interpolation on Zoom In** + the default Display When Zoomed.
- Suggest Processing after Import (suggest Extract Line Drawing or Background Removal after an import).
- Tool Select Position (Left / Right / Popup), 1–3 columns, labels.
- Show Message on Undo.
- Sound.
- Layer Row Height (+ floating).
- Drag Left Side to Reorder Layers.
- Layer Thumbnail Whole / Only Contents.
- The Background Color of the workspace.
- Keyboard Layout ANSI/JIS.
- Show Keyboard Shortcuts. **Custom keyboard shortcuts**.
- Disable Contents Layer Selection with Eyedropper.
- Invert Mouse Wheel.
- Pan with Trackpad Scroll.

## Pressure / Touch Offset
- Use Pressure Sensitivity.
- A **Pressure Adjustment curve** (nodes: a tap = add, dragging outside = delete).
- Weak Sensitivity.
- **Touch Offset** (shifting the drawing point with arrows), Mouse Offset on PC.

## Cursor
- Brush Tool Cursor: None / Thickness Mark / **Brush Pattern**.
- Other Tool Cursor: None / Tool Icon.
- A **Crosshair** at the pen tip (hover).
- The mouse cursor on the canvas.
- Change Cursor by situation.

## Stylus
- The list of supported ones. Stylus Selection (Bluetooth styluses), Stylus Settings.
- **Palm Rejection**:
  - None;
  - **Hover** (while the pen is above the screen, the finger is ignored);
  - **Canvas** (the finger does not draw but controls the UI);
  - **Driver** (the pen only).
- **Double Tap / Squeeze** (Apple Pencil) or Button 1/2: assignable functions.
- **Barrel Roll** (rotating the pen → the brush angle).
- Vibration Intensity (Lenovo Tab Pen Pro).

## For Professionals
- Save Bucket / Stabilizer / Shape tool / Selection / Filter / Adjustment layer settings between sessions.
- Set Added Layer as Current.
- **Brush Thickness Depends on Canvas** (the thickness scales with the canvas size).
- Texture Memory Size (auto/manual).
- **Brush Stroke Prediction** (prediction to reduce latency).
- **Touch History Completion** (coalesced touches, detailed events).

## Reset / Other
- Show tutorials again.
- Reset basic/custom brushes, palettes, filters, adjustment params, stylus.
- Uninstall fonts.
- Remove All References.
- Delete Brush Pattern cache.
- **Backup Settings (.ipcfg)**.

## What to add to the IP settings
- Palm rejection modes.
- Touch offset.
- A pattern cursor and a crosshair.
- Barrel roll.
- Squeeze.
- Brush stroke prediction (≈ IP Low Latency Mode).
- Coalesced touches.
- Brush thickness relative to the canvas.
- Keeping the tool settings between sessions (toggles).
- Custom shortcuts.
- Backup/restore of the settings.
- Suggest processing after import.
- An undo toast and sound.
