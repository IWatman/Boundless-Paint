# M2 tool specs: guides, shapes, symmetry, perspective

Sources: features/10–14, spec/UI-SHELL.md, spec/FEATURE-MAP.md §10–13, spec/GAPS.md "Shapes and guides". Names and visuals are our own (00-clean-room/PROCESS.md).
Units: coordinates and geometry sizes in document px. Gesture thresholds in **screen px** (dp × density), converted into document units by dividing by `ViewTransform.zoom`.

## G0. M2 scope and reserves
| Group | M2 | Later (schema id reserve) |
|---|---|---|
| Guides | Lines, Ellipse, Lazy, Pen, Hatching, Grid overlay (display only) | `radial`, `array`, `perspectiveArray` (M4) |
| Shapes | Line, Rect, Ellipse/Circle, Regular polygon 3–16, Arc, Path (curve and polyline) + Stamp, Fill/Outline, detection | `roundedRect`, `arrow`, `balloon`, `star` (M4), shapes in perspective planes (M4) |
| Symmetry | Mirror V / H / VH, Radial 2–32 (+fan), Kaleido 2–16, crop | `array` (M4) |
| Perspective | 1pt, 2pt, 3pt, Isometric, 2D grid | `curvilinear` (5pt, fisheye) → M3 |
- **Decision:** regular polygon moves from M4 to M2: it is needed as a detection result. Star stays in M4.
- **Decision:** curvilinear in M3. It needs projection onto arcs and its own grid, and in M2 there is no second consumer for it.

## G1. Constraint pipeline architecture
### 1.1 Stage order
```
InputPoint (GestureRouter)
 → Stabilizer (EMA, exists)            ┐
 → LazyRopeStage (if Lazy is active)   │ PointStage chain
 → SnapStage (Lines/Ellipse/Pen/Persp) │ (point / lift)
 → HatchingStage (if active)           ┘
 → StrokeResampler (CATMULL_ROM | LINEAR, closed)
 → dab generator (M2 brush engine: jitter, scatter, angle)
 → SymmetryFanOut (N affine copies of each dab)
 → DabRenderer → stroke buffer → compositor (opacity cap, alpha lock, selection mask, clip)
```
- **Decision:** symmetry works as a replication of **dabs**, not of points. All copies get the same spacing and random values (scatter, jitter): that is exactly what is expected from a mirror. One resampler for all copies, one buffer, one TilePatch. In spec/brush-engine.md §1 symmetry must be moved from the constraint stage to after the dab generator.
- State that depends on the canvas (the smudge/mix reservoir of Blend brushes) is stored **per copy**: an array of size N in the dab generator.
- Lazy comes before snap: the rope smooths the hand, snap straightens the result. EMA and Lazy add up (decision: this is also how IP Smoothness is separate from Lazy).
- Shapes skip Stabilizer, Lazy, Snap and Hatching. They go through FanOut, so symmetry applies to them.
- Constraints work in all brush modes (Paint, Erase, Blend) and for the selection brush (selection brush, M2).

