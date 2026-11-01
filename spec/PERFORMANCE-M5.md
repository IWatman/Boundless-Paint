# Boundless Paint — M5 performance baseline

- **Date:** 2026-11-01
- **Branch:** `m5-perf` (worktree `C:\Users\dushc\.local\share\opencode\worktree\df57b0\m5-perf`), based on `main` at `ccca990`
- **Scope:** read-only measurement and inspection. No production code changes, no emulator, no tag/push/version change.
- **Related specs:** [tools/m2-brush.md §6](tools/m2-brush.md), [qa/TABLET-CHECKLIST.md §6](qa/TABLET-CHECKLIST.md), [STATUS.md](STATUS.md), [filters.md](filters.md).

This is a **desktop JVM baseline**, not a device certification. Anything that requires a GPU, Android runtime,
Compose, a real stylus or an Android heap is marked **unknown** below. Derived numbers are labelled as arithmetic,
not measurements.

---

## 1. Environment

| Item | Value |
|---|---|
| Machine | ASUS desktop, AMD Ryzen 7 5800X, 8 cores / 16 logical processors, 3.8 GHz base |
| RAM | 32 GB (34,241,642,496 bytes) |
| OS | Windows 11 Pro, 10.0.26300, 64-bit |
| JDK | Microsoft OpenJDK 21.0.9+10-LTS (HotSpot, 64-bit) |
| Gradle | 8.11.1 |
| Kotlin | 2.0.20 |
| Android Gradle Plugin / build | from the worktree at `ccca990` |
| Benchmark heap | Gradle `:core:test` worker default (not overridden) for `StrokeBench` |
| Probe heap | 4 GB, set with a temporary Gradle init script for the supplementary probe only |

The machine was shared with other work during the runs. CPU frequency, background builds and JIT/GC state were
not controlled. The observed run-to-run spread is large (up to about 2× on individual scenarios), so the numbers
are **indicative**, not a JMH-quality microbenchmark.

---

## 2. Methodology

### 2.1 Existing harness: `StrokeBench`

The only brush benchmark in the repository is
[`core/src/test/kotlin/app/painter/core/stroke/StrokeBench.kt`](../core/src/test/kotlin/app/painter/core/stroke/StrokeBench.kt).

- Canvas: `Document.create(1300, 1200)`, one raster layer, 1 or 4 worker threads (`StrokeContext.defaultThreads()`
  is 4 on this 16-thread machine).
- Path: 251 input points from `x = 100` to `x = 1100` at 4 px spacing, a 1000 px line at 2000 px/s.
  Points are fed in chunks of 8 (32 px, one 60 Hz frame at 2000 px/s).
- Timed calls: 32 `StrokeEngine.add(frame)` calls plus one `StrokeEngine.end()` call = 33 samples. **The final
  `end()` is included in the same mean and max**, which matters for the watercolor scenario E.
- Rounds: 3; the first round is warm-up, and the reported result is the best mean of rounds 2–3. Max is the max of
  that same best round, not a percentile.
- Output: console plus `core/build/stroke-bench.txt`.
- The harness never asserts timings. It is also **not tagged** `bench` as the spec suggests, so it is part of a
  normal `:core:test` run.

Command used (from the worktree):

```powershell
. .\tools\env.ps1
.\gradlew.bat :core:test --tests '*StrokeBench*' --rerun-tasks --console=plain
```

`--rerun-tasks` was added so Gradle cannot skip an up-to-date test task. The benchmark was run three times. The
command completed with `BUILD SUCCESSFUL` in 1m 44s, 1m 17s and 1m 55s; each run wrote a fresh
`core/build/stroke-bench.txt`.

### 2.2 Supplementary probe (temporary, not committed)

`StrokeBench` does not split `add()` from `end()`, and it does not exercise M5 filters or the CPU compositor. To get
those numbers, a temporary test was added, run, and then deleted before the commit. It used the same 1000 px path and
`BrushParams`/`StrokeResources` as `StrokeBench` and added:

1. separate timing of `add()` frames and `end()`;
2. scenario C with an actual grain texture (`bitmap head + TextureParams(depth = 0.6)`), because the existing
   scenario C is labelled "texture: S2" but passes no `TextureParams`;
3. `Filters.applyToGrid` and `Filters.apply` for `TwirlParams` at 1920×1080 and 3840×2160;
4. `Compositor.flattenInto` for ten full layers at 1920×1080 and 3840×2160.

The probe ran with 4 GB of test heap and produced two runs. It is **not** a JMH benchmark: it is single-shot per
configuration with only the stroke scenarios retaining the 3-round warm-up. The probe file and init script were
removed; this section records what was run and why its numbers are more variable than the A–E table.

### 2.3 What was not measured

- No emulator or tablet was started; `BlendParityTest` (an `androidTest` GPU↔CPU correctness suite, not a
  benchmark) was not run.
- No GLES timing, no Compose frame timing, no `FrameMetrics`, no input-to-photon latency.
- No Android heap/RSS measurement, no `dumpsys meminfo`, no thermal throttling test.
- No real stylus pressure/tilt path; `StrokeBench` uses synthetic points.

---

## 3. Results

### 3.1 Scenarios A–E, three runs

All values are **ms per timed call** (one `add()` batch of 8 points, or the final `end()`), from
`core/build/stroke-bench.txt`. "Median" is the median of the three run means. "Max" is the range of the per-run
maxima.

