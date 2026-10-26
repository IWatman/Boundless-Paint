package app.painter.render

import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Builds a 2048×1536 test document for manual renderer checks: a full gradient layer,
 * a MULTIPLY layer of soft circles, and a 75 % opacity layer of rectangles and triangles.
 */
fun createDemoDocument(): Document {
    val w = 2048
    val h = 1536
    val doc = Document(w, h, name = "Render demo")

    val gradient = doc.newRasterLayer("Gradient")
    fill(gradient.pixels) { x, y, out ->
        val u = x / (w - 1f)
        val v = y / (h - 1f)
        out[0] = u; out[1] = 0.4f + 0.4f * v; out[2] = 1f - u; out[3] = 1f
    }

    val circles = doc.newRasterLayer("Circles")
    circles.blendMode = BlendMode.MULTIPLY
    val centers = arrayOf(
        floatArrayOf(500f, 450f, 300f, 1f, 0.2f, 0.2f),
        floatArrayOf(1000f, 800f, 380f, 0.2f, 0.9f, 0.3f),
        floatArrayOf(1550f, 500f, 260f, 0.2f, 0.3f, 1f),
        floatArrayOf(1200f, 1250f, 200f, 1f, 0.8f, 0.1f),
    )
    fill(circles.pixels) { x, y, out ->
        out.fill(0f)
        for (c in centers) {
            val d = hypot(x - c[0], y - c[1])
            val a = ((c[2] - d) / 24f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val inv = 1f - a
            out[0] = c[3] * a + out[0] * inv
            out[1] = c[4] * a + out[1] * inv
            out[2] = c[5] * a + out[2] * inv
            out[3] = a + out[3] * inv
        }
    }

    val shapes = doc.newRasterLayer("Shapes")
    shapes.opacity = 0.75f
    fill(shapes.pixels) { x, y, out ->
        out.fill(0f)
        if (x in 150f..650f && y in 1000f..1400f) {
            out[0] = 0.1f; out[1] = 0.1f; out[2] = 0.1f; out[3] = 1f
        }
        val tx = 1750f
        val ty = 1050f
        if (y in ty..ty + 400f && abs(x - tx) <= (y - ty) * 0.6f) {
            out[0] = 0.9f; out[1] = 0.5f; out[2] = 0f; out[3] = 1f
        }
        if (x in 900f..1100f && y in 80f..280f) {
            out[0] = 0.5f; out[1] = 0f; out[2] = 0.5f; out[3] = 0.5f
        }
    }

    doc.root.add(gradient)
    doc.root.add(circles)
    doc.root.add(shapes)
    doc.activeId = shapes.id
    return doc
}

/** Fills every in-bounds pixel via [px] (premultiplied output) and drops empty tiles. */
private inline fun fill(grid: TileGrid, px: (x: Float, y: Float, out: FloatArray) -> Unit) {
    val c = FloatArray(4)
    for (ty in 0 until grid.tilesY) for (tx in 0 until grid.tilesX) {
        val t = grid.getOrCreate(tx, ty)
        for (ly in 0 until Tile.SIZE) {
            val y = ty * Tile.SIZE + ly
            if (y >= grid.height) break
            for (lx in 0 until Tile.SIZE) {
                val x = tx * Tile.SIZE + lx
                if (x >= grid.width) break
                px(x.toFloat(), y.toFloat(), c)
                val i = t.index(lx, ly)
                t.data[i] = c[0]; t.data[i + 1] = c[1]; t.data[i + 2] = c[2]; t.data[i + 3] = c[3]
            }
        }
        t.markDirty()
    }
    grid.compact()
}