### 1.2 API (core, pure JVM)
```kotlin
package app.painter.core.geom
data class Vec2(val x: Float, val y: Float)
/** x' = a·x + c·y + tx; y' = b·x + d·y + ty. det < 0 = mirrored. */
data class Affine2(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    fun map(x: Float, y: Float, out: FloatArray); fun then(o: Affine2): Affine2; fun inverse(): Affine2
    val det: Float; val rotationRad: Float
    companion object { val ID: Affine2; fun rotation(c: Vec2, rad: Float): Affine2; fun reflection(c: Vec2, axisRad: Float): Affine2 }
}

package app.painter.core.stroke
/** Stage output. lift() = pen up: the current sub-stroke ends, the next point() starts a new one. */
interface PointSink { fun point(p: InputPoint); fun lift() }
interface PointStage {
    fun begin(p: InputPoint, out: PointSink)
    fun add(p: InputPoint, out: PointSink)
    fun end(out: PointSink)
    fun cancel() {}
}
enum class Interpolation { CATMULL_ROM, LINEAR }

/** Immutable settings of a single stroke. The defaults = M1 behavior. */
data class StrokeOptions(
    val stabilize: Boolean = true,
    val stages: List<PointStage> = emptyList(),   // after Stabilizer
    val interpolation: Interpolation = Interpolation.CATMULL_ROM,
    val closed: Boolean = false,                  // closed shape: no taper, no double dab at the junction
    val copies: SymmetryCopies = SymmetryCopies.NONE,
    val fill: FillSpec? = null,                   // shape fill (G3.4)
)
class StrokeEngine(doc, layerId, params, color, mode, options: StrokeOptions = StrokeOptions())

package app.painter.core.guide
/** A track that the stroke snaps to. s = the arc-length parameter (for Pen and Hatching). */
interface SnapTrack {
    fun tangentAt(p0: Vec2): Vec2                       // unit
    fun project(x: Float, y: Float, hintS: Float, out: Projection)  // out.x, out.y, out.s
}
/** A source of track families (a guide or a grid). */
interface SnapGuide {
    /** Candidates for a stroke started at p0. An empty list = the guide does not participate (magnet outside the radius). */
    fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>)
}
data class SnapContext(val screenToDoc: Float /* = 1/zoom */, val brushSize: Float)
class SnapStage(guides: List<SnapGuide>, ctx: SnapContext) : PointStage
class LazyRopeStage(radiusDoc: Float, elastic: Float) : PointStage
class HatchingStage : PointStage   // reads the track direction from SnapStage, if present

package app.painter.core.symmetry
class SymmetryCopies(val transforms: List<Affine2>, val domain: ((Float, Float) -> Boolean)?) {
    companion object { val NONE: SymmetryCopies }
}
object SymmetryMath { fun copies(cfg: SymmetryConfig, strokeStart: Vec2): SymmetryCopies }
```
- **Decision:** `InputPoint` **does not change**. `TilePatch` does not change. `StrokeEngine` gets one new parameter with a default, so the old calls and the 18 stroke tests remain valid.
- An adapter `PointSink` sits before `StrokeResampler`: `lift()` calls `resampler.end()`, the next `point()` calls `resampler.begin()`. Taper is computed per sub-stroke.
- In M2, `DabSink` is extended to the struct `Dab(x, y, size, flow, pressure, tilt, angle, flipX)` (the brush engine does this). FanOut per copy: position = `T·(x, y)`, `angle' = angle + rot(T)`. For det < 0: `angle' = 2·axis − angle`, `flipX = !flipX`.
- SnapStage and Lazy are recreated for every stroke from `GuideState` (an immutable snapshot), so editing the handles during a stroke is impossible and there are no races.

### 1.3 SnapStage: track selection and capture
1. `begin(p0)`: collect the tracks of all active SnapGuides. If empty, the points pass through unchanged.
2. Points **accumulate without output** while the offset from p0 < `lockDist` = 12 screen px.
3. Capture: `u = normalize(p_lock − p0)`. Pick the track with the minimal `acos(|u · tangentAt(p0)|)`. On a tie (±1°) the priority: Pen > Ellipse > Lines > Perspective.
4. The accumulated points are projected and output. After that every point is projected: `project(p, hintS = s_prev)`.
5. The stroke ended before capture (a dot or a short tap): the points are output as they are.
6. Overlay: before capture all candidates are faintly visible at the cursor, the selected track is highlighted and extended to the screen edges.
- **Decision:** if Snap is on, the stroke always snaps (like in IP), there is no angle threshold. There is no snapping to the grid lines themselves: snapping works along the direction only.