| Scenario | Threads | Run 1 | Run 2 | Run 3 | Median | Max (range) | M2 target |
|---|---:|---:|---:|---:|---:|---:|---|
| A — size 20, spacing 10% | 1 | 0.52 | 0.41 | 0.42 | **0.42** | 4.79–9.13 | ≤ 8 ms |
| A — size 20, spacing 10% | 4 | 0.69 | 0.34 | 1.00 | **0.69** | 3.76–10.77 | ≤ 8 ms |
| B — size 300 soft, spacing 5% | 1 | 2.26 | 2.43 | 3.41 | **2.43** | 19.38–44.78 | ≤ 8 ms |
| B — size 300 soft, spacing 5% | 4 | 1.49 | 1.54 | 1.75 | **1.54** | 13.80–14.90 | ≤ 8 ms |
| C — size 500 bitmap, spacing 8% | 1 | 3.66 | 3.66 | 6.51 | **3.66** | 20.08–41.10 | ≤ 8 ms |
| C — size 500 bitmap, spacing 8% | 4 | 1.89 | 1.76 | 2.89 | **1.89** | 18.68–26.18 | ≤ 8 ms |
| D — smudge size 200 | 1 | 1.61 | 1.58 | 2.70 | **1.61** | 2.40–5.86 | ≤ 12 ms |
| D — smudge size 200 | 4 | 1.22 | 1.41 | 2.48 | **1.41** | 2.50–8.27 | ≤ 12 ms |
| E — watercolor size 80 | 1 | 5.50 | 6.51 | 11.34 | **6.51** | 95.14–196.68 | tick ≤ 8 ms |
| E — watercolor size 80 | 4 | 4.72 | 5.80 | 8.23 | **5.80** | 82.06–119.91 | tick ≤ 8 ms |

Important interpretation:

- **A–C means pass the M2 target on this desktop** (all medians below 8 ms). The maxima do not: C reaches
  20–41 ms and B 19–45 ms. The maximum is dominated by the final `end()` composite/commit (and, in some early
  frames, JIT/GC), not the steady-state per-frame cost; the probe below confirms that split.
- **D passes comfortably** against the 12 ms target: medians 1.6/1.4 ms, maxima 2.4–8.3 ms.
- **E's 5.5–11.3 ms mean is not a tick measurement**, because the 33 samples include the pen-up `dry()` loop. The
  probe measured the steady-state watercolor tick at 2.5–4.4 ms mean, and the pen-up phase at 87–167 ms. The
  "tick ≤ 8 ms" target is met on the desktop mean; the pen-up freeze is not.
- Four threads are **not consistently faster**. For small dabs A and sometimes D, 4 threads are slower
  (A median 0.69 vs 0.42 ms; run 3: 1.00 vs 0.42 ms). The per-flush scheduling overhead dominates tiny batches.

### 3.2 Per-phase probe (add vs pen-up)

Temporary probe, two runs, same path. `add mean`/`add max` cover only the 32 `add()` calls (the closest thing to
the M2 "CPU per frame" target); `end` is the single final call. Values are ranges over the two probe runs unless
marked otherwise.

| Scenario | Threads | add mean (ms/frame) | add max (ms) | end (ms) |
|---|---:|---:|---:|---:|
| A — size 20 | 1 | 0.33–0.38 | 0.83–1.38 | 13.07–22.53 |
| B — size 300 soft | 1 | 1.81–2.26 | 4.57–18.14 | 19.14–32.22 |
| C — size 500 bitmap | 1 | 2.78–4.71 | 4.82–18.01 | 20.50–55.61 |
| C2 — size 500 bitmap + grain | 1 | 6.22¹ | 9.67¹ | 27.10¹ |
| D — smudge size 200 | 1 | 2.16–2.70 | 5.95–8.85 | 1.42–2.58 |
| E — watercolor size 80 | 1 | 3.11–3.42 | 9.69–16.77 | 93.04–120.07 |
| A — size 20 | 4 | 0.62–0.78 | 2.06–2.30 | 4.27–7.65 |
| B — size 300 soft | 4 | 1.28–1.42 | 2.19–2.61 | 13.97–16.19 |
| C — size 500 bitmap | 4 | 1.50–1.82 | 2.61–4.17 | 24.56–26.21 |
| C2 — size 500 bitmap + grain | 4 | 1.89¹ | 2.95¹ | 21.16¹ |
| D — smudge size 200 | 4 | 1.29–2.04 | 2.06–4.28 | 1.03–1.82 |
| E — watercolor size 80 | 4 | 2.48–4.42 | 8.80–20.56 | 87.20–167.05 |

¹ C2 was added only to the second probe run, so it has a single sample. It is **not** one of the M2 A–E scenarios;
it exists because the current C omits the texture that its label mentions. With a 256×256 grain texture at depth
0.6, the add mean is 6.22 ms at one thread (versus 2.78–4.71 ms for untextured C in the same probe) and 1.89 ms at
four threads; it stays below 8 ms for this single run, with a 9.67 ms maximum at one thread. This is a warning for
texture-heavy presets, not a new target result.

The `end()` column is one-shot per stroke. It is single-threaded (`StrokeEngine.compositeBuffer`), so the 1-thread
and 4-thread `end` differences are noise/GC, not parallel speed-up. A 1000 px stroke at 1300×1200 touches only a
small number of tiles; a full-canvas or 4K stroke will be much more expensive (see §4).

### 3.3 M5 filter and compositor probe

