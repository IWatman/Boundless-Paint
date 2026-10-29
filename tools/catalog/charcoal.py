"""Charcoal: vine, compressed and a charcoal pencil.

Charcoal is the darkest dry medium, so the dabs overlap loosely (a low flow, a big scatter) and the
charcoal grain texture with a low floor keeps the broken, dusty edge. The pencil is the controlled
member of the family: a small head, a short taper and a shallower grain. Names and tags are ours
(spec/assets.md).
"""
from __future__ import annotations

from .base import curve, preset

VINE = preset(
    "charcoal.vine",
    "Vine charcoal",
    "Древесный уголь",
    ["уголь", "charcoal", "древесный", "vine", "скетч", "sketch"],
    "charcoal",
    params={
        "size": 24.0,
        "opacity": 0.9,
        "flow": 0.45,
        "hardness": 0.25,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.25),
        "taperStart": 6.0,
        "taperEnd": 20.0,
        "head": {"depth": 0.3},
        "jitter": {"size": 0.09, "angle": 0.9, "scatter": 0.06},
        "texture": {"depth": 0.8, "lowerLimit": 0.3, "scale": 0.9},
    },
    resources={"head": "asset:heads/charcoal_disk_01.png", "texture": "asset:textures/grain_charcoal.png"},
)

COMPRESSED = preset(
    "charcoal.compressed",
    "Compressed charcoal",
    "Прессованный уголь",
    ["уголь", "charcoal", "прессованный", "compressed", "тёмный", "dark"],
    "charcoal",
    params={
        "size": 30.0,
        "opacity": 0.98,
        "flow": 0.6,
        "hardness": 0.35,
        "pressureToFlow": curve([(0.0, 0.25), (1.0, 1.0)]),
        "taperStart": 4.0,
        "taperEnd": 12.0,
        "head": {"depth": 0.35},
        "jitter": {"size": 0.06, "angle": 0.7, "scatter": 0.04},
        "texture": {"depth": 0.7, "lowerLimit": 0.35, "scale": 0.8},
    },
    resources={"head": "asset:heads/charcoal_disk_02.png", "texture": "asset:textures/grain_charcoal.png"},
)

PENCIL = preset(
    "charcoal.pencil",
    "Charcoal pencil",
    "Угольный карандаш",
    ["угольный карандаш", "charcoal pencil", "контур", "outline", "деталь", "detail"],
    "charcoal",
    params={
        "size": 8.0,
        "sizeMin": 1.5,
        "sizeMax": 60.0,
        "opacity": 0.92,
        "flow": 0.55,
        "spacing": 0.05,
        "hardness": 0.6,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.5), (1.0, 1.0)], 0.2),
        "taperStart": 2.0,
        "taperEnd": 8.0,
        "head": {"depth": 0.2},
        "jitter": {"size": 0.04, "angle": 0.5, "scatter": 0.02},
        "texture": {"depth": 0.75, "lowerLimit": 0.4, "scale": 0.6},
    },
    resources={"head": "asset:heads/charcoal_disk_02.png", "texture": "asset:textures/grain_charcoal.png"},
)

CATEGORY = {
    "id": "charcoal",
    "name": {"en": "Charcoal", "ru": "Уголь"},
    "brushes": [VINE, COMPRESSED, PENCIL],
}
