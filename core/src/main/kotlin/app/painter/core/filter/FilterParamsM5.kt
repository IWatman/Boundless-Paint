package app.painter.core.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Parameters of the M5 blur, light, texture, geometric, stylization and material filters (spec/filters.md).
 * They belong to the sealed [FilterParams] family and are written by [FilterCodec] exactly like the M3 ones.
 */

/** Edge-preserving smoothing: a blur that keeps strong outlines. */
@Serializable
@SerialName("smooth")
data class SmoothParams(
    /** 1..32 px. */
    val radius: Float = 4f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "smooth"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(1f, 32f)).toInt()
}

/** Polygonal aperture blur with bright highlights weighted more, like a lens stopped down. */
@Serializable
@SerialName("lens-blur")
data class LensBlurParams(
    /** 0..100 px. */
    val radius: Float = 12f,
    /** 0..1, how much brighter samples dominate the average. */
    val highlightBoost: Float = 0.4f,
    /** 0..360 degrees, rotation of the aperture polygon. */
    val bladeAngleDeg: Float = 0f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "lens-blur"
    override val reach: Int get() = ceil(radius.coerceIn(0f, 100f)).toInt() + 1
}

/** Classic high-pass detail: a grey image of the difference from the blurred source. */
@Serializable
@SerialName("high-pass")
data class HighPassParams(
    /** 0.5..32 px. */
    val radius: Float = 8f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "high-pass"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(0.5f, 32f)).toInt()
}

/** Frosted glass: a smooth random displacement of the pixels by up to [distance] px. */
@Serializable
@SerialName("scatter")
data class ScatterParams(
    /** 1..50 px. */
    val distance: Float = 8f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "scatter"
    override val reach: Int get() = ceil(distance.coerceIn(1f, 50f)).toInt() + 2
}

/** Film grain in the colour channels or in the alpha channel on a [size]-px cell grid. */
@Serializable
@SerialName("grain")
data class GrainParams(
    /** mono, rgb or alpha. */
    val variant: String = "mono",
    /** 1..16 px. */
    val size: Int = 2,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "grain"
    override val reach: Int get() = 0
}

/** Local contrast: luminance detail added back at the [radius] scale. */
@Serializable
@SerialName("local-contrast")
data class LocalContrastParams(
    /** 1..32 px. */
    val radius: Float = 12f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "local-contrast"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(1f, 32f)).toInt()
}

/** Out-of-focus highlights: a disc-shaped bloom of the bright pixels. */
@Serializable
@SerialName("bokeh")
data class BokehParams(
    /** 1..64 px. */
    val size: Float = 12f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "bokeh"
    override val reach: Int get() = ceil(size.coerceIn(1f, 64f)).toInt() + 1
}

/** Additive lens flare with a core, a ring and star streaks at [centerX],[centerY] of the content. */
@Serializable
@SerialName("lens-flare")
data class LensFlareParams(
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    /** 1..80 px. */
    val size: Float = 40f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "lens-flare"
    override val reach: Int get() = ceil(size.coerceIn(1f, 80f) * 2.5f).toInt().coerceAtLeast(1)
}

/** Light shafts that gather the pixels brighter than [threshold] toward a centre. */
@Serializable
@SerialName("god-rays")
data class GodRaysParams(
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    /** 1..160 px. */
    val length: Float = 64f,
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.6f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "god-rays"
    override val reach: Int get() = ceil(length.coerceIn(1f, 160f)).toInt() + 2
}

/** Star streaks from the bright pixels, [rays] spikes at [angleDeg]. */
@Serializable
@SerialName("cross")
data class CrossFilterParams(
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.6f,
    /** 1..80 px. */
    val length: Float = 24f,
    /** 0..360 degrees. */
    val angleDeg: Float = 0f,
    /** 2..8 spikes. */
    val rays: Int = 4,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "cross"
    override val reach: Int get() = ceil(length.coerceIn(1f, 80f)).toInt() + 2
}

