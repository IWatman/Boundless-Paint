"""Painting media: an oil round and flat, gouache, a dry acrylic, an ink wash, a watercolour round.

A paint brush is defined by the medium rather than the grain: the oils pick colour up from the canvas
(`paint.mixIn`) and drag it along the stroke (`paint.pull`), the acrylic rides on the weave of the canvas
(`texture`) and sticks to it (`effects.adhesion`), and the washes stay translucent with a darker edge
(`effects.wetEdges`, `effects.glaze`) plus water in the mix (`paint.dilution`, `paint.wet`). Names and
tags are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import curve, link, preset

OIL_ROUND = preset(
    "paints.oil_round",
    "Oil, round",
    "Масло, круглая",
    ["масло", "oil", "круглая", "round", "живопись", "painting", "корпусная", "impasto"],
    "marker",
    params={
        "size": 24.0,
        "sizeMax": 240.0,
        "opacity": 0.92,
        "flow": 0.7,
        "spacing": 0.04,
        "hardness": 0.6,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.55), (1.0, 1.0)], 0.25),
        "taperStart": 2.0,
        "taperEnd": 8.0,
        "taperSize": 0.9,
        "taperOpacity": 0.5,
        "head": {"aspect": 0.0, "angle": 0.0, "rotationFollow": 0.55, "depth": 0.4},
        "jitter": {"size": 0.03, "angle": 0.3, "scatter": 0.02},
        "paint": {"mixIn": 0.5, "pull": 0.6},
        "effects": {"adhesion": 0.2},
        "dynamics": [link("PRESSURE", "FLOW", [(0.0, 0.5), (1.0, 1.0)])],
    },
    resources={"head": "asset:heads/bristle_round.png"},
)

OIL_FLAT = preset(
    "paints.oil_flat",
    "Oil, flat",
    "Масло, плоская",
    ["масло", "oil", "плоская", "flat", "флейц", "bright", "живопись", "painting"],
    "marker",
    params={
        "size": 32.0,
        "sizeMin": 3.0,
        "sizeMax": 320.0,
        "opacity": 0.94,
        "flow": 0.8,
        "spacing": 0.03,
        "hardness": 0.65,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.3),
        "taperStart": 1.5,
        "taperEnd": 6.0,
        "taperSize": 0.85,
        "taperOpacity": 0.4,
        "head": {"aspect": 0.5, "angle": 0.0, "rotationFollow": 0.9, "depth": 0.35},
        "jitter": {"size": 0.02, "angle": 0.12, "scatter": 0.01},
        "paint": {"mixIn": 0.4},
        "effects": {"adhesion": 0.15},
        "dynamics": [link("PRESSURE", "FLOW", [(0.0, 0.6), (1.0, 1.0)])],
    },
    resources={"head": "asset:heads/bristle_flat.png"},
)

GOUACHE = preset(
    "paints.gouache",
    "Gouache",
    "Гуашь",
    ["гуашь", "gouache", "кроющая", "opaque", "плакат", "poster", "матовый", "matte"],
    "ink",
    params={
        "size": 20.0,
        "sizeMin": 2.0,
        "sizeMax": 200.0,
        "opacity": 1.0,
        "flow": 0.85,
        "spacing": 0.04,
        "hardness": 0.3,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.3),
        "pressureToFlow": curve([(0.0, 0.6), (1.0, 1.0)]),
        "taperStart": 2.0,
        "taperEnd": 8.0,
        "taperSize": 0.9,
        "taperOpacity": 0.35,
        "jitter": {"size": 0.015},
        "paint": {"mixIn": 0.2, "dilution": 0.1},
    },
)

ACRYLIC_DRY = preset(
    "paints.acrylic_dry",
    "Acrylic, dry brush",
    "Акрил, сухая кисть",
    ["акрил", "acrylic", "сухая кисть", "dry brush", "холст", "canvas", "фактура", "texture"],
    "chalk",
    params={
        "size": 26.0,
        "sizeMax": 260.0,
        "opacity": 0.88,
        "flow": 0.42,
        "hardness": 0.6,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.55), (1.0, 1.0)], 0.28),
        "head": {"depth": 0.45, "rotationFollow": 0.5},
        "jitter": {"size": 0.05, "angle": 0.4, "scatter": 0.02},
        "texture": {"depth": 0.9, "lowerLimit": 0.2, "scale": 0.7},
        "effects": {"adhesion": 0.5},
    },
    resources={"head": "asset:heads/bristle_dry.png", "texture": "asset:textures/fabric_canvas.png"},
)

INK_WASH = preset(
    "paints.ink_wash",
    "Ink wash",
    "Тушь, заливка",
    ["тушь", "ink", "заливка", "wash", "лессировка", "glaze", "мягкая", "soft"],
    "ink",
    params={
        "size": 48.0,
        "sizeMin": 4.0,
        "sizeMax": 400.0,
        "opacity": 0.5,
        "flow": 0.6,
        "spacing": 0.04,
        "hardness": 0.15,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.3),
        "pressureToFlow": curve([(0.0, 0.4), (1.0, 1.0)]),
        "taperStart": 4.0,
        "taperEnd": 16.0,
        "taperSize": 0.9,
        "taperOpacity": 0.6,
        "jitter": {"size": 0.02, "scatter": 0.01},
        "effects": {"wetEdges": 0.7, "glaze": 0.6},
    },
)

WATERCOLOR_ROUND = preset(
    "paints.watercolor",
    "Watercolour, round",
    "Акварель, круглая",
    ["акварель", "watercolour", "watercolor", "круглая", "round", "мокро", "wet", "прозрачная", "glaze"],
    "ink",
    params={
        "size": 36.0,
        "sizeMin": 3.0,
        "sizeMax": 300.0,
        "opacity": 0.6,
        "flow": 0.4,
        "spacing": 0.03,
        "hardness": 0.08,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.25),
        "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
        "taperStart": 3.0,
        "taperEnd": 12.0,
        "taperSize": 0.9,
        "taperOpacity": 0.5,
        "jitter": {"size": 0.03, "scatter": 0.02},
        "paint": {"dilution": 0.4, "wet": True},
        "effects": {"wetEdges": 0.5, "glaze": 0.4},
    },
)

CATEGORY = {
    "id": "paints",
    "name": {"en": "Paints", "ru": "Краски"},
    "brushes": [OIL_ROUND, OIL_FLAT, GOUACHE, ACRYLIC_DRY, INK_WASH, WATERCOLOR_ROUND],
}
