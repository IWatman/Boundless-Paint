# Assets v1

## Built-in brushes (our own presets, our own names)
> Updated: the M2 goal = a full stock catalog of ≈236 brushes (spec/brush-catalog.md). The table below = the first-wave core inside M2.

| Category | Presets (first wave) |
|---|---|
| Pencils | Graphite HB, Graphite 4B, Technical pencil, Hatching |
| Pens and ink | Even pen, Soft pen, Pressure ink, Liner, Pixel 1 px |
| Calligraphy | Flat nib, Round nib |
| Markers | Marker, Chisel marker, Highlighter, Blender marker |
| Paints | Flat oil, Round oil, Gouache, Palette knife, Dry brush |
| Watercolor | Wet wash, Light watercolor, Dense watercolor, Water (blender) |
| Airbrush | Soft, Medium, Hard, Splatter |
| Chalk and pastel | Chalk, Pastel, Charcoal |
| Design | Dashed, Dots, Arrow |
| Fill | Solid, Noise, Screentone |
| Glow | Neon, Soft light, Sparks |
| Screentone | Dots 45°, Lines, Halftone |
| Textures | Brick, Wood, Mesh |
| Procedural | Sketch, Fur, Cobweb, Ribbons |
| Erasers | Soft, Hard, Textured |
| Blending | Finger, Blur, Soft blend |

## Generated resources (`tools/gen_assets.py`)
- Heads: circle (AA), square, bristle (N noisy strokes), sponge (Worley noise), chalk (noise + threshold), splatter.
- Textures: paper (perlin fbm), canvas (weave), grain, chalk.
- Papers for Paper (≈ 12): smooth, watercolor, kraft, canvas, rice…
- Patterns (≈ 20): check, dots, stripes, brick, herringbone…
- Screentones: see GAPS "Screentone layer".

## Fonts (OFL / Apache, from Google Fonts)
Inter, Noto Sans (+ Cyrillic), Noto Sans JP (vertical text), Noto Serif, Caveat, Bangers, Comic Neue, Permanent Marker, Roboto Mono. Licenses in `app/src/main/assets/fonts/LICENSES/`.

## Palettes
Our own "Basic 24", "Skin tones 12", "Grays 10", "Pastel 16".
