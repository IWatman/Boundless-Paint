# Спеки инструментов M2: пипетка, выделение, трансформация, заливки

Источники: features/15-selection, 16-transform, 09-fill, 05-color, 02-workspace-ui-gestures; spec/GAPS.md (метрики), spec/UI-SHELL.md (каркас).
Правило M2: растровые операции считаются на CPU в `:core` (тайл-параллельно, `Dispatchers.Default`), GPU только превью и композит. Всё, что записывается в документ, считает CPU. Превью на GPU должно совпадать с CPU-результатом с точностью ≤ 2/255.
Монетизации нет: все режимы, интерполяции, типы градиентов доступны сразу.

## 0. Общие изменения ядра (делает координатор, до остальных пакетов)

### 0.1 Новые общие типы `core/raster`
| Тип | Суть |
|---|---|
| `MaskTile` + `isEmpty()`, `isFull()`, `fill(v: Byte)` | дописать методы, формат данных не меняется |
| `class MaskGrid(width, height)` | разреженная сетка `MaskTile`, отсутствующий тайл = 0. API зеркалит `TileGrid`: `get/getOrCreate/put/keys/entries/copy/compact/clear/keysInRect`, `value(x, y): Float` (0 вне холста), `bounds(): IntRect?` |
| `data class IntRect(x0, y0, x1, y1)` | полуоткрытый прямоугольник в px документа. `intersect`, `union`, `isEmpty`, `tileKeys(gridW, gridH)` |
| `class MaskPatch(before: Map<TileKey, MaskTile?>, after: …)` | аналог `TilePatch` для маски выделения. `byteSize = n · 65536` |
| `class BitPlane(width, height)` | 1 бит на пиксель (`LongArray`), `get/set/clear/or/and/andNot/bounds()`. Для регионов заливки и палочки (4096² = 2 МБ) |

### 0.2 Документ
- `Document.selection: Selection` (не null, пустая маска = выделения нет).
- `class Selection(width, height) { val mask: MaskGrid; var active: Boolean = true; val isEmpty: Boolean; val version: Long }`.
- «Действует» = `active && !isEmpty`. Только тогда инструменты маскируются.
- `Selection.coverageOrNull(): MaskGrid?` возвращает маску, если выделение действует, иначе null. Им пользуются все пиксельные операции.

### 0.3 Прочие общие API
| Где | Что | Зачем |
|---|---|---|
| `core/blend/Compositor` | `fun flattenTile(doc, key, includePaper: Boolean): Tile?` | композит одного тайла. Пипетка и заливка по холсту не держат весь холст в памяти (4096² float = 256 МБ) |
| `core/history` | `class MaskPatchCommand(patch, name)`, `class SetSelectionActive(on)` | undo выделения |
| `core/raster/MaskedEdit.kt` | `fun maskedCommit(layer, result: Map<TileKey, Tile?>, mask: MaskGrid?, name): TilePatch` | общий путь: `out = before·(1−m) + after·m` (premult). Им пользуются заливка, градиент, Delete, фильтры (M3) |
| `core/stroke/StrokeEngine` | ctor-параметр `clip: MaskGrid? = null` | мазок под выделением: в `compositeBuffer` `sa *= m`, в direct-режиме `DabRenderer.render(…, clip)` умножает покрытие даба на `m` |
| `app/render/CanvasView` | `setSelection(mask?, showOverlay)`, `setFloating(FloatingPreview?)`, `setGradientPreview(GradientSpec?)`, `setRegionPreview(MaskGrid?, color)` | превью (раздел 2.7, 3.6, 4.5) |
| `app/render` | `interface ToolInput { onDown/onMove/onUp/onCancel(docX, docY, p) ; onTap }` + маршрутизация в активный workspace | у workspace свои жесты, мазок не начинается |

### 0.4 Формат проекта (`core/io`)
- `manifest.json`: `"selection": { "active": true }`. Поле отсутствует = нет выделения. Schema остаётся 1 (новое поле опционально, старые читатели его игнорируют). **Решение.**
- `selection/tiles.idx` + `selection/tiles.bin`: формат как у `masks/<nodeId>/`, R8, LZ4 на тайл.
- Выделение сохраняется всегда (у ibis это опция, у нас дефолт без настройки). **Решение.**
- Буфер обмена, настройки инструментов и последний градиент хранятся в настройках приложения, не в проекте.

## 1. Пипетка (`core/pick`)

| Параметр | Диапазон | Дефолт |
|---|---|---|
| Источник | Холст (все видимые слои + Paper, скрытая Paper = белый) / Текущий слой | Холст |
| Размер сэмпла | 1–100 px (диаметр круга) | 1 |
| Задержка long-press | 200–1500 мс | 500 мс (сейчас константа 600 мс в `GestureRouter`, вынести в настройки) |