Temporary probe, two runs. `TwirlParams` is a full-source warp (`needsFullSource = true`). `Filters.apply` builds
the history patch; `Filters.applyToGrid` is the no-history path used by live filter layers. Times include the
one-time full-content snapshot.

| Operation | Canvas | Time (two runs) | History patch | Notes |
|---|---|---:|---:|---|
| `Filters.applyToGrid(Twirl)` | 1920×1080 | 53.6–98.4 ms | — | full-source copy + per-pixel warp |
| `Filters.apply(Twirl)` | 1920×1080 | 56.3–91.5 ms | 40.0 MiB (40 tiles) | before+after snapshots retained |
| `Filters.applyToGrid(Twirl)` | 3840×2160 | 149.4–301.1 ms | — | full-source copy is 126.6 MiB |
| `Filters.apply(Twirl)` | 3840×2160 | 291.3–302.2 ms | 135.0 MiB (135 tiles) | 70% of the 192 MiB history cap |
| `Compositor.flattenInto`, 10 full layers | 1920×1080 | 857.9–1140.0 ms | — | CPU; used by live filter refresh |
| `Compositor.flattenInto`, 10 full layers | 3840×2160 | 2517.0–3815.9 ms | — | stress case; 4K UI limit is at most 6 layers (see §4.8) |

The two runs differ by up to 2× because there is no proper warm-up and the machine was shared. The probe printed
`MB` after dividing by 2^20, so the values here are MiB. Treat these as order-of-magnitude costs. The important
ratios are stable:

- a 4K full-source filter is roughly 3–4× the pixel work of 1080p and costs **0.15–0.30 s on a desktop JVM**;
- its history patch is **135 MiB**, versus 40 MiB at 1080p;
- ten full layers at 1080p cost **about 0.9–1.1 s** in the CPU compositor, and the 4K stress case is
  **2.5–3.8 s**.

### 3.4 Comparison with the M2 numbers

The M2 baseline in [STATUS.md](STATUS.md) (M2 section, 2026-10-31) is:

> A 0.23–0.27 ms/frame, B 0.87–1.69, C 1.08–2.23, D (smudge) 0.97–1.10, E (watercolor) ~8.2 mean / ~68 max.

That was also a 16-core desktop JVM. It was not re-run in this session, and the tree has changed substantially
since M2 (M3 filters, M4 vector/animation, M5 filters and pattern fill), so the comparison is **indicative only**.

| Scenario | M2 (STATUS) | M5 median, 1 thread | M5 median, 4 threads | Signal |
|---|---:|---:|---:|---|
| A | 0.23–0.27 | 0.42 | 0.69 | about 1.6–3.0× slower |
| B | 0.87–1.69 | 2.43 | 1.54 | 1-thread clearly slower; 4-thread at the top of M2 |
| C | 1.08–2.23 | 3.66 | 1.89 | 1-thread slower; 4-thread inside M2 |
| D | 0.97–1.10 | 1.61 | 1.41 | about 1.3–1.7× slower |
| E | ~8.2 mean / ~68 max | 6.51 | 5.80 | mean improved; max worse (95–197 ms in these runs) |

The A/B difference is large enough to deserve a bisect before calling it a regression: this session's machine load
alone produced up to 2× spread, and the M2 rows are the best observed ranges, not a controlled comparison. What
can be said safely:

- the M2 **mean targets are still met** for A–D and for the E tick on the desktop;
- the maximum/spike behavior is worse than the M2 note, especially E's pen-up and C's end-of-stroke;
- there is no evidence that the 4-thread path scales well for small dabs, and some evidence that it hurts.

---

## 4. Hot paths and concrete bottlenecks

Line references are to the working tree at `ccca990`.

### 4.1 Live filter layers are recomputed synchronously on the main thread — highest impact

`StudioViewModel.init` installs `history.onChanged = { … refreshFilterLayers() … }` at
[`app/src/main/kotlin/app/painter/studio/StudioViewModel.kt:396`](../app/src/main/kotlin/app/painter/studio/StudioViewModel.kt).
`refreshFilterLayers` ([line 3405](../app/src/main/kotlin/app/painter/studio/StudioViewModel.kt)) does, for every
visible root `FilterLayer`:

1. `Compositor.flattenInto(doc, below, includePaper = true, dst = filter.pixels)` — a full CPU render of every
   sibling below the filter, over the whole canvas;
2. `Filters.applyToGrid(filter.pixels, filter.params, threads = STROKE_THREADS)` — a full filter pass, including a
   full-source snapshot for the warps.

This runs on the same thread as the history push (the UI thread for strokes and tool actions), and there is no
debounce or cancellation. The measured cost of only the flatten step is **0.86–1.14 s for 10 layers at 1080p** and
**2.5–3.8 s for the 10-layer 4K stress case**, before the filter itself. The filter adds 0.05–0.10 s at 1080p and
0.15–0.30 s at 4K. This is the largest measured risk in this report.

The refresh also runs after *every* history change, not only after a filter parameter change: a normal brush stroke
below a live filter layer triggers it, and every visible root filter is recomputed even when the changed layer is
above it. The `applyToGrid` path avoids history copies, but not the full flatten, the full-source copy or the
per-apply thread-pool creation. After the recompute, `canvas?.invalidate(filter.id, null)` marks the whole filter
cache dirty, so `DocumentSync` copies every changed tile back to half and the GL thread uploads the full layer
([line 3419](../app/src/main/kotlin/app/painter/studio/StudioViewModel.kt)).