### 1.4 Selection, layers, undo
- The selection mask is applied only in the compositor: to the preview and to the final composite, to all symmetry copies and to the shape fill. The selection does not affect snap.
- A locked layer: the stroke and the shape are inert, guide handles still work.
- Undo: a stroke with N copies = one `TilePatchCommand`. A committed shape = one patch. Stamp = a separate patch per copy.
- **Decision:** edits of guides, symmetry and grids do **not** go into History. They mark the document dirty (autosave), Tool settings has Reset. IP behaves the same way: this is view state, not pixels.

### 1.5 Serialization (`manifest.json → guides`)
An optional block. If it is absent, the defaults are used. Unknown fields are ignored, the project schema stays 1. The key `ruler` from document-model.md is replaced by the keys `lines`/`ellipse`/`pen`. In core `Document` gets `var guides: GuideState = GuideState()` (an immutable data class, edited via `copy`).
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
- The config of a disabled guide is kept: when the guide is re-opened it returns to its previous position. `lazy.radius` is in screen px, everything else in document px, angles in degrees.

## G2. Guides
General:
- Opened from Creative Tools → Guides. Each guide adds a mini-toolbar to the stack at the top right (the first icon = close).
- Guides are compatible with each other and with symmetry. Several snap guides give a common pool of tracks (§1.3).
- Handles are hidden with Lock. With an active snap guide, shape detection is off (decision).

### 2.1 Lines (ruler)
- Model: the segment a–b defines the axis. Default: a horizontal line through the center of the visible area, length 50% of its width.
- Handles: `a`, `b` (drag = angle and length, angle snap to 0/45/90° ±3°); `move` (the middle of the segment = center tick, drag = translate).
- Tracks:
  - parallel = a straight line through p0 along the axis;
  - perpendicular = a straight line through p0 across the axis (toggle, on by default). Off = "along the axis only".
- **Magnet** (toggle, off by default):
  - a stroke started within 32 screen px of the axis lies on the axis itself, parallels and perpendiculars are not given;
  - a stroke farther than 32 px is free.
- **Side:** determined by the sign of the distance from p0 to the axis. With `edge` = on the track shifts by `brushSize/2` toward p0 (the brush is guided along the edge, like along a ruler). With `edge` = off the track goes along the center of the axis (default).
- Projection: orthogonal onto the line, `s` = the coordinate along the line.

### 2.2 Ellipse
- Model: `c`, `rx`, `ratio = ry/rx ∈ [0.02, 1]`, `angle`. Default: a circle, rx = 25% of the short side of the visible area.
- Handles:
  - center: drag = translate; when moving closer than 8 screen px to the minor axis line the translation sticks to it (cylinders);
  - angle: at the end of the major axis, snap to 0/45/90° ±3°;
  - flatness: on the minor axis, at the limit = a circle;
  - scale: the corner of the bounding rectangle, changes rx while keeping the ratio.
- Let `E` map the document into unit space: shift by −c, rotate by −angle, scale y by 1/ratio.
- Tracks:
  - concentric = an ellipse with the same center, ratio and angle through p0. `r0 = |E(p0)|`, `project(p) = E⁻¹(r0 · normalize(E(p)))`;
  - radial = a straight line through `c` and p0.
- An arc or a radius is chosen by the initial direction (§1.3).
- Magnet and edge as with Lines (32 px from the contour). The track = the ellipse itself, the edge shift = `±brushSize/2` along r.

### 2.3 Pen guide (path)
- Construction:
  - tap = a cusp node;
  - drag > 8 screen px when placing = an `aligned` node with tangents along the drag;
  - a tap on a segment = insert a node;
  - a tap on a node = cusp ↔ smooth (smooth = automatic Catmull-Rom tangents, tension 0.5, GAPS);
  - dragging a node onto a neighbor (< 16 px) = delete the node.