**Алгоритм.** Берём пиксели в круге диаметра `d` с центром в `(floor x, floor y)`. Тайлы получаем через `flattenTile` или из слоя, они кешируются на сессию пипетки (ключ `doc.modified`). Premult-значения усредняются, потом цвет un-premultiply. Если средняя `α < 1/255`, результата нет. Режим «Текущий слой»: adjustment-слои выше игнорируются. Итоговая альфа всегда 1, цвет кладётся в current color и в recent colors.

**Вызов:**
- long-press по холсту (дефолт, назначаемое);
- drag от кнопки Color в main toolbar наружу больше 24 dp (T6);
- иконка в Color panel;
- кнопка стилуса (назначаемая, долг 12);
- клавиша `I`.

Цвет обновляется на move и фиксируется на up. Тап вторым пальцем или drag обратно на кнопку отменяет.

**Лупа (UI):**
- Кольцо диаметром 112 dp, толщина 14 dp, центр смещён на 96 dp вверх от точки (у края экрана уходит вниз или вбок).
- Верхняя половина кольца = новый цвет, нижняя = текущий.
- Внутри кольца пиксели под точкой с увеличением ×8 (nearest) и перекрестье 1 px. Сэмпл-круг обведён, если `d > 1`.
- Под кольцом hex нового цвета.
- Настройки (⚙ пипетки, также Settings → Input): источник, размер, задержка, «Лупа вкл/выкл» (вкл).

**API:**
```kotlin
enum class PickSource { CANVAS, CURRENT_LAYER }
class Eyedropper(doc: Document, source: PickSource, layerId: String?, diameter: Int = 1) {
    fun sample(x: Float, y: Float): Rgba?            // straight, a = 1
    fun loupe(x: Int, y: Int, radius: Int, out: IntArray) // ARGB8888 (2r+1)², для UI
}
```
**Приёмка:**
- сэмпл однотонной области = цвет ± 1/255;
- `d = 5` на шахматке чёрное/белое даёт серый ≈ 0.5;
- 60 fps лупы на 4096² × 10 слоёв (кеш тайлов).

**Тесты:**
- `pick_singlePixel_currentLayer`;
- `pick_canvas_includesPaper`, `pick_canvas_hiddenPaperIsWhite`;
- `pick_average_premultipliedNoDarkFringe` (полупрозрачный красный на прозрачном = чистый красный);
- `pick_transparent_returnsNull`;
- `pick_outOfBounds_null`;
- `pick_cacheInvalidatedOnModified`.

## 2. Выделение (`core/select`)

### 2.1 Модель
- Маска 8 бит уровня документа (`Document.selection.mask`), значения 0..1, мягкие края допустимы.
- Отдельный от маски флаг `active`: выключение в top bar не стирает маску.
- Комбинирование новой формы `S` с маской `M`, нечёткие операции (**решение**: max/min, без накопления краёв):

| Режим | Формула | Вызов |
|---|---|---|
| Новое | `S` | дефолт, если маска пуста |
| Добавить | `max(M, S)` | дефолт, если маска есть (как Normal у IP) |
| Вычесть | `min(M, 1 − S)` | |
| Пересечь | `min(M, S)` | |

- Touch-Alt (удержание пальца при стилусе) на время жеста меняет Добавить ↔ Вычесть.

### 2.2 Инструменты
| Инструмент | Ввод | Алгоритм |
|---|---|---|
| Лассо | свободный drag, на up контур замыкается | полилиния, прореженная до шага ≥ 1 px документа → `PolygonRaster` |
| Полигон | тап = узел; drag = свободный участок; hold 1 с на узле = перетащить; тап по первому узлу или ✓ = замкнуть | тот же растеризатор |
| Прямоугольник | drag от угла к углу. После up форма остаётся редактируемой: 8 ручек resize, ручка rotate, drag внутри = move. Следующий тап вне формы = коммит и новая форма | форма → 4 вершины |
| Эллипс | drag от центра (IP). Ручки как у прямоугольника | 256 сегментов → полигон |
| Палочка | тап = tolerance из настроек; drag от точки = live tolerance | `Region` из §4.1 (общий код с заливкой) → маска |
| Кисть / ластик выделения | Paint и Erase main toolbar рисуют по маске любой кистью, цвет игнорируется | `MaskStrokeEngine`: обычный мазок в RGBA-буфер, на `end()` альфа буфера `a` идёт в маску: add `m + a(1−m)`, erase `m(1−a)` |
| Выделить непрозрачное | Layer options слоя/группы | `m = α` слоя (группа: композит без Paper) с выбранным режимом §2.1 (булевы операции IP) |

**PolygonRaster.**
- Скан-линии, правило non-zero (самопересекающееся лассо закрашивает петли). **Решение.**
- AA вкл: 4×4 суперсэмпл покрытия на пиксель (16 уровней). AA выкл: центр пикселя.
- Работа ограничена bbox, тайлы параллельно.

**Параметры палочки** (⚙, общие с заливкой: tolerance, boundary, reference):

