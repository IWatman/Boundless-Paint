package app.painter.core.vector

import app.painter.core.doc.Document
import app.painter.core.doc.VectorLayer
import app.painter.core.doc.effectivelyLocked
import app.painter.core.geom.Vec2
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.BrushResources
import app.painter.core.stroke.StrokeContext
import app.painter.core.stroke.StrokeEngine
import app.painter.core.stroke.StrokeMode
import app.painter.core.stroke.StrokeOptions
import app.painter.core.stroke.StrokeTarget
import app.painter.core.stroke.SymmetryCopies
import app.painter.core.symmetry.SymmetryMath

/**
 * Re-renders the raster cache of a [VectorLayer] with the ordinary [StrokeEngine].
 *
 * Recorded points are post-stage (stabilizer, lazy rope and snapping already applied), so replay disables
 * stabilization and guide stages and only restores the stored seed, zoom, view rotation and symmetry.
 * Selection clipping and guide snapping are not part of a stored stroke: the interactive cache may therefore
 * differ from a replay when the stroke was clipped by a selection. Textured brushes replay only when the
 * caller supplies their [BrushResources]; [app.painter.core.io.ProjectIO] has no brush library and uses
 * [BrushResources.EMPTY].
 */
object VectorRenderService {

    /** Appends [stroke] and paints only it on top of the existing cache; returns its pixel patch, if any. */
    fun appendStroke(
        doc: Document,
        layer: VectorLayer,
        stroke: VectorStroke,
        resources: BrushResources = BrushResources.EMPTY,
    ): TilePatch? {
        if (layer.effectivelyLocked || stroke.points.isEmpty()) return null
        layer.appendStroke(stroke)
        return renderStroke(doc, layer, stroke, resources)
    }

    /** Replaces all paths and rebuilds the cache from scratch; returns every tile the cache touched. */
    fun replaceStrokes(
        doc: Document,
        layer: VectorLayer,
        strokes: List<VectorStroke>,
        resourcesFor: (BrushParams) -> BrushResources = { BrushResources.EMPTY },
    ): Set<TileKey> {
        if (layer.effectivelyLocked) return emptySet()
        val touched = HashSet<TileKey>(layer.pixels.keys())
        layer.setStrokes(strokes)
        layer.pixels.clear()
        for (stroke in strokes) {
            val patch = renderStroke(doc, layer, stroke, resourcesFor(stroke.brush))
            if (patch != null) touched.addAll(patch.after.keys)
        }
        return touched
    }

    /** Rebuilds the cache from the current paths, e.g. after loading a project. */
    fun renderAll(
        doc: Document,
        layer: VectorLayer,
        resourcesFor: (BrushParams) -> BrushResources = { BrushResources.EMPTY },
    ): Set<TileKey> = replaceStrokes(doc, layer, layer.strokes.toList(), resourcesFor)

    /** Paints one stored stroke on the layer cache without touching [VectorLayer.strokes]. */
    fun renderStroke(
        doc: Document,
        layer: VectorLayer,
        stroke: VectorStroke,
        resources: BrushResources = BrushResources.EMPTY,
    ): TilePatch? {
        if (layer.effectivelyLocked || stroke.points.isEmpty()) return null
        val points = stroke.points.map(VectorPoint::toInputPoint)
        val symmetry = stroke.symmetry?.takeIf { it.on }?.let {
            SymmetryMath.copies(it, Vec2(points.first().x, points.first().y))
        } ?: SymmetryCopies.NONE
        val mode = if (stroke.mode == StrokeMode.ERASE) StrokeMode.ERASE else StrokeMode.PAINT
        val engine = StrokeEngine(
            doc = doc,
            target = StrokeTarget.Layer(layer.id),
            brush = Brush.of(stroke.brush),
            res = resources,
            color = stroke.color,
            mode = mode,
            ctx = StrokeContext(
                zoom = if (stroke.zoom > 0f) stroke.zoom else 1f,
                viewRotation = stroke.viewRotation,
                seed = stroke.seed,
            ),
            options = StrokeOptions(stabilize = false, copies = symmetry),
        )
        engine.begin(points.first())
        engine.add(points.drop(1))
        return engine.endPatch()
    }
}