- Mini-toolbar: Close/Open, Lock, ✓ (delete the path and start a new one).
- **Decision:** Lock is reversible. Close and Lock snap: a tap on the first node = Close.
- While the path is not locked, touches build the path instead of drawing. After Lock any stroke with any brush follows the path. Changing the brush is allowed.
- Track: the path flattened into a polyline with a 0.25 px tolerance, with an arc-length table.
  - `project`: the nearest point within the window `s_prev ± (2·|Δp| + 8 px)`. Without a hint the search is global.
  - On a closed path the window wraps around.
  - The window prevents jumping onto a neighboring loop.

### 2.4 Lazy (rope)
- The brush tip `q` is dragged behind the pen `p`: if `|p − q| > R`, then `q = p − R · normalize(p − q)`, otherwise `q` stays. The first point: `q = p`.
- `R = radius · screenToDoc · (1 + elastic · min(v, 4) / 4)`, where v = the pen speed in screen px/ms.
- Settings: radius 0–200 screen px (default 60, 0 = off), elastic 0–100% (default 0).
- On pen-up the tip **does not catch up** with the pen (decision: the essence of a rope).
- Overlay: a circle of radius R around the pen and the line p–q, visible during a stroke and on stylus hover.

### 2.5 Hatching
- A modifier. The stroke direction `u0` is taken from the captured track, without a track = the initial direction of the stroke.
- Only the segments where the projection of the movement onto `u0` (or ds on the track) is positive are drawn. A reversal of more than 2 px gives `lift()`, moving forward starts a new sub-stroke.
- With Lines you get parallel strokes, with Ellipse concentric or radial ones, with Perspective strokes toward the vanishing point.

### 2.6 Grid overlay (View)
- Display only, without snapping (snapping is provided by the 2D grid from perspective).
- Two steps: minor (default 32 px), major every N minor (default 4), origin, 2 colors.
- Minor is not drawn if the step < 6 screen px. The origin handle is visible only in Tool settings → "Change origin".

## G3. Shapes
### 3.1 General
- A shape = a single editable geometric stroke with the **current brush** in the current mode (Paint/Erase/Blend), with the current color and parameters.
- The shape is raster: after commit these are pixels (a vector layer in M4).
- Rendering: geometry → polyline (0.25 px tolerance) → `StrokeEngine` with `stabilize=false`, `Interpolation.LINEAR`, `closed` for closed ones. Pressure = 1 for all points.
- Taper: only for open shapes. Closed ones have no taper, the tail dab is removed if it is closer than 0.5·step to the first one.
- Preview while editing: `cancel()` of the old engine and a new `StrokeEngine` on every change, at most once per frame.

| Shape | Creation (drag) | Handles | Constrain (Touch-Alt / ⊥ toggle) |
|---|---|---|---|
| Line | start → end | 2 ends, move (middle) | angle in 15° steps |
| Rect | corner → corner | 4 corners (the opposite one stays fixed), 4 sides (stretch), move (center), rotate (above the top side) | square; rotation in 15° steps |
| Ellipse | center → radius (circle) | rx, ry (on the axes), 4 corners of the frame, move, rotate | circle; rotation in 15° steps |
| Polygon | center → vertex (radius + rotation) | vertex (radius + angle), move; vertex count 3–16 (default 5) in Tool settings | rotation in 15° steps |
| Arc | start → end, the middle at a 25% bulge of the chord | start, end, mid (curvature; on the chord = a straight line) | mid exactly on the perpendicular bisector |
| Path | as the Pen guide (2.3), Close, ✓ | nodes, tangents of aligned nodes | tangents in 15° steps |
- A polyline = a Path of cusp nodes only (there is no separate tool, decision).
- A Line does not snap to perspective. Shape handles snap to 2D grid intersections (12 screen px). Shapes have no other snaps in M2.

### 3.2 Life cycle
- Draft (handles visible) → commit. The commit is triggered by:
  - a tap outside the shape and the handles: in the Shape tool a tap starts a new shape, in the Paint tool it starts a regular stroke;
  - ✓ in the mini-toolbar;
  - a change of tool, layer or brush;
  - opening a workspace or Home.