| Параметр | Диапазон | Дефолт |
|---|---|---|
| Tolerance | 0–100 % | 15 % |
| Смежные (contiguous) | вкл/выкл | вкл |
| Источник | Холст / Текущий слой / Слой-источник | Холст |
| Граница | Цвет / Непрозрачность | Цвет |
| Расширение | −20…+20 px | 0 |
| Сглаживание | вкл/выкл | вкл |

Live tolerance: расстояние drag в dp / 300 dp → 0–100 %, пересчёт не чаще 15 Гц, последний запрос отменяет предыдущий.

### 2.3 Операции над маской
| Операция | Параметры | Алгоритм |
|---|---|---|
| Выделить всё | | все тайлы в пределах холста = 255 |
| Инвертировать | | `1 − m` по всему холсту, отсутствующие тайлы → полные |
| Снять | | маска пуста |
| Расширить / сжать | −100…+100 px, дефолт 0, слайдер с live-превью | бинаризация `m ≥ 0.5`, знаковое EDT (§4.2), покрытие `clamp(e + 0.5 − s, 0, 1)`. Мягкость маски теряется (**решение**), после этого можно Растушевать |
| Растушевать | радиус 0–250 px, дефолт 8 | гаусс σ = r/2, три box-прохода, сепарабельно, тайлы с полем 3σ |
| Трансформировать маску | | §3 с объектом «маска» |

### 2.4 Операции над содержимым
Пустое выделение означает весь слой или холст.

| Операция | Что делает |
|---|---|
| Копировать | активный слой × m → внутренний буфер + системный буфер (PNG через FileProvider) |
| Копировать сведённое | видимый композит без Paper × m → буфер |
| Вырезать | Копировать + Удалить, одна команда |
| Вставить | из буфера (свежее из внутреннего и системного) → новый слой над активным, позиция = исходная (или центр видимой области, если источник извне) → сразу открывается Transform |
| Дубликат на слой | активный × m → новый слой «<имя> копия», над активным → Transform |
| Изолировать | Вырезать на новый слой → Transform |
| Залить | текущий цвет, normal OVER, покрытие m, учитывает alpha lock |
| Удалить | `maskedCommit` прозрачным |
| Трансформировать | §3 с выделенным фрагментом |

- После Вставить, Дубликат и Изолировать маска сохраняется и трансформируется вместе с фрагментом (**решение**, отличие от IP: выделение не сбрасывается).
- `Clear layer` (long-press undo) при действующем выделении очищает только выделенное.

### 2.5 Маскирование других инструментов
- Кисть, ластик, blend (M2), заливка, градиент, Delete, фильтры (M3): результат проходит через `m`. Для мазка через `StrokeEngine(clip)`, для остального через `maskedCommit`.
- Workspace выделения открыт: Paint/Erase рисуют по маске, не по слою.
- Выделение выключено (`active = false`) или пусто: маскирования нет.

### 2.6 UI (IP-каркас)
**Editing Tools → Selection** открывает Selection workspace. Тулбар внизу, скроллится:
- ✗ (снять выделение и выйти);
- селектор инструмента (Лассо, Полигон, Прямоугольник, Эллипс, Палочка, Кисть);
- сегмент режима (Новое / Добавить / Вычесть / Пересечь);
- операции маски (Всё, Инвертировать, Расширить/сжать, Растушевать, Трансформировать маску);
- операции содержимого (Копировать, Копировать сведённое, Вырезать, Вставить, Дубликат, Изолировать, Залить, Удалить, Трансформировать);
- ✓ (выйти, выделение действует).

