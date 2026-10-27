# ibisPaint: UI, gestures, keyboard

Source: ibispaint.com/lecture №04, 81, 151, 167, 197, 199.
We do NOT carry over the ibisPaint UI; the interface will be like IP's. Only the features IP lacks are listed here.

## Structure (for reference)
- Main toolbar: Brush/Eraser, Tool Select, Properties, Color, Full Screen, Layer, Back.
- Tool Select (15):
  - Transform;
  - Magic Wand;
  - Lasso;
  - Filter;
  - Brush;
  - Eraser;
  - Smudge;
  - Blur;
  - Special Pen;
  - Bucket;
  - Vector;
  - Text;
  - Frame Divider;
  - Eyedropper;
  - Canvas.
- Top-right: View Menu, Selection Area tool, Stabilizer, Ruler, Material.
- Tool Select: position Left / Right / Popup (1–3 columns), labels on/off.
- Floating Layer Window (tablet/PC): a movable, resizable layers window.
- Reference Window (see 14).

## Gestures (new relative to IP)
- **Cancel drawing**: while holding the drawing finger, tap with a second one. The stroke is cancelled (IP has no such thing).
- Quick Eyedropper: long-press, magnifier, the delay is configurable. Cancel with a second finger.
- 2-finger tap = undo, 3 = redo (same as IP).
- Mouse/PC:
  - Space+drag = pan;
  - Z+drag or Alt+Space = zoom;
  - R+drag or Shift+Space = rotate;
  - wheel = zoom, Shift+wheel = rotate (direction inversion in the settings);
  - right mouse button = quick eyedropper;
  - **Alt+Ctrl+drag = brush size**.
- Trackpad: pan or zoom, depending on the setting.
- Shift+/ = the list of shortcuts. `[` `]` = size. ANSI/JIS layout.
- **Show Message on Undo** (toast), **Sound** (undo sound).
- **Crosshair** at the pen tip on hover. Brush cursor: None / Thickness mark / Brush pattern.
- **Palm rejection** (see 25).

## What to take into the IP UI
- The cancel-drawing gesture.
- Floating layer window on tablets (IP's layer panel is already floating, so an undock option can be offered).
- A brush-pattern cursor and a crosshair on hover.
- Alt+Ctrl+drag for size on desktop.
