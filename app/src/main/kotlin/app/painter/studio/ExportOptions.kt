package app.painter.studio

/** Image formats the export dialog offers (spec/tools/m0-m1-tools.md T10). */
enum class ExportFormat(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPG("image/jpeg", "jpg"),
    /** 8-bit CMYK TIFF; no alpha, always composited on the paper. */
    CMYK_TIFF("image/tiff", "tiff"),
}

/**
 * What the export dialog returns: [format], JPG [quality] (1..100) and a transparent background — the last one
 * only exists for PNG, JPG has no alpha channel.
 */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.PNG,
    val quality: Int = 95,
    val transparent: Boolean = false,
) {
    /** True only for PNG with the switch on: JPG always keeps the paper. */
    val transparentBackground: Boolean get() = format == ExportFormat.PNG && transparent
}
