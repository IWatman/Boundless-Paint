# Infinite Painter: filters

Source: User Guide /painter/filters/*. 41 filters. 27 of them are available as non-destructive Filter layers (the exact list is not given in the docs).

## Shared toolbar
- Cancel.
- **Variant selector** (for the filters that have variants, cyclic).
- Another filter (it will ask: apply or discard the current one).
- Masking menu (quick masks, see 17).
- Invert mask.
- Confirm.
- Hide effect.
- Strength (a value + a slider, usually 0…100%, for some −100…+100).
- Filter-specific controls on the canvas: the center, the direction.
- Filter selector: a long press on Compare = before/after.
- It is applied to a layer or a selection, and also to a layer mask or a selection mask.

## Photographic (11)
| Filter | Strength / behavior |
|---|---|
| Black & White | −100…+100 (light ↔ dark B/W) |
| Fade | 0–100%, permanently lowers the alpha |
| Glow | 0–100, the width of the glow of the highlights |
| Invert ×4 | RGB / Chroma / Luminance / Alpha, −100 (original) … +100 (inverted). Alpha 100% → transparent becomes gray #808080 |
| Sepia | 0–100 |
| Grain ×3 | Mono / RGB / Alpha noise |
| Bokeh | 0–100, depends on the resolution |
| Local Contrast | 0–100 |
| Warm | −100 (cool) … +100 (warm) |
| Lens flare | 0–100, drag = the center |
| Gradient map | a gradient editor like in Linear fill, without transparency, no limit on the stops |

## Structure (11)
| Filter | Behavior |
|---|---|
| Blur | a true Gaussian (the Blur of the filter layer is different, not Gaussian, algorithmic) |
| Smooth | edge-preserving noise reduction |
| Bloom / Gloom | brighten / darken the light areas |
| Dilate / Erode | morphology; on line art it thickens / thins the lines |
| Scatter | frosted glass |
| Sharpen | — |
| High-pass | a high-pass copy + Hard Light, essentially an unsharp mask |
| Emboss | the relief height, B/W, the light direction is fixed |
| Fisheye | −100 (pinch) … +100 (fisheye), drag = the center |

## Artistic (16)
| Filter | Behavior |
|---|---|
| Halftone ×4 | Grayscale / B&W / CMYK / Alpha |
| Manga | B/W: edges → lines, flat fills → screentone; strength = brightness |
| Hexagons | size ≈ px, the average over the cell |
| Pixelate | the block size |
| Stained Glass | Voronoi |
| Stamp | 1-bit, a threshold |
| Edges | black edges on transparent |
| Trace | black edges over the original |
| Stereo | an RGB channel offset (chromatic aberration), the background is transparent |
| Kaleidoscope | 1–12 mirrors, drag = the center |
| Palette | posterize to 2–48 colors (the palette from the image) |
| Monet / Van Gogh | impressionism / swirls |
| Pop-art | halftone + a color boost |
| Pencil | hatching, the stroke length |
| Paintbrush | oil painting |

## Motion (3)
- **Direction**: motion blur, drag = the direction.
- **Zoom**: radial zoom blur, drag = the center.
- **Spin**: −/+ = counter-clockwise / clockwise.

## Filters in brushes
- See 04: a brush applies a filter or a correction to the layer under the stroke or to the stroke itself.
