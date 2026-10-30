package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D stamp (spec/tools/m2-brush.md §1.17): the dabs of the stroke paint nothing of their own, they stamp a shaded
 * disk into the stroke buffer through a `z` plane owned by the effect. Every dab writes the height `z = n_z·r` of
 * its virtual sphere at the pixel, and a pixel takes a dab only when `z_new ≥ z_buf`, so overlapping dabs at a small
 * spacing expose the top surface of a single smooth tube instead of stacking their alphas.
 *
 * The shading uses a fixed light from the top-left `L` and `H = normalize(L + (0,0,1))`, both in screen space, so
 * the highlight of a straight horizontal tube stays on one row and the dark side is bottom-right. The straight
 * colour is `c·(0.35 + 0.65·max(0, n·L)) + 0.4·(n·H)²⁴`, clamped to 0..1 and written premultiplied by the dab
 * alpha and the selection clip.
 *
 * | kind | normal |
 * |---|---|
 * | [ThreeDKind.TUBE] | `n = (x, y, sqrt(1 − r²))`, `x, y` the pixel offset over the radius |
 * | [ThreeDKind.RIBBON] | a flat band: `up = (0,0,1)` rotated around the path tangent by `s·twist_rate` |
 * | [ThreeDKind.TWISTY] | the tube normal times four bands along `φ = atan2(nₓ, n_z) + s·twist_rate` |
 *
 * `s` is the path length so far in px and `twist_rate` is taken in degrees per px. [reset] drops the `z` plane and
 * the path state so a re-rendered stroke rebuilds them from the recorded points.
 */