/*
 * Parameters of the M5 geometric deformations (spec/filters.md, Deformations).
 */

/** Spherical lens: the disc around a centre is magnified toward its middle, the rim stays in place. */
@Serializable
@SerialName("sphere-lens")
data class SphereLensParams(
    /** 8..400 px. */
    val radius: Float = 120f,
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "sphere-lens"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/** Sinusoidal displacement perpendicular to the wave direction. */
@Serializable
@SerialName("wave")
data class WaveParams(
    /** 0..100 px. */
    val amplitude: Float = 12f,
    /** 4..400 px. */
    val wavelength: Float = 64f,
    /** 0..360 degrees. */
    val angleDeg: Float = 0f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "wave"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/** Concentric radial ripples around a centre. */
@Serializable
@SerialName("ripple")
data class RippleParams(
    /** 0..50 px. */
    val amplitude: Float = 8f,
    /** 0.2..20 wave cycles per 100 px. */
    val frequency: Float = 3f,
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "ripple"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/** Rotation that grows toward the centre of a disc and fades to zero at its rim. */
@Serializable
@SerialName("twirl")
data class TwirlParams(
    /** -720..720 degrees. */
    val angleDeg: Float = 180f,
    /** 8..500 px. */
    val radius: Float = 150f,
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "twirl"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/** Wraps the content between a rectangle and a disc. */
@Serializable
@SerialName("polar")
data class PolarCoordinatesParams(
    /** rect-to-polar or polar-to-rect. */
    val variant: String = "rect-to-polar",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "polar"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/** Rectangular bulge: the middle of the content is pushed outward, the border stays fixed. */
@Serializable
@SerialName("expansion")
data class ExpansionParams(
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "expansion"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/** Rotational mirror symmetry of [segments] wedges around a centre. */
@Serializable
@SerialName("kaleidoscope")
data class KaleidoscopeParams(
    /** 1..12 wedges. */
    val segments: Int = 6,
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "kaleidoscope"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/*
 * Parameters of the M5 shape pixelations and pointillism.
 */

/** Voronoi crystallisation: each jittered cell becomes one flat colour. */
@Serializable
@SerialName("crystallize")
data class CrystallizeParams(
    /** 2..100 px. */
    val size: Int = 24,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "crystallize"
    override val reach: Int get() = 3 * size.coerceIn(2, 100) + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Pointy-top hexagonal cells, each filled with its average colour. */
@Serializable
@SerialName("hex-pixelate")
data class HexPixelateParams(
    /** 2..200 px, the circumradius of a hexagon. */
    val size: Int = 16,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "hex-pixelate"
    override val reach: Int get() = 2 * size.coerceIn(2, 200) + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Equilateral triangles in staggered rows, each filled with its average colour. */
@Serializable
@SerialName("triangle-pixelate")
data class TrianglePixelateParams(
    /** 2..200 px, the side of a triangle. */
    val size: Int = 16,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "triangle-pixelate"
    override val reach: Int get() = 2 * size.coerceIn(2, 200) + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Dots of the source colour on a flat background. */
@Serializable
@SerialName("pointillize")
data class PointillizeParams(
    /** 2..64 px, the dot spacing. */
    val size: Int = 8,
    /** "#RRGGBB" between the dots. */
    val background: String = "#FFFFFF",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "pointillize"
    override val reach: Int get() = size.coerceIn(2, 64) + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/*
 * Parameters of the M5 stylization and material filters: gloom, sketch, trace, bevel, emboss, relief, satin,
 * wet edge, waterdrop, extrude and clouds.
 */

/** Shadows below [threshold] deepen and the darkening spreads by [radius] px. */
@Serializable
@SerialName("gloom")
data class GloomParams(
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.5f,
    /** 1..32 px. */
    val radius: Float = 12f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "gloom"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(1f, 32f)).toInt()
    override val needsStableSource: Boolean get() = true
}

/** Pencil drawing: thresholded edges of [thickness] px on paper, keeping only [amount] of them. */
@Serializable
@SerialName("sketch")
data class SketchParams(
    /** 1..8 px. */
    val thickness: Int = 2,
    /** 0..1, how strongly the faint edges survive. */
    val amount: Float = 0.6f,
    /** 0..1, how weak a gradient still counts as an edge. */
    val sensitivity: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "sketch"
    override val reach: Int get() = thickness.coerceIn(1, 8)
    override val needsStableSource: Boolean get() = true
}

/** Dark outline traced along the edges of the source, over the source itself. */
@Serializable
@SerialName("trace")
data class TraceParams(
    /** 0..1 edge darkening. */
    val strength: Float = 0.7f,
) : FilterParams() {
    override val kind: String get() = "trace"
    override val reach: Int get() = 1
    override val needsStableSource: Boolean get() = true
}

/** Bevelled edge of the painted shape: [variant] is inner, outer or both. */
@Serializable
@SerialName("bevel")
data class BevelParams(
    /** inner, outer or both. */
    val variant: String = "inner",
    /** 0..100, contrast of the light and shade. */
    val depth: Float = 60f,
    /** 1..64 px, width of the slope. */
    val size: Int = 6,
    /** 0..360 degrees of the light. */
    val angleDeg: Float = 225f,
    /** "#RRGGBB" of the lit side. */
    val highlight: String = "#FFFFFF",
    /** "#RRGGBB" of the shaded side. */
    val shadow: String = "#000000",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "bevel"
    private val width: Int get() = size.coerceIn(1, 64)
    private val outer: Boolean get() = variant.lowercase().let { it == "outer" || it == "both" }

    override val reach: Int get() = width + width / 2 + 2
    override val writeReach: Int get() = if (outer) width + 2 else 0
    override val needsStableSource: Boolean get() = true
}

/** Greyscale relief from the directional difference of the luminance. */
@Serializable
@SerialName("emboss")
data class EmbossParams(
    /** 0..100, relief depth. */
    val height: Float = 50f,
    /** 0..360 degrees of the light. */
    val angleDeg: Float = 225f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "emboss"
    private val distance: Int get() = 1 + (height.coerceIn(0f, 100f) / 25f).roundToInt()

    override val reach: Int get() = distance + 1
    override val needsStableSource: Boolean get() = true
}

/** Lit relief of the source colours: the directional luminance slope shades the pixels. */
@Serializable
@SerialName("relief")
data class ReliefParams(
    /** 0..100, relief height. */
    val height: Float = 60f,
    /** 0..360 degrees of the light. */
    val angleDeg: Float = 225f,
    /** 0..16 px blur of the height map. */
    val soften: Float = 2f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "relief"
    override val reach: Int get() = ceil(soften.coerceIn(0f, 16f)).toInt() + 2
    override val needsStableSource: Boolean get() = true
}

/** Silky sheen inside the painted shape, [distance] px from its edge. */
@Serializable
@SerialName("satin")
data class SatinParams(
    /** "#RRGGBB" of the sheen. */
    val color: String = "#FFFFFF",
    /** 0..360 degrees of the fold direction. */
    val angleDeg: Float = 30f,
    /** 1..100 px, how far the sheen reaches into the shape. */
    val distance: Int = 24,
    /** 1..32 px softness of the sheen. */
    val size: Int = 8,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "satin"
    private val extent: Int get() = distance.coerceIn(1, 100)
    private val softness: Int get() = size.coerceIn(1, 32)

    override val reach: Int get() = extent + softness + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Watercolour wet edge: pigment darkens in the last [width] px of the painted area. */
@Serializable
@SerialName("wet-edge")
data class WetEdgeParams(
    /** 1..64 px. */
    val width: Int = 8,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "wet-edge"
    private val band: Int get() = width.coerceIn(1, 64)

    override val reach: Int get() = band + band / 3 + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/**
 * A single water drop over the centre of the painted area: a lens bulge with a refractive rim and a
 * specular highlight. It samples one snapshot of the whole content, because the lens radius follows the
 * content size and its refraction reaches well past any fixed tile padding.
 */
@Serializable
@SerialName("waterdrop")
data class WaterdropParams(
    /** 0..100, how strongly the drop magnifies. */
    val height: Float = 60f,
    /** 0..1, brightness of the specular highlight. */
    val gloss: Float = 0.6f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "waterdrop"
    override val reach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/**
 * Solid extrusion of the painted shape along [angleDeg] by [length] px, drawn behind it in [color] with
 * [shading] toward the far end. Every output pixel only reads the source up to [length] px behind it, so a
 * padded tile is enough; the write itself grows the shape by the same distance.
 */
@Serializable
@SerialName("extrude")
data class ExtrudeParams(
    /** 0..360 degrees. */
    val angleDeg: Float = 45f,
    /** 1..100 px. */
    val length: Int = 24,
    /** "#RRGGBB" of the side face. */
    val color: String = "#808080",
    /** 0..1 darkening toward the far end. */
    val shading: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "extrude"
    override val reach: Int get() = length.coerceIn(1, 100) + 1
    override val writeReach: Int get() = length.coerceIn(1, 100) + 1
    override val needsStableSource: Boolean get() = true
}

/**
 * Fractal cloud generator between two colours. It is a Draw generator: it covers the canvas (or the
 * selection) and does not read the layer, so it stays tile-safe by evaluating the noise at canvas
 * coordinates instead of a padded patch.
 */
@Serializable
@SerialName("clouds")
data class CloudsParams(
    /** 1..256 px, the largest cloud feature. */
    val scale: Float = 64f,
    /** 1..8 octaves. */
    val detail: Int = 4,
    val seed: Long = 1L,
    /** "#RRGGBB" of the denser clouds. */
    val colorA: String = "#FFFFFF",
    /** "#RRGGBB" of the gaps. */
    val colorB: String = "#000000",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "clouds"
    override val reach: Int get() = 0
    override val coversCanvas: Boolean get() = true
}

/*
 * Parameters of the M5 artistic and generative filters: palette, QR code, stained glass, dots, stamp, glitch,
 * retro game, chrome, sheer, impressionist, swirl painting and pop art.
 */

/**
 * Palette posterization: the content is reduced to [colors] colours by a deterministic median cut over its own
 * colour histogram, then every pixel takes its nearest palette entry. The palette is built from the whole
 * content, so the filter uses the full-source path and does not depend on tile order.
 */
@Serializable
@SerialName("palette")
data class PaletteParams(
    /** 2..48 colours. */
    val colors: Int = 8,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "palette"
    override val reach: Int get() = 0
    override val writeReach: Int get() = 0
    override val needsFullSource: Boolean get() = true
}

/**
 * QR code generator: UTF-8 [text], [size] pixels per module, a quiet zone of [margin] modules and the module
 * [color]. It is a Draw generator that covers the canvas (or the selection) and does not read the layer, so any
 * tile can draw its own part of the symbol from canvas coordinates.
 */
@Serializable
@SerialName("qr-code")
data class QrCodeParams(
    val text: String = "Boundless Paint",
    /** 1..32 px per module. */
    val size: Int = 8,
    /** 0..8 quiet-zone modules. */
    val margin: Int = 4,
    /** "#RRGGBB" of the dark modules. */
    val color: String = "#000000",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "qr-code"
    override val reach: Int get() = 0
    override val writeReach: Int get() = 0
    override val coversCanvas: Boolean get() = true
}

/** Stained glass: jittered Voronoi cells of one average colour with a [borderWidth] px border. */
@Serializable
@SerialName("stained-glass")
data class StainedGlassParams(
    /** 4..100 px, the lattice pitch of the Voronoi seeds. */
    val cell: Int = 24,
    /** 0..20 px, the drawn border along the cell boundaries. */
    val borderWidth: Float = 2f,
    /** "#RRGGBB" of the lead came between the panes. */
    val borderColor: String = "#000000",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "stained-glass"
    override val reach: Int get() = 3 * cell.coerceIn(4, 100) + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/**
 * Dots mosaic: round dots of the source colour on a hexagonal or square lattice, separated by [color] gaps.
 * The lattice is anchored to canvas coordinates, so neighbouring tiles agree on every dot.
 */
@Serializable
@SerialName("dots")
data class DotsParams(
    /** 1..64 px, the dot diameter. */
    val size: Int = 6,
    /** 2..128 px between dot centres. */
    val spacing: Int = 12,
    /** hex or square. */
    val shape: String = "hex",
    /** "#RRGGBB" of the gaps between dots. */
    val color: String = "#FFFFFF",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "dots"
    override val reach: Int get() = spacing.coerceIn(2, 128) + size.coerceIn(1, 64) + 4
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** One-bit stamp: luminance above [threshold] becomes white, below it black; the painted shape stays. */
@Serializable
@SerialName("stamp")
data class StampParams(
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "stamp"
    override val reach: Int get() = 0
    override val writeReach: Int get() = 0
}

/** Glitch: horizontal slices of [sliceHeight] px shift by up to [amount] px and split the RGB channels. */
@Serializable
@SerialName("glitch")
data class GlitchParams(
    /** 0..64 px of slice displacement. */
    val amount: Int = 12,
    /** 2..128 px, the height of one shifted slice. */
    val sliceHeight: Int = 16,
    val seed: Long = 1L,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "glitch"
    override val reach: Int get() = amount.coerceIn(0, 64) + maxOf(1, amount.coerceIn(0, 64) / 6) + 3
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Retro game: pixel blocks quantized to an NES-like, Game Boy-like or general 16-colour palette. */
@Serializable
@SerialName("retro-game")
data class RetroGameParams(
    /** nes, gb or 16. */
    val palette: String = "nes",
    /** 1..32 px per pixel block. */
    val pixelSize: Int = 4,
    /** 0..1 ordered-dither strength. */
    val dither: Float = 0.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "retro-game"
    override val reach: Int get() = 2 * pixelSize.coerceIn(1, 32) + 2
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Chrome: a polished metal ramp driven by the luminance detail at the [smoothness] scale. */
@Serializable
@SerialName("chrome")
data class ChromeParams(
    /** 0..16 px, the scale of the detail the metal bands respond to. */
    val smoothness: Float = 4f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "chrome"
    override val reach: Int get() = 2 * ceil(smoothness.coerceIn(0f, 16f)).toInt()
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Sheer: a soft diagonal sheen that lifts the pigments without changing the painted shape. */
@Serializable
@SerialName("sheer")
data class SheerParams(
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "sheer"
    override val reach: Int get() = 0
    override val writeReach: Int get() = 0
}

/** Impressionist: short directional dabs of the local colour, [strokeSize] px across. */
@Serializable
@SerialName("impressionist")
data class ImpressionistParams(
    /** 2..32 px, the lattice pitch and the basis of the dab length. */
    val strokeSize: Int = 8,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "impressionist"
    override val reach: Int get() = 4 * strokeSize.coerceIn(2, 32) + 8
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Swirl painting: the content is dragged along a procedural swirl field and smeared with the flow. */
@Serializable
@SerialName("swirl-painting")
data class SwirlPaintingParams(
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "swirl-painting"
    override val reach: Int get() = 40
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}

/** Pop art: a halftone dot screen in saturated source colours on white paper. */
@Serializable
@SerialName("pop-art")
data class PopArtParams(
    /** 0.2..1.2, the dot radius relative to the 12 px cell. */
    val dotScale: Float = 0.8f,
    /** 0..2, the saturation boost of the dot colours. */
    val saturation: Float = 1.6f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "pop-art"
    override val reach: Int get() = 18
    override val writeReach: Int get() = 0
    override val needsStableSource: Boolean get() = true
}
