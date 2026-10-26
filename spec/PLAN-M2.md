# План реализации M2

Цель M2: паритет с основой IP и полный стоковый набор кистей. Спеки инструментов:
- [tools/m2-brush.md](tools/m2-brush.md): движок кисти, каталог ≈236, браузер, редактор, стилус, состояние инструментов;
- [tools/m2-select-transform-fill.md](tools/m2-select-transform-fill.md): пипетка, выделение, трансформация, ведро, градиент;
- [tools/m2-layers-adjust.md](tools/m2-layers-adjust.md): группы, мультивыбор, маски, 33 режима, adjustment-слои, коррекции;
- [tools/m2-guides-shapes.md](tools/m2-guides-shapes.md): гайды, шейпы и распознавание, симметрия, перспектива.

При расхождении спек действует этот файл.

## 1. Архитектурные решения M2
| # | Решение | Почему |
|---|---|---|
| A1 | Штампы и растровые операции считаются на CPU в `:core`, тайл-параллельно. GPU только композит и превью. GPU-штампы делаем только если не пройден бенчмарк (m2-brush §6) | детерминизм, JVM-тесты, одна реализация. Отступление от ROADMAP «GPU-штампы» |
| A2 | Тайлы слоёв хранятся в half (8 Б/px), буферы мазка во float32 (`Tile(precise = true)`). На GPU тайлы грузятся как `GL_HALF_FLOAT` | ✅ сделано |
| A3 | `MaskGrid(width, height, defaultValue)` — единый тип для выделения (default 0) и масок слоёв (0 или 255) | ✅ сделано |
| A4 | Симметрия размножает **штампы**, а не точки | одинаковые spacing и рандом у копий, один патч |
| A5 | Правки гайдов, симметрии и сеток не попадают в History | это состояние вида |
| A6 | Мультивыбор слоёв — состояние UI. Команды принимают списки id | |
| A7 | Неразрушающие adjustment-слои; сила задаётся opacity слоя | как ibis |
| A8 | Акварель — CPU-симуляция в половинном разрешении (m2-brush §1.9) | вместо GPU-симуляции из GAPS |

## 2. Единый контракт мазка (сводит четыре спеки)
```kotlin
// core/stroke/Pipeline.kt (✅ есть): PointSink, PointStage, Interpolation, SymmetryCopies, FillSpec,
// StrokeOptions(stabilize, stages, interpolation, closed, copies, fill, clip), StrokeTarget(Layer | Mask | Selection)
class StrokeEngine(
    doc: Document,
    target: StrokeTarget,
    brush: Brush,
    res: BrushResources,
    color: Rgba,
    mode: StrokeMode,              // PAINT, BLEND, ERASE, CLONE
    ctx: StrokeContext,            // zoom, viewRotation, seed, lowerComposite, paperTexture, clone, threads
    options: StrokeOptions = StrokeOptions(),
) {
    fun begin(p: InputPoint); fun add(points: List<InputPoint>); fun cancel()
    fun end(): StrokeCommit?       // Pixels(TilePatch) | Mask(MaskPatch); применён к документу
    val buffer: TileGrid; fun takeDirty(): Set<TileKey>; val previewOpacity: Float
}
// Совместимость M1: StrokeEngine(doc, layerId, params, color, mode) остаётся обёрткой, end() даёт TilePatch?.
```
- `InputPoint` получил `twist` (✅). `Dab` несёт `angle` и `flipX` для зеркальных копий.
- Порядок стадий: stabilizer → `options.stages` (lazy, snap, hatching) → resampler (`interpolation`, `closed`) → dab generator → symmetry copies → kernels → buffer → композит (cap, blend mode кисти, alpha lock, `clip`).
- Цель Mask/Selection: значение маски = покрытие мазка × яркость цвета; ластик уменьшает.
- `core/geom/Affine2`, `Vec2` (✅ есть).

## 3. Общие типы, готовые до старта фаз (✅)
- `core/raster`: `Tile` (half/precise, `t[i]`, `read/write`, `scale`, `toFloats`, `copyHalvesTo`, `ofHalves`, `contentEquals`), `TileGrid(precise)`, `MaskTile` (`isUniform/isEmpty/isFull/fill`), `MaskGrid`, `IntRect`, `MaskPatch(targetId)`, `BitPlane`.
- `core/doc`: `Selection` (`Document.selection`), `LayerMask` (`Node.mask`), `ColorTag` (`Node.colorTag`), `Node.reference`, `EditTarget` (`Document.activeTarget`), `Document.referenceNode`.
- `core/blend/Compositor.flattenTile(doc, key, includePaper)`.

