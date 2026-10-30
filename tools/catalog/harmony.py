"""Harmony brushes: many thin lines drawn along one stroke (spec/tools/m2-brush.md §1).

A harmony brush carries a `HarmonyParams` engine in `params.special` (`type: "harmony"`): every dab of the
stroke is expanded into a bundle of thin lines whose `variant` picks the family (`SKETCHY`, `SHADED`,
`WEB`, …), `spread` scales how far the bundle opens across the stroke and `density` how many lines it
holds. So the brush size is the thickness of one line in pixels, not the width of the bundle, and the
pressure curves only modulate that thickness: the dab params keep the size range narrow and the dabs close
together, while opacity stays near 1 so the lines read as ink rather than as a wash. Names and tags are
ours (spec/assets.md); the variants missing from the engine are not used here.
"""
from __future__ import annotations

from .base import curve, preset

SKETCHY = preset(
    "harmony.sketchy",
    "Sketchy line",
    "Эскизная линия",
    ["эскиз", "sketch", "гармония", "harmony", "линия", "line", "штриховка", "hatching"],
    "ink",
    params={
        "size": 1.5,
        "sizeMin": 0.5,
        "sizeMax": 8.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.7),
        "pressureToFlow": curve([(0.0, 0.7), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "harmony", "variant": "SKETCHY", "spread": 1.0, "density": 1.0},
    },
)

SKETCHY_DENSE = preset(
    "harmony.sketchy_dense",
    "Dense sketch",
    "Плотный эскиз",
    ["эскиз", "sketch", "плотный", "dense", "гармония", "harmony", "линия", "line", "штриховка", "hatching"],
    "ink",
    params={
        "size": 1.5,
        "sizeMin": 0.5,
        "sizeMax": 8.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.7),
        "pressureToFlow": curve([(0.0, 0.7), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "harmony", "variant": "SKETCHY", "spread": 1.0, "density": 1.6},
    },
)

SHADED = preset(
    "harmony.shaded",
    "Shading lines",
    "Линии штриховки",
    ["тень", "shading", "штрих", "hatch", "гармония", "harmony", "линия", "line", "тон", "tone"],
    "ink",
    params={
        "size": 1.2,
        "sizeMin": 0.4,
        "sizeMax": 6.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.6),
        "pressureToFlow": curve([(0.0, 0.6), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "harmony", "variant": "SHADED", "spread": 1.0, "density": 1.0},
    },
)

SHADED_WIDE = preset(
    "harmony.shaded_wide",
    "Wide shading",
    "Широкая штриховка",
    [
        "тень",
        "shading",
        "широкая",
        "wide",
        "покрытие",
        "coverage",
        "гармония",
        "harmony",
        "линия",
        "line",
        "тон",
        "tone",
    ],
    "ink",
    params={
        "size": 1.2,
        "sizeMin": 0.4,
        "sizeMax": 6.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.6),
        "pressureToFlow": curve([(0.0, 0.6), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "harmony", "variant": "SHADED", "spread": 1.5, "density": 1.0},
    },
)

WEB = preset(
    "harmony.web",
    "Web lines",
    "Сетчатые линии",
    ["сеть", "web", "сетка", "mesh", "гармония", "harmony", "линия", "line", "паутина", "cobweb"],
    "ink",
    params={
        "size": 1.0,
        "sizeMin": 0.4,
        "sizeMax": 5.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.7),
        "pressureToFlow": curve([(0.0, 0.7), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "harmony", "variant": "WEB", "spread": 1.0, "density": 1.0},
    },
)

WEB_FINE = preset(
    "harmony.web_fine",
    "Fine web",
    "Тонкая сеть",
    ["сеть", "web", "тонкая", "fine", "сетка", "mesh", "гармония", "harmony", "паутина", "cobweb"],
    "ink",
    params={
        "size": 0.8,
        "sizeMin": 0.3,
        "sizeMax": 4.0,
        "opacity": 0.95,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.7),
        "pressureToFlow": curve([(0.0, 0.7), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "harmony", "variant": "WEB", "spread": 1.0, "density": 0.7},
    },
)

CATEGORY = {
    "id": "harmony",
    "name": {"en": "Harmony", "ru": "Гармония"},
    "brushes": [SKETCHY, SKETCHY_DENSE, SHADED, SHADED_WIDE, WEB, WEB_FINE],
}
