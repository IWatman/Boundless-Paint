# Спеки инструментов M2: гайды, шейпы, симметрия, перспектива

Источники: features/10–14, spec/UI-SHELL.md, spec/FEATURE-MAP.md §10–13, spec/GAPS.md «Шейпы и гайды». Имена и визуал свои (00-clean-room/PROCESS.md).
Единицы: координаты и размеры геометрии в px документа. Пороги жестов в **экранных px** (dp × density), в документ переводятся делением на `ViewTransform.zoom`.

## G0. Состав M2 и резервы
| Группа | M2 | Позже (резерв id в схеме) |
|---|---|---|
| Гайды | Lines, Ellipse, Lazy, Pen, Hatching, Grid overlay (только показ) | `radial`, `array`, `perspectiveArray` (M4) |
| Шейпы | Line, Rect, Ellipse/Circle, Regular polygon 3–16, Arc, Path (кривая и полилиния) + Stamp, Fill/Outline, распознавание | `roundedRect`, `arrow`, `balloon`, `star` (M4), шейпы в плоскостях перспективы (M4) |
| Симметрия | Mirror V / H / VH, Radial 2–32 (+fan), Kaleido 2–16, crop | `array` (M4) |
| Перспектива | 1pt, 2pt, 3pt, Isometric, 2D grid | `curvilinear` (5pt, fisheye) → M3 |
- **Решение:** regular polygon переезжает из M4 в M2: он нужен как результат распознавания. Star остаётся в M4.
- **Решение:** curvilinear в M3. Для него нужна проекция на дуги и своя сетка, а в M2 этому нет второго потребителя.

## G1. Архитектура constraint-конвейера
### 1.1 Порядок стадий
```
InputPoint (GestureRouter)
 → Stabilizer (EMA, есть)              ┐
 → LazyRopeStage (если Lazy активен)   │ PointStage-цепочка
 → SnapStage (Lines/Ellipse/Pen/Persp) │ (point / lift)
 → HatchingStage (если активен)        ┘
 → StrokeResampler (CATMULL_ROM | LINEAR, closed)
 → dab generator (M2 brush engine: jitter, scatter, angle)
 → SymmetryFanOut (N аффинных копий каждого штампа)
 → DabRenderer → stroke buffer → compositor (opacity cap, alpha lock, selection mask, clip)
```
- **Решение:** симметрия работает как размножение **штампов**, а не точек. Все копии получают одинаковые spacing и рандом (scatter, jitter): это и ожидается от зеркала. Один resampler на все копии, буфер один, TilePatch один. В spec/brush-engine.md §1 симметрию нужно перенести из стадии constraint после dab generator.
- Состояние, которое зависит от холста (резервуар smudge/mix у Blend-кистей), хранится **на копию**: массив размера N в dab generator.
- Lazy стоит до snap: верёвка сглаживает руку, snap выпрямляет результат. EMA и Lazy складываются (решение: так и в IP Smoothness отдельно от Lazy).
- Шейпы пропускают Stabilizer, Lazy, Snap и Hatching. Через FanOut они идут, поэтому симметрия к ним применяется.
- Constraints работают во всех режимах кисти (Paint, Erase, Blend) и для кисти выделения (selection brush, M2).

