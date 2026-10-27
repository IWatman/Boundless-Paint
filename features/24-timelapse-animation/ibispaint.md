# ibisPaint: timelapse and animation

Source: ibispaint.com/lecture №20, 22, 182, 188, 210, 211.

## Timelapse
- It is always recorded (the IPV history). Playback in the gallery.
- **Save Artwork at This Point**: from any point of the timelapse → a new artwork.
- Export of the video to the photo album and social networks. YouTube upload.
- The drawing time is shown.

## Animation mode (an Animation canvas)
- **FPS** on creation.
- The **Animation bar**: + frame, drag = the order, a tap = frame settings (**Duration**, Duplicate, Delete).
- **Onion skin**:
  - Display Mode (past/future);
  - Number of Onion Skins;
  - First Opacity, Last Opacity (the result = the product);
  - Color.
- Play: **Loop / Ping pong / One-shot**.
- Save Animation Video.

## Video mode (conversion from Animation, irreversible)
- 60 fps fixed. A clip with a duration of 0 is deleted. Ping-pong is unavailable.
- Timeline: the playhead in the center, a time ruler, pinch or Ctrl+wheel = zoom, dragging the ruler = the height of the area.
- Tracks:
  - a **Drawing track** (clips butt up against each other, no gaps; an empty clip = a pause);
  - an **Audio track** (clips are free);
  - at least 1 drawing track.
- A clip:
  - Add (after the selected one, the same length);
  - a long-press drag (within the tracks of the same type);
  - a handle = the duration.
  - Drawing clip settings: Duration, Frame Count.
  - Audio clip settings: Volume, Duplicate, **Reselect** (relink), Delete.
- Audio:
  - import of audio or of the audio track from a video;
  - a range > the file = looping;
  - Track: Mute, Volume, Delete;
  - **Snap Clip**;
  - a global Volume;
  - the final loudness = clip + track + master.
  - A broken link warning.
- Export MOV/MP4, the IPVZ project.

## For the merged implementation
- IP has no animation. We take ibis's model entirely and make the UI in IP's style: a bottom timeline widget + a mini-toolbar.