internal class ThreeDEffect(
    params: ThreeDParams,
    private val width: Int,
    private val height: Int,
    private val color: Rgba,
    private val clip: MaskGrid? = null,
) {
    private val kind = params.kind
    private val twistRate = params.twistRate

    /** Height of the top surface per pixel, kept in the red channel of a precise grid owned by the effect. */
    private val zPlane = TileGrid(width, height, precise = true)

    /** Path length so far, px (`s` of the twist), and the last dab centre that advanced it. */
    private var s = 0f
    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f

    /** Unit tangent of the path from the last pair of dab centres; NaN until a segment is known. */
    private var tangentX = Float.NaN
    private var tangentY = 0f

    /** Stamps every dab of [batch] into [target] (a premultiplied stroke buffer), in order. */
    fun add(batch: DabBatch, target: TileAccess) {
        for (i in 0 until batch.size) {
            val dab = batch[i]
            if (hasLast) {
                val dx = dab.x - lastX
                val dy = dab.y - lastY
                val seg = sqrt(dx * dx + dy * dy)
                if (seg > MIN_SEGMENT) {
                    s += seg
                    tangentX = dx / seg
                    tangentY = dy / seg
                }
            }
            lastX = dab.x
            lastY = dab.y
            hasLast = true
            stamp(dab, target)
        }
    }

    /** Drops the `z` plane and the path state: a re-rendered stroke replays its dabs and rebuilds both. */
    fun reset() {
        zPlane.clear()
        s = 0f
        hasLast = false
        tangentX = Float.NaN
        tangentY = 0f
    }

    /** Stamps one disk of [dab] into [target], keeping only the pixels its own surface does not lose on `z`. */
    private fun stamp(dab: Dab, target: TileAccess) {
        val radius = dab.d * 0.5f
        if (radius <= 0f) return
        val flow = (dab.flow * dab.a).coerceIn(0f, 1f)
        if (flow <= 0f) return
        val cx = dab.x
        val cy = dab.y
        val rOut = radius + 0.5f
        val x0 = max(0, floor(cx - rOut).toInt())
        val x1 = min(width - 1, floor(cx + rOut).toInt())
        val y0 = max(0, floor(cy - rOut).toInt())
        val y1 = min(height - 1, floor(cy + rOut).toInt())
        if (x0 > x1 || y0 > y1) return
        // Twist angle of the dab, shared by RIBBON and TWISTY.
        val twist = s * twistRate * DEG
        // RIBBON is flat: its normal, and so its colour, are the same for every pixel of the disk.
        val flat = kind == ThreeDKind.RIBBON
        val tx = if (tangentX.isNaN()) cos(dab.angle) else tangentX
        val ty = if (tangentX.isNaN()) sin(dab.angle) else tangentY
        val rnx = ty * sin(twist)
        val rny = -tx * sin(twist)
        val rnz = cos(twist)
        val cr = color.r
        val cg = color.g
        val cb = color.b
        var tileKey = Long.MIN_VALUE
        var buf: Tile? = null
        var zb: Tile? = null
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val d2 = dx * dx + dy * dy
                if (d2 >= rOut * rOut) continue
                val dist = sqrt(d2)
                // One-pixel AA ramp of the disk edge; the selection clips its alpha below.
                val cover = (rOut - dist).coerceIn(0f, 1f)
                if (cover <= 0f) continue
                // Surface height of this dab at the pixel: the sphere of the tube, shared by all kinds for order.
                val rr = dist / radius
                val nz = if (rr < 1f) sqrt(1f - rr * rr) else 0f
                val z = nz * radius
                val key = TileKey(x shr Tile.SHIFT, y shr Tile.SHIFT)
                if (key.packed != tileKey) {
                    tileKey = key.packed
                    zb = zPlane.getOrCreate(key.tx, key.ty)
                    buf = target.prepare(key, create = true)
                }
                val zt = zb ?: continue
                val zi = zt.index(x and Tile.MASK, y and Tile.MASK)
                if (z < zt[zi]) continue
                var a = flow * cover
                val m = clip?.value(x, y) ?: 1f
                if (m < 1f) a *= m
                if (a <= 0f) continue
                zt[zi] = z
                val t = buf ?: continue
                val nx = dx / radius
                val ny = dy / radius
                // Normal of the shading: flat for RIBBON, the tube sphere otherwise.
                val nnx = if (flat) rnx else nx
                val nny = if (flat) rny else ny
                val nnz = if (flat) rnz else nz
                val diff = 0.35f + 0.65f * max(0f, nnx * LX + nny * LY + nnz * LZ)
                val spec = specular(max(0f, nnx * HX + nny * HY + nnz * HZ))
                var band = 1f
                if (kind == ThreeDKind.TWISTY) {
                    val phi = atan2(nx, nz) + twist
                    band = 0.5f + 0.5f * cos(BANDS * phi)
                }
                val sr = ((cr * diff + spec) * band).coerceIn(0f, 1f)
                val sg = ((cg * diff + spec) * band).coerceIn(0f, 1f)
                val sb = ((cb * diff + spec) * band).coerceIn(0f, 1f)
                val p = t.index(x and Tile.MASK, y and Tile.MASK)
                t[p] = sr * a
                t[p + 1] = sg * a
                t[p + 2] = sb * a
                t[p + 3] = a
                t.markDirty()
            }
        }
    }

    /** Specular term `0.4·x²⁴` of the highlight, `x = max(0, n·H)`. */
    private fun specular(x: Float): Float {
        if (x <= 0f) return 0f
        val x2 = x * x
        val x4 = x2 * x2
        val x8 = x4 * x4
        val x16 = x8 * x8
        return 0.4f * x16 * x8
    }

    private companion object {
        /** Norms of the raw light `L = (-0.5,-0.6,0.62)` and half vector `H = normalize(L + (0,0,1))`. */
        val LEN = sqrt(0.5f * 0.5f + 0.6f * 0.6f + 0.62f * 0.62f)
        val HLEN = sqrt(0.5f * 0.5f + 0.6f * 0.6f + 1.62f * 1.62f)

        /** Light `L` from the top-left, normalized. */
        val LX = -0.5f / LEN
        val LY = -0.6f / LEN
        val LZ = 0.62f / LEN

        /** Half vector `H = normalize(L + (0,0,1))`. */
        val HX = -0.5f / HLEN
        val HY = -0.6f / HLEN
        val HZ = 1.62f / HLEN

        /** Degrees to radians. */
        val DEG = (PI / 180.0).toFloat()

        /** Bright bands of [ThreeDKind.TWISTY] around the tube. */
        const val BANDS = 4f

        /** A dab closer than this to the previous one keeps the old tangent, px. */
        const val MIN_SEGMENT = 1e-4f
    }
}
