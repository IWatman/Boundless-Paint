# ibisPaint: filters (84+)

Source: ibispaint.com/lecture №53–76, 86–88, 92, 94, 97–102, 111–117, 120–131, 134, 136, 142–150, 160–161, 164–165, 176–177, 179, 181, 194. The pages usually show 1–3 sliders without ranges. The parameters are given where they are named.
General: Tool Select → Filter → category tabs. Sliders + handles on the canvas. ✓. It acts on a layer or a selection. Some of them are available as an Adjustment layer. Settings: Save Filter Parameters.

## Adjust Color
| Filter | Parameters |
|---|---|
| Brightness & Contrast | brightness, contrast |
| **Tone Curve** | RGB/R/G/B tabs, nodes |
| Hue Saturation Lightness | H, S, L |
| Color Balance | the balances |
| **Extract Line Drawing** | White, Black, Middle (50%): a scan → line art |
| **Find Edges (Handwriting)** | Thickness, Amount of Lines, Sensitivity… |
| Find Edges | Algorithm, Smoothness, Black, White, Intermediate |
| **Change Drawing Color** | a color, sliders (recolor the line art) |
| Mono Color | — |
| Grayscale | — |
| Black & White | a threshold |
| Posterize | levels |
| Invert Color | — |
| **Gradation Map** | stops, dark on the left, light on the right |
| **Levels Adjustment** | a histogram, Auto, Shadows/Midtones/Highlights, the R/G/B channel |
| **Replace Color** | up to 5 reference points, an HSL replacement for each, Fuzziness, Range |

## Blur
- Gaussian Blur (Radius).
- **Zooming Blur**.
- **Moving Blur**.
- **Spin Blur**.
- **Lens Blur** (Radius, Brightness, Direction).
- **Mosaic**.
- **Unsharp Mask**.
- **Frosted Glass**.

## Style (layer-style effects, raster)
- Stroke (Both / Outer).
- Stained Glass.
- Wet Edge.
- Glow (Inner / Outer).
- Bevel (Inner / Outer).
- Emboss.
- Relief (+ Relief HQ, smart).
- Waterdrop (Rounded).
- Satin.
- **Drop Shadow** (sliders + a color).
- **Extrude**.
- **God Rays**.

## Draw (generators)
- **Parallel Gradation**, **Concentric Gradation**, **Radial Line Gradation**: gradient fills.
- **Radial Line** (focus + shape), **Speed Line** (position, length, thickness): manga effects.
- **Clouds**.
- **QR Code** (generating a QR from text).

## Smart (AI)
- **Watercolor** (Saturation, 1.5 pt).
- **Background Removal**: tap an object (blue) / minus = the background (red), Show Mask.
- Relief HQ (1 pt).

## Artistic
- **Anime Background** (Brightness, Contrast, Saturation…).
- **Manga Background** (Contrast, Edge Darkness, Screen Tone Size…).
- **Chromatic Aberration** (an RGB shift).
- **Glitch**.
- **Noise**.
- **Retro Game**.
- Chrome.
- **Bloom**.
- **Cross Filter** (star flares).
- Sheer.

## Pixelate
- Crystalize.
- Hexagonal.
- Square.
- Triangular.
- Pointillize.
- Dots (Hexagonal / Square).

## Transform (deformations)
- Expansion.
- Fisheye Lens.
- Sphere Lens.
- Wave.
- Ripple.
- Twirl.
- Polar Coordinates.

## Frame
- **Table** (a backing frame).
- **Blur Frame** (a vignette blur along the edge).

## Movie
- **Rain** (animated in video mode?).

## Comparison with IP (41 filters)
- The same in essence: Blur, Motion/Zoom/Spin, Pixelate/Hexagons, Stained Glass, Emboss, Fisheye, Bloom, Halftone≈Dots, Posterize≈Palette, Gradient map, Invert, B&W, Noise≈Grain, Chromatic≈Stereo, Edges≈Find Edges, Manga.
- ibis only:
  - the Tone Curve filter (in IP Curves is a separate tool);
  - Levels;
  - Replace Color;
  - Color Balance;
  - Extract Line Drawing;
  - Find Edges (Handwriting);
  - Change Drawing Color;
  - Unsharp Mask;
  - Frosted Glass (≈IP Scatter);
  - Lens Blur;
  - Mosaic;
  - all the Style effects (Stroke, Glow, Bevel, Satin, Drop Shadow, Extrude, God Rays, Wet Edge, Waterdrop, Relief);
  - the Draw generators (gradients, Radial/Speed Line, Clouds, QR);
  - Smart (Watercolor, Background Removal);
  - Anime/Manga Background;
  - Glitch;
  - Retro Game;
  - Chrome;
  - Cross;
  - Sheer;
  - the pixelations (Crystalize, Triangular, Pointillize);
  - Sphere, Wave, Ripple, Twirl, Polar;
  - Table, Blur Frame;
  - Rain.
- IP only: Fade, Glow, Sepia, Bokeh, Local Contrast, Warm, Lens flare, Smooth, Gloom, Dilate, Erode, High-pass, Stamp, Trace, Kaleidoscope, Monet, Van Gogh, Pop-art, Pencil, Paintbrush, the Invert variants, the Grain variants, the Halftone variants.
- The result of the union: about **110 unique filters**. Prioritization in `/spec/`.
