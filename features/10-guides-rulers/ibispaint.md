# ibisPaint: rulers (Ruler) and the stabilizer tools

Source: ibispaint.com/lecture №43–48, 50, 51, 89, 90.

## Ruler tool (8 types)
| Ruler | Controls | Purpose |
|---|---|---|
| **Straight** | angle handle, move handle | straight lines, speed lines |
| **Circular** | position, size | precise circles |
| **Elliptical** | width, position, height, angle (4 handles) | ellipses, balloons |
| **Radial** | center (several, "Add Radial Ruler", selected with an arrow) | rays from a point, vanishing points |
| **Mirror** | Phase (angle), position | mirror symmetry (= IP Symmetry) |
| **Kaleidoscope** | **Division** (number of sectors), **Phase** (angle) | lace (≈ IP Kaleidoscopic/Radial) |
| **Array** | Division X/Y (up to 10×10), a frame in perspective (drag the corners), **Lock** → an orange work-cell | you draw in one cell → it is copied into all of them with perspective |
| **Perspective Array** | Division X/Y/Z (starts at 3), **Phase**, Face Selection (Right/Front…), Vertex/Face thumbs (size, position, orientation of the cube), Lock | 1/2/3-point perspective from a cubic lattice, the drawing in the work-cell is replicated (windows, fences) |
- Rulers are combined: Perspective Array + Straight + Radial (centers at the VP).
- The brush options "Bind Center on Elliptical Ruler" and "Billboard Display on Array Ruler" (see 04).

## The stabilizer group (Drawing tools in the Stabilizer window)
- Stabilizer: Real Time / After, Fade (see 04).
- **Straight Line**: drawing a straight line without a ruler.
- The other Drawing tools (Rectangle, Circle, Ellipse, Regular Polygon, Bézier, Polyline) are in 11.

## Mapping to IP
| ibis | IP analogue | What to do |
|---|---|---|
| Straight Ruler | Lines guide | IP is richer (parallel + perpendicular + center). Add an "along the axis only" mode |
| Circular/Elliptical | Ellipse guide | IP is richer (arcs, radii, concentric) |
| Radial Ruler | none (partly Perspective 1pt) | **add** a Radial guide (rays from a point, several centers) |
| Mirror | Symmetry V/H | exists |
| Kaleidoscope | Kaleidoscopic symmetry | exists |
| Array Ruler | none (Quilt pattern post factum) | **add** an Array guide (live replication in a grid + perspective) |
| Perspective Array | none (Grids + Stamp) | **add** it |
| Stabilizer Real Time/After | Smoothness + Lazy guide | **add** an After mode and Fast Strokes |
| Force Fade | Size/Flow profile | IP has it in a richer form, add a quick toggle |
