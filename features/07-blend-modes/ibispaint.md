# ibisPaint: blend modes

Source: ibispaint.com/lecture №83. 28 modes (the app store claims 27; the difference is between versions).

| Group | Modes |
|---|---|
| Normal | Normal |
| Dark | Darken, Multiply, Color Burn, Linear Burn, Darker Color |
| Light | Lighten, Screen, Color Dodge, Linear Dodge, **Add**, Lighter Color |
| Contrast | Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix |
| Variations | **Invert** (inverts the lower layer, the color of the upper one does not matter), Difference, Exclusion, Subtract, Divide |
| Color | Hue, Saturation, Color, Luminosity |

## Differences from IP
- ibis has, IP does not:
  - **Add**: differs from Linear Dodge at opacity<1, brighter, for glow;
  - **Invert**.
- IP has, ibis does not: Negative, Erase, Mask, Line Art, Passthrough.
- In ibis the blend mode list has a separate item **Screentone** (apply a screentone to the layer), see 21.

## The combined set for the implementation
Normal, Passthrough (groups), Darken, Multiply, Color Burn, Linear Burn, Darker Color, Lighten, Screen, Color Dodge, Linear Dodge, Add, Lighter Color, Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix, Difference, Exclusion, Negative, Invert, Subtract, Divide, Hue, Saturation, Color, Luminosity, Erase, Mask, Line Art. **33** modes in total.