Прочие элементы:
- **Top bar**, пока маска не пуста: меню Selection (те же операции без инструментов + вид маски) и тоггл Active (акцентный цвет, клавиша `\`).
- **⚙** параметры палочки и AA фигур.
- **Layer options**: «Выделить непрозрачное» с режимами Новое / Добавить / Вычесть / Пересечь.
- **Вставить** продублировать в Options-меню, когда буфер не пуст.

### 2.7 Отображение
- **Marching ants.** Контур по iso 0.5 маски, marching squares на CPU. Кеш по `MaskTile.version`. Отрезки в VBO.
- Штрих 1 dp, шейдер чередует ч/б по `fract((gl_FragCoord.x + gl_FragCoord.y) / 16dp + t)`, сдвиг 0.5 периода в секунду. Полилинии сшивать не нужно. **Решение.**
- **Overlay.** Невыделенная область тонируется (R8-текстура маски, цвет #E0405A, 35 %). Включён внутри workspace, вне его только ants. В меню вид: Ants / Overlay / оба, цвет, opacity 0–100 %.
- Неактивное выделение: ants серым, без анимации.

### 2.8 Undo
- Каждая операция над маской = `MaskPatchCommand` (Лассо, Палочка, Расширить…). Мазок кисти выделения = одна команда.
- Операции над содержимым = `CompoundCommand(TilePatchCommand…, MaskPatchCommand?, AddLayer?)`.
- Тоггл Active = `SetSelectionActive`. ✗ = команда «Снять выделение».
- Live-превью (live tolerance, слайдер расширения) в историю не попадает, только финальное значение.

### 2.9 API
```kotlin
enum class SelectMode { NEW, ADD, SUBTRACT, INTERSECT }
sealed interface SelShape { fun toPolygon(): FloatArray }  // xy-пары в px документа
data class PolyShape(val pts: FloatArray) : SelShape
data class RectShape(val cx: Float, val cy: Float, val w: Float, val h: Float, val angleDeg: Float) : SelShape
data class EllipseShape(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angleDeg: Float) : SelShape
object PolygonRaster { fun rasterize(poly: FloatArray, w: Int, h: Int, aa: Boolean): MaskGrid }
object MaskOps {
    fun combine(dst: MaskGrid, src: MaskGrid, mode: SelectMode): Set<TileKey>
    fun selectAll(m: MaskGrid); fun invert(m: MaskGrid); fun fromAlpha(g: TileGrid): MaskGrid
    fun expand(m: MaskGrid, px: Float, aa: Boolean): MaskGrid
    fun feather(m: MaskGrid, radius: Float): MaskGrid
    fun contour(t: MaskTile, key: TileKey): FloatArray  // отрезки для ants
}
class MaskStrokeEngine(doc: Document, params: BrushParams, mode: StrokeMode) {
    fun begin(p: InputPoint); fun add(points: List<InputPoint>); fun end(): MaskPatch?; fun cancel()
}
object SelectionEdits {   // каждая функция возвращает готовую Command
    fun shape(doc: Document, s: SelShape, mode: SelectMode, aa: Boolean): Command
    fun wand(doc: Document, x: Int, y: Int, p: RegionParams, ref: RefSource, mode: SelectMode): Command
    fun copy(doc: Document, merged: Boolean): Floating  // Floating(pixels: TileGrid, bounds: IntRect)
    fun delete(doc: Document): Command?; fun fill(doc: Document, color: Rgba): Command?
    fun pasteAsLayer(doc: Document, f: Floating, at: IntRect): Command
}
```
**Приёмка:**
- лассо-круг r = 100 даёт площадь πr² ± 1 %;
- AA-край имеет промежуточные значения, без AA только 0 и 255;
- инверт дважды = исходная маска (побитно);
- мазок кистью вне выделения не меняет пиксели (побитно);
- палочка на 4096² ≤ 300 мс на mid-range;
- ants видны на любом фоне, 60 fps при pan;
- выделение переживает save/load и force-stop.

**Тесты:**
- `raster_rect_exactArea`, `raster_circle_areaWithin1pct`, `raster_selfIntersecting_nonZero`, `raster_aaOff_binary`;
- `combine_add_max`, `combine_subtract`, `combine_intersect`, `combine_newReplaces`;
- `invert_twice_identity`, `selectAll_coversCanvasOnly`;
- `expand_square_by10`, `contract_toEmpty`, `feather_preservesMassApprox`;
- `fromAlpha_matchesLayerAlpha`;
- `maskStroke_add_then_erase`;
- `strokeClip_outsideUnchanged`, `strokeClip_softEdgeScalesAlpha`;
- `delete_onlySelected`;
- `copyPaste_roundTrip_pixelsEqual`, `copyMerged_excludesPaper`;
- `undo_maskPatch_restoresExact`;
- `io_selection_saveLoad`, `io_noSelection_fieldAbsent`;
- `contour_squareMask_4edges`.

## 3. Трансформация (`core/transform`)

### 3.1 Объекты и сессия
**Объект:**
- Выделение действует: фрагмент `слой × m` на каждом слое набора, маска трансформируется вместе с ним.
- Выделения нет: слой целиком.
- Отдельный объект «маска выделения»: Selection → Трансформировать маску.

**Набор слоёв:**
- По умолчанию активный слой.
- Тап по превью в Layer panel добавляет или убирает слой (акцентная рамка).
- Группа означает все её растровые потомки.
- Заблокированные слои пропускаются (тост).

**Вход в сессию:**
1. Для каждого слоя `floating = pixels × m` (копия тайлов) и `rest = pixels × (1 − m)`. Без выделения `rest` пуст.
2. Cage = общий плотный bbox альфы всех floating, выровнен по осям холста.

**Сессия:**
- Режимы можно чередовать. Все правки составляют одно отображение от **исходного** floating, качество не теряется до ✓ (**решение**: virtual transform внутри сессии; сохранение source между сессиями по GAPS → M3).
- ✓: CPU-ресэмплинг `floating` по обратному отображению → новые тайлы → normal OVER на `rest` → `TilePatch` на слой (+ `MaskPatch`) → одна `CompoundCommand("Трансформация")`.
- Всё, что вышло за холст, обрезается. **Решение.**
- ✗ восстанавливает слои без записи в историю.

### 3.2 Режимы
| Режим | Жесты | Модель |
|---|---|---|
| Базовый (дефолт) | drag внутри или вне cage = move; угол = scale (пропорция по тогглу «Uniform», дефолт вкл, от центра); боковая ручка = stretch по одной оси от противоположной стороны; ручка rotate над верхним краем (32 dp); 2 пальца внутри cage = move + pinch + rotate, вне cage = навигация; Touch-Alt: move по 45°, scale шагом 100 % | `Affine(a, b, c, d, tx, ty)` |
| Якорь | как Базовый, но pivot = перетаскиваемая точка (может быть за холстом), прилипает к 9 точкам cage и центру холста в радиусе 24 dp. Pivot служит центром scale/rotate/flip/45° | Affine вокруг pivot |
| Искажение | 4 угла свободно; боковая ручка: перпендикулярно = stretch, вдоль = skew (двигает оба угла); drag внутри = move всей четвёрки. Невыпуклый четырёхугольник не принимается, остаётся последнее валидное положение | гомография 3×3 по 4 парам точек (DLT, Гаусс 8×8 с выбором ведущего) |
| Деформация | сетка 4×4 контрольных точек (3×3 ячейки), drag за любую точку внутри тянет ближайшую в параметрах (u, v) с весами Бернштейна | бикубический Безье-патч = clamped B-spline 4×4 (GAPS). Тесселяция 32×32 в превью, 64×64 на коммите |

**Кнопки:**
- Отразить H и Отразить V относительно pivot (центр cage, если pivot не задан).
- ↻45° по часовой.
- «Штамп»: текущий результат копируется на слой «<имя> штампы» (создаётся один раз за сессию), сессия продолжается.
- «Только рамка» (unlock cage): двигается cage без объекта. Только Базовый и Искажение.

**Композиция:** affine × homography сводится в одну гомографию. Warp применяется после неё, к узлам сетки, прошедшим через гомографию. Итог хранится как `Mapping`.

### 3.3 Числовой ввод
Кнопка «123» открывает диалог:

| Поле | Диапазон | Примечание |
|---|---|---|
| X, Y | центр cage, px | |
| W, H | 1…32768 px или 1…10000 % | замок пропорций |
| Угол | −360…360°, шаг 0.1 | |
| Сдвиг X/Y | −89…89° | |

Доступен в Базовом и Якоре. В Искажении диалог показывает координаты 4 углов.

### 3.4 Ресэмплинг
| Метод | Формула | Когда |
|---|---|---|
| Ближайший | `floor(u), floor(v)` | пиксель-арт |
| Билинейный | 2×2 | быстрый |
| Бикубический (дефолт) | Catmull-Rom (a = −0.5), 4×4, premult, результат clamp: `α ∈ [0, 1]`, `rgb ≤ α` | качество |

- Уменьшение сильнее 2× (локальный якобиан: `max singular < 0.5`): сначала выбирается уровень box-mip-пирамиды floating (каждый уровень = среднее 2×2) с масштабом ≥ 0.5, потом метод. Так получается «усреднение» ibis без отдельного режима. **Решение.**
- Lanczos 2/3 → P2.
- Метод выбирается в ⚙ сессии и запоминается в настройках.
- Превью на GPU: linear + mipmap, nearest для «Ближайшего».

### 3.5 Привязки (⚙, дефолт вкл)
- Move: края и центр cage прилипают к краям и центру холста, порог 8 dp экрана.
- Rotate: 0/90/180/270 ± 3° (как T1). С Touch-Alt шаг 15°.
- Scale: к 100 % ± 2 %.
- Pivot: см. Якорь.

### 3.6 Превью
- `CanvasView.setFloating(FloatingPreview(layerIds, textures, mapping, tess))`.
- `rest` загружается как пиксели слоя. Floating рисуется сеткой треугольников с uv в стеке на месте слоя (тот же blend и opacity). Перезаливка floating-текстур не нужна.
- Cage, ручки и pivot рисуются Compose-оверлеем поверх GL. HUD показывает W×H, угол, %.

### 3.7 UI
**Вход:** Editing Tools → Transform; из Selection; автоматически после Вставить, Дубликат, Изолировать, Импорт изображения.

**Тулбар внизу:** ✗ · режим (Базовый / Якорь / Искажение / Деформация) · Отразить H · Отразить V · ↻45° · Штамп · Только рамка · 123 · ✓.

**⚙:** интерполяция, Uniform, привязки.

### 3.8 API
```kotlin
sealed interface Mapping {
    /** dst → src; false если точка вне области отображения. */
    fun inverse(x: Float, y: Float, out: FloatArray): Boolean
    fun dstBounds(src: IntRect): IntRect
}
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) : Mapping {
    fun then(o: Affine): Affine; fun inverted(): Affine?
    companion object { val IDENTITY: Affine; fun trs(tx: Float, ty: Float, sx: Float, sy: Float, rotDeg: Float, px: Float, py: Float): Affine }
}
class Projective(val m: FloatArray /*3×3*/) : Mapping { companion object { fun fromQuads(src: FloatArray, dst: FloatArray): Projective? } }
class BezierWarp(val pre: Projective, val ctrl: FloatArray /*16 точек dst*/, val src: IntRect, val tess: Int = 64) : Mapping
enum class Resample { NEAREST, BILINEAR, BICUBIC }
object Resampler { fun sample(src: TileGrid, mips: MipChain?, u: Float, v: Float, mode: Resample, out: FloatArray) }
class TransformSession(doc: Document, layerIds: List<String>, target: Target /*PIXELS, SELECTION_MASK*/) {
    val cage: IntRect; val floating: Map<String, TileGrid>
    fun stamp(mapping: Mapping, mode: Resample): Command
    fun commit(mapping: Mapping, mode: Resample): Command  // CompoundCommand, уже применена
    fun cancel()
}
object Snap { fun move(cage: RectF, canvasW: Int, canvasH: Int, thresholdPx: Float): PointF; fun angle(deg: Float, step: Float, tol: Float): Float }
```
- Коммит: обходятся тайлы `dstBounds ∩ холст`, параллельно, на каждый пиксель `inverse` → `sample`.
- Warp: для каждого треугольника тесселяции барицентрический обход dst-пикселей → uv. Это та же геометрия, что на GPU.

**Приёмка:**
- move на целые px с Nearest и Bicubic побитно равен сдвигу;
- поворот 4 × 90° = исходник побитно, flip дважды = исходник;
- scale 50 % шахматки 1 px = серый 0.5 ± 1/255 (mip);
- Искажение квадрата в те же углы = identity ± 1/255;
- превью и коммит расходятся ≤ 2/255 и ≤ 0.5 px;
- 3 слоя + выделение → одна запись undo, undo восстанавливает слои и маску побитно;
- коммит 4096² bicubic ≤ 1.5 с.

**Тесты:**
- `affine_compose_inverse`;
- `trs_aroundPivot`;
- `homography_identityQuad`, `homography_squareToTrapezoid_cornersExact`, `homography_degenerate_null`;
- `warp_identityGrid_isIdentity`, `warp_cornersInterpolate`;
- `resample_nearest_integerShift_exact`, `resample_bicubic_integerShift_exact`, `resample_bicubic_noOvershoot`;
- `downscale_usesMip_checkerboardGray`;
- `rotate90x4_identity`, `flipTwice_identity`;
- `session_withSelection_restUntouched`, `session_multiLayer_singleCommand`, `session_offCanvasClipped`, `session_cancel_noHistory`, `session_maskTransformed`;
- `stamp_createsLayerOnce`;
- `snap_move_edges`, `snap_angle_90`.

## 4. Заливки (`core/fill`)

### 4.1 Регион (общий для ведра, палочки, ограничения градиента)
**Источник пикселей `PixelSource.tile(key)`:**
- Холст: видимые слои, без Paper. Пустые места остаются прозрачными, поэтому граница по непрозрачности работает. **Решение.**
- Текущий слой.
- Слой-источник: назначается в Layer options «Сделать источником заливки». Пока источник ≠ активному, на тулбаре видна иконка.

**Шаги:**
1. **Seed:** цвет `c0` под точкой (un-premult).
2. **Fillable `F`** (BitPlane, тайлы параллельно):
   - граница Цвет: `max(|ΔR|, |ΔG|, |ΔB|, |ΔA|) ≤ tol` на un-premult (GAPS);
   - граница Непрозрачность: `|ΔA| ≤ tol`;
   - tol 100 % = весь холст.
3. **Gap closing** `g` (0–20 px, дефолт 0 для палочки, 4 для ведра):
   - `B = ¬F`; `B' = dilate(B, g)` (EDT ≤ g);
   - flood от seed в `¬B'` даёт `R'`;
   - `R = dilate(R', g + 1) ∩ F`;
   - если seed попал в `B'`, gap closing не применяется;
   - протечка через разрыв ограничена g px (принято).
