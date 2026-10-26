package app.painter.core.color

/** Straight (non-premultiplied) sRGB-encoded color, components 0..1. */
data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    companion object {
        val BLACK = Rgba(0f, 0f, 0f)
        val WHITE = Rgba(1f, 1f, 1f)
        val TRANSPARENT = Rgba(0f, 0f, 0f, 0f)
    }
}
