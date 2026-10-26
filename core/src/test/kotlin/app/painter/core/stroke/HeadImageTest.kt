package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.gray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadImageTest {
    /** Asymmetric head: an "L" shape. */
    private val lHead = HeadImage(gray(256, 256) { x, y -> if ((x in 40..90 && y in 30..220) || (x in 40..210 && y in 170..220)) 1f else 0f })

    private fun stamp(head: HeadImage?, d: Float, angle: Float, aa: Boolean = true, aspect: Float = 0f, hardness: Float = 1f): TileGrid {
        val grid = TileGrid(256, 256, precise = true)
        val dab = Dab().apply {
            x = 128f; y = 128f; this.d = d; this.angle = angle; this.hardness = hardness
            val a = aspect
            sx = if (a < 0f) 1f + 0.95f * a else 1f
            sy = if (a > 0f) 1f - 0.95f * a else 1f
        }
        val batch = DabBatch(1).also { it.add().set(dab) }
        val access = BufferAccess(grid, TilePool(), {})
        TileScheduler(1).use { it.run(batch, access, MaskKernel(MaskOp.OVER, head, aa)) }
        return grid
    }

    @Test
    fun mipLevels() {
        val p = lHead.sharp
        assertEquals(9, p.levelCount)
        assertEquals(4, p.level(16f))
        assertEquals(0, p.level(512f))
        assertEquals(0, p.level(256f))
        assertEquals(8, p.level(0.5f))
    }

    @Test
    fun rotationBy90IsExact() {
        val a = stamp(lHead, 160f, 0f)
        val b = stamp(lHead, 160f, (PI / 2).toFloat())
        var maxErr = 0f
        var covered = 0
        for (y in 0 until 256) for (x in 0 until 256) {
            // Rotating the dab by +90° about (128,128) maps the local point (dx, dy) to (−dy, dx).
            val dx = x - 128; val dy = y - 128
            val rx = 128 - dy - 1
            val ry = 128 + dx
            if (rx !in 0 until 256 || ry !in 0 until 256) continue
            val va = alpha(a, x, y)
            if (va > 0f) covered++
            maxErr = maxOf(maxErr, abs(va - alpha(b, rx, ry)))
        }
        assertTrue(covered > 1000)
        assertTrue(maxErr <= 1f / 255f, "max error $maxErr")
    }

    @Test
    fun aspectSquashesHeight() {
        val g = stamp(null, 100f, 0f, aspect = 0.5f)
        val w = (0 until 256).count { alpha(g, it, 128) > 0.5f }
        val h = (0 until 256).count { alpha(g, 128, it) > 0.5f }
        assertTrue(abs(w - 100) <= 2, "width $w")
        assertTrue(abs(h - 52.5f) <= 2f, "height $h")
    }

    @Test
    fun antialiasOffGivesBinaryCoverage() {
        for (head in listOf(null, lHead)) {
            val g = stamp(head, 37.3f, 0.4f, aa = false)
            var ones = 0
            for (y in 0 until 256) for (x in 0 until 256) {
                val v = alpha(g, x, y)
                assertTrue(v == 0f || v == 1f, "partial coverage $v")
                if (v == 1f) ones++
            }
            assertTrue(ones > 50)
        }
    }

    @Test
    fun pixelPenIsOnePixel() {
        val doc = Document.create(64, 64)
        val e = StrokeTestKit.engine(doc, BrushParams(size = 1f, hardness = 1f, head = HeadParams(antialias = false)))
        e.begin(InputPoint(20.3f, 30.8f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        var n = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = alpha(px, x, y)
            assertTrue(v == 0f || v == 1f)
            if (v == 1f) n++
        }
        assertEquals(1, n)
        assertEquals(1f, alpha(px, 20, 30))
    }

    @Test
    fun softnessBlursBitmap() {
        val sharp = stamp(lHead, 200f, 0f, hardness = 1f)
        val soft = stamp(lHead, 200f, 0f, hardness = 0f)
        val edges = { g: TileGrid -> (0 until 256).count { val v = alpha(g, it, 180); v > 0.05f && v < 0.95f } }
        assertTrue(edges(soft) > edges(sharp) + 10)
    }

    @Test
    fun noAliasingAtSmallAndLargeSizes() {
        val disk = HeadImage(gray(256, 256) { x, y -> if ((x - 127.5f) * (x - 127.5f) + (y - 127.5f) * (y - 127.5f) < 120f * 120f) 1f else 0f })
        for (d in listOf(16f, 512f)) {
            val g = TileGrid(1024, 1024, precise = true)
            val batch = DabBatch(1)
            batch.add().apply { x = 512f; y = 512f; this.d = d; hardness = 1f }
            TileScheduler(1).use { it.run(batch, BufferAccess(g, TilePool(), {}), MaskKernel(MaskOp.OVER, disk)) }
            // Monotone radial profile: no moiré rings.
            var prev = 2f
            for (x in 512 until 512 + (d / 2).toInt() + 2) {
                val v = alpha(g, x, 512)
                assertTrue(v <= prev + 1e-4f, "d=$d x=$x $v > $prev")
                prev = v
            }
        }
    }
}
