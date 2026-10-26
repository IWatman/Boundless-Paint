# ibisPaint: линейки (Ruler) и стабилизатор-инструменты

Источник: ibispaint.com/lecture №43–48, 50, 51, 89, 90.

## Ruler tool (8 типов)
| Линейка | Контролы | Назначение |
|---|---|---|
| **Straight** | angle handle, move handle | прямые, спидлайны |
| **Circular** | position, size | точные окружности |
| **Elliptical** | width, position, height, angle (4 ручки) | эллипсы, баллоны |
| **Radial** | центр (несколько, «Add Radial Ruler», выбор стрелкой) | лучи из точки, точки схода |
| **Mirror** | Phase (угол), position | зеркальная симметрия (= IP Symmetry) |
| **Kaleidoscope** | **Division** (кол-во секторов), **Phase** (угол) | кружево (≈ IP Kaleidoscopic/Radial) |
| **Array** | Division X/Y (до 10×10), рамка в перспективе (drag углов), **Lock** → оранжевая work-cell | рисуешь в одной ячейке → копируется во все с перспективой |
| **Perspective Array** | Division X/Y/Z (старт 3), **Phase**, Face Selection (Right/Front…), Vertex/Face thumbs (размер, позиция, ориентация куба), Lock | 1/2/3-точечная перспектива из кубической решётки, рисунок в work-cell тиражируется (окна, заборы) |
- Линейки комбинируются: Perspective Array + Straight + Radial (центры в VP).
- Опции кисти «Bind Center on Elliptical Ruler», «Billboard Display on Array Ruler» (см. 04).

## Стабилизатор-группа (Drawing tools в окне Stabilizer)
- Stabilizer: Real Time / After, Fade (см. 04).
- **Straight Line**: рисование прямой без линейки.
- Остальные Drawing tools (Rectangle, Circle, Ellipse, Regular Polygon, Bézier, Polyline) см. 11.

## Сопоставление с IP
| ibis | IP-аналог | Что делать |
|---|---|---|
| Straight Ruler | Lines guide | IP богаче (параллель + перпендикуляр + центр). Добавить режим «только вдоль оси» |
| Circular/Elliptical | Ellipse guide | IP богаче (дуги, радиусы, концентрика) |
| Radial Ruler | нет (частично Perspective 1pt) | **добавить** Radial guide (лучи из точки, несколько центров) |
| Mirror | Symmetry V/H | есть |
| Kaleidoscope | Kaleidoscopic symmetry | есть |
| Array Ruler | нет (Quilt pattern пост-фактум) | **добавить** Array guide (живое тиражирование в сетке + перспектива) |
| Perspective Array | нет (Grids + Stamp) | **добавить** |
| Stabilizer Real Time/After | Smoothness + Lazy guide | **добавить** After-режим и Fast Strokes |
| Force Fade | Size/Flow profile | есть в IP богаче, добавить быстрый тоггл |
