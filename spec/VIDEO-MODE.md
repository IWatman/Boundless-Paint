# Video mode (M5)

Status: **design** (2026-11-01). Target milestone: **M5** (spec/ROADMAP.md). This document adds no production
code; it defines the model, UI, export pipeline and the implementation slices.

Sources read for this design:

- spec/FEATURE-MAP.md §24 (timelapse and animation), spec/ROADMAP.md M5, spec/filters.md milestones;
- `core/src/main/kotlin/app/painter/core/doc/Animation.kt`, `core/src/main/kotlin/app/painter/core/doc/Document.kt`,
  `core/src/main/kotlin/app/painter/core/doc/Nodes.kt`, `core/src/main/kotlin/app/painter/core/io/Manifest.kt`,
  `core/src/main/kotlin/app/painter/core/io/ProjectIO.kt`, `core/src/main/kotlin/app/painter/core/blend/Compositor.kt`;
- `app/src/main/kotlin/app/painter/timelapse/TimelapseStore.kt`,
  `app/src/main/kotlin/app/painter/timelapse/TimelapseEncoder.kt`,
  `app/src/main/kotlin/app/painter/ui/studio/TimelapseDialog.kt`,
  `app/src/main/kotlin/app/painter/studio/StudioViewModel.kt`,
  `app/src/main/kotlin/app/painter/ui/studio/AnimationPanel.kt`,
  `app/src/main/kotlin/app/painter/ui/studio/StudioScreen.kt`;
- spec/STATUS.md, spec/document-model.md, spec/file-formats.md, spec/GAPS.md, spec/PERFORMANCE-M5.md,
  `features/24-timelapse-animation/*`.

The reference behavior is ibisPaint's video mode (features/24-timelapse-animation/ibispaint.md): a 60 fps
timeline, drawing and audio tracks, clips, and MP4/MOV export. We keep the interaction model but make decisions
where the reference is silent or where Android cannot follow it.

## 1. Decisions at a glance

| Topic | Decision |
|---|---|
| Timebase | **60 ticks per second**. In a 60 fps export one tick = one output frame; the explicit 4K/30 fallback emits every second tick. The timeline has no user-facing fps control; only the export dialog offers the labelled 4K/30 fallback. |
| Tracks in M5 | **One drawing track + one audio track** are shipped in the UI. The manifest, core model, compositor and exporter support N drawing tracks (S5 tests multi-track visibility), but S5 did not add add/delete-track UI; that remains a later slice and the format is already ready for it. |
| Source frames | The existing animation frames stay the source of truth. A video clip is a **timed reference** to a frame layer, not a copy of its pixels. |
| Conversion | Additive and one-way in the UI: `Document.video` is added, `Document.animation` is kept. Frames are not destroyed. No "leave video mode" command in M5. |
| Project type | **No new `ProjectType` and no change to `Document.type`.** `video` presence defines video mode. |
| Container/codec | **MP4 only**: H.264/AVC video + AAC-LC audio. MOV is a non-goal; `MediaMuxer` has no QuickTime container. |
| Default export | 1080p at 60 fps, video with optional audio. 4K is offered only if the device encoder supports the exact size and 60 fps (or as an explicitly labelled 4K/30 fallback). |
| Resolution | Output keeps the canvas aspect, long side 720/1080/1440/3840, and is capped at **8,294,400 pixels** (3840×2160 UHD). No silent fps or size change. |
| Audio | Project-owned copies under `audio/`; import or record; per-clip volume, track mute/volume, master volume; offline mix encoded to AAC. Preview uses the same mixed file, so preview and export agree. |
| Rendering | CPU `Compositor.flatten` with a **visibility override** map, so export never mutates the editing document. A `videoBusy` lock blocks pixel edits during export. |
| Memory | No whole-video buffering. The exporter holds one rendered I420 frame (the rasterizer buffer) and one encoder input; at 4K no second frame is allocated. The MP4 is encoded to a cache file and streamed to SAF. |
| Undo | Video metadata is **not undoable** (same as `AnimationMeta`). Frame edits remain undoable. Deleting a frame keeps clips (they render blank) so undo can restore the frame. |

## 2. Current state (what the code does today)

### 2.1 Frame animation

- `Document.animation: AnimationMeta?` holds `fps` (1..60, default 12), `playback` (`LOOP`, `PINGPONG`, `ONCE`),
  onion-skin flags and `frameGroupId`. `AnimationMeta` is not undoable state.
- Frames are **ordinary `RasterLayer`s** that are direct children of the group `frameGroupId`, in
  `GroupNode.children` order (bottom to top). `Animation.frames(doc)` returns them with
  `filterIsInstance<RasterLayer>()`, so a `VectorLayer` frame is possible (it extends `RasterLayer`).
- `Animation.normalize(doc)` makes the frame group visible, makes exactly the active frame visible and points
  `Document.activeId` at a frame. It is called after load, on save, on layer refresh and by the studio.
- `startAnimation()` wraps the active raster layer in a new group at the layer's old position in the stack; the
  group id is stored in `AnimationMeta`. Frames therefore composite with the rest of the document in normal stack
  order.
- The animation panel (`ui/studio/AnimationPanel.kt`, opened from Options) shows frame thumbnails, transport,
  add/duplicate/delete, fps, loop mode and onion distances. Playback toggles frame visibility with a coroutine
  `delay(1000 / fps)`; it does not rebuild the layer rows every tick.
- The manifest (`schema = 2`) stores an optional `animation` block. Reading ignores unknown keys and the JSON
  writer omits nulls (`explicitNulls = false`), so non-animation projects are unchanged.
- `ProjectType.ANIMATION` exists in `ProjectType`, but **nothing assigns it**: `Document.type` is a `val`, there
  is no New Project type picker in the current UI, and the type string is only read back into `Document.type`.
  It has no behavior today.
- Frames count against the document layer limit. `LayerLimit.maxLayers` uses `w·h·4` bytes per layer, 40% of the
  large heap and a fixed 192 MiB GPU allowance. Derived from that formula: with a 512 MB large heap a 3840×2160
  document allows **6** raster layers, a 1920×1080 document allows **24**. Smaller heaps allow fewer. An
  animation is therefore already limited to a handful of frames at 4K, independently of this design. The GAPS
  "≤ 999 frames" statement is not enforced by the current code; the layer limit is.

### 2.2 Timelapse

- `TimelapseStore(projectDir)` writes a **720 px** JPEG (quality 82) of the flattened document to
  `timelapse/frames/NNNNNN.jpg` after a change; `StudioViewModel` schedules at most one capture per 500 ms and
  copies the document on the main thread before encoding off it.
- `TimelapseEncoder` encodes JPEGs to H.264 MP4 at **30 fps** with `MediaCodec` and `MediaMuxer`, flexible YUV
  input with an I420 fallback, 8 Mbps at 1080p and 20 Mbps at 4K. The dialog offers 15/30/60 s or full and
  720p/1080p/4K.
- `encodeTimelapse` writes the whole MP4 into a `ByteArray`, then `TimelapseDialog` writes those bytes to the
  system file picker URI. This is acceptable for a 720p/30 fps clip but **must not be copied** for video mode:
  a 60 fps 4K video can be far larger than the heap.
- `Node.hideInRecording` is persisted, copied by `snapshotDocument` and history commands, but **no UI sets it and
  neither timelapse capture nor the compositor reads it**. It is a dormant field today.

### 2.3 Gaps this design fills

The current animation has no timing beyond a single fps, no tracks, no audio, no 60 fps guarantee, no video
export and no memory-safe video writing. Video mode adds those without changing the existing animation format or
the timelapse feature.

## 3. Product scope

### 3.1 In scope for M5

1. **Video timeline at 60 fps** with a playhead, a time ruler and clips.
2. **One drawing track** (the animation frame group) with clips that reference frame layers. Track visibility
   (eye) and per-track duration are editable.
3. **One audio track**: import an audio file, record from the microphone, trim by duration, per-clip volume,
   track mute/volume, master volume. Audio is optional.
4. **60 fps preview** on the canvas from the timeline (best effort; export is exact).
5. **MP4 export**: H.264 video, optional AAC audio, 720p/1080p/1440p/4K, progress and cancel.
6. **Persistence** of the video block in `.pnt`/project storage, including autosave, duplicate, archive, Drive
   backup and restore.
7. **Compatibility**: documents without video load and save exactly as today; documents with animation but no
   video behave as today.

### 3.2 Explicit non-goals for M5

- MOV/QuickTime, HEVC, AV1, VP9/WebM, ProRes, HDR/10-bit, alpha video, 120 fps, variable frame rate.
- Multiple audio tracks, audio effects (fade, ducking, EQ, pitch), audio from a video file, waveform editing.
- Multiple drawing tracks in the shipped UI (the model supports them; see S5), per-track opacity/blend,
  multi-layer frames (a frame is still one raster/vector layer), layer property animation.
