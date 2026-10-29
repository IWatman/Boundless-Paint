"""Base templates for the stock brush catalog (spec/tools/m2-brush.md §5).

A category module picks one of [BASE] and overrides only the fields that differ from it, so a preset
reads as a short list of "what makes it this brush". The templates use the S1 feature set plus the S2a
paper texture (`params.texture` with a `resources.texture` image): head source plus an `asset:` head,
hardness, spacing, flow, opacity, size range, the pressure curves, the taper, jitter (scatter/angle/size),
the rotation mode, the texture and dynamics links. Paint mixing and the special engines stay off until
their milestones land.

Everything is plain Python data (dicts and lists) that is dumped to JSON by `tools/gen_assets.py`;
see tools/catalog/__init__.py for the writer and the index.
"""
from __future__ import annotations

import copy


def curve(points: list[tuple[float, float]], floor: float = 0.0) -> dict:
    """Dynamics curve: control points `[[x, y], ...]` (x strictly increasing) and a floor [floor]."""
    return {"points": [[float(x), float(y)] for x, y in points], "min": float(floor)}


def link(source: str, target: str, points: list[tuple[float, float]], floor: float = 0.0) -> dict:
    """One dynamics link `target ← curve(source)`; names are `DynSource`/`DynTarget` constants."""
    return {"source": source, "target": target, "curve": curve(points, floor)}


