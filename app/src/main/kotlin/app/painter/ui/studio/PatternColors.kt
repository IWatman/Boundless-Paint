package app.painter.ui.studio

import app.painter.core.color.Rgba
import app.painter.ui.components.HsvUi
import app.painter.ui.components.luma

/** Choose a neutral background that contrasts with the current ink. */
internal fun patternBackgroundHsv(ink: Rgba): HsvUi =
    HsvUi(0f, 0f, if (ink.luma() < 0.5f) 1f else 0f)