- Transitions, filters or effects on the whole video, text overlays, motion blur/interpolation.
- Range export, GIF/WebP animation, gallery video playback or video thumbnails, a New Project video type.
- Background/foreground-service export; export continues only while the studio is alive and the progress dialog
  is open.
- Timelapse frames as video clips; the timelapse remains a separate recording.

### 3.3 Terminology

| Term | Meaning |
|---|---|
| **Tick** | 1/60 s. The timeline's integer unit; in a 60 fps export one tick is one output frame. |
| **Frame** | A `RasterLayer` (or `VectorLayer`) child of a drawing track's group. The existing animation frame. |
| **Clip** | A timed reference to a frame (or a blank pause) with `startTick` and `durationTicks`. |
| **Drawing track** | An ordered, non-overlapping sequence of clips that references one frame group. |
| **Audio clip** | A timed reference to an audio file with a gain. Audio clips may overlap. |
| **Video mode** | A document with a `Document.video` block. Frames and layers are still edited normally. |

### 3.4 Project type

Video mode is **not** a `ProjectType`. `Document.type` stays whatever it was (`REGULAR` today, because no UI
assigns `ANIMATION`). Reasons:

- `type` is a `val` and would require recreating the `Document` to change; it is not read by any behavior.
- A project can start as a regular drawing, become an animation from Options, and then become a video; a single
  immutable enum cannot represent that history.
- The gallery, New Project creator and `ProjectStore` keep working unchanged.

The authoritative flag is `Document.video != null`. A future gallery badge can read the manifest header without
changing `ProjectType`.

### 3.5 Entry and one-way conversion

Entry points:

- **Options → Video mode…** — enabled when the document has an animation with at least one frame. If the
  document has no animation, the item is disabled and a short hint says to start an animation first (Options →
  Animation → Start animation).
- **Animation panel → Video** button in the header — the same action, for discoverability.

The conversion dialog states:

- “N frames become a 60 fps timeline. Each frame keeps its duration from the animation fps.”
- Ping-pong is expanded into a linear sequence (A B C B) because video mode has no ping-pong.
- “Video mode is one-way in the UI; the frame layers are kept.”

`Video.fromAnimation(doc)` produces the initial block; `doc.video` is set, the document is marked dirty and the
normal autosave writes it. There is no undo entry for the metadata, matching `AnimationMeta`. A future command
can drop `video` and return to plain animation without losing frames, but M5 does not expose it.

### 3.6 Coexistence with frame animation and timelapse

- **Animation panel** stays the place to edit frames (add/duplicate/delete, onion skin). When `video != null`,
  the panel hides or disables the fps and playback controls (the video timeline is authoritative) and shows a
  “Video mode: 60 fps” note. Onion skin remains available for drawing.
- **One bottom panel at a time**: opening Video closes Animation and vice versa. The Video panel has a
  “Frames…” action that opens the Animation panel; the Animation panel has a “Video” action that opens the
  timeline.
- `refreshAnimation()` still runs on every layer refresh and keeps the “one visible frame” invariant while
  editing. Video preview temporarily overrides visibility, as animation playback already does; it restores the
  editing frame when it stops.
- **Timelapse** continues to record drawing changes independently. Video playback, scrubbing and export create
  no history entries, so they create no timelapse frames. Video export does not read or delete timelapse frames.
- Both features remain in the same Options menu, clearly named “Timelapse” and “Video mode…”.

## 4. Data model

### 4.1 Core types

New file `core/src/main/kotlin/app/painter/core/doc/Video.kt`:

```kotlin
/** Video timeline is always 60 ticks per second; in a 60 fps export one tick is one output frame. */
const val VIDEO_TICKS_PER_SECOND = 60

data class VideoMeta(
    val tracks: List<VideoTrack> = emptyList(),
    val audio: VideoAudio? = null,
)

data class VideoTrack(
    val id: String,
    val name: String,
    /** Group whose raster children are the frames of this track (for the first track: AnimationMeta.frameGroupId). */
    val groupId: String,
    val visible: Boolean = true,
    val clips: List<VideoClip> = emptyList(),
)

data class VideoClip(
    val id: String,
    /** Frame layer id, or null for a deliberate blank/pause clip. */
    val frameId: String?,
    val startTick: Long,
    val durationTicks: Long,
)

data class VideoAudio(
    val clips: List<AudioClip> = emptyList(),
    /** Track volume, 0..1, applied after every clip gain. */
    val volume: Float = 1f,
    /** Master volume, 0..1, applied after the track volume. */
    val masterVolume: Float = 1f,
    val muted: Boolean = false,
)

data class AudioClip(
    val id: String,
    /** Path relative to the project directory, e.g. "audio/8f3c….m4a". */
    val file: String,
    val name: String,
    val startTick: Long,
    val durationTicks: Long,
    val sourceOffsetTicks: Long = 0,
    /** 0..1. */
    val volume: Float = 1f,
)

object Video {
    fun fromAnimation(doc: Document): VideoMeta?
    fun frames(doc: Document, track: VideoTrack): List<RasterLayer>
    fun durationTicks(meta: VideoMeta): Long
    fun clipAt(track: VideoTrack, tick: Long): VideoClip?
    fun visibleFrames(doc: Document, meta: VideoMeta, tick: Long): Map<String, Boolean>
    fun normalize(meta: VideoMeta, doc: Document, clearOrphaned: Boolean): VideoMeta?
    fun tickAt(step: Int, fps: Int): Long
    fun sequence(frames: Int, playback: AnimationPlayback): List<Int>
}
```

- `VideoMeta` is immutable by design: an edit produces a new tree assigned to `Document.video`. This makes
  snapshots and background reads safe and keeps normalization pure. It is still **not** undoable, like
  `AnimationMeta`.

`Document` gains:

```kotlin
/** Video timeline; null for a document that has not been converted (see spec/VIDEO-MODE.md). */
var video: VideoMeta? = null
```

The save path (`StudioViewModel.save()`) normalizes the video structure with
`doc.video?.let { Video.normalize(it, doc, clearOrphaned = false) }` next to the existing
`Animation.normalize(doc)`. It does **not** drop clips whose frame is missing: autosave can run between a frame
deletion and its undo, and dropping the clip would make the undo incomplete. Load uses `clearOrphaned = true`
(there is no undo history after a reload). The studio refresh also uses `clearOrphaned = false` (see 4.5).

### 4.2 Timebase and conversion from animation

The timeline unit is an integer tick; at 60 fps one tick lasts 1/60 s and tick `n` is timestamped at
`n * 1_000_000 / 60` µs (integer division). Timestamps are computed from the tick index, never accumulated from a
float fps, so a long video cannot drift.

For a source animation at `f` fps (`1..60`), step `i` (a position in the playback sequence) has the cumulative
boundary

```
tickAt(i) = (i * 60 + f / 2) / f        // integer division, rounds half up
duration(i) = tickAt(i + 1) - tickAt(i) // ticks, always >= 1 for f <= 60
```

The total duration of `n` steps is `tickAt(n)`, which is `round(n * 60 / f)`. Examples:

| Source fps | 1 step | 2 steps | 12 steps | Note |
|---|---|---|---|---|
| 60 | 1 | 2 | 12 | one tick per frame |
| 30 | 2 | 4 | 24 | exact |
| 24 | 3 (2.5 rounded) | 5 | 30 | alternates 3/2 ticks, no drift |
| 12 | 5 | 10 | 60 | exact |
| 1 | 60 | 120 | 720 | one source frame per second |

The conversion sequence:

- `LOOP` and `ONCE`: frames `0 .. n-1`, one pass.
- `PINGPONG`: `0, 1, …, n-1, n-2, …, 1` (one cycle, without repeating frame 0 at the end). For `n = 1`:
  `[0]`. For `n = 2`: `[0, 1]`.
- `n = 0`: no conversion.
- Clip ids are deterministic during conversion: `"vclip-<step>-<frameId>"`; the track id is
  `"vtrack-<frameGroupId>"`. Later clips use `newNodeId()`.

The initial track is named with a new “Drawing” string and points at `AnimationMeta.frameGroupId`. The
source `AnimationMeta` is left untouched: old builds that ignore `video` still see an animation at its original
fps (approximate timing), and no frame data is destroyed.

### 4.3 Track and clip semantics

- **Non-overlapping drawing clips.** Within a track, clips are sorted by `startTick`; a later clip cannot start
  before the previous one ends. The UI butt-joints clips. A **gap** (possible in a malformed file) renders
  transparent, and a `frameId = null` clip is a deliberate blank/pause.
- **Duration** of the video is `max(startTick + durationTicks)` over all drawing tracks. Audio never extends the
  video; audio past the end is truncated with a warning.
