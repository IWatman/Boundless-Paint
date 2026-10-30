"""Watercolour: brushes whose dabs wet the grid of the watercolour simulation (spec/tools/m2-brush.md §1.9).

The pigment spreads while the paper is wet and settles darker on the rim, so these brushes keep a soft round
head, a low flow and a wet edge on composite that sharpens the rim the simulation builds. Names and tags are ours
(spec/assets.md).
"""
from __future__ import annotations

from .base import curve, preset


def _watercolor(bid: str, en: str, ru: str, tags: list[str], *, size: float, opacity: float, flow: float,
                bleed: float, glaze: float, dryout: float, wet_edges: float) -> dict:
    """A round soft watercolour brush; pressure drives the size and the amount of water."""
    return preset(
        bid,
        en,
        ru,
        ["акварель", "watercolour", "watercolor", "мокро", "wet"] + tags,
        "ink",
        params={
            "size": size,
            "sizeMin": 4.0,
            "sizeMax": 300.0,
            "opacity": opacity,
            "flow": flow,
            "spacing": 0.05,
            "hardness": 0.6,
            "pressureToSize": curve([(0.0, 0.2), (0.5, 0.6), (1.0, 1.0)], 0.25),
            "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
            "taperStart": 0.0,
            "taperEnd": 0.0,
            "head": {"source": "round"},
            "jitter": {"size": 0.03, "scatter": 0.01},
            "effects": {"wetEdges": wet_edges},
            "special": {"type": "watercolor", "bleed": bleed, "glaze": glaze, "dryout": dryout},
        },
    )


WET_WASH = _watercolor(
    "watercolor.wet_wash", "Wet wash", "Мокрая заливка", ["заливка", "wash", "растекание", "bleed"],
    size=80.0, opacity=0.7, flow=0.5, bleed=0.9, glaze=0.5, dryout=0.2, wet_edges=0.5,
)

LIGHT = _watercolor(
    "watercolor.light", "Light watercolour", "Лёгкая акварель", ["лёгкая", "light", "прозрачная", "glaze"],
    size=40.0, opacity=0.45, flow=0.35, bleed=0.5, glaze=0.6, dryout=0.5, wet_edges=0.6,
)

DENSE = _watercolor(
    "watercolor.dense", "Dense watercolour", "Плотная акварель", ["плотная", "dense", "насыщенная", "rich"],
    size=36.0, opacity=0.9, flow=0.8, bleed=0.3, glaze=0.8, dryout=0.7, wet_edges=0.4,
)

# TODO(S8b): "Water (blender)" needs the mix_in pickup of the layer, which the simulation does not have yet.

CATEGORY = {
    "id": "watercolor",
    "name": {"en": "Watercolour", "ru": "Акварель"},
    "brushes": [WET_WASH, LIGHT, DENSE],
}
