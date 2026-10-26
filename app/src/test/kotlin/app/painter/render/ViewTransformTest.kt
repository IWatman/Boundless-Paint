package app.painter.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTransformTest {
    private val eps = 1e-3f

    @Test
    fun roundTripWithRotationAndZoom() {
        val t = ViewTransform()
        t.set(2.5f, 37f, 120f, -40f)
        val s = FloatArray(2)
        val d = FloatArray(2)
        t.docToScreen(300f, 711f, s)
        t.screenToDoc(s[0], s[1], d)
        assertEquals(300f, d[0], eps)
        assertEquals(711f, d[1], eps)
    }

    @Test
    fun placeDocPointAnchors() {
        val t = ViewTransform()
        t.placeDocPoint(100f, 200f, 500f, 400f, 3f, 90f)
        val s = FloatArray(2)
        t.docToScreen(100f, 200f, s)
        assertEquals(500f, s[0], eps)
        assertEquals(400f, s[1], eps)
        t.docToScreen(101f, 200f, s)
        assertEquals(500f, s[0], eps)
        assertEquals(403f, s[1], eps)
    }

    @Test
    fun zoomIsClamped() {
        val t = ViewTransform()
        t.set(1000f, 0f, 0f, 0f)
        assertEquals(ViewTransform.MAX_ZOOM, t.zoom, 0f)
        t.set(0.001f, 0f, 0f, 0f)
        assertEquals(ViewTransform.MIN_ZOOM, t.zoom, 0f)
    }

    @Test
    fun zoomAboutKeepsPivot() {
        val t = ViewTransform()
        t.set(1f, 15f, 10f, 20f)
        val before = FloatArray(2)
        t.screenToDoc(300f, 250f, before)
        t.zoomAbout(2f, 300f, 250f)
        val after = FloatArray(2)
        t.screenToDoc(300f, 250f, after)
        assertEquals(before[0], after[0], eps)
        assertEquals(before[1], after[1], eps)
        assertEquals(2f, t.zoom, eps)
    }

    @Test
    fun snapRotation() {
        assertEquals(90f, ViewTransform.snapRotation(88f), 0f)
        assertEquals(0f, ViewTransform.snapRotation(-2.5f), 0f)
        assertEquals(180f, ViewTransform.snapRotation(-178f), 0f)
        assertEquals(45f, ViewTransform.snapRotation(45f), 0f)
        assertEquals(84f, ViewTransform.snapRotation(84f), 0f)
    }

    @Test
    fun normalize() {
        assertEquals(-90f, ViewTransform.normalizeDeg(270f), eps)
        assertEquals(180f, ViewTransform.normalizeDeg(-180f), eps)
        assertEquals(10f, ViewTransform.normalizeDeg(730f), eps)
    }

    @Test
    fun fitCentersDocument() {
        val t = ViewTransform()
        t.set(3f, 30f, 0f, 0f)
        t.fit(2000, 1000, 1000, 1000, margin = 0f)
        assertEquals(0.5f, t.zoom, eps)
        assertEquals(0f, t.rotationDeg, 0f)
        val s = FloatArray(2)
        t.docToScreen(1000f, 500f, s)
        assertEquals(500f, s[0], eps)
        assertEquals(500f, s[1], eps)
    }

    @Test
    fun ndcMatrixMapsCorners() {
        val t = ViewTransform()
        t.set(2f, 0f, 100f, 50f)
        val m = FloatArray(16)
        t.toNdcMatrix(800, 600, m)
        // doc (0,0) -> screen (100,50) -> ndc (-0.75, 0.8333)
        assertEquals(-0.75f, m[12], eps)
        assertEquals(1f - 100f / 600f, m[13], eps)
        // doc (350, 275) -> screen (800, 600) -> ndc (1, -1)
        assertEquals(1f, m[0] * 350f + m[4] * 275f + m[12], eps)
        assertEquals(-1f, m[1] * 350f + m[5] * 275f + m[13], eps)
    }

    @Test
    fun visibleBounds() {
        val t = ViewTransform()
        t.set(0.5f, 0f, 0f, 0f)
        val b = FloatArray(4)
        t.visibleDocBounds(100, 50, b)
        assertEquals(0f, b[0], eps); assertEquals(0f, b[1], eps)
        assertEquals(200f, b[2], eps); assertEquals(100f, b[3], eps)
    }
}
