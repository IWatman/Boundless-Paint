package app.painter.core.io

import app.painter.core.doc.Animation
import app.painter.core.doc.AnimationMeta
import app.painter.core.doc.AudioClip
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Video
import app.painter.core.doc.VideoAudio
import app.painter.core.doc.VideoClip
import app.painter.core.doc.VideoEdit
import app.painter.core.doc.VideoTrack
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence of the video block (spec/VIDEO-MODE.md §4.6–4.8). */
class VideoProjectIOTest {
    private val root: File = createTempDirectory("painter-video-io").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun videoDoc(): Document {
        val doc = Document(32, 32, name = "Video")
        val group = GroupNode("frames", "Frames")
        doc.root.add(group)
        repeat(3) { i -> group.add(doc.newRasterLayer("Frame ${i + 1}")) }
        doc.animation = AnimationMeta(fps = 12, frameGroupId = group.id)
        doc.activeId = Animation.frames(doc).first().id
        Animation.normalize(doc)
        doc.video = Video.fromAnimation(doc, trackName = "Drawing")
        return doc
    }

    private fun withAudio(doc: Document): Document {
        doc.video = doc.video!!.copy(
            audio = VideoAudio(
                clips = listOf(
                    AudioClip("aclip-1", "audio/voice.m4a", "Voice", 0, 600, 0, 0.8f),
                    AudioClip("aclip-2", "audio/music.mp3", "Music", 120, 300, 60, 0.5f),
                ),
                volume = 0.9f,
                masterVolume = 0.7f,
                muted = false,
            ),
        )
        return doc
    }

    /** Adds a second frame group and track; the first track keeps the animation group. */
    private fun withSecondTrack(doc: Document): Document {
        val group = GroupNode("frames-b", "Frames B")
        doc.root.add(group)
        val first = doc.newRasterLayer("B1").also { group.add(it) }
        val second = doc.newRasterLayer("B2").also { group.add(it) }
        val track = VideoTrack(
            id = "vtrack-b",
            name = "Drawing 2",
            groupId = group.id,
            visible = false,
            clips = listOf(
                VideoClip("b-1", first.id, 0, 30),
                VideoClip("b-2", second.id, 30, 60),
            ),
        )
        doc.video = VideoEdit.addTrack(doc.video!!, track)
        doc.activeId = first.id
        return doc
    }