4. **Flood:** скан-линейный с явным стеком отрезков, 4-связность (не протекает через диагональные линии). Non-contiguous: `R = F`.
5. **Expansion** `e` и AA → покрытие через знаковое EDT (§4.2): `cov = AA ? clamp(e + 0.5 − s, 0, 1) : (s ≤ e ? 1 : 0)`.
6. **Итог:** `cov × selection`.

### 4.2 EDT
- Точное евклидово, Felzenszwalb–Huttenlocher: 1D-параболы, сначала столбцы, потом строки, параллельно по полосам.
- Считается только в `bbox(R) ± (|e| + g + 2)`.
- Знаковое расстояние:
  - `s = d_in − 0.5` снаружи (`d_in` = расстояние до ближайшего пикселя R);
  - `s = −(d_out − 0.5)` внутри.

### 4.3 Ведро
| Параметр | Диапазон | Дефолт |
|---|---|---|
| Tolerance (общий с палочкой) | 0–100 % | 15 % |
| Граница | Цвет / Непрозрачность | Цвет |
| Источник | Холст / Текущий слой / Слой-источник | Холст |
| Gap closing | 0–20 px | 4 |
| Expansion | −20…+20 px, шаг 0.5 | +1.5 (как у ibis, закрывает щели антиалиаса лайнарта) |
| Сглаживание | вкл/выкл | вкл |
| Смежные | вкл/выкл | вкл |
| Режим | Залить / Стереть (в прозрачность) | Залить |
| Opacity | 0–100 % | 100 % |

