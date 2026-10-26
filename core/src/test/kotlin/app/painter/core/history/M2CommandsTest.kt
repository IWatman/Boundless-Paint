package app.painter.core.history

import app.painter.core.adjust.BrightnessContrastParams
import app.painter.core.adjust.InvertParams
import app.painter.core.blend.BlendMode
import app.painter.core.blend.Compositor
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.ColorTag
import app.painter.core.doc.Document
import app.painter.core.doc.EditTarget
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskPatch
import app.painter.core.raster.MaskTile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M2CommandsTest {
    /** root: [L1, G{A, B}, L2], active = L2. */
    private class Fixture {
        val doc = Document.create(300, 40)
        val l1 = doc.activeRaster!!.also { it.name = "L1" }
        val g = GroupNode("g", "Group")
        val a = doc.newRasterLayer("A")
        val b = doc.newRasterLayer("B")
        val l2 = doc.newRasterLayer("L2")
        val h = History()

        init {
            doc.root.add(g); g.add(a); g.add(b); doc.root.add(l2)
            fillRect(l1.pixels, 0, 0, 300, 20, 1f, 0f, 0f, 1f)
            fillRect(a.pixels, 5, 5, 40, 40, 0f, 1f, 0f, 0.5f)
            fillRect(b.pixels, 20, 0, 30, 10, 0f, 0f, 1f, 1f)
            fillRect(l2.pixels, 260, 0, 270, 10, 0f, 0f, 1f, 1f)
            doc.activeId = l2.id
        }

        fun ids(vararg n: app.painter.core.doc.Node) = n.map { it.id }
    }

    private fun roundTrip(f: Fixture, cmd: Command, check: () -> Unit = {}) {
        val before = fingerprint(f.doc)
        f.h.execute(f.doc, cmd)
        val after = fingerprint(f.doc)
        check()
        f.h.undo(f.doc)
        assertEquals(before, fingerprint(f.doc))
        f.h.redo(f.doc)
        assertEquals(after, fingerprint(f.doc))
        f.h.undo(f.doc)
        assertEquals(before, fingerprint(f.doc))
    }

    private fun near(e: Float, v: Float, tol: Float = 2e-3f) = assertTrue(kotlin.math.abs(e - v) <= tol, "expected $e, was $v")

    @Test
    fun groupSameParent() {
        val f = Fixture()
        roundTrip(f, GroupNodes(f.ids(f.l2, f.l1), "New", "ng")) {
            val ng = f.doc.findNode("ng") as GroupNode
            assertEquals(listOf(f.g.id, "ng"), f.doc.root.children.map { it.id })
            assertEquals(listOf(f.l1.id, f.l2.id), ng.children.map { it.id })
            assertEquals(BlendMode.PASSTHROUGH, ng.blendMode)
            assertTrue(ng.expanded)
        }
    }

    @Test
    fun groupDifferentParentsGoesToTopPlace() {
        val f = Fixture()
        roundTrip(f, GroupNodes(f.ids(f.a, f.l1), "New", "ng")) {
            assertEquals(listOf(f.g.id, f.l2.id), f.doc.root.children.map { it.id })
            val ng = f.doc.findNode("ng")!!
            assertEquals(f.g, ng.parent)
            assertEquals(listOf("ng", f.b.id), f.g.children.map { it.id })
            assertEquals(listOf(f.l1.id, f.a.id), (ng as GroupNode).children.map { it.id })
        }
    }

    @Test
    fun ungroupKeepsOrderAndAppliesMask() {
        val f = Fixture()
        f.a.mask = LayerMask(MaskGrid(300, 40, -1).also { it.getOrCreate(TileKey(0, 0)).data.fill(0x80.toByte()) })
        f.g.mask = LayerMask(MaskGrid(300, 40, 0))
        roundTrip(f, Ungroup(f.g.id)) {
            assertEquals(listOf(f.l1.id, f.a.id, f.b.id, f.l2.id), f.doc.root.children.map { it.id })
            assertNull(f.doc.findNode(f.g.id))
            assertEquals(0, f.b.mask!!.tiles.defaultValue.toInt())
            assertEquals(0, f.a.mask!!.tiles.byteAt(1, 1))
        }
        roundTrip(f, Ungroup(f.g.id, UngroupMaskPolicy.DISCARD)) { assertNull(f.b.mask) }
    }

    @Test
    fun moveNodesKeepsOrder() {
        val f = Fixture()
        roundTrip(f, MoveNodes(f.ids(f.l2, f.l1), f.g.id, 1)) {
            assertEquals(listOf(f.a.id, f.l1.id, f.l2.id, f.b.id), f.g.children.map { it.id })
            assertEquals(listOf(f.g.id), f.doc.root.children.map { it.id })
        }
        roundTrip(f, MoveNodes(f.ids(f.b, f.a), null, 0)) {
            assertEquals(listOf(f.a.id, f.b.id, f.l1.id, f.g.id, f.l2.id), f.doc.root.children.map { it.id })
        }
    }

    @Test
    fun moveGroupIntoDescendantFails() {
        val f = Fixture()
        val inner = GroupNode("inner", "Inner").also { f.g.add(it) }
        assertFailsWith<IllegalArgumentException> { MoveNodes(listOf(f.g.id, f.l1.id), inner.id, 0).apply(f.doc) }
        assertEquals(f.doc.root, f.l1.parent)
    }

    @Test
    fun deleteNodesMulti() {
        val f = Fixture()
        roundTrip(f, DeleteNodes(f.ids(f.l2, f.a, f.l1))) {
            assertEquals(listOf(f.g.id), f.doc.root.children.map { it.id })
            assertEquals(listOf(f.b.id), f.g.children.map { it.id })
            assertEquals(f.g.id, f.doc.activeId)
        }
        roundTrip(f, DeleteNodes(f.ids(f.g, f.a))) { assertEquals(f.l2.id, f.doc.activeId) }
    }

    @Test
    fun duplicateNodesMulti() {
        val f = Fixture()
        val cmd = DuplicateNodes(f.ids(f.l1, f.a))
        roundTrip(f, cmd) {
            assertEquals(4, f.doc.root.children.size)
            assertEquals(3, f.g.children.size)
            assertEquals("A copy", f.g.children[1].name)
            assertEquals("L1 copy", f.doc.root.children[1].name)
            assertEquals(cmd.copyIds.last(), f.doc.activeId)
        }
    }

    @Test
    fun mergeNodes() {
        val f = Fixture()
        f.b.clipped = true
        val cmd = MergeNodes(f.ids(f.l1, f.b, f.l2), Compositor)
        roundTrip(f, cmd) {
            val m = f.doc.findNode(cmd.resultId!!) as RasterLayer
            assertEquals("L2", m.name)
            assertEquals(listOf(f.g.id, m.id), f.doc.root.children.map { it.id })
            assertEquals(listOf(f.a.id), f.g.children.map { it.id })
            assertEquals(m.id, f.doc.activeId)
            // b's real base (a) is not merged: b is drawn unclipped.
            assertEquals(listOf(0f, 0f, 1f, 1f), pixel(m.pixels, 25, 5).toList())
            assertEquals(listOf(0f, 0f, 1f, 1f), pixel(m.pixels, 265, 5).toList())
        }
    }

    @Test
    fun flattenGroupPassthroughBecomesNormal() {
        val f = Fixture()
        f.g.opacity = 0.5f
        val cmd = FlattenGroup(f.g.id, Compositor)
        roundTrip(f, cmd) {
            val r = f.doc.findNode(cmd.resultId!!) as RasterLayer
            assertEquals(BlendMode.NORMAL, r.blendMode)
            assertEquals(0.5f, r.opacity)
            assertEquals("Group", r.name)
            assertEquals(1, f.doc.root.children.indexOf(r))
            assertEquals(listOf(0f, 0f, 1f, 1f), pixel(r.pixels, 25, 5).toList())
        }
        assertFalse(cmd.mayChangeLook)
    }

    @Test
    fun flattenDocumentAndCopyMerged() {
        val f = Fixture()
        f.l2.visible = false
        val expected = Compositor.flatten(f.doc, false)
        val cmd = FlattenDocument(Compositor)
        roundTrip(f, cmd) {
            val r = f.doc.root.children.single() as RasterLayer
            assertEquals("Layer 1", r.name)
            assertEquals(gridHash(expected.also { it.compact() }), gridHash(r.pixels))
        }
        val cm = CopyMerged(f.g.id, Compositor)
        roundTrip(f, cm) {
            assertEquals(cm.resultId, f.doc.root.children[2].id)
            assertEquals(2, f.g.children.size)
        }
    }

    @Test
    fun mergeDownAdjustmentBakes() {
        val f = Fixture()
        val add = AddAdjustmentLayer(InvertParams(), null, 1)
        f.h.execute(f.doc, add)
        val adj = f.doc.findNode(add.nodeId)!!
        assertEquals("Invert", adj.name)
        assertEquals(add.nodeId, f.doc.activeId)
        roundTrip(f, MergeDown(add.nodeId, Compositor)) {
            assertNull(f.doc.findNode(add.nodeId))
            assertEquals(listOf(0f, 1f, 1f, 1f), pixel(f.l1.pixels, 5, 5).toList())
            assertEquals(listOf(0f, 0f, 0f, 0f), pixel(f.l1.pixels, 5, 30).toList())
        }
    }

    @Test
    fun mergeDownOntoGroupAndMasks() {
        val f = Fixture()
        val cmd = MergeDown(f.l2.id, Compositor)
        roundTrip(f, cmd) {
            val r = f.doc.findNode(cmd.targetId!!) as RasterLayer
            assertEquals(listOf(f.l1.id, r.id), f.doc.root.children.map { it.id })
            assertEquals(BlendMode.NORMAL, r.blendMode)
            assertEquals(listOf(0f, 0f, 1f, 1f), pixel(r.pixels, 265, 5).toList())
        }
        val f2 = Fixture()
        f2.l1.mask = LayerMask(MaskGrid(300, 40, 0))
        f2.l1.opacity = 0.5f
        val up = f2.doc.newRasterLayer("Up").also { fillRect(it.pixels, 0, 0, 10, 10, 0f, 1f, 0f, 1f); it.clipped = true }
        f2.doc.root.add(up, 1)
        roundTrip(f2, MergeDown(up.id, Compositor)) {
            assertNull(f2.l1.mask)
            assertEquals(1f, f2.l1.opacity)
            assertEquals(listOf(0f, 0f, 0f, 0f), pixel(f2.l1.pixels, 5, 5).toList(), "hidden base clips upper too")
        }
    }

    @Test
    fun mergeOntoAdjustmentForbidden() {
        val f = Fixture()
        f.doc.root.add(AdjustmentLayer("adj", "adj", InvertParams()), 2)
        assertFailsWith<IllegalStateException> { MergeDown(f.l2.id, Compositor).apply(f.doc) }
    }

    @Test
    fun addMaskFourWays() {
        val f = Fixture()
        roundTrip(f, AddMask(f.l1.id, MaskInit.REVEAL_ALL)) {
            assertEquals(-1, f.l1.mask!!.tiles.defaultValue.toInt())
            assertTrue(f.l1.mask!!.tiles.keys().isEmpty())
            assertEquals(EditTarget.MASK, f.doc.activeTarget)
        }
        roundTrip(f, AddMask(f.l1.id, MaskInit.HIDE_ALL)) { assertEquals(0, f.l1.mask!!.tiles.defaultValue.toInt()) }
        f.doc.selection.mask.getOrCreate(TileKey(0, 0)).data[0] = 100
        roundTrip(f, AddMask(f.l1.id, MaskInit.FROM_SELECTION)) {
            assertEquals(100, f.l1.mask!!.tiles.byteAt(0, 0))
            assertEquals(0, f.l1.mask!!.tiles.byteAt(1, 0))
        }
        roundTrip(f, AddMask(f.a.id, MaskInit.FROM_ALPHA)) { assertEquals(128, f.a.mask!!.tiles.byteAt(6, 6)) }
        roundTrip(f, AddMask(f.g.id, MaskInit.FROM_ALPHA, Compositor)) {
            assertEquals(255, f.g.mask!!.tiles.byteAt(25, 5))
            assertEquals(0, f.g.mask!!.tiles.byteAt(100, 5))
        }
    }

    @Test
    fun applyMaskRasterAndGroup() {
        val f = Fixture()
        f.l1.mask = LayerMask(MaskGrid(300, 40, -1).also { it.getOrCreate(TileKey(0, 0)).data.fill(0) })
        roundTrip(f, ApplyMask(f.l1.id)) {
            assertNull(f.l1.mask)
            assertEquals(listOf(0f, 0f, 0f, 0f), pixel(f.l1.pixels, 5, 5).toList())
            assertEquals(listOf(1f, 0f, 0f, 1f), pixel(f.l1.pixels, 280, 5).toList())
        }
        f.g.mask = LayerMask(MaskGrid(300, 40, 0x80.toByte()))
        roundTrip(f, ApplyMask(f.g.id)) {
            near(0.502f, pixel(f.b.pixels, 25, 5)[3])
            assertNull(f.g.mask)
        }
        f.g.add(AdjustmentLayer("adj", "a", InvertParams()))
        assertFalse(ApplyMask.isAvailable(f.g))
    }

    @Test
    fun maskPatchPropsInvertFillDelete() {
        val f = Fixture()
        f.l1.mask = LayerMask(MaskGrid(300, 40, -1))
        val k = TileKey(0, 0)
        val after = MaskTile().also { it.fill(0x40) }
        roundTrip(f, MaskPatchCommand(MaskPatch(f.l1.id, mapOf(k to null), mapOf(k to after)))) {
            assertEquals(0x40, f.l1.mask!!.tiles.byteAt(3, 3))
        }
        roundTrip(f, SetMaskProps(f.l1.id, enabled = false, linked = false, density = 0.3f)) {
            assertFalse(f.l1.mask!!.enabled); assertEquals(0.3f, f.l1.mask!!.density)
        }
        roundTrip(f, InvertMask(f.l1.id)) { assertEquals(0, f.l1.mask!!.tiles.defaultValue.toInt()) }
        roundTrip(f, FillMask(f.l1.id, reveal = false)) { assertEquals(0, f.l1.mask!!.tiles.byteAt(3, 3)) }
        f.doc.activeId = f.l1.id
        f.doc.activeTarget = EditTarget.MASK
        roundTrip(f, DeleteMask(f.l1.id)) {
            assertNull(f.l1.mask)
            assertEquals(EditTarget.LAYER, f.doc.activeTarget)
        }
        assertFailsWith<IllegalArgumentException> {
            MaskPatchCommand(MaskPatch(MaskPatch.SELECTION, emptyMap(), emptyMap()))
        }
    }

    @Test
    fun setAdjustParams() {
        val f = Fixture()
        val adj = AdjustmentLayer("adj", "BC", BrightnessContrastParams())
        f.doc.root.add(adj)
        val after = BrightnessContrastParams(30f, 10f)
        roundTrip(f, SetAdjustParams("adj", adj.params, after)) { assertEquals(after, adj.params) }
    }

    @Test
    fun referenceIsUnique() {
        val f = Fixture()
        f.h.execute(f.doc, SetNodeProps(f.l1.id, reference = true))
        roundTrip(f, SetNodeProps(f.g.id, reference = true, colorTag = ColorTag.RED, locked = true)) {
            assertEquals(f.g, f.doc.referenceNode)
            assertFalse(f.l1.reference)
            assertEquals(ColorTag.RED, f.g.colorTag)
            assertTrue(f.g.locked)
        }
        assertEquals(f.l1, f.doc.referenceNode)
        f.doc.root.add(AdjustmentLayer("adj", "a", InvertParams()))
        assertFailsWith<IllegalArgumentException> { SetNodeProps("adj", reference = true).apply(f.doc) }
    }

    @Test
    fun lockBlocksPixelCommands() {
        val f = Fixture()
        f.g.locked = true
        f.l1.locked = true
        f.l1.mask = LayerMask(MaskGrid(300, 40, -1))
        val before = fingerprint(f.doc)
        val blocked = listOf(
            ClearLayer(f.l1.id),
            ClearLayer(f.a.id),
            TilePatchCommand(TilePatch(f.a.id, emptyMap(), emptyMap())),
            DeleteNode(f.g.id),
            DeleteNodes(listOf(f.l2.id, f.l1.id)),
            MergeDown(f.l2.id, Compositor),
            MergeDown(f.b.id, Compositor),
            MergeNodes(listOf(f.l1.id, f.l2.id), Compositor),
            FlattenGroup(f.g.id, Compositor),
            FlattenDocument(Compositor),
            AddMask(f.a.id),
            SetMaskProps(f.l1.id, density = 0.5f),
            MaskPatchCommand(MaskPatch(f.l1.id, emptyMap(), emptyMap())),
            ApplyMask(f.l1.id),
        )
        for (c in blocked) {
            assertFailsWith<LockedException>(c.name) { f.h.execute(f.doc, c) }
            assertEquals(before, fingerprint(f.doc), "state changed by ${c.name}")
        }
        assertFalse(f.h.canUndo)
        // Properties and order stay editable.
        f.h.execute(f.doc, SetNodeProps(f.l1.id, opacity = 0.5f))
        f.h.execute(f.doc, MoveNode(f.l1.id, null, 2))
        assertEquals(2, f.h.undoSize)
    }

    @Test
    fun deepCopyCarriesNewFields() {
        val f = Fixture()
        f.a.mask = LayerMask(MaskGrid(300, 40, 0).also { it.getOrCreate(TileKey(0, 0)).data[5] = 9 })
        f.a.colorTag = ColorTag.GREEN
        f.a.reference = true
        val c = f.a.deepCopy()
        assertEquals(ColorTag.GREEN, c.colorTag)
        assertFalse(c.reference, "copies are never the reference")
        assertEquals(9, c.mask!!.tiles.byteAt(5, 0))
        f.a.mask!!.tiles.get(TileKey(0, 0))!!.data[5] = 1
        assertEquals(9, c.mask!!.tiles.byteAt(5, 0))
        val adj = AdjustmentLayer("x", "x", InvertParams()).also { it.opacity = 0.3f }
        val ac = adj.deepCopy()
        assertIs<AdjustmentLayer>(ac)
        assertEquals(adj.params, ac.params)
        assertEquals(0.3f, ac.opacity)
        assertNotNull(ac.id)
    }
}