    private fun writeManifest(dir: File, video: String) {
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"schema":2,"app":"painter","id":"v","name":"V","width":16,"height":16,
               "activeId":"f1","animation":{"fps":12,"playback":"loop","frameGroupId":"frames"},
               "nodes":[{"id":"frames","name":"Frames","kind":"group","children":[
                 {"id":"f1","name":"Frame 1","kind":"raster"},
                 {"id":"f2","name":"Frame 2","kind":"raster"}]}],
               "video":$video}""",
        )
    }

    @Test
    fun videoBlockRoundTripsWithAllFields() {
        val doc = withAudio(videoDoc())
        val dir = File(root, "roundtrip")
        ProjectIO.save(doc, dir, null)

        val text = File(dir, "manifest.json").readText()
        assertTrue(text.contains("\"schema\": 2"), "the video block is additive to schema 2")
        assertTrue(text.contains("\"video\""))
        assertTrue(text.contains("\"tracks\""))
        assertTrue(text.contains("\"audio/voice.m4a\""))

        val loaded = ProjectIO.load(dir)
        assertEquals(doc.video, loaded.video)
        assertEquals(doc.animation, loaded.animation)
        assertEquals("audio/voice.m4a", loaded.video!!.audio!!.clips.single { it.id == "aclip-1" }.file)
        assertEquals(0.8f, loaded.video!!.audio!!.clips.single { it.id == "aclip-1" }.volume)

        // A second writer pass must not drift (ids, ordering and ticks are stable).
        ProjectIO.save(loaded, dir, null)
        assertEquals(doc.video, ProjectIO.load(dir).video)
    }

    @Test
    fun secondDrawingTrackRoundTripsAndSurvivesSaveDelta() {
        val doc = withAudio(withSecondTrack(videoDoc()))
        val dir = File(root, "two-tracks")
        ProjectIO.save(doc, dir, null)

        val loaded = ProjectIO.load(dir)
        assertEquals(2, loaded.video!!.tracks.size)
        assertEquals(doc.video, loaded.video)
        assertEquals(listOf("frames", "frames-b"), loaded.video!!.tracks.map { it.groupId })
        assertFalse(loaded.video!!.tracks[1].visible)
        assertEquals(listOf("b-1", "b-2"), loaded.video!!.tracks[1].clips.map { it.id })
        assertEquals("B1", loaded.findNode(loaded.video!!.tracks[1].clips[0].frameId!!)!!.name)

        // Autosave only paints the manifest; both tracks and the audio block must travel together.
        val tracker = SaveTracker()
        tracker.commitAll(doc)
        doc.video = doc.video!!.copy(
            tracks = doc.video!!.tracks.mapIndexed { i, track -> track.copy(name = "T$i") },
        )
        ProjectIO.saveDelta(doc, dir, tracker.collect(doc))
        val afterDelta = ProjectIO.load(dir).video!!
        assertEquals(2, afterDelta.tracks.size)
        assertEquals(listOf("T0", "T1"), afterDelta.tracks.map { it.name })
        assertEquals(2, afterDelta.audio!!.clips.size)
    }

    @Test
    fun oneTrackManifestStillLoadsUnchanged() {
        val dir = File(root, "one-track")
        writeManifest(
            dir,
            """{"tracks":[{"id":"vt","name":"Drawing","groupId":"frames","visible":true,"clips":[
                 {"id":"c1","frameId":"f1","startTick":0,"durationTicks":5},
                 {"id":"c2","frameId":"f2","startTick":5,"durationTicks":5}]}]}""",
        )
        val video = assertNotNull(ProjectIO.load(dir).video)
        assertEquals(1, video.tracks.size)
        val track = video.tracks.single()
        assertEquals("vt", track.id)
        assertEquals("frames", track.groupId)
        assertTrue(track.visible)
        assertEquals(listOf("c1", "c2"), track.clips.map { it.id })
        assertEquals(listOf(0L, 5L), track.clips.map { it.startTick })
    }

    @Test
    fun documentWithoutVideoWritesNoVideoKey() {
        val doc = videoDoc()
        doc.video = null
        val dir = File(root, "plain")
        ProjectIO.save(doc, dir, null)
        val text = File(dir, "manifest.json").readText()
        assertFalse(text.contains("\"video\""))
        assertNull(ProjectIO.load(dir).video)
    }

    @Test
    fun schema2ManifestWithoutVideoLoadsUnchanged() {
        val dir = File(root, "schema2")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"schema":2,"app":"painter","id":"x","name":"Old","width":16,"height":16,
               "activeId":"f1","animation":{"fps":24,"playback":"pingpong","frameGroupId":"frames"},
               "nodes":[{"id":"frames","name":"Frames","kind":"group","children":[
                 {"id":"f1","name":"Frame 1","kind":"raster"},
                 {"id":"f2","name":"Frame 2","kind":"raster"}]}]}""",
        )
        val doc = ProjectIO.load(dir)
        assertNull(doc.video)
        assertEquals(24, doc.animation!!.fps)
        assertEquals(listOf("f1", "f2"), Animation.frames(doc).map { it.id })
        assertEquals("f1", doc.activeId)
    }

    @Test
    fun unknownVideoFieldsAreIgnored() {
        val dir = File(root, "unknown")
        writeManifest(
            dir,
            """{"future":true,"tracks":[{"id":"vt","name":"D","groupId":"frames","futureTrack":1,
                 "clips":[{"id":"c1","frameId":"f1","startTick":0,"durationTicks":10,"futureClip":"x"}]}],
               "audio":{"futureAudio":1,"volume":1.0,"masterVolume":1.0,"muted":false,"clips":[]}}""",
        )
        val doc = ProjectIO.load(dir)
        val video = assertNotNull(doc.video)
        val track = video.tracks.single()
        assertEquals("vt", track.id)
        assertEquals("D", track.name)
        assertEquals(listOf("c1"), track.clips.map { it.id })
        assertEquals(10L, track.clips.single().durationTicks)
        assertNotNull(video.audio)
    }

    @Test
    fun loadDropsClipsWithMissingFrames() {
        val dir = File(root, "missing-frame")
        writeManifest(
            dir,
            """{"tracks":[{"id":"vt","name":"D","groupId":"frames","clips":[
                 {"id":"gone","frameId":"missing","startTick":0,"durationTicks":5},
                 {"id":"kept","frameId":"f1","startTick":5,"durationTicks":5}]}]}""",
        )
        val doc = ProjectIO.load(dir)
        assertEquals(listOf("kept"), doc.video!!.tracks.single().clips.map { it.id })
        assertEquals(5L, doc.video!!.tracks.single().clips.single().startTick)
    }

    @Test
    fun loadDropsTracksWithMissingOrEmptyGroups() {
        val missing = File(root, "missing-group")
        writeManifest(
            missing,
            """{"tracks":[{"id":"vt","name":"D","groupId":"nope","clips":[
                 {"id":"c1","frameId":"f1","startTick":0,"durationTicks":5}]}]}""",
        )
        assertNull(ProjectIO.load(missing).video)

        val empty = File(root, "empty-group")
        empty.mkdirs()
        File(empty, "manifest.json").writeText(
            """{"schema":2,"id":"e","name":"E","width":16,"height":16,
               "nodes":[{"id":"frames","name":"Frames","kind":"group"}],
               "video":{"tracks":[{"id":"vt","name":"D","groupId":"frames","clips":[]}]}}""",
        )
        assertNull(ProjectIO.load(empty).video)
    }

    @Test
    fun saveDeltaKeepsTheVideoBlock() {
        val doc = withAudio(videoDoc())
        val dir = File(root, "delta")
        ProjectIO.save(doc, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(doc)

        // Only video metadata changes: no layer delta, the manifest still travels on every autosave.
        doc.video = doc.video!!.copy(
            tracks = doc.video!!.tracks.map { it.copy(name = "Renamed track", visible = false) },
        )
        val delta = tracker.collect(doc)
        assertTrue(delta.layers.isEmpty(), "no pixels changed")
        ProjectIO.saveDelta(doc, dir, delta)

        val loaded = ProjectIO.load(dir)
        assertEquals(doc.video, loaded.video)
        assertEquals("Renamed track", loaded.video!!.tracks.single().name)
        assertFalse(loaded.video!!.tracks.single().visible)
        assertEquals(2, loaded.video!!.audio!!.clips.size)
    }

    @Test
    fun audioDirectorySurvivesSaveDeltaAndPntExport() {
        val doc = withAudio(videoDoc())
        val dir = File(root, "audio-files")
        ProjectIO.save(doc, dir, null)
        val audioFile = File(dir, "audio/voice.m4a")
        audioFile.parentFile.mkdirs()
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        // An autosave writes a new manifest and carries the non-owned audio directory to the new tree.
        val tracker = SaveTracker()
        tracker.commitAll(doc)
        doc.video = doc.video!!.copy(audio = doc.video!!.audio!!.copy(masterVolume = 0.5f))
        ProjectIO.saveDelta(doc, dir, tracker.collect(doc))
        assertTrue(File(dir, "audio/voice.m4a").isFile, "saveDelta dropped audio/")
        assertEquals(0.5f, ProjectIO.load(dir).video!!.audio!!.masterVolume)

        val archive = File(root, "audio.pnt")
        archive.outputStream().use { ProjectIO.exportPnt(dir, it) }
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("audio/voice.m4a"), ".pnt has no audio entry")
        }
    }
}
