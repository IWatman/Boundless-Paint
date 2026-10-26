# ibisPaint: фильтры (84+)

Источник: ibispaint.com/lecture №53–76, 86–88, 92, 94, 97–102, 111–117, 120–131, 134, 136, 142–150, 160–161, 164–165, 176–177, 179, 181, 194. Страницы обычно показывают 1–3 слайдера без диапазонов. Параметры там, где они названы.
Общее: Tool Select → Filter → вкладки категорий. Слайдеры + ручки на холсте. ✓. Действует на слой или выделение. Часть доступна как Adjustment layer. Settings: Save Filter Parameters.

## Adjust Color
| Фильтр | Параметры |
|---|---|
| Brightness & Contrast | brightness, contrast |
| **Tone Curve** | RGB/R/G/B вкладки, узлы |
| Hue Saturation Lightness | H, S, L |
| Color Balance | балансы |
| **Extract Line Drawing** | White, Black, Middle (50%): скан → лайнарт |
| **Find Edges (Handwriting)** | Thickness, Amount of Lines, Sensitivity… |
| Find Edges | Algorithm, Smoothness, Black, White, Intermediate |
| **Change Drawing Color** | цвет, слайдеры (перекрасить лайнарт) |
| Mono Color | — |
| Grayscale | — |
| Black & White | порог |
| Posterize | уровни |
| Invert Color | — |
| **Gradation Map** | стопы, тёмные слева, светлые справа |
| **Levels Adjustment** | гистограмма, Auto, Shadows/Midtones/Highlights, канал R/G/B |
| **Replace Color** | до 5 reference points, у каждой HSL-замена, Fuzziness, Range |

## Blur
- Gaussian Blur (Radius).
- **Zooming Blur**.
- **Moving Blur**.
- **Spin Blur**.
- **Lens Blur** (Radius, Brightness, Direction).
- **Mosaic**.
- **Unsharp Mask**.
- **Frosted Glass**.

## Style (layer-style-эффекты, растрово)
- Stroke (Both / Outer).
- Stained Glass.
- Wet Edge.
- Glow (Inner / Outer).
- Bevel (Inner / Outer).
- Emboss.
- Relief (+ Relief HQ, smart).
- Waterdrop (Rounded).
- Satin.
- **Drop Shadow** (слайдеры + цвет).
- **Extrude**.
- **God Rays**.

## Draw (генераторы)
- **Parallel Gradation**, **Concentric Gradation**, **Radial Line Gradation**: градиенты-заливки.
- **Radial Line** (фокус + форма), **Speed Line** (позиция, длина, толщина): манга-эффекты.
- **Clouds**.
- **QR Code** (генерация QR из текста).

## Smart (ИИ)
- **Watercolor** (Saturation, 1.5 pt).
- **Background Removal**: тап по объекту (синий) / минус = фон (красный), Show Mask.
- Relief HQ (1 pt).

## Artistic
- **Anime Background** (Brightness, Contrast, Saturation…).
- **Manga Background** (Contrast, Edge Darkness, Screen Tone Size…).
- **Chromatic Aberration** (RGB shift).
- **Glitch**.
- **Noise**.
- **Retro Game**.
- Chrome.
- **Bloom**.
- **Cross Filter** (звёздочки).
- Sheer.

## Pixelate
- Crystalize.
- Hexagonal.
- Square.
- Triangular.
- Pointillize.
- Dots (Hexagonal / Square).

## Transform (деформации)
- Expansion.
- Fisheye Lens.
- Sphere Lens.
- Wave.
- Ripple.
- Twirl.
- Polar Coordinates.

## Frame
- **Table** (рамка-подложка).
- **Blur Frame** (виньетка-размытие по краю).

## Movie
- **Rain** (анимированный в видео-режиме?).

## Сопоставление с IP (41 фильтр)
- Совпадают по сути: Blur, Motion/Zoom/Spin, Pixelate/Hexagons, Stained Glass, Emboss, Fisheye, Bloom, Halftone≈Dots, Posterize≈Palette, Gradient map, Invert, B&W, Noise≈Grain, Chromatic≈Stereo, Edges≈Find Edges, Manga.
- Только у ibis:
  - Tone Curve-фильтр (у IP Curves как отдельный инструмент);
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
  - все Style-эффекты (Stroke, Glow, Bevel, Satin, Drop Shadow, Extrude, God Rays, Wet Edge, Waterdrop, Relief);
  - Draw-генераторы (градиенты, Radial/Speed Line, Clouds, QR);
  - Smart (Watercolor, Background Removal);
  - Anime/Manga Background;
  - Glitch;
  - Retro Game;
  - Chrome;
  - Cross;
  - Sheer;
  - пикселизации (Crystalize, Triangular, Pointillize);
  - Sphere, Wave, Ripple, Twirl, Polar;
  - Table, Blur Frame;
  - Rain.
- Только у IP: Fade, Glow, Sepia, Bokeh, Local Contrast, Warm, Lens flare, Smooth, Gloom, Dilate, Erode, High-pass, Stamp, Trace, Kaleidoscope, Monet, Van Gogh, Pop-art, Pencil, Paintbrush, Invert-варианты, Grain-варианты, Halftone-варианты.
- Итог объединения: около **110 уникальных фильтров**. Приоритезация в `/spec/`.
