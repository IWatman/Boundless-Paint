# M5 QA closeout

- **Milestone:** M5 — Polish (Boundless Paint 0.5.10).
- **Baseline:** `m5-qa`, based on `main` `ac2440a` (`v0.5.10`).
- **Date:** 2026-11-02.
- **Scope:** documentation only. No production code, version bump, tag or push.
- **Verdict:** the shipped M5 scope is code-complete on JVM/emulator evidence, but **M5 is not QA-closed**.
  Pattern tools (path/quilt/tile) are still absent, and the real-tablet matrix, 4K, audio drift and throughput
  measurements are pending.

This page separates three things that are easy to confuse:

1. **Implemented** — the code and UI exist in the worktree.
2. **Covered** — a named JVM or instrumented test exercises it.
3. **Verified on hardware** — the reference XP-Pen tablet produced a result.

Only the first two have evidence today. A row is never marked passed because the feature "should work"; unrun rows
stay **Pending**.

## 1. Sources and method

Read for this closeout: [ROADMAP.md](ROADMAP.md) (M5), the M5 sections of [STATUS.md](STATUS.md),
[FEATURE-MAP.md](FEATURE-MAP.md), [VIDEO-MODE.md](VIDEO-MODE.md), [filters.md](filters.md),
[PERFORMANCE-M5.md](PERFORMANCE-M5.md), [qa/TABLET-CHECKLIST.md](qa/TABLET-CHECKLIST.md) and
[../README.md](../README.md).

Actions taken:

- inspected the M5 source and test names (`PatternFill`, `MaterialStamp`, `VideoExportPlan`, `AudioMix`,
  `VideoPanel`, the five `FiltersM5*` suites and the instrumented video/audio suites);
- ran `python tools/check_localization.py` on 2026-11-02 (exit 0; see §2);
- did **not** run Gradle, the emulator or the tablet. Test counts and emulator results below are quoted from
  STATUS and are marked "reported".

## 2. Feature checklist against the M5 roadmap

The roadmap line is short: **"Remaining filters, pattern tools, materials, video mode, localization,
performance."** The table expands it into the shipped scope.

