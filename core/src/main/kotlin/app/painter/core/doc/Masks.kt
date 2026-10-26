package app.painter.core.doc

import app.painter.core.raster.MaskGrid

/**
 * Document selection (spec/tools/m2-select-transform-fill.md §2.1). Never null: an empty mask means
 * "nothing selected". Tools are masked only while the selection is in effect ([coverageOrNull]).
 */
class Selection(width: Int, height: Int) {
    val mask = MaskGrid(width, height, 0)
    var active: Boolean = true

    val isEmpty: Boolean get() = mask.isUniform

    /** The mask when the selection is in effect (active and non-empty), else null. */
    fun coverageOrNull(): MaskGrid? = if (active && !isEmpty) mask else null

    fun copyFrom(o: Selection) {
        mask.clear()
        for ((k, t) in o.mask.entries()) mask.put(k, t.copy())
        active = o.active
    }
}

/** Raster mask of a layer or group (spec/tools/m2-layers-adjust.md L3). 0 hides, 255 reveals. */
class LayerMask(val tiles: MaskGrid) {
    var enabled: Boolean = true
    /** Moves together with the layer content in transforms. */
    var linked: Boolean = true
    /** Strength 0..1: effective = 1 - density * (1 - mask). */
    var density: Float = 1f

    fun deepCopy(): LayerMask = LayerMask(tiles.copy()).also {
        it.enabled = enabled; it.linked = linked; it.density = density
    }
}

enum class ColorTag { NONE, RED, ORANGE, YELLOW, GREEN, BLUE, VIOLET, GRAY }

/** What painting tools edit on the active node. */
enum class EditTarget { LAYER, MASK }
