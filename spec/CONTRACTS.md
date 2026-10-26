# Контракты между модулями (M1)

Готовые общие типы (не менять без согласования):
- `core/raster`: `Half`, `Tile` (256², FloatArray RGBA premult), `MaskTile`, `TileKey`, `TileGrid`.
- `core/color/Rgba` (straight sRGB 0..1).
- `core/blend/BlendMode` (33 режима, `id`, `shaderIndex`).
- `core/doc`: `Node`, `RasterLayer`, `GroupNode`, `Paper`, `Document`.
- `core/stroke/InputPoint`.

## Пакеты и владельцы
| Пакет | Содержимое | Публичный API, на который опираются другие |
|---|---|---|
| `core/blend` | CPU-формулы всех режимов, композитор | `object BlendMath { fun blendPixel(mode, dst: FloatArray, dOff, src: FloatArray, sOff, opacity) }` premult in/out; `object Compositor { fun compositeInto(dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float, clipAlpha: TileGrid? = null); fun flatten(doc: Document, includePaper: Boolean): TileGrid }` |
| `core/color` | HSB/RGB/LAB/hex | `data class Hsb(h 0..360, s 0..1, v 0..1)`, `ColorConv.toHsb(Rgba)`, `ColorConv.fromHsb(Hsb, a)`, `ColorConv.toHex(Rgba, withAlpha)`, `ColorConv.parseHex(String): Rgba?`, `ColorConv.toLab/fromLab` |
| `core/history` | команды и undo | `interface Command { val name: String; fun apply(doc: Document); fun revert(doc: Document); val byteSize: Long }`; `class History(maxSteps = 100, maxBytes = 512 MB) { fun execute(doc, cmd); fun push(cmd) /*уже применённая*/; fun undo(doc): Boolean; fun redo(doc): Boolean; val canUndo; val canRedo; var onChanged: (() -> Unit)? }`; `class TilePatchCommand(patch: TilePatch)`; команды слоёв в `core/history/LayerCommands.kt`. MergeDown принимает `blender: (dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float) -> Unit`, чтобы не зависеть от `core/blend` |
| `core/stroke` | пайплайн мазка | `data class BrushParams`, `enum StrokeMode { PAINT, ERASE }`, `class StrokeEngine(doc: Document, layerId: String, params: BrushParams, color: Rgba, mode: StrokeMode) { fun begin(p: InputPoint); fun add(points: List<InputPoint>); fun end(): TilePatch?; fun cancel(); val buffer: TileGrid /*stroke buffer, premult*/; fun takeDirty(): Set<TileKey>; val previewOpacity: Float }`; `class TilePatch(val layerId: String, val before: Map<TileKey, Tile?>, val after: Map<TileKey, Tile?>)` (в `core/raster/TilePatch.kt`, общий тип). `end()` сам применяет мазок к слою и возвращает патч |
| `core/io` | проектные файлы | `object ProjectIO { fun save(doc: Document, dir: File, thumbnailPng: ByteArray?); fun load(dir: File): Document }`; `class ProjectStore(rootDir: File) { fun list(): List<ProjectInfo>; fun dirFor(id): File; fun delete(id); fun duplicate(id): String; fun rename(id, name) }`; `data class ProjectInfo(id, name, width, height, modified, thumbFile: File?)` |
| `app/render` | GL-холст | `class CanvasView(ctx) : GLSurfaceView`: `fun setDocument(doc)`, `fun invalidate(layerId: String?, keys: Collection<TileKey>?)` (null = всё), `fun setStrokePreview(layerId: String, buffer: TileGrid?, erase: Boolean, opacity: Float)`, `fun invalidateStroke(keys)`, `var inputListener: CanvasInputListener?`, `fun fitToScreen()`, `val viewState: StateFlow<ViewState>(zoom, rotation)` |
| `app/render` | ввод | `interface CanvasInputListener { fun onStrokeBegin(p: InputPoint); fun onStrokeMove(points: List<InputPoint>); fun onStrokeEnd(); fun onStrokeCancel(); fun onUndoGesture(); fun onRedoGesture(); fun onLongPress(docX: Float, docY: Float) }` (координаты уже в пикселях документа) |
| `app/ui` | Compose-экраны | Чистый UI без зависимостей от `core/history`, `core/stroke`, `app/render`: `data class StudioUiState(...)`, `interface StudioActions`, `@Composable fun StudioScreen(state, actions, canvas: @Composable () -> Unit)`, `HomeScreen`, `GalleryScreen`, `NewCanvasScreen`, тема. `StudioViewModel` и связку пишет координатор |

## Правила для параллельной работы
- Каждый пишет только в свой пакет + тесты в `core/src/test/kotlin/<тот же пакет>`.
- Общие типы выше не меняются. Если нужно, оставь пометку в отчёте.
- Сборка: `. .\tools\env.ps1` затем `.\gradlew.bat :core:test --tests "app.painter.core.<pkg>.*" --console=plain`. App-модуль: `.\gradlew.bat :app:compileDebugKotlin`. Gradle может ждать lock, это нормально.
- Стиль: Kotlin official, KDoc на публичных API, без лишних комментариев.
- M1-отступление от плана: штампы рисуются на CPU (`core/stroke`), на GPU только композит. GPU-штампы в M2.

## M2
Сводный контракт мазка, общие типы и владельцы пакетов по фазам: [PLAN-M2.md](PLAN-M2.md) §2–§4. Правила параллельной работы те же.
