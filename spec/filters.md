# Filters (combined list, our parameters)

- Common controls: Strength (unless stated otherwise: 0–100%, default 50), quick mask (none / focus / tilt-shift / highlights / midtones / shadows / saturation), invert mask, hide effect, variant.
- `live` = available as a Filter layer (deterministic, no pixels outside a radius > 64 px).
- Milestone: M3 = basic, M5 = the rest.
- Parameters: `name range (default)`.

## Color and tone (Adjust)
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Brightness | −100–100 (0) | ✓ | M2 |
| Contrast | −100–100 (0) | ✓ | M2 |
| Brightness & Contrast | both | ✓ | M2 |
| Exposure | −5–5 EV (0) | ✓ | M2 |
| Gamma | 0.2–5 (1) | ✓ | M2 |
| Saturation | −100–100 (0) | ✓ | M2 |
| Vibrance | −100–100 (0) | ✓ | M2 |
| Temperature | −100–100 (0) | ✓ | M2 |
| Tint | −100–100 (0) | ✓ | M2 |
| Hue | −180–180° (0) | ✓ | M2 |
| HSL | H ±180, S ±100, L ±100 | ✓ | M2 |
| Color Balance | shadows/mid/highlights × (C-R, M-G, Y-B) ±100, preserve luminosity | ✓ | M3 |
| Curves | RGB, R, G, B, A curves | ✓ | M2 |
| Levels | in black 0–253, gamma 0.1–9.99, in white 2–255, out black/white; channel RGB/R/G/B; Auto | ✓ | M3 |
| Gradient map | stops (color, position), reverse | ✓ | M3 |
| Replace color | up to 5 points: key color, range, fuzziness, H/S/L shift | ✓ | M3 |
| Posterize | levels 2–64 (8) | ✓ | M3 |
| Palette (posterize to an image) | colors 2–48 (8) | — | M5 |
| Grayscale | — | ✓ | M2 |
| Black & White | threshold 0–255 (128), smoothing | ✓ | M3 |
| Mono color | color | ✓ | M3 |
| Sepia | strength | ✓ | M3 |
| Warm | −100–100 | ✓ | M3 |
| Fade | 0–100 | ✓ | M3 |
| Invert | variant RGB / Chroma / Luminance / Alpha, strength −100–100 | ✓ | M2 |
| Change line color | color, strength (recolor the opaque part, keeping alpha) | ✓ | M3 |
| Extract line art | white 0–255 (220), black 0–255 (40), mid 0–100 (50) → line art on transparent | — | M3 |
| Clear white | gray / color | — | M2 |

## Blur and sharpness
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Gaussian blur | radius 0–250 px (5) | ✓ | M3 |
| Smooth (edge-preserving) | strength, radius | ✓ | M5 |
| Motion blur | angle (drag), distance 0–500 | ✓ | M3 |
| Zoom blur | center (drag), amount | — | M3 |
| Spin blur | center, angle −90–90 | — | M3 |
| Lens blur | radius, highlight boost, blade angle | — | M5 |
| Mosaic / Pixelate (square) | cell 2–200 | ✓ | M3 |
| Unsharp mask | amount 0–500%, radius 0.1–100, threshold 0–255 | ✓ | M3 |
| Sharpen | strength | ✓ | M3 |
| High-pass (detail) | strength, radius | ✓ | M5 |
| Frosted glass / Scatter | distance 1–50 | — | M5 |
| Noise | amount, mono/color, alpha | — | M3 |
| Grain | variant mono / rgb / alpha, size | — | M5 |
| Local contrast | strength, radius | ✓ | M5 |

## Light
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Glow / Bloom | threshold, radius, strength | ✓ | M3 |
| Gloom | threshold, radius | ✓ | M5 |
| Bokeh | strength, size | — | M5 |
| Lens flare | center, size, strength | — | M5 |
| God rays | center, length, strength, threshold | — | M5 |
| Cross filter (stars) | threshold, length, angle, rays 2–8 | — | M5 |

## Morphology and edges
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Dilate | radius 1–20 | — | M3 |
| Erode | radius 1–20 | — | M3 |
| Find edges | algorithm (sobel / canny), smoothness, black, white, mid | — | M3 |
| Find edges (sketch) | thickness, amount of lines, sensitivity | — | M5 |
| Edges (on transparent) | strength | — | M3 |
| Trace (edges on top) | strength | — | M5 |

## Layer style (raster)
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Stroke outer / inner / both | width 1–100, color, opacity | ✓ | M3 |
| Drop shadow | angle, distance, blur, color, opacity | ✓ | M3 |
| Glow outer / inner | radius, color, opacity | ✓ | M3 |
| Bevel inner / outer | depth, size, angle, highlight/shadow color | ✓ | M5 |
| Emboss | height 0–100, angle | ✓ | M5 |
| Relief | height, angle, soften | ✓ | M5 |
| Satin | color, angle, distance, size | ✓ | M5 |
| Wet edge | width, strength | ✓ | M5 |
| Waterdrop | height, gloss | — | M5 |
| Extrude | angle, length, color, shading | — | M5 |

## Generators (Draw)
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Linear / Radial / Angle gradation | points, stops (= Fill gradients) | ✓ | M3 |
| Radial lines | center (drag), count 10–500, length random, inner radius, thickness, color | — | M3 |
| Speed lines | angle, count, length, thickness, spacing jitter, color | — | M3 |
| Clouds | scale, detail, seed, 2 colors | — | M5 |
| QR code | text, size, margin, color | — | M5 |
| Halftone | variant gray / bw / cmyk / alpha, cell size, angle | ✓ | M3 |
| Manga (tone from an image) | brightness, edge, tone size | — | M5 |
| Manga background | contrast, edge darkness, tone size | — | M5 |
| Anime background | brightness, contrast, saturation, posterize | — | M5 |

## Artistic
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Stained glass (voronoi) | cell size, border width, border color | — | M5 |
| Hexagon pixelate | size | ✓ | M5 |
| Triangle pixelate | size | ✓ | M5 |
| Crystallize | size | — | M5 |
| Pointillize | size, background | — | M5 |
| Dots (hex / square) | size, spacing, color | ✓ | M5 |
| Stamp (1-bit) | threshold | ✓ | M5 |
| Chromatic aberration | distance, angle | ✓ | M3 |
| Glitch | amount, slice height, seed | — | M5 |
| Retro game | palette (NES-like / GB-like / 16), pixel size, dither | — | M5 |
| Chrome | strength, smoothness | — | M5 |
| Sheer | strength | — | M5 |
| Kaleidoscope | segments 1–12, center | — | M5 |
| Impressionist | strength, stroke size | — | M5 |
| Swirl painting | strength | — | M5 |
| Pop art | dot scale, saturation | — | M5 |
| Pencil sketch | strength, line length | — | M5 |
| Oil paint | radius, levels | — | M5 |

## Deformations
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Fisheye / pinch | −100–100, center | — | M3 |
| Sphere lens | radius, strength, center | — | M5 |
| Wave | amplitude, wavelength, angle | — | M5 |
| Ripple | amplitude, frequency, center | — | M5 |
| Twirl | angle −720–720, radius, center | — | M5 |
| Polar coordinates | rect→polar / polar→rect | — | M5 |
| Expansion (bulge rect) | strength | — | M5 |

## Frames and misc
| Filter | Parameters | live | Milestone |
|---|---|---|---|
| Blur frame (vignette) | width, blur | ✓ | M5 |
| Table frame | width, color, corner radius | — | M5 |
| Rain | density, angle, length, opacity, seed | — | M5 |