| # | M5 item | Shipped in 0.5.10 | Evidence | QA status |
|---|---|---|---|---|
| 1 | Remaining filters | All 52 filters marked M5 in [filters.md](filters.md), in five batches: smooth/lens/high-pass/scatter/grain/local contrast/bokeh/flare/god rays/cross; geometric warps; stylization/material filters; artistic/generative filters; manga/sketch/oil/frames/rain. Common strength and per-filter controls; one undo step; selection, alpha lock and tile seams covered. | JVM `FiltersM5Test` (8), `FiltersM5GeometryTest` (11), `FiltersM5StyleTest` (15), `FiltersM5ArtisticTest` (15), `FiltersM5FinalTest` (16) = 65 tests. | JVM covered. No device visual pass recorded. |
| 2 | Pattern fill | Twelve seamless procedural patterns (checks, dots, diagonal stripes, brick, herringbone, canvas, zigzag, waves, triangles, stars, hexagons, wood grain), two colours, tile size, rotation, phase offset, contrast, invert and opacity; worker fill, one undo step. | JVM `PatternFillTest` (22): lattice wrap, rotation, offsets, controls, selection, alpha lock, undo. | JVM covered. Device visual/seam check pending. |
| 3 | Pattern tools (path / quilt / tile) | **Not shipped.** Only pattern fill exists; no path, quilt or tile pattern tool is in the UI or core. README still lists these under "Next (M5)". | No implementation found. [FEATURE-MAP.md](FEATURE-MAP.md) §21 lists them as P2. | ⚠️ **Open M5 gap.** Must be implemented or explicitly deferred to M6 before the milestone is called closed. |
| 4 | Materials | Eight procedural stamps (balloon, fabric, wood, metal, stone, foliage, sparkle, paper); Stamp and Paint modes; size, rotation, opacity and spacing; favourites, recents and settings persisted across restarts. | JVM `MaterialStampTest`, `MaterialStrokeTest`, `MaterialPrefsCodecTest`. | JVM covered. No import, user library or single-stamp drag placement; no device visual pass. |
| 5 | Video S1 — model, persistence, timeline | 60 Hz tick model, tracks/clips/audio, animation conversion, manifest/`saveDelta`/snapshot persistence, read-only timeline with scrubbing. | JVM `VideoTest` (13), `VideoProjectIOTest` (7), `SnapshotTest` (+2). | JVM covered. Manual timeline pass pending. |
| 6 | Video S2 — editing and preview | Add/blank/delete/trim/move, snapping, 60 Hz playback cursor, transport, canvas preview, stop on paint/undo/panel close; no history or timelapse entries. | JVM `VideoEditTest` (10), `VideoPlaybackCursorTest` (6), `VideoTimelineTest` (5). | JVM covered. 60 fps preview on hardware pending. |
| 7 | Video S3 — MP4 export | 720p/1080p/1440p plan, BT.709 limited-range I420, H.264 at 60 fps, progress/cancel, cache file streamed to SAF. | JVM `VideoExportPlanTest` (13 at S3), `YuvTest` (8); instrumented `VideoExporterTest` (3) on the `painter_tab` AVD. | Emulator covered (reported). 1080p60 timing and player compatibility pending. |
| 8 | Video S4 — audio | Import and record, clip/track/master volume, mute, move/trim, 48 kHz stereo offline mix, AAC-LC M4A, mux with video, preview from the same mix; project-owned relative audio files. | JVM `AudioMixTest`, `AudioTimelineTest`, `VideoProjectIOTest` additions; instrumented `AudioMixerTest`, `AudioPreviewPlayerTest`, `ProjectAudioTest`, `VideoExporterAudioTest` (8/8 reported green on `painter_tab`). | Emulator covered (reported). Real recording, codec matrix and A/V drift pending. |
| 9 | Video S5 — 4K and capability guards | 4K/60 only when the encoder advertises it; explicit 4K/30 fallback; 40 Mbps; 8,294,400 px cap; memory guard; derived layer limit; one reusable I420 frame. Second drawing track **not shipped** (model/compositor are multi-track-ready). | JVM `VideoExportPlanTest` (19), `VideoTest` (+1 multi-track), `LayerLimitTest` (+1); instrumented `VideoExporter4KTest` was skipped on `painter_tab`. | JVM covered. 4K hardware unverified; no 4K throughput claim. |
| 10 | Localization | Static UI strings moved to resources; Russian wording fixes; localization checker added. | `python tools/check_localization.py` on 2026-11-02: **891 EN resources (1 non-translatable), 890 RU, 0 missing, 0 extra, 0 other problems, exit 0.** | Checker covered. Russian on-device spot check pending. STATUS records the 2026-11-01 797/796 snapshot and the 2026-11-02 recheck. |
| 11 | Performance | Desktop JVM baseline A–E, filter/compositor probe, hot-path analysis and prioritized risks. | [PERFORMANCE-M5.md](PERFORMANCE-M5.md). | Desktop only. Device frame times, GLES cost, heap and thermals are unmeasured. |

## 3. Verified vs unverified

| Level | What exists | Result | Re-run in this closeout? |
|---|---|---|---|
| JVM | **831 tests reported** (723 core, 108 app) at the end of S5, including the five M5 filter suites, `PatternFillTest`, the three material suites, `VideoTest`/`VideoProjectIOTest`/`VideoEditTest`/`VideoPlaybackCursorTest`/`VideoExportPlanTest`/`YuvTest`, `AudioMixTest`/`AudioTimelineTest`. | Reported green in [STATUS.md](STATUS.md). | No — counts are quoted, not re-run. |
| Instrumented | `VideoExporterTest` (3) and the S4 audio suites (8/8) on the `painter_tab` AVD. | Reported green. | No. |
| Instrumented 4K | `VideoExporter4KTest`. | **Skipped** because the AVD encoder/heap did not qualify. Not a pass. | No. |
| Emulator manual | No M5-specific manual UI pass is recorded; S3/S4 evidence is instrumented only. | — | No. |
| Real tablet | M5 rows have not been run on the XP-Pen. The 2026-10-26 check covers M0–M1 stylus/palm basics only. | **Pending.** | No. |
| 4K | JVM plan/memory guard/layer-limit tests only. No 4K file from hardware. | **Unverified.** | No. |
| A/V drift | No audible or measured drift test. Instrumented S4 asserts PTS/track structure, not perceived sync. | **Unverified.** | No. |
| Throughput | No 1080p60 or 4K device timing. PERFORMANCE-M5 is a desktop CPU baseline. | **Unverified.** | No. |
| Big project | No long-timeline/max-layer stability run on hardware. | **Pending.** | No. |
| Localization | The checker was run in this closeout. | **Pass** (891/890, exit 0). | Yes. |

