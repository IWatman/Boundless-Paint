package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.geom.Vec2
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.gridHash
import app.painter.core.history.pixel
import app.painter.core.raster.IntRect
import app.painter.core.raster.TileGrid
import app.painter.core.raster.alphaBounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternPathTest {
    private val background = Rgba(1f, 0f, 0f)
    private val ink = Rgba(0f, 0f, 1f)

    @Test
    fun spacingIsIndependentOfSampling() {
        val sparse = listOf(Vec2(10f, 40f), Vec2(290f, 40f))
        val dense = List(281) { Vec2(10f + it, 40f) }
        val a = PatternPath.stamps(sparse, 30f, followTangent = true, angleDeg = 0f)
        val b = PatternPath.stamps(dense, 30f, followTangent = true, angleDeg = 0f)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].x, b[i].x, 1e-3f, "x of tile $i")
            assertEquals(a[i].y, b[i].y, 1e-3f, "y of tile $i")
            assertEquals(a[i].distance, b[i].distance, 1e-3f, "distance of tile $i")
            assertEquals(a[i].angleDeg, b[i].angleDeg, 1e-3f, "angle of tile $i")
        }
    }

    @Test
    fun spacingFollowsArcLengthAlongABentPath() {
        val path = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f))
        val stamps = PatternPath.stamps(path, 50f, followTangent = true, angleDeg = 0f)
        val expected = listOf(
            0f to (0f to 0f),
            50f to (50f to 0f),
            100f to (100f to 0f),
            150f to (100f to 50f),
            200f to (100f to 100f),
        )
        assertEquals(expected.size, stamps.size)
        for ((i, pair) in expected.withIndex()) {
            val (distance, point) = pair
            assertEquals(distance, stamps[i].distance, 1e-3f, "arc length of tile $i")
            assertEquals(point.first, stamps[i].x, 1e-3f, "x of tile $i")
            assertEquals(point.second, stamps[i].y, 1e-3f, "y of tile $i")
        }
        assertEquals(0f, stamps[0].angleDeg, 1e-3f)
        assertEquals(0f, stamps[2].angleDeg, 1e-3f, "tile closing the horizontal segment")
        assertEquals(90f, stamps[3].angleDeg, 1e-3f)
        assertEquals(90f, stamps[4].angleDeg, 1e-3f)
    }

    @Test
    fun tangentAnglesFollowThePolyline() {
        val path = listOf(Vec2(0f, 0f), Vec2(120f, 0f), Vec2(120f, 80f))
        val stamps = PatternPath.stamps(path, 20f, followTangent = true, angleDeg = 45f)
        assertTrue(stamps.size > 8)
        for (stamp in stamps) {
            if (stamp.distance < 120f) assertEquals(0f, stamp.angleDeg, 1e-3f, "horizontal tile at ${stamp.distance}")
            if (stamp.distance > 120f) assertEquals(90f, stamp.angleDeg, 1e-3f, "vertical tile at ${stamp.distance}")
        }
        assertEquals(0f, stamps.last().angleDeg - 90f, 1e-3f)
    }

    @Test
    fun fixedAngleIgnoresThePath() {
        val path = listOf(Vec2(5f, 5f), Vec2(50f, 90f), Vec2(140f, 20f))
        val stamps = PatternPath.stamps(path, 25f, followTangent = false, angleDeg = 37f)
        assertTrue(stamps.size > 3)
        for (stamp in stamps) assertEquals(37f, stamp.angleDeg, 1e-3f, "tile at ${stamp.distance}")
        val wrapped = PatternPath.stamps(path, 25f, followTangent = false, angleDeg = 397f)
        for (stamp in wrapped) assertEquals(37f, stamp.angleDeg, 1e-3f)
    }

    @Test
    fun spacingIsClampedToItsRange() {
        val path = listOf(Vec2(0f, 0f), Vec2(1000f, 0f))
        val min = PatternPath.stamps(path, 0f, followTangent = true, angleDeg = 0f)
        val atMin = PatternPath.stamps(path, PatternPathSpec.MIN_SPACING, followTangent = true, angleDeg = 0f)
        assertEquals(atMin.size, min.size)
        assertEquals(atMin[1].x, min[1].x, 1e-3f)
        val max = PatternPath.stamps(path, 10_000f, followTangent = true, angleDeg = 0f)
        val atMax = PatternPath.stamps(path, PatternPathSpec.MAX_SPACING, followTangent = true, angleDeg = 0f)
        assertEquals(atMax.size, max.size)
        assertEquals(atMax[1].x, max[1].x, 1e-3f)
    }

    @Test
    fun emptyAndCoincidentPaths() {
        assertTrue(PatternPath.stamps(emptyList(), 32f, followTangent = true, angleDeg = 0f).isEmpty())
        val coincident = PatternPath.stamps(
            listOf(Vec2(12f, 34f), Vec2(12f, 34f), Vec2(12f, 34f)),
            32f,
            followTangent = true,
            angleDeg = 20f,
        )
        assertEquals(1, coincident.size)
        assertEquals(12f, coincident[0].x, 0f)
        assertEquals(34f, coincident[0].y, 0f)
        assertEquals(0f, coincident[0].angleDeg, 0f)
    }

    @Test
    fun tangentRotatesTheTile() {
        val path = listOf(Vec2(80f, 80f), Vec2(100f, 100f))
        val along = render(PatternPathSpec(PatternKind.DIAGONAL_STRIPES, 256f, 64f, followTangent = true), path)
        val upright = render(PatternPathSpec(PatternKind.DIAGONAL_STRIPES, 256f, 64f, followTangent = false), path)
        assertTrue(changed(along, upright) > 50, "the 45° tangent did not rotate the tile")
    }

    @Test
    fun fixedAngleRotatesTheTile() {
        val path = listOf(Vec2(80f, 80f))
        val upright = render(PatternPathSpec(PatternKind.DIAGONAL_STRIPES, 256f, 64f, followTangent = false), path)
        val tilted = render(
            PatternPathSpec(PatternKind.DIAGONAL_STRIPES, 256f, 64f, followTangent = false, angleDeg = 30f),
            path,
        )
        assertTrue(changed(upright, tilted) > 50, "the fixed angle did not rotate the tile")
    }

    @Test
    fun tileScaleChangesTheFootprint() {
        val path = listOf(Vec2(128f, 128f))
        val small = render(PatternPathSpec(PatternKind.CHECKS, 256f, 16f), path)
        val large = render(PatternPathSpec(PatternKind.CHECKS, 256f, 64f), path)
        val smallCount = countInk(small)
        val largeCount = countInk(large)
        assertTrue(smallCount > 0, "the small tile painted nothing")
        assertTrue(largeCount > smallCount * 5, "the large tile is not larger ($largeCount vs $smallCount)")
        val smallBounds = small.alphaBounds()!!
        val largeBounds = large.alphaBounds()!!
        assertEquals(16, smallBounds.x1 - smallBounds.x0)
        assertEquals(64, largeBounds.x1 - largeBounds.x0)
    }

    @Test
    fun invertSwapsTheTwoColours() {
        val path = listOf(Vec2(48f, 48f))
        val natural = render(PatternPathSpec(PatternKind.CHECKS, 64f, 64f), path)
        val inverted = render(PatternPathSpec(PatternKind.CHECKS, 64f, 64f, invert = true), path)
        val a = pixel(natural, 56, 56)
        val b = pixel(inverted, 56, 56)
        assertTrue(a[2] > 0.8f && a[0] < 0.2f, "the natural tile is not blue")
        assertTrue(b[0] > 0.8f && b[2] < 0.2f, "the inverted tile is not red: ${b[0]}, ${b[1]}, ${b[2]}, ${b[3]}")
        assertEquals(a[3], b[3], 1e-6f)
    }

    @Test
    fun scaleAndOpacityAreClamped() {
        val path = listOf(Vec2(80f, 80f))
        val clampedLow = render(PatternPathSpec(PatternKind.CHECKS, 64f, 1f), path)
        val minScale = render(PatternPathSpec(PatternKind.CHECKS, 64f, PatternPathSpec.MIN_SCALE), path)
        assertEquals(gridHash(minScale), gridHash(clampedLow))
        val clampedHigh = render(PatternPathSpec(PatternKind.CHECKS, 64f, 5000f), path)
        val maxScale = render(PatternPathSpec(PatternKind.CHECKS, 64f, PatternPathSpec.MAX_SCALE), path)
        assertEquals(gridHash(maxScale), gridHash(clampedHigh))
        val opaque = render(PatternPathSpec(PatternKind.DOTS, 64f, 32f, opacity = 4f), path)
        val full = render(PatternPathSpec(PatternKind.DOTS, 64f, 32f, opacity = 1f), path)
        assertEquals(gridHash(full), gridHash(opaque))
    }

    @Test
    fun selectionClipsThePath() {
        val doc = Document.create(200, 200)
        val layer = doc.activeRaster!!
        val mask = doc.selection.mask
        for (y in 0 until 60) for (x in 0 until 60) mask.getOrCreate(0, 0).data[(y shl 8) + x] = -1
        val path = listOf(Vec2(20f, 30f), Vec2(180f, 30f))
        val patch = PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.CHECKS, 40f, 32f), background, ink, path)
        assertNotNull(patch)
        var inside = false
        for (y in 0 until 200) for (x in 0 until 200) {
            val a = pixel(layer.pixels, x, y)[3]
            if (x >= 60 || y >= 60) assertEquals(0f, a, 1e-6f, "outside the selection at $x,$y")
            else if (a > 0f) inside = true
        }
        assertTrue(inside, "the path painted nothing inside the selection")
        assertEquals(0f, pixel(layer.pixels, 150, 30)[3], 1e-6f)
    }

    @Test
    fun alphaLockKeepsTheOriginalAlpha() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 20, 20, 60, 60, 0f, 1f, 0f, 0.5f)
        layer.alphaLock = true
        val before = pixel(layer.pixels, 40, 40).copyOf()
        val path = listOf(Vec2(20f, 40f), Vec2(100f, 40f))
        assertNotNull(PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.CHECKS, 20f, 32f), background, ink, path))
        val inside = pixel(layer.pixels, 40, 40)
        assertEquals(0.5f, inside[3], 1e-3f)
        assertTrue(
            abs(inside[0] - before[0]) + abs(inside[2] - before[2]) > 0.05f,
            "the alpha-locked pixel did not take the pattern colours",
        )
        assertEquals(0f, pixel(layer.pixels, 100, 40)[3], 1e-6f)
    }

    @Test
    fun lockedLayerIsLeftAlone() {
        val doc = Document.create(96, 96)
        val layer = doc.activeRaster!!
        layer.locked = true
        val path = listOf(Vec2(10f, 48f), Vec2(86f, 48f))
        assertNull(PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.DOTS, 24f, 24f), background, ink, path))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun oneGestureIsOneUndoStep() {
        val doc = Document.create(240, 160)
        val layer = doc.activeRaster!!
        val path = listOf(Vec2(20f, 80f), Vec2(220f, 80f))
        val patch = PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.BRICK, 36f, 32f), background, ink, path)
        assertNotNull(patch)
        val after = pixel(layer.pixels, 20, 80)

        val history = History()
        history.push(TilePatchCommand(patch, "Pattern path"))
        assertEquals(1, history.undoSize)
        assertTrue(history.undo(doc))
        assertEquals(0f, pixel(layer.pixels, 20, 80)[3], 1e-6f)
        assertTrue(history.redo(doc))
        assertEquals(after[3], pixel(layer.pixels, 20, 80)[3], 1e-6f)
        assertEquals(1, history.undoSize)
    }

    @Test
    fun tapPlacesOneTile() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        val path = listOf(Vec2(64f, 64f))
        assertNotNull(PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.CHECKS, 64f, 32f), background, ink, path))
        val bounds = layer.pixels.alphaBounds()!!
        assertEquals(IntRect(48, 48, 80, 80), bounds)
    }

    @Test
    fun canvasClippingKeepsEveryPixelInsideTheLayer() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        val path = listOf(Vec2(0f, 32f))
        assertNotNull(PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.CHECKS, 64f, 64f), background, ink, path))
        val bounds = layer.pixels.alphaBounds()!!
        assertEquals(0, bounds.x0)
        assertTrue(bounds.x1 <= 64 && bounds.y1 <= 64)
        assertEquals(32, bounds.x1 - bounds.x0)

        val outside = Document.create(32, 32)
        val outsideLayer = outside.activeRaster!!
        assertNull(
            PatternPath.apply(
                outside,
                outsideLayer.id,
                PatternPathSpec(PatternKind.CHECKS, 64f, 64f),
                background,
                ink,
                listOf(Vec2(-200f, -200f)),
            ),
        )
        assertTrue(outsideLayer.pixels.keys().isEmpty())
    }

    @Test
    fun emptyPathChangesNothing() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        assertNull(PatternPath.apply(doc, layer.id, PatternPathSpec(PatternKind.DOTS, 32f, 32f), background, ink, emptyList()))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun opacityAppliesToTheWholeTile() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        val path = listOf(Vec2(64f, 64f))
        val spec = PatternPathSpec(PatternKind.CHECKS, 64f, 32f, opacity = 0.5f)
        assertNotNull(PatternPath.apply(doc, layer.id, spec, background, ink, path))
        var maxAlpha = 0f
        for (y in 0 until 128) for (x in 0 until 128) maxAlpha = maxOf(maxAlpha, pixel(layer.pixels, x, y)[3])
        assertEquals(0.5f, maxAlpha, 1e-3f)
    }

    @Test
    fun pathIsDeterministic() {
        val spec = PatternPathSpec(PatternKind.HERRINGBONE, 22f, 40f, followTangent = false, angleDeg = 23f, invert = true)
        val path = listOf(Vec2(10f, 10f), Vec2(70f, 30f), Vec2(110f, 90f))
        assertEquals(gridHash(render(spec, path)), gridHash(render(spec, path)))
    }

    private fun render(spec: PatternPathSpec, path: List<Vec2>): TileGrid {
        val doc = Document.create(160, 160)
        val layer = doc.activeRaster!!
        assertNotNull(PatternPath.apply(doc, layer.id, spec, background, ink, path))
        return layer.pixels
    }

    private fun countInk(grid: TileGrid): Int {
        var count = 0
        for (y in 0 until grid.height) for (x in 0 until grid.width) if (pixel(grid, x, y)[3] > 0f) count++
        return count
    }

    private fun changed(a: TileGrid, b: TileGrid): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = pixel(a, x, y)
            val q = pixel(b, x, y)
            if (abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2]) + abs(p[3] - q[3]) > 0.05f) count++
        }
        return count
    }
}
