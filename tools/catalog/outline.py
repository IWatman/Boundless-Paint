"""Outline and shadow: double brushes that draw the dab twice, a body and a shifted copy behind it.

A double brush carries a `DoubleParams` engine in `params.special` (`type: "double"`) with three knobs
that matter here: `shadowSize` (how much bigger the shadow dab is than the body), `distance` (the offset
between the two in pixels, along `angle`) and `shadowColor`. The body itself stays the paint colour, so
with `distance = 0` and a white shadow the copy becomes an outline around the whole stroke; with a grey
shadow at a distance it turns into a drop shadow instead. Names and tags are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import preset

PEN = preset(
    "outline.pen",
    "Outline pen",
    "Ручка с обводкой",
    ["обводка", "outline", "контур", "contour", "двойной", "double", "ручка", "pen"],
    "ink",
    params={
        "size": 8.0,
        "sizeMin": 1.0,
        "sizeMax": 60.0,
        "spacing": 0.04,
        "hardness": 0.9,
        "head": {"source": "round"},
        "special": {
            "type": "double",
            "shadowColor": "#ffffff",
            "shadowSize": 3.0,
            "angle": 45.0,
            "distance": 0.0,
        },
    },
)

SHADOW_MARKER = preset(
    "outline.shadow_marker",
    "Shadow marker",
    "Маркер с тенью",
    ["тень", "shadow", "маркер", "marker", "двойной", "double", "падающая тень", "drop shadow"],
    "marker",
    params={
        "size": 24.0,
        "spacing": 0.03,
        "opacity": 0.9,
        "flow": 0.95,
        "hardness": 0.7,
        "head": {"aspect": -0.2, "angle": 45.0, "rotationMode": "fixed", "rotationFollow": 0.0},
        "special": {
            "type": "double",
            "shadowColor": "#808080",
            "shadowSize": 5.0,
            "angle": 45.0,
            "distance": 6.0,
        },
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

THICK = preset(
    "outline.thick",
    "Thick outline",
    "Толстая обводка",
    ["обводка", "outline", "контур", "contour", "толстая", "thick", "двойной", "double", "заливка"],
    "marker",
    params={
        "size": 20.0,
        "spacing": 0.025,
        "opacity": 0.95,
        "flow": 1.0,
        "hardness": 0.85,
        "head": {"aspect": 0.0, "angle": 0.0, "rotationMode": "fixed", "rotationFollow": 0.0},
        "special": {
            "type": "double",
            "shadowColor": "#ffffff",
            "shadowSize": 6.0,
            "angle": 45.0,
            "distance": 0.0,
        },
    },
    resources={"head": "asset:heads/soft_disk.png"},
)

CATEGORY = {
    "id": "outline",
    "name": {"en": "Outline and shadow", "ru": "Контур и тень"},
    "brushes": [PEN, SHADOW_MARKER, THICK],
}
