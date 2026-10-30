"""Filter effects: two blurs, a sharpen and two glows.

A filter brush does not lay down colour: `params.special` carries a `FilterBrushParams` engine
(`type: "filter"`) with the filter kind, its strength and `onStroke = false`, so the engine runs the
filter once over the layer instead of on every dab. The head only describes the cursor, so the presets
stay a soft (or crisp, for the sharpen) round dab: a size, a spacing and a hardness. Names and tags are
ours (spec/assets.md).
"""
from __future__ import annotations

from .base import preset

BLUR_SOFT = preset(
    "effects.blur_soft",
    "Blur, soft",
    "Размытие, мягкое",
    ["размытие", "blur", "мягкое", "soft", "фильтр", "filter", "эффект", "effect"],
    "ink",
    params={
        "size": 60.0,
        "sizeMin": 8.0,
        "sizeMax": 400.0,
        "spacing": 0.2,
        "hardness": 0.1,
        "head": {"source": "round"},
        "special": {"type": "filter", "filter": "BLUR", "onStroke": False, "strength": 0.5},
    },
)

BLUR_STRONG = preset(
    "effects.blur_strong",
    "Blur, strong",
    "Размытие, сильное",
    ["размытие", "blur", "сильное", "strong", "фильтр", "filter", "сглаживание", "smoothing"],
    "ink",
    params={
        "size": 60.0,
        "sizeMin": 8.0,
        "sizeMax": 400.0,
        "spacing": 0.2,
        "hardness": 0.1,
        "head": {"source": "round"},
        "special": {"type": "filter", "filter": "BLUR", "onStroke": False, "strength": 1.0},
    },
)

SHARPEN = preset(
    "effects.sharpen",
    "Sharpen",
    "Резкость",
    ["резкость", "sharpen", "фильтр", "filter", "контур", "edge", "детали", "detail"],
    "ink",
    params={
        "size": 40.0,
        "sizeMin": 4.0,
        "sizeMax": 240.0,
        "spacing": 0.15,
        "hardness": 0.8,
        "head": {"source": "round"},
        "special": {"type": "filter", "filter": "SHARPEN", "onStroke": False, "strength": 0.6},
    },
)

GLOW = preset(
    "effects.glow",
    "Glow",
    "Свечение",
    ["свечение", "glow", "фильтр", "filter", "блик", "bloom", "мягкий свет", "soft light"],
    "ink",
    params={
        "size": 80.0,
        "sizeMin": 8.0,
        "sizeMax": 480.0,
        "spacing": 0.2,
        "hardness": 0.05,
        "head": {"source": "round"},
        "special": {"type": "filter", "filter": "GLOW", "onStroke": False, "strength": 0.6},
    },
)

GLOW_SOFT = preset(
    "effects.glow_soft",
    "Glow, soft dodge",
    "Свечение, мягкое осветление",
    ["свечение", "glow", "осветление", "dodge", "фильтр", "filter", "мягкое", "soft"],
    "ink",
    params={
        "size": 60.0,
        "sizeMin": 6.0,
        "sizeMax": 360.0,
        "spacing": 0.2,
        "hardness": 0.2,
        "head": {"source": "round"},
        "special": {"type": "filter", "filter": "GLOW", "onStroke": False, "strength": 0.3},
    },
)

CATEGORY = {
    "id": "effects",
    "name": {"en": "Effects", "ru": "Эффекты"},
    "brushes": [BLUR_SOFT, BLUR_STRONG, SHARPEN, GLOW, GLOW_SOFT],
}