### 1.2 API (core, чистый JVM)
```kotlin
package app.painter.core.geom
data class Vec2(val x: Float, val y: Float)
/** x' = a·x + c·y + tx; y' = b·x + d·y + ty. det < 0 = зеркальная. */
data class Affine2(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    fun map(x: Float, y: Float, out: FloatArray); fun then(o: Affine2): Affine2; fun inverse(): Affine2
    val det: Float; val rotationRad: Float
    companion object { val ID: Affine2; fun rotation(c: Vec2, rad: Float): Affine2; fun reflection(c: Vec2, axisRad: Float): Affine2 }
}

package app.painter.core.stroke
/** Выход стадии. lift() = поднять перо: текущий под-мазок кончается, следующий point() начинает новый. */
interface PointSink { fun point(p: InputPoint); fun lift() }
interface PointStage {
    fun begin(p: InputPoint, out: PointSink)
    fun add(p: InputPoint, out: PointSink)
    fun end(out: PointSink)
    fun cancel() {}
}
enum class Interpolation { CATMULL_ROM, LINEAR }
/** Неизменяемые настройки одного мазка. Дефолты = поведение M1. */
data class StrokeOptions(
    val stabilize: Boolean = true,
    val stages: List<PointStage> = emptyList(),   // после Stabilizer
    val interpolation: Interpolation = Interpolation.CATMULL_ROM,
    val closed: Boolean = false,                  // замкнутый шейп: без taper, без двойного штампа на стыке
    val copies: SymmetryCopies = SymmetryCopies.NONE,
    val fill: FillSpec? = null,                   // заливка шейпа (G3.4)
)
class StrokeEngine(doc, layerId, params, color, mode, options: StrokeOptions = StrokeOptions())

package app.painter.core.guide
/** Трек, к которому прилипает мазок. s = параметр длины дуги (для Pen и Hatching). */
interface SnapTrack {
    fun tangentAt(p0: Vec2): Vec2                       // единичный
    fun project(x: Float, y: Float, hintS: Float, out: Projection)  // out.x, out.y, out.s
}
/** Источник семейств треков (гайд или сетка). */
interface SnapGuide {
    /** Кандидаты для мазка, начатого в p0. Пустой список = гайд не участвует (magnet вне радиуса). */
    fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>)
}
data class SnapContext(val screenToDoc: Float /* = 1/zoom */, val brushSize: Float)
class SnapStage(guides: List<SnapGuide>, ctx: SnapContext) : PointStage
class LazyRopeStage(radiusDoc: Float, elastic: Float) : PointStage
class HatchingStage : PointStage   // читает направление трека из SnapStage, если он есть

package app.painter.core.symmetry
class SymmetryCopies(val transforms: List<Affine2>, val domain: ((Float, Float) -> Boolean)?) {
    companion object { val NONE: SymmetryCopies }
}
object SymmetryMath { fun copies(cfg: SymmetryConfig, strokeStart: Vec2): SymmetryCopies }
```
- **Решение:** `InputPoint` **не меняется**. `TilePatch` не меняется. У `StrokeEngine` один новый параметр с дефолтом, так что старые вызовы и 18 тестов stroke остаются валидными.
- Перед `StrokeResampler` стоит адаптер `PointSink`: `lift()` вызывает `resampler.end()`, следующий `point()` вызывает `resampler.begin()`. Taper считается на каждый под-мазок.
- `DabSink` в M2 расширяется до структуры `Dab(x, y, size, flow, pressure, tilt, angle, flipX)` (это делает brush engine). FanOut на копию: позиция = `T·(x, y)`, `angle' = angle + rot(T)`. Для det < 0: `angle' = 2·axis − angle`, `flipX = !flipX`.
- SnapStage и Lazy пересоздаются на каждый мазок из `GuideState` (иммутабельный снимок), поэтому правка ручек во время мазка невозможна и гонок нет.

### 1.3 SnapStage: выбор трека и захват
1. `begin(p0)`: собрать треки всех активных SnapGuide. Если пусто, точки идут без изменений.
2. Точки **копятся без вывода**, пока смещение от p0 < `lockDist` = 12 экр. px.
3. Захват: `u = normalize(p_lock − p0)`. Выбираем трек с минимальным `acos(|u · tangentAt(p0)|)`. При равенстве (±1°) приоритет: Pen > Ellipse > Lines > Perspective.
4. Накопленные точки проецируются и выводятся. Дальше каждую точку проецируем: `project(p, hintS = s_prev)`.
5. Мазок кончился до захвата (точка или короткий тап): точки выводятся как есть.
6. Overlay: до захвата у курсора тонко видны все кандидаты, выбранный трек подсвечивается и продлевается до краёв экрана.
- **Решение:** если Snap включён, мазок прилипает всегда (как в IP), порога угла нет. Снапа к самим линиям сетки нет: прилипание идёт только по направлению.

### 1.4 Selection, слои, undo
- Selection mask применяется только в compositor: к превью и к финальному композиту, ко всем копиям симметрии и к заливке шейпа. На snap выделение не влияет.
- Закрытый (locked) слой: мазок и шейп инертны, ручки гайдов работают.
- Undo: мазок с N копиями = один `TilePatchCommand`. Зафиксированный шейп = один патч. Stamp = отдельный патч на каждую копию.
- **Решение:** правки гайдов, симметрии и сеток в History **не попадают**. Они помечают документ dirty (автосейв), в Tool settings есть Reset. IP ведёт себя так же: это состояние вида, а не пикселей.

