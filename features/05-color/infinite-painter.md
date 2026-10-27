# Infinite Painter: color

Source: User Guide /painter/color/*.

## Color pipeline (important for the engine)
- Internal depth **64 bpp**: 16 bits per RGB channel + 16 bits of alpha. The goal: smooth gradients and strokes, safe editing.
- sRGB gamut. CMYK images are not supported, CMYK sliders are only converted to RGB. K does not deepen the black: black = RGB 0,0,0.
- **Gamma correction** (a toggle in the Layers panel or Settings):
  - mixing of semi-transparent areas "like light", less excess contrast on overlaps;
  - it affects soft edges, layer blending, semi-transparent gradients, filters;
  - it can produce banding;
  - changing the mode changes the look of old projects.
- **On-screen proofing** (only the display changes, not the pixels), Color mode:
  - sRGB (default);
  - CMYK profiles (Generic CMYK and others);
  - Grayscale;
  - Line Art (all layers in Line Art blending);
  - color-blindness simulation.
  - Since 7.1 the setting is global in Settings.

## Color panel (key C)
- 4 tool tabs:
  - HSB wheel;
  - Sliders;
  - Main swatch palette;
  - Lighting Orb.
- It remembers the last tab.
- A single current color. There is no separate background color. Everything is synchronized.
- The color in Fill and Paper does not change the main color.
- The same selector is used in Fill, Paper, Text color and some filters.
- Elements:
  - the Settings gear;
  - the previous and current color;
  - the eyedropper;
  - the compact palette;
  - tab navigation.
- **Floating swatch**: pull the current color out onto the canvas for comparison. It lives as long as the panel is open.
- **Compare tone**: shows the gray tone (HSB brightness) of the color under the swatch relative to the current and previous one. It lets you change H/S at a constant tone.

## Eyedropper
- Invocation:
  - the icon;
  - pulling out from the toolbar color indicator or Solid Fill;
  - a finger gesture;
  - a stylus button;
  - the I key;
  - long-press on the canvas (default).
- Drag → release = confirm. A ring shows the sample point.
- It samples the canvas and the references.
- Sample area: 1–100 px (averaging over the radius).
- Sample all layers (default ON):
  - ON: all layers + Paper (hidden Paper = white);
  - OFF: only the current layer. Adjustment layers above are ignored.

## Color history
- Up to 10 colors. The Recent strip on the toolbar + a section at the top of the Main palette.
- Dragging along the strip pages through it with a large preview.
- Nearly identical colors are averaged rather than added.
- The strip can be hidden.

## Color wheel (HSB)
- A hue ring (S=B=100%), an S-B field, a complementary color marker (computed in RGB, not in HSB).
- The outer ring of the pointer = the previous color.
- **Tone Lock** (tap on the hue pointer, default OFF): when the hue changes, S/B are adjusted automatically for equal perceived brightness.
- 6 styles (global):
  - Square (a large S-B field + a linear hue);
  - Round (S-B on a circle);
  - Default (an XY field inside the ring);
  - Diamond (S-B rotated by 45°: pure white and black are easier);
  - Triangle (saturated on the right, white at the top, black at the bottom);
  - Combo (S and B as two arcs + a large swatch in the center).
- **Widget**: pull it out onto the canvas with two fingers, pinch to scale, close by dragging onto the ✖.

## Sliders
- Models:
  - HSB: H 0–360, S/B 0–100;
  - RGB: 0–255;
  - LAB: L 0–100, a/b −128…+127 (interpretation: lightness, tint, temperature);
  - CMYK: 0–100.
- The slider bar is colored with the possible colors. Tapping the edges = a precise step. Tapping the number = input.
- **Hex**: input/copy, with and without `#`, the shorthand `#F00`.

## Swatch palettes
- **Collection**: the set of open palettes in the Main tab. The compact palette under the wheel/sliders is synchronized.
- Compact palette:
  - + adds the current color;
  - the name (palette selection);
  - horizontal scroll = switch palette.
- Collection menu: New, Open, Save (as a new one), Rename, Remove (from the collection only).
- **Library**: presets (cannot be deleted) + custom (no limit). Long-press: Rename, Share `.clrs`, Delete.
- Import:
  - `.clrs` (our own format);
  - **Smart palette** from an image (the image itself is not imported);
  - **Camera** (the photo is not saved).
- It is not compatible with Photoshop and other palettes.
- Editing:
  - + = add at the end;
  - long-press + drag = reorder;
  - dragging outside the palette = delete (without confirmation).

## Lighting Orb
- A sphere lit by a "light + base color + shadow" model.
- Controls:
  - a picker on the sphere;
  - Light color;
  - Base color;
  - Shadow color;
  - Hue and Saturation of the one selected out of the three;
  - Contrast (the intensity of light and shadow).
- The brightness of light and shadow is adjusted by Contrast, not by Saturation.
- A usage idea: warm light + a cool shadow.
