"""Particle brushes: every dab seeds a cloud of sprite particles (spec/tools/m2-brush.md §1).

A particle brush carries a `ParticlesParams` engine in `params.special` (`type: "particles"`): a dab seeds
`particles` sprites around it over `radius` (relative to the dab size) and lets them overshoot the dab by
`overshoot`; each sprite is drawn at `size` of the dab size. `attractors` is how many pulling points spread
the sprites, so a low value clumps them into a trail and a high one throws them into a spray. The sprite is
the `resources.particle` mask (a 64 px grayscale image) tinted with the brush colour, so the mask carries
only the silhouette: the spark and the droplet are spiky, the soft dot is a blur, the hard dot an edge.
Sparks and splatter throw the sprites well past the dab, dust keeps them close on a wide soft dab, and the
smoke trail is a low-opacity column of large soft sprites. Names and tags are ours (spec/assets.md).
"""
from __future__ import annotations

from .base import curve, preset

SPARKS = preset(
    "particles.sparks",
    "Sparks",
    "Искры",
    ["искры", "sparks", "частицы", "particles", "огонь", "fire", "искра", "spark"],
    "ink",
    params={
        "size": 24.0,
        "sizeMin": 4.0,
        "sizeMax": 120.0,
        "opacity": 0.95,
        "flow": 0.9,
        "spacing": 0.04,
        "hardness": 0.9,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.2),
        "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "jitter": {"size": 0.15, "scatter": 0.02},
        "special": {
            "type": "particles",
            "attractors": 10,
            "particles": 120,
            "radius": 1.2,
            "size": 0.06,
            "overshoot": 1.5,
        },
    },
    resources={"particle": "asset:particles/p_spark.png"},
)

DUST = preset(
    "particles.dust",
    "Dust",
    "Пыль",
    ["пыль", "dust", "частицы", "particles", "мягкая", "soft", "точка", "dot"],
    "ink",
    params={
        "size": 90.0,
        "sizeMin": 10.0,
        "sizeMax": 320.0,
        "opacity": 0.5,
        "flow": 0.35,
        "spacing": 0.05,
        "hardness": 0.15,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.55), (1.0, 1.0)], 0.25),
        "pressureToFlow": curve([(0.0, 0.2), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "jitter": {"scatter": 0.05, "size": 0.1},
        "special": {
            "type": "particles",
            "attractors": 6,
            "particles": 200,
            "radius": 2.5,
            "size": 0.09,
            "overshoot": 0.3,
        },
    },
    resources={"particle": "asset:particles/p_soft.png"},
)

SPLATTER = preset(
    "particles.splatter",
    "Splatter",
    "Брызги",
    ["брызги", "splatter", "частицы", "particles", "кляксы", "spatter", "капли", "drops"],
    "ink",
    params={
        "size": 40.0,
        "sizeMin": 6.0,
        "sizeMax": 200.0,
        "opacity": 0.9,
        "flow": 0.8,
        "spacing": 0.06,
        "hardness": 0.95,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.15),
        "pressureToFlow": curve([(0.0, 0.4), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "jitter": {"scatter": 0.03, "size": 0.2},
        "special": {
            "type": "particles",
            "attractors": 12,
            "particles": 60,
            "radius": 1.5,
            "size": 0.08,
            "overshoot": 2.0,
        },
    },
    resources={"particle": "asset:particles/p_hard.png"},
)

SMOKE_TRAIL = preset(
    "particles.smoke_trail",
    "Smoke trail",
    "Дымный след",
    ["дым", "smoke", "частицы", "particles", "след", "trail", "дымка", "haze"],
    "ink",
    params={
        "size": 70.0,
        "sizeMin": 8.0,
        "sizeMax": 300.0,
        "opacity": 0.35,
        "flow": 0.25,
        "spacing": 0.05,
        "hardness": 0.1,
        "pressureToSize": curve([(0.0, 0.0), (0.5, 0.6), (1.0, 1.0)], 0.3),
        "pressureToFlow": curve([(0.0, 0.15), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "jitter": {"scatter": 0.04, "size": 0.12},
        "special": {
            "type": "particles",
            "attractors": 4,
            "particles": 40,
            "radius": 1.8,
            "size": 0.16,
            "overshoot": 0.6,
        },
    },
    resources={"particle": "asset:particles/p_smoke.png"},
)

DROPLETS = preset(
    "particles.droplets",
    "Droplets",
    "Капли",
    ["капли", "droplets", "частицы", "particles", "вода", "water", "дождь", "rain"],
    "ink",
    params={
        "size": 45.0,
        "sizeMin": 5.0,
        "sizeMax": 160.0,
        "opacity": 0.85,
        "flow": 0.75,
        "spacing": 0.05,
        "hardness": 0.7,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.2),
        "pressureToFlow": curve([(0.0, 0.3), (1.0, 1.0)]),
        "taperStart": 0.0,
        "taperEnd": 0.0,
        "head": {"source": "round"},
        "jitter": {"scatter": 0.02, "size": 0.08},
        "special": {
            "type": "particles",
            "attractors": 8,
            "particles": 30,
            "radius": 1.0,
            "size": 0.12,
            "overshoot": 1.0,
        },
    },
    resources={"particle": "asset:particles/p_droplet.png"},
)

CATEGORY = {
    "id": "particles",
    "name": {"en": "Particles", "ru": "Частицы"},
    "brushes": [SPARKS, DUST, SPLATTER, SMOKE_TRAIL, DROPLETS],
}
