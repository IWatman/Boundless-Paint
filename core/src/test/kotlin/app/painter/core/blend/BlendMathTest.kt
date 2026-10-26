package app.painter.core.blend

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class BlendMathTest {
    private val tol = 1e-3f

    private fun blend(mode: BlendMode, dst: FloatArray, src: FloatArray, opacity: Float = 1f): FloatArray {
        val d = dst.copyOf()
        BlendMath.blendPixel(mode, d, 0, src, 0, opacity)
        return d
    }

    private fun assertPixel(expected: FloatArray, actual: FloatArray, label: String) {
        for (i in 0..3) {
            assertTrue(
                abs(expected[i] - actual[i]) <= tol,
                "$label: expected ${expected.toList()} but was ${actual.toList()}",
            )
        }
    }

    private fun px(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)

    private val cb1 = px(0.4f, 0.7f, 0.9f)
    private val cs1 = px(0.8f, 0.3f, 0.6f)
    private val cb2 = px(0.3f, 0.5f, 0.15f)
    private val cs2 = px(0.2f, 0.9f, 0.4f)

    /** Opaque-over-opaque results, so the output equals B(Cb, Cs) for color modes. */
    private val table1 = mapOf(
        BlendMode.NORMAL to px(0.8f, 0.3f, 0.6f),
        BlendMode.PASSTHROUGH to px(0.8f, 0.3f, 0.6f),
        BlendMode.DARKEN to px(0.4f, 0.3f, 0.6f),
        BlendMode.MULTIPLY to px(0.32f, 0.21f, 0.54f),
        BlendMode.COLOR_BURN to px(0.25f, 0f, 0.833333f),
        BlendMode.LINEAR_BURN to px(0.2f, 0f, 0.5f),
        BlendMode.DARKER_COLOR to px(0.8f, 0.3f, 0.6f),
        BlendMode.LIGHTEN to px(0.8f, 0.7f, 0.9f),
        BlendMode.SCREEN to px(0.88f, 0.79f, 0.96f),
        BlendMode.COLOR_DODGE to px(1f, 1f, 1f),
        BlendMode.LINEAR_DODGE to px(1f, 1f, 1f),
        BlendMode.ADD to px(1f, 1f, 1f),
        BlendMode.LIGHTER_COLOR to px(0.4f, 0.7f, 0.9f),
        BlendMode.OVERLAY to px(0.64f, 0.58f, 0.92f),
        BlendMode.SOFT_LIGHT to px(0.539473f, 0.616f, 0.909737f),
        BlendMode.HARD_LIGHT to px(0.76f, 0.42f, 0.92f),
        BlendMode.VIVID_LIGHT to px(1f, 0.5f, 1f),
        BlendMode.LINEAR_LIGHT to px(1f, 0.3f, 1f),
        BlendMode.PIN_LIGHT to px(0.6f, 0.6f, 0.9f),
        // Green: vivid_light(0.7, 0.3) = 1 - 0.3 / 0.6 = 0.5 exactly, which is not < 0.5.
        BlendMode.HARD_MIX to px(1f, 1f, 1f),
        BlendMode.DIFFERENCE to px(0.4f, 0.4f, 0.3f),
        BlendMode.EXCLUSION to px(0.56f, 0.58f, 0.42f),
        BlendMode.NEGATIVE to px(0.8f, 1f, 0.5f),
        BlendMode.INVERT to px(0.6f, 0.3f, 0.1f),
        BlendMode.SUBTRACT to px(0f, 0.4f, 0.3f),
        BlendMode.DIVIDE to px(0.5f, 1f, 1f),
        BlendMode.HUE to px(0.949f, 0.449f, 0.749f),
        BlendMode.SATURATION to px(0.4f, 0.7f, 0.9f),
        BlendMode.COLOR to px(0.949f, 0.449f, 0.749f),
        BlendMode.LUMINOSITY to px(0.251f, 0.551f, 0.751f),
        BlendMode.ERASE to px(0f, 0f, 0f, 0f),
        // Lum(cs1) = 0.483: the layer itself is invisible, dst is scaled.
        BlendMode.MASK to px(0.1932f, 0.3381f, 0.4347f, 0.483f),
        // m = 0.483, ink = 0.517, alpha = max(0.483, 0.517), color = Cb * (1 - ink) * alpha.
        BlendMode.LINE_ART to px(0.4f * 0.483f * 0.517f, 0.7f * 0.483f * 0.517f, 0.9f * 0.483f * 0.517f, 0.517f),
    )

    private val table2 = mapOf(
        BlendMode.NORMAL to px(0.2f, 0.9f, 0.4f),
        BlendMode.PASSTHROUGH to px(0.2f, 0.9f, 0.4f),
        BlendMode.DARKEN to px(0.2f, 0.5f, 0.15f),
        BlendMode.MULTIPLY to px(0.06f, 0.45f, 0.06f),
        BlendMode.COLOR_BURN to px(0f, 0.444444f, 0f),
        BlendMode.LINEAR_BURN to px(0f, 0.4f, 0f),
        BlendMode.DARKER_COLOR to px(0.3f, 0.5f, 0.15f),
        BlendMode.LIGHTEN to px(0.3f, 0.9f, 0.4f),
        BlendMode.SCREEN to px(0.44f, 0.95f, 0.49f),
        BlendMode.COLOR_DODGE to px(0.375f, 1f, 0.25f),
        BlendMode.LINEAR_DODGE to px(0.5f, 1f, 0.55f),
        BlendMode.ADD to px(0.5f, 1f, 0.55f),
        BlendMode.LIGHTER_COLOR to px(0.2f, 0.9f, 0.4f),
        BlendMode.OVERLAY to px(0.12f, 0.9f, 0.12f),
        BlendMode.SOFT_LIGHT to px(0.174f, 0.665685f, 0.1245f),
        BlendMode.HARD_LIGHT to px(0.12f, 0.9f, 0.12f),
        BlendMode.VIVID_LIGHT to px(0f, 1f, 0f),
        BlendMode.LINEAR_LIGHT to px(0f, 1f, 0f),
        BlendMode.PIN_LIGHT to px(0.3f, 0.8f, 0.15f),
        BlendMode.HARD_MIX to px(0f, 1f, 0f),
        BlendMode.DIFFERENCE to px(0.1f, 0.4f, 0.25f),
        BlendMode.EXCLUSION to px(0.38f, 0.5f, 0.43f),
        BlendMode.NEGATIVE to px(0.5f, 0.6f, 0.55f),
        BlendMode.INVERT to px(0.7f, 0.5f, 0.85f),
        BlendMode.SUBTRACT to px(0.1f, 0f, 0f),
        BlendMode.DIVIDE to px(1f, 0.555556f, 0.375f),
        BlendMode.HUE to px(0.184f, 0.534f, 0.284f),
        BlendMode.SATURATION to px(0.239463f, 0.558748f, 0f),
        BlendMode.COLOR to px(0f, 0.646092f, 0.184598f),
        BlendMode.LUMINOSITY to px(0.5335f, 0.7335f, 0.3835f),
        BlendMode.ERASE to px(0f, 0f, 0f, 0f),
        // Lum(cs2) = 0.635.
        BlendMode.MASK to px(0.3f * 0.635f, 0.5f * 0.635f, 0.15f * 0.635f, 0.635f),
        // m = 0.635, ink = 0.365, alpha = 0.635.
        BlendMode.LINE_ART to px(0.3f * 0.635f * 0.635f, 0.5f * 0.635f * 0.635f, 0.15f * 0.635f * 0.635f, 0.635f),
    )

    @Test
    fun tablesCoverEveryMode() {
        for (m in BlendMode.entries) {
            assertTrue(m in table1 && m in table2, "missing $m")
        }
    }

    @Test
    fun opaqueOverOpaqueTable1() {
        for ((mode, exp) in table1) assertPixel(exp, blend(mode, cb1, cs1), "$mode #1")
    }

    @Test
    fun opaqueOverOpaqueTable2() {
        for ((mode, exp) in table2) assertPixel(exp, blend(mode, cb2, cs2), "$mode #2")
    }

    @Test
    fun opacityScalesSource() {
        assertPixel(px(0.6f, 0.5f, 0.75f), blend(BlendMode.NORMAL, cb1, cs1, 0.5f), "normal 50%")
        assertPixel(px(0.36f, 0.455f, 0.72f), blend(BlendMode.MULTIPLY, cb1, cs1, 0.5f), "multiply 50%")
        assertPixel(px(0.7f, 0.85f, 0.95f), blend(BlendMode.LINEAR_DODGE, cb1, cs1, 0.5f), "linear dodge 50%")
        assertPixel(px(0.8f, 0.85f, 1f), blend(BlendMode.ADD, cb1, cs1, 0.5f), "add 50%")
        assertPixel(px(0.45f, 0.6f, 0.7f), blend(BlendMode.INVERT, cb1, cs1, 0.25f), "invert 25%")
        assertPixel(px(0.2f, 0.35f, 0.45f, 0.5f), blend(BlendMode.ERASE, cb1, cs1, 0.5f), "erase 50%")
        // MASK with half opacity: m = 0.483 * 0.5 + 0.5.
        val m = 0.7415f
        assertPixel(px(0.4f * m, 0.7f * m, 0.9f * m, m), blend(BlendMode.MASK, cb1, cs1, 0.5f), "mask 50%")
    }

    @Test
    fun premultipliedSourceAlphaMatchesOpacity() {
        val halfSrc = px(0.4f, 0.15f, 0.3f, 0.5f)
        assertPixel(px(0.36f, 0.455f, 0.72f), blend(BlendMode.MULTIPLY, cb1, halfSrc), "multiply half-alpha src")
    }

    @Test
    fun transparentSourceLeavesDestinationUnchanged() {
        val clear = px(0f, 0f, 0f, 0f)
        val halfDst = px(0.2f, 0.35f, 0.45f, 0.5f)
        for (mode in BlendMode.entries) {
            assertPixel(cb1, blend(mode, cb1, clear), "$mode clear src")
            assertPixel(halfDst, blend(mode, halfDst, clear), "$mode clear src half dst")
            assertPixel(cb1, blend(mode, cb1, cs1, 0f), "$mode zero opacity")
        }
    }

    @Test
    fun transparentDestination() {
        val clear = px(0f, 0f, 0f, 0f)
        val colorModes = BlendMode.entries.filter { it.group != BlendMode.Group.MASKING && it != BlendMode.INVERT }
        for (mode in colorModes) {
            assertPixel(cs1, blend(mode, clear, cs1), "$mode over clear")
            assertPixel(px(0.4f, 0.15f, 0.3f, 0.5f), blend(mode, clear, cs1, 0.5f), "$mode 50% over clear")
        }
        assertPixel(clear, blend(BlendMode.INVERT, clear, cs1), "invert over clear")
        assertPixel(clear, blend(BlendMode.ERASE, clear, cs1), "erase over clear")
        assertPixel(clear, blend(BlendMode.MASK, clear, cs1), "mask over clear")
        assertPixel(px(0f, 0f, 0f, 0.5f), blend(BlendMode.LINE_ART, clear, px(0f, 0f, 0f), 0.5f), "line art ink over clear")
    }

    @Test
    fun semiTransparentDestination() {
        val halfDst = px(0.2f, 0.35f, 0.45f, 0.5f)
        // 0.5 * Cs + 0.5 * B(Cb, Cs)
        assertPixel(px(0.56f, 0.255f, 0.57f), blend(BlendMode.MULTIPLY, halfDst, cs1), "multiply over half dst")
        assertPixel(px(0.8f, 0.3f, 0.6f), blend(BlendMode.NORMAL, halfDst, cs1), "normal over half dst")
    }

    @Test
    fun maskingModesWithWhiteAndBlack() {
        val white = px(1f, 1f, 1f)
        val black = px(0f, 0f, 0f)
        assertPixel(cb1, blend(BlendMode.MASK, cb1, white), "mask white")
        assertPixel(px(0f, 0f, 0f, 0f), blend(BlendMode.MASK, cb1, black), "mask black")
        assertPixel(cb1, blend(BlendMode.LINE_ART, cb1, white), "line art white")
        assertPixel(px(0f, 0f, 0f, 1f), blend(BlendMode.LINE_ART, cb1, black), "line art black")
        assertPixel(px(0.1f, 0.175f, 0.225f, 0.5f), blend(BlendMode.LINE_ART, cb1, px(0.5f, 0.5f, 0.5f)), "line art gray")
    }

    @Test
    fun offsetsAreRespected() {
        val dst = floatArrayOf(9f, 9f, 9f, 9f) + cb1
        val src = floatArrayOf(7f, 7f, 7f, 7f, 7f, 7f, 7f, 7f) + cs1
        BlendMath.blendPixel(BlendMode.MULTIPLY, dst, 4, src, 8, 1f)
        assertPixel(px(9f, 9f, 9f, 9f), dst.copyOfRange(0, 4), "untouched prefix")
        assertPixel(table1.getValue(BlendMode.MULTIPLY), dst.copyOfRange(4, 8), "offset pixel")
    }
}
