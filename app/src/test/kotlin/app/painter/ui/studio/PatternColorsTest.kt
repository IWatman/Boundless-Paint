package app.painter.ui.studio

import app.painter.core.color.Rgba
import app.painter.ui.components.luma
import app.painter.ui.components.toHsvUi
import app.painter.ui.components.toRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PatternColorsTest {
    @Test
    fun backgroundContrastsWithDarkAndLightInks() {
        val inks = listOf(
            Rgba(0f, 0f, 0f, 1f),
            Rgba(1f, 1f, 1f, 1f),
            Rgba(1f, 0f, 0f, 1f),
            Rgba(0f, 1f, 0f, 1f),
            Rgba(0.5f, 0.5f, 0.5f, 1f),
        )
        for (ink in inks) {
            val background = patternBackgroundHsv(ink).toRgba(ink.a)
            assertTrue(abs(background.luma() - ink.luma()) >= 0.499f)
        }
    }

    @Test
    fun initialColorsPreserveInkAndAlpha() {
        val ink = Rgba(0.2f, 0.4f, 0.8f, 0.35f)
        val patternInk = ink.toHsvUi().toRgba(ink.a)
        val background = patternBackgroundHsv(ink).toRgba(ink.a)
        assertEquals(ink.r, patternInk.r, 0.00001f)
        assertEquals(ink.g, patternInk.g, 0.00001f)
        assertEquals(ink.b, patternInk.b, 0.00001f)
        assertEquals(ink.a, patternInk.a, 0f)
        assertEquals(ink.a, background.a, 0f)
    }
}
