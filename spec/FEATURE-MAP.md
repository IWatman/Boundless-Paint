# Объединённая карта функций (IP ∪ ibis) → место в IP-UI

Источник: `IP` = только Infinite Painter, `ibis` = только ibisPaint, `оба` = есть в обеих (берём лучшую версию).
Приоритет:
- **P0**: ядро MVP;
- **P1**: паритет с IP;
- **P2**: добавки из ibis;
- **P3**: сервисы и опции.
Детали в `features/<NN>/`.

## 01 Проекты и галерея
| Функция | Ист. | Где в UI | P |
|---|---|---|---|
| Проект: холст + слои + метаданные, автосейв, crash-backup | оба | — | P0 |
| История версий (1–10/∞, Restore = новый проект) | IP | Projects → long-press | P1 |
| Снапшот из таймлапса | ibis | Playback → ⋮ | P2 |
| Галерея: папки (вложенные), drag, rename, duplicate, delete | оба | Projects | P0 |
| Мультивыбор, корзина, время рисования | ibis | Projects | P2 |
| Шаблоны | IP | Projects | P2 |
| New Project: пресеты, единицы, dpi, ориентация, бумага, лимит слоёв | оба | New Project creator | P0 |
| Типы: Regular, Pattern (тайл ½), Challenge, **Animation**, **Manuscript** | IP+ibis | New Project creator | P1/P2 |
| From image: gallery, camera, files, clipboard (stock search опционально) | оба | Home | P1 |

## 02 UI и жесты
Каркас: spec/UI-SHELL.md. Добавки из ibis:
- cancel-stroke жест;
- курсор-паттерн и crosshair;
- тост и звук undo;
- floating layer window.
- **P1/P2**.

## 03–04 Кисти
| Функция | Ист. | Где | P |
|---|---|---|---|
| Stamp-движок: head bitmap, spacing, angle, rotation (fixed/tangent), softness, flow, opacity | оба | Brush Editor | P0 |
| Dynamics: pressure, velocity, tilt (кривые) → size, flow, scatter, depth, dilution, opacity, blur | IP кривые + ibis blur | Editor → Stroke/Head | P0 |
| Глобальная калибровка pressure/velocity/tilt | оба | Settings → Brush | P0 |
| Профили по длине (size/flow) + force fade (start/end, smooth/linear) | оба | Editor → Stroke | P1 |
| Jitter: size, angle, scatter, flow, spacing, opacity, HSB color | оба | Editor | P1 |
| Текстура: Fixed/Rotate/Warp, depth, scale, stretch, structure, softness, angle, lower limit, moving | оба | Editor → Texture | P1 |
| Smudge: mix-in, dilution, pull, blur, wet, sample lower layers | IP (+ibis Water type) | Editor → Paint, Blend mode | P0 |
| Режимы: Paint / Blend / Erase / Clone + отдельные Smudge и Blur кисти | оба | Main toolbar | P0 |
| Blend mode кисти + strength | оба | Editor → Stroke | P1 |
| Wet edges, glaze strokes, adhesion, impasto (structure, depth) | IP | Editor | P1 |
| Watercolor-эффект (bleed, glaze, mix-in, dryout) | IP | Editor → Special | P2 |
| Screentone snap | IP | Special | P2 |
| Particles (attractors) | IP | Special | P2 |
| Scatter-частицы (density, deviation, size) | ibis | Editor → Jitter | P2 |
| **Double brush** (контур/тень) | ibis | Editor → Type | P2 |
| **Color stamp brush** (base color, hue shift) | ibis | Editor → Type | P2 |
| Harmony (процедурные линии) | IP | Brush library | P3 |
| Fill brushes | IP | Brush library | P1 |
| Brush-as-filter (фильтр в мазке или под мазком) | IP | Special | P2 |
| Opacity saturation / separate strokes / add opacity / MSAA / dots | ibis | Editor → Shape | P2 |
| Ruler-aware: bind to ellipse center, billboard in array | ibis | Editor → Shape | P3 |
| Barrel roll / stylus rotation, orient to screen | оба | Editor → Head | P2 |
| Библиотека: папки, favorites per mode, recent, created, downloads, hide, restore, search | оба | Brush selector | P0 |
| Brush Creator (из битмапа, из выделения), Brush Pattern editor (холст) | оба | Brush selector + | P1 |
| Импорт/экспорт кисти и пака (свой формат) + QR (опц.) | оба | Brush options | P1/P3 |
| Стабилизация: smoothness, lazy (radius, elastic), real-time constant + fast, after-stroke | оба | Editor/Guides/Stabilizer | P0 |
| Своя библиотека встроенных кистей по 16+ категориям | — | — | P1 |