- Hit-test (in order): a handle (24 screen px) → the contour (16 px, drag = translate) → outside the shape.
- **Stamp:** commits a copy (its own patch) and keeps the draft for editing. In the draft size, opacity, color and brush can be changed.
- The undo gesture on a draft deletes the draft without committing. There is no local handle history (decision).
- 2 fingers on a draft = navigation, the draft is preserved.

### 3.3 Outline / Fill
- Toggles in the mini-toolbar: **Outline** (a brush outline, on by default) and **Fill** (off by default, closed shapes only). At least one is on.
- Fill:
  - color = the current one (a separate color in M4), `alpha = color.a`, the shared opacity cap of the buffer;
  - the fill is drawn into the same stroke buffer before the outline: one patch, one preview;
  - in Erase mode the fill erases.
- Rasterization (core, CPU): even-odd over the polyline (GAPS), 4×4 supersampling per pixel, only the tiles crossing the bbox. On the GPU in M2 it can stay on the CPU together with the dabs if we stay within the budget.

### 3.4 Detection (Shape detection)
- Works in Paint, Erase and Blend without an active snap guide. In Settings → Shapes: On (default), hold time 0.1–5 s (default 0.6 s), priority of each figure 0.5–2 (default 1).
- **Hold:** `GestureRouter` starts a timer during the stroke. A displacement > 6 screen px from the anchor point moves the anchor and restarts the timer. When the timer fires → `onStrokeHold()`.
- After `onStrokeHold`:
  - the stroke engine does `cancel()`, a dashed candidate and alternative icons are shown (up to 4, a tap = switch);
  - continuing the drag without lifting: for Line and Polyline the last node moves; for Ellipse, Rect and Polygon the size (the ratio of distances to the center) and the angle (the angle difference);
  - lifting → a draft with handles (3.2).

**Algorithm** (`ShapeDetector.detect(points, opts): List<Candidate>`, points after Stabilizer):
1. L = length, D = the bbox diagonal. If L < 24 screen px → `[]`. Resampling by arc length with step `S = D/40`, from 32 to 256 points.
2. `closed = |p_end − p_0| < 0.2·D`.
3. Corners: ShortStraw (Wolin et al., 2008, public paper).
   - `straw_i = |p_{i−3} p_{i+3}|`, a corner = a local minimum below `0.95·median`.
   - Between neighboring corners the segment is a straight line if chord/path ≥ 0.95. If not, a corner is added at the minimum straw of the middle part.
   - A triple of corners with chord/path ≥ 0.98 over the merged segment is merged.
   - For closed paths the p_end–p_0 junction is checked in a wrap-around way.
4. Candidates and the error `e` = RMS distance from the samples to the figure / D:

| Candidate | Condition | Fit | max e |
|---|---|---|---|
| Line | open, chord/L ≥ 0.9 | TLS (PCA), ends = the projections of p_0 and p_end | 0.025 |
| Arc | open, 0 corners | Kåsa circle (least squares), sweep 20–330°, r < 4·D | 0.03 |
| Ellipse | closed | direct Fitzgibbon fit; ratio ≥ 0.88 → a circle (r = the mean) | 0.035 |
| Triangle | closed, 3 corners | vertices = the corners, then least-squares lines of the sides and their intersections | 0.03 |
| Rect | closed, 4 corners, all angles 90 ± 15° | the angle = the mean of the side directions mod 90°, sizes by projections; < 4° from the axes → no rotation | 0.03 |
| Quad | closed, 4 corners | as Triangle | 0.03 |
| Polygon N | closed, 5–8 corners, CV of the side lengths < 0.2, angles ± 15° from (N−2)·180/N | center = centroid, R = the mean, rotation by the first vertex | 0.03 |
| Polyline | open, ≥ 1 corner | vertices = p_0, the corners, p_end | 0.025 |
| Curve | always | Schneider (cubic Bézier, tolerance 0.01·D), closed → a closed Path | — (fallback) |
5. More than 8 corners on a closed path → only Ellipse and Curve. `score = e / maxE / priority`, take the candidates with e ≤ maxE, sort by score. The first one is shown, the next 4 go into the icons. Curve is always last, except when it is the only one.

