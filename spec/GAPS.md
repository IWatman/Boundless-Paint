# Пробелы → ответы

Статус: **все пункты закрыты** (2026-10-26).
- По разделу B оригиналы не измеряем: решение принимаем сами (см. DECISIONS.md «Раздел B»). Поэтому у каждого пункта стоит наш ответ, а не замер.
- Где написано «в spec/X», ответ расписан там подробно.

## A. Архитектурные решения
| # | Вопрос | Ответ |
|---|---|---|
| A1 | Платформа и стек | Только Android. Kotlin + Jetpack Compose + OpenGL ES 3.2. minSdk 29. Другие платформы позже |
| A2 | Рендер-конвейер | Тайлы 256² RGBA16F premultiplied, sRGB-кодировка, тоггл gamma-correct (деф. off). spec/blend-and-color.md |
| A3 | Модель документа | Тайлы + command-undo со снапшотами тайлов, 100 шагов / 512 MB. Virtual transform. spec/document-model.md |
| A4 | Векторный слой | Список path-объектов (точки + давление + ссылка на параметры кисти), рендер stamp-движком. В M4 |
| A5 | Анимация | Кадр = свой стек слоёв + опциональный общий фон. В M4 |
| A6 | Многостраничность | Нет. Одна страница = проект |
| A7 | Свои форматы | ZIP-контейнеры + JSON. spec/file-formats.md |
| A8 | Совместимость | PNG/JPG/WebP (M1), PSD in/out (M3), ORA (M5). ABR нет |
| A9 | ИИ | После v1 |
| A10 | Онлайн | Локально + синк с Google Drive (M4). Галереи и комьюнити нет |
| A11 | Языки | RU + EN |
| A12 | Монетизация | Нет. Всё бесплатно |

## B. Числа и поведение (наши решения)
### Кисти
| Пункт | Ответ |
|---|---|
| Диапазоны и дефолты параметров | Таблицы в spec/brush-engine.md §2 |
| Кривые динамики по умолчанию | pressure→size: линейная, min 20%. pressure→flow: выкл. velocity, tilt: выкл. Пресеты кистей переопределяют |
| Tilt offset | Центр штампа смещается по азимуту на `tilt·offset·size/2` (offset −100..100) |
| Texture gradation | Глубина текстуры умножается на линейный спад вдоль оси наклона: `1 − g·t`, t ∈ [0,1] от кончика к хвосту |
| Adhesion | Буфер «загрузки» мазка L (0..1) накапливает покрытие штампов. Видимая альфа = `smoothstep(a, 1, L)`, a = adhesion |
| Structure (импасто) | Нормаль из градиента маски штампа (и накопленной высоты мазка) × structure. Ламберт со светом сверху-слева, модуляция яркости ±25% |
| Depth (щетина) | Маска как height-map: `α = sat((h − d·(1−p)) / (1 − d·(1−p)))`, d = depth, p = давление |
| Wet edges | `α' = α·(1 − w·(1 − e))`, где `e = sat((α − blur₄(α))·4 + 0.5)`. Края плотнее середины |
| Glaze strokes | Внутри мазка штампы объединяются по max (без накопления), сила = glaze |
| Mix-in / Dilution / Pull / Blur | Резервуар цвета R. На штамп: S = среднее холста под штампом (радиус выборки = blur). `R = lerp(R, S, 1 − pull)`. `paint = lerp(paint, S, mix_in)` (постоянно). Нанесённый цвет = `lerp(paint, R, dilution)`. wet = переносить альфу S |
| Watercolor | GPU-симуляция на wet-буфере (вода W, пигмент P), 30 Гц, пока W > 0. Диффузия P ∝ W·bleed, испарение W −= dryout. Потемнение края ∝ glaze. Подмешивание нижнего слоя ∝ mix_in |
| Harmony | Своя реализация: на каждой точке соединяем её линиями с прошлыми точками в радиусе r, с вероятностью p и альфой ∝ (1 − dist/r). Варианты задаются набором (r, p, смещение, длина). Без чужого кода |
| Particles | Аттракторы на пружине к точке пути со смещением radius. Частицы на пружине к аттракторам с затуханием, overshoot = коэффициент инерции |
| ibis Water / Double / Color | Water → mix_in + dilution + pull. Double → special.double. Color → head_color + base_color hue-shift: `hue += hue(cur) − hue(base)`, S и V ≤ исходных |
| Стабилизатор | Real-time: EMA, `α = 1 − 0.95·s/100`. Speed-based: `α /= 1 + k·speed`. After: сглаживание Гауссом по точкам, σ = after·0.5 px·N, затем перерисовка буфера. Lazy: rope radius |
| Fill brush + shapes | Контур шейпа = полигон заливки, заливка по even-odd |

