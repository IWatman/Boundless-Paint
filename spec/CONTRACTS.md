# Contracts between modules (M1)

Ready-made shared types (do not change without agreement):
- `core/raster`: `Half`, `Tile` (256², FloatArray RGBA premult), `MaskTile`, `TileKey`, `TileGrid`.
- `core/color/Rgba` (straight sRGB 0..1).
- `core/blend/BlendMode` (33 modes, `id`, `shaderIndex`).
- `core/doc`: `Node`, `RasterLayer`, `GroupNode`, `Paper`, `Document`.
- `core/stroke/InputPoint`.

## Packages and owners
| Package | Contents | Public API that others rely on |
|---|---|---|
| `core/blend` | CPU formulas of all modes, compositor | `object BlendMath { fun blendPixel(mode, dst: FloatArray, dOff, src: FloatArray, sOff, opacity) }` premult in/out; `object Compositor { fun compositeInto(dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float, clipAlpha: TileGrid? = null); fun flatten(doc: Document, includePaper: Boolean): TileGrid }` |
| `core/color` | HSB/RGB/LAB/hex | `data class Hsb(h 0..360, s 0..1, v 0..1)`, `ColorConv.toHsb(Rgba)`, `ColorConv.fromHsb(Hsb, a)`, `ColorConv.toHex(Rgba, withAlpha)`, `ColorConv.parseHex(String): Rgba?`, `ColorConv.toLab/fromLab` |
| `core/history` | commands and undo | `interface Command { val name: String; fun apply(doc: Document); fun revert(doc: Document); val byteSize: Long }`; `class History(maxSteps = 100, maxBytes = 512 MB) { fun execute(doc, cmd); fun push(cmd) /*already applied*/; fun undo(doc): Boolean; fun redo(doc): Boolean; val canUndo; val canRedo; var onChanged: (() -> Unit)? }`; `class TilePatchCommand(patch: TilePatch)`; layer commands in `core/history/LayerCommands.kt`. MergeDown takes `blender: (dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float) -> Unit` so that it does not depend on `core/blend` |
| `core/stroke` | stroke pipeline | `data class BrushParams`, `enum StrokeMode { PAINT, ERASE }`, `class StrokeEngine(doc: Document, layerId: String, params: BrushParams, color: Rgba, mode: StrokeMode) { fun begin(p: InputPoint); fun add(points: List<InputPoint>); fun end(): TilePatch?; fun cancel(); val buffer: TileGrid /*stroke buffer, premult*/; fun takeDirty(): Set<TileKey>; val previewOpacity: Float }`; `class TilePatch(val layerId: String, val before: Map<TileKey, Tile?>, val after: Map<TileKey, Tile?>)` (in `core/raster/TilePatch.kt`, a shared type). `end()` applies the stroke to the layer itself and returns the patch |
| `core/io` | project files | `object ProjectIO { fun save(doc: Document, dir: File, thumbnailPng: ByteArray?); fun load(dir: File): Document }`; `class ProjectStore(rootDir: File) { fun list(): List<ProjectInfo>; fun dirFor(id): File; fun delete(id); fun duplicate(id): String; fun rename(id, name) }`; `data class ProjectInfo(id, name, width, height, modified, thumbFile: File?)` |
| `app/render` | GL canvas | `class CanvasView(ctx) : GLSurfaceView`: `fun setDocument(doc)`, `fun invalidate(layerId: String?, keys: Collection<TileKey>?)` (null = everything), `fun setStrokePreview(layerId: String, buffer: TileGrid?, erase: Boolean, opacity: Float)`, `fun invalidateStroke(keys)`, `var inputListener: CanvasInputListener?`, `fun fitToScreen()`, `val viewState: StateFlow<ViewState>(zoom, rotation)` |
| `app/render` | input | `interface CanvasInputListener { fun onStrokeBegin(p: InputPoint); fun onStrokeMove(points: List<InputPoint>); fun onStrokeEnd(); fun onStrokeCancel(); fun onUndoGesture(); fun onRedoGesture(); fun onLongPress(docX: Float, docY: Float) }` (coordinates are already in document pixels) |
| `app/ui` | Compose screens | Pure UI with no dependencies on `core/history`, `core/stroke`, `app/render`: `data class StudioUiState(...)`, `interface StudioActions`, `@Composable fun StudioScreen(state, actions, canvas: @Composable () -> Unit)`, `HomeScreen`, `GalleryScreen`, `NewCanvasScreen`, theme. `StudioViewModel` and the wiring are written by the coordinator |

## Rules for parallel work
- Everyone writes only to their own package + tests in `core/src/test/kotlin/<same package>`.
- The shared types above are not changed. If needed, leave a note in the report.
- Build: `. .\tools\env.ps1` then `.\gradlew.bat :core:test --tests "app.painter.core.<pkg>.*" --console=plain`. App module: `.\gradlew.bat :app:compileDebugKotlin`. Gradle may wait for a lock, that is normal.
- Style: official Kotlin, KDoc on public APIs, no superfluous comments.
- M1 deviation from the plan: dabs are drawn on the CPU (`core/stroke`), the GPU only does compositing. GPU dabs in M2.

## M2
The consolidated stroke contract, shared types and package owners by phase: [PLAN-M2.md](PLAN-M2.md) §2–§4. The parallel work rules are the same.
