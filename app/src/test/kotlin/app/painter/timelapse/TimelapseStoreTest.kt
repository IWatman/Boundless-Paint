package app.painter.timelapse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelapseStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyCompletedFramesAreVisibleToExport() {
        val store = TimelapseStore(folder.root)
        val bytes = byteArrayOf(1, 2, 3)
        val count = store.publishFrame { out ->
            out.write(bytes)
            out.flush()
            assertTrue(store.frames().isEmpty())
            true
        }
        assertEquals(1, count)
        assertArrayEquals(bytes, store.frames().single().readBytes())
    }

    @Test
    fun failedCaptureKeepsEarlierFramesAndLeavesNoPartialFile() {
        val store = TimelapseStore(folder.root)
        val bytes = byteArrayOf(1, 2, 3)
        store.publishFrame { out -> out.write(bytes); true }
        val count = store.publishFrame { out ->
            out.write(4)
            false
        }
        assertEquals(1, count)
        assertEquals(listOf("000000.jpg"), store.framesDir.list()?.toList())
        assertArrayEquals(bytes, store.frames().single().readBytes())
    }
}
