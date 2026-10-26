package app.painter.core.blend

import app.painter.core.adjust.HueSaturationParams
import app.painter.core.adjust.InvertParams
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.newNodeId
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class CompositorM2Test {
    private val w = 300
    private val h = 20

    private fun solid(d: Document, r: Float, g: Float, b: Float, a: Float = 1f, x0: Int = 0, x1: Int = w): RasterLayer {
        val l = d.newRasterLayer("l")
        for (y in 0 until h) for (x in x0 until x1) l.pixels.setPixel(x, y, r * a, g * a, b * a, a)
        return l
    }

    /** Mask with default [def]; pixel x < 10 of tile (0,0) set to [left] if given. */
    private fun mask(def: Int, left: Int? = null, density: Float = 1f): LayerMask {
        val g = MaskGrid(w, h, def.toByte())
        if (left != null) {
            val t = g.getOrCreate(TileKey(0, 0))
            for (y in 0 until h) for (x in 0 until 10) t.data[y * 256 + x] = left.toByte()
        }
        return LayerMask(g).also { it.density = density }
    }

    private fun px(g: TileGrid, x: Int, y: Int = 1) = FloatArray(4).also { g.getPixel(x, y, it) }

    private fun assertPx(expected: List<Float>, actual: FloatArray, label: String, tol: Float = 2e-3f) {
        for (i in 0..3) assertTrue(abs(expected[i] - actual[i]) <= tol, "$label: expected $expected but was ${actual.toList()}")
    }

    private fun assertTileEquivalence(d: Document) {
        val flat = Compositor.flatten(d, includePaper = true)
        for (tx in 0 until flat.tilesX) {
            val t = Compositor.flattenTile(d, TileKey(tx, 0), includePaper = true)!!
            assertTrue(t.contentEquals(flat.get(tx, 0)!!), "flattenTile($tx) differs")
        }
    }

    @Test
    fun compositeIntoWithMaskDensityAndDefaults() {
        val dst = TileGrid(w, h)
        val src = TileGrid(w, h).also { for (x in 0 until w) it.setPixel(x, 1, 1f, 0f, 0f, 1f) }
        Compositor.compositeInto(dst, src, BlendMode.NORMAL, 1f, mask = mask(0, left = 255).tiles)
        assertPx(listOf(1f, 0f, 0f, 1f), px(dst, 5), "revealed")
        assertPx(listOf(0f, 0f, 0f, 0f), px(dst, 50), "hidden in tile")
        assertPx(listOf(0f, 0f, 0f, 0f), px(dst, 280), "missing tile, default 0")

        val dst2 = TileGrid(w, h)
        Compositor.compositeInto(dst2, src, BlendMode.NORMAL, 1f, mask = mask(255, left = 0).tiles, maskDensity = 0.5f)
        assertPx(listOf(0.5f, 0f, 0f, 0.5f), px(dst2, 5), "density 0.5 over hidden")
        assertPx(listOf(1f, 0f, 0f, 1f), px(dst2, 280), "missing tile, default 255")
    }

    @Test
    fun addWithPartialBackdropAlpha() {
        val d = floatArrayOf(0.25f, 0.1f, 0f, 0.5f)
        BlendMath.blendPixel(BlendMode.ADD, d, 0, floatArrayOf(0.4f, 0.4f, 0.4f, 0.5f), 0, 1f)
        // C = aS(1-aB)Cs + aB·min(1, Cb + Cs·aS): Cb = (0.5, 0.2, 0), Cs = 0.8.
        assertPx(listOf(0.25f * 0.8f + 0.5f * 0.9f, 0.2f + 0.5f * 0.6f, 0.2f + 0.5f * 0.4f, 0.75f), d, "add")
    }

    @Test
    fun rasterAndIsolatedGroupMask() {
        val d = Document(w, h)
        d.root.add(solid(d, 0f, 0f, 1f))
        val top = solid(d, 1f, 0f, 0f).also { it.mask = mask(255, left = 0) }
        d.root.add(top)
        var flat = Compositor.flatten(d, false)
        assertPx(listOf(0f, 0f, 1f, 1f), px(flat, 5), "masked out")
        assertPx(listOf(1f, 0f, 0f, 1f), px(flat, 50), "revealed")
        top.mask!!.enabled = false
        assertPx(listOf(1f, 0f, 0f, 1f), px(Compositor.flatten(d, false), 5), "disabled mask")

        d.root.remove(top)
        val g = GroupNode(newNodeId(), "g").also { it.blendMode = BlendMode.NORMAL; it.mask = mask(0, left = 255) }
        g.add(solid(d, 1f, 0f, 0f))
        d.root.add(g)
        flat = Compositor.flatten(d, false)
        assertPx(listOf(1f, 0f, 0f, 1f), px(flat, 5), "group revealed")
        assertPx(listOf(0f, 0f, 1f, 1f), px(flat, 50), "group hidden")
        assertTileEquivalence(d)
    }

    @Test
    fun passthroughGroupMask() {
        val d = Document(w, h)
        d.root.add(solid(d, 0f, 0f, 1f))
        val g = GroupNode(newNodeId(), "g").also { it.mask = mask(255, left = 0) }
        g.add(solid(d, 1f, 0f, 0f, 0.5f))
        g.add(AdjustmentLayer(newNodeId(), "inv", InvertParams()))
        d.root.add(g)
        val flat = Compositor.flatten(d, false)
        assertPx(listOf(0f, 0f, 1f, 1f), px(flat, 5), "hidden part keeps below")
        // inside: red 50% over blue = (0.5, 0, 0.5), then inverted = (0.5, 1, 0.5)
        assertPx(listOf(0.5f, 1f, 0.5f, 1f), px(flat, 50), "revealed part")
        g.mask!!.density = 0.5f
        assertPx(listOf(0.25f, 0.5f, 0.75f, 1f), px(Compositor.flatten(d, false), 5), "half density lerp")
        assertTileEquivalence(d)
    }

    @Test
    fun clipToBaseWithMask() {
        val d = Document(w, h)
        val base = solid(d, 0f, 0f, 1f).also { it.mask = mask(255, left = 0) }
        d.root.add(base)
        d.root.add(solid(d, 1f, 0f, 0f).also { it.clipped = true })
        val flat = Compositor.flatten(d, false)
        assertPx(listOf(0f, 0f, 0f, 0f), px(flat, 5), "clip follows base mask")
        assertPx(listOf(1f, 0f, 0f, 1f), px(flat, 50), "clipped shown")
        assertTileEquivalence(d)
    }

    @Test
    fun clipToPassthroughGroup() {
        val d = Document(w, h)
        val g = GroupNode(newNodeId(), "g")
        g.add(solid(d, 0f, 0f, 1f, x0 = 0, x1 = 100))
        d.root.add(g)
        d.root.add(solid(d, 1f, 0f, 0f).also { it.clipped = true })
        val flat = Compositor.flatten(d, false)
        assertPx(listOf(1f, 0f, 0f, 1f), px(flat, 50), "inside group alpha")
        assertPx(listOf(0f, 0f, 0f, 0f), px(flat, 150), "outside group alpha")
    }

    @Test
    fun adjustmentScopeIsolatedVsPassthrough() {
        val d = Document(w, h)
        d.root.add(solid(d, 0.2f, 0.4f, 0.6f))
        val g = GroupNode(newNodeId(), "g").also { it.blendMode = BlendMode.NORMAL }
        g.add(solid(d, 1f, 0f, 0f, x0 = 0, x1 = 10))
        g.add(AdjustmentLayer(newNodeId(), "inv", InvertParams()))
        d.root.add(g)
        var flat = Compositor.flatten(d, false)
        assertPx(listOf(0.2f, 0.4f, 0.6f, 1f), px(flat, 50), "isolated: below untouched")
        assertPx(listOf(0f, 1f, 1f, 1f), px(flat, 5), "isolated: group content inverted")
        g.blendMode = BlendMode.PASSTHROUGH
        flat = Compositor.flatten(d, false)
        assertPx(listOf(0.8f, 0.6f, 0.4f, 1f), px(flat, 50), "passthrough: below inverted")
        assertTileEquivalence(d)
    }

    @Test
    fun clippedAdjustmentStaysInBaseAlpha() {
        val d = Document(w, h)
        d.root.add(solid(d, 0.2f, 0.4f, 0.6f))
        d.root.add(solid(d, 1f, 1f, 1f, a = 0.5f, x0 = 0, x1 = 10))
        d.root.add(AdjustmentLayer(newNodeId(), "inv", InvertParams()).also { it.clipped = true })
        val flat = Compositor.flatten(d, false)
        assertPx(listOf(0.2f, 0.4f, 0.6f, 1f), px(flat, 50), "outside base untouched")
        // acc = (0.6, 0.7, 0.8); f = alpha of base 0.5 -> lerp to inverted (0.4, 0.3, 0.2)
        assertPx(listOf(0.5f, 0.5f, 0.5f, 1f), px(flat, 5), "inside base half strength")
    }

    @Test
    fun adjustmentKeepsTransparentAndAlpha() {
        val d = Document(w, h)
        d.root.add(solid(d, 1f, 0f, 0f, a = 0.5f, x0 = 0, x1 = 10))
        d.root.add(AdjustmentLayer(newNodeId(), "inv", InvertParams()).also { it.opacity = 0.5f })
        d.root.add(AdjustmentLayer(newNodeId(), "hue", HueSaturationParams(lightness = 100f)))
        val flat = Compositor.flatten(d, false)
        assertPx(listOf(0f, 0f, 0f, 0f), px(flat, 50), "transparent stays")
        assertTrue(flat.get(1, 0) == null, "no tile created")
        assertPx(listOf(0.5f, 0.5f, 0.5f, 0.5f), px(flat, 5), "alpha kept")
    }

    @Test
    fun adjustmentIsNoClipBase() {
        val d = Document(w, h)
        d.root.add(solid(d, 0f, 0f, 1f))
        d.root.add(AdjustmentLayer(newNodeId(), "u", HueSaturationParams()))
        d.root.add(solid(d, 1f, 0f, 0f).also { it.clipped = true })
        assertPx(listOf(0f, 0f, 1f, 1f), px(Compositor.flatten(d, false), 5), "clipped over adjustment not drawn")
    }

    @Test
    fun adjustmentOpacityZeroAndMaskedAdjustment() {
        val d = Document(w, h)
        d.root.add(solid(d, 0.2f, 0.4f, 0.6f))
        val adj = AdjustmentLayer(newNodeId(), "inv", InvertParams()).also { it.opacity = 0f }
        d.root.add(adj)
        assertPx(listOf(0.2f, 0.4f, 0.6f, 1f), px(Compositor.flatten(d, false), 5), "opacity 0")
        adj.opacity = 1f
        adj.mask = mask(0, left = 255)
        val flat = Compositor.flatten(d, false)
        assertPx(listOf(0.8f, 0.6f, 0.4f, 1f), px(flat, 5), "mask reveals")
        assertPx(listOf(0.2f, 0.4f, 0.6f, 1f), px(flat, 50), "mask hides")
        assertTileEquivalence(d)
    }
}
