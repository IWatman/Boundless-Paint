# Infinite Painter: blend modes

Source: User Guide /painter/layers/blending/*.

- 30 modes. Passthrough is not counted, the 3 masking modes are included in the number.
- The same modes for layers, groups and brushes (Brush Editor → Stroke → Blending mode + Strength).
- For a brush the mode compares the stroke color with the active layer.
- The Opacity slider is visible next to the selector and works in real time.

| Group | Modes |
|---|---|
| Normal | Normal, (Passthrough: groups only) |
| Darkening | Darken, Multiply, Color Burn, Linear Burn (A+B−1), Darker Color |
| Lightening | Lighten, Screen, Color Dodge, Linear Dodge, Lighter Color |
| Contrast | Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix |
| Comparative | Difference, Exclusion, Negative, Subtract, Divide |
| HSL | Hue, Saturation, Color, Luminosity |
| Masking (unique) | Erase, Mask, Line Art |

## Masking modes
- **Erase**: the alpha of the layer "cuts out" the layers below, non-destructively. A brush in Paint with this mode works like an eraser.
- **Mask**: the brightness of the layer masks the lower ones. Dark hides, white and transparent reveal. The equivalent of "group + mask", only faster.
- **Line Art**: like Mask, but the darkest parts stay visible as a line. Several line art layers can be stacked, each with its own mask.

## Notes for the implementation
- The standard modes match PS/Procreate. Take the public formulas (W3C Compositing & Blending Level 1 and the standard formulas for Vivid/Linear/Pin Light/Hard Mix/Darker/Lighter Color/Subtract/Divide).
- **Negative**: non-standard. Presumably 1−|1−A−B| (like "Negation" in Krita). Verify empirically.
- Take the gamma-correction mode into account (05-color): blending in linear space.