### 4.2 Watercolor pen-up blocks on a synchronous dry loop

`StrokeEngine.end` calls:

```kotlin
watercolor?.let { it.dry(); it.write(access) }
```

at [`core/src/main/kotlin/app/painter/core/stroke/StrokeEngine.kt:397`](../core/src/main/kotlin/app/painter/core/stroke/StrokeEngine.kt).
`WetSimulation.dry()` ([`WetSimulation.kt:179`](../core/src/main/kotlin/app/painter/core/stroke/WetSimulation.kt))
runs up to `DRY_LIMIT_MS / TICK_MS = 1200 / 33.3 ≈ 36` ticks, over every wet tile, until dry or the cap. `tick()`
is single-threaded ([line 143](../core/src/main/kotlin/app/painter/core/stroke/WetSimulation.kt)) and `step()`
([line 490](../core/src/main/kotlin/app/painter/core/stroke/WetSimulation.kt)) walks all 128×128 cells, four
neighbours each, with `neighbourTile` doing a tile-map lookup for cross-tile neighbours
([line 580](../core/src/main/kotlin/app/painter/core/stroke/WetSimulation.kt)). The class comment itself records
`TODO(S8b): tick on worker threads`.

Measured effect: the `add()` tick is 3.1–4.4 ms mean (2.5–4.4 ms at 4 threads), but the final `end()` is
**87–167 ms on this desktop JVM**. `StudioViewModel.onStrokeEnd`
([line 640](../app/src/main/kotlin/app/painter/studio/StudioViewModel.kt)) calls `engine.end()` directly on the UI
thread. This is a visible pen-up freeze, and a mobile CPU will not be faster. The M2 target only covered the tick;
the checklist's "pen-up drying does not freeze the UI for long" is the failing part.

`WetSimulation.write()` ([line 201](../core/src/main/kotlin/app/painter/core/stroke/WetSimulation.kt)) is a second
cost inside `end()`: for every dirty tile it walks the pigment bounding box, does four bilinear `readCell` calls and
four `grainAt` lookups per pixel, then writes the tile. The `lastWrite` bookkeeping clears pixels that just dried.

### 4.3 Full-source filters make a full-content float copy and a history copy

`Filters.apply` ([`Filters.kt:47`](../core/src/main/kotlin/app/painter/core/filter/Filters.kt)) computes `content`,
then `snapshotTiles` before and after (lines 80–82) to build the `TilePatch`. `Filters.processFullSource`
([line 143](../core/src/main/kotlin/app/painter/core/filter/Filters.kt)) calls `readRect` on the entire content:
`readRect` allocates `FloatArray(w * h * 4)` and copies tile by tile
([line 317](../core/src/main/kotlin/app/painter/core/filter/Filters.kt)). At 3840×2160 that is **126.6 MiB** for the
source alone, plus 67.5 MiB before + 67.5 MiB after for the history patch, plus the layer itself. The per-pixel
warp in `applyFullSourceTile` ([line 217](../core/src/main/kotlin/app/painter/core/filter/Filters.kt)) calls
`globalPixel` and a bilinear `sample` for each output pixel.

`Filters.runParallel` ([line 2008](../core/src/main/kotlin/app/painter/core/filter/Filters.kt)) creates a **new
fixed thread pool on every filter pass** and shuts it down immediately. This is small compared with a 4K copy but
matters for repeated small filters and for live refresh loops.

Cell filters (`CrystallizeParams`, `HexPixelateParams`, `TrianglePixelateParams`, `PointillizeParams`) use
`needsStableSource` and large reaches: for crystallize at `size = 100`, `reach = 3 * size + 2 = 302`, so one tile
patch is `(256 + 2·302)² · 4 floats · 4 B ≈ 11.3 MiB`; four workers can hold ~45 MiB of patches at once. This path
was not timed; it is a risk for 4K cell filters.

### 4.4 Wet-edge / adhesion preview is processed and uploaded on the main thread

When `wetEdges > 0` or `adhesion > 0`, `StrokeEngine.previewTile`
([line 919](../core/src/main/kotlin/app/painter/core/stroke/StrokeEngine.kt)) calls `wetFactors`
([line 854](../core/src/main/kotlin/app/painter/core/stroke/StrokeEngine.kt)) for every requested tile. `wetFactors`
copies the tile plus its 8-neighbour halo into a `264×264` plane, runs a horizontal box pass and a vertical box
pass, and produces one factor per tile pixel. `CanvasView.invalidateStroke`
([`CanvasView.kt:164`](../app/src/main/kotlin/app/painter/render/CanvasView.kt)) expands every dirty tile with
`withNeighbours` ([line 289](../app/src/main/kotlin/app/painter/render/CanvasView.kt)), so one changed tile causes
up to 9 `previewTile` calls. Each result is converted from float to half by `DocumentSync.copyTile`
([`DocumentSync.kt:281`](../app/src/main/kotlin/app/painter/render/DocumentSync.kt)) — 262,144 values per tile —
and uploaded on the GL thread.

None of A–E enables `wetEdges` or `adhesion` (`EffectParams` defaults are 0), so this cost is **not measured**.
It is the main unknown behind checklist item 6.6 ("wet edges preview, large size: no stutter").

### 4.5 Per-flush tile scheduling overhead