### 1.5 Сериализация (`manifest.json → guides`)
Опциональный блок. Если его нет, берутся дефолты. Неизвестные поля игнорируются, schema проекта остаётся 1. Ключ `ruler` из document-model.md заменяется ключами `lines`/`ellipse`/`pen`. В core `Document` получает `var guides: GuideState = GuideState()` (неизменяемый data class, правки через `copy`).
```json
"guides": {
  "v": 1,
  "lines":    {"on":false,"a":[x,y],"b":[x,y],"perpendicular":true,"magnet":false,"edge":false,"locked":false},
  "ellipse":  {"on":false,"c":[x,y],"rx":300,"ratio":0.5,"angle":0,"magnet":false,"edge":false,"locked":false},
  "pen":      {"on":false,"nodes":[{"p":[x,y],"t":"cusp|smooth|aligned","in":[dx,dy],"out":[dx,dy]}],"closed":false,"locked":false},
  "lazy":     {"on":false,"radius":60,"elastic":0},
  "hatching": {"on":false},
  "symmetry": {"on":false,"type":"mirror|radial|kaleido","c":[x,y],"angle":90,"axes":"v|h|vh","planes":6,"fan":360,"crop":false,"locked":false},
  "perspective": {"on":false,"type":"1pt|2pt|3pt|iso|grid2d","vp":[[x,y],...],"horizon":0,"spacing":64,"ratio":1,"origin":[x,y],"snap":true,"visible":true,"locked":false},
  "grid":     {"on":false,"minor":32,"majorEvery":4,"origin":[0,0],"minorColor":"#FFFFFF26","majorColor":"#FFFFFF4D"}
}
```
- Конфиг выключенного гайда хранится: при повторном открытии гайд встаёт на прежнее место. `lazy.radius` в экр. px, всё остальное в px документа, углы в градусах.

## G2. Гайды
Общее:
- Открытие из Creative Tools → Guides. Каждый гайд добавляет mini-toolbar в стек справа сверху (первая иконка = закрыть).
- Гайды совместимы друг с другом и с симметрией. Несколько snap-гайдов дают общий пул треков (§1.3).
- Ручки скрываются при Lock. При активном snap-гайде распознавание шейпов выключено (решение).

### 2.1 Lines (линейка)
- Модель: отрезок a–b, задаёт ось. Дефолт: горизонталь через центр видимой области, длина 50% её ширины.
- Ручки: `a`, `b` (drag = угол и длина, snap угла к 0/45/90° ±3°); `move` (середина отрезка = center tick, drag = перенос).
- Треки:
  - parallel = прямая через p0 вдоль оси;
  - perpendicular = прямая через p0 поперёк оси (тоггл, дефолт вкл). Выкл = «только вдоль оси».
- **Magnet** (тоггл, дефолт выкл):
  - мазок, начатый в пределах 32 экр. px от оси, ложится на саму ось, параллели и перпендикуляры не даются;
  - мазок дальше 32 px свободный.
- **Сторона:** определяется знаком расстояния от p0 до оси. С `edge` = вкл трек сдвигается на `brushSize/2` в сторону p0 (кисть ведётся по краю, как по линейке). С `edge` = выкл трек идёт по центру оси (дефолт).
- Проекция: ортогональная на прямую, `s` = координата вдоль прямой.

### 2.2 Ellipse
- Модель: `c`, `rx`, `ratio = ry/rx ∈ [0.02, 1]`, `angle`. Дефолт: круг, rx = 25% короткой стороны видимой области.
- Ручки:
  - center: drag = перенос; при движении ближе 8 экр. px к линии малой оси перенос прилипает к ней (цилиндры);
  - angle: на конце большой оси, snap к 0/45/90° ±3°;
  - flatness: на малой оси, до упора = круг;
  - scale: угол описанного прямоугольника, меняет rx при сохранённом ratio.
- Пусть `E` переводит документ в единичное пространство: сдвиг на −c, поворот на −angle, масштаб y на 1/ratio.
- Треки:
  - concentric = эллипс с тем же центром, ratio и углом через p0. `r0 = |E(p0)|`, `project(p) = E⁻¹(r0 · normalize(E(p)))`;
  - radial = прямая через `c` и p0.
