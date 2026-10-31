"""Markers: a chisel, a round one, a highlighter, a felt tip and an alcohol marker.

All four sit on the marker template: a wide translucent dab, little pressure response and no taper. The
highlighter keeps `buildup` off, so overlapping dabs stay at its low opacity instead of darkening. The
alcohol marker multiplies its stroke into the layer, so a second layer of ink over the first darkens it the way
real alcohol ink does. No texture, no paint mixing, no special engines.
"""
from __future__ import annotations

from .base import curve, link, preset

CHISEL = preset(
    "markers.chisel",
    "Chisel marker",
    "Маркер-скос",
    ["маркер", "marker", "chisel", "скос", "broad"],
    "marker",
    params={
        "size": 20.0,
        "opacity": 0.8,
        "flow": 0.9,
        "spacing": 0.02,
        "hardness": 0.62,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.8),
        "head": {"aspect": -0.35, "angle": 45.0, "rotationMode": "tangent", "rotationFollow": 0.9},
        "jitter": {"size": 0.02},
    },
)

ROUND = preset(
    "markers.round",
    "Round marker",
    "Круглый маркер",
    ["маркер", "marker", "round", "заливка", "fill"],
    "marker",
    params={
        "size": 16.0,
        "opacity": 0.72,
        "flow": 0.9,
        "spacing": 0.025,
        "hardness": 0.72,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.8),
        "head": {"source": "bitmap", "aspect": 0.0, "angle": 0.0, "rotationMode": "fixed", "rotationFollow": 0.0},
        "jitter": {"size": 0.02},
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

HIGHLIGHTER = preset(
    "markers.highlighter",
    "Highlighter",
    "Текстовыделитель",
    ["текстовыделитель", "highlighter", "прозрачный", "wash", "studying"],
    "marker",
    params={
        "size": 18.0,
        "sizeMin": 4.0,
        "sizeMax": 80.0,
        "opacity": 0.32,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.5,
        "buildup": False,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.9),
        "pressureToFlow": curve([(0.0, 0.9), (1.0, 1.0)]),
        "head": {"aspect": -0.7, "angle": -35.0, "rotationMode": "fixed", "rotationFollow": 0.0},
        "jitter": {"size": 0.01},
        "dynamics": [link("PRESSURE", "FLOW", [(0.0, 0.85), (1.0, 1.0)])],
    },
)

FELT_TIP = preset(
    "markers.felt_tip",
    "Felt tip pen",
    "Фломастер",
    ["фломастер", "felt tip", "liner", "color", "детский", "kids"],
    "marker",
    params={
        "size": 6.0,
        "sizeMin": 1.0,
        "sizeMax": 40.0,
        "opacity": 0.9,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.9,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.75),
        "head": {"aspect": -0.5, "angle": 0.0, "rotationMode": "fixed", "rotationFollow": 0.0},
        "jitter": {"size": 0.02},
        "dynamics": [link("PRESSURE", "FLOW", [(0.0, 0.7), (1.0, 1.0)])],
    },
)

ALCOHOL = preset(
    "markers.alcohol",
    "Alcohol marker",
    "Спиртовой маркер",
    ["маркер", "marker", "спиртовой", "alcohol", "умножение", "multiply", "слои", "layering"],
    "marker",
    params={
        "size": 22.0,
        "opacity": 0.6,
        "flow": 0.9,
        "spacing": 0.025,
        "hardness": 0.8,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.8),
        "head": {"source": "bitmap", "aspect": 0.0, "angle": 0.0, "rotationMode": "fixed", "rotationFollow": 0.0},
        "effects": {"blendMode": "multiply", "blendStrength": 1.0},
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

FADE = preset(
    "markers.fade",
    "Marker, fading",
    "Маркер, выцветающий",
    ["маркер", "marker", "выцветание", "fade", "прозрачность", "opacity", "лёгкий", "light"],
    "marker",
    params={
        "size": 24.0,
        "opacity": 0.8,
        "flow": 0.85,
        "spacing": 0.03,
        "hardness": 0.6,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.7),
        # A light touch lays a fainter dab instead of a thinner one (per-dab opacity, not flow).
        "dynamics": [link("PRESSURE", "OPACITY", [(0.0, 0.35), (1.0, 1.0)])],
    },
)

CATEGORY = {
    "id": "markers",
    "name": {"en": "Markers", "ru": "Маркеры"},
    "brushes": [CHISEL, ROUND, HIGHLIGHTER, FELT_TIP, ALCOHOL, FADE],
}