Every `StrokeEngine.add(frame)` ends in `flush()`
([`StrokeEngine.kt:584`](../core/src/main/kotlin/app/painter/core/stroke/StrokeEngine.kt)), which calls
`TileScheduler.run` ([`TileScheduler.kt:38`](../core/src/main/kotlin/app/painter/core/stroke/TileScheduler.kt)).
`runRange` ([line 55](../core/src/main/kotlin/app/painter/core/stroke/TileScheduler.kt)) builds a
`HashMap<Long, MutableList<Int>>` of tile bins, an `ArrayList<Job>`, allocates a `Job` per tile and invokes
`executor.invokeAll` once per flush. For a 20 px brush at 500 dabs/stroke, each frame carries only a few dabs and a
few tiles; the binning, `Job` allocation and pool hand-off are comparable to the actual dab work. This is the
likely reason 4 threads are slower than 1 for A. The scheduler itself is process-wide
(`TileScheduler.shared`), so the pool is not recreated per flush; only the per-flush bins/jobs are.

### 4.6 Pen-up composite and working-set commit are single-threaded

`StrokeEngine.compositeBuffer`
([`StrokeEngine.kt:732`](../core/src/main/kotlin/app/painter/core/stroke/StrokeEngine.kt)) loops over every buffer
tile and every pixel, applying opacity, wet-edge factors, adhesion alpha, and optionally `BlendMath.blendPixel` for
a brush blend mode. `LayerWorkingSet.commit`
([`WorkingTiles.kt:127`](../core/src/main/kotlin/app/painter/core/stroke/WorkingTiles.kt)) then encodes the touched
working tiles back to half, builds the `TilePatch` before/after maps, and releases the float tiles. The probe's
`end` column (13–56 ms for a 1000 px stroke on desktop) is this path; it scales with touched area, not with path
length alone. At 4K a full-canvas stroke can touch 135 tiles and is far more expensive (arithmetic in §4.9).

### 4.7 GPU tile compositing at 4K / 10 layers is unmeasured

`CanvasRenderer.compositeVisibleTiles`
([`CanvasRenderer.kt:241`](../app/src/main/kotlin/app/painter/render/CanvasRenderer.kt)) composites each visible
dirty tile; `tileTarget` ([line 285](../app/src/main/kotlin/app/painter/render/CanvasRenderer.kt)) calls
`compositeTileInto` ([line 631](../app/src/main/kotlin/app/painter/render/CanvasRenderer.kt)), which runs
`renderChildren` ([line 647](../app/src/main/kotlin/app/painter/render/CanvasRenderer.kt)) with one blend pass per
visible layer. At 4K the document is 15×9 = 135 tiles; at 10 layers a full visible redraw is up to 1,350 blend
passes, plus isolated group targets and adjustment passes. The renderer mitigates this with a bounded LRU composite
cache (base 64 tiles, grown to the visible set) and a single mipmapped overview at zoom < 1
([lines 107–130, 218–233](../app/src/main/kotlin/app/painter/render/CanvasRenderer.kt)). No GL/device measurement
was made, so the actual frame time is unknown.

### 4.8 The 4K layer limit is at most 6 layers, not 10+

`LayerLimit.maxLayers` ([`app/src/main/kotlin/app/painter/studio/LayerLimit.kt:17`](../app/src/main/kotlin/app/painter/studio/LayerLimit.kt))
uses `perLayer = w * h * 4` bytes (half-float at 50% fill) and a fixed 192 MiB GPU allowance. At 3840×2160:
`perLayer = 31.6 MiB` (33.2 MB), so the 192 MiB GPU allowance permits **at most 6** layers. The CPU side is also
bounded by 40% of the large heap (`StudioViewModel.layerLimitFor`,
[line 4188](../app/src/main/kotlin/app/painter/studio/StudioViewModel.kt)), so a smaller heap gives fewer layers
(for example 3 on a 256 MB large heap). Therefore **checklist item 6.7 ("4K canvas, 10+ layers") cannot be
exercised under the current limit** unless the heap/GPU allowance changes. At 1080p the GPU allowance alone permits
24 layers, so 10 layers is a valid case on a device with a 512 MB large heap; smaller heaps lower the limit.

The formula assumes half of the canvas is covered; a full-canvas 4K layer is 67.5 MiB in half floats, so six full
layers are ~405 MiB CPU and ~405 MiB GPU, versus the 192 MiB GPU allowance. The limit is an average-coverage
estimate, not a worst-case bound. This is a policy/measurement risk, not a measured failure.

### 4.9 History and transient memory

`TilePatch.byteSize` is the sum of the before and after tiles
([`TilePatch.kt:12`](../core/src/main/kotlin/app/painter/core/raster/TilePatch.kt)); a 256×256 half tile is 0.5 MiB,
a float tile is 1 MiB. The app caps history at `maxMemory / 4` clamped to 64–192 MiB
([`StudioViewModel.kt:269`](../app/src/main/kotlin/app/painter/studio/StudioViewModel.kt)); the `History` default
of 512 MiB is not used by the app. A full-canvas 4K filter patch is 135 MiB = **70% of the 192 MiB cap**. The
`History.evict` loop ([`History.kt:110`](../core/src/main/kotlin/app/painter/core/history/History.kt)) drops old
steps, but the newest step is always kept even when it alone exceeds the cap.

Arithmetic for a full-canvas buffered 4K stroke (not an observed RSS):

- stroke buffer: 135 tiles × 1 MiB = 135 MiB;
- float working copies: 135 MiB;
- before half-snapshot: 67.5 MiB;
- total before the layer itself and GPU staging: **≈337 MiB**.