## G4. Symmetry
- Types:
  - **Mirror**: axes `v` (default), `h` or `vh`. The axis angle is arbitrary: `v` = 90°, `h` = 0°, `vh` = two orthogonal axes;
  - **Radial**: N 2–32 (default 6), fan 10–360° (default 360);
  - **Kaleido**: N 2–16 (default 6), mirrors.
- Copies (θ = the axis angle, c = the center):
  - Mirror v/h: {I, Refl(c, θ)};
  - vh: {I, Refl(θ), Refl(θ + 90°), Rot(180°)};
  - Radial: `Rot(c, k·step)`, k = 0..N−1, where step = 360/N at fan = 360, otherwise fan/(N−1). The fan sector is oriented from the angle of the stroke start point relative to c (copy 0 = the original);
  - Kaleido: `Rot(k·360/N)` and `Rot(k·360/N) ∘ Refl(θ)`, 2N copies in total.
- No more than 32 copies (decision, dab budget).
- **Crop on axis** (Mirror, Kaleido; off by default): the fundamental domain = the half-plane (or the 180/N wedge) containing the stroke start point. A dab whose center is outside the domain is skipped in **all** copies. The stroke breaks off at the axis without per-pixel clipping.
- Handles:
  - center (move);
  - rotate (on the axis, 120 screen px from the center, snap 0/45/90° ±3°);
  - for Radial with fan < 360, a sector edge handle.
- Lock (mini-toolbar) hides the handles.
- Overlay: all axes to the screen edges (1 dp line, accent 60%), for Radial rays, for fan a 10% shading outside the sector.
- No separate stroke preview is made: the copies are already in the stroke buffer.
- Symmetry applies to strokes, guides (track copies as dab copies) and shapes. Liquify in M3.

## G5. Perspective grids
| Type | Model (default) | Track families | Handles |
|---|---|---|---|
| 1pt | VP1 = canvas center, horizon = 0° | to VP1; ∥ horizon; ⊥ horizon | VP1 (move, also outside the canvas), horizon rotation |
| 2pt | VP1 = (−0.25W, 0.45H), VP2 = (1.25W, 0.45H) | to VP1; to VP2; ⊥ the VP1–VP2 line | VP1, VP2 |
| 3pt | as 2pt + VP3 = (0.5W, 2H) | to VP1, VP2, VP3 | VP1–3 |
| Iso | spacing 64, angle 0 | 30°, 150°, 90° (+angle) | origin, spacing, rotate |
| 2D grid | spacing 64, ratio 1, angle 0, origin (0,0) | ∥ the grid axes (2) | origin, cell angle (spacing/ratio), rotate |
- The "to VP" track = a straight line through p0 and the VP. If |p0 − VP| < 4 px, the family is skipped. A parallel track = a straight line through p0.
- Track selection by the initial direction (§1.3). Free strokes do not snap to the grid lines themselves.
- Mini-toolbar: Close, Snap (on by default), Visibility (on by default). A hidden grid keeps snapping. Tool settings: Lock, Reset.
- One grid is active. Choosing another type replaces the current one (its config is kept in the manifest).
- Overlay:
  - 24 rays to a VP across the visible area;
  - 2pt verticals every 64 px;
  - colors VP1 `#FF7A59`, VP2 `#3DDC97`, VP3 `#4F8CFF`, horizon `#FFD166`, all at 50%;
  - 2D and Iso draw only visible lines, at a step < 6 screen px they are thinned out ×2.

