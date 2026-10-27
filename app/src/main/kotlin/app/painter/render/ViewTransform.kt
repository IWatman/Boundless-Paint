package app.painter.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Public, observable part of the canvas view: zoom factor and rotation in degrees (−180, 180]. */
data class ViewState(val zoom: Float, val rotationDeg: Float, val tx: Float = 0f, val ty: Float = 0f)

/**
 * Affine mapping between document pixels and view (screen) pixels:
 * `screen = T(tx, ty) · R(rotation) · S(zoom) · doc`. Screen y points down; positive rotation
 * is clockwise on screen. Not thread-safe; owned by the main thread.
 */
class ViewTransform {
    /** Zoom factor, always within [MIN_ZOOM, MAX_ZOOM]. */
    var zoom: Float = 1f
        private set

    /** Rotation in degrees, normalized to (−180, 180]. */
    var rotationDeg: Float = 0f
        private set

    /** Screen position of the document origin. */
    var tx: Float = 0f
        private set
    var ty: Float = 0f
        private set

    private var cosR = 1f
    private var sinR = 0f
    private val tmp = FloatArray(2)

    /** Snapshot of zoom and rotation. */
    val state: ViewState get() = ViewState(zoom, rotationDeg, tx, ty)

    /** Sets all parameters at once; zoom is clamped and rotation normalized. */
    fun set(zoom: Float, rotationDeg: Float, tx: Float, ty: Float) {
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        this.rotationDeg = normalizeDeg(rotationDeg)
        val rad = Math.toRadians(this.rotationDeg.toDouble())
        cosR = cos(rad).toFloat()
        sinR = sin(rad).toFloat()
        this.tx = tx
        this.ty = ty
    }

    /** Copies all parameters from [other]. */
    fun set(other: ViewTransform) = set(other.zoom, other.rotationDeg, other.tx, other.ty)

    /**
     * Sets zoom and rotation, then translates so that document point ([docX], [docY]) lands on
     * screen point ([screenX], [screenY]). Used for gesture-anchored pan/zoom/rotate.
     */
    fun placeDocPoint(docX: Float, docY: Float, screenX: Float, screenY: Float, zoom: Float, rotationDeg: Float) {
        set(zoom, rotationDeg, 0f, 0f)
        val a = this.zoom * cosR
        val b = this.zoom * sinR
        tx = screenX - (a * docX - b * docY)
        ty = screenY - (b * docX + a * docY)
    }

    /** Translates by a screen-space delta. */
    fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
    }

    /** Multiplies zoom by [factor] keeping screen point ([px], [py]) fixed. */
    fun zoomAbout(factor: Float, px: Float, py: Float) {
        val d = tmp
        screenToDoc(px, py, d)
        placeDocPoint(d[0], d[1], px, py, zoom * factor, rotationDeg)
    }

    /** Rotates by [deltaDeg] keeping screen point ([px], [py]) fixed. */
    fun rotateAbout(deltaDeg: Float, px: Float, py: Float) {
        val d = tmp
        screenToDoc(px, py, d)
        placeDocPoint(d[0], d[1], px, py, zoom, rotationDeg + deltaDeg)
    }

    /**
     * Fits a [docW]×[docH] document centered into a [viewW]×[viewH] view, with rotation reset
     * to 0 and [margin] (fraction of the view) left free around it.
     */
    fun fit(docW: Int, docH: Int, viewW: Int, viewH: Int, margin: Float = 0.04f) {
        if (docW <= 0 || docH <= 0 || viewW <= 0 || viewH <= 0) return
        val avail = 1f - 2f * margin
        val z = min(viewW * avail / docW, viewH * avail / docH)
        placeDocPoint(docW / 2f, docH / 2f, viewW / 2f, viewH / 2f, z, 0f)
    }

    /** Writes the screen position of a document point into [out] (2 floats). */
    fun docToScreen(x: Float, y: Float, out: FloatArray) {
        val a = zoom * cosR
        val b = zoom * sinR
        out[0] = a * x - b * y + tx
        out[1] = b * x + a * y + ty
    }

    /** Writes the document position of a screen point into [out] (2 floats). */
    fun screenToDoc(x: Float, y: Float, out: FloatArray) {
        val sx = (x - tx) / zoom
        val sy = (y - ty) / zoom
        out[0] = cosR * sx + sinR * sy
        out[1] = -sinR * sx + cosR * sy
    }

    /**
     * Writes a column-major 4×4 matrix mapping document pixels to normalized device coordinates
     * of a [viewW]×[viewH] viewport (y up in NDC) into [out] (16 floats).
     */
    fun toNdcMatrix(viewW: Int, viewH: Int, out: FloatArray) {
        val a = zoom * cosR
        val b = zoom * sinR
        val sx = 2f / viewW
        val sy = -2f / viewH
        out.fill(0f)
        out[0] = a * sx; out[1] = b * sy
        out[4] = -b * sx; out[5] = a * sy
        out[10] = 1f
        out[12] = tx * sx - 1f; out[13] = ty * sy + 1f
        out[15] = 1f
    }

    /**
     * Writes the axis-aligned document-space bounding box of the [viewW]×[viewH] view into
     * [out] as (minX, minY, maxX, maxY).
     */
    fun visibleDocBounds(viewW: Int, viewH: Int, out: FloatArray) {
        val p = tmp
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until 4) {
            screenToDoc(if (i and 1 == 0) 0f else viewW.toFloat(), if (i < 2) 0f else viewH.toFloat(), p)
            x0 = min(x0, p[0]); y0 = min(y0, p[1]); x1 = max(x1, p[0]); y1 = max(y1, p[1])
        }
        out[0] = x0; out[1] = y0; out[2] = x1; out[3] = y1
    }

    companion object {
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 64f

        /** Rotation snapping tolerance around multiples of 90°, in degrees. */
        const val SNAP_TOLERANCE_DEG = 3f

        /** Normalizes an angle to (−180, 180]. */
        fun normalizeDeg(deg: Float): Float {
            var d = deg - 360f * floor(deg / 360f)
            if (d > 180f) d -= 360f
            return d
        }

        /** Returns the nearest multiple of 90° if [deg] is within [tolerance] of it, else [deg] normalized. */
        fun snapRotation(deg: Float, tolerance: Float = SNAP_TOLERANCE_DEG): Float {
            val n = normalizeDeg(deg)
            val q = (n / 90f).roundToInt() * 90f
            return if (abs(n - q) <= tolerance) normalizeDeg(q) else n
        }
    }
}
