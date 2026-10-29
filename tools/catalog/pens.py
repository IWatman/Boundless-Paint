"""Pens: a fineliner, a ballpoint, a brush pen, a technical pen and a calligraphy nib.

All five sit on the ink template, so they are saturated and even; what sets them apart is the pressure
curve, the tapers and the dab shape. S1 only: no texture, no paint mixing, no special engines.
"""
from __future__ import annotations

from .base import curve, preset

FINELINER = preset(
    "pens.fineliner",
    "Fineliner",
    "Линер",
    ["линер", "fineliner", "ink", "контур", "lineart"],
    "ink",
    params={
        "size": 2.2,
        "sizeMin": 0.8,
        "sizeMax": 12.0,
        "opacity": 1.0,
        "flow": 0.95,
        "spacing": 0.03,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.8),
        "pressureToFlow": curve([(0.0, 0.7), (1.0, 1.0)]),
        "jitter": {"size": 0.01},
    },
)

BALLPOINT = preset(
    "pens.ballpoint",
    "Ballpoint pen",
    "Шариковая ручка",
    ["ручка", "pen", "ballpoint", "скетч", "sketch"],
    "ink",
    params={
        "size": 2.6,
        "sizeMin": 0.8,
        "sizeMax": 10.0,
        "opacity": 0.92,
        "flow": 0.7,
        "spacing": 0.04,
        "hardness": 0.85,
        "pressureToSize": curve([(0.0, 0.0), (0.4, 0.5), (1.0, 1.0)], 0.55),
        "pressureToFlow": curve([(0.0, 0.2), (1.0, 1.0)]),
        "taperStart": 1.5,
        "taperEnd": 5.0,
        "taperOpacity": 0.25,
        "jitter": {"size": 0.02, "angle": 0.3},
    },
)

BRUSH_PEN = preset(
    "pens.brush_pen",
    "Brush pen",
    "Кистевая ручка",
    ["кисть", "brush", "леттеринг", "lettering", "calligraphy"],
    "ink",
    params={
        "size": 14.0,
        "sizeMin": 1.0,
        "sizeMax": 90.0,
        "opacity": 1.0,
        "flow": 0.9,
        "spacing": 0.035,
        "hardness": 0.7,
        "pressureToSize": curve([(0.0, 0.0), (0.2, 0.15), (0.6, 0.6), (1.0, 1.0)], 0.05),
        "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
        "taperStart": 12.0,
        "taperEnd": 20.0,
        "taperOpacity": 0.2,
        "head": {"source": "bitmap"},
        "jitter": {"size": 0.03, "angle": 0.15, "scatter": 0.005},
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

TECHNICAL = preset(
    "pens.technical",
    "Technical pen",
    "Рапидограф",
    ["рапидограф", "technical", "constant", "чертёж", "drafting"],
    "ink",
    params={
        "size": 1.6,
        "sizeMin": 0.5,
        "sizeMax": 8.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.025,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "pressureToFlow": curve([(0.0, 1.0), (1.0, 1.0)]),
        "head": {"source": "round"},
    },
)

CALLIGRAPHY = preset(
    "pens.calligraphy",
    "Calligraphy pen",
    "Каллиграфическое перо",
    ["каллиграфия", "calligraphy", "flat", "леттеринг", "lettering"],
    "ink",
    params={
        "size": 20.0,
        "sizeMin": 2.0,
        "sizeMax": 150.0,
        "opacity": 1.0,
        "flow": 0.95,
        "spacing": 0.02,
        "hardness": 0.9,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.6),
        "head": {"source": "bitmap", "aspect": 0.8, "angle": -40.0, "rotationMode": "fixed"},
        "jitter": {"size": 0.02},
    },
    resources={"head": "asset:heads/rounded_square.png"},
)

CATEGORY = {
    "id": "pens",
    "name": {"en": "Pens", "ru": "Ручки"},
    "brushes": [FINELINER, BALLPOINT, BRUSH_PEN, TECHNICAL, CALLIGRAPHY],
}