## 05 Цвет
| Функция | Ист. | P |
|---|---|---|
| 16 bpc внутренний конвейер, gamma-correct blending (тоггл) | IP | P0 (архитектура) |
| HSB wheel (6 стилей), sliders HSB/RGB/LAB/CMYK, hex | IP | P0/P1 |
| Opacity в цвете | ibis | P2 |
| Tone lock, compare tone, complementary marker | IP | P2 |
| Палитры: коллекция, библиотека, smart palette из изображения/камеры | оба | P1 |
| История цветов (до 100, слияние похожих) | оба | P1 |
| Пипетка: радиус 1–100, all layers / current, quick (long-press, лупа), refs, contents-layer jump | оба | P0/P2 |
| Lighting orb | IP | P3 |
| Proofing: sRGB, CMYK, grayscale, line art, color-blind | IP | P2 |
| Режим документа Color / Gray / BW | ibis | P2 |

## 06 Слои
| Функция | Ист. | P |
|---|---|---|
| Растровые слои, opacity, blend, hide, rename, duplicate, clear, merge, delete | оба | P0 |
| Alpha lock, clipping (мульти, группы) | оба | P0 |
| Группы (вложенные, passthrough/normal, flatten, copy merged) | оба | P0 |
| Маски слоя (8-bit, linked, apply, invert, из выделения) | IP | P1 |
| Adjustment layers (все коррекции + фильтры) | оба | P1 |
| Filter layers (live) | IP | P1 |
| Text, Panels layers | IP | P1 |
| **Vector layer** (кисть-пути, правка вершин и ручек, simplify, vector eraser, convert, loops) | ibis | P2 |
| **Screentone-свойство слоя** | ibis | P2 |
| Paper layer (цвет, текстура: depth, opacity, scale) + canvas papers | оба | P1 |
| Visibility modes: Normal / Solo / Trace (прогрессивный) | IP | P1 |
| Clear white (gray/color), invert layer, flip layer/canvas H/V, add from canvas/folder, export layer PNG | ibis | P1/P2 |
| Pinch-to-group, hide when recording | IP | P2 |
| Thumbnails: whole / contents | ibis | P3 |

## 07 Blend modes
- 33 режима (список в features/07/ibispaint.md, последний раздел). P0: 12 базовых. P1: остальные.

## 08 Текст
| Функция | Ист. | P |
|---|---|---|
| Text layer, рамка с переносом, snap-направляющие | IP | P1 |
| Шрифты TTF/OTF, variable axes, missing-font handling | IP | P1 |
| Outline / outline-only, align, case, spacing, justify + scale-justify + balance | IP | P1/P2 |
| **Вертикальный текст** | ibis | P2 |
| **Фоновая плашка + margin** | ibis | P2 |
| RTL, complex scripts (через HarfBuzz) | IP | P1 |

## 09 Заливки
| Функция | Ист. | P |
|---|---|---|
| Bucket: tolerance, live tolerance, multi/single/source layer | IP | P0 |
| Expansion ±px, gap recognition, unpainted recognition, boundary color/opacity, under line | ibis | P1 |
| Continuous filling, eraser bucket, surrounding fill/eraser (scrape) | ibis | P2 |
| Lasso fill / lasso eraser (+all layers) | ibis | P1 |
| Gradients: linear, radial, sweep, стопы с opacity, repeat, reflect, reverse | IP | P1 |
| Pattern fill (тайл, HSB/contrast/opacity) | IP | P2 |
| Fill brushes | IP | P1 |

## 10 Guides и линейки
| Функция | Ист. | P |
|---|---|---|
| Lines (параллель/перпендикуляр/центр), Ellipse (дуги, радиусы), Pen (Bézier) | IP | P1 |
| Hatching guide | IP | P2 |
| Lazy guide | IP | P1 |
| **Radial guide** (лучи, мульти-центр) | ibis | P2 |
| **Array guide** (живое тиражирование ячейки, перспектива) | ibis | P2 |
| **Perspective array** | ibis | P3 |
| Straight-line mode стабилизатора | ibis | P2 |

## 11 Шейпы
| Функция | Ист. | P |
|---|---|---|
| Line, Rect, Circle/Ellipse, Path, Arc, stamp | IP | P1 |
| Rounded rect, regular polygon (≤16), arrow, speech balloon | ibis | P2 |
| Fill/outline/brush-line опции у шейпа | ibis | P2 |
| Shape detection (hold), кандидаты, приоритеты, время | оба | P1 |
| Шейпы в перспективе (3 плоскости) | IP | P2 |

