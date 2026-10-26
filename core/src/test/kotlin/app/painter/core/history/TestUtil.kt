package app.painter.core.history

import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid

/** Structural + pixel fingerprint of a document, for exact state comparison. */
fun fingerprint(doc: Document): String = buildString {
    append("active=").append(doc.activeId).append('\n')
    dump(doc.root, 0)
}

private fun StringBuilder.dump(n: Node, depth: Int) {
    repeat(depth) { append("  ") }
    append(n.id).append('|').append(n.name).append('|').append(n.visible).append('|')
        .append(n.opacity).append('|').append(n.blendMode).append('|').append(n.clipped)
    when (n) {
        is RasterLayer -> append('|').append(n.alphaLock).append('|').append(gridHash(n.pixels))
        is GroupNode -> {}
    }
    append('\n')
    if (n is GroupNode) for (c in n.children) dump(c, depth + 1)
}

fun gridHash(g: TileGrid): String =
    g.entries().sortedBy { it.key.packed }
        .joinToString(";") { "${it.key}:${it.value.data.contentHashCode()}" }

fun fillRect(g: TileGrid, x0: Int, y0: Int, x1: Int, y1: Int, r: Float, gg: Float, b: Float, a: Float) {
    for (y in y0 until y1) for (x in x0 until x1) g.setPixel(x, y, r * a, gg * a, b * a, a)
}

/** Normal source-over on premultiplied tiles; ignores [mode]. */
val normalBlender: (TileGrid, TileGrid, BlendMode, Float) -> Unit = { dst, src, _, opacity ->
    for ((k, s) in src.entries()) {
        val d = dst.getOrCreate(k.tx, k.ty)
        var i = 0
        while (i < s.data.size) {
            val sa = s.data[i + 3] * opacity
            for (c in 0 until 4) d.data[i + c] = s.data[i + c] * opacity + d.data[i + c] * (1f - sa)
            i += 4
        }
        d.markDirty()
    }
}

fun pixel(g: TileGrid, x: Int, y: Int): FloatArray = FloatArray(4).also { g.getPixel(x, y, it) }

const val TILE_BYTES = Tile.SIZE.toLong() * Tile.SIZE * 16
