# Infinite Painter: settings, input, stylus, keyboard, UI controls

Source: User Guide /painter/technical/*.

## Application settings
1. **Account**: name, email, Log out.
2. **Interface**:
   - the Dark/Light theme;
   - the color wheel style;
   - **Scale of interface**;
   - Hide navigation bar;
   - Split Tools menu;
   - Recent Colors strip.
3. **Language**: 14 languages (EN, DE, ES, FR, ID, IT, JA, KO, NL, PL, pt-BR, RU, zh-CN, zh-TW). The default follows the system.
4. **Workflow**: Rotate canvas (on/off for two-finger rotation), Show layer changes (the layer blinks when it is selected).
5. **Rendering**:
   - **Low Latency Mode** (Disabled / Active / Fastest);
   - Color mode (proofing, default Standard);
   - Gamma correction;
   - **Deep color 64-bit** (default on).
6. **Saving**: Save changes prompt, History 1–10 / Unlimited / off (**default 6**).
7. **New project**: Timelapse on/off, Video quality 720p…4K.
8. **Gestures**: Double tap function, Long press function (assignable: eyedropper, flip, fit, etc.).
9. **Hardware**: Volume keys → a function (for example, paging through layers), Disable Back.
10. **Stylus** (it appears after a pressure stylus is detected):
    - the Finger function (Eyedropper, Blend, Erase, move canvas);
    - front button;
    - back button.
11. **Brush settings**:
    - Draw cursor (the tip's outline);
    - Shape detection;
    - global Pressure / Velocity / Tilt (a tap = disable for all brushes);
    - global calibration curves.
12. **About**.

## Stylus
- Capacitive: no pressure. Active: pressure and tilt.
- The very first contact of an active stylus separates the roles: the stylus draws, the finger navigates.
- The S Pen works out of the box, the side button is assignable.
- Calibration: pressure and tilt curves, globally.
- Touch-Alt (finger + stylus) see 02.

## Keyboard
| Action | Key |
|---|---|
| New / Open / Save | N / Ctrl+O / Ctrl+S |
| Paint / Blend / Erase / Eyedropper | B / K / E / I |
| Size −/+ | [ / ] |
| Layers | F7 |
| Tools menu / Brush editor / Color | T / Shift+Ctrl+B / C |
| Transform / Solid fill / Gradient / Path | V / F / G / P |
| Color adjustments / Curves | Ctrl+B / Ctrl+U |
| Undo / Redo | Ctrl+Z / Shift+Ctrl+Z |
| Flip / Fit | H / Shift+Ctrl+O |
| Lock transparency / Selection on-off | / / \ |
| Lasso / Rect / Brush sel / Wand | L / M / Q / W |
| Invert sel / Clear sel | Shift+Ctrl+I / Ctrl+D |
| Cheat sheet | hold Ctrl |

## UI control types (IP's design system, to be repeated in our UI)
- **Button**: tap, sometimes long-press, sometimes a gesture (a virtual slider).
- **Linear slider**:
  - a name, a track, a knob, −/+ zones at the ends, a value with units (%, °, px);
  - drag + a value bubble;
  - a tap on the edge = one step (1% for 0–100, about 3–4° for angles);
  - a tap on the number = keyboard input (in some of them).
- **Circular slider** (in toolbars): an arc + a number in the center. Drag left-right or down-up. A tap opens a linear popup.
- **Virtual slider**: hidden (Size and Opacity on the main toolbar).
- **Dynamic slider** (text size): it returns to the center, a relative change.
- **Selector list** (dropdown).
- **Curve graph** (XY, nodes).
- **Toggle**: a switch or an icon-toggle (active = red). On the custom toolbar it shows the state.
- **Mini-toolbar**: at the top right, for the tools without their own toolbar. They stack. The main icon = close.
- **Tool settings menu** (the gear on the left of the top bar): it gathers the settings of all active tools.

## Technical requirements (v7.2 Android)
- Android 9+, 6 GB RAM recommended, a pressure stylus.
- The whole project in RAM: the canvas and layer limits depend on the memory.
- PNTR = a ZIP archive of the project files, cross-platform.

## Canvas presets (the full list)
- Photo @300dpi: 3R 1500×1050, 4R 1800×1200, 4D 1800×1350, 5R 2100×1500, 6R 2400×1800, 8R 3000×2400, S8R 3600×2400, 10R 3600×3000, S10R 4500×3000, 11R 4200×3300, S11R 5100×3300, 12R 4500×3600, S12R 5400×3600.
- ISO:
  - A2 5613×3969 and A3 3969×2806 (240 dpi);
  - A4 3508×2480, A5 2480×1748, A6 1748×1240, A7 1240×874, A8 874×614, A9 614×437, A10 437×307 (300 dpi).
- US @300: Letter 3300×2550, Legal 4200×2550, Tabloid 5100×3300, Poster 7200×5400.
- Other @600: Business card 2079×1299, Postcard 3600×2400.
- Social: 2160² Square, Stories 1080×1920, Banner 2560×1440.
- Video: 720p, 1080p, Film 2K 2048×1556, 4K 4096×2160.