- A track is visible only if `visible` is true; a hidden track contributes nothing.
- The frame layer's own `visible` flag is **not** the timeline control. The timeline decides which frame of a
  track is visible. Frame-layer eyes in the layer panel keep the animation meaning “make this the editing
  frame”.
- Non-frame layers (backgrounds, overlays, adjustment layers, filter layers) keep their own visibility and are
  composited on every output frame. They are not animated by the timeline in M5.
- `Node.hideInRecording` is honored by the video renderer (a hidden node is excluded from every output frame),
  but **the UI to set it does not exist yet**. It is P1 in S3, with the compositor override; until then the flag
  stays dormant. If it is implemented, timelapse capture should honor the same flag in the same change, otherwise
  the field means two different things.

### 4.4 Audio semantics

- `AudioClip.file` is **relative to the project directory**. Import and recording copy the bytes into
  `audio/`; no absolute URI or external link is stored. Projects stay self-contained across duplicate, archive,
  Drive backup and restore.
- Audio clips may overlap; they are mixed. Clip volume, track `VideoAudio.volume` and `VideoAudio.masterVolume`
  multiply. The final gain is `clip.volume × track.volume × masterVolume`; the mix is clamped to `[-1, 1]` (hard
  clip, no normalization). 0.5 gain is −6 dB if a dB display is added later.
- A clip's source range is `startTick` + `sourceOffsetTicks` for `durationTicks`. If it runs past the end of the
  audio file, the source wraps (loops); a clip that ends before the file end simply plays that range.
- Mixing is at 48 kHz stereo. Mono sources are duplicated to both channels; sources with more than two channels
  are rejected in M5. Unsupported codecs are rejected on import with the codec name in the message, not on
  export after a long render.
- Track mute (`VideoAudio.muted`) or “Include audio” off omits the audio track from the MP4 entirely (fastest
  export, smallest file). Master volume 0 has the same audible result but keeps the track.

### 4.5 Invariants and normalization

`Video.normalize(meta, doc, clearOrphaned)` is pure and returns a new block:

1. Clamp every clip to the non-negative timeline: `end = max(0, startTick + durationTicks)`,
   `startTick = max(0, startTick)`, `durationTicks = end - startTick`; drop clips with `durationTicks == 0`.
2. Sort drawing clips by `startTick`. For a later overlapping clip, move its start to the previous end and
   shorten it; drop it if that leaves no duration. Gaps are preserved.
3. Sort audio clips by `startTick`; overlaps are allowed. Clamp volumes to `0..1`.
4. Keep clips whose frame is missing when `clearOrphaned = false` (the studio and save, so undo can restore the
   frame). When `clearOrphaned = true` (load only), drop clips whose frame no longer exists, and drop tracks whose
   group is missing or has no raster children. Return null if no track remains.
5. Never reorder frames or layers; clip order is independent of `GroupNode.children` after conversion.

Notes:

- The studio refreshes the UI and the save path uses `clearOrphaned = false`, so deleting a frame does not
  silently delete its clips. The clip shows “Frame missing” and renders blank; undo restores both the frame and
  the clip, even if an autosave happened in between.
- Load uses `clearOrphaned = true` because there is no undo history after a reload; a broken reference cannot
  survive a round trip forever.
- `Video.fromAnimation` always returns one track; `normalize` can return null only for a broken empty block.

### 4.6 Manifest

`core/src/main/kotlin/app/painter/core/io/Manifest.kt` gains a top-level optional block:

```json
"video": {
  "tracks": [
    {
      "id": "vtrack-4f2c…",
      "name": "Drawing 1",
      "groupId": "4f2c…",
      "visible": true,
      "clips": [
        { "id": "vclip-0-8a1b…", "frameId": "8a1b…", "startTick": 0, "durationTicks": 300 }
      ]
    }
  ],
  "audio": {
    "volume": 1.0,
    "masterVolume": 1.0,
    "muted": false,
    "clips": [
      {
        "id": "aclip-…",
        "file": "audio/…m4a",
        "name": "Voice",
        "startTick": 0,
        "durationTicks": 600,
        "sourceOffsetTicks": 0,
        "volume": 1.0
      }
    ]
  }
}
```

Rules:

- `video` is absent (`explicitNulls = false`) for every document that has not been converted.
- `MANIFEST_SCHEMA` **stays 2**. The block is additive, the reader already uses `ignoreUnknownKeys = true`, and
  no code validates the schema number. Bumping to 3 would not add migration code and would only break the
  existing schema-2 test. A future breaking manifest change can take schema 3.
- The DTOs mirror the core types one-to-one: `VideoDto`, `VideoTrackDto`, `VideoClipDto`, `VideoAudioDto`,
  `AudioClipDto`. Ticks are `Long`. The writer/reader live next to the existing `AnimationDto` mapping in
  `ProjectIO.kt`.
- **Compatibility:** a document without `video` is unchanged. A document with `video` loads in an older build
  as a normal animation because the old build ignores the unknown block; if the old build re-saves, the `video`
  block is dropped (unknown keys are not preserved), but the frames and `animation` block survive. Older builds
  do **not** see clip edits (trim/move/add), only the original animation. This is the safe degradation; do not
  advertise opening new projects in old builds.

### 4.7 Files on disk

```
project/
  manifest.json                 # + "video"
  layers/<frameId>/…            # unchanged frame pixels
  audio/<uuid>.m4a|mp3|wav|…    # project-owned copies (import and recording)
  timelapse/frames/…            # unchanged, independent
```

- `ProjectIO.OWNED` does **not** include `audio/`, exactly like `timelapse/`. The atomic save/`saveDelta` path
  carries non-owned directories from the old project to the new one (`finishCommit`). Audio is therefore kept by
  full save, incremental autosave, crash recovery and duplicating a project.
- `.pnt` export walks every file in the project directory, so audio and timelapse frames are inside the archive.
  This keeps projects self-contained and makes Drive backup/restore work without a second asset store. The cost
  is archive size; a future “exclude media” export option is a non-goal for M5.
- `audio/<uuid>` uses the original extension where possible (`.m4a`, `.mp3`, `.wav`, `.ogg`, `.flac`); the
  manifest stores only the relative path.

### 4.8 Document copies and the autosave trap

`StudioViewModel.save()`/autosave uses `snapshotDocument(doc, pixels = false)` and `ProjectIO.saveDelta`.
`snapshotDocument` currently copies `animation` but knows nothing about `video`. **The first slice must add
`copy.video = doc.video` there**, otherwise autosave would silently drop the block even though a full manual
save kept it. Because `VideoMeta` is immutable, sharing the reference between the snapshot and the live document
is safe.

Likewise, any future document copy (PSD is not one, but Drive and duplicate are directory copies) must keep the
block. Tests in S1 explicitly cover `saveDelta` and `snapshotDocument`.

## 5. UI

### 5.1 Entry points and conversion

- Options menu (bottom-right ⋮), after “Animation”:
  - `state.video == null` → **“Video mode…”**, enabled only when `Animation.frames(doc).isNotEmpty()`.
  - `state.video != null` → **“Video timeline”**, opens the panel.
- Animation panel header: a **Video** icon/button that converts or opens the timeline.
- Conversion dialog: frame count, resulting duration at 60 fps, the ping-pong expansion note and the one-way
  warning. Confirm → `startVideoMode()`; Cancel → nothing.
- If the frame group is missing (broken project), the entry is disabled with the reason.

### 5.2 Panel layout

A bottom dock panel, consistent with `AnimationPanel` and `PanelSurface`, with a fixed minimum height and a
vertically scrollable track area. From top to bottom:

1. **Header**: title “Video”, “60 fps” label, total duration `mm:ss.cc`, preview-audio toggle, “Frames…”,
   “Export…”, close.
2. **Transport**: rewind-to-start, play/pause, current time, preview-loop toggle (preview only, default off).
3. **Ruler**: seconds marks and labels; tap/drag seeks; pinch zooms the horizontal scale; a vertical drag on the
   ruler switches track height (compact/normal).
4. **Track headers + lanes**:
   - drawing track: eye, name, duration; clips as rounded rectangles with a frame thumbnail and frame name;
   - audio track: speaker/mute, volume, name; clips with a name and a static bar (waveform is a non-goal).
5. **Playhead**: a vertical line across the lanes, auto-scrolled to stay visible (centered while playing).

The panel is a floating overlay above the canvas, like the animation panel. It does not resize the canvas.

### 5.3 Transport and preview

- Playback uses a **60 Hz tick clock**, not `AnimationMeta.fps`: at play start record `startTick` and
  `startElapsedMs`; each UI frame computes `tick = startTick + elapsedMs * 60 / 1000`. If ticks were skipped,
  show the latest one (drop frames, never queue them). A coroutine checks every ~8 ms; the visible update only
  happens when the tick changes.
