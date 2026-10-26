# Движок кисти (единая модель)

Объединяет параметры IP (features/04/infinite-painter.md) и ibis (features/04/ibispaint.md). Дубли слиты. Диапазоны и дефолты наши.

## 1. Конвейер мазка
```
input events (x, y, pressure, tiltX, tiltY, azimuth, twist, time)
 → coalesced/predicted touches (Android MotionEvent history + predicted)
 → stabilizer (real-time: constant + speed-based; lazy-rope; after-stroke)
 → constraint (lazy / guide / ruler / perspective snap / hatching / shape)
 → resampler (дискретизация по spacing вдоль кривой Catmull-Rom)
 → dab generator (на каждый штамп: позиция, размер, угол, flow, opacity, цвет, scatter, текстура)
 → symmetry (копии штампов через аффинные преобразования)
 → dab renderer (GPU: head mask × texture × color → stroke buffer)
 → stroke compositor (stroke buffer → слой: blend mode, opacity cap, alpha lock, selection mask, clip)
```
- Мазок рисуется в отдельный **stroke buffer** (RGBA16F, размером с bbox). Opacity мазка = cap на буфере (IP Opacity / ibis Opacity Saturation). Композит в слой идёт на каждом кадре для превью, финально на pen-up.
- Режим «build-up» (ibis Opacity Saturation = off): штампы сразу в слой без cap.

## 2. Параметры
Обозначения:
- **[dyn]**: поддерживает динамику (кривые pressure / velocity / tilt / random / stroke-progress);
- `%` = 0..1 внутри.

### 2.1 Stroke
| id | Параметр | Диапазон | Деф. | Источник |
|---|---|---|---|---|
| size | Размер, px диаметр | 0.5–size_max | 20 | оба |
| size_min, size_max | Границы слайдера | 0.5–5000 | 1 / 500 | ibis |
| size_relative | Размер относительно холста | bool | false | ibis |
| opacity | Cap непрозрачности мазка [dyn] | 0–100% | 100 | оба |
| buildup | Накопление без cap | bool | false | ibis |
| smoothing | Стабилизатор real-time constant | 0–100 | 10 | оба |
| smoothing_speed | Стабилизатор, растущий со скоростью | 0–100 | 0 | ibis |
| after_smoothing | Сглаживание после pen-up | 0–100 | 0 | ibis |
| lazy_radius | «Верёвка», px экрана | 0–200 | 0 | IP |
| lazy_elastic | Радиус растёт со скоростью | bool | false | IP |
| taper_start, taper_end | Длина сужения, px | 0–500 | 0 | оба |
| taper_shape | smooth / linear | enum | smooth | ibis |
| taper_size, taper_opacity | Влияние сужения на размер и opacity | 0–100% | 100 / 0 | ibis |
| size_profile, flow_profile | Кривая по длине мазка (3 секции) | curve | off | IP |
| adhesion | Порог «материала» | 0–100 | 0 | IP |
| wet_edges | Края плотнее середины | 0–100 | 0 | IP |
| glaze | Без накопления внутри мазка | 0–100 | 0 | IP |
| blend_mode | Режим наложения мазка | 33 | normal | оба |
| blend_strength | Сила режима | 0–100% | 100 | IP |
| separate_strokes | Каждый мазок отдельно (иначе сливаются в одном слое-буфере сессии) | bool | true | ibis |

### 2.2 Head (штамп)
| id | Параметр | Диапазон | Деф. |
|---|---|---|---|
| head_source | round / bitmap / procedural | enum | round |
| head_color | Использовать RGBA битмапа | bool | false |
| head_invert | Инвертировать | bool | false |
| head_curve | Тоновая кривая маски | curve | linear |
| colorize | Сколько цвета из битмапа (при head_color) | 0–100 | 0 |
| base_color | База для hue-shift цветного штампа (ibis Color type) | color | — |
| hardness | 1 − softness. Край штампа | 0–100 | 80 |
| aspect | Сплющивание −100..100 (− выше, + шире) | −100–100 | 0 |
| spacing | Шаг, % от размера | 1–1000 | 10 |
| flow | Непрозрачность штампа [dyn] | 0–100 | 100 |
| angle | Базовый угол | 0–360 | 0 |
| rotation_mode | fixed / tangent(direction) / reverse / stylus-azimuth / stylus-twist (barrel) / random | enum | fixed |
| rotation_follow | Доля следования направлению (IP −100..100) | −100–100 | 0 |
| orient_screen | Угол относительно экрана | bool | false |
| antialias | AA штампа | bool | true |
| dots | Дискретные точки | bool | false |
| structure | Импасто-рельеф (нормали из маски, освещение) | 0–100 | 0 |
| depth | Альфа головы как height-map (щетина) | 0–100 | 0 |

### 2.3 Jitter (random)
- Size, Angle, Scatter (offset ⊥ направлению, % размера), Flow, Opacity, Spacing: 0–100, деф. 0.
- Count: штампов на шаг (1–16).
- Initial (раз на мазок) и continuous (на штамп): угол и HSB.
- **Color jitter HSB**: hue ±180°, sat, bri. Initial и continuous.
- **Scatter particles** (ibis):
  - particle_count 1–64;
  - particle_size;
  - deviation −100..100 (наружу/внутрь);
  - angle_from_center.

