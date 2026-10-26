package app.painter.core.raster

import app.painter.core.doc.Document
import app.painter.core.history.fillRect
import app.painter.core.history.pixel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MaskedEditTest {
    @Test
    fun maskedCommit_blendsThroughMask() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 300, 300, 1f, 0f, 0f, 1f)
        val after = Tile().also { t -> var i = 0; while (i < Tile.CHANNELS) { t.write(i, 0f, 0f, 1f, 1f); i += 4 } }
        val mask = MaskGrid(300, 300)
        mask.getOrCreate(0, 0).data[(10 shl Tile.SHIFT) + 10] = -1
        mask.getOrCreate(0, 0).data[(10 shl Tile.SHIFT) + 11] = 128.toByte()
        val p = assertNotNull(maskedCommit(l, mapOf(TileKey(0, 0) to after, TileKey(1, 0) to after), mask))
        assertEquals(setOf(TileKey(0, 0)), p.after.keys)
        assertEquals(1f, pixel(l.pixels, 10, 10)[2])
        assertEquals(0.5f, pixel(l.pixels, 11, 10)[2], 3e-3f)
        assertEquals(1f, pixel(l.pixels, 12, 10)[0])
        assertEquals(1f, pixel(l.pixels, 260, 10)[0])
    }

    @Test
    fun maskedCommit_nullMaskReplaces_andAlphaLock() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 10, 10, 1f, 0f, 0f, 0.5f)
        val p = maskedPatch(l, mapOf(TileKey(0, 0) to null), null)!!
        assertNull(p.after[TileKey(0, 0)])
        assertEquals(0.5f, pixel(l.pixels, 1, 1)[3], 1e-3f) // patch only, not applied
        val green = Tile().also { t -> var i = 0; while (i < Tile.CHANNELS) { t.write(i, 0f, 1f, 0f, 1f); i += 4 } }
        maskedCommit(l, mapOf(TileKey(0, 0) to green), null, preserveAlpha = true)
        val px = pixel(l.pixels, 1, 1)
        assertEquals(0.5f, px[3], 1e-3f); assertEquals(0.5f, px[1], 1e-3f)
        assertEquals(0f, pixel(l.pixels, 20, 20)[3])
        assertNull(maskedPatch(l, mapOf(TileKey(0, 0) to l.pixels.get(0, 0)), null))
    }
}