- Дуга или радиус выбираются по начальному направлению (§1.3).
- Magnet и edge как у Lines (32 px от контура). Трек = сам эллипс, edge-сдвиг = `±brushSize/2` по r.

### 2.3 Pen guide (путь)
- Построение:
  - тап = cusp-узел;
  - drag > 8 экр. px при постановке = `aligned`-узел с касательными по drag;
  - тап по отрезку = вставить узел;
  - тап по узлу = cusp ↔ smooth (smooth = авто-касательные Catmull-Rom, tension 0.5, GAPS);
  - drag узла на соседа (< 16 px) = удалить узел.
- Mini-toolbar: Close/Open, Lock, ✓ (удалить путь и начать новый).
- **Решение:** Lock обратимый. Close и Lock прилипают: тап по первому узлу = Close.
- Пока путь не залочен, касания строят путь, а не рисуют. После Lock любой мазок любой кистью идёт по пути. Смена кисти разрешена.
- Трек: путь, сплющенный в полилинию с допуском 0.25 px, с таблицей длины дуги.
  - `project`: ближайшая точка в окне `s_prev ± (2·|Δp| + 8 px)`. Без hint ищется глобально.
  - На замкнутом пути окно идёт по кругу.
  - Окно не даёт перескакивать на соседнюю петлю.

### 2.4 Lazy (верёвка)
- Кончик кисти `q` тянется за пером `p`: если `|p − q| > R`, то `q = p − R · normalize(p − q)`, иначе `q` стоит. Первая точка: `q = p`.
- `R = radius · screenToDoc · (1 + elastic · min(v, 4) / 4)`, где v = скорость пера в экр. px/мс.
- Настройки: radius 0–200 экр. px (дефолт 60, 0 = выкл), elastic 0–100% (дефолт 0).
- На pen-up кончик **не догоняет** перо (решение: суть верёвки).
- Overlay: окружность R вокруг пера и линия p–q, видны во время мазка и при hover стилуса.

### 2.5 Hatching
- Модификатор. Направление штриха `u0` берётся из захваченного трека, без трека = начальное направление мазка.
- Рисуются только участки, где проекция движения на `u0` (или ds на треке) положительна. Разворот больше чем на 2 px даёт `lift()`, ход вперёд начинает новый под-мазок.
- С Lines получаются параллельные штрихи, с Ellipse концентрические или радиальные, с Perspective штрихи к точке схода.

### 2.6 Grid overlay (View)
- Только показ, без снапа (снап делает 2D grid из перспективы).
- Две ступени: minor (дефолт 32 px), major каждые N minor (дефолт 4), origin, 2 цвета.
- Minor не рисуется, если шаг < 6 экр. px. Ручка origin видна только в Tool settings → «Изменить начало».

## G3. Шейпы
### 3.1 Общее
- Шейп = один редактируемый геометрический мазок **текущей кистью** в текущем режиме (Paint/Erase/Blend), с текущими цветом и параметрами.
- Шейп растровый: после фиксации это пиксели (векторный слой в M4).
- Рендер: геометрия → полилиния (допуск 0.25 px) → `StrokeEngine` с `stabilize=false`, `Interpolation.LINEAR`, `closed` для замкнутых. Pressure = 1 у всех точек.
- Taper: только у открытых шейпов. Замкнутые без taper, хвостовой штамп снимается, если он ближе 0.5·step к первому.
- Превью при правке: `cancel()` старого движка и новый `StrokeEngine` на каждое изменение, не чаще 1 раза за кадр.

| Шейп | Создание (drag) | Ручки | Constrain (Touch-Alt / тоггл ⊥) |
|---|---|---|---|
| Line | start → end | 2 конца, move (середина) | угол шагом 15° |
| Rect | угол → угол | 4 угла (противоположный неподвижен), 4 стороны (stretch), move (центр), rotate (над верхней стороной) | квадрат; поворот шагом 15° |
| Ellipse | центр → радиус (круг) | rx, ry (на осях), 4 угла рамки, move, rotate | круг; поворот шагом 15° |
| Polygon | центр → вершина (радиус + поворот) | вершина (радиус + угол), move; число углов 3–16 (дефолт 5) в Tool settings | поворот шагом 15° |
| Arc | start → end, середина на прогибе 25% хорды | start, end, mid (кривизна; на хорде = прямая) | mid ровно на серединном перпендикуляре |
| Path | как Pen guide (2.3), Close, ✓ | узлы, касательные aligned-узлов | касательные шагом 15° |
- Полилиния = Path из одних cusp-узлов (отдельного инструмента нет, решение).
- Line к перспективе не снапится. Ручки шейпов прилипают к пересечениям 2D grid (12 экр. px). Других снапов у шейпов в M2 нет.