- **Применение:** Залить = normal OVER цвета с `cov · opacity`, alpha lock соблюдается. Стереть = `d ·= 1 − cov · opacity`. Через `maskedCommit`, одна команда «Заливка».
- **Перекраска:** после заливки смена цвета в Color panel перекрашивает последнюю заливку (revert + повтор с новым цветом, та же запись undo), пока не сделана следующая заливка или ✓. **Решение.**
- **Live tolerance:** как у палочки. Превью = `setRegionPreview(cov, color)`.
- **Лассо-заливка** (подтип в селекторе): обвести → полигон (§2.2, non-zero, AA вкл) → залить или стереть с opacity, с учётом выделения. Варианты «все слои» и Surrounding fill → M4.

### 4.4 Градиент
**Типы.** `p0` = старт, `p1` = конец, `d = p1 − p0`.

| Тип | `t` |
|---|---|
| Линейный | `dot(p − p0, d) / |d|²` |
| Радиальный | `|p − p0| / |d|` |
| Симметричный | `|dot(p − p0, d)| / |d|²` |
| Угловой | `((atan2(p − p0) − atan2(d)) / 2π) mod 1`, затем `fract(t · n)`, повторы n = 1–100 (дефолт 1) |

**Повтор:**
- Pad (дефолт) = clamp;
- Repeat = `fract`;
- Reflect = треугольник;
- Reverse: `t → 1 − t`.