A full-source filter at 4K has a similar peak: 126.6 MiB source + 67.5 MiB before + 67.5 MiB after + the layer.
These numbers are derived from `Tile.byteSize` and tile counts; they are not heap measurements. The app has an
`OutOfMemoryError` fallback around stroke start/move/end (`failStroke`), but repeated GC pressure near the cap will
still cause jank.

### 4.10 Other observations

- `TilePool.DEFAULT_LIMIT = 32` ([`WorkingTiles.kt:41`](../core/src/main/kotlin/app/painter/core/stroke/WorkingTiles.kt))
  retains up to 32 MiB of float tiles; `StagingPool` retains up to 64 × 0.5 MiB = 32 MiB of half arrays
  (`DocumentSync.kt:86`). Both are bounded and not a primary risk, but they add to the 4K peak.
- The existing scenario C in `StrokeBench` does not pass `TextureParams`; its "texture: S2" label is aspirational.
  C2 above is the first measurement of the bitmap+grain path.
- `StrokeBench` has no `@Tag("bench")`; the M2 spec says it should not be part of a normal run, but it currently is.

---

## 5. Top risks

| Risk | Evidence | Severity | Status |
|---|---|---|---|
| Live filter layer refresh blocks the UI | `refreshFilterLayers` on `history.onChanged`; 0.86–1.14 s (10 layers, 1080p) + filter; 2.5–3.8 s for the 4K 10-layer stress | **High** | measured desktop; device unknown |
| Watercolor pen-up freeze | 87–167 ms `end()` on desktop; `dry()` up to 36 ticks; `onStrokeEnd` on the UI thread | **High** | measured desktop; device unknown |
| 4K full-source filter | 149–302 ms + 135 MiB history patch; 126.6 MiB transient source | **High** | measured desktop |
| Wet-edge / adhesion preview | `previewTile` + 9-neighbour uploads + float→half on the main thread | **Medium–High** | not measured |
| 4K / 10+ layers | 4K UI limit is at most 6 layers; 1080p 10 layers CPU flatten ~0.9–1.1 s | **Medium** | derived; checklist mismatch |
| History / peak memory | 135 MiB patch vs 192 MiB cap; ~337 MiB stroke arithmetic | **High** | derived, not RSS |
| GPU compositing 4K / 10 layers | up to 135 tiles × 10 passes per full redraw; LRU/overview mitigations | **Medium** | not measured |
| Benchmark reliability | up to 2× spread across three runs; no JMH, no tag, no p95 | **Medium** | process risk |

---

## 6. Prioritized next steps

1. **Move live-filter refresh off the main thread** (P0). Recompute asynchronously with a generation token and
   cancellation, coalesce/debounce changes, and keep the previous cache visible until the new one is ready. If
   possible, restrict the flatten/filter to the dirty rectangle or visible tiles instead of the whole canvas.
   This is the single largest win and does not require changing the filter math.
2. **Make watercolor drying incremental / off-thread** (P0). `StrokeEngine.end` should not run up to 36 ticks
   synchronously on the UI thread. Commit the stroke, then dry on a worker/timer (the `TODO(S8b)` in
   `WetSimulation`), or cap the per-frame tick budget and finish asynchronously. Keep the patch's before/after
   semantics for undo.
3. **Avoid the full-source float copy for warps** (P1). A warp that only needs a bounded region should read tiles
   through a read-only view; if the algorithm truly needs the whole content, reuse one buffer per filter pass and
   share a single filter executor instead of `Executors.newFixedThreadPool` per apply
   (`Filters.runParallel`). Measure the 4K path again after the change.
4. **Bound history by area and avoid double full copies for huge operations** (P1). For a 4K full-canvas filter,
   the 135 MiB patch is 70% of the app cap. Consider delta/compressed storage, a per-command area cap, or
   committing large filters as a single "resnapshot" step with a lower retention policy. At minimum, surface the
   memory cost and test undo/redo near the cap.
5. **Move wet-edge/adhesion preview work off the main thread** (P1). Cache the per-tile factors keyed by tile
   version, or compute them on the stroke worker. Reduce the 9-neighbour re-upload to the union of dirty regions,
   or upload the interior only and let the halo update on the next frame. Add a benchmark scenario for
   `wetEdges > 0` and a large brush.
6. **Revisit the 4K layer-limit policy** (P1). Either (a) keep the current limit (at most 6 layers) and change checklist 6.7 to the
   supported limit, or (b) raise the GPU allowance / change the 50%-coverage formula if 10+ layers at 4K is a
   product requirement. Test a 4K document with the maximum allowed full layers for actual RSS and GL memory.
7. **Measure on the reference device/emulator** (P1). Add `FrameMetrics`/Macrobenchmark or use the existing
   debug on-screen counter. Cover: 1080p 10 layers, 4K at the layer limit, watercolor tick + pen-up, wet-edge
   preview, a 4K full-source filter, and history memory near the cap. Record results in
   [qa/TABLET-CHECKLIST.md §6](qa/TABLET-CHECKLIST.md).
8. **Improve the harness** (P2). Add `@Tag("bench")` and exclude it from the normal suite; report `add` and `end`
   separately (the probe shows they differ by 1–2 orders of magnitude for E); add the texture variant to C; run
   at least 5 iterations and report median/p95; fix CPU frequency/governor and avoid concurrent builds.