### 3.2 Жизненный цикл
- Draft (ручки видны) → фиксация. Фиксацию вызывают:
  - тап мимо шейпа и ручек: в Shape tool тап начинает новый шейп, в Paint-инструменте начинает обычный мазок;
  - ✓ в mini-toolbar;
  - смена инструмента, слоя или кисти;
  - открытие workspace или Home.
- Hit-test (по порядку): ручка (24 экр. px) → контур (16 px, drag = перенос) → вне шейпа.
- **Stamp:** фиксирует копию (свой патч) и оставляет черновик для правки. В черновике можно менять size, opacity, цвет, кисть.
- Жест undo при черновике удаляет черновик без фиксации. Локальной истории ручек нет (решение).
- 2 пальца в черновике = навигация, черновик сохраняется.

### 3.3 Outline / Fill
- Тогглы в mini-toolbar: **Outline** (обводка кистью, дефолт вкл) и **Fill** (дефолт выкл, только для замкнутых шейпов). Хотя бы один включён.
- Fill:
  - цвет = текущий (отдельный цвет в M4), `alpha = color.a`, общий opacity cap буфера;
  - заливка рисуется в тот же stroke buffer до обводки: один патч, одно превью;
  - в Erase-режиме заливка стирает.
- Растеризация (core, CPU): even-odd по полилинии (GAPS), 4×4 суперсэмплинг на пиксель, только тайлы, пересекающие bbox. На GPU в M2 вместе со штампами можно оставить CPU, если укладываемся в бюджет.

### 3.4 Распознавание (Shape detection)
- Работает в Paint, Erase и Blend без активного snap-гайда. В Settings → Shapes: Вкл (дефолт), время удержания 0.1–5 с (дефолт 0.6 с), приоритет каждой фигуры 0.5–2 (дефолт 1).
- **Удержание:** `GestureRouter` во время мазка ставит таймер. Смещение > 6 экр. px от точки якоря переносит якорь и перезапускает таймер. Когда таймер сработал → `onStrokeHold()`.
- После `onStrokeHold`:
  - движок мазка делает `cancel()`, показывается пунктирный кандидат и иконки альтернатив (до 4, тап = переключить);
  - продолжение drag без отрыва: у Line и Polyline двигается последний узел; у Ellipse, Rect и Polygon размер (отношение расстояний до центра) и угол (разница углов);
  - отрыв → черновик с ручками (3.2).

**Алгоритм** (`ShapeDetector.detect(points, opts): List<Candidate>`, точки после Stabilizer):
1. L = длина, D = диагональ bbox. Если L < 24 экр. px → `[]`. Пересэмплинг по длине дуги с шагом `S = D/40`, от 32 до 256 точек.
2. `closed = |p_end − p_0| < 0.2·D`.
3. Углы: ShortStraw (Wolin и др., 2008, публичная статья).
   - `straw_i = |p_{i−3} p_{i+3}|`, угол = локальный минимум ниже `0.95·median`.
   - Между соседними углами отрезок прямой, если хорда/путь ≥ 0.95. Если нет, добавляем угол в минимуме straw средней части.
   - Тройку углов с хорда/путь ≥ 0.98 по объединённому отрезку сливаем.
   - Для closed стык p_end–p_0 проверяется по кругу.
4. Кандидаты и ошибка `e` = RMS расстояния от сэмплов до фигуры / D:

| Кандидат | Условие | Фит | max e |
|---|---|---|---|
| Line | open, хорда/L ≥ 0.9 | TLS (PCA), концы = проекции p_0 и p_end | 0.025 |
| Arc | open, 0 углов | круг Kåsa (МНК), развёртка 20–330°, r < 4·D | 0.03 |
| Ellipse | closed | прямой фит Fitzgibbon; ratio ≥ 0.88 → круг (r = средний) | 0.035 |
| Triangle | closed, 3 угла | вершины = углы, затем МНК-прямые сторон и их пересечения | 0.03 |
| Rect | closed, 4 угла, все углы 90 ± 15° | угол = среднее направлений сторон mod 90°, размеры по проекциям; < 4° от осей → без поворота | 0.03 |
| Quad | closed, 4 угла | как Triangle | 0.03 |
| Polygon N | closed, 5–8 углов, CV длин сторон < 0.2, углы ± 15° от (N−2)·180/N | центр = центроид, R = средний, поворот по первой вершине | 0.03 |
| Polyline | open, ≥ 1 угла | вершины = p_0, углы, p_end | 0.025 |
| Curve | всегда | Schneider (кубические Bézier, допуск 0.01·D), closed → замкнутый Path | — (запасной) |
5. Больше 8 углов у closed → только Ellipse и Curve. `score = e / maxE / priority`, берём кандидаты с e ≤ maxE, сортируем по score. Первый показывается, следующие 4 идут в иконки. Curve всегда последний, кроме случая, когда он единственный.

## G4. Симметрия
- Типы:
  - **Mirror**: оси `v` (дефолт), `h` или `vh`. Угол оси любой: `v` = 90°, `h` = 0°, `vh` = две ортогональные оси;
  - **Radial**: N 2–32 (дефолт 6), fan 10–360° (дефолт 360);
  - **Kaleido**: N 2–16 (дефолт 6), зеркала.
- Копии (θ = угол оси, c = центр):
  - Mirror v/h: {I, Refl(c, θ)};
  - vh: {I, Refl(θ), Refl(θ + 90°), Rot(180°)};
  - Radial: `Rot(c, k·step)`, k = 0..N−1, где step = 360/N при fan = 360, иначе fan/(N−1). Сектор fan ориентирован от угла точки начала мазка относительно c (копия 0 = оригинал);
  - Kaleido: `Rot(k·360/N)` и `Rot(k·360/N) ∘ Refl(θ)`, всего 2N копий.
- Не больше 32 копий (решение, бюджет на штампы).
- **Crop on axis** (Mirror, Kaleido; дефолт выкл): фундаментальная область = полуплоскость (или клин 180/N) с точкой начала мазка. Штамп, у которого центр вне области, пропускается во **всех** копиях. Мазок обрывается на оси без пиксельного клипа.
- Ручки:
  - center (move);
  - rotate (на оси, 120 экр. px от центра, snap 0/45/90° ±3°);
  - у Radial при fan < 360 ручка края сектора.
- Lock (mini-toolbar) прячет ручки.
- Overlay: все оси до краёв экрана (линия 1 dp, акцент 60%), у Radial лучи, у fan затенение вне сектора 10%.
- Превью мазка отдельно не делается: копии уже в stroke buffer.
- Симметрия применяется к мазкам, гайдам (копии трека как копии штампов) и шейпам. Liquify в M3.

## G5. Перспективные сетки
| Тип | Модель (дефолт) | Семейства треков | Ручки |
|---|---|---|---|
| 1pt | VP1 = центр холста, horizon = 0° | к VP1; ∥ горизонту; ⊥ горизонту | VP1 (move, за холстом тоже), поворот горизонта |
| 2pt | VP1 = (−0.25W, 0.45H), VP2 = (1.25W, 0.45H) | к VP1; к VP2; ⊥ линии VP1–VP2 | VP1, VP2 |
| 3pt | как 2pt + VP3 = (0.5W, 2H) | к VP1, VP2, VP3 | VP1–3 |
| Iso | spacing 64, angle 0 | 30°, 150°, 90° (+angle) | origin, spacing, rotate |
| 2D grid | spacing 64, ratio 1, angle 0, origin (0,0) | ∥ осям сетки (2) | origin, угол ячейки (spacing/ratio), rotate |
- Трек «к VP» = прямая через p0 и VP. Если |p0 − VP| < 4 px, семейство пропускается. Параллельный трек = прямая через p0.
- Выбор трека по начальному направлению (§1.3). Свободные мазки к самим линиям сетки не прилипают.
- Mini-toolbar: Close, Snap (дефолт вкл), Visibility (дефолт вкл). Скрытая сетка продолжает снапить. Tool settings: Lock, Reset.
- Активна одна сетка. Выбор другого типа заменяет текущую (её конфиг сохраняется в manifest).
- Overlay:
  - лучей на VP 24 через видимую область;
  - вертикали 2pt каждые 64 px;
  - цвета VP1 `#FF7A59`, VP2 `#3DDC97`, VP3 `#4F8CFF`, горизонт `#FFD166`, все 50%;
  - 2D и Iso рисуют только видимые линии, при шаге < 6 экр. px прореживают ×2.