- The canvas is driven by frame visibility, like animation playback. `showVideoFrame()` sets the visible frame
  of each visible track and `doc.activeId` to the topmost visible frame, updates `state.activeLayerId` and calls
  `canvas.invalidate()`. It does **not** rebuild layer rows every tick; rows refresh on stop.
- **Painting stops playback.** `onStrokeBegin` stops video playback (like animation playback) before it starts
  the stroke, so the stroke lands on the frame on screen. Undo/redo also stop playback.
- Closing the panel, leaving the studio, opening another document or starting a filter stops playback and
  restores the editing frame through `Animation.normalize`/`refreshAnimation()`.
- Preview is best effort: Compose/GL scheduling can miss a 16.7 ms deadline. The export always renders exact
  ticks; the UI does not claim the preview is frame-accurate.
- Playback creates **no history entry** and does **no** `markDirty` just for the frame flips. The final editing
  frame and visibility are persisted like animation playback does today.
- If sound is on, the mixed audio file (see 6.5) is played with one `MediaPlayer` started at
  `playheadTick / 60` seconds, paused/stopped with the timeline and re-seeked on scrub. If it drifts, seek is the
  fallback. Audio preparation shows a “Preparing audio…” state; the user can mute and keep editing.

### 5.4 Tracks and clips

- **Select** a clip by tap; the inspector opens. The selected clip gets an accent border.
- **Add clip** (drawing track header): inserts after the selected clip with the same duration and ripples the
  following clips right; if nothing is selected, inserts at the playhead. It references the same frame as the
  selected clip (a hold) or the next unused frame. The track stays butt-jointed.
- **Add blank clip / pause**: a clip with `frameId = null`, useful as a timed pause. It is an explicit clip, not
  a gap.
