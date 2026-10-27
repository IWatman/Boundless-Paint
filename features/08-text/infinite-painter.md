# Infinite Painter: text (v7.2+)

Source: User Guide /painter/text/*.

## Purpose and model
- Short text: headings, captions, balloons. Not typesetting.
- Each text object is stored as a separate **Text layer** and stays editable.
- LTR/RTL and non-Latin support. If the font has no glyph → the nearest system font is used.
- Formatting applies to the whole layer. There is no per-fragment formatting.
- Absent: paragraph indents, drop caps, hyphenation (7.2).

## Creation
- Creative Tools → Text. The layer is created above the active regular layer.
- It cannot be added if a special layer (adjustment, filter, panels, text) is active. The Tools menus are unavailable in that case.
- The default text is "Double Tap to Edit".
- The editor: a double tap or the keyboard button. Input through any IME, paste from the clipboard. OK.
- Confirm: close the workspace, return to the last working layer. Cancel: undo everything from the session.
- Editing again: activate the text layer.

## Text frame
- The frame always contains the whole text.
- Corner handles: scale the text. Side handles: the frame width (line wrapping), the height adjusts automatically.
- Dragging. Guides while moving and resizing:
  - green: the centers of the block;
  - red: the text edges;
  - gray: the lines;
  - blue: the canvas axes with snapping.

## Toolbar
- Keyboard.
- **Size**: the slider returns to the center (a relative "jog"), the value in px.
- **Fonts**:
  - families → variants (weights);
  - variable fonts: a slider for each axis;
  - "flexible fonts": extra settings.
- **Color** (fill). In Outline only mode this is the outline color.
- **Outline**:
  - Outline only / Outline + fill;
  - thickness in px (0 = off);
  - color.
- **Format**:
  - Align left / center / right;
  - Case: Normal, ALL CAPS, lowercase, Small caps (if the font supports it).
- **Spacing**:
  - Letter (0 = the font default, ±);
  - Word;
  - Line (% of the size, usually 100–120, <100 = overlapping lines).
- **Justify**:
  - Traditional (via letter and word spacing, needs >25 characters per line);
  - **Scale-justification** (fitting the height, that is the size, of each line to the width);
  - **Balance lines** (equalizing the line lengths without changing the spacing).
- **To Image**: rasterization, irreversible.

## Fonts
- 18 built-in Google Fonts families (OFL/Apache), >100 typefaces. Variable ones: Fraunces, Fredoka, Roboto Flex.
- Latin; some have Cyrillic and Greek. There is no Arabic, Hebrew or CJK, custom fonts are needed for those.
- Import TTF/OTF through "+" (the system file manager, the cloud). Installing directly from Google Fonts is not supported.
- Deleting a custom one: long-press → Delete. Built-in ones cannot be deleted.
- A missing font:
  - a warning on opening;
  - rendering with a system font;
  - after reinstalling, the look is restored automatically;
  - the font name can be copied.

## Techniques (not built in)
- A shadow = a rasterized copy. A complex outline = duplicated layers, clipping.
