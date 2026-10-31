"""Colour stamps: one brush per RGBA stamp head, each dab painting the head image as a stamp.

A colour stamp does not take the paint colour from the palette: `params.special` carries a
`ColorStampParams` engine (`type: "color-stamp"`, `colorize: 1`) that shifts the hue of the head image
relative to `head.baseColor`, so the stamp keeps its own artwork. Every preset therefore sets
`head.color = true` plus a `head.baseColor`: the mean colour of the head PNG's opaque pixels, computed
from the PNG itself at generation time, so it follows any change of the artwork.

The presets stay a bitmap head at a size of about 60 px with a spacing of 1.0–1.5, so a stroke lays the
stamps out apart instead of blending them into a line, and the angle and size jitter scatter them. Names
and tags are ours (spec/assets.md).
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

from .base import curve, link, preset

#: The generated head PNGs, `app/src/main/assets/brushes/heads`; presets refer to them as `asset:heads/…`.
HEADS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "brushes" / "heads"

#: Lowest alpha count as painted: alpha over 0.5 of full coverage.
OPAQUE = 127


def base_colour(name: str) -> str:
    """Mean colour of the opaque pixels (alpha > 0.5) of `heads/stamp_<name>.png` as `#rrggbb`."""
    with Image.open(HEADS / f"stamp_{name}.png") as img:
        rgba = img.convert("RGBA").tobytes()
    total = [0, 0, 0]
    count = 0
    for i in range(0, len(rgba), 4):
        if rgba[i + 3] > OPAQUE:
            total[0] += rgba[i]
            total[1] += rgba[i + 1]
            total[2] += rgba[i + 2]
            count += 1
    if count == 0:
        raise ValueError(f"stamp head {name!r} has no opaque pixels")
    # Integer rounding (half up) keeps the hex string identical on every run and platform.
    return "#" + "".join(f"{(2 * c + count) // (2 * count):02x}" for c in total)


def stamp(index: int, name: str, name_en: str, name_ru: str, tags: list[str]) -> dict:
    """One colour-stamp preset: `heads/stamp_<name>.png` as the head, its own colours as the base.

    [index] only picks the spacing, so the presets walk the 1.0–1.5 range in 0.1 steps and no two
    neighbours of the list look alike.
    """
    return preset(
        f"stamps.{name}",
        name_en,
        name_ru,
        tags,
        "ink",
        params={
            "size": 60.0,
            "sizeMin": 8.0,
            "sizeMax": 320.0,
            "spacing": round(1.0 + 0.1 * (index % 6), 1),
            "hardness": 1.0,
            "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.65),
            "pressureToFlow": curve([(0.0, 0.6), (1.0, 1.0)]),
            "head": {
                "source": "bitmap",
                "color": True,
                "baseColor": base_colour(name),
                "rotationMode": "fixed",
                "angle": 0.0,
            },
            "jitter": {"size": 0.15, "angle": 0.9, "scatter": 0.04},
            "special": {"type": "color-stamp", "colorize": 1.0},
        },
        resources={"head": f"asset:heads/stamp_{name}.png"},
    )


#: Head name, en name, ru name, tags: one brush per generated `stamp_*` head, in listing order.
STAMPS: list[tuple[str, str, str, list[str]]] = [
    ("star", "Star stamp", "Штамп-звезда", ["звезда", "star", "штамп", "stamp", "фигура", "shape", "награда", "award"]),
    ("heart", "Heart stamp", "Штамп-сердце", ["сердце", "heart", "штамп", "stamp", "любовь", "love", "валентинка"]),
    ("flower", "Flower stamp", "Штамп-цветок", ["цветок", "flower", "штамп", "stamp", "весна", "spring", "лепесток"]),
    ("leaf", "Leaf stamp", "Штамп-листок", ["лист", "leaf", "штамп", "stamp", "природа", "nature", "осень", "autumn"]),
    ("snowflake", "Snowflake stamp", "Штамп-снежинка", ["снежинка", "snowflake", "штамп", "stamp", "зима", "winter"]),
    ("note", "Note stamp", "Штамп-нота", ["нота", "note", "штамп", "stamp", "музыка", "music", "мелодия", "melody"]),
    ("cloud", "Cloud stamp", "Штамп-облако", ["облако", "cloud", "штамп", "stamp", "небо", "sky", "погода", "weather"]),
    ("droplet", "Droplet stamp", "Штамп-капля", ["капля", "droplet", "штамп", "stamp", "вода", "water", "дождь"]),
    ("bubble", "Bubble stamp", "Штамп-пузырь", ["пузырь", "bubble", "штамп", "stamp", "прозрачный", "transparent"]),
    ("butterfly", "Butterfly stamp", "Штамп-бабочка", ["бабочка", "butterfly", "штамп", "stamp", "лето", "summer"]),
    ("grass", "Grass stamp", "Штамп-трава", ["трава", "grass", "штамп", "stamp", "природа", "nature", "луг", "meadow"]),
    ("rose", "Rose stamp", "Штамп-роза", ["роза", "rose", "штамп", "stamp", "цветок", "flower", "букет"]),
    ("berry", "Berry stamp", "Штамп-ягода", ["ягода", "berry", "штамп", "stamp", "фрукт", "fruit", "сладкое"]),
    ("diamond", "Diamond stamp", "Штамп-ромб", ["ромб", "diamond", "штамп", "stamp", "фигура", "shape", "грань"]),
]

AUTUMN_LEAF = preset(
    "stamps.leaf_autumn",
    "Autumn leaf",
    "Осенний лист",
    ["лист", "leaf", "осень", "autumn", "штамп", "stamp", "клён", "maple"],
    "ink",
    params={
        "size": 64.0,
        "sizeMin": 8.0,
        "sizeMax": 320.0,
        "spacing": 1.3,
        "hardness": 1.0,
        "pressureToSize": curve([(0.0, 0.0), (1.0, 1.0)], 0.6),
        "pressureToFlow": curve([(0.0, 0.6), (1.0, 1.0)]),
        "head": {
            "source": "bitmap",
            "color": True,
            "baseColor": base_colour("leaf"),
            "rotationMode": "fixed",
            "angle": 0.0,
        },
        "jitter": {"size": 0.2, "angle": 1.0, "scatter": 0.06, "hue": 0.08},
        # A light touch keeps the leaf's own colour; a hard press lets the hue drift, like a pile of dry leaves.
        "dynamics": [link("PRESSURE", "COLOR_JITTER", [(0.0, 0.2), (1.0, 1.0)])],
        "special": {"type": "color-stamp", "colorize": 1.0},
    },
    resources={"head": "asset:heads/stamp_leaf.png"},
)

CATEGORY = {
    "id": "stamps",
    "name": {"en": "Stamps", "ru": "Штампы"},
    "brushes": [stamp(i, *entry) for i, entry in enumerate(STAMPS)] + [AUTUMN_LEAF],
}
