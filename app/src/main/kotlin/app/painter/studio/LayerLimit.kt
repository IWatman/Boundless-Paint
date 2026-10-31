package app.painter.studio

/**
 * How many raster layers a [width]×[height] canvas may have (spec/GAPS.md): 40% of the large heap over
 * 4 bytes per pixel (half-float tiles at 50% fill), and the same estimate for the layer tile textures on the
 * GPU, which live outside the Java heap and get their own conservative allowance (debt 6).
 */
object LayerLimit {

    /**
     * Estimated GPU allowance for layer tiles. Android exposes no portable GPU memory figure, so this is a
     * deliberate constant: the composite side is bounded by the renderer's LRU cache, the layer tiles are not.
     */
    const val GPU_BUDGET_BYTES: Long = 192L * 1024 * 1024

    /** Layers that fit [heapBudget] and [gpuBudget], at least 1 and at most 999. */
    fun maxLayers(width: Int, height: Int, heapBudget: Long, gpuBudget: Long = GPU_BUDGET_BYTES): Int {
        if (width <= 0 || height <= 0) return 1
        // Half-float RGBA tiles (8 B/px), half of the canvas covered.
        val perLayer = (width.toLong() * height * 4).coerceAtLeast(1)
        val cpu = heapBudget / perLayer
        val gpu = gpuBudget / perLayer
        return minOf(cpu, gpu).toInt().coerceIn(1, 999)
    }
}
