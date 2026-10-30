"""3D: brushes that paint a shaded tube or ribbon instead of flat dabs (spec/tools/m2-brush.md §1.17).

The dabs write a lit disk through a depth plane, so the stroke reads as a raised object: a round tube, a flat
ribbon (not in the catalog yet) and a tube with twisted bands. Names and tags are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import curve, preset


def _threed(bid: str, en: str, ru: str, tags: list[str], *, kind: str, size: float, twist: float = 1.0,
            aspect: float = 0.0) -> dict:
    """A hard round 3D brush; pressure drives the thickness."""
    head = {"source": "round"}
    if aspect:
        head = {"source": "round", "aspect": aspect, "rotationMode": "tangent"}
    return preset(
        bid,
        en,
        ru,
        ["3d", "объём", "volume"] + tags,
        "ink",
        params={
            "size": size,
            "sizeMin": 4.0,
            "sizeMax": 200.0,
            "opacity": 1.0,
            "flow": 1.0,
            "spacing": 0.015,
            "hardness": 1.0,
            "pressureToSize": curve([(0.0, 0.25), (1.0, 1.0)], 0.4),
            "pressureToFlow": curve([(0.0, 1.0), (1.0, 1.0)]),
            "taperStart": 0.0,
            "taperEnd": 0.0,
            "head": head,
            "special": {"type": "3d", "kind": kind, "twistRate": twist},
        },
    )


TUBE = _threed("threed.tube", "Tube", "Трубка", ["трубка", "tube", "неон", "neon"], kind="TUBE", size=24.0)
THICK = _threed("threed.thick_tube", "Thick tube", "Толстая трубка", ["трубка", "tube", "толстая", "thick"], kind="TUBE", size=48.0)
# TODO: a flat ribbon needs per-pixel shading across its width; the per-dab flat normal shows facets.
RIBBON = _threed("threed.ribbon", "Ribbon", "Лента", ["лента", "ribbon"], kind="RIBBON", size=30.0, twist=0.3)
TWISTY = _threed(
    "threed.twisty", "Twisted ribbon", "Скрученная лента", ["лента", "ribbon", "твист", "twist"],
    kind="TWISTY", size=30.0, twist=1.0,
)

CATEGORY = {
    "id": "threed",
    "name": {"en": "3D", "ru": "3D"},
    "brushes": [TUBE, THICK, TWISTY],
}
