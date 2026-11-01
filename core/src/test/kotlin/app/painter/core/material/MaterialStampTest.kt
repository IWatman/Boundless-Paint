package app.painter.core.material

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.gridHash
import app.painter.core.history.pixel
import app.painter.core.raster.TileGrid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialStampTest {
    private val kinds = MaterialKind.entries
    private val ink = Rgba(0.86f, 0.32f, 0.20f)
    private val clear = Rgba.TRANSPARENT

    @Test
    fun samplingIsDeterministicForEveryMaterial() {
        for (kind in kinds) {
            val spec = MaterialSpec(kind, 256f, 33f, 0.8f)
            val first = samples(spec, 96)
            val second = samples(spec, 96)
            assertEquals(first.size, second.size, "$kind sample count")
            for (i in first.indices) {
                assertEquals(first[i][0], second[i][0], 0f, "$kind coverage at $i")
                assertEquals(first[i][1], second[i][1], 0f, "$kind tone at $i")
            }
        }
    }

    @Test
    fun samplingClampsSizeRotationAndOpacity() {
        val sampler = MaterialSampler(MaterialSpec(MaterialKind.WOOD, 9000f, -450f, 4f))
        assertEquals(MaterialSpec.MAX_SIZE, sampler.size)
        assertEquals(270f, sampler.rotationDeg, 1e-4f)
        assertEquals(1f, sampler.opacity)
        val small = MaterialSampler(MaterialSpec(MaterialKind.WOOD, 1f, 720f, -2f))
        assertEquals(MaterialSpec.MIN_SIZE, small.size)
        assertEquals(0f, small.rotationDeg)
        assertEquals(0f, small.opacity)
    }

    @Test
    fun everyMaterialHasAVisibleFootprint() {
        val shaped = setOf(MaterialKind.BALLOON, MaterialKind.STONE, MaterialKind.FOLIAGE, MaterialKind.SPARKLE)
        for (kind in kinds) {
            val values = samples(MaterialSpec(kind, 256f), 96)
            val covered = values.count { it[0] > 0.5f }
            assertTrue(covered > 200, "$kind is almost empty ($covered px)")
            if (kind in shaped) {
                assertTrue(values.count { it[0] < 0.1f } > 100, "$kind has no transparent surround")
            }
            val tones = values.filter { it[0] > 0.2f }.map { it[1] }
            assertTrue(tones.isNotEmpty(), "$kind has no opaque pixels")
            assertTrue(tones.max() - tones.min() > 0.08f, "$kind has a flat tone")
        }
    }

    @Test
    fun everyMaterialDrawsADifferentTexture() {
        val grids = kinds.associateWith { rendering(it, 96) }
        for (first in kinds) for (second in kinds) if (first < second) {
            assertTrue(changed(grids.getValue(first), grids.getValue(second)) > 200, "$first and $second look the same")
        }
    }

    @Test
    fun fillIsDeterministicForEveryMaterial() {
        for (kind in kinds) {
            val spec = MaterialSpec(kind, 128f, 41f, 0.75f)
            assertEquals(gridHash(stamp(spec, ink)), gridHash(stamp(spec, ink)), "$kind is not deterministic")
        }
    }

    @Test
    fun stampChangesTheEmptyLayer() {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        val patch = MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.BALLOON, 128f), ink)
        assertNotNull(patch)
        assertEquals(patch.after.keys, layer.pixels.keys())
        val centre = pixel(layer.pixels, 128, 128)
        assertEquals(1f, centre[3], 1e-3f)
        assertTrue(centre[0] > 0.3f && centre[1] > 0.05f, "the stamp did not take the current colour")
        assertEquals(0f, pixel(layer.pixels, 10, 10)[3], 1e-6f)
        assertEquals(0f, pixel(layer.pixels, 250, 250)[3], 1e-6f)
    }

    @Test
    fun sizeChangesHowMuchTheStampCovers() {
        val small = stamp(MaterialSpec(MaterialKind.FABRIC, 64f), ink)
        val large = stamp(MaterialSpec(MaterialKind.FABRIC, 160f), ink)
        val smallArea = area(small)
        val largeArea = area(large)
        assertTrue(largeArea > smallArea * 4, "the larger stamp covers $largeArea px, the smaller $smallArea")
        assertEquals(0f, pixel(small, 128, 180)[3], 1e-6f, "the small stamp reached beyond its footprint")
        assertTrue(pixel(large, 128, 180)[3] > 0.5f, "the large stamp did not reach the probe")
    }

    @Test
    fun rotationChangesTheTexture() {
        val upright = stamp(MaterialSpec(MaterialKind.WOOD, 192f, 0f), ink)
        val rotated = stamp(MaterialSpec(MaterialKind.WOOD, 192f, 37f), ink)
        assertTrue(changed(upright, rotated) > 500, "the rotation left the wood grain untouched")
        assertTrue(pixel(upright, 128, 128)[3] > 0.9f && pixel(rotated, 128, 128)[3] > 0.9f)
        assertTrue(area(upright) > 30000 && area(rotated) > 30000, "the rotated stamp lost its body")
    }

    @Test
    fun opacityScalesTheStamp() {
        val full = stamp(MaterialSpec(MaterialKind.FABRIC, 128f, opacity = 1f), ink)
        val half = stamp(MaterialSpec(MaterialKind.FABRIC, 128f, opacity = 0.35f), ink)
        val a = pixel(full, 128, 128)
        val b = pixel(half, 128, 128)
        assertEquals(1f, a[3], 1e-3f)
        assertEquals(0.35f, b[3], 1e-3f, "opacity did not scale the alpha")
        for (channel in 0..2) assertEquals(a[channel] * 0.35f, b[channel], 2e-3f, "opacity did not scale channel $channel")
        assertEquals(area(full), area(half), "opacity changed the footprint")
    }

    @Test
    fun transparentColourOrZeroOpacityChangesNothing() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        assertNull(MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.STONE, 64f), clear))
        assertNull(MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.STONE, 64f, opacity = 0f), ink))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun selectionClipsAndCentresTheStamp() {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        paintSelection(doc, 200, 200, 240, 240)
        assertNotNull(MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.FABRIC, 64f), ink))
        assertTrue(pixel(layer.pixels, 220, 220)[3] > 0.5f, "the stamp missed the selection centre")
        for (y in 0 until 256) for (x in 0 until 256) {
            if (x in 200 until 240 && y in 200 until 240) continue
            assertEquals(0f, pixel(layer.pixels, x, y)[3], 1e-6f, "the stamp escaped the selection at $x,$y")
        }
        assertEquals(0f, pixel(layer.pixels, 128, 128)[3], 1e-6f, "the stamp stayed at the canvas centre")
    }

    @Test
    fun alphaLockKeepsTheOriginalAlpha() {
        val doc = Document.create(192, 192)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 64, 64, 128, 128, 0.1f, 0.3f, 0.8f, 0.5f)
        layer.alphaLock = true
        assertNotNull(MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.STONE, 96f), ink))
        val inside = pixel(layer.pixels, 96, 96)
        assertEquals(0.5f, inside[3], 1e-3f, "alpha lock did not keep the painted alpha")
        assertTrue(inside[0] + inside[1] > 0.2f, "the alpha-locked pixel did not take the material tone")
        assertEquals(0f, pixel(layer.pixels, 96, 136)[3], 1e-6f, "alpha lock painted on a transparent pixel")
    }

    @Test
    fun lockedLayerIsLeftAlone() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        layer.locked = true
        assertNull(MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.METAL, 96f), ink))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun undoRedoRestoresThePreviousPixels() {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 80, 80, 176, 176, 0.2f, 0.4f, 0.6f, 1f)
        val before = pixel(layer.pixels, 128, 128)
        val patch = MaterialStamp.apply(doc, layer.id, MaterialSpec(MaterialKind.FOLIAGE, 128f), ink)
        assertNotNull(patch)
        val after = pixel(layer.pixels, 128, 128)
        assertFalse(same(before, after), "the material did not cover the painted square")

        val history = History()
        history.push(TilePatchCommand(patch, "Material"))
        assertEquals(1, history.undoSize)
        assertTrue(history.undo(doc))
        assertTrue(same(before, pixel(layer.pixels, 128, 128)))
        assertEquals(0f, pixel(layer.pixels, 20, 20)[3], 1e-6f)

        assertTrue(history.redo(doc))
        assertTrue(same(after, pixel(layer.pixels, 128, 128)))
        assertEquals(1, history.undoSize)
    }

    private fun stamp(spec: MaterialSpec, color: Rgba): TileGrid {
        val doc = Document.create(256, 256)
        val layer = doc.activeRaster!!
        assertNotNull(MaterialStamp.apply(doc, layer.id, spec, color))
        return layer.pixels
    }

    private fun rendering(kind: MaterialKind, size: Int): FloatArray {
        val sampler = MaterialSampler(MaterialSpec(kind, size.toFloat()))
        val half = sampler.size * 0.5f
        val out = FloatArray(size * size * 2)
        val sample = FloatArray(2)
        for (y in 0 until size) for (x in 0 until size) {
            sampler.sample(
                (x + 0.5f) / size * 2f * half - half,
                (y + 0.5f) / size * 2f * half - half,
                sample,
            )
            val i = (y * size + x) * 2
            out[i] = sample[0]
            out[i + 1] = sample[1]
        }
        return out
    }

    private fun samples(spec: MaterialSpec, resolution: Int): List<FloatArray> {
        val sampler = MaterialSampler(spec)
        val half = sampler.size * 0.5f
        val out = FloatArray(2)
        return ArrayList<FloatArray>(resolution * resolution).also { list ->
            for (y in 0 until resolution) for (x in 0 until resolution) {
                sampler.sample(
                    (x + 0.5f) / resolution * 2f * half - half,
                    (y + 0.5f) / resolution * 2f * half - half,
                    out,
                )
                list.add(out.copyOf())
            }
        }
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
            if (abs(p[3] - q[3]) > 0.02f || abs(p[0] - q[0]) > 0.02f || abs(p[1] - q[1]) > 0.02f ||
                abs(p[2] - q[2]) > 0.02f
            ) count++
        }
        return count
    }

    private fun changed(a: FloatArray, b: FloatArray): Int = a.indices.count { abs(a[it] - b[it]) > 0.02f }

    private fun same(a: FloatArray, b: FloatArray): Boolean = a.indices.all { abs(a[it] - b[it]) <= 1e-5f }
}
