# UI shell (modelled on Infinite Painter)

The interface repeats the structure of IP. The visual style is our own (see 00-clean-room/PROCESS.md). ibisPaint features are embedded into the slots of this shell (FEATURE-MAP.md, column "Where in UI").

## Screens
1. **Home**: tabs Discover · Home · Projects (swipe).
   - Home: Blank / From image / Pattern / Challenge / **Animation** / **Manuscript** + Recent.
   - Projects: gallery with folders, context menu, templates, trash, multi-select.
2. **New Project creator**: name, orientation, paper, size (px/mm/cm/in + dpi), presets, layer limit, type.
3. **Studio**: the main screen.
4. **Settings**: a full-screen panel of sections.
5. **Playback**: timelapse.

## Studio: persistent elements
| Zone | Element |
|---|---|
| Top bar left | Home · Creative Tools · Editing Tools · ⚙ Tool settings (conditionally) |
| Top bar center | Custom toolbar (icon docking, tablets) · Selection menu + toggle (conditionally) · Reference menu (conditionally) |
| Top bar right | Layers toggle · Options menu |
| Top right | **Mini-toolbars** of the active tools (stack) |
| Main toolbar (floating, docks to edges or a corner) | Paint · Blend · Erase · (Clone) · Size (virtual slider) · Color · Opacity/Flow/Softness (virtual slider) · Recent colors strip |
| Bottom left | Undo · Redo · History slider |
| Top | HUD (values, size ring) |
| Right | Layer panel (collapsed / expanded) |
| Floating widgets | Navigator · Color wheel · References · **Floating swatch** · **Timeline** (animation) |

## Modal workspaces (own toolbar at the bottom or top, Cancel/Confirm)
Selection · Fill · Transform · Adjustments · Filters · Liquify · Text · Panels/Frame divider · Clipboard · Crop/Resize/Straighten · Pattern tools · **Vector edit** · **Smart filters**.

## Menus
- **Creative Tools**:
  - Tools: Fill, Text, Clone, **Vector**, **Special pens**;
  - Guides: Lines, Ellipse, Hatching, Lazy, Pen, **Radial**, **Array**, **Perspective array**;
  - Shapes: Line, Rect, **Rounded rect**, Circle, **Polygon**, Path, Arc, **Arrow**, **Balloon**;
  - Symmetry;
  - Perspective;
  - View (+ **Grid overlay**, **Pixel view**).
- **Editing Tools**:
  - Selection;
  - Transform;
  - Adjustments;
  - Curves;
  - **Levels**;
  - Filters;
  - Liquify;
  - Patterns;
  - **Materials**;
  - Canvas (Crop, Resize, Straighten, Panels, **Canvas size**, **Color mode**, **Manuscript settings**).
- **Options**: New · Open · Save · Import · Record · Export · Community · Classroom · Settings · **Animation settings** (in an animation project).

## Gestures (basic IP + ibis)
- 1 finger/stylus: tool. 2 fingers: pan, zoom, rotate. Quick pinch: fit.
- 2-tap undo, 3-tap redo, 4-tap immersive.
- 3 fingers vertically: size, horizontally: opacity.
- Long-press: assignable (default eyedropper, magnifier, delay).
- Touch-Alt (finger while using the stylus): modifier.
- **Cancel stroke**: while holding a stroke, tap with a second finger.
- Double tap: assignable.

## Control principles (design system)
Linear / circular / virtual / dynamic sliders, selector list, curve graph, toggle (icon-toggle), mini-toolbar, tool settings menu. Description in features/25-input-stylus-settings/infinite-painter.md.

## Open UI questions
- Where the animation timeline should live: a bottom dock widget (proposed) or a separate workspace.
- Vector tool: a separate workspace (like Transform) or a brush mode on a vector layer.
- Desktop layout (mouse, keyboard): IP is Android/iPad only. We take the shortcuts of both programs + Space/Z/R.