## G6. UI (структура IP, визуал свой)
| Где | Что |
|---|---|
| Top bar → Creative Tools | Guides: Lines · Ellipse · Hatching · Lazy · Pen. Shapes: Line · Rect · Ellipse · Polygon · Arc · Path. Symmetry: Mirror · Radial · Kaleido. Perspective: 1pt · 2pt · 3pt · Iso · 2D grid. View: Grid overlay. M4-пункты (Radial guide, Array, Perspective array, Rounded rect, Arrow, Balloon) скрыты до M4 |
| Mini-toolbars (стек справа сверху) | Lines: Magnet, ⊥. Ellipse: Magnet. Pen: Close/Open, Lock, ✓. Lazy: —. Hatching: —. Symmetry: Lock, Crop. Perspective: Snap, Visibility. Shape: Outline, Fill, Stamp, ⊥ (constrain), ✓ |
| ⚙ Tool settings | секции активных: Lines (edge), Ellipse (edge), Lazy (radius, elastic), Symmetry (тип, N, fan, Reset), Perspective (Lock, Reset), Polygon (углы), Grid overlay (шаги, цвета, origin) |
| Settings → Shapes | Распознавание вкл, время удержания, приоритеты, Reset |
- Ручки (свой стиль):
  - круг 16 dp, заливка `#F5F7FA`, кольцо 2 dp `#1B1F27`; активная = кольцо акцент `#4F8CFF`;
  - move = крест-стрелка, rotate = круг с дуговой стрелкой на стебле 40 dp;
  - hit-зона 24 dp; размер на экране постоянный при любом zoom.
- Линии гайдов 1 dp, акцент 80%, бесконечные продолжения пунктиром. Выбранный трек 1.5 dp `#FF7A59`.
- Overlay рисуется Compose `Canvas` поверх `CanvasView` в экранных координатах через `ViewTransform.docToScreen` (решение: GL-проход не нужен).
- Ввод (app/render):
  - на `ACTION_DOWN` `GestureRouter` сначала спрашивает `Host.overlay?.hitTest(x, y)`; попадание = drag ручки, не мазок;
  - второй палец во время drag ручки откатывает drag и включает навигацию;
  - Touch-Alt: палец, который неподвижно лежал ≥ 150 мс до касания стилуса, = модификатор `constrain`. Это не отмена мазка: правило отмены касается только второго пальца после начала мазка.

## G7. Изменения контрактов (для координатора)
- `core/stroke`:
  - `StrokeOptions`, `PointStage`, `PointSink`, `Interpolation`;
  - `StrokeResampler` получает режим LINEAR и `end(closed)`;
  - `StrokeEngine` получает 6-й параметр с дефолтом;
  - `InputPoint` без изменений.
- Новые пакеты: `core/geom`, `core/guide` (Lines/Ellipse/Pen/Perspective SnapGuide, SnapStage, LazyRopeStage, HatchingStage, GuideState), `core/symmetry`, `core/shape` (Shape, ShapeHandles, ShapeOutline, PolygonFill, ShapeDetector, HoldDetector).
- `core/doc/Document`: `var guides: GuideState`. `core/io/ProjectIO`: пишет и читает `manifest.guides`.
- `app/render`:
  - `CanvasInputListener.onStrokeHold()` (default-метод);
  - `GestureRouter.Host.overlay: OverlayHitTester?`;
  - флаг `constrainHeld` в событиях мазка.
- Доки: brush-engine.md §1 (FanOut после dab generator), document-model.md (`guides`), file-formats.md (`guides`), ROADMAP (polygon в M2, curvilinear в M3).

