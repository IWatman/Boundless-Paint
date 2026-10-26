package app.painter.core.blend

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.newNodeId
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositorTest {
    private val w = 300
    private val h = 20

    private fun doc() = Document(w, h)

    private fun layer(d: Document, vararg pixels: Pair<Pair<Int, Int>, FloatArray>): RasterLayer {
        val l = d.newRasterLayer("l")
        for ((xy, c) in pixels) l.pixels.setPixel(xy.first, xy.second, c[0], c[1], c[2], c[3])
        return l
    }

    private fun assertPixel(g: TileGrid, x: Int, y: Int, expected: FloatArray, label: String) {
        val out = FloatArray(4)
        g.getPixel(x, y, out)
        for (i in 0..3) {
            assertTrue(abs(out[i] - expected[i]) <= 1e-3f, "$label: expected ${expected.toList()} but was ${out.toList()}")
        }
    }

    private val cb = floatArrayOf(0.4f, 0.7f, 0.9f, 1f)
    private val cs = floatArrayOf(0.8f, 0.3f, 0.6f, 1f)
    private val clear = floatArrayOf(0f, 0f, 0f, 0f)

    @Test
    fun twoLayersWithOpacityAndPaper() {
        val d = doc()
        d.root.add(layer(d, (5 to 5) to cb, (290 to 3) to cb))
        d.root.add(layer(d, (5 to 5) to cs, (290 to 3) to cs).apply { opacity = 0.5f })
        val flat = Compositor.flatten(d, includePaper = false)
        assertPixel(flat, 5, 5, floatArrayOf(0.6f, 0.5f, 0.75f, 1f), "normal 50%")
        assertPixel(flat, 290, 3, floatArrayOf(0.6f, 0.5f, 0.75f, 1f), "second tile")
        assertPixel(flat, 7, 7, clear, "empty pixel")

        val withPaper = Compositor.flatten(d, includePaper = true)
        assertPixel(withPaper, 7, 7, floatArrayOf(1f, 1f, 1f, 1f), "paper")
        assertPixel(withPaper, 5, 5, floatArrayOf(0.6f, 0.5f, 0.75f, 1f), "layers over paper")

        d.paper.color = Rgba(0.2f, 0.4f, 0.6f)
        d.paper.visible = false
        assertPixel(Compositor.flatten(d, includePaper = true), 7, 7, clear, "hidden paper")
    }

    @Test
    fun blendModeAndHiddenLayer() {
        val d = doc()
        d.root.add(layer(d, (1 to 1) to cb))
        d.root.add(layer(d, (1 to 1) to cs).apply { blendMode = BlendMode.MULTIPLY })
        d.root.add(layer(d, (1 to 1) to floatArrayOf(1f, 0f, 0f, 1f)).apply { visible = false })
        assertPixel(Compositor.flatten(d, false), 1, 1, floatArrayOf(0.32f, 0.21f, 0.54f, 1f), "multiply")
    }

    @Test
    fun clippingUsesBaseAlpha() {
        val d = doc()
        val base = layer(d, (5 to 5) to floatArrayOf(0.2f, 0.2f, 0.2f, 0.5f))
        val green = floatArrayOf(0f, 1f, 0f, 1f)
        val clipped = layer(d, (5 to 5) to green, (6 to 6) to green, (280 to 6) to green).apply { clipped = true }
        d.root.add(base)
        d.root.add(clipped)
        val flat = Compositor.flatten(d, false)
        assertPixel(flat, 5, 5, floatArrayOf(0.1f, 0.6f, 0.1f, 0.75f), "clipped over base")
        assertPixel(flat, 6, 6, clear, "outside base")
        assertPixel(flat, 280, 6, clear, "base tile missing")

        base.visible = false
        assertPixel(Compositor.flatten(d, false), 5, 5, clear, "hidden base hides clipped")
    }

    @Test
    fun clipAlphaParameter() {
        val dst = TileGrid(w, h)
        val src = TileGrid(w, h).apply { setPixel(2, 2, 0.8f, 0.3f, 0.6f, 1f); setPixel(270, 2, 1f, 1f, 1f, 1f) }
        val clip = TileGrid(w, h).apply { setPixel(2, 2, 0f, 0f, 0f, 0.25f) }
        Compositor.compositeInto(dst, src, BlendMode.NORMAL, 1f, clip)
        assertPixel(dst, 2, 2, floatArrayOf(0.2f, 0.075f, 0.15f, 0.25f), "clipped by 25%")
        assertNull(dst.get(1, 0), "fully clipped tile is not created")
    }

    @Test
    fun maskWithTransparentSourceKeepsDestination() {
        val dst = TileGrid(w, h).apply { setPixel(3, 3, 0.4f, 0.7f, 0.9f, 1f); setPixel(4, 4, 0.4f, 0.7f, 0.9f, 1f) }
        val src = TileGrid(w, h).apply { setPixel(4, 4, 0f, 0f, 0f, 1f) }
        Compositor.compositeInto(dst, src, BlendMode.MASK, 1f)
        assertPixel(dst, 3, 3, cb, "transparent mask keeps")
        assertPixel(dst, 4, 4, clear, "black mask hides")
    }

    private fun groupDoc(mode: BlendMode, opacity: Float): Document {
        val d = doc()
        d.root.add(layer(d, (5 to 5) to cb))
        val g = GroupNode(newNodeId(), "g").apply { blendMode = mode; this.opacity = opacity }
        g.add(layer(d, (5 to 5) to cs).apply { blendMode = BlendMode.MULTIPLY })
        d.root.add(g)
        return d
    }

    @Test
    fun passthroughGroupBlendsChildrenWithBackdrop() {
        assertPixel(
            Compositor.flatten(groupDoc(BlendMode.PASSTHROUGH, 1f), false), 5, 5,
            floatArrayOf(0.32f, 0.21f, 0.54f, 1f), "passthrough",
        )
        assertPixel(
            Compositor.flatten(groupDoc(BlendMode.PASSTHROUGH, 0.5f), false), 5, 5,
            floatArrayOf(0.36f, 0.455f, 0.72f, 1f), "passthrough 50%",
        )
    }

    @Test
    fun normalGroupIsIsolated() {
        assertPixel(
            Compositor.flatten(groupDoc(BlendMode.NORMAL, 1f), false), 5, 5,
            floatArrayOf(0.8f, 0.3f, 0.6f, 1f), "isolated group",
        )
        assertPixel(
            Compositor.flatten(groupDoc(BlendMode.NORMAL, 0.5f), false), 5, 5,
            floatArrayOf(0.6f, 0.5f, 0.75f, 1f), "isolated group 50%",
        )
    }

    @Test
    fun groupAsClipBase() {
        val d = doc()
        val g = GroupNode(newNodeId(), "g")
        g.add(layer(d, (5 to 5) to floatArrayOf(0.2f, 0.2f, 0.2f, 0.5f)))
        d.root.add(g)
        val green = floatArrayOf(0f, 1f, 0f, 1f)
        d.root.add(layer(d, (5 to 5) to green, (6 to 6) to green).apply { clipped = true })
        val flat = Compositor.flatten(d, false)
        assertPixel(flat, 5, 5, floatArrayOf(0.1f, 0.6f, 0.1f, 0.75f), "clipped to group")
        assertPixel(flat, 6, 6, clear, "outside group")
    }

    @Test
    fun flattenTileMatchesFlatten() {
        for (mode in listOf(BlendMode.PASSTHROUGH, BlendMode.NORMAL)) for (op in listOf(1f, 0.5f)) {
            val d = groupDoc(mode, op)
            d.root.add(layer(d, (270 to 7) to cs, (5 to 5) to cb).apply { clipped = true; blendMode = BlendMode.SCREEN })
            for (paper in listOf(false, true)) {
                val full = Compositor.flatten(d, paper)
                for (key in full.keys()) {
                    val t = Compositor.flattenTile(d, key, paper)
                    assertTrue(t != null && t.contentEquals(full.get(key)!!), "$mode $op $paper $key")
                }
                if (!paper) assertNull(Compositor.flattenTile(d, app.painter.core.raster.TileKey(0, 5), false))
            }
        }
    }
}