## 4. Фазы и владельцы пакетов
Агенты пишут только в свои пакеты и тесты к ним. Общие типы из §3 меняет только координатор.

### Фаза 1: ядро (параллельно, 4 потока)
| Поток | Владеет | Содержание |
|---|---|---|
| K (кисть) | `core/stroke` (кроме Pipeline.kt), `core/io/BrushIO.kt` | m2-brush S0 + S1: `LayerWorkingSet`, `TilePool`, `TileScheduler`, hash-RNG, `PathWalker`, `DabGenerator`, `Brush` + codec, re-render на pen-up, lazy rope, after-smoothing, бенчмарк; bitmap-головы, rotation, jitter, dynamics, taper, профили. Контракт §2, включая `options` и цели Mask/Selection |
| L (слои) | `core/doc` (кроме Masks.kt), `core/adjust`, `core/history` (кроме SelectionCommands.kt), `core/blend`, `core/io/ProjectIO.kt` | m2-layers S1: `AdjustmentLayer`, `AdjustParams`, `AdjustMath`, `AdjustLut`, новые команды, маски и adjustment в Compositor, manifest schema 2 (маски, adjust, colorTag, reference, activeTarget, **selection**, **guides** как непрозрачный JSON-блок), тест-векторы blend/adjust |
| S (выделение и заливки) | `core/pick`, `core/select`, `core/transform`, `core/fill`, `core/raster/MaskedEdit.kt`, `core/history/SelectionCommands.kt` | m2-select: всё ядро §1–§4 (пипетка, операции выделения, палочка, трансформации и ресэмплинг, ведро, градиенты) |
| G (гайды) | `core/guide`, `core/symmetry`, `core/shape`, `core/perspective` | m2-guides: `SnapStage`, `LazyRopeStage`, `HatchingStage`, гайды Lines/Ellipse/Pen, `SymmetryMath`, перспективные сетки, генерация шейпов, распознавание, `GuideState` + сериализация (kotlinx) |

### Фаза 2: рендер и связка (после фазы 1)
| Поток | Владеет | Содержание |
|---|---|---|
| R (рендер) | `app/render` | маски (R8), adjustment-шейдер, фиксы ADD и клипа к passthrough, выделение (marching ants), floating-превью трансформации, превью градиента и региона, превью мазка в маску, parity-тесты (androidTest) |
| V (связка) | `app/studio`, `MainActivity` | координатор: ViewModel под новые API, ввод инструментов (`ToolInput`), workspaces |

### Фаза 3: UI (после фазы 2, параллельно по экранам)
| Поток | Владеет | Содержание |
|---|---|---|
| UB | `app/ui/brush` | браузер, редактор, стилус-настройки |
| UL | `app/ui/studio/LayerPanel*`, `app/ui/adjust` | группы, мультивыбор, маски, режимы с превью, adjustment, кривые, Levels |
| US | `app/ui/tools` | контекстные тулбары выделения, трансформации, заливки, градиента, лупа |
| UG | `app/ui/guides` | overlay гайдов и ручек (Compose), мини-тулбары, симметрия, перспектива |

### Фаза 4: движок до конца и каталог
- K: S2–S9 (S2 ∥ S3 ∥ S4 ∥ S7 параллельно, разными агентами по kernel-файлам).
- Каталог S10: `tools/gen_assets.py` + `tools/catalog/*.py` → `app/src/main/assets/brushes/`. Категории подключаются по мере готовности фич.

### Фаза 5: приёмка M2
- Все JVM-тесты, бенчмарк кисти, parity-тесты на эмуляторе.
- Ручной чек-лист из «Приёмки» каждой спеки на эмуляторе, стилус — на XP-Pen, когда будет доступ.
- Долги STATUS.md 4, 5, 6, 8, 9, 10, 11, 12, 13 закрываются в фазах 2–4.

## 5. Изменения объёма относительно ROADMAP
- В M2 переносятся: Clone (кисть), правильный многоугольник, Hatching, Color Balance, Levels, Gradient map, Posterize, Black & White.
- В M3 уходят: криволинейная перспектива (5 точек и fisheye), Lanczos, сохранение исходника трансформации между сессиями.
- В M4 остаются: radial и array линейки, rounded rect, стрелка, баллон, звезда, lasso fill по всем слоям, surrounding fill.
