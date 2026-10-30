"""Textures: brushes built around the grain modes of spec/tools/m2-brush.md §1.4.

Dry brush and hair run a stretched grain along the stroke (warp-along-stroke), so the fibres follow the curve
instead of the paper. Bark keeps a coarser wood grain along the path. The grunge brush shifts, scales and turns its
canvas-fixed texture once per stroke (texture jitter), so two strokes never repeat the same pattern. Names and tags
are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import curve, preset

DRY_BRUSH = preset(
    "textures.dry_brush",
    "Dry brush",
    "Сухая кисть",
    ["сухая", "dry", "кисть", "brush", "волокна", "fibres", "щетина", "bristle"],
    "ink",
    params={
        "size": 40.0,
        "sizeMin": 6.0,
        "sizeMax": 200.0,
        "opacity": 0.9,
        "flow": 0.8,
        "spacing": 0.03,
        "hardness": 0.7,
        "pressureToSize": curve([(0.0, 0.3), (1.0, 1.0)], 0.4),
        "pressureToFlow": curve([(0.0, 0.4), (1.0, 1.0)]),
        "head": {"source": "bitmap", "rotationMode": "tangent", "rotationFollow": 1.0},
        "texture": {"mode": "warp-along-stroke", "depth": 1.0, "lowerLimit": 0.1, "scale": 0.35, "stretch": 8.0,
                    "gradation": 0.4},
    },
    resources={"head": "asset:heads/bristle_dry.png", "texture": "asset:textures/fabric_fiber.png"},
)

HAIR = preset(
    "textures.hair",
    "Hair strands",
    "Пряди",
    ["волосы", "hair", "пряди", "strands", "мех", "fur", "линии", "lines"],
    "ink",
    params={
        "size": 30.0,
        "sizeMin": 4.0,
        "sizeMax": 160.0,
        "opacity": 0.85,
        "flow": 0.9,
        "spacing": 0.03,
        "hardness": 0.8,
        "pressureToSize": curve([(0.0, 0.2), (1.0, 1.0)], 0.4),
        "taperStart": 10.0,
        "taperEnd": 30.0,
        "taperSize": 0.8,
        # The warp grain follows the dab frame, so the head turns with the stroke.
        "head": {"source": "round", "rotationMode": "tangent", "rotationFollow": 1.0},
        "texture": {"mode": "warp-along-stroke", "depth": 1.0, "lowerLimit": 0.05, "scale": 0.25, "stretch": 12.0},
    },
    resources={"texture": "asset:textures/hatch_lines.png"},
)

BARK = preset(
    "textures.bark",
    "Bark",
    "Кора",
    ["кора", "bark", "дерево", "wood", "волокна", "grain"],
    "ink",
    params={
        "size": 60.0,
        "sizeMin": 8.0,
        "sizeMax": 300.0,
        "opacity": 0.95,
        "flow": 0.85,
        "spacing": 0.04,
        "hardness": 0.6,
        "pressureToSize": curve([(0.0, 0.4), (1.0, 1.0)], 0.4),
        "head": {"source": "round", "rotationMode": "tangent", "rotationFollow": 1.0},
        "texture": {"mode": "warp-along-stroke", "depth": 0.9, "lowerLimit": 0.15, "scale": 0.6, "stretch": 3.0},
    },
    resources={"texture": "asset:textures/wood.png"},
)

GRUNGE = preset(
    "textures.grunge",
    "Grunge",
    "Гранж",
    ["гранж", "grunge", "грязь", "dirt", "фактура", "texture", "шум", "noise"],
    "ink",
    params={
        "size": 90.0,
        "sizeMin": 10.0,
        "sizeMax": 400.0,
        "opacity": 0.8,
        "flow": 0.9,
        "spacing": 0.08,
        "hardness": 0.6,
        "pressureToSize": curve([(0.0, 0.5), (1.0, 1.0)], 0.4),
        "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
        "head": {"source": "round"},
        "texture": {"mode": "canvas-fixed", "depth": 1.0, "lowerLimit": 0.05, "scale": 1.2, "softness": 0.02,
                    "jitterPos": True, "jitterScale": True, "jitterAngle": True},
    },
    resources={"texture": "asset:textures/grunge.png"},
)

CATEGORY = {
    "id": "textures",
    "name": {"en": "Textures", "ru": "Фактуры"},
    "brushes": [DRY_BRUSH, HAIR, BARK, GRUNGE],
}