9. **Only then consider GPU dabs** (P2). The M2 gate says GPU dabs for `MaskKernel` only if B/C fail after CPU
   optimizations. The desktop means pass; the device numbers are unknown. Do not start GPU dabs before the
   device baseline, because the main measured risks are the filter/compositor and pen-up paths, not the dab
   kernel means.

---

## 7. Limitations

- **Desktop only.** No Android device, emulator, GLES driver, Compose frame timing or stylus latency was measured.
  Device numbers may be several times slower and are the main missing input for a release decision.
- **Not JMH.** JIT, GC, CPU frequency and background load were uncontrolled. Three `StrokeBench` runs produced up
  to 2× differences; the supplementary probe had only two runs. Maxima are single samples, not p95/p99.
- **`StrokeBench` includes `end()`** in its mean/max. For A–D that is a small one-off; for E it dominates the mean
  and the max. Use the probe's `add` column for tick comparisons, and treat the `end` column as pen-up cost.
- **The probe was temporary.** It was deleted after the runs; its source is not in the repository, so the exact
  filter/compositor numbers are not reproducible from a committed harness. The A–E numbers are reproducible with
  the command in §2.1.
- **C2 is one sample**, and the existing C omits the texture its label mentions. Do not treat C2 as a target row.
- **Memory figures are arithmetic**, based on `Tile.byteSize` (0.5 MiB half / 1 MiB float per 256² tile), not
  observed Android heap or RSS. The 4K 10-layer probe exceeds the current UI layer limit (at most 6 at 4K); it is a
  stress bound for the compositor, not a supported configuration.
- **M2 comparison is not controlled.** The M2 rows come from `STATUS.md` and were not re-run on this tree. A
  per-commit bisect would be needed to attribute the A/B slowdown.
- **`BlendParityTest` is a correctness suite**, not a benchmark, and was not run (no emulator). It does not
  produce performance numbers.
- **No GPU compositing numbers.** The 4K/10-layer GPU cost and the effect of the LRU cache are unknown.

---

## Appendix A — exact commands and raw output

### A.1 `StrokeBench`, run 1

```text
. .\tools\env.ps1
.\gradlew.bat :core:test --tests '*StrokeBench*' --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 44s
5 actionable tasks: 5 executed
```

`core/build/stroke-bench.txt`:

```text
cores=16
threads=1 A size 20 spacing 10%: mean 0.52 ms/frame, max 9.13 ms, frames=33, ~dabs=500
threads=1 B size 300 soft spacing 5%: mean 2.26 ms/frame, max 21.39 ms, frames=33, ~dabs=66
threads=1 C size 500 bitmap spacing 8% (texture: S2): mean 3.66 ms/frame, max 20.08 ms, frames=33, ~dabs=25
threads=1 D smudge size 200: mean 1.61 ms/frame, max 2.59 ms, frames=33, ~dabs=50
threads=1 E watercolor size 80: mean 5.50 ms/frame, max 95.14 ms, frames=33, ~dabs=250
threads=4 A size 20 spacing 10%: mean 0.69 ms/frame, max 10.77 ms, frames=33, ~dabs=500
threads=4 B size 300 soft spacing 5%: mean 1.49 ms/frame, max 13.83 ms, frames=33, ~dabs=66
threads=4 C size 500 bitmap spacing 8% (texture: S2): mean 1.89 ms/frame, max 21.29 ms, frames=33, ~dabs=25
threads=4 D smudge size 200: mean 1.22 ms/frame, max 2.50 ms, frames=33, ~dabs=50
threads=4 E watercolor size 80: mean 4.72 ms/frame, max 82.06 ms, frames=33, ~dabs=250
```

### A.2 `StrokeBench`, run 2

```text
BUILD SUCCESSFUL in 1m 17s
5 actionable tasks: 5 executed
```

```text
cores=16
threads=1 A size 20 spacing 10%: mean 0.41 ms/frame, max 4.79 ms, frames=33, ~dabs=500
threads=1 B size 300 soft spacing 5%: mean 2.43 ms/frame, max 19.38 ms, frames=33, ~dabs=66
threads=1 C size 500 bitmap spacing 8% (texture: S2): mean 3.66 ms/frame, max 21.38 ms, frames=33, ~dabs=25
threads=1 D smudge size 200: mean 1.58 ms/frame, max 2.40 ms, frames=33, ~dabs=50
threads=1 E watercolor size 80: mean 6.51 ms/frame, max 100.34 ms, frames=33, ~dabs=250
threads=4 A size 20 spacing 10%: mean 0.34 ms/frame, max 3.76 ms, frames=33, ~dabs=500
threads=4 B size 300 soft spacing 5%: mean 1.54 ms/frame, max 13.80 ms, frames=33, ~dabs=66
threads=4 C size 500 bitmap spacing 8% (texture: S2): mean 1.76 ms/frame, max 18.68 ms, frames=33, ~dabs=25
threads=4 D smudge size 200: mean 1.41 ms/frame, max 2.57 ms, frames=33, ~dabs=50
threads=4 E watercolor size 80: mean 5.80 ms/frame, max 96.92 ms, frames=33, ~dabs=250
```

### A.3 `StrokeBench`, run 3

```text
BUILD SUCCESSFUL in 1m 55s
5 actionable tasks: 5 executed
```

