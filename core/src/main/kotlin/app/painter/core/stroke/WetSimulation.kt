package app.painter.core.stroke

import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * CPU watercolor model (spec/tools/m2-brush.md В§1.9): the dabs do not paint, they deposit water and pigment into a
 * sparse grid that [tick] diffuses, pulls to the edges and evaporates.
 *
 * One cell is 2Г—2 document px; a wet tile covers exactly one pixel [Tile] (128Г—128 cells) and is keyed by that
 * tile's [TileKey], so [write] maps cells to pixels without a second index.
 *
 * | element | rule |
 * |---|---|
 * | deposit | `W += amt`, `P += colourВ·amt/2` with `amt = maskВ·flowВ·opacity`, alpha-clamped (premultiplied) |
 * | water | `W' = W + 0.25В·bleedВ·ОЈ(Wn в€’ W)/4`, then `W в€’= (0.15 + 1.35В·dryout)В·DT` |
 * | pigment | `P' = P + 0.2В·bleedВ·ОЈ w(c,n)В·(Pn в€’ P)/4`, `w = min(W, Wn)` only where both cells are wet |
 * | rim | `В± 0.1В·glazeВ·(Wn в€’ W)` transports pigment towards the drier cell (the darker edge of a puddle) |
 * | drying | below [DRY] a cell is dry: it keeps its pigment and stops diffusing |
 *
 * The tick is double-buffered: every cell is computed from the old state of the whole grid (also across tile
 * borders), so a stroke replays identically. Single-threaded; water alone is simulated, the pigment is premultiplied
 * and written straight into the stroke buffer.
 *
 * TODO(S8b): tick on worker threads; mix_in pickup of the layer; paper grain; drying after pen-up on a timer.
 */
