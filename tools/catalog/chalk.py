"""Chalk and pastel: chalk, dry and oil pastel, a wax crayon.

Dry media live off the grain: the dab is ragged and soft, while a canvas-fixed paper texture with a
low floor breaks the stroke into the tooth of the sheet. Oil pastel and crayon use a shallower grain
because their binder fills the paper. Names and tags are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import curve, link, preset

CHALK = preset(
    "chalk.chalk",
    "Chalk",
    "Мел",
    ["мел", "chalk", "доска", "blackboard", "dry"],
    "chalk",
    params={
        "size": 16.0,
        "opacity": 0.85,
        "flow": 0.5,
        "hardness": 0.35,
        "head": {"depth": 0.25},
        "jitter": {"size": 0.07, "angle": 0.5, "scatter": 0.04},
        "texture": {"depth": 0.8, "lowerLimit": 0.3, "scale": 0.7},
    },
    resources={"head": "asset:heads/chalk_01.png", "texture": "asset:textures/grain_chalk.png"},
)

SOFT_PASTEL = preset(
    "chalk.soft_pastel",
    "Soft pastel",
    "Сухая пастель",
    ["пастель", "pastel", "сухая", "soft", "портрет", "portrait"],
    "chalk",
    params={
        "size": 26.0,
        "opacity": 0.95,
        "flow": 0.62,
        "hardness": 0.28,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.35),
        "taperStart": 6.0,
        "taperEnd": 20.0,
        "head": {"aspect": -0.3, "rotationFollow": 0.5, "depth": 0.3},
        "jitter": {"size": 0.05, "angle": 0.4, "scatter": 0.03},
        "texture": {"depth": 0.9, "lowerLimit": 0.25, "scale": 0.85},
    },
    resources={"head": "asset:heads/pastel_square.png", "texture": "asset:textures/paper_rough.png"},
)

OIL_PASTEL = preset(
    "chalk.oil_pastel",
    "Oil pastel",
    "Масляная пастель",
    ["масляная пастель", "oil pastel", "воск", "wax", "жирный", "bold"],
    "chalk",
    params={
        "size": 22.0,
        "opacity": 0.98,
        "flow": 0.75,
        "hardness": 0.45,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.4),
        "head": {"aspect": -0.35, "depth": 0.15},
        "jitter": {"size": 0.03, "angle": 0.3, "scatter": 0.02},
        "texture": {"depth": 0.6, "lowerLimit": 0.4, "scale": 0.75},
        "dynamics": [link("PRESSURE", "TEX_DEPTH", [(0.0, 0.7), (1.0, 1.0)])],
    },
    resources={"head": "asset:heads/pastel_square.png", "texture": "asset:textures/grain_crayon.png"},
)

CRAYON = preset(
    "chalk.crayon",
    "Crayon",
    "Восковой мелок",
    ["восковой мелок", "crayon", "wax", "детский", "kids", "bulk"],
    "chalk",
    params={
        "size": 14.0,
        "opacity": 0.9,
        "flow": 0.65,
        "hardness": 0.5,
        "pressureToFlow": curve([(0.0, 0.35), (1.0, 1.0)]),
        "head": {"depth": 0.18},
        "jitter": {"size": 0.05, "angle": 0.5, "scatter": 0.02},
        "texture": {"depth": 0.65, "lowerLimit": 0.35, "scale": 0.8},
    },
    resources={"head": "asset:heads/crayon.png", "texture": "asset:textures/grain_chalk.png"},
)

CATEGORY = {
    "id": "chalk",
    "name": {"en": "Chalk & pastel", "ru": "Мел и пастель"},
    "brushes": [CHALK, SOFT_PASTEL, OIL_PASTEL, CRAYON],
}
