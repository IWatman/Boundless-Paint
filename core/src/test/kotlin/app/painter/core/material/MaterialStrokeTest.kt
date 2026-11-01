package app.painter.core.material

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.geom.Vec2
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.gridHash
import app.painter.core.history.pixel
import app.painter.core.raster.TileGrid
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialStrokeTest {
    private val ink = Rgba(0.86f, 0.32f, 0.20f)
    private val clear = Rgba.TRANSPARENT

    @Test
    fun stampCentresKeepAStableDistanceAcrossSegments() {
        val points = listOf(Vec2(10f, 10f), Vec2(110f, 10f), Vec2(110f, 210f))
        val centres = MaterialStroke.stampCentres(points, 25f)
        assertEquals(13, centres.size)
        assertEquals(points[0], centres[0])
        for (i in 1 until centres.size) {
            val a = centres[i - 1]
            val b = centres[i]
            assertEquals(25f, hypot(b.x - a.x, b.y - a.y), 1e-3f, "stamp $i is off its spacing")
        }
        assertEquals(110f, centres.last().x, 1e-3f)
        assertEquals(210f, centres.last().y, 1e-3f)
    }

    @Test
    fun stampCentresDoNotDependOnHowTheGestureWasSampled() {
        val sparse = listOf(Vec2(0f, 0f), Vec2(200f, 0f), Vec2(200f, 150f))
        val dense = ArrayList<Vec2>()
        for (x in 0..200 step 7) dense.add(Vec2(x.toFloat(), 0f))
        for (y in 0..150 step 7) dense.add(Vec2(200f, y.toFloat()))
        dense.add(Vec2(200f, 150f))
        val a = MaterialStroke.stampCentres(sparse, 23f)
        val b = MaterialStroke.stampCentres(dense, 23f)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].x, b[i].x, 1e-3f, "sample $i x")
            assertEquals(a[i].y, b[i].y, 1e-3f, "sample $i y")
        }
    }

    @Test
    fun aTapStampsOnceAtThePointer() {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        val patch = MaterialStroke.apply(
            doc, layer.id, MaterialSpec(MaterialKind.BALLOON, 128f), ink, listOf(Vec2(128f, 128f)),
        )
        assertNotNull(patch)
        val centre = pixel(layer.pixels, 128, 128)
        assertEquals(1f, centre[3], 1e-3f)
        assertTrue(centre[0] > 0.3f && centre[1] > 0.05f, "the tap did not take the current colour")
        assertEquals(0f, pixel(layer.pixels, 16, 16)[3], 1e-6f)

        val stampDoc = Document.create(256, 256)
        val stampLayer = stampDoc.activeRaster!!
        assertNotNull(MaterialStamp.apply(stampDoc, stampLayer.id, MaterialSpec(MaterialKind.BALLOON, 128f), ink))
        assertEquals(gridHash(stampLayer.pixels), gridHash(layer.pixels), "a tap should match the centre stamp")
    }

    @Test
    fun aDragPaintsAlongTheWholeGesture() {
        val tap = render(MaterialSpec(MaterialKind.FABRIC, 64f), listOf(Vec2(64f, 128f)))
        val drag = render(
            MaterialSpec(MaterialKind.FABRIC, 64f),
            listOf(Vec2(64f, 128f), Vec2(128f, 128f), Vec2(192f, 128f)),
        )
        assertTrue(area(drag) > area(tap) * 2, "the drag covered ${area(drag)} px, the tap ${area(tap)}")
        assertEquals(0f, pixel(tap, 192, 128)[3], 1e-6f)
        assertTrue(pixel(drag, 192, 128)[3] > 0.9f, "the stroke stopped before the end of the gesture")
        assertTrue(pixel(drag, 64, 128)[3] > 0.9f, "the stroke missed its start")
    }

    @Test
    fun spacingChangesHowDenselyTheStrokeIsPainted() {
        val points = listOf(Vec2(40f, 128f), Vec2(216f, 128f))
        val tight = render(MaterialSpec(MaterialKind.STONE, 48f, spacing = 0.2f), points)
        val wide = render(MaterialSpec(MaterialKind.STONE, 48f, spacing = 1f), points)
        assertTrue(changed(tight, wide) > 500, "the spacing control left the stroke unchanged")
    }

    @Test
    fun theStrokeIsDeterministicForEveryMaterial() {
        for (kind in MaterialKind.entries) {
            val spec = MaterialSpec(kind, 96f, 31f, 0.8f, 0.4f)
            val points = listOf(Vec2(64f, 64f), Vec2(160f, 90f), Vec2(192f, 180f))
            assertEquals(gridHash(render(spec, points)), gridHash(render(spec, points)), "$kind is not deterministic")
        }
    }

    @Test
    fun zeroOpacityOrTransparentColourChangesNothing() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        val points = listOf(Vec2(24f, 64f), Vec2(104f, 64f))
        assertNull(MaterialStroke.apply(doc, layer.id, MaterialSpec(MaterialKind.STONE, 64f), clear, points))
        assertNull(
            MaterialStroke.apply(
                doc, layer.id, MaterialSpec(MaterialKind.STONE, 64f, opacity = 0f), ink, points,
            ),
        )
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun selectionClipsTheWholeStroke() {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        paintSelection(doc, 100, 100, 140, 140)
        assertNotNull(
            MaterialStroke.apply(
                doc,
                layer.id,
                MaterialSpec(MaterialKind.FABRIC, 48f),
                ink,
                listOf(Vec2(60f, 120f), Vec2(200f, 120f)),
            ),
        )
        assertTrue(pixel(layer.pixels, 120, 120)[3] > 0.5f, "the stroke missed the selection")
        for (y in 0 until 256) for (x in 0 until 256) {
            if (x in 100 until 140 && y in 100 until 140) continue
            assertEquals(0f, pixel(layer.pixels, x, y)[3], 1e-6f, "the stroke escaped the selection at $x,$y")
        }
    }

    @Test
    fun alphaLockKeepsTheOriginalAlphaAlongTheStroke() {
        val doc = Document.create(192, 192)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 64, 64, 160, 160, 0.1f, 0.3f, 0.8f, 0.5f)
        layer.alphaLock = true
        assertNotNull(
            MaterialStroke.apply(
                doc,
                layer.id,
                MaterialSpec(MaterialKind.STONE, 64f),
                ink,
                listOf(Vec2(60f, 120f), Vec2(180f, 120f)),
            ),
        )
        val inside = pixel(layer.pixels, 110, 120)
        assertEquals(0.5f, inside[3], 1e-3f, "alpha lock did not keep the painted alpha")
        assertTrue(inside[0] + inside[1] > 0.2f, "the alpha-locked pixel did not take the material tone")
        assertEquals(0f, pixel(layer.pixels, 110, 180)[3], 1e-6f, "alpha lock painted on a transparent pixel")
    }

    @Test
    fun lockedLayerIsLeftAlone() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        layer.locked = true
        assertNull(
            MaterialStroke.apply(
                doc,
                layer.id,
                MaterialSpec(MaterialKind.METAL, 64f),
                ink,
                listOf(Vec2(20f, 64f), Vec2(108f, 64f)),
            ),
        )
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun theWholeStrokeIsOneUndoStep() {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 64, 64, 192, 192, 0.2f, 0.4f, 0.6f, 1f)
        val before = pixel(layer.pixels, 128, 128)
        val beforeEdge = pixel(layer.pixels, 20, 20)
        val patch = MaterialStroke.apply(
            doc,
            layer.id,
            MaterialSpec(MaterialKind.FOLIAGE, 96f),
            ink,
            listOf(Vec2(64f, 128f), Vec2(128f, 128f), Vec2(192f, 128f)),
        )
        assertNotNull(patch)
        val after = pixel(layer.pixels, 128, 128)
        assertFalse(same(before, after), "the stroke did not cover the painted square")

        val history = History()
        history.push(TilePatchCommand(patch, "Material stroke"))
        assertEquals(1, history.undoSize)
        assertTrue(history.undo(doc))
        assertTrue(same(before, pixel(layer.pixels, 128, 128)))
        assertTrue(same(beforeEdge, pixel(layer.pixels, 20, 20)))
        assertEquals(0, history.undoSize)

        assertTrue(history.redo(doc))
        assertTrue(same(after, pixel(layer.pixels, 128, 128)))
        assertEquals(1, history.undoSize)
        assertEquals(0, history.redoSize)
    }

    @Test
    fun stampsDoNotEscapeTheCanvas() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        val patch = MaterialStroke.apply(
            doc,
            layer.id,
            MaterialSpec(MaterialKind.WOOD, 64f),
            ink,
            listOf(Vec2(-1000f, 64f), Vec2(1000f, 64f), Vec2(1000f, -1000f)),
        )
        assertNotNull(patch)
        assertEquals(patch.after.keys, layer.pixels.keys())
        for (key in patch.after.keys) {
            assertTrue(layer.pixels.isInBounds(key.tx, key.ty), "the stroke created an out-of-canvas tile $key")
        }
        assertTrue(pixel(layer.pixels, 64, 64)[3] > 0.5f, "the stroke did not cross the canvas")
        assertEquals(0f, pixel(layer.pixels, 2, 2)[3], 1e-6f)
        assertEquals(0f, pixel(layer.pixels, 126, 126)[3], 1e-6f)
    }

    private fun render(spec: MaterialSpec, points: List<Vec2>): TileGrid {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        assertNotNull(MaterialStroke.apply(doc, layer.id, spec, ink, points))
        return layer.pixels
    }

    private fun paintSelection(doc: Document, x0: Int, y0: Int, x1: Int, y1: Int) {
        val mask = doc.selection.mask
        for (y in y0 until y1) for (x in x0 until x1) {
            val tile = mask.getOrCreate(x shr 8, y shr 8)
            tile.data[((y and 255) shl 8) + (x and 255)] = -1
        }
    }

    private fun area(grid: TileGrid): Int {
        var count = 0
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            if (pixel(grid, x, y)[3] > 0.01f) count++
        }
        return count
    }

    private fun changed(a: TileGrid, b: TileGrid): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = pixel(a, x, y)
            val q = pixel(b, x, y)
            if (abs(p[3] - q[3]) > 0.02f || abs(p[0] - q[0]) > 0.02f ||
                abs(p[1] - q[1]) > 0.02f || abs(p[2] - q[2]) > 0.02f
            ) count++
        }
        return count
    }

    private fun same(a: FloatArray, b: FloatArray): Boolean = a.indices.all { abs(a[it] - b[it]) <= 1e-5f }
}