Для Углового Reflect по умолчанию вкл, как у IP.

**Стопы:**
- 2–32 штуки, `pos 0..1`, цвет straight sRGB с альфой.
- Интерполяция между соседними стопами линейная в **premultiplied** (нет тёмной каймы у прозрачных стопов). **Решение.**
- За пределами крайних стопов цвет крайнего.
- Пресеты: «Цвет → прозрачный» (дефолт при первом запуске: current color α1 → тот же α0), «Цвет → предыдущий цвет». Потом используется последний градиент.

**Дизеринг** (дефолт вкл):
- к premult-RGBA добавляется детерминированный треугольный шум `(h1 + h2 − 1) / 255`, где `h` = хеш `(x, y, канал)` в [0, 1);
- clamp в `[0, α]`;
- детерминизм нужен, чтобы превью, коммит и тесты совпадали.

**Применение:**
- normal OVER с `opacity (0–100 %, деф. 100) × selection × (регион?)`;
- тоггл «Ограничить областью» (дефолт выкл) берёт регион §4.1 от `p0` с настройками ведра;
- alpha lock соблюдается;
- одна команда «Градиент» на каждый подтверждённый градиент.

**Ручки (до ✓ или до начала следующего градиента):**
- стоп-кружки на оси: drag = двигать, tap = цвет (Color panel + opacity стопа), увести от оси дальше 48 dp = удалить (минимум 2 остаются);
- tap по оси = новый стоп (цвет = текущее значение в этой точке);
- drag оси = двигать весь градиент;
- концы `p0` и `p1` двигаются.

Для Радиального рисуется окружность-превью, для Углового луч 0°.

**Превью:**
- GPU-шейдер с теми же формулами (uniform: тип, p0, p1, до 32 стопов, повтор, n, дизеринг) поверх слоя под маской выделения;
- CPU-коммит считает ту же функцию, тест-вектор GPU↔CPU обязателен (как долг 5).

### 4.5 UI
**Вход:** Creative Tools → Tools → Fill открывает Fill workspace.

**Тулбар внизу:**
- тип: Ведро / Градиент (подтип в выпадающем меню: Линейный, Радиальный, Симметричный, Угловой) / Лассо-заливка;
- Источник (иконка-переключатель Холст / Текущий / Слой-источник);
- Залить ↔ Стереть;
- контролы типа;
- ✓.

Контролы типа:
- Ведро: tolerance (virtual slider);
- Градиент: повтор, Reverse, повторы n (Угловой), пресет, дизеринг, opacity.

**⚙:** tolerance, граница, gap, expansion, AA, смежные, «Ограничить областью».