```text
cores=16
threads=1 A size 20 spacing 10%: mean 0.42 ms/frame, max 6.43 ms, frames=33, ~dabs=500
threads=1 B size 300 soft spacing 5%: mean 3.41 ms/frame, max 44.78 ms, frames=33, ~dabs=66
threads=1 C size 500 bitmap spacing 8% (texture: S2): mean 6.51 ms/frame, max 41.10 ms, frames=33, ~dabs=25
threads=1 D smudge size 200: mean 2.70 ms/frame, max 5.86 ms, frames=33, ~dabs=50
threads=1 E watercolor size 80: mean 11.34 ms/frame, max 196.68 ms, frames=33, ~dabs=250
threads=4 A size 20 spacing 10%: mean 1.00 ms/frame, max 5.37 ms, frames=33, ~dabs=500
threads=4 B size 300 soft spacing 5%: mean 1.75 ms/frame, max 14.90 ms, frames=33, ~dabs=66
threads=4 C size 500 bitmap spacing 8% (texture: S2): mean 2.89 ms/frame, max 26.18 ms, frames=33, ~dabs=25
threads=4 D smudge size 200: mean 2.48 ms/frame, max 8.27 ms, frames=33, ~dabs=50
threads=4 E watercolor size 80: mean 8.23 ms/frame, max 119.91 ms, frames=33, ~dabs=250
```

### A.4 Supplementary probe (temporary; two runs)

Command:

```powershell
. .\tools\env.ps1
.\gradlew.bat -I perf.init.gradle :core:test --tests '*M5PerfProbe*' --rerun-tasks --console=plain
```

Run 1 (`build/m5-perf-probe.txt`):

```text
cores=16 maxHeapMB=4096
PHASE threads=1 A size 20: add mean 0.38 ms/frame, add max 1.38 ms, end 22.53 ms
PHASE threads=1 B size 300 soft: add mean 2.26 ms/frame, add max 4.57 ms, end 32.22 ms
PHASE threads=1 C size 500 bitmap: add mean 4.71 ms/frame, add max 18.01 ms, end 55.61 ms
PHASE threads=1 D smudge 200: add mean 2.70 ms/frame, add max 5.95 ms, end 2.58 ms
PHASE threads=1 E watercolor 80: add mean 3.42 ms/frame, add max 16.77 ms, end 120.07 ms
PHASE threads=4 A size 20: add mean 0.62 ms/frame, add max 2.30 ms, end 4.27 ms
PHASE threads=4 B size 300 soft: add mean 1.28 ms/frame, add max 2.19 ms, end 16.19 ms
PHASE threads=4 C size 500 bitmap: add mean 1.82 ms/frame, add max 4.17 ms, end 24.56 ms
PHASE threads=4 D smudge 200: add mean 2.04 ms/frame, add max 4.28 ms, end 1.82 ms
PHASE threads=4 E watercolor 80: add mean 4.42 ms/frame, add max 20.56 ms, end 167.05 ms
FILTER full-source twirl 1920x1080 applyToGrid: 98.4 ms
FILTER full-source twirl 1920x1080 with history: 91.5 ms, patch 40.0 MB (40 tiles)
FILTER full-source twirl 3840x2160 applyToGrid: 301.1 ms
FILTER full-source twirl 3840x2160 with history: 302.2 ms, patch 135.0 MB (135 tiles)
COMPOSITE flatten 10 layers 1920x1080: 1140.0 ms, dst 20.0 MB
COMPOSITE flatten 10 layers 3840x2160: 3815.9 ms, dst 67.5 MB
```

Run 2 (includes C2):

```text
cores=16 maxHeapMB=4096
PHASE threads=1 A size 20: add mean 0.33 ms/frame, add max 0.83 ms, end 13.07 ms
PHASE threads=1 B size 300 soft: add mean 1.81 ms/frame, add max 18.14 ms, end 19.14 ms
PHASE threads=1 C size 500 bitmap: add mean 2.78 ms/frame, add max 4.82 ms, end 20.50 ms
PHASE threads=1 C2 size 500 bitmap+grain: add mean 6.22 ms/frame, add max 9.67 ms, end 27.10 ms
PHASE threads=1 D smudge 200: add mean 2.16 ms/frame, add max 8.85 ms, end 1.42 ms
PHASE threads=1 E watercolor 80: add mean 3.11 ms/frame, add max 9.69 ms, end 93.04 ms
PHASE threads=4 A size 20: add mean 0.78 ms/frame, add max 2.06 ms, end 7.65 ms
PHASE threads=4 B size 300 soft: add mean 1.42 ms/frame, add max 2.61 ms, end 13.97 ms
PHASE threads=4 C size 500 bitmap: add mean 1.50 ms/frame, add max 2.61 ms, end 26.21 ms
PHASE threads=4 C2 size 500 bitmap+grain: add mean 1.89 ms/frame, add max 2.95 ms, end 21.16 ms
PHASE threads=4 D smudge 200: add mean 1.29 ms/frame, add max 2.06 ms, end 1.03 ms
PHASE threads=4 E watercolor 80: add mean 2.48 ms/frame, add max 8.80 ms, end 87.20 ms
FILTER full-source twirl 1920x1080 applyToGrid: 53.6 ms
FILTER full-source twirl 1920x1080 with history: 56.3 ms, patch 40.0 MB (40 tiles)
FILTER full-source twirl 3840x2160 applyToGrid: 149.4 ms
FILTER full-source twirl 3840x2160 with history: 291.3 ms, patch 135.0 MB (135 tiles)
COMPOSITE flatten 10 layers 1920x1080: 857.9 ms, dst 20.0 MB
COMPOSITE flatten 10 layers 3840x2160: 2517.0 ms, dst 67.5 MB
```