**What this means:** "831 JVM tests green" is useful regression evidence, but it does not certify codec behaviour,
memory pressure, thermal throttling, stylus latency or playback compatibility. Those are the hardware rows below.

## 4. Device test matrix — XP-Pen Magic Drawing Pad

Reference device: **XP-Pen MDP1221**, Android 15, 2160×1440, EMR stylus (`tct_stylus`), pressure 0..16383,
tilt, two buttons and an eraser end. Use build **0.5.10** or later. Detailed steps and the Result/Notes columns
are in [qa/TABLET-CHECKLIST.md](qa/TABLET-CHECKLIST.md) §9. **Every row below is Pending** until a real run is
recorded.

| ID | Area | Test | Measurement / pass condition | Checklist | Status |
|---|---|---|---|---|---|
| D1 | Stylus | Warm-up, light stroke, restart, calibration wizard, curve presets | Full pressure reaches the brush's full size/opacity (p_cal ≥ 0.98); light strokes are thin but continuous; calibration survives restart | 1.1–1.7 | Pending |
| D2 | Stylus | Primary/secondary buttons and eraser end | Primary = eyedropper, secondary = one undo, eraser end erases, tip restores the previous brush | 2.1–2.5 | Pending |
| D3 | Stylus | Tilt and azimuth | Shading pencil widens/lightens; calligraphy nib turns with tilt | 3.1–3.2 | Pending |
| D4 | Palm/finger | Two-minute palm rest, gestures with the pen, finger rules, hover reject | No palm stroke; gestures still work; finger behaviour matches the selected rule | 4.1–4.5 | Pending |
| D5 | Video UI | Convert an animation, scrub, play/pause/stop, paint during playback, close the panel | Timeline duration/clips match the animation; canvas follows the playhead; painting stops playback and edits the visible frame; closing restores the editing frame | 9.1–9.3 | Pending |
| D6 | 1080p export | 5 s timeline, 1080p60, audio off | Record wall-clock time; file has one `video/avc` track, 300 frames, duration 5 s ±1 frame; temp file deleted; plays in the Android and a desktop player | 9.4 | Pending |
| D7 | 1440p / 4K chips | Inspect offered/disabled chips, 4K30 fallback, memory guard, layer-limit warning | Chips match the encoder; 4K30 is explicit; low heap refuses 4K with the estimate; no silent fps/size change | 9.5 | Pending |
| D8 | 4K export | If offered: 5 s at 4K30 or 4K60 | Record wall-clock time; completes without OOM; layer count stays within the derived limit (≤6 at 4K on a 512 MB heap); if unsupported, record the exact skip reason | 9.6 | Pending / skip if unsupported |
| D9 | Audio import | Import MP3 and M4A; try WAV if the device decoder supports it; place clips at the playhead; try an unsupported file | Clip appears and plays when supported; an unsupported codec is rejected with its name | 9.7 | Pending |
| D10 | Audio record | Allow and deny `RECORD_AUDIO`; record 10 s of voice | Allowed: clip inserted at the playhead and audible. Denied: clear message, no crash | 9.8 | Pending |
| D11 | Audio mux | Export with audio, then muted; inspect tracks and duration | Two tracks (`video/avc`, `audio/mp4a-latm`) with audio; one track when muted; audio duration ≤ video; audio truncated at the end | 9.9 | Pending |
| D12 | A/V sync | Clap/voice recording, 60 s export, watch and listen | First clap aligned within one frame; no audible drift over the clip | 9.10 | Pending |
| D13 | Playback | 720p, 1080p, 1440p and 4K files in the Android player and a desktop player | Correct duration, orientation and audio; no decode errors | 9.11 | Pending |
| D14 | Big project | 1080p, 10 layers if the heap allows, 60 s timeline, audio, repeated scrub/play/export | No ANR, OOM or force-stop; Dalvik heap stable; temp files cleaned | 9.12 | Pending |
| D15 | 4K canvas | 4K document at the current layer limit; draw, pan, filter, export | No OOM/ANR; drawing/panning remain usable; record the actual layer count | 9.12, 9.6 | Pending |
| D16 | Patterns/materials | Each pattern rotated/offset; each material stamped and painted; restart | No seams; controls change the result; favourites/recents survive restart | 9.13–9.14 | Pending |
| D17 | Localization | Switch the device to Russian; visit every M5 dialog | No English leftovers, clipped labels or broken plurals in filters, pattern fill, materials, video, audio and export | 9.15 | Pending |
| D18 | Stylus regression | Repeat D1–D4 on the M5 build | M5 changes did not regress pressure, palm rejection or buttons | 9.16 | Pending |