- **Delete** removes the clip and ripples the following clips left, so the drawing track stays butt-jointed.
- **Trim**: right edge changes `durationTicks` and ripples the following clips; dragging below one tick deletes
  the clip (the reference's “a clip with duration 0 is deleted”). Left-edge trim and magnetic ripple are P1.
- **Move**: long-press drag within the same drawing track; the clip is removed and reinserted at the target
  position, rippling the others. Moving across tracks is not available with one drawing track; the model/UI must
  enforce “same type, same track” when more tracks exist.
- **Snap**: on by default; targets are the playhead, other clip edges, whole seconds and 0.5 s. The toggle is in
  the header overflow.
- **Zero-length, overlapping, unsorted** data from a file is normalized on load (4.5), never rendered as
  overlapping frames.
- Audio clips: select for volume/duplicate/replace/delete; drag to move; right-edge trim (loop if longer than
  the source); “Snap clip” uses the same targets.

### 5.5 Clip inspector

Drawing clip:

| Field | Behavior |
|---|---|
| Frame | thumbnail, frame name, frame index; “Select frame” seeks the playhead to the clip and makes the frame active for editing |
| Duration | seconds, one decimal; edits `durationTicks` |
| Frame count | integer 60 Hz frames; linked to Duration (`durationTicks`) |
| Duplicate | inserts a copy after the clip |
| Delete | removes the clip |

Audio clip:

| Field | Behavior |
|---|---|
| Name / file | name and a “missing file” warning |
| Volume | 0–100%, clip gain |
| Start / duration | read-only summary; trim on the lane |
| Duplicate | copies with the same source offset |
| Replace file… | SAF picker, replaces the file and probes it again |
| Delete | removes the clip |

### 5.6 Audio UI

- **Import…** uses `ActivityResultContracts.OpenDocument` with `audio/*` (MIME from the provider is validated).
  The bytes are copied to `audio/`; `MediaExtractor` must find an audio track and a supported codec, otherwise
  the copy is deleted and an error names the codec. The clip is inserted at the playhead.
- **Record** uses `MediaRecorder` (AAC-LC in MP4, 48 kHz stereo, 128 kbps) to `audio/rec-<uuid>.m4a`.
  `RECORD_AUDIO` is requested at the first record tap; if denied, the panel explains and keeps import
  available. Recording pauses the video preview; stopping inserts a clip at the playhead with the measured
  duration. Leaving the studio finalizes or discards the recording explicitly (no background recording).
- **Mute**: speaker on the audio track; master volume in the panel header/overflow. The export dialog has an
  “Include audio” switch; off means no audio track at all.
- **Missing file**: the clip shows a warning icon; export refuses with the clip name, or the user replaces/
  deletes it. Because files are project-owned, this is rare (a manually edited project or a partial archive).

### 5.7 Export dialog

Title “Export video”. Content:

- Duration (read-only, from the timeline) and frame count (`durationTicks`).
- Resolution chips: 720p, 1080p (default), 1440p, 4K. A chip is disabled with a reason when the device encoder
  cannot do that size at 60 fps; 4K also gets an explicit **“4K · 30 fps”** chip when 60 fps is unsupported but
  30 fps is. When neither 4K rate works, one disabled **“4K · 60 fps”** chip names the reason. There is no silent
  fps change.
- A 4K chip is also disabled when the memory guard refuses it (see 6.7); the reason shows the estimated working
  set and the device's large heap.
- When the selected plan is 4K and the timeline references more frame layers than `LayerLimit` allows at that
  export size, the dialog explains the derived limit and the frame count. Frames are layers, so a 4K canvas
  cannot hold that many frames; the current canvas can still be exported.
- The timeline is 60 fps; the only alternate output rate is the explicit **4K · 30 fps** fallback. There is no
  generic fps selector and no silent change.
- “Include audio” (disabled when there are no clips, muted, or a file is missing) and a note that the master
  volume applies.
- Estimated size (bitrate × duration, labelled “estimate”) and the warning that the estimate is not a promise.
- Warnings: clips with missing frames render blank; audio beyond the end is truncated.
- Confirm opens `ActivityResultContracts.CreateDocument("video/mp4")` with a suggested name
  `<project>-video.mp4`. The encoder writes a cache temp file first; only a successful encode is streamed to the
  chosen URI.

The dialog itself does not start encoding; it passes a `VideoExportOptions` to the ViewModel, which owns the
job and progress state.

### 5.8 Progress, cancel and errors

- A modal progress dialog: phase (“Preparing audio”, “Rendering frames”, “Writing file”), percent, `encoded /
  total` ticks, elapsed time, Cancel.
- Cancel sets the job's cancellation flag; the encoder loop checks between frames and in its 10 ms dequeue
  timeouts; `finally` stops/releases the codec and muxer and deletes the temp file. The UI reports “Export
  cancelled”.
- On success: “Video exported”. On failure: the phase and a short reason (encoder, storage, audio clip name).
- Save, Home, project switch, undo/redo and strokes are disabled or blocked while `videoBusy` is true. The
  progress dialog is modal; Back cancels.
- Leaving the app/process death is not handled by a service; a stale temp file is deleted on the next export or
  app start. This is documented as a limitation, not a promise.

### 5.9 State and actions (sketch)

`StudioUiState` additions:

```kotlin
val video: VideoUi? = null,          // non-null iff Document.video != null
val videoPanelOpen: Boolean = false,
```

```kotlin
@Immutable data class VideoUi(
    val durationTicks: Long = 0,
    val playheadTick: Long = 0,
    val playing: Boolean = false,
    val loopPreview: Boolean = false,
    val tracks: List<VideoTrackUi> = emptyList(),
    val audio: VideoAudioUi? = null,
)

@Immutable data class VideoTrackUi(
    val id: String, val name: String, val visible: Boolean, val clips: List<VideoClipUi>,
)

@Immutable data class VideoClipUi(
    val id: String, val frameId: String?, val startTick: Long, val durationTicks: Long,
    val label: String, val thumbnail: ImageBitmap?, val missing: Boolean,
)

@Immutable data class VideoAudioUi(
    val muted: Boolean, val volume: Float, val masterVolume: Float, val clips: List<AudioClipUi>,
)

@Immutable data class AudioClipUi(
    val id: String, val name: String, val startTick: Long, val durationTicks: Long,
    val volume: Float, val missing: Boolean,
)
```

`StudioActions` additions (S1 uses only the first four; the rest arrive with their slices):

```kotlin
fun startVideoMode() {}
fun toggleVideoPanel() {}
fun seekVideo(tick: Long) {}
fun selectVideoClip(id: String?) {}
fun setVideoPlaying(playing: Boolean) {}
fun setVideoLoopsPreview(on: Boolean) {}
fun addVideoClip(frameId: String?) {}
fun deleteVideoClip(id: String) {}
fun setVideoClipDuration(id: String, ticks: Long) {}
fun moveVideoClip(id: String, startTick: Long) {}
fun setVideoTrackVisible(id: String, visible: Boolean) {}
fun importAudio() {}
fun startAudioRecording() {}
fun stopAudioRecording() {}
fun deleteAudioClip(id: String) {}
fun setAudioClipVolume(id: String, volume: Float) {}
fun setAudioMasterVolume(volume: Float) {}
fun setAudioMuted(muted: Boolean) {}
fun exportVideo(options: VideoExportOptions) {}
fun cancelVideoExport() {}
```

```kotlin
/** Chosen in the export dialog; the timeline itself stays 60 ticks per second. */
data class VideoExportOptions(
    val longSide: Int = 1080,
    val outputFps: Int = 60,          // 30 only for the explicit 4K/30 fallback
    val includeAudio: Boolean = true,
)
```

### 5.10 Localization and accessibility

- Every new string goes to `values/strings.xml` and `values-ru/strings.xml`; `tools/check_localization.py` must
  stay green (707 EN / 706 RU today, the difference is `app_name`).
- Clip rectangles carry content descriptions with frame name and time; the playhead and transport buttons have
  descriptions; the export progress is announced (`liveRegion`).
- Touch targets follow the existing 36–48 dp controls; the timeline supports pinch and one-finger drag without
  conflicting with canvas gestures (the panel captures its own pointers).

## 6. Rendering and export pipeline

### 6.1 Frame selection at a tick

For a tick `t`, `Video.visibleFrames(doc, meta, t)` returns the set of frame layer ids that must be visible:

1. Start with **every** raster child of **every** track group set to `false` (this prevents a stale visible
   frame from the animation invariant leaking into a clip gap).
2. For each visible track, find the clip covering `t` (`startTick <= t < end`); if it has a `frameId`, set that
   frame to `true`. The track's `groupId` is also mapped to `true`, so a hidden frame group does not blank a
   visible track (ancestor groups keep their own visibility).
3. Walk the whole document and set `false` for every node with `hideInRecording = true` (frames, static layers
   and groups); a hidden group makes its subtree irrelevant.
4. Non-frame nodes without that flag are not in the map; they keep their own visibility.

This is a `Map<String, Boolean>` passed to the compositor.

### 6.2 Compositor visibility override

`Compositor.flatten` is extended with an optional override:

```kotlin
fun flatten(doc: Document, includePaper: Boolean, visible: Map<String, Boolean>? = null): TileGrid
```

`Stack` holds the map and uses `visible?.get(node.id) ?: node.visible` for every child; `isolated`,
`passthrough`, `contentOf` and group recursion use the same stack, so the override applies to nested groups.
The default `null` preserves every existing call site. `flattenNodes`/`flattenInto` get the same optional
parameter for the export path; thumbnails, PSD and timelapse are unchanged.

Tests (JVM, no Android): a hidden frame is not composited, an override can show a frame the document marked
hidden, a group override hides its children, and the default path is bit-identical to the old behavior.

The export **never flips visibility on the live document**. This avoids flicker, avoids marking the document
dirty, and avoids racing the canvas/GL thread.

### 6.3 Rasterization to YUV (no intermediate bitmap)

Rendering one unique frame:

1. `Compositor.flatten(doc, includePaper = false, visible = map)` — the accumulator only allocates painted
   tiles; transparent areas are handled by the rasterizer.
2. A `VideoRasterizer` samples the grid at the output size with bilinear filtering and writes **I420 YUV**
   directly. It composites over the paper color when `doc.paper.visible`, otherwise over black (video has no
   alpha). This avoids a full `ARGB_8888` bitmap and the `IntArray` that `gridToBitmap` allocates today.
3. The YUV buffer is cached for the current *unique* frame; repeated ticks copy it into the encoder input. A
   content fingerprint (frame id + tile versions + visibility signature) decides whether the cache is valid.

Color: BT.709 limited range (Y 16–235, U/V 16–240), `KEY_COLOR_STANDARD = COLOR_STANDARD_BT709`,
`KEY_COLOR_RANGE = COLOR_RANGE_LIMITED`, `KEY_COLOR_TRANSFER = COLOR_TRANSFER_SDR_VIDEO` where the encoder
accepts those keys. The RGB→YUV matrix is a pure function in `core/video/Yuv.kt` and is unit-tested; the existing
timelapse encoder keeps its current BT.601 matrix and its output is not changed by this work.

Assumption: most players honor the BT.709 tags; some ignore them and may show a slight hue shift. This is a
tolerated risk, not measured.

### 6.4 Encoder and muxer

This is a new `VideoExporter` (with its own encoder loop), not a parameterisation of `TimelapseEncoder`: the
timelapse contract is 30 fps JPEG → H.264 → ByteArray, while video needs 60 fps, a visibility-driven frame
source, audio and streaming output. The only shared code is the YUV plane-writing pattern.

Video:

- `MediaCodec.createEncoderByType(MIMETYPE_VIDEO_AVC)` selected through `MediaCodecList` by a
  `VideoCapabilities` check for the exact size/rate (prefer a hardware encoder that supports
  `COLOR_FormatYUV420Flexible`).
- `MediaFormat`: `KEY_COLOR_FORMAT = COLOR_FormatYUV420Flexible`, `KEY_BIT_RATE` from the preset,
  `KEY_FRAME_RATE = 60`, `KEY_I_FRAME_INTERVAL = 1` s. If the codec rejects the color keys, configure again
  without them (log once).
- Input: cached I420 written to `codec.getInputImage(index)` planes with row/pixel strides (the existing
  `TimelapseEncoder.YuvFrame` shows the pattern); fall back to a single I420 `getInputBuffer` when the image is
  null. If the codec reports neither flexible nor a planar format, fail with “The video encoder does not accept
  buffer input on this device”; a surface path is a later optimisation (S5).
- Output: standard `dequeueOutputBuffer` loop. `INFO_OUTPUT_FORMAT_CHANGED` supplies the video track format;
  the muxer is started once both the video and (when present) audio tracks are added (6.5). Write samples,
  `releaseOutputBuffer(false)`. Queue an empty EOS buffer at `durationUs` and drain to EOS.
- Timestamps: `ptsUs = tick * 1_000_000L / 60`. Integer division puts each timestamp within 1 µs of the exact
  value; the error does not accumulate. Duration is `durationTicks * 1_000_000L / 60`.
- Alignment: output width/height are rounded down to the encoder's `getWidthAlignment()`/`getHeightAlignment()`
  (at least 2). This may lose at most a few pixels of aspect; no letterboxing.
- Frames: exactly `durationTicks` output frames at 60 fps (every second tick at 30 fps). Repeated ticks reuse the
  cached YUV; a changed frame triggers a re-render. The single reusable frame is the whole cache (one frame at
  4K), so a hold is encoded without allocating a second buffer; re-rendering a revisited hold is accepted.

Muxer: `MediaMuxer(tempFile.absolutePath, MUXER_OUTPUT_MPEG_4)`. `MediaMuxer` offers MP4, WebM, 3GPP and Ogg
output on modern Android; **there is no QuickTime/MOV output**, so “MOV” in the reference is not implemented.

### 6.5 Audio mix and mux

Audio is pre-rendered once per audio-timeline revision:

1. For each clip, `MediaExtractor` + `MediaCodec` decode to PCM; resample to 48 kHz and convert mono→stereo.
2. Stream the mix in ~1 s chunks (not the whole file in RAM), applying `clip × track × master`, wrapping the
   source when the clip is longer than the file, and hard-clamping.
3. Encode AAC-LC (`MIMETYPE_AUDIO_AAC`, 48 kHz, 2 ch, 192 kbps) into `cacheDir/video-audio/<hash>.m4a` with an
   audio-only `MediaMuxer`. The file length is exactly the video duration (pad/trim).
4. Cache key: SHA-256 of every clip's relative path, size and mtime, start/duration/offset/volume, track
   volume/mute, `masterVolume` and the video duration. Any edit invalidates it. The same file is used by preview
   (`MediaPlayer`) and export, so they cannot disagree.

Export muxing:

- The video encoder's output format arrives on `INFO_OUTPUT_FORMAT_CHANGED`; the audio format is read from the
  mixed M4A with `MediaExtractor`. Add both tracks, start the muxer once, then interleave: after each video
  sample, write every audio sample whose PTS is below the next video PTS. The audio sample's `BufferInfo`
  (offset/size/PTS/flags) comes from the extractor after `readSampleData` into a reusable buffer; the PTS and
  flags are written unchanged, the buffer position/limit are set to the sample.
- If audio is absent/muted, add only the video track.
- Audio encoder priming/padding may put the first audible sample a fraction of an AAC frame late; the
  instrumented test asserts the first sample PTS is 0 (within one AAC frame) and that no audio extends past the
  video duration. No invented tolerance beyond “one AAC frame”.

### 6.6 Progress and cancel

- Phase weights: audio 0–15% (0 if no audio), video 15–95%, finalize 95–100%. Within video, progress is
  `renderedTicks / durationTicks`; report at most every ~250 ms or 1%, whichever is rarer.
- The job runs on `Dispatchers.Default` for rasterization and `Dispatchers.IO` for file/encoder work. The
  `MediaCodec` calls use 10 ms timeouts, so cancellation is responsive even when no buffer is ready.
- `finally`: stop/release codec, stop/release muxer if started, close extractors, delete the temp file on
  cancel/failure. The ViewModel clears `videoBusy` and the progress state.
- The final MP4 is streamed from the cache file to the SAF URI in 1 MiB chunks; cancellation is checked between
  chunks. The cache file is deleted after a successful copy. Cancel/failure before the copy leaves no video bytes
  at the URI, but the system picker may already have created an empty document there; the app attempts to delete
  it and tolerates providers that refuse.

### 6.7 Memory and 4K limits

Per-frame memory (arithmetic, not measured):

| Item | 1080p | 4K UHD |
|---|---|---|
| RGBA16F flattened grid, full canvas | 15.8 MiB | 63.3 MiB |
| I420 YUV buffer | 3.0 MiB | 11.9 MiB |
| Encoder input buffer (one) | ~3.0 MiB | ~11.9 MiB |
| ARGB bitmap (not used by this path) | 7.9 MiB | 31.6 MiB |

The export path avoids the bitmap and flattens **without paper**, so the grid only holds painted tiles; the
display paper color is applied in the rasterizer. Peak is therefore `painted grid + 2 × I420 + encoder buffers`.
At 4K worst case the grid can still approach 63.3 MiB; the YUV pair is 23.8 MiB.

Guards:

- `VideoExportPlan` computes the output size from the canvas aspect and the resolution preset, caps total pixels
  at 8,294,400, rounds to encoder alignment, and rejects any result below 64 px or above 4096 on either side.
- A memory guard estimates `2 × YUV + full-canvas RGBA16F grid + 48 MiB` and refuses 4K when it exceeds half
  of `ActivityManager.largeMemoryClass`. At 3840 × 2160 the estimate is 141,570,048 bytes (135 MiB), so a 256 MB
  large heap is refused and a 512 MB heap is accepted. The exact headroom is device-specific; the dialog shows
  the estimate and the device heap. The guard is applied to the 4K preset only; 720p–1440p are left to the
  encoder and the OS.
- The exporter holds exactly one reusable I420 frame. At 4K that is 11.9 MiB, not a multi-frame cache; the
  memory estimate budgets it together with the encoder input buffer.
- Layer limit: frames are layers, so the existing `LayerLimit` caps the number of frames (6 at 4K with a 512 MB
  large heap, 24 at 1080p). Video mode does not raise it. A 4K project therefore cannot have a long
  frame-by-frame animation today; this is an existing product limit, not a video regression. The export dialog
  derives the same limit from the selected output size and shows it when the timeline references more frame
  layers than fit; it also warns when the current canvas itself is over the limit.
- No `readBytes()` of the MP4 anywhere; the timelapse path's ByteArray pattern is not reused.

### 6.8 Output presets

Design constants, not measurements. “Long side” is the target; the actual size follows the canvas aspect and the
pixel cap.

| Preset | Long side | Default bitrate (H.264) | Notes |
|---|---|---|---|
| 720p | 1280 | 8 Mbps | widest device support |
| 1080p | 1920 | 16 Mbps | default |
| 1440p | 2560 | 24 Mbps | useful when 4K60 is unsupported |
| 4K | 3840 | 40 Mbps | only with 60 fps support and the memory guard; otherwise offer 4K · 30 fps |

1080p at 16 Mbps is 2× the timelapse's 8 Mbps at 1080p/30 fps, matching the doubled frame rate. 4K at 40 Mbps is
2× the timelapse's 20 Mbps. These are starting points; tune them on device and record the result in STATUS, do
not treat them as benchmarks.

The explicit 4K/30 fallback samples the timeline every second tick: output frame `i` shows timeline tick `2i`
and is timestamped at `i * 1_000_000 / 30` µs. Audio is unaffected, and the bitrate stays at 40 Mbps. There is
no 30 fps chip at 720p/1080p/1440p: if 60 fps is unsupported there, that chip is disabled. The 4K fallback is
always labelled as 30 fps, never silent.

### 6.9 Container/codec decision

- Video: H.264/AVC (`video/avc`), 8-bit 4:2:0, SDR, BT.709 limited range.
- Audio: AAC-LC (`audio/mp4a-latm`), 48 kHz stereo, 192 kbps.
- Container: MP4 (`video/mp4`, `.mp4`), `MUXER_OUTPUT_MPEG_4`.
- MOV is **not** implemented. The reference says “MOV/MP4”; Android's muxer has no QuickTime writer, and a
  hand-written MOV muxer is out of scope. The UI says MP4 only.
- HEVC/AV1/WebM/VP9 are not M5. A future WebM path (`MUXER_OUTPUT_WEBM` + VP9/Opus) is possible on the same
  pipeline but would need a second codec profile matrix.

### 6.10 Failure modes and messages

| Failure | Behavior |
|---|---|
| No frames / no clips | Export disabled with “Add at least one clip”. |
| 60 fps unsupported at the chosen size | Chip disabled with the reason; 1080p/720p or explicit 4K·30 remains. |
| 4K refused by the memory guard | 4K chip disabled with the estimated working set and the device large heap; a forced export is refused before the file picker. |
| Encoder configure/start fails | No file is created; “The video encoder could not start (codec/size)”. |
| Codec does not accept buffer input | “This device's encoder needs a surface; 1080p/720p may work.” (S5 may add the surface path.) |
| Muxer write fails / storage full | Cancel, delete temp, “Not enough storage or the file is not writable”. |
| Audio codec unsupported | Import rejects with the codec name; export refuses with the clip name. |
| Missing audio file | Export refuses; “Replace or delete the audio clip”. |
| Missing frame | Clip renders blank; export warns with the clip count. |
| Cancel | Job cancelled, temp deleted; no video bytes written to the user URI. The picker may have created an empty document; the app tries to delete it and tolerates refusal. Cancelling during the copy can leave a partial document (the UI says so). |
| Process death | Temp may remain in `cacheDir`; cleaned on next export/start. No background service. |

## 7. Phased plan

Five slices. Each slice compiles, ships a working APK and has green JVM tests. Instrumented tests are added with
S3/S4. S5 can be cut without affecting the format or the M5 user story.

### S1 — Model, conversion, persistence, read-only timeline

Deliverable: a document can be converted to video mode; the timeline is visible and scrubbable; everything
survives save/load/autosave. No playback, editing or export.

Acceptance:

- Converting an animation creates one track and one clip per playback step; total duration is
  `round(steps * 60 / fps)` ticks; `PINGPONG` expands to `A B C B`; `LOOP`/`ONCE` are one pass.
- `doc.video` round-trips through `ProjectIO.save`/`load`, `saveDelta` and `snapshotDocument`; a document without
  video writes no `video` key; a schema-2 manifest without video loads unchanged.
- The Video panel opens from Options, closes the Animation panel, shows the duration and clips, and scrubbing
  changes only UI state (no history, no timelapse frame, no canvas pixel change).
- Core tests and app JVM tests green; `:app:compileDebugKotlin` green.

Tests: `VideoTest`, `VideoProjectIOTest`, extended `SnapshotTest`.

### S2 — Timeline editing and 60 fps preview

Deliverable: clips can be added, deleted, trimmed, moved and made blank; the canvas previews the timeline at
60 fps; playback stops on painting and restores the editing frame.

Acceptance:

- Add after selection (same duration), delete, right-edge trim (0 deletes), move within the track, blank clip,
  snap on/off; add/delete/trim/move ripple the track and keep it butt-jointed; clips stay sorted and
  non-overlapping after every operation.
- Play/pause/stop/scrub; the canvas shows the clip's frame; the playhead uses the tick clock; painting stops
  playback and edits the frame on screen; undo/redo stops playback.
- Playback makes no history entry and no timelapse frame; closing the panel restores the editing frame; the
  document saves after the final frame selection.

Tests: pure `VideoEditTest` (add/trim/move/normalize), `VideoPlaybackCursorTest` (tick math, loop, clamping);
app JVM test for the UI mapper if it stays pure. Manual check on device/emulator for the canvas.

### S3 — MP4 export without audio

Deliverable: 720p/1080p (and 1440p if the encoder supports it) H.264 MP4 export at 60 fps, with progress and
cancel, streamed to SAF.

Acceptance:

- A 5 s timeline exports exactly 300 frames at 60 fps; `MediaExtractor` sees one `video/avc` track, the chosen
  size, and a duration within one frame of 5 s.
- Export does not allocate the whole MP4 in memory; the cache file is streamed to the picked URI and deleted
  after success.
- Progress reaches 100%, cancel takes effect at the next frame boundary or codec timeout and leaves no temp
  file; the document is not modified, marked dirty, or added to history.
- Encoder-capability failures are reported before the file picker, not after a long render.

Tests: `VideoExportPlanTest` (JVM: sizes/aspect/cap/alignment/bitrate/capability decisions),
`YuvTest` (JVM: matrix and plane strides), `VideoExporterTest` (instrumented: encode 1 s at a small size,
assert track/duration/sample count; cancel test; unsupported-size test with a fake capability).

### S4 — Audio

Deliverable: import/record, clip volume, track mute/volume, master volume; AAC muxing; preview sound from the
same mix.

Acceptance:

- Import copies the file to `audio/`, rejects unsupported codecs with the codec name, and inserts a clip at the
  playhead; recording uses `RECORD_AUDIO`, inserts at the playhead, and handles denial.
- Mixing applies clip × track × master, loops long clips, clamps, and produces a 48 kHz stereo AAC file of the
  video duration; the cache invalidates on every audio edit.
- Export with audio has two tracks (`video/avc`, `audio/mp4a-latm`); muted export has one; A/V starts at 0
  within one AAC frame; audio beyond the video end is truncated.
- Preview sound starts at the playhead and follows pause/stop/seek; muting is instant.

Tests: `AudioMixTest` (JVM: sample mapping, loop wrap, gain, clamp, offsets), `AudioTimelineTest` (JVM:
normalization/overlaps/duration), instrumented `AudioMixerTest` (decode a bundled short asset, encode, inspect
the M4A) and `VideoExporterAudioTest` (muxed tracks, PTS).

### S5 — 4K, capability matrix, performance and optional second track

Deliverable: 4K/30 and 4K/60 where supported, a memory guard, the row/YUV optimizations measured on device, and
(if time) the second drawing track UI.

Status (2026-11-02): the 4K/60 and explicit 4K/30 chips, the 4K memory guard and the derived layer-limit warning
shipped. The second drawing track UI was evaluated and **cut**: the model, `Video.visibleFrames`, the compositor
and the exporter already composite every visible track (covered by a `VideoTest` case), but creating a second
frame group, wiring it into the animation panel and layer rows is a larger change than S5 could ship safely. The
UI therefore stays one-track and the format is ready for a later slice. No 4K throughput number is claimed; the
instrumented 4K smoke skips an unsupported device.

Acceptance:

- The resolution chips reflect `VideoCapabilities` exactly; 4K60 is offered only when supported; 4K30 is
  explicit; no silent fps change.
- The memory guard refuses 4K on low-heap devices with a clear message; 4K export stays within the cache budget;
  a 1080p60 export of a representative document completes on the reference tablet and the timing is recorded in
  STATUS (no number is claimed here).
- Derived 4K layer limits are shown in the export dialog when the timeline references more frame layers than
  the export size can hold; the current-canvas limit is also shown when it is exceeded.
- The model/compositor stay multi-track-ready; the shipped UI is one drawing track and is documented as such.

Tests: `VideoExportPlanTest` extended for the capability matrix, 4K/30 fallback, pixel cap and memory guard;
`LayerLimitTest` covers the derived 4K/1080p limits; instrumented 4K smoke only on a supporting device (skipped
otherwise, never failed on an unsupported device); manual performance notes in STATUS.

## 8. First slice: file-level plan

S1 is deliberately narrow. It touches no `MediaCodec`, no audio playback/recording/export and no timeline
playback; the audio fields exist in the model and DTO from the start so the format is stable when S4 lands.

### 8.1 New files

| File | Contents |
|---|---|
| `core/src/main/kotlin/app/painter/core/doc/Video.kt` | `VIDEO_TICKS_PER_SECOND`, `VideoMeta`, `VideoTrack`, `VideoClip`, `VideoAudio`, `AudioClip`; `Video.fromAnimation`, `frames`, `durationTicks`, `clipAt`, `normalize`, `tickAt`, `sequence`. KDoc points to this spec. |
| `core/src/test/kotlin/app/painter/core/doc/VideoTest.kt` | Conversion for LOOP/ONCE/PINGPONG, fps 1/12/24/30/60, one-frame and empty animations, duration sum, deterministic ids, normalization (sort/overlap/zero), missing-frame retention, `clipAt` boundaries. |
| `core/src/test/kotlin/app/painter/core/io/VideoProjectIOTest.kt` | Save/load round-trip, `saveDelta` keeps the block, no `video` key when null, schema-2 without video, unknown video fields, orphan cleanup on load, audio relative paths (DTO only; no files). |
| `app/src/main/kotlin/app/painter/ui/studio/VideoPanel.kt` | Read-only bottom panel: header, duration, ruler, one drawing lane, clip rectangles, playhead, scrub; no transport buttons yet. Uses the existing `PanelSurface`, `PainterIconButton`, `ToggleChip` where relevant. |

### 8.2 Modified files

| File | Change |
|---|---|
| `core/src/main/kotlin/app/painter/core/doc/Document.kt` | `var video: VideoMeta? = null` with KDoc. No constructor change. |
| `core/src/main/kotlin/app/painter/core/io/Manifest.kt` | `val video: VideoDto? = null` in `ManifestDto`; `VideoDto`, `VideoTrackDto`, `VideoClipDto`, `VideoAudioDto`, `AudioClipDto` (`@Serializable`). |
| `core/src/main/kotlin/app/painter/core/io/ProjectIO.kt` | Read `m.video` into `doc.video` with `Video.normalize(meta, doc, clearOrphaned = true)`; write `doc.video` in `toDto`. No other format change. |
| `app/src/main/kotlin/app/painter/studio/Snapshot.kt` | `copy.video = doc.video` (immutable reference). |
| `app/src/main/kotlin/app/painter/ui/studio/StudioState.kt` | `VideoUi`, `VideoTrackUi`, `VideoClipUi`, `VideoAudioUi`; `video`, `videoPanelOpen` in `StudioUiState`; `startVideoMode`, `toggleVideoPanel`, `seekVideo`, `selectVideoClip` in `StudioActions` with defaults. |
| `app/src/main/kotlin/app/painter/studio/StudioViewModel.kt` | `startVideoMode()` (guards, `commitDocumentEdits`, `Video.fromAnimation`, `markDirty`, close animation panel, open video panel, `refreshVideo`); `toggleVideoPanel` and `toggleAnimationPanel` keep the panels mutually exclusive; `seekVideo` clamped; `refreshVideo()` called from `refreshLayers()` and `setDocument()`; reset the playhead and close the panel on document switch; `save()` normalizes the video structure with `clearOrphaned = false` next to `Animation.normalize`. No playback fields yet. |
| `app/src/main/kotlin/app/painter/ui/studio/StudioScreen.kt` | Options item “Video mode…” / “Video timeline”; show `VideoPanel` when `state.videoPanelOpen`; opening Video closes Animation and vice versa; the animation panel gets a “Video” action. |
| `app/src/main/kotlin/app/painter/ui/studio/AnimationPanel.kt` | A “Video” header action that calls `startVideoMode()` (or `toggleVideoPanel()` when the block exists); no change to playback or fps controls in S1. |
| `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml` | New strings: video title, mode, track name, convert dialog, duration, frames, missing frame, not available hint, panel close/export placeholders. Keep the localization checker green. |
| `app/src/test/kotlin/app/painter/studio/SnapshotTest.kt` | Assert the video block survives a snapshot (and is absent when null). |

No changes in S1 to `Compositor`, `TimelapseEncoder`, `AnimationPanel` playback behavior, Android permissions or
Gradle files.

### 8.3 S1 implementation order

1. Core types + `Video.fromAnimation` + `Video.normalize` + tests (pure, fastest feedback).
2. Manifest DTOs + `ProjectIO` read/write + `Snapshot` + tests.
3. ViewModel state/action + `refreshVideo` mapping (frame ids to labels/thumbnails from `AnimationUi`).
4. `VideoPanel` + `StudioScreen` wiring + strings.
5. Full test run and a manual smoke on the emulator/device: open an animation, convert, inspect the timeline,
   save, reopen, confirm the clips.

### 8.4 S1 acceptance commands

```powershell
. .\tools\env.ps1
.\gradlew.bat :core:test --tests "app.painter.core.doc.VideoTest" --tests "app.painter.core.io.VideoProjectIOTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "app.painter.studio.SnapshotTest" --console=plain
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

The full gate before a release remains `:core:test :app:testDebugUnitTest :app:assembleDebug`.

## 9. Test strategy

### 9.1 JVM (no device)

The majority of the feature is pure and must be tested here:

- **Conversion and timing**: sequence expansion, tick boundaries, no drift, fps that does not divide 60, one
  frame, 999 frames (if the document can hold them), ping-pong endpoints.
- **Editing algebra**: add/trim/move/delete, normalization, gaps vs overlaps, zero duration, clamping, undo of a
  frame deletion keeping the clip.
- **Selection**: `visibleFrames` at boundaries, gap ticks, hidden tracks, multiple clips referencing one frame,
  missing frames.
- **Export plan**: aspect ratios (16:9, 4:3, 1:1, portrait), the 8,294,400 px cap, encoder alignment, minimum
  size, bitrate table, capability decisions with a fake `VideoCapabilities` interface.
- **Color**: RGB→YUV BT.709 matrix, limited-range bounds, plane strides, alpha-over-paper compositing.
- **Audio math**: sample index from tick, loop wrap, source offset, gains, clamp, chunk boundaries, total
  duration, silence padding.
- **Persistence**: full save, `saveDelta`, snapshot, absence/presence of the JSON block, unknown fields, older
  manifests, relative audio paths, orphan cleanup.

### 9.2 Instrumented (device/emulator, API 29+)

Only the Android codec/muxer/file layer needs a device:

- `VideoExporterTest`: encode a short synthetic animation (small size, e.g. 320×240 or 640×360, 60 fps, 1 s) and
  inspect with `MediaExtractor`/`MediaFormat`: one video track, MIME `video/avc`, width/height, sample count,
  duration within one frame; EOS flushes; cancel deletes the temp file.
- `VideoExporterAudioTest`: the same with a generated/short bundled AAC; two tracks; first audio PTS 0 within
  one AAC frame; audio duration ≤ video duration.
- `AudioMixerTest`: decode a short bundled WAV/M4A, mix two overlapping clips with different gains, encode, and
  inspect the result; unsupported codec path produces the expected exception.
- Capability test: a fake `VideoCapabilities` covers the JVM logic; a device test may smoke-test one unsupported
  combination by asserting the error, not by requiring a specific device.

Instrumented tests must be skippable on unsupported hardware (assume/`Assume` on codec availability), not red.

### 9.3 Manual/device checklist

- 1080p60 export of a 5 s animation plays in a desktop player and on the phone; A/V sync with a clap/voice
  recording; mute; missing file; cancel mid-render; screen rotation during export.
- 4K60 on a device that advertises it; 4K30 fallback on one that does not; low-heap device refuses 4K with a
  clear message.
- Save, autosave, duplicate, `.pnt` export/import, Drive backup/restore with audio; older build opens the
  project (video ignored, frames intact).
- Preview: painting stops playback; undo stops playback; closing the panel restores the frame; no timelapse
  frames from playback.

### 9.4 What is not tested

- Exact device thermals, encoder quality, or playback compatibility with every third-party player.
- Compose UI screenshots or gesture automation (the project has no Compose UI test dependency today).
- Process death during export (documented limitation).

## 10. Risks

| Risk | Likelihood / impact | Mitigation |
|---|---|---|
| CPU compositing/YUV is too slow for 4K60 on device | Medium / High | Cache per unique frame; 1080p default; 4K only with capability and memory checks; GPU-surface path in S5 if measurements demand it. PERFORMANCE-M5 measured 2.5–3.8 s for a 10-layer 4K CPU flatten **on a desktop JVM**; device timing is unknown, so no throughput promise is made. |
| Encoder variability (size/rate/color format/alignment) | High / High | Select via `MediaCodecList` + `VideoCapabilities`; align sizes; flexible/I420 support with a clear failure; instrumented matrix; explicit 4K30 fallback. |
| Memory at 4K (grid + YUV + encoder) | Medium / High | No bitmap, no full-video buffer, no-paper flatten, one reusable frame, 4K memory guard, derived 4K layer limit (≤6 with a 512 MB heap). |
| Audio/video sync and AAC priming | Medium / Medium | Offline mix to AAC, trim to the exact duration, interleave by PTS, instrumented PTS assertions, manual clap test. |
| Audio codec/file support varies | Medium / Medium | Validate on import; project-owned copies; precise error naming the clip/codec; optional export without audio. |
| Data loss: autosave drops `video`; old build re-saves without it | Medium / High | S1 explicitly updates `Snapshot`; `saveDelta` tests; old build degrades to the animation block, frames intact; documented. |
| Frame deletion vs clips | Medium / Medium | Keep missing clips while editing **and saving** (blank + warning), prune on load only, undo restores the frame. |
| Preview is not truly 60 fps | Medium / Low | Tick clock with dropped frames; export exact; UI does not promise frame-accurate preview. |
| Project/archive size from audio + timelapse | Medium / Low | Documented; audio is optional; future “exclude media” export is a non-goal for M5. |
| Export interrupted by process death | Medium / Low | Cache temp + cleanup on next start; no foreground service in M5; modal progress and blocked navigation. |
| `hideInRecording` inconsistency (video honors it, timelapse does not) | Low / Low | Implement both in the same change when the UI is added; until then the field stays dormant and video relies on track visibility. |
| MOV expectation from the reference | Low / Low | MP4-only decision stated in the UI and docs; `MediaMuxer` has no MOV writer. |
| 4K “long side 3840” on non-16:9 canvases | Low / Medium | The 8,294,400 px cap and alignment are shown in the dialog; a 4:3 “4K” export is smaller than 3840×2880 by design. |

## 11. Non-goals (explicit)

- MOV/QuickTime, HEVC, AV1, VP9/WebM, ProRes, HDR, 10-bit, alpha, 120 fps, VFR.
- More than one audio track; audio effects, waveform editing, audio from a video, pitch/tempo.
- More than one drawing track in the M5 UI (model supports it; S5 optional), per-track opacity/blend,
  multi-layer frames, layer property animation.
- Transitions, video filters/effects, text overlays, motion blur/interpolation, range export, GIF/WebP.
- Gallery video playback/thumbnails, a New Project video type, cloud rendering, background export service.
- Timelapse→video conversion; using timelapse frames as clips.

## 12. Assumptions and open questions

Assumptions (mark them in code/review if they change):

1. Android's `MediaMuxer` remains the container writer; no custom MOV muxer is added.
2. `MediaCodec` buffer-input (flexible YUV) is available at 1080p60 on the reference device; 4K60 is not
   assumed and is gated by capability checks.
3. A frame remains a single raster/vector layer. Multi-layer frames would need a model change and are out of
   scope.
4. Audio files are project-owned copies, not external links; therefore “relink” is a replace, not a repair of a
   moving URI.
5. The document can be locked against pixel edits for the duration of an export; the UI stays foreground.
6. `Document.type` stays a label; no gallery migration is needed.
7. The 60 fps preview is a UI aid; only the exported file is guaranteed to render every tick at 60 fps (every
   second tick for the explicit 4K/30 fallback).

Open questions for implementation review:

- Is a second drawing track worth the animation-panel complexity in M5, or should it wait? The model is ready
  either way.
- Should `hideInRecording` be exposed together with the timelapse fix, or left dormant for M5?
- Is 1440p worth a chip, or should the fallback be only 1080p60 / 4K30?
- Does the platform encoder accept BT.709/limited-range keys on the reference devices? If not, fall back to the
  current BT.601 matrix and note the color shift.
- Should the audio mix be cached as AAC (small, needs an encoder) or WAV (large, no encoder) for preview? AAC is
  the plan; measure preparation time on device.

## 13. References

- `spec/FEATURE-MAP.md` §24; `spec/ROADMAP.md` M5; `spec/GAPS.md` “Timelapse and animation”.
- `spec/document-model.md` §Animation; `spec/file-formats.md` §Project `.pnt`.
- `core/src/main/kotlin/app/painter/core/doc/Animation.kt`, `Document.kt`, `Nodes.kt`.
- `core/src/main/kotlin/app/painter/core/io/Manifest.kt`, `ProjectIO.kt`; `core/src/main/kotlin/app/painter/core/blend/Compositor.kt`.
- `app/src/main/kotlin/app/painter/timelapse/TimelapseStore.kt`, `TimelapseEncoder.kt`;
  `app/src/main/kotlin/app/painter/ui/studio/TimelapseDialog.kt`, `AnimationPanel.kt`;
  `app/src/main/kotlin/app/painter/studio/StudioViewModel.kt`, `Snapshot.kt`, `LayerLimit.kt`.
- `spec/PERFORMANCE-M5.md` §4.8 (4K layer limit) and §4.9 (history/peak memory); numbers there are desktop JVM
  or derived, not device measurements.
- `features/24-timelapse-animation/ibispaint.md` (video mode reference) and `infinite-painter.md` (timelapse).