## G6. UI (IP structure, our own visuals)
| Where | What |
|---|---|
| Top bar → Creative Tools | Guides: Lines · Ellipse · Hatching · Lazy · Pen. Shapes: Line · Rect · Ellipse · Polygon · Arc · Path. Symmetry: Mirror · Radial · Kaleido. Perspective: 1pt · 2pt · 3pt · Iso · 2D grid. View: Grid overlay. The M4 items (Radial guide, Array, Perspective array, Rounded rect, Arrow, Balloon) are hidden until M4 |
| Mini-toolbars (stack at the top right) | Lines: Magnet, ⊥. Ellipse: Magnet. Pen: Close/Open, Lock, ✓. Lazy: —. Hatching: —. Symmetry: Lock, Crop. Perspective: Snap, Visibility. Shape: Outline, Fill, Stamp, ⊥ (constrain), ✓ |
| ⚙ Tool settings | sections of the active ones: Lines (edge), Ellipse (edge), Lazy (radius, elastic), Symmetry (type, N, fan, Reset), Perspective (Lock, Reset), Polygon (corners), Grid overlay (steps, colors, origin) |
| Settings → Shapes | Detection on, hold time, priorities, Reset |
- Handles (our own style):
  - a 16 dp circle, fill `#F5F7FA`, a 2 dp ring `#1B1F27`; the active one = accent ring `#4F8CFF`;
  - move = a cross-arrow, rotate = a circle with an arc arrow on a 40 dp stem;
  - hit zone 24 dp; the on-screen size is constant at any zoom.
- Guide lines 1 dp, accent 80%, infinite continuations dashed. The selected track 1.5 dp `#FF7A59`.
- The overlay is drawn by a Compose `Canvas` above `CanvasView` in screen coordinates via `ViewTransform.docToScreen` (decision: no GL pass needed).
- Input (app/render):
  - on `ACTION_DOWN` `GestureRouter` first asks `Host.overlay?.hitTest(x, y)`; a hit = a handle drag, not a stroke;
  - a second finger during a handle drag rolls the drag back and enables navigation;
  - Touch-Alt: a finger that has been resting motionless for ≥ 150 ms before the stylus touch = the `constrain` modifier. This is not a stroke cancel: the cancel rule concerns only a second finger after the stroke has started.

## G7. Contract changes (for the coordinator)
- `core/stroke`:
  - `StrokeOptions`, `PointStage`, `PointSink`, `Interpolation`;
  - `StrokeResampler` gets a LINEAR mode and `end(closed)`;
  - `StrokeEngine` gets a 6th parameter with a default;
  - `InputPoint` unchanged.
- New packages: `core/geom`, `core/guide` (Lines/Ellipse/Pen/Perspective SnapGuide, SnapStage, LazyRopeStage, HatchingStage, GuideState), `core/symmetry`, `core/shape` (Shape, ShapeHandles, ShapeOutline, PolygonFill, ShapeDetector, HoldDetector).
- `core/doc/Document`: `var guides: GuideState`. `core/io/ProjectIO`: writes and reads `manifest.guides`.
- `app/render`:
  - `CanvasInputListener.onStrokeHold()` (a default method);
  - `GestureRouter.Host.overlay: OverlayHitTester?`;
  - the `constrainHeld` flag in the stroke events.
- Docs: brush-engine.md §1 (FanOut after the dab generator), document-model.md (`guides`), file-formats.md (`guides`), ROADMAP (polygon in M2, curvilinear in M3).

## G8. Work order
1. **G-a** geom + pipeline refactor (PointStage, lift, LINEAR, closed, FanOut) without a behavior change: the M1 tests are green.
2. **G-b** Symmetry + overlay/handles (hit-test skeleton, Compose overlay, mini-toolbar).
3. **G-c** Lines, Ellipse, Lazy, Hatching + GuideState in the manifest.
4. **G-d** Perspective 1/2/3pt, Iso, 2D grid; Grid overlay.
5. **G-e** Shape tool: all 6 shapes, handles, Outline/Fill, Stamp, constrain.
6. **G-f** Pen guide (reuses Path from G-e) + detection + hold.
- FanOut and `Dab` (angle/flip) must be agreed with the move of dabs to the GPU (M2 brush engine): fan-out stays in core, the GPU receives a ready list of dabs.

