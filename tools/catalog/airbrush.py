"""Airbrushes: a soft one, a hard one and a spray.

The soft airbrush turns `buildup` on, so repeated passes darken; the hard one caps at its opacity and
keeps a defined edge, and the spray scatters a grainy head with size jitter. S1 only: no texture, no
paint mixing, no special engines.
"""
from __future__ import annotations

from .base import curve, link, preset

SOFT = preset(
    "airbrush.soft",
    "Soft airbrush",
    "Мягкий аэрограф",
    ["аэрограф", "airbrush", "soft", "градиент", "gradient"],
    "airbrush",
    params={
        "size": 70.0,
        "sizeMin": 4.0,
        "sizeMax": 400.0,
        "opacity": 0.5,
        "flow": 0.1,
        "spacing": 0.03,
        "hardness": 0.1,
        "buildup": True,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.5), (1.0, 1.0)], 0.08),
        "pressureToFlow": curve([(0.0, 0.0), (1.0, 1.0)], 0.03),
        "head": {"source": "bitmap"},
        "jitter": {"size": 0.06, "scatter": 0.04},
        "dynamics": [link("VELOCITY", "FLOW", [(0.0, 0.0), (1.0, 1.0)], 0.3)],
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

HARD = preset(
    "airbrush.hard",
    "Hard airbrush",
    "Жёсткий аэрограф",
    ["аэрограф", "airbrush", "hard", "плотный", "opaque"],
    "airbrush",
    params={
        "size": 26.0,
        "sizeMin": 2.0,
        "sizeMax": 200.0,
        "opacity": 0.85,
        "flow": 0.45,
        "spacing": 0.04,
        "hardness": 0.8,
        "pressureToSize": curve([(0.0, 0.0), (0.6, 0.6), (1.0, 1.0)], 0.15),
        "pressureToFlow": curve([(0.0, 0.15), (1.0, 1.0)]),
        "head": {"source": "bitmap"},
        "jitter": {"size": 0.03, "scatter": 0.01},
        "dynamics": [link("PRESSURE", "FLOW", [(0.0, 0.3), (1.0, 1.0)])],
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

SPRAY = preset(
    "airbrush.spray",
    "Spray",
    "Распылитель",
    ["спрей", "spray", "брызги", "splatter", "тонер", "tone"],
    "airbrush",
    params={
        "size": 60.0,
        "sizeMin": 6.0,
        "sizeMax": 300.0,
        "opacity": 0.6,
        "flow": 0.35,
        "spacing": 0.12,
        "hardness": 0.85,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.25),
        "pressureToFlow": curve([(0.0, 0.1), (1.0, 1.0)]),
        "head": {"source": "bitmap", "rotationMode": "random"},
        "jitter": {"size": 0.5, "scatter": 0.35},
        "dynamics": [
            link("PRESSURE", "FLOW", [(0.0, 0.2), (1.0, 1.0)]),
            link("VELOCITY", "SCATTER", [(0.0, 1.0), (1.0, 0.3)]),
        ],
    },
    resources={"head": "asset:heads/spray.png"},
)

CATEGORY = {
    "id": "airbrush",
    "name": {"en": "Airbrushes", "ru": "Аэрографы"},
    "brushes": [SOFT, HARD, SPRAY],
}