The matrix deliberately has **no pass/fail time target** for 1080p/4K export. The specs promise correct output,
not real-time encoding. Record the wall-clock time and the device/encoder; a future target can then be set from
data instead of a guess.

## 5. M5 exit criteria

M5 is closed only when all of E1–E7 are met. E8 is release hygiene for this documentation change.

| ID | Criterion | Status | Evidence / action |
|---|---|---|---|
| E1 | Every M5 roadmap item is shipped or explicitly deferred: filters, pattern tools, materials, video S1–S5, localization, performance. | **Open** | Filters, pattern fill, materials, video and localization are shipped. Path/quilt/tile pattern tools are not; either implement them or record a formal M6 deferral in ROADMAP/README. |
| E2 | All JVM tests green, including the M5 suites; localization checker exits 0 with no missing/extra keys. | **Reported met; checker met** | 831 JVM tests reported green; checker run here: 891/890, exit 0. The full suite was not re-run in this docs-only closeout. |
| E3 | Instrumented video/audio suites green on the `painter_tab` AVD; 4K skip is explicit, never a false pass. | **Reported met** | S3 `VideoExporterTest` 3/3 and S4 audio 8/8 reported green; `VideoExporter4KTest` skipped. |
| E4 | D1–D4 pass on the XP-Pen: pressure/calibration, buttons/eraser, tilt, palm/finger. | **Pending** | Real-tablet rows 1–4 and 9.16. |
| E5 | D5–D13 pass: timeline/preview, 1080p60 export timing, 4K capability/export, audio import/record/mux, A/V sync, playback. | **Pending** | Real-tablet rows 9.1–9.11. |
| E6 | D14–D15 pass: big-project and 4K-canvas stability at the supported layer limit, with heap/ANR observations. | **Pending** | Real-tablet rows 9.12 and 9.6. |
| E7 | Documentation is accurate: QA-M5, M5 checklist section, STATUS link, and no stale M5 "Next" list. | **Partially met** | This page and the checklist section are added; the STATUS link is added. README still lists pattern tools/material work as "Next (M5)"; resolve with E1. |
| E8 | No production code, version bump, tag or push in the QA change. | **Met** | Docs-only diff. |

## 6. Known non-goals

These are accepted product boundaries, not QA failures. They come from [VIDEO-MODE.md](VIDEO-MODE.md) §3.2 and
§11 plus the M5 scope.

- **Video formats:** MOV/QuickTime, HEVC, AV1, VP9/WebM, ProRes, HDR/10-bit, alpha video, 120 fps, variable
  frame rate. MP4 with H.264 + AAC-LC is the only shipped container/codec pair.