У Ведра нет ✗: ✓ + undo (как у IP). У Градиента ✗ отменяет только текущий градиент. Main toolbar и Color panel остаются доступны. Несколько заливок разных типов подряд допускаются.

### 4.6 API
```kotlin
enum class Boundary { COLOR, ALPHA }
sealed interface RefSource { object Canvas : RefSource; object Current : RefSource; data class Layer(val id: String) : RefSource }
data class RegionParams(val tolerance: Float = 0.15f, val boundary: Boundary = Boundary.COLOR,
    val contiguous: Boolean = true, val gapPx: Int = 0, val expandPx: Float = 0f, val antialias: Boolean = true)
fun interface PixelSource { fun tile(key: TileKey): Tile? }
object Region {
    fun source(doc: Document, ref: RefSource): PixelSource
    fun fillable(src: PixelSource, w: Int, h: Int, seed: Rgba, p: RegionParams): BitPlane
    fun flood(f: BitPlane, x: Int, y: Int): BitPlane
    fun coverage(r: BitPlane, expandPx: Float, aa: Boolean): MaskGrid
    fun compute(doc: Document, ref: RefSource, x: Int, y: Int, p: RegionParams): MaskGrid?  // весь пайплайн §4.1
}
object Edt { fun signed(r: BitPlane, area: IntRect): FloatArray }
object BucketFill { fun apply(doc: Document, layerId: String, cov: MaskGrid, color: Rgba, opacity: Float, erase: Boolean): TilePatch? }
enum class GradientType { LINEAR, RADIAL, SYMMETRIC, ANGULAR }
enum class Spread { PAD, REPEAT, REFLECT }
data class GradientStop(val pos: Float, val color: Rgba)
data class GradientSpec(val type: GradientType, val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val stops: List<GradientStop>, val spread: Spread = Spread.PAD, val reverse: Boolean = false,
    val repetitions: Int = 1, val dither: Boolean = true)
object Gradient {
    fun t(g: GradientSpec, x: Float, y: Float): Float
    fun eval(g: GradientSpec, x: Int, y: Int, out: FloatArray)  // premult + dither
    fun apply(doc: Document, layerId: String, g: GradientSpec, opacity: Float, region: MaskGrid?): TilePatch?
}
```
Все `apply` сами умножают на `doc.selection.coverageOrNull()` и возвращают патч уже применённым (как `StrokeEngine.end()`).

**Приёмка:**
- заливка замкнутого круга лайнарта толщиной 3 px с разрывом 3 px при gap 4 не протекает наружу;
- при expansion 1.5 нет белой каймы у AA-линии;
- tol 0 на однотонном слое заливает весь смежный регион, tol 100 весь слой;
- 4-связность: диагональная линия толщиной 1 px не пропускает заливку;
- ведро на 4096² ≤ 300 мс (gap 0), ≤ 800 мс (gap 4);
- градиент на 4096² ≤ 500 мс, без бандинга на 8-битном экспорте при dither вкл;
- превью и коммит ≤ 2/255.

**Тесты:**
- `fillable_chebyshevMetric`, `fillable_alphaBoundary`, `fillable_tol100_all`;
- `flood_4connected_diagonalBlocks`, `flood_nonContiguous_equalsF`, `flood_largeNoStackOverflow` (4096² пустой);
- `gap_closesSmallGap`, `gap_seedInGap_fallback`;
- `edt_matchesBruteForce_random64`;
- `expand_positive_negative`, `aa_coverageRamp`;
- `bucket_respectsSelection`, `bucket_alphaLock`, `bucket_erase`, `bucket_canvasRef_ignoresPaper`, `bucket_sourceLayer`;
- `lassoFill_area`;
- `gradient_linear_endpoints`, `gradient_radial`, `gradient_symmetric`, `gradient_angular_reps`;
- `gradient_spread_repeat_reflect`, `gradient_reverse`;
- `gradient_premultInterp_noDarkFringe`;
- `gradient_dither_deterministic`, `gradient_dither_boundedAmplitude`;
- `gradient_stops_unsorted_sorted`;
- `gradient_respectsSelection`;
- `undo_fill_singleCommand`;
- `recolor_lastFill_sameHistoryEntry`.

## 5. Порядок реализации
1. **§0 (координатор):** общие типы, `Document.selection`, `MaskPatchCommand`, `flattenTile`, `maskedCommit`, `StrokeEngine(clip)`, IO выделения. Без этого остальное не собирается.
2. **Параллельно:** `core/pick` (§1), `core/fill/Region + Edt` (§4.1–4.2, нужен палочке), `core/transform` (§3, от выделения зависит только через `MaskGrid`).
3. **`core/select`** (§2), использует `Region`.
4. **Ведро и градиент** (§4.3–4.4).
5. **App:**
   - `ToolInput`-маршрутизация;
   - шейдеры overlay/ants/floating/gradient;
   - workspaces Selection, Transform, Fill;
   - лупа;
   - настройки.
