# UI components (design system)

Structure and behavior modelled on IP (features/25-input-stylus-settings/infinite-painter.md). Visuals are our own.

## Theme
- Dark (default) and Light. Material 3 tokens, our own accent **#4F8CFF** (not red as in IP).
- Active element: accent color + 16% fill.
- Corner radii: 12 dp panels, 8 dp buttons. Icons 24 dp, tap target ≥ 44 dp.
- UI font: system (Roboto).

## Components
| Component | Behavior |
|---|---|
| IconButton | tap. Optionally long-press (secondary action, tooltip hint) |
| IconToggle | active = accent. Also works in the custom toolbar |
| LinearSlider | label, track, value + units. Drag with a value bubble. Tap on the edge = ±step. Tap on the number = input |
| CircularSlider | arc + number, drag in any direction. Tap = popup with a LinearSlider |
| VirtualSlider | invisible: drag from the button up/down (or left/right for a horizontal toolbar), the HUD shows the value |
| JogSlider | the handle returns to the center, the rate of change ∝ the deviation (text size) |
| SelectorList | dropdown |
| CurveEditor | XY graph, nodes (tap add, drag, drag onto a neighbor = delete), presets, invert |
| MiniToolbar | top right, stack. The first icon = the tool (tap = close) + toggles |
| ToolSettingsMenu | gear in the top bar: sections of all active tools |
| BottomWorkspaceBar | for modal workspaces: Cancel · tools · Confirm |
| Popup panel | anchored to the button, closes on a tap outside |
| FloatingWidget | drag, pinch scale, double tap = flip (for navigator/ref), close by dragging onto the ✖ in the center |
| HUD | top center: the value + a brush size ring at the canvas scale |
| Toast | undo/redo messages (optional) |

## Main toolbar
- Vertical, by default at the left edge.
- Order: Paint · Blend · Erase · [Clone] · ─ · Size · Color · Opacity.
- Dragging with two fingers, snapping to the edges. Compact mode in the bottom corner (M5).
