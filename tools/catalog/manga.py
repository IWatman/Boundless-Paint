"""Manga and comic: the flat fill and the screentone dots (spec/tools/m2-brush.md §1.14).

A fill brush carries a `FillBrushParams` engine in `params.special` (`type: "fill"`, `content: "solid"`): the
trajectory of the stroke is closed and filled as one polygon, so the dab params only describe the stroke that
outlines it and the edge softness plays no part. A screentone brush carries a `ScreentoneParams` engine
(`type: "screentone"`): its dabs paint no pixels of their own, they raise the coverage `c` of the grid nodes they
cover and the stroke receives one dot per node with `diameter = spacing·sqrt(c)·1.13`. `spacing` is the grid step
in pixels, `angle` its rotation, `shape` the dot form (`DOT` here) and `scaleWithSize` makes the step follow the
brush size. So a hard head gives even dots, while a soft one (a low `hardness` over a large
`size`) leaves small dots at the stroke rim and full ones in the core: the gradient tone. Names and tags are ours
(spec/assets.md).
"""
from __future__ import annotations

from .base import curve, preset

FILL = preset(
    "manga.fill",
    "Fill brush",
    "Заливка",
    ["заливка", "fill", "закраска", "плоская заливка", "flat fill", "комикс", "comic", "манга", "manga"],
    "ink",
    params={
        "size": 24.0,
        "sizeMin": 2.0,
        "sizeMax": 300.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.05,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "fill", "content": "solid", "drawPreview": True},
    },
)

FILL_SOFT = preset(
    "manga.fill_soft",
    "Soft fill",
    "Мягкая заливка",
    ["заливка", "fill", "полупрозрачная", "soft", "плоская заливка", "flat", "тон", "shading", "манга", "manga"],
    "ink",
    params={
        "size": 24.0,
        "sizeMin": 2.0,
        "sizeMax": 300.0,
        "opacity": 0.5,
        "flow": 1.0,
        "spacing": 0.05,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "fill", "content": "solid"},
    },
)

TONE_FINE = preset(
    "manga.tone_fine",
    "Fine dot tone",
    "Мелкий тон",
    ["тон", "screentone", "скринтон", "точки", "dots", "сетка", "dot", "мелкий", "fine", "манга", "manga"],
    "ink",
    params={
        "size": 16.0,
        "sizeMin": 4.0,
        "sizeMax": 60.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.05,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "pressureToFlow": curve([(0.0, 1.0), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "screentone", "spacing": 6.0, "angle": 45.0, "shape": "DOT", "scaleWithSize": False},
    },
)

TONE_MEDIUM = preset(
    "manga.tone_medium",
    "Medium dot tone",
    "Средний тон",
    ["тон", "screentone", "скринтон", "точки", "dots", "сетка", "dot", "средний", "medium", "манга", "manga"],
    "ink",
    params={
        "size": 20.0,
        "sizeMin": 6.0,
        "sizeMax": 80.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.05,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "pressureToFlow": curve([(0.0, 1.0), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "screentone", "spacing": 10.0, "angle": 45.0, "shape": "DOT", "scaleWithSize": False},
    },
)

TONE_COARSE = preset(
    "manga.tone_coarse",
    "Coarse dot tone",
    "Крупный тон",
    ["тон", "screentone", "скринтон", "точки", "dots", "сетка", "dot", "крупный", "coarse", "манга", "manga"],
    "ink",
    params={
        "size": 28.0,
        "sizeMin": 8.0,
        "sizeMax": 120.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.05,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "pressureToFlow": curve([(0.0, 1.0), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "screentone", "spacing": 16.0, "angle": 45.0, "shape": "DOT", "scaleWithSize": False},
    },
)

TONE_GRADIENT = preset(
    "manga.tone_gradient",
    "Gradient tone",
    "Градиентный тон",
    [
        "тон",
        "screentone",
        "скринтон",
        "градиент",
        "gradient",
        "точки",
        "dots",
        "плавный",
        "smooth",
        "манга",
        "manga",
    ],
    "ink",
    params={
        "size": 90.0,
        "sizeMin": 20.0,
        "sizeMax": 300.0,
        "opacity": 1.0,
        "flow": 1.0,
        "spacing": 0.02,
        "hardness": 0.1,
        "pressureToSize": curve([(0.0, 1.0), (1.0, 1.0)]),
        "pressureToFlow": curve([(0.0, 1.0), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "special": {"type": "screentone", "spacing": 8.0, "angle": 45.0, "shape": "DOT", "scaleWithSize": False},
    },
)

CATEGORY = {
    "id": "manga",
    "name": {"en": "Manga and comic", "ru": "Манга и комикс"},
    "brushes": [FILL, FILL_SOFT, TONE_FINE, TONE_MEDIUM, TONE_COARSE, TONE_GRADIENT],
}