### Слои и цвет
| Пункт | Ответ |
|---|---|
| Negative, Invert | spec/blend-and-color.md: negative = `1−|1−Cb−Cs|`, invert = `1−Cb` с силой αs |
| Trace mode | Нижние слои: множитель opacity `0.5^k` (k = 1 для соседнего), минимум 0.1. Верхние: 0.25. Paper без изменений |
| Лимит слоёв | `max = floor(budget / (w·h·8·0.5))`, budget = 40% от `ActivityManager.memoryClass`·MB для GPU+CPU кэша. Показываем в New Project |
| Adjustment layers | 11 типов: Brightness, Contrast, Exposure, Gamma, Saturation, Vibrance, Temperature, Tint, Hue, Curves, **Levels** + все фильтры с пометкой `live` в spec/filters.md |
| Screentone-слой | Свои паттерны: dot (круг), line, cross, square, diamond, noise. LPI 20–120 (деф. 60), угол 0–180 (деф. 45). Размер в физических единицах от dpi, поэтому при ресайзе пересчитывается. Набор пресетов ≈ 40 |

### Заливки и выделения
| Пункт | Ответ |
|---|---|
| Метрика tolerance | `max(|ΔR|,|ΔG|,|ΔB|,|ΔA|)` на unpremultiplied (как «chebyshev»). Порог = tolerance%. Режим boundary=opacity: только `|ΔA|` |
| Gap recognition | Закрытие разрывов до N px (настройка 0–20, деф. 4): морфологическое закрытие маски границ перед flood fill, потом расширение результата обратно до линий |
| Unpainted recognition | Пиксели с α линии < 0.5 считаются заливаемыми. Расширение под линию на expansion px |
| Color range | range = жёсткий порог расстояния, fuzziness = ширина мягкого спада после порога. До 5 точек, объединение по max |
| Expand / contract | Distance transform, 0–100 px (не 6) |

### Трансформации и деформации
| Пункт | Ответ |
|---|---|
| Warp / Mesh | Одна модель: сетка B-spline N×M контрольных точек (Warp = 4×4 точки, 3×3 ячейки; Mesh = до 16×16). Рендер: тесселяция в треугольники на GPU |
| Liquify | Falloff `(1 − r²)²`, strength 0–100 (деф. 50), давление умножает силу. Поле смещений на сетке 1/4 разрешения, билинейно |
| Virtual transform | Держится через: affine, mesh, flip, rotate 45, resize/crop холста (как offset). Коммитится при любой пиксельной правке слоя, фильтре, merge, liquify |

### Фильтры
- Полный список ~110 с нашими параметрами, диапазонами, дефолтами и пометкой `live`: **spec/filters.md**.

### Шейпы и гайды
| Пункт | Ответ |
|---|---|
| Распознавание | Удержание 0.6 с (настройка 0.1–5). Кандидаты: line, arc, polyline, smooth curve, triangle, rect, quad, ellipse, regular polygon. Первый = лучший по ошибке аппроксимации с весами приоритета. Остальные показываются иконками |
| Pen / Path | Кубические Bézier. Smooth-узел = автоматические касательные (Catmull-Rom, tension 0.5), cusp = угол. Ручки редактируются (smooth / aligned / free как в ibis) |

### Ввод
| Пункт | Ответ |
|---|---|
| Prediction | `androidx.input.motionprediction.MotionEventPredictor` (1 кадр), рисуется во временный буфер и заменяется реальными точками |
| Palm rejection | None / Stylus draws only (палец = навигация) / Hover (при hover-событиях стилуса пальцы игнорируются целиком) / Strict (только стилус везде). Определение стилуса: `TOOL_TYPE_STYLUS` |

### Таймлапс и анимация
| Пункт | Ответ |
|---|---|
| Таймлапс | Захват кадра на команду, ≤ 30/с. Экспорт H.264 MP4 (MediaCodec), 30 fps, 8 Мбит/с @1080p, 20 @4K. Длительности: 15 / 30 / 60 с / полная. Качество: 720p / 1080p / 4K |
| Лимиты анимации | Кадры ≤ 999, fps 1–60 (деф. 12), onion skin 0–5 в каждую сторону, play loop / pingpong / once |

## C. Ассеты
| Пункт | Ответ |
|---|---|
| Кисти | Свои пресеты 16 категорий (spec/assets.md). Головы и текстуры процедурно генерируются скриптом `tools/gen_assets.py` (.venv: numpy + pillow) |
| Бумаги, паттерны, тоны, палитры | Тот же генератор + несколько CC0. Палитры составляем сами |
| Шрифты | Google Fonts с лицензией OFL/Apache, файлы лицензий рядом. Список в spec/assets.md |
| Материалы | v1: свой минимальный набор (баллоны, ткани) векторно и процедурно |
| Иконки | Material Symbols (Apache 2.0) + свои |

## D. Документы
| # | Документ | Статус |
|---|---|---|
| 1 | spec/brush-engine.md | ✅ |
| 2 | spec/document-model.md | ✅ |
| 3 | spec/blend-and-color.md | ✅ |
| 4 | spec/tools/*.md | ✅ M0–M1 инструменты. Спеки следующих вех пишутся **до** начала соответствующей вехи, это часть её плана |
| 5 | spec/file-formats.md | ✅ |
| 6 | spec/ui-components.md | ✅ |
| 7 | spec/test-corpus.md | ✅ |
| 8 | spec/ROADMAP.md + spec/PLAN-M0-M1.md | ✅ |
| 9 | spec/filters.md | ✅ |
| 10 | spec/assets.md | ✅ |