- **Audio:** more than one audio track, fades/ducking/EQ/pitch/tempo, waveform editing, audio extracted from a
  video file.
- **Tracks and frames:** a second drawing-track UI, per-track opacity/blend, multi-layer frames, layer-property
  animation. The model/compositor are multi-track-ready; the UI is not.
- **Editing/export:** transitions, video filters/effects, text overlays, motion blur/interpolation, range export,
  GIF/WebP animation, gallery video playback or thumbnails, a New Project video type, background/foreground
  export, and timelapse frames used as video clips.

## 7. Remaining M6 candidates

These are candidates, not commitments. The first item is the only direct M5 scope gap.

| Candidate | Why it remains | Suggested priority |
|---|---|---|
| Path, quilt and tile pattern tools | M5 roadmap says "pattern tools"; only pattern fill shipped. README lists them under "Next (M5)". | P2 — close the M5 gap first. |
| Material import, user library and single-stamp drag placement | Current materials are eight built-ins with Stamp/Paint; no import or arbitrary placement. | P2 |
| Second drawing-track UI | S5 cut it; the core model, `Video.visibleFrames`, compositor and exporter already handle multiple visible tracks. | P2 |
| Async live-filter refresh | [PERFORMANCE-M5.md](PERFORMANCE-M5.md) §4.1: synchronous full-canvas refresh blocks the UI (0.86–1.14 s for 10 layers at 1080p on desktop). | P0 before large-document work |
| Incremental watercolor drying | §4.2: pen-up `end()` blocks the UI (87–167 ms desktop). | P0/P1 |
| Bounded full-source warps and lower 4K history cost | §4.3/§4.9: 4K full-source filter is 149–302 ms and a 135 MiB history patch. | P1 |
| Wet-edge/adhesion preview off the main thread | §4.4: per-tile processing and 9-neighbour uploads on the main thread; not measured. | P1 |
| 4K layer-limit policy and RSS measurement | §4.8: the 4K limit is at most 6 layers with a 512 MB heap, so "4K, 10+ layers" is not supported. | P1 |
| Device performance harness | §6.7: add `FrameMetrics`/macrobenchmark coverage for 1080p, 4K, watercolor, wet edges and filters. | P1 |
| More locales and on-device localization QA | Only English and Russian resources exist; Russian visual spot check is still pending. | P2 |

## 8. Gaps and documentation actions found in this closeout

1. **Pattern tools are missing.** `filters.md`, pattern fill and materials are complete, but path/quilt/tile
   pattern tools from FEATURE-MAP §21 are absent. This is the one clear roadmap gap.
2. **Localization counts in STATUS are stale.** The 2026-11-01 note says 797 EN / 796 RU; the 2026-11-02 checker
   reports 891 EN (1 non-translatable) / 890 RU. The counts are updated with a dated note in STATUS.
3. **Checklist item 6.7 was impossible.** It asked for "4K canvas, 10+ layers", while PERFORMANCE-M5 §4.8 derives
   a maximum of 6 layers at 4K with a 512 MB heap. The row now asks for the supported limit and records the
   actual layer count.
4. **No M5 hardware evidence.** The 4K instrumented test was skipped; there is no 1080p60 timing, no 4K file,
   no audio-drift measurement and no real-tablet M5 pass.
5. **No M5 manual emulator pass is recorded.** S3/S4 are instrumented; the filter/pattern/material/video dialogs
   have JVM coverage but no recorded visual pass.
6. **README still says "Next (M5)"** for pattern tools and further material work. Resolve together with E1.

## 9. Closeout

The M5 implementation is a credible release candidate for the **shipped** scope: the filter set is complete, the
pattern fill and materials have direct JVM coverage, video S1–S5 are implemented with emulator evidence for
export/audio, and the localization checker passes. It is not a QA-closed milestone until the XP-Pen matrix
(D1–D18), the 4K/1080p timing, the audio import/record/mux and drift checks, and the big-project stability run
produce recorded results — and until the pattern-tools gap is either implemented or formally deferred.
