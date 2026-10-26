package app.painter.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTextTest {
    @Test
    fun stripsBoldMarkersAndBullets() {
        val t = releaseNotesText("# Title\r\n**New:**\n- **Button** works\n* item")
        assertEquals("Title\nNew:\n• Button works\n• item", t.text)
        assertEquals(3, t.spanStyles.size)
    }
}
