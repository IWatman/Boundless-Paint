"""Pencils: graphite grades, a mechanical pencil and a coloured pencil.

S1 only, so no paper texture: the tooth of the paper is faked with the grain heads, a low flow and a
small size/angle jitter. Names and tags are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import curve, link, preset

HB = preset(
    "pencils.hb",
    "HB",
    "HB",
    ["графит", "graphite", "скетч", "sketch", "medium"],
    "pencil",
    params={
        "size": 5.0,
        "opacity": 0.88,
        "flow": 0.5,
        "spacing": 0.07,
        "hardness": 0.88,
        "jitter": {"size": 0.03, "angle": 0.4, "scatter": 0.01},
    },
    resources={"head": "asset:heads/grain_disk_02.png"},
)

TWO_B = preset(
    "pencils.2b",
    "2B",
    "2B",
    ["графит", "graphite", "тёмный", "dark", "shading"],
    "pencil",
    params={
        "size": 6.5,
        "flow": 0.62,
        "spacing": 0.06,
        "hardness": 0.8,
        "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
        "taperEnd": 10.0,
        "jitter": {"size": 0.05, "angle": 0.5, "scatter": 0.02},
    },
    resources={"head": "asset:heads/grain_disk_01.png"},
)

SIX_B_SOFT = preset(
    "pencils.6b_soft",
    "6B soft",
    "6B мягкий",
    ["графит", "graphite", "мягкий", "soft", "charcoal"],
    "pencil",
    params={
        "size": 9.0,
        "opacity": 0.95,
        "flow": 0.7,
        "spacing": 0.05,
        "hardness": 0.6,
        "taperStart": 4.0,
        "taperEnd": 14.0,
        "taperOpacity": 0.4,
        "jitter": {"size": 0.07, "angle": 0.7, "scatter": 0.03},
        "dynamics": [link("VELOCITY", "FLOW", [(0.0, 0.0), (1.0, 1.0)], 0.45)],
    },
    resources={"head": "asset:heads/charcoal_disk_01.png"},
)

MECHANICAL = preset(
    "pencils.mechanical",
    "Mechanical pencil",
    "Механический карандаш",
    ["механический", "mechanical", "0.5", "technical", "lineart"],
    "pencil",
    params={
        "size": 2.4,
        "sizeMin": 0.8,
        "sizeMax": 12.0,
        "opacity": 0.95,
        "flow": 0.6,
        "spacing": 0.04,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.5),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "jitter": {"size": 0.02, "angle": 0.2, "scatter": 0.0},
    },
    resources={"head": "asset:heads/grain_disk_03.png"},
)

COLORED = preset(
    "pencils.colored",
    "Colored pencil",
    "Цветной карандаш",
    ["цветной", "colored", "wax", "иллюстрация", "illustration"],
    "pencil",
    params={
        "size": 7.0,
        "opacity": 0.85,
        "flow": 0.65,
        "spacing": 0.06,
        "hardness": 0.7,
        "taperEnd": 10.0,
        "jitter": {"size": 0.04, "angle": 0.8, "scatter": 0.01},
    },
    resources={"head": "asset:heads/grain_disk_04.png"},
)

CATEGORY = {
    "id": "pencils",
    "name": {"en": "Pencils", "ru": "Карандаши"},
    "brushes": [HB, TWO_B, SIX_B_SOFT, MECHANICAL, COLORED],
}