### 2.4 Texture
| id | Параметр | Диапазон | Деф. |
|---|---|---|---|
| tex_source | none / bitmap | — | none |
| tex_mode | canvas-fixed / rotate-with-stroke / warp-along-stroke / moving(stamp-relative) | enum | canvas-fixed |
| tex_depth | Сила [dyn] | 0–100 | 50 |
| tex_lower_limit | Порог закраски (ibis), 100 = без порога | 0–100 | 100 |
| tex_scale | 0.05–4 | — | 1 |
| tex_stretch | 1–50 | — | 1 |
| tex_angle | 0–360 | — | 0 |
| tex_softness | Блюр текстуры | 0–100 | 0 |
| tex_structure | Импасто текстуры | 0–100 | 0 |
| tex_invert, tex_color, tex_curve | как у head | — | — |
| tex_scale_with_size | bool | — | false |
| tex_gradation | Спад при наклоне | 0–100 | 0 |
| tex_jitter_pos / scale / angle | initial и continuous | bool | false |
| tex_aa | bool | — | true |

### 2.5 Paint (смешивание)
| id | Параметр | Диапазон | Деф. |
|---|---|---|---|
| mix_in | Подхват с заменой цвета [dyn] | 0–100 | 0 |
| dilution | Подхват без замены (100 = блендер) [dyn] | 0–100 | 0 |
| wet | Переносить альфу | bool | false |
| pull | Длина смаза (память цвета) | 0–100 | 50 |
| blur | Усреднение подхвата (радиус выборки) | 0–100 | 0 |
| blend_flow | Множитель flow в Blend-режиме | 0–100 | 100 |
| sample_lower | Сэмплить видимые слои ниже | bool (глобально) | false |
| water_color_mix / wetness / dragging | ibis Water type, маппятся на mix_in/dilution/pull | — | — |

### 2.6 Special
- **Watercolor**:
  - bleed 0–100;
  - glaze 0–100;
  - mix_in 0–100;
  - dryout 0–100.
  - Модель: wet-слой (вода + пигмент), диффузия на GPU (пинг-понг), высыхание по времени.
- **Screentone snap**: grid_spacing px, angle 0–180, scale_with_size.
- **Particles** (attractors 1–64, particles 1–400, radius, p_size 0–0.1, overshoot 0–2).
- **Double** (контур/тень): body_color/shadow_color, shadow_size (px или %), angle, distance, texture_on_shadow.
- **Harmony** (процедурные): sketchy, shaded, chrome, fur, longfur, web, squares, circles, ribbon, grid. Собственная реализация по описанию «линии к ближайшим прошлым точкам».
- **Fill brush**: мазок = контур, заливка внутри (solid / pattern / procedural).
- **Filter brush**: target = layer-under-stroke или stroke. Фильтр из списка + strength −100..100.
- **3D-кисти** (tube, ribbon, twisty): штамп с шейдингом по нормали вдоль траектории. P3.

### 2.7 Dynamics (кривые)
- Источники:
  - pressure;
  - velocity;
  - tilt (altitude);
  - azimuth;
  - twist/barrel;
  - random;
  - stroke progress;
  - (finger: pressure = 1).
- Цели: size, flow, opacity, scatter, angle, hardness, tex_depth, mix_in, dilution, color jitter, blur (ibis «blurring»).
- Каждая связь = кривая (Bézier/монотонный кубический сплайн, узлы 2–8) + min.
- Глобальная калибровка pressure / velocity / tilt (Settings) применяется до кривых кисти.
- tilt_offset: смещение роста размера относительно кончика.

## 3. Режимы инструмента
- **Paint**.
- **Blend** (только smudge-часть, цвет не кладётся).
- **Erase** (destination-out, flow/opacity работают).
- **Clone** (источник: слой + offset/aligned, или artistic: цвет из источника).
- Отдельные preset-инструменты: Smudge (= Blend), Blur (filter brush gaussian), Liquify (деформационная кисть).

## 4. Стабилизация (порядок)
1. Predicted + coalesced точки.
2. Lazy rope (если radius > 0).
3. Real-time: EMA с α = f(smoothing, speed).
4. Pen-up: after_smoothing. Повторная подгонка всего мазка (сглаживание сплайном) и перерисовка буфера.
5. Straight-line mode: отрезок от первой до последней точки.

## 5. Производительность (цели)
- Задержка pen → пиксель ≤ 1 кадр (16 мс) при size ≤ 300 px на среднем планшете 2022 года.
- Штамп рисуется инстансированным квадом. Head и texture — атласы. Stroke buffer рисуется только по dirty-тайлам.

## 6. Формат кисти (.pbrush)
`brush.json`: {schema, id (UUID), parent_id, name, category, params{...}, dynamics[{source, target, curve:[[x,y]...], min}], resources{head: "head.png", texture: "tex.png", particle: ...}}.