## G8. Порядок работ
1. **G-a** geom + рефактор конвейера (PointStage, lift, LINEAR, closed, FanOut) без изменения поведения: тесты M1 зелёные.
2. **G-b** Симметрия + overlay/ручки (каркас hit-test, Compose overlay, mini-toolbar).
3. **G-c** Lines, Ellipse, Lazy, Hatching + GuideState в manifest.
4. **G-d** Перспектива 1/2/3pt, Iso, 2D grid; Grid overlay.
5. **G-e** Shape tool: все 6 шейпов, ручки, Outline/Fill, Stamp, constrain.
6. **G-f** Pen guide (переиспользует Path из G-e) + распознавание + hold.
- FanOut и `Dab` (angle/flip) согласовать с переводом штампов на GPU (M2 brush engine): fan-out остаётся в core, GPU получает готовый список штампов.

## G9. Приёмка
- Lines: 20 параллельных и 20 перпендикулярных штрихов дрожащей рукой. Все центры штампов на своей прямой в пределах 0.01 px.
- Ellipse: концентрические дуги не пересекаются. Радиальные штрихи проходят через центр (≤ 0.5 px).
- Pen: S-путь с близкими петлями, мазок вдоль пути не перескакивает. Замкнутый путь обходится по кругу.
- Lazy R = 60: кончик отстаёт ровно на R, на pen-up не догоняет.
- Симметрия: Kaleido N = 8 (16 копий) при 60 fps на 4096², один undo убирает все копии. Crop обрывает мазок на оси.
- Перспектива: в 2pt вертикали строго ⊥ горизонту, лучи сходятся в VP с точностью ≤ 0.5 px. VP за холстом работает.
- Шейпы: углы Rect острые (без скругления сплайном), стык замкнутого шейпа без тёмной точки при flow 30%. Fill + Outline = один undo.
- Распознавание: 10 эталонных фигур от руки (пальцем и стилусом) опознаются ≥ 9 из 10 раз. Удержание 0.6 с срабатывает и не мешает медленным мазкам.
- Сохранение: guides переживают save/load и force-stop. Проект M1 без `guides` открывается.
- Ни одной платной функции, рекламы, вотермарки (no-monetization).

## G10. JVM-тесты
| Пакет | Тесты |
|---|---|
| geom | Affine2: compose/inverse, det у отражения = −1, map rotation/reflection |
| stroke | M1-тесты без изменений. LINEAR: точки квадрата → центры штампов на рёбрах ±0.01. closed: нет лишнего штампа на стыке. lift: 3 под-мазка = 3 taper-хвоста. Options по дефолту = байт-в-байт M1-буфер |
| guide/SnapStage | захват через 12 px; hold-back и сброс накопленных; точка до захвата без изменений; выбор parallel/perp по углу 10° и 80°; приоритет при равенстве |
| guide/Lines | magnet: внутри/вне 32 px; знак стороны; edge-сдвиг = size/2 |
| guide/Ellipse | `|E(project(p))| = r0`; radial через центр; ratio 1 = круг |
| guide/Pen | непрерывность s на S-кривой; wrap на замкнутом; глобальный поиск без hint |
| guide/Lazy | `|p − q| ≤ R`; R = 0 = тождество; elastic увеличивает R |
| guide/Hatching | зигзаг из 5 ходов → 3 под-мазка вперёд; гистерезис 2 px |
| guide/Perspective | 1pt трек через VP; 2pt вертикаль ⊥ VP1–VP2; 3pt вертикаль через VP3; iso 3 направления; grid2d 2 направления; p0 ≈ VP пропускает семейство |
| symmetry | число копий (2/4/N/2N), все `|det| = 1`; fan-шаги; crop-домен; интеграция с движком: буфер Mirror v совпадает со своим отражением (≤ 1/255); один TilePatch; undo восстанавливает |
| shape | tessellation эллипса ≤ 0.25 px; ручки Rect (противоположный угол неподвижен), constrain квадрат/15°; Polygon N; Arc через 3 точки; even-odd звезда: центр пуст; площадь AA-круга ±1% |
| shape/ShapeDetector | синтетика с шумом σ = 1%·D (seed), по 50 на фигуру: line, arc, circle, ellipse, triangle, rect (повёрнут 30°), quad, pentagon, polyline (3 звена), S-curve, точность ≥ 95%; приоритет меняет победителя; короткий мазок → `[]`; > 8 углов → ellipse |
| shape/HoldDetector | неподвижно 600 мс → срабатывание; дрожание < 6 px не сбрасывает; движение сбрасывает |
| io | roundtrip GuideState; manifest без guides → дефолты; неизвестные поля игнорируются |