## G9. Acceptance
- Lines: 20 parallel and 20 perpendicular strokes with a shaky hand. All dab centers lie on their own line within 0.01 px.
- Ellipse: concentric arcs do not intersect. Radial strokes pass through the center (≤ 0.5 px).
- Pen: an S path with close loops, a stroke along the path does not jump. A closed path is traversed in a wrap-around way.
- Lazy R = 60: the tip lags behind by exactly R, it does not catch up on pen-up.
- Symmetry: Kaleido N = 8 (16 copies) at 60 fps on 4096², one undo removes all copies. Crop breaks the stroke off at the axis.
- Perspective: in 2pt the verticals are strictly ⊥ to the horizon, the rays converge at the VP within ≤ 0.5 px. A VP outside the canvas works.
- Shapes: Rect corners are sharp (no spline rounding), the junction of a closed shape has no dark dot at flow 30%. Fill + Outline = one undo.
- Detection: 10 reference freehand figures (with a finger and a stylus) are recognized ≥ 9 out of 10 times. A 0.6 s hold fires and does not interfere with slow strokes.
- Persistence: guides survive save/load and force-stop. An M1 project without `guides` opens.
- Not a single paid feature, ad or watermark (no-monetization).

## G10. JVM tests
| Package | Tests |
|---|---|
| geom | Affine2: compose/inverse, det of a reflection = −1, map rotation/reflection |
| stroke | The M1 tests unchanged. LINEAR: the points of a square → dab centers on the edges ±0.01. closed: no extra dab at the junction. lift: 3 sub-strokes = 3 taper tails. Options at the defaults = a byte-for-byte M1 buffer |
| guide/SnapStage | capture after 12 px; hold-back and reset of the accumulated points; a dot before capture unchanged; choosing parallel/perp at angles 10° and 80°; priority on a tie |
| guide/Lines | magnet: inside/outside 32 px; sign of the side; edge shift = size/2 |
| guide/Ellipse | `|E(project(p))| = r0`; radial through the center; ratio 1 = a circle |
| guide/Pen | continuity of s on an S-curve; wrap on a closed one; global search without a hint |
| guide/Lazy | `|p − q| ≤ R`; R = 0 = identity; elastic increases R |
| guide/Hatching | a zigzag of 5 passes → 3 sub-strokes forward; hysteresis 2 px |
| guide/Perspective | 1pt track through the VP; 2pt vertical ⊥ VP1–VP2; 3pt vertical through VP3; iso 3 directions; grid2d 2 directions; p0 ≈ VP skips the family |
| symmetry | copy count (2/4/N/2N), all `|det| = 1`; fan steps; crop domain; integration with the engine: the Mirror v buffer coincides with its own reflection (≤ 1/255); one TilePatch; undo restores |
| shape | ellipse tessellation ≤ 0.25 px; Rect handles (the opposite corner stays fixed), constrain square/15°; Polygon N; Arc through 3 points; even-odd star: the center is empty; AA circle area ±1% |
| shape/ShapeDetector | synthetic data with noise σ = 1%·D (seed), 50 per figure: line, arc, circle, ellipse, triangle, rect (rotated 30°), quad, pentagon, polyline (3 segments), S-curve, accuracy ≥ 95%; priority changes the winner; a short stroke → `[]`; > 8 corners → ellipse |
| shape/HoldDetector | motionless for 600 ms → fires; jitter < 6 px does not reset; movement resets |
| io | GuideState roundtrip; a manifest without guides → the defaults; unknown fields are ignored |
