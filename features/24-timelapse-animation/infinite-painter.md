# Infinite Painter: timelapse (no animation)

Source: User Guide /painter/data/timelapse/.

## Recording
- Only real edits (strokes) are recorded, not the screen. The pace = the drawing time, pauses are cut out. The data is stored in the project.
- Settings → New Project:
  - the video quality (resolution): chosen before creating the project, it does not change afterwards;
  - on/off for new projects.
- The video proportions = the canvas proportions.
- Modes:
  - **Regular** (the canvas is always in frame);
  - **Show navigation** (the video follows zoom, pan, rotate), switchable during recording.
- The UI, cursors and touches are never recorded.
- **Hide when recording** on a layer or a group.
- Record panel:
  - an indicator (a red dot);
  - on/off;
  - the duration and the size estimate;
  - Show navigation;
  - View Playback.
- Project statistics: the number of strokes, the time (by touches), the distance travelled by the strokes, the number of undos.

## Playback
- A scrubber without play/pause, Close, Delete (irreversible).
- **Export**: choosing the clip duration from a list (it depends on the recording length). Several exports of different lengths are possible. To the gallery or to Share.
- The FPS, the codec and the watermark are not specified in the docs.

## Animation
- Absent. We take it from ibisPaint (see ibispaint.md).
