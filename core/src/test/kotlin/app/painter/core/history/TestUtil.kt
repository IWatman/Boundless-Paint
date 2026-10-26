package app.painter.core.history

import app.painter.core.blend.BlendMode
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid

/** Structural + pixel fingerprint of a document, for exact state comparison. */
fun fingerprint(doc: Document): String = buildString {
    append("active=").append(doc.activeId).append(" target=").append(doc.activeTarget).append('\n')
    dump(doc.root, 0)
}

private fun StringBuilder.dump(n: Node, depth: Int) {
    repeat(depth) { append("  ") }
    append(n.id).append('|').append(n.name).append('|').append(n.visible).append('|')
        .append(n.opacity).append('|').append(n.blendMode).append('|').append(n.clipped)
        .append('|').append(n.colorTag).append('|').append(n.reference).append('|').append(n.locked)
    n.mask?.let { m ->
        append("|mask:").append(m.enabled).append(',').append(m.linked).append(',').append(m.density)
            .append(',').append(m.tiles.defaultValue).append(',')
            .append(m.tiles.entries().sortedBy { it.key.packed }.joinToString(";") { "${it.key}:${it.value.data.contentHashCode()}" })
    }
    when (n) {
        is RasterLayer -> append('|').append(n.alphaLock).append('|').append(gridHash(n.pixels))
        is GroupNode -> append('|').append(n.expanded)
        is AdjustmentLayer -> append('|').append(n.params)
    }
    append('\n')
    if (n is GroupNode) for (c in n.children) dump(c, depth + 1)
}

fun gridHash(g: TileGrid): String =
    g.entries().sortedBy { it.key.packed }
        .joinToString(";") { "${it.key}:${it.value.contentHash()}" }

fun fillRect(g: TileGrid, x0: Int, y0: Int, x1: Int, y1: Int, r: Float, gg: Float, b: Float, a: Float) {
    for (y in y0 until y1) for (x in x0 until x1) g.setPixel(x, y, r * a, gg * a, b * a, a)
}

/** Normal source-over on premultiplied tiles; ignores [mode]. */
val normalBlender: (TileGrid, TileGrid, BlendMode, Float) -> Unit = { dst, src, _, opacity ->
    for ((k, s) in src.entries()) {
        val d = dst.getOrCreate(k.tx, k.ty)
        var i = 0
        while (i < Tile.CHANNELS) {
            val sa = s[i + 3] * opacity
            for (c in 0 until 4) d[i + c] = s[i + c] * opacity + d[i + c] * (1f - sa)
            i += 4
        }
        d.markDirty()
    }
}

fun pixel(g: TileGrid, x: Int, y: Int): FloatArray = FloatArray(4).also { g.getPixel(x, y, it) }

const val TILE_BYTES = Tile.SIZE.toLong() * Tile.SIZE * 16
