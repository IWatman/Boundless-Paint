package app.painter.core.history

import app.painter.core.doc.Node
import app.painter.core.raster.TileGrid

/**
 * Rendering used by merge/flatten commands, so that `core/history` does not depend on the compositor.
 * Implemented by `app.painter.core.blend.Compositor`.
 */
interface NodeRenderer {
    /**
     * Composites sibling [nodes] (bottom-to-top) onto [dst] with the stack rules of a full render: blend modes,
     * opacity, masks, clipping (to the nearest non-clipped node of [nodes] below), group isolation and adjustment
     * layers acting on [dst]. Leading clipped nodes clip to [clipBase] alpha; without it they are not drawn.
     * With [ignoreVisibility] hidden entries of [nodes] are drawn too (their hidden descendants are not).
     */
    fun renderInto(dst: TileGrid, nodes: List<Node>, ignoreVisibility: Boolean = false, clipBase: TileGrid? = null)
}
