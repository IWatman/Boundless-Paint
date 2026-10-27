# Infinite Painter: workspace, UI, gestures

Source: User Guide, the Studio workspace section (/painter/studio/*).

## Studio structure
- A single workspace. The shared controls stay in place with any tool.
- Tools with their own workspace (modal mode):
  - Selection;
  - Fill;
  - Transform;
  - Color adjustments;
  - Filters;
  - Liquify;
  - Clipboard.

### Always visible
- Home: close, save, back to Home.
- Two Tools menus:
  - **Creative**: Fill, Text, Clone, Guides (5), Shapes (5), Symmetry, Perspective (6), View;
  - **Editing**: Selection, Transform, Adjustments, Filters, Liquify, Patterns, Canvas.
- The Layer panel toggle.
- Options menu: New, Open, Save, Import, Record, Export, Community, Classroom, Settings.
- The canvas.
- The Main toolbar.
- Undo / Redo. Redo only appears after an undo.

### Conditional elements
- Top bar:
  - the Tool settings gear: the settings of all active tools in one menu;
  - the Selection menu and the selection toggle, when there is a selection;
  - the Reference menu (pushpin), when there are references;
  - the Custom toolbar.
- Mini-toolbars at the top right for the active tool.
- Parameter popups (for example, opacity).
- Shortcut T: Creative Tools.
- The Tools menus are linked and switch by swiping.
- The Split Tools menu option: off → a single menu with two tabs.
- The chevron = a horizontally scrollable row of icons.

## Main toolbar
- Brush modes: **Paint**, **Blend** (smudging), **Erase**. Tapping a mode again opens the brush selection for that mode.
- Size:
  - tap: popup;
  - drag up/down: change the size.
- Color:
  - tap: the Color panel;
  - drag up/down: HSB brightness (hue is preserved at 0% and 100%);
  - pull outward: eyedropper;
  - in a horizontal toolbar the axes are swapped.
- In Blend/Clone the color is replaced by a gear. For effect brushes there is an effect-strength control instead of color.
- Opacity:
  - tap: popup;
  - drag: change;
  - it can be reassigned to Flow or Softness.
- The Recent Colors strip (color history) can be docked to the toolbar.
- The toolbar:
  - is moved with two fingers to any place and snaps to the edges;
  - in the bottom corner it turns into a compact form (legacy, for phones);
  - long-press on the compact-form icon: undock.

## Undo
- Tap: undo. Long-press: clear the current layer (also undoable).
- Gestures: 2 fingers = undo, 3 fingers = redo.
- The History button appears after the first undo. It opens a history slider: left = undo, right = redo.
- The limit is 64 steps in v7 (the Gestures section says 32: a discrepancy in the doc).
- A step = a stroke or a complex operation.
- Undo works only within a session. For a long history there are project versions.

## HUD
- At the top of the screen. Real-time numeric values for size, opacity, flow.
- A visual ring:
  - the inner one = the current size;
  - the outer one = the maximum brush size;
  - zoom is taken into account, so the ring shows the real size.
- Undo/redo are also shown in the HUD.

## Customization
- Widgets:
  - Navigator (it can be monochrome for checking tone);
  - Color wheel;
  - the Main toolbar as a widget;
  - Reference images.
- Light theme (in Settings).
- The Layer panel in collapsed form.
- Custom toolbar (tablets only):
  - long-press a tool icon → drag it into the top bar;
  - reorder, delete (drag it outside);
  - icons from Tools, Options, Layer options;
  - the number of slots depends on the screen.
- Immersive mode:
  - a 4-finger tap: hide the whole UI, the tool stays active;
  - what is not hidden: references, color wheel, navigator, the mini-toolbars of the active tools;
  - gestures and the keyboard keep working.

## Gestures
- Pinch: zoom. Two-finger rotate: rotation. Both at the same time.
- A quick pinch + release: fit-to-screen.
- Double tap with one finger: fit-to-screen (reassignable).
- Long-press on the canvas: an assignable action, eyedropper by default.
- Long-press in lists: an options menu for brushes, brush folders, projects, palettes, swatches, textures, patterns, the clipboard.
- Long-press + drag: moving layers, projects, brushes, brush folders, toolbar icons.
- 3 fingers vertically: size. 3 fingers horizontally: opacity (or flow/softness). This also works for other tools.
- Touch-Alt (the stylus draws, the finger touches the screen):
  - for brushes: eyedropper or the assigned Finger function (Blend, Erase, move canvas);
  - Rectangle: a square;
  - Line: 15° steps;
  - Ellipse: an axis in 15° steps;
  - Move: 45°;
  - Resize: 100% steps;
  - Gradient / Pattern fill: an axis at 45°;
  - Crop / Resize canvas: preserve the proportions.
- After the stylus has been used, some functions are available only to the stylus (a finger = navigation).

## Floating widgets
- Several at once, free placement, pinch to scale.
- Navigator, Reference images, Color wheel.

## Reference images
- Import: Import → "Import As Reference". They are stored in the project, no limit. Deleting one does not touch the original.
- Two fingers: move, scale, rotate. A tap raises it to the top. It can be partly off the edge of the screen.
- Double tap: flip.
- Dragging the stylus onto the ✖ in the center: quick hide.
- Color sampling: a stylus tap, a finger in Normal mode, the eyedropper in the Color panel.
- Reference menu: previews, visibility, deletion.