## 12 Симметрия
- V/H (любой угол, crop), Radial 2–16 + fan, Kaleido 2–16 + crop: IP, **P1**.
- Array-симметрия: ibis, **P2**.

## 13 Перспектива
- 1/2/3pt, curvilinear, isometric, 2D со snap и видимостью: IP, **P1**.
- Grid overlay 2-уровневый: ibis, **P2**.

## 14 Вид
- Zoom, rotate, pan, fit, flip (+vertical), lock view, navigator (gray, flip), refs widgets, immersive: **P0/P1**.
- Pixelated zoom: ibis, **P2**.

## 15 Выделения
| Функция | Ист. | P |
|---|---|---|
| 8-bit маска, workspace, set/add/subtract, boolean с слоями (intersect, diff) | оба | P0/P1 |
| Lasso, Rect, Ellipse, Polyline, Path, Wand, Brush, Color range (multi-point, fuzziness) | оба | P0/P1 |
| Expand/contract, feather, invert, select all, transform mask | оба | P0 |
| Duplicate, isolate, copy merged, delete, cut/copy/paste (системный буфер), library clipboard, make brush | оба | P0/P1 |
| Marching ants / overlay | IP | P1 |
| Сохранение выделения между сессиями | ibis | P2 |

## 16 Трансформации
| Функция | Ист. | P |
|---|---|---|
| Basic, anchor, distort (perspective), warp | IP | P0/P1 |
| Mesh N×M + smoothness | ibis | P2 |
| Мультислой, фрагмент, маска | IP | P0 |
| Flip H/V, rotate 45°, stamp, unlock cage | оба | P1 |
| Числовой ввод, 6 интерполяций | ibis | P1 |
| Perspective repeat (inner/horizon/full) | ibis | P3 |
| **Virtual transformations** (без потерь до правки) | IP | P1 (архитектура) |

## 17 Коррекции
- Brightness, Contrast, Exposure, Gamma, Saturation, Vibrance, Temperature, Tint, Hue: IP, **P1**.
- Curves RGBA: IP, **P1**.
- Levels, Color balance, HSL, Replace color: ibis, **P2**.
- Quick masks (focus, tilt-shift, tonal, saturation): IP, **P2**.

## 18 Фильтры
- ~110 объединённых (список в features/18/*).
- **P1**: blur/sharpen/noise/pixelate/edges/halftone/gradient map/motion.
- **P2**: style-эффекты, draw-генераторы, artistic.
- **P3**: smart (ИИ).

## 19 Liquify
- Push, pinch, bloat, swirl, restore, rewind, strength, smoothen + symmetry: **P1**.

## 20 Clone
- Photo / artistic / smart, offset, align, source layer: IP, **P1/P3**.
- Copy zoom, angle jitter: ibis, **P2**.

## 21 Паттерны, материалы, тоны
- Pattern tools (symmetry/path/quilt/tile): IP, **P2**.
- Material library (локальная): ibis, **P2**.
- Screentone-слой: ibis, **P2**.

## 22 Холст
- Crop (неразрушающий), resize (контент, канва, якорь), straighten: IP, **P1**.
- Resize в mm+dpi с интерполяцией, color mode: ibis, **P1/P2**.
- Panels + frame divider (поля, гаттеры, fill панели): оба, **P2**.

## 23 Импорт/экспорт
- PNG/JPG/WebP: **P0**.
- PSD (слои, маски, adjustment, flattened) in/out: **P1**.
- ZIP слоёв: **P2**.
- Свой проектный формат: **P0**.
- CMYK PSD + ICC/intent/BPC: **P2**.
- Gray 8 / mono 1-bit: **P2**.
- Ассеты (кисть, пак, палитра, настройки): **P1**.
- Super-resolution, AI-disturbance: **P3**.

## 24 Таймлапс и анимация
- Таймлапс (regular / follow navigation, hide layer, export длительностей, статистика): IP, **P1**.
- Frame-анимация (fps, duration, onion skin, loop/pingpong/once): ibis, **P2**.
- Video mode (треки, аудио, 60 fps, MOV/MP4): ibis, **P3**.

## 25 Ввод и настройки
- Pressure, tilt, velocity, palm rejection 4 режима, touch offset, stylus buttons/squeeze/barrel, low latency/prediction, coalesced touches, шорткаты (+custom), volume keys: **P0–P2**.

## 26 Манга
- Manuscript-тип (bleed/trim/safe, пресеты), color mode, вертикальный текст, баллоны, speed/radial lines, manga background: ibis, **P2**.

## 27 Онлайн, ИИ
- Облако, галерея: **вне ядра**, P3, отдельное решение.
- Challenges: IP, P3.
