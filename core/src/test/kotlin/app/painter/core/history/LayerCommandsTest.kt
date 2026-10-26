package app.painter.core.history

import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.RasterLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayerCommandsTest {
    /** root: [L1, G{A, B}, L2], active = L2. */
    private class Fixture {
        val doc = Document.create(512, 300)
        val l1 = doc.activeRaster!!
        val g = GroupNode("g", "Group")
        val a = doc.newRasterLayer("A")
        val b = doc.newRasterLayer("B")
        val l2 = doc.newRasterLayer("L2")
        val h = History()

        init {
            doc.root.add(g); g.add(a); g.add(b); doc.root.add(l2)
            fillRect(l1.pixels, 0, 0, 300, 20, 1f, 0f, 0f, 1f)
            fillRect(a.pixels, 5, 5, 40, 40, 0f, 1f, 0f, 0.5f)
            fillRect(l2.pixels, 260, 0, 270, 10, 0f, 0f, 1f, 1f)
            doc.activeId = l2.id
        }
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

    @Test
    fun addLayer() {
        val f = Fixture()
        val n = f.doc.newRasterLayer("New")
        roundTrip(f, AddLayer(n, f.g.id, 1)) {
            assertSame(f.g, n.parent)
            assertEquals(1, f.g.indexOf(n))
            assertEquals(n.id, f.doc.activeId)
        }
        assertNull(n.parent)
        assertEquals(f.l2.id, f.doc.activeId)
    }

    @Test
    fun deleteRestoresPositionAndFixesActive() {
        val f = Fixture()
        roundTrip(f, DeleteNode(f.l2.id)) {
            assertNull(f.doc.findNode(f.l2.id))
            assertEquals(f.g.id, f.doc.activeId)
        }
        assertEquals(2, f.doc.root.indexOf(f.l2))
        assertEquals(f.l2.id, f.doc.activeId)

        f.doc.activeId = f.a.id
        roundTrip(f, DeleteNode(f.a.id)) { assertEquals(f.b.id, f.doc.activeId) }
        assertEquals(0, f.g.indexOf(f.a))

        f.doc.activeId = f.b.id
        roundTrip(f, DeleteNode(f.g.id)) {
            assertNull(f.doc.findNode(f.b.id))
            assertEquals(f.l1.id, f.doc.activeId)
        }
        assertEquals(1, f.doc.root.indexOf(f.g))
        assertEquals(f.b.id, f.doc.activeId)
    }

    @Test
    fun deleteUnrelatedKeepsActive() {
        val f = Fixture()
        roundTrip(f, DeleteNode(f.l1.id)) { assertEquals(f.l2.id, f.doc.activeId) }
    }

    @Test
    fun deleteLastLayerLeavesNoActive() {
        val doc = Document.create(10, 10)
        val h = History()
        h.execute(doc, DeleteNode(doc.activeId!!))
        assertNull(doc.activeId)
        h.undo(doc)
        assertEquals(1, doc.layerCount())
        assertTrue(doc.activeId != null)
    }

    @Test
    fun moveBetweenGroups() {
        val f = Fixture()
        roundTrip(f, MoveNode(f.l2.id, f.g.id, 0)) {
            assertSame(f.g, f.l2.parent)
            assertEquals(listOf(f.l2, f.a, f.b), f.g.children)
        }
        roundTrip(f, MoveNode(f.b.id, null, 0)) {
            assertEquals(listOf(f.b, f.l1, f.g, f.l2), f.doc.root.children)
        }
        roundTrip(f, MoveNode(f.l1.id, null, 2)) {
            assertEquals(listOf(f.g, f.l2, f.l1), f.doc.root.children)
        }
        assertEquals(listOf(f.l1, f.g, f.l2), f.doc.root.children)
    }

    @Test
    fun moveGroupIntoItselfFails() {
        val f = Fixture()
        val inner = GroupNode("inner", "Inner")
        f.g.add(inner)
        assertFailsWith<IllegalArgumentException> { MoveNode(f.g.id, inner.id, 0).apply(f.doc) }
        assertFailsWith<IllegalArgumentException> { MoveNode(f.g.id, f.g.id, 0).apply(f.doc) }
        assertSame(f.doc.root, f.g.parent)
    }

    @Test
    fun duplicateIsDeepAndIndependent() {
        val f = Fixture()
        val cmd = DuplicateNode(f.g.id)
        roundTrip(f, cmd) {
            val copy = f.doc.findNode(cmd.copyId!!) as GroupNode
            assertEquals("Group copy", copy.name)
            assertEquals(f.doc.root.indexOf(f.g) + 1, f.doc.root.indexOf(copy))
            assertEquals(copy.id, f.doc.activeId)
            assertEquals(2, copy.children.size)
            val ca = copy.children[0] as RasterLayer
            assertNotEquals(f.a.id, ca.id)
            assertEquals(gridHash(f.a.pixels), gridHash(ca.pixels))
        }
        f.h.redo(f.doc)
        val copy = f.doc.findNode(cmd.copyId!!) as GroupNode
        val ca = copy.children[0] as RasterLayer
        val origHash = gridHash(f.a.pixels)
        fillRect(ca.pixels, 0, 0, 50, 50, 1f, 1f, 1f, 1f)
        assertEquals(origHash, gridHash(f.a.pixels))
        assertNotEquals(origHash, gridHash(ca.pixels))
    }

    @Test
    fun duplicateRasterPlacedAbove() {
        val f = Fixture()
        val cmd = DuplicateNode(f.a.id)
        f.h.execute(f.doc, cmd)
        assertEquals(listOf("A", "A copy", "B"), f.g.children.map { it.name })
    }

    @Test
    fun setNodeProps() {
        val f = Fixture()
        roundTrip(
            f,
            SetNodeProps(
                f.a.id, newName = "X", visible = false, opacity = 0.25f,
                blendMode = BlendMode.MULTIPLY, clipped = true, alphaLock = true,
            ),
        ) {
            assertEquals("X", f.a.name)
            assertEquals(false, f.a.visible)
            assertEquals(0.25f, f.a.opacity)
            assertEquals(BlendMode.MULTIPLY, f.a.blendMode)
            assertEquals(true, f.a.clipped)
            assertEquals(true, f.a.alphaLock)
        }
        roundTrip(f, SetNodeProps(f.g.id, opacity = 0.5f)) { assertEquals(0.5f, f.g.opacity) }
    }

    @Test
    fun clearLayer() {
        val f = Fixture()
        roundTrip(f, ClearLayer(f.l1.id)) { assertTrue(f.l1.pixels.keys().isEmpty()) }
        assertEquals(2, f.l1.pixels.keys().size)
    }

    @Test
    fun mergeDown() {
        val doc = Document.create(300, 300)
        val low = doc.activeRaster!!
        low.opacity = 0.5f
        low.blendMode = BlendMode.SCREEN
        val up = doc.newRasterLayer("Up")
        up.opacity = 0.5f
        doc.root.add(up)
        doc.activeId = up.id
        fillRect(low.pixels, 0, 0, 10, 10, 1f, 0f, 0f, 1f)
        fillRect(up.pixels, 5, 5, 280, 15, 0f, 0f, 1f, 1f)
        val h = History()
        val before = fingerprint(doc)
        h.execute(doc, MergeDown(up.id, normalBlender))

        assertNull(doc.findNode(up.id))
        assertEquals(low.id, doc.activeId)
        assertEquals("Layer 1", low.name)
        assertEquals(BlendMode.SCREEN, low.blendMode)
        assertEquals(1f, low.opacity)
        val p0 = pixel(low.pixels, 1, 1)
        assertEquals(listOf(0.5f, 0f, 0f, 0.5f), p0.toList())
        val p1 = pixel(low.pixels, 6, 6)
        assertEquals(0.25f, p1[0], 1e-6f)
        assertEquals(0.5f, p1[2], 1e-6f)
        assertEquals(0.75f, p1[3], 1e-6f)
        val p2 = pixel(low.pixels, 270, 6)
        assertEquals(listOf(0f, 0f, 0.5f, 0.5f), p2.toList())
        val after = fingerprint(doc)

        h.undo(doc)
        assertEquals(before, fingerprint(doc))
        assertEquals(0.5f, low.opacity)
        h.redo(doc)
        assertEquals(after, fingerprint(doc))
    }

    @Test
    fun mergeDownRequiresRasterBelow() {
        val f = Fixture()
        assertFailsWith<IllegalStateException> { MergeDown(f.l2.id, normalBlender).apply(f.doc) }
        assertFailsWith<IllegalStateException> { MergeDown(f.l1.id, normalBlender).apply(f.doc) }
    }

    @Test
    fun mixedSequenceRestoresInitialState() {
        val f = Fixture()
        val initial = fingerprint(f.doc)
        val cmds = listOf(
            AddLayer(f.doc.newRasterLayer("N"), f.g.id),
            MoveNode(f.l1.id, f.g.id, 0),
            DuplicateNode(f.a.id),
            SetNodeProps(f.b.id, opacity = 0.1f),
            MergeDown(f.b.id, normalBlender),
            ClearLayer(f.l2.id),
            DeleteNode(f.g.id),
        )
        val states = mutableListOf(initial)
        for (c in cmds) { f.h.execute(f.doc, c); states += fingerprint(f.doc) }
        for (i in cmds.indices.reversed()) {
            f.h.undo(f.doc)
            assertEquals(states[i], fingerprint(f.doc), "after undo of ${cmds[i].name}")
        }
        for (i in cmds.indices) {
            f.h.redo(f.doc)
            assertEquals(states[i + 1], fingerprint(f.doc), "after redo of ${cmds[i].name}")
        }
    }
}
