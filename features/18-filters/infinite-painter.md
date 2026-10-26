# Infinite Painter: фильтры

Источник: User Guide /painter/filters/*. 41 фильтр. 27 из них доступны как неразрушающие Filter layers (точный список в доке не дан).

## Общий toolbar
- Cancel.
- **Variant selector** (у фильтров с вариантами, циклический).
- Another filter (спросит: применить или бросить текущий).
- Masking menu (quick masks, см. 17).
- Invert mask.
- Confirm.
- Hide effect.
- Strength (значение + слайдер, обычно 0…100%, у некоторых −100…+100).
- Специфичные контролы на холсте: центр, направление.
- Селектор фильтров: долгое нажатие Compare = до/после.
- Применяется к слою или выделению, а также к маске слоя или выделения.

## Photographic (11)
| Фильтр | Strength / поведение |
|---|---|
| Black & White | −100…+100 (светлый ↔ тёмный ч/б) |
| Fade | 0–100%, перманентно снижает альфу |
| Glow | 0–100, ширина свечения светов |
| Invert ×4 | RGB / Chroma / Luminance / Alpha, −100 (оригинал) … +100 (инверсия). Alpha 100% → прозрачное становится серым #808080 |
| Sepia | 0–100 |
| Grain ×3 | Mono / RGB / Alpha noise |
| Bokeh | 0–100, зависит от разрешения |
| Local Contrast | 0–100 |
| Warm | −100 (холод) … +100 (тепло) |
| Lens flare | 0–100, drag = центр |
| Gradient map | редактор градиента как в Linear fill, без прозрачности, стопы без лимита |

## Structure (11)
| Фильтр | Поведение |
|---|---|
| Blur | настоящий Gaussian (у filter-слоя Blur другой, не гауссов, алгоритм) |
| Smooth | шумоподавление с сохранением краёв |
| Bloom / Gloom | усилить / затемнить светлые области |
| Dilate / Erode | морфология; на лайнарте утолщает / утоньшает линии |
| Scatter | frosted glass |
| Sharpen | — |
| High-pass | high-pass копии + Hard Light, по сути unsharp mask |
| Emboss | relief height, ч/б, направление света фиксировано |
| Fisheye | −100 (pinch) … +100 (fisheye), drag = центр |

## Artistic (16)
| Фильтр | Поведение |
|---|---|
| Halftone ×4 | Grayscale / B&W / CMYK / Alpha |
| Manga | ч/б: края → линии, плоские заливки → скринтон; strength = яркость |
| Hexagons | размер ≈ px, среднее по ячейке |
| Pixelate | размер блока |
| Stained Glass | Voronoi |
| Stamp | 1-bit, порог |
| Edges | чёрные края на прозрачном |
| Trace | чёрные края поверх оригинала |
| Stereo | смещение RGB-каналов (хроматическая аберрация), фон прозрачный |
| Kaleidoscope | 1–12 зеркал, drag = центр |
| Palette | posterize 2–48 цветов (палитра из изображения) |
| Monet / Van Gogh | импрессионизм / завихрения |
| Pop-art | halftone + усиление цвета |
| Pencil | штриховка, длина штрихов |
| Paintbrush | масляная живопись |

## Motion (3)
- **Direction**: motion blur, drag = направление.
- **Zoom**: radial zoom blur, drag = центр.
- **Spin**: −/+ = против / по часовой.

## Фильтры в кистях
- См. 04: кисть применяет фильтр или коррекцию к слою под мазком или к самому мазку.