internal class WetSimulation(
    params: WatercolorParams,
    private val width: Int,
    private val height: Int,
    private val clip: MaskGrid? = null,
) {
    /** Bleed: how much water and pigment one cell hands to its neighbours per tick. */
    // Brush files may come from outside (import), so every parameter is made finite and bounded here.
    private val bleed = sanitize(params.bleed, 0.5f)

    /** Glaze: strength of the edge transport (the darker rim of a drying puddle). */
    private val glaze = sanitize(params.glaze, 0.5f)

    /** Dryout: evaporation speed; higher means a shorter wet phase. */
    private val dryout = sanitize(params.dryout, 0.5f)

    /** Mix-in of the layer under the brush; not picked up yet (TODO(S8b)). */
    @Suppress("unused")
    private val mixIn = sanitize(params.mixIn, 0f)

    /** Cells of the document, one cell per [CELL_PX]ВІ px. */
    private val cellsX = (width + CELL_PX - 1) / CELL_PX
    private val cellsY = (height + CELL_PX - 1) / CELL_PX

    /** Wet tiles that fit inside the document. */
    private val tilesX = (cellsX + CELLS - 1) / CELLS
    private val tilesY = (cellsY + CELLS - 1) / CELLS

    /** Wet tiles keyed by [TileKey.packed], so the iteration order is deterministic. */
    private val tiles = TreeMap<Long, WetTile>()

    /** Pixel tiles to hand to [write]. */
    private val dirty = LinkedHashSet<TileKey>()

    /** Clock remainder of [advance], ms. */
    private var acc = 0f

    /** Creation order of the wet tiles, for the [MAX_TILES] eviction. */
    private var born = 0L

    /** Deposits every dab of [batch]: water and pigment are added to the cells under the dab mask. */
    fun deposit(batch: DabBatch) {
        if (cellsX <= 0 || cellsY <= 0) return
        for (n in 0 until batch.size) {
            val dab = batch[n]
            val d = dab.d
            if (!(d > 0f) || !dab.x.isFinite() || !dab.y.isFinite()) continue
            val mask = DabMask(dab)
            val half = max(d * 0.5f + 1f, dab.halfExtent())
            val i0 = max(floor((dab.x - half - 1f) * INV_CELL_PX).toInt(), 0)
            val i1 = min(ceil((dab.x + half - 1f) * INV_CELL_PX).toInt(), cellsX - 1)
            val j0 = max(floor((dab.y - half - 1f) * INV_CELL_PX).toInt(), 0)
            val j1 = min(ceil((dab.y + half - 1f) * INV_CELL_PX).toInt(), cellsY - 1)
            if (i0 > i1 || j0 > j1) continue
            val ca = sanitize(dab.a, 0f)
            val cr = sanitize(dab.r, 0f)
            val cg = sanitize(dab.g, 0f)
            val cb = sanitize(dab.b, 0f)
            for (j in j0..j1) {
                val cy = j * CELL_PX + 1
                val dy = cy - dab.y
                if (abs(dy) > half) continue
                for (i in i0..i1) {
                    val cx = i * CELL_PX + 1
                    val dx = cx - dab.x
                    if (abs(dx) > half) continue
                    val m = mask.cov(dx, dy)
                    if (!(m > 0f)) continue
                    val cv = clip?.value(cx.coerceIn(0, width - 1), cy.coerceIn(0, height - 1)) ?: 1f
                    val amt = m * cv * dab.flow * dab.opacity
                    if (!(amt > 0f) || !amt.isFinite()) continue
                    val tile = wetTile(i shr CELL_SHIFT, j shr CELL_SHIFT, create = true) ?: continue
                    val idx = ((j and CELL_MASK) shl CELL_SHIFT) + (i and CELL_MASK)
                    val water = min(MAX_WATER, tile.w[idx] + amt)
                    tile.w[idx] = water
                    if (water >= DRY) tile.wet = true
                    // Half the deposit: the pigment a dab leaves is denser than the water that carries it.
                    val k = idx * CHANNELS
                    val add = amt * 0.5f
                    var pr = tile.p[k] + cr * ca * add
                    var pg = tile.p[k + 1] + cg * ca * add
                    var pb = tile.p[k + 2] + cb * ca * add
                    var pa = tile.p[k + 3] + ca * add
                    if (pa > 1f) {
                        val inv = 1f / pa
                        pr *= inv
                        pg *= inv
                        pb *= inv
                        pa = 1f
                    }
                    tile.p[k] = channel(pr, pa)
                    tile.p[k + 1] = channel(pg, pa)
                    tile.p[k + 2] = channel(pb, pa)
                    tile.p[k + 3] = pa
                    tile.touched = true
                }
            }
        }
        for (tile in tiles.values) {
            if (!tile.touched) continue
            tile.touched = false
            markDirty(tile.key)
        }
    }

    /** One explicit step of [DT] over every tile that has a wet cell; see [step] for the per-cell rules. */
    fun tick() {
        if (tiles.isEmpty()) return
        val active = ArrayList<WetTile>(tiles.size)
        for (tile in tiles.values) if (tile.wet) active.add(tile)
        if (active.isEmpty()) return
        // Water that leaves a tile over a seam needs a tile to land in: without one the cell next to it reads as dry
        // and the flow stops at the border row.
        val grown = ArrayList<WetTile>(4)
        for (tile in active) growBorder(tile, grown)
        active.addAll(grown)
        for (tile in active) step(tile)
        for (tile in active) {
            tile.swap()
            markDirty(tile.key)
        }
    }

    /** Feeds [elapsedMs] of wall clock into the tick clock; at most [MAX_TICKS_PER_ADVANCE] ticks per call. */
    fun advance(elapsedMs: Long) {
        if (elapsedMs <= 0L) return
        acc += elapsedMs.toFloat()
        val ticks = (acc / TICK_MS).toInt()
        if (ticks <= 0) return
        // The whole ticks are consumed even when the surplus is dropped, so the remainder stays below one tick.
        acc -= ticks * TICK_MS
        repeat(min(ticks, MAX_TICKS_PER_ADVANCE)) { tick() }
    }

    /** True when no tile has a wet cell left. */
    val isDry: Boolean
        get() {
            for (tile in tiles.values) if (tile.wet) return false
            return true
        }

    /** Ticks until [isDry] or [DRY_LIMIT_MS], then clears all water; returns the number of ticks run. */
    fun dry(): Int {
        val limit = (DRY_LIMIT_MS / TICK_MS).toInt()
        var n = 0
        while (n < limit && !isDry) {
            tick()
            n++
        }
        for (tile in tiles.values) {
            tile.w.fill(0f)
            tile.nw.fill(0f)
            tile.wet = false
            tile.wetNext = false
        }
        return n
    }

    /** Overwrites the pixels of the dirty tiles with a bilinear sample of the pigment; the target is this stroke. */
    fun write(target: TileAccess) {
        if (dirty.isEmpty()) return
        val quad = FloatArray(4 * CHANNELS)
        val sample = FloatArray(CHANNELS)
        for (key in dirty) {
            val px0 = key.tx shl Tile.SHIFT
            val py0 = key.ty shl Tile.SHIFT
            if (px0 >= width || py0 >= height || px0 + Tile.SIZE <= 0 || py0 + Tile.SIZE <= 0) continue
            val own = tiles[key.packed]
            val tile = target.prepare(key, create = own != null) ?: continue
            val nx = min(Tile.SIZE, width - px0)
            val ny = min(Tile.SIZE, height - py0)
            for (py in 0 until ny) {
                val v = (py0 + py + 0.5f) * INV_CELL_PX - 0.5f
                val cy0 = floor(v).toInt()
                val fy = v - cy0
                // Without a wet tile of its own only the outer pixel ring samples cells of the neighbours.
                val ring = own == null && py != 0 && py != ny - 1
                for (px in 0 until nx) {
                    if (ring && px != 0 && px != nx - 1) continue
                    val u = (px0 + px + 0.5f) * INV_CELL_PX - 0.5f
                    val cx0 = floor(u).toInt()
                    val fx = u - cx0
                    // Neighbour cells may live in another wet tile (or outside the document: then they read 0).
                    readCell(own, cx0, cy0, quad, 0)
                    readCell(own, cx0 + 1, cy0, quad, 4)
                    readCell(own, cx0, cy0 + 1, quad, 8)
                    readCell(own, cx0 + 1, cy0 + 1, quad, 12)
                    for (ch in 0 until CHANNELS) {
                        val top = quad[ch] + (quad[4 + ch] - quad[ch]) * fx
                        val bottom = quad[8 + ch] + (quad[12 + ch] - quad[8 + ch]) * fx
                        val s = top + (bottom - top) * fy
                        sample[ch] = if (s.isFinite()) s else 0f
                    }
                    // Paper grain: the pigment settles thicker in the pits of the paper.
                    val g0 = grainAt(own, cx0, cy0)
                    val g1 = grainAt(own, cx0 + 1, cy0)
                    val g2 = grainAt(own, cx0, cy0 + 1)
                    val g3 = grainAt(own, cx0 + 1, cy0 + 1)
                    val gTop = g0 + (g1 - g0) * fx
                    val gn = gTop + (g2 + (g3 - g2) * fx - gTop) * fy
                    val grain = 1f + GRAIN * (2f * gn - 1f)
                    val alpha = (sample[3] * grain).coerceIn(0f, 1f)
                    tile.write(
                        tile.index(px, py),
                        channel(sample[0] * grain, alpha),
                        channel(sample[1] * grain, alpha),
                        channel(sample[2] * grain, alpha),
                        alpha,
                    )
                }
            }
            tile.markDirty()
        }
        dirty.clear()
    }

    /** Copy of the pixel tiles [write] would fill, for the caller. */
    fun dirtyKeys(): Set<TileKey> = LinkedHashSet(dirty)

    /** Drops all wet tiles and the tick clock. */
    fun reset() {
        tiles.clear()
        dirty.clear()
        acc = 0f
        born = 0L
    }

    /** ОЈ of the pigment alpha over all cells. */
    fun pigmentMass(): Double {
        var mass = 0.0
        for (tile in tiles.values) {
            var i = 3
            while (i < tile.p.size) {
                mass += tile.p[i]
                i += CHANNELS
            }
        }
        return mass
    }

    /** Water of the cell holding document pixel ([x], [y]); 0 when the cell is missing. */
    fun waterAt(x: Int, y: Int): Float {
        val tile = cellTile(x, y) ?: return 0f
        return tile.w[cellIndex(x, y)]
    }

    /** Premultiplied pigment of the cell holding pixel ([x], [y]) into [out]; 0 when the cell is missing. */
    fun pigmentAt(x: Int, y: Int, out: FloatArray) {
        val tile = cellTile(x, y)
        if (tile == null) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            out[3] = 0f
            return
        }
        val idx = cellIndex(x, y) * CHANNELS
        out[0] = tile.p[idx]
        out[1] = tile.p[idx + 1]
        out[2] = tile.p[idx + 2]
        out[3] = tile.p[idx + 3]
    }

    /** The wet tile at tile coordinates ([tx], [ty]); created only inside the document and only when [create]. */
    private fun wetTile(tx: Int, ty: Int, create: Boolean): WetTile? {
        val key = TileKey(tx, ty)
        tiles[key.packed]?.let { return it }
        if (!create || tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) return null
        if (tiles.size >= MAX_TILES) forceDryOldest()
        val tile = WetTile(key, born++)
        fillPaper(tile)
        tiles[key.packed] = tile
        return tile
    }

    /** Removes the water of the oldest-created tile; it keeps its pigment and is not ticked any more. */
    private fun forceDryOldest() {
        var oldest: WetTile? = null
        for (tile in tiles.values) if (oldest == null || tile.born < oldest.born) oldest = tile
        val tile = oldest ?: return
        tile.w.fill(0f)
        tile.nw.fill(0f)
        tile.wet = false
        tile.wetNext = false
    }

    /**
     * Marks the pixel tile of [key] dirty, plus its 8 neighbours inside the document: their border pixels sample its
     * cells even when they have no wet tile of their own.
     */
    private fun markDirty(key: TileKey) {
        for (dy in -1..1) {
            for (dx in -1..1) {
                val tx = key.tx + dx
                val ty = key.ty + dy
                if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) continue
                dirty.add(TileKey(tx, ty))
            }
        }
    }

    /**
     * Creates the tile next to every wet border row or column of [tile], so that the flow across a tile seam is the
     * same as inside one tile; the new tiles are collected in [out] and are ticked in this very step.
     */
    private fun growBorder(tile: WetTile, out: ArrayList<WetTile>) {
        val w = tile.w
        var left = false
        var right = false
        var top = false
        var bottom = false
        for (k in 0 until CELLS) {
            if (w[k shl CELL_SHIFT] >= DRY) left = true
            if (w[(k shl CELL_SHIFT) + CELL_MASK] >= DRY) right = true
            if (w[k] >= DRY) top = true
            if (w[CELL_MASK * CELLS + k] >= DRY) bottom = true
        }
        val tx = tile.key.tx
        val ty = tile.key.ty
        if (left) ensureTile(tx - 1, ty, out)
        if (right) ensureTile(tx + 1, ty, out)
        if (top) ensureTile(tx, ty - 1, out)
        if (bottom) ensureTile(tx, ty + 1, out)
    }

    /**
     * Queues the tile at ([tx], [ty]) inside the document: a dry one that already exists (it still has to take the
     * water coming over the seam), or a new one (with the [MAX_TILES] eviction).
     */
    private fun ensureTile(tx: Int, ty: Int, out: ArrayList<WetTile>) {
        if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) return
        val existing = tiles[TileKey(tx, ty).packed]
        if (existing != null) {
            if (!existing.wet && existing !in out) out.add(existing)
            return
        }
        val tile = wetTile(tx, ty, create = true) ?: return
        out.add(tile)
    }

    /**
     * Fills [WetTile.paper] with two octaves of value noise on a fixed seed, so every stroke meets the same paper:
     * water runs further along the open grain (ragged edges and blooms) and the pigment settles in its pits.
     */
    private fun fillPaper(tile: WetTile) {
        val ox = tile.key.tx shl CELL_SHIFT
        val oy = tile.key.ty shl CELL_SHIFT
        for (j in 0 until CELLS) {
            for (i in 0 until CELLS) {
                val idx = (j shl CELL_SHIFT) + i
                tile.paper[idx] = 0.6f * valueNoise(ox + i, oy + j, 6, CH_PAPER) + 0.4f * valueNoise(ox + i, oy + j, 16, CH_PAPER + 1)
                tile.grain[idx] = valueNoise(ox + i, oy + j, 3, CH_PAPER + 2)
            }
        }
    }

    /** Smooth value noise 0..1 of cell ([cx], [cy]) on a lattice of [scale] cells, stream [ch]. */
    private fun valueNoise(cx: Int, cy: Int, scale: Int, ch: Int): Float {
        val gx = Math.floorDiv(cx, scale)
        val gy = Math.floorDiv(cy, scale)
        var fx = (cx - gx * scale + 0.5f) / scale
        var fy = (cy - gy * scale + 0.5f) / scale
        fx = fx * fx * (3f - 2f * fx)
        fy = fy * fy * (3f - 2f * fy)
        val a = lattice(gx, gy, ch)
        val b = lattice(gx + 1, gy, ch)
        val c = lattice(gx, gy + 1, ch)
        val d = lattice(gx + 1, gy + 1, ch)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    private fun lattice(gx: Int, gy: Int, ch: Int): Float =
        HashRng.rnd(PAPER_SEED, ch, (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL))

    /**
     * Recomputes every cell of [tile] into its second arrays from the old state of the whole grid: water diffusion,
     * pigment diffusion, edge transport, then evaporation.
     */
    private fun step(tile: WetTile) {
        val w = tile.w
        val p = tile.p
        val nw = tile.nw
        val np = tile.np
        val ox = tile.key.tx shl CELL_SHIFT
        val oy = tile.key.ty shl CELL_SHIFT
        val water = 0.25f * bleed
        val pigment = 0.2f * bleed / 4f
        val edge = EDGE * glaze / 4f
        val paper = tile.paper
        val evap = (0.15f + 1.35f * dryout) * DT
        tile.wetNext = false
        for (j in 0 until CELLS) {
            val cy = oy + j
            for (i in 0 until CELLS) {
                val cx = ox + i
                val idx = (j shl CELL_SHIFT) + i
                val k = idx * CHANNELS
                if (cx >= cellsX || cy >= cellsY) {
                    // A cell past the document edge always reads as dry, so it has to stay empty.
                    nw[idx] = 0f
                    np[k] = 0f
                    np[k + 1] = 0f
                    np[k + 2] = 0f
                    np[k + 3] = 0f
                    continue
                }
                val w0 = w[idx]
                val wet0 = w0 >= DRY
                val pr = p[k]
                val pg = p[k + 1]
                val pb = p[k + 2]
                val pa = p[k + 3]
                var flow = 0f
                var wetNear = false
                // Pigment weights over the 4 neighbours: P' = PВ·(1 + ko) + ОЈ knВ·Pn.
                var ko = 0f
                var nr = 0f
                var ng = 0f
                var nb = 0f
                var na = 0f
                for (d in 0 until 4) {
                    val ax = cx + NX[d]
                    val ay = cy + NY[d]
                    val near = neighbourTile(tile, ax, ay)
                    if (near == null) {
                        flow -= w0
                        continue
                    }
                    val ni = ((ay and CELL_MASK) shl CELL_SHIFT) + (ax and CELL_MASK)
                    val wn = near.w[ni]
                    // Permeability of the pair, symmetric so that water and pigment stay conserved.
                    val open = PERM_MIN + (PERM_MAX - PERM_MIN) * 0.5f * (paper[idx] + near.paper[ni])
                    flow += (wn - w0) * open
                    if (wn < DRY) continue
                    wetNear = true
                    if (!wet0) continue
                    val nk = ni * CHANNELS
                    var kn = pigment * min(w0, wn) * open
                    ko -= kn
                    if (w0 > wn) {
                        // The cell is the wetter one: pigment is dragged out of it.
                        ko -= edge * (w0 - wn)
                    } else if (wn > w0) {
                        // The neighbour is the wetter one: its pigment is dragged in.
                        kn += edge * (wn - w0)
                    }
                    nr += kn * near.p[nk]
                    ng += kn * near.p[nk + 1]
                    nb += kn * near.p[nk + 2]
                    na += kn * near.p[nk + 3]
                }
                // A dry cell with no wet neighbour is left alone, evaporation included.
                val w1 = if (wet0 || wetNear) w0 + water * flow / 4f - evap else w0
                val wn1 = w1.coerceIn(0f, MAX_WATER)
                nw[idx] = wn1
                if (wn1 >= DRY) tile.wetNext = true
                np[k] = pr + ko * pr + nr
                np[k + 1] = pg + ko * pg + ng
                np[k + 2] = pb + ko * pb + nb
                np[k + 3] = pa + ko * pa + na
            }
        }
    }

    /**
     * The wet tile of cell ([cx], [cy]), with [hint] tried first so that the cells of one tile need no map lookup;
     * null for a missing tile or a cell outside the document, which both read as dry.
     */
    private fun neighbourTile(hint: WetTile, cx: Int, cy: Int): WetTile? {
        if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY) return null
        val tx = cx shr CELL_SHIFT
        val ty = cy shr CELL_SHIFT
        if (hint.key.tx == tx && hint.key.ty == ty) return hint
        return tiles[TileKey(tx, ty).packed]
    }

    /** The wet tile holding pixel ([x], [y]), or null outside the document. */
    private fun cellTile(x: Int, y: Int): WetTile? {
        val cx = x shr 1
        val cy = y shr 1
        if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY) return null
        return tiles[TileKey(cx shr CELL_SHIFT, cy shr CELL_SHIFT).packed]
    }

    /** Grain of cell ([cx], [cy]) ([WetTile.grain]), 0.5 for a missing tile or outside the document. */
    private fun grainAt(hint: WetTile?, cx: Int, cy: Int): Float {
        if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY) return 0.5f
        val tx = cx shr CELL_SHIFT
        val ty = cy shr CELL_SHIFT
        val tile = if (hint != null && hint.key.tx == tx && hint.key.ty == ty) hint else tiles[TileKey(tx, ty).packed]
        return tile?.grain?.get(((cy and CELL_MASK) shl CELL_SHIFT) + (cx and CELL_MASK)) ?: 0.5f
    }

    /** Index of the cell holding pixel ([x], [y]) within its wet tile. */
    private fun cellIndex(x: Int, y: Int): Int {
        val cx = x shr 1
        val cy = y shr 1
        return ((cy and CELL_MASK) shl CELL_SHIFT) + (cx and CELL_MASK)
    }

    /**
     * Premultiplied pigment of cell ([cx], [cy]) into [out] at [off]; a missing or outside cell reads 0. [hint] is
     * tried first, so the cells of one tile need no map lookup.
     */
    private fun readCell(hint: WetTile?, cx: Int, cy: Int, out: FloatArray, off: Int) {
        val tile = if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY) {
            null
        } else if (hint != null && hint.key.tx == cx shr CELL_SHIFT && hint.key.ty == cy shr CELL_SHIFT) {
            hint
        } else {
            tiles[TileKey(cx shr CELL_SHIFT, cy shr CELL_SHIFT).packed]
        }
        if (tile == null) {
            out[off] = 0f
            out[off + 1] = 0f
            out[off + 2] = 0f
            out[off + 3] = 0f
            return
        }
        val idx = (((cy and CELL_MASK) shl CELL_SHIFT) + (cx and CELL_MASK)) * CHANNELS
        out[off] = tile.p[idx]
        out[off + 1] = tile.p[idx + 1]
        out[off + 2] = tile.p[idx + 2]
        out[off + 3] = tile.p[idx + 3]
    }

    /** One wet tile: water [w] and premultiplied pigment [p] per cell, plus the [nw]/[np] tick targets. */
    private class WetTile(val key: TileKey, val born: Long) {
        var w = FloatArray(CELLS * CELLS)
        var p = FloatArray(CELLS * CELLS * CHANNELS)
        var nw = FloatArray(CELLS * CELLS)
        var np = FloatArray(CELLS * CELLS * CHANNELS)

        /** Paper of every cell, 0..1 ([fillPaper]): how readily it takes water. */
        val paper = FloatArray(CELLS * CELLS)

        /** Fine grain of the paper, 0..1: where the settled pigment lies thicker. */
        val grain = FloatArray(CELLS * CELLS)

        /** True when at least one cell holds water; recomputed at the end of every tick. */
        var wet = false

        /** The same flag for the state the running tick writes. */
        var wetNext = false

        /** Set when [deposit] changed a cell, so the pixel tile is reported dirty once per deposit. */
        var touched = false

        /** Makes the tick result the current state. */
        fun swap() {
            val tw = w
            w = nw
            nw = tw
            val tp = p
            p = np
            np = tp
            wet = wetNext
        }
    }

    private companion object {
        /** Length of one explicit tick, s. */
        const val DT = 1f / 30f

        /** Length of one tick, ms вЂ” the clock [advance] follows. */
        const val TICK_MS = 1000f / 30f

        /** A cell below this water amount is dry. */
        const val DRY = 0.01f

        /** Cap of the water of one cell. */
        const val MAX_WATER = 1f

        /** Cap of the live wet tiles; above it the oldest-created tile is force-dried. */
        const val MAX_TILES = 64

        /** Cap of the ticks one [advance] call may run; the rest of the backlog is dropped. */
        const val MAX_TICKS_PER_ADVANCE = 8

        /** Cap of the drying loop of [dry], ms. */
        const val DRY_LIMIT_MS = 4000

        /** Document px per cell. */
        const val CELL_PX = 2

        /** Reciprocal of [CELL_PX]. */
        const val INV_CELL_PX = 1f / CELL_PX

        /** Cells per side of a wet tile: one pixel [Tile] holds [CELLS]ВІ cells. */
        const val CELLS = Tile.SIZE / CELL_PX

        /** log2 of [CELLS]. */
        const val CELL_SHIFT = 7

        /** [CELLS] - 1. */
        const val CELL_MASK = CELLS - 1

        /** Strength of the edge transport per unit of glaze (spec: 0.1, raised so the rim reads on the sheet). */
        const val EDGE = 0.35f

        /** Permeability range of the paper: water crosses an open pair of cells this many times faster. */
        const val PERM_MIN = 0.3f
        const val PERM_MAX = 1.7f

        /** Depth of the paper grain in the settled pigment, В± of the alpha. */
        const val GRAIN = 0.06f

        /** Fixed paper seed and its noise streams: the same paper under every stroke. */
        const val PAPER_SEED = 0x5A1E_7A9EL
        const val CH_PAPER = 24

        /** Channels of one pigment cell (premultiplied RGBA). */
        const val CHANNELS = 4

        /** Cell x offsets of the 4-neighbourhood [tick] follows: +x, в€’x, +y, в€’y. */
        val NX = intArrayOf(1, -1, 0, 0)

        /** Cell y offsets matching [NX]. */
        val NY = intArrayOf(0, 0, 1, -1)

        /** [v], made finite (falls back to [fallback]) and clamped to 0..1. */
        fun sanitize(v: Float, fallback: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else fallback

        /** [v] limited to 0..[alpha], the valid range of a premultiplied colour channel. */
        fun channel(v: Float, alpha: Float): Float = if (v.isFinite()) v.coerceIn(0f, alpha) else 0f
    }
}
