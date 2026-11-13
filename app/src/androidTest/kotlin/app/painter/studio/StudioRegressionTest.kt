package app.painter.studio

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.FilterLayer
import app.painter.core.filter.NoiseParams
import app.painter.core.filter.BlurParams
import app.painter.core.fill.PatternKind
import app.painter.core.fill.PatternPathSpec
import app.painter.core.fill.PatternSpec
import app.painter.core.fill.QuiltSpec
import app.painter.core.material.MaterialSpec
import app.painter.core.material.MaterialKind
import app.painter.core.io.ProjectIO
import app.painter.core.io.SaveTracker
import app.painter.core.raster.MaskTile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.stroke.LayerWorkingSet
import app.painter.core.stroke.TilePool
import app.painter.ui.studio.ToolUi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises real Main/Default/IO dispatchers without touching the user's projects. */
@RunWith(AndroidJUnit4::class)
class StudioRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var models: ViewModelStore
    private lateinit var vm: StudioViewModel

    @Before
    fun createModel() {
        val context = instrumentation.targetContext
        root = File(context.cacheDir, "studio-regression-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val application = TestApplication(isolated)
        instrumentation.runOnMainSync {
            models = ViewModelStore()
            vm = ViewModelProvider(models, StudioViewModel.factory(application))[StudioViewModel::class.java]
            vm.newDocument("Regression", 64, 64)
        }
        awaitSave()
    }

    @After
    fun closeModel() {
        awaitSave()
        instrumentation.runOnMainSync { models.clear() }
        instrumentation.waitForIdleSync()
        root.deleteRecursively()
    }

    @Test
    fun clearingLayerDuringPatternOrMaterialRenderDoesNotResurrectPixels() {
        val operations: List<() -> Unit> = listOf(
            { vm.patternFill(PatternSpec(PatternKind.CHECKS), Rgba.WHITE, Rgba.BLACK, 1f) },
            { vm.patternQuilt(QuiltSpec(), Rgba.WHITE, Rgba.BLACK, 1f) },
            { vm.stampMaterial(MaterialSpec(MaterialKind.FABRIC, size = 32f)) },
            {
                vm.selectMaterial(MaterialSpec(MaterialKind.FABRIC, size = 32f))
                vm.onToolDown(24f, 24f)
                vm.onToolUp(24f, 24f)
            },
            {
                vm.selectPatternPath(PatternPathSpec(PatternKind.CHECKS), Rgba.WHITE, Rgba.BLACK)
                vm.onToolDown(24f, 24f)
                vm.onToolUp(24f, 24f)
            },
        )
        for (operation in operations) {
            instrumentation.runOnMainSync {
                val layer = document().activeRaster!!
                layer.pixels.setPixel(10, 10, 1f, 0f, 0f, 1f)
                operation()
                assertTrue(vm.state.value.filterBusy)
                // Main is occupied by this block: the worker cannot commit before this edit.
                vm.clearLayer(layer.id)
            }
            awaitRender()
            instrumentation.runOnMainSync {
                assertTrue("Stale worker restored cleared pixels", document().activeRaster!!.pixels.keys().isEmpty())
                vm.undo()
                val pixel = FloatArray(4)
                document().activeRaster!!.pixels.getPixel(10, 10, pixel)
                assertEquals("Undo must revert clear, not a stale render", 1f, pixel[0], 0.001f)
            }
        }
    }

    @Test
    fun filterBusyDisablesHistoryAndClearInvalidatesItsResult() {
        instrumentation.runOnMainSync {
            vm.addLayer()
            val layer = document().activeRaster!!
            layer.pixels.setPixel(10, 10, 1f, 0f, 0f, 1f)
            val count = document().layerCount()
            vm.applyFilter(NoiseParams())
            assertTrue(vm.state.value.filterBusy)
            assertFalse("Undo must also look disabled", vm.state.value.canUndo)
            vm.undo()
            assertEquals(count, document().layerCount())
            vm.clearLayer(layer.id)
        }
        awaitRender()
        instrumentation.runOnMainSync {
            assertTrue(document().activeRaster!!.pixels.keys().isEmpty())
            assertTrue(vm.state.value.canUndo)
        }
    }

    @Test
    fun saveAndReopenKeepSuccessiveStrokesSelectionAndFilterLayer() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        val key = TileKey(0, 0)
        val tracker = SaveTracker()
        val dir = File(root, "saved")
        fun stroke(green: Float) {
            val working = LayerWorkingSet(layer.pixels, TilePool())
            working.prepare(key, true)!!.write(0, 1f, green, 0f, 1f)
            working.commit(layer.id)
        }
        stroke(0f)
        val filter = FilterLayer("filter", "Blur", BlurParams(radius = 5f), TileGrid(64, 64))
        doc.root.add(filter)
        doc.selection.mask.put(key, MaskTile().also { it.fill(127.toByte()) })
        doc.selection.active = true
        val initial = prepareDocumentSave(doc, tracker, false, false)
        ProjectIO.save(initial.document, dir, null)
        tracker.commitVersions(initial.versions!!)
        stroke(1f)
        val next = prepareDocumentSave(doc, tracker, true, false)
        ProjectIO.saveDelta(next.document, dir, next.delta!!)
        val loaded = ProjectIO.load(dir)
        val pixel = FloatArray(4)
        (loaded.findNode(layer.id) as app.painter.core.doc.RasterLayer).pixels.getPixel(0, 0, pixel)
        assertEquals(1f, pixel[1], 0.001f)
        assertEquals(127, loaded.selection.mask.byteAt(0, 0))
        assertTrue(loaded.selection.active)
        assertEquals(filter.params, (loaded.findNode(filter.id) as FilterLayer).params)
    }

    @Test
    fun queuedSaveAfterUndoPersistsTheUndoneState() {
        instrumentation.runOnMainSync {
            vm.patternFill(PatternSpec(PatternKind.CHECKS), Rgba.WHITE, Rgba.BLACK, 1f)
        }
        awaitRender()
        var projectId = ""
        instrumentation.runOnMainSync {
            projectId = document().id
            assertFalse(document().activeRaster!!.pixels.keys().isEmpty())
            vm.save()
            vm.undo()
            assertTrue(document().activeRaster!!.pixels.keys().isEmpty())
            vm.save()
        }
        awaitSave()
        val loaded = ProjectIO.load(File(root, "files/projects/$projectId"))
        assertTrue("Undone pixels returned after reopen", loaded.activeRaster!!.pixels.keys().isEmpty())
    }

    @Test
    fun reopeningWaitsForQueuedSaveAndLoadsLatestRevision() = runBlocking {
        val lock = StudioViewModel::class.java.getDeclaredField("ioLock").run {
            isAccessible = true
            get(vm) as Mutex
        }
        lock.lock()
        var lockedByTest = true
        try {
            val id = withContext(Dispatchers.Main) {
                vm.addLayer()
                vm.save()
                document().id
            }
            val opening = async(Dispatchers.Main) { vm.openDocument(id) }
            withContext(Dispatchers.Main) { yield() }
            assertFalse("Open must wait for the queued write", opening.isCompleted)
            lock.unlock()
            lockedByTest = false
            withTimeout(30_000) { opening.await() }
            withContext(Dispatchers.Main) { assertEquals(2, document().layerCount()) }
        } finally {
            if (lockedByTest) lock.unlock()
        }
    }

    @Test
    fun failedSaveKeepsCurrentDocumentAndReportsError() = runBlocking {
        val old = withContext(Dispatchers.Main) {
            vm.addLayer()
            document().also { doc ->
                doc.root.add(app.painter.core.doc.RasterLayer("bad/id", "Invalid", TileGrid(64, 64)))
            }
        }
        val created = withContext(Dispatchers.Main) { vm.createDocument("Other", 64, 64) }
        assertFalse(created)
        withContext(Dispatchers.Main) {
            assertSame(old, document())
            assertNotNull(vm.saveError.value)
            // Restore a valid document so teardown can finish without another intentional failure.
            old.root.children.firstOrNull { it.id == "bad/id" }?.let { old.root.remove(it) }
        }
        Unit
    }

    @Test
    fun laterQueuedSuccessClearsFailureOnlyForItsOwnRevision() = runBlocking {
        val lock = StudioViewModel::class.java.getDeclaredField("ioLock").run {
            isAccessible = true
            get(vm) as Mutex
        }
        lock.lock()
        var lockedByTest = true
        try {
            withContext(Dispatchers.Main) {
                vm.addLayer()
                val doc = document()
                val bad = app.painter.core.doc.RasterLayer("bad/id", "Invalid", TileGrid(64, 64))
                doc.root.add(bad)
                vm.save()
                doc.root.remove(bad)
                vm.addLayer()
                vm.save()
            }
            lock.unlock()
            lockedByTest = false
            awaitSave()
            withContext(Dispatchers.Main) {
                val dirty = StudioViewModel::class.java.getDeclaredField("dirtySinceSave").run {
                    isAccessible = true
                    getBoolean(vm)
                }
                assertFalse("A successful newer snapshot supersedes the failed write", dirty)
                assertEquals(3, ProjectIO.load(File(root, "files/projects/${document().id}")).layerCount())
            }
        } finally {
            if (lockedByTest) lock.unlock()
        }
    }

    @Test
    fun unsupportedVectorOperationsReportErrorWithoutChangingDocument() {
        instrumentation.runOnMainSync {
            vm.addVectorLayer()
            val vectorId = document().activeId!!
            vm.resizeCanvas(96, 96, false, 0.5f, 0.5f)
            assertEquals(64, document().width)
            assertNotNull(vm.saveError.value)
            vm.saveError.value = null
            vm.mergeDown(vectorId)
            assertEquals(2, document().layerCount())
            assertNotNull(vm.saveError.value)
        }
    }

    @Test
    fun staleBucketAndWandCannotOverwriteLaterEdits() {
        instrumentation.runOnMainSync {
            vm.selectTool(ToolUi.FILL)
            vm.onToolDown(10f, 10f)
            vm.onToolUp(10f, 10f)
            vm.addLayer()
        }
        awaitRegion()
        instrumentation.runOnMainSync {
            assertTrue((document().root.children.first() as app.painter.core.doc.RasterLayer).pixels.keys().isEmpty())
            vm.selectTool(ToolUi.SELECT_WAND)
            vm.onToolDown(10f, 10f)
            vm.onToolUp(10f, 10f)
            vm.selectAll()
        }
        awaitRegion()
        instrumentation.runOnMainSync {
            assertEquals(255, document().selection.mask.byteAt(0, 0))
            assertEquals(255, document().selection.mask.byteAt(63, 63))
        }
    }

    private fun document(): Document = StudioViewModel::class.java.getDeclaredField("doc").run {
        isAccessible = true
        get(vm) as Document
    }

    private fun awaitRender() = runBlocking {
        withTimeout(30_000) { vm.state.first { !it.filterBusy } }
    }

    private fun awaitRegion() = runBlocking {
        var job: kotlinx.coroutines.Job? = null
        instrumentation.runOnMainSync {
            job = StudioViewModel::class.java.getDeclaredField("regionJob").run {
                isAccessible = true
                get(vm) as? kotlinx.coroutines.Job
            }
        }
        assertNotNull("Expected a region worker", job)
        withTimeout(30_000) { job!!.join() }
    }

    private fun awaitSave() = runBlocking {
        val job = instrumentation.run {
            var captured: kotlinx.coroutines.Job? = null
            runOnMainSync {
                captured = StudioViewModel::class.java.getDeclaredField("saveJob").run {
                    isAccessible = true
                    get(vm) as? kotlinx.coroutines.Job
                }
            }
            captured
        }
        withTimeout(30_000) { job?.join() }
    }

    private class TestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
    }
}