BASE: dict[str, dict] = {
    # Graphite: a grainy disk, a narrow size range, light pressure sensitivity and short tapers.
    "pencil": {
        "params": {
            "size": 6.0,
            "sizeMin": 1.0,
            "sizeMax": 60.0,
            "opacity": 0.9,
            "flow": 0.55,
            "spacing": 0.07,
            "hardness": 0.85,
            "pressureToSize": curve([(0.0, 0.0), (0.5, 0.5), (1.0, 1.0)], 0.12),
            "pressureToFlow": curve([(0.0, 0.2), (1.0, 1.0)]),
            "taperStart": 2.0,
            "taperEnd": 8.0,
            "taperShape": "smooth",
            "taperSize": 1.0,
            "taperOpacity": 0.3,
            "head": {"source": "bitmap", "rotationMode": "fixed", "angle": 0.0, "depth": 0.15},
            "jitter": {"size": 0.03, "angle": 0.4, "scatter": 0.01},
        },
        "resources": {"head": "asset:heads/grain_disk_02.png"},
    },
    # Chalk: a ragged dry dab on a heavy canvas-fixed grain, so the flow is low and pressure drives the grain.
    "chalk": {
        "params": {
            "size": 18.0,
            "sizeMin": 2.0,
            "sizeMax": 180.0,
            "opacity": 0.92,
            "flow": 0.55,
            "spacing": 0.06,
            "hardness": 0.4,
            "pressureToSize": curve([(0.0, 0.0), (0.5, 0.55), (1.0, 1.0)], 0.2),
            "pressureToFlow": curve([(0.0, 0.25), (1.0, 1.0)]),
            "taperStart": 3.0,
            "taperEnd": 12.0,
            "taperShape": "smooth",
            "taperSize": 0.8,
            "taperOpacity": 0.35,
            "head": {"source": "bitmap", "rotationMode": "tangent", "rotationFollow": 0.6, "depth": 0.2},
            "jitter": {"size": 0.06, "angle": 0.6, "scatter": 0.03},
            "texture": {"mode": "canvas-fixed", "depth": 0.75, "lowerLimit": 0.3, "scale": 0.8},
            "dynamics": [link("PRESSURE", "TEX_DEPTH", [(0.0, 0.55), (1.0, 1.0)])],
        },
        "resources": {"head": "asset:heads/chalk_01.png", "texture": "asset:textures/grain_chalk.png"},
    },
    # Charcoal: the same dry dab, softer and deeper: a lower hardness, more scatter and a heavier grain.
    "charcoal": {
        "params": {
            "size": 22.0,
            "sizeMin": 3.0,
            "sizeMax": 220.0,
            "opacity": 0.95,
            "flow": 0.5,
            "spacing": 0.05,
            "hardness": 0.3,
            "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.22),
            "pressureToFlow": curve([(0.0, 0.2), (1.0, 1.0)]),
            "taperStart": 4.0,
            "taperEnd": 16.0,
            "taperShape": "smooth",
            "taperSize": 0.85,
            "taperOpacity": 0.4,
            "head": {"source": "bitmap", "rotationMode": "tangent", "rotationFollow": 0.7, "depth": 0.25},
            "jitter": {"size": 0.08, "angle": 0.9, "scatter": 0.05},
            "texture": {"mode": "canvas-fixed", "depth": 0.8, "lowerLimit": 0.3, "scale": 0.9},
            "dynamics": [link("PRESSURE", "TEX_DEPTH", [(0.0, 0.5), (1.0, 1.0)])],
        },
        "resources": {"head": "asset:heads/charcoal_disk_01.png", "texture": "asset:textures/grain_charcoal.png"},
    },
    # Ink: saturated and even, so the flow curve is shallow and the dabs sit close together.
    "ink": {
        "params": {
            "size": 4.0,
            "sizeMin": 1.0,
            "sizeMax": 40.0,
            "opacity": 1.0,
            "flow": 1.0,
            "spacing": 0.05,
            "hardness": 0.95,
            "pressureToSize": curve([(0.0, 0.0), (0.35, 0.55), (1.0, 1.0)], 0.25),
            "pressureToFlow": curve([(0.0, 0.35), (1.0, 1.0)]),
            "head": {"source": "round"},
        },
        "resources": {},
    },
    # Marker: a wide chisel dab that turns with the stroke, translucent and almost pressure-free.
    "marker": {
        "params": {
            "size": 18.0,
            "sizeMin": 2.0,
            "sizeMax": 120.0,
            "opacity": 0.75,
            "flow": 0.85,
            "spacing": 0.03,
            "hardness": 0.55,
            "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.75),
            "taperStart": 0.0,
            "taperEnd": 0.0,
            "head": {
                "source": "bitmap",
                "aspect": -0.4,
                "angle": 45.0,
                "rotationMode": "tangent",
                "rotationFollow": 0.8,
            },
            "jitter": {"size": 0.02},
            "dynamics": [link("PRESSURE", "FLOW", [(0.0, 0.6), (1.0, 1.0)])],
        },
        "resources": {"head": "asset:heads/rounded_square.png"},
    },
    # Airbrush: a very soft disk, low flow and a spray that widens with speed.
    "airbrush": {
        "params": {
            "size": 60.0,
            "sizeMin": 4.0,
            "sizeMax": 400.0,
            "opacity": 0.55,
            "flow": 0.12,
            "spacing": 0.04,
            "hardness": 0.12,
            "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.1),
            "pressureToFlow": curve([(0.0, 0.0), (1.0, 1.0)], 0.05),
            "head": {"source": "bitmap"},
            "jitter": {"scatter": 0.08, "size": 0.1},
            "dynamics": [link("VELOCITY", "FLOW", [(0.0, 0.0), (1.0, 1.0)], 0.4)],
        },
        "resources": {"head": "asset:heads/soft_disk.png"},
    },
    # Eraser: full flow, no colour jitter; sub-presets swap in a grainier head or a softer edge.
    "eraser": {
        "params": {
            "size": 30.0,
            "sizeMin": 2.0,
            "sizeMax": 300.0,
            "opacity": 1.0,
            "flow": 1.0,
            "spacing": 0.06,
            "hardness": 0.8,
            "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.3),
            "taperStart": 0.0,
            "taperEnd": 0.0,
            "head": {"source": "round"},
        },
        "resources": {},
    },
}


def _merge(target: dict, over: dict) -> dict:
    """Deep-merges [over] into [target] (dicts merge, every other value replaces) and returns it."""
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            target[key] = _merge(target[key], dict(value))
        else:
            target[key] = copy.deepcopy(value)
    return target


def preset(id: str, name_en: str, name_ru: str, tags: list[str], base: str, **overrides) -> dict:
    """One catalog brush: the [base] template deep-merged with [overrides].

    [id] is `<category>.<name>`; the category is also the output folder and must match the id prefix.
    [overrides] are extra top-level keys of the brush (`params`, `resources`, …), merged deeply, so
    `params={"head": {"rotationMode": "tangent"}}` keeps the rest of the template head.
    """
    if base not in BASE:
        raise KeyError(f"{id}: unknown base template {base!r}")
    category, _, slug = id.partition(".")
    if not category or not slug:
        raise ValueError(f"{id}: id must be <category>.<name>")
    if not name_en or not name_ru:
        raise ValueError(f"{id}: both en and ru names are required")
    data = _merge(copy.deepcopy(BASE[base]), overrides)
    return {
        "schema": 1,
        "id": id,
        "category": category,
        "name": {"en": name_en, "ru": name_ru},
        "tags": list(tags),
        "params": data.get("params", {}),
        "resources": data.get("resources", {}),
    }
