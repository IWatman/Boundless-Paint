"""Erasers: a hard rubber, a soft kneaded one and a grainy one.

Eraser presets are ordinary brushes: the mode does the erasing, so only flow, hardness, spacing and
the head differ between them (S1, no texture).
"""
from __future__ import annotations

from .base import curve, preset

HARD = preset(
    "erasers.hard",
    "Hard eraser",
    "Жёсткий ластик",
    ["ластик", "eraser", "hard", "clean"],
    "eraser",
    params={
        "size": 26.0,
        "flow": 1.0,
        "spacing": 0.06,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.55),
        "head": {"source": "round"},
    },
)

SOFT = preset(
    "erasers.soft",
    "Soft eraser",
    "Мягкий ластик",
    ["ластик", "eraser", "soft", "lift"],
    "eraser",
    params={
        "size": 40.0,
        "opacity": 0.9,
        "flow": 0.6,
        "spacing": 0.06,
        "hardness": 0.12,
        "pressureToFlow": curve([(0.0, 0.25), (1.0, 1.0)]),
        "head": {"source": "bitmap"},
        "jitter": {"size": 0.03},
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

GRAINY = preset(
    "erasers.grainy",
    "Grainy eraser",
    "Зернистый ластик",
    ["ластик", "eraser", "grain", "текстура", "texture"],
    "eraser",
    params={
        "size": 34.0,
        "flow": 0.75,
        "spacing": 0.07,
        "hardness": 0.5,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.4),
        "head": {"source": "bitmap", "rotationMode": "random"},
        "jitter": {"size": 0.06, "angle": 1.0, "scatter": 0.04},
    },
    resources={"head": "asset:heads/charcoal_disk_02.png"},
)

CATEGORY = {
    "id": "erasers",
    "name": {"en": "Erasers", "ru": "Ластики"},
    "brushes": [HARD, SOFT, GRAINY],
}
