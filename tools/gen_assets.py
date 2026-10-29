"""Procedural brush assets (heads, textures, particles) for the stock catalog.

Usage: .venv\\Scripts\\python tools\\gen_assets.py [--only heads|textures|particles|presets] [--check] [--out DIR]

Everything is generated from code (clean-room: no third-party images). Output is deterministic: every
asset is seeded with crc32(name), so --check can regenerate into a temp dir and compare bytes.
Heads are white-on-black coverage (gray) or RGBA colour stamps; textures tile seamlessly. The
`presets` group dumps the brush JSON of tools/catalog (never edited by hand).
Spec: spec/tools/m2-brush.md §5.
"""
from __future__ import annotations

import argparse
import filecmp
import json
import math
import shutil
import sys
import tempfile
import zlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "brushes"
HEAD = 256
BIG_HEAD = 512
TEX = 512
SS = 4  # supersampling for vector shapes

# --------------------------------------------------------------------------------------------- helpers


def rng(name: str) -> np.random.Generator:
    return np.random.default_rng(zlib.crc32(name.encode()))


def norm(a: np.ndarray) -> np.ndarray:
    lo, hi = float(a.min()), float(a.max())
    return np.zeros_like(a) if hi - lo < 1e-12 else (a - lo) / (hi - lo)


def fbm(n: int, beta: float, r: np.random.Generator, aniso: tuple[float, float] = (1.0, 1.0)) -> np.ndarray:
    """Seamless 1/f^beta noise in 0..1 (spectral synthesis, periodic by construction)."""
    white = r.standard_normal((n, n))
    fy = np.fft.fftfreq(n)[:, None] * aniso[1]
    fx = np.fft.fftfreq(n)[None, :] * aniso[0]
    f = np.sqrt(fx * fx + fy * fy)
    f[0, 0] = 1.0
    spec = np.fft.fft2(white) / f ** beta
    spec[0, 0] = 0
    return norm(np.real(np.fft.ifft2(spec)))


def worley(n: int, count: int, r: np.random.Generator, mode: str = "f1") -> np.ndarray:
    """Seamless Worley noise (toroidal distances), normalised 0..1."""
    pts = r.random((count, 2)) * n
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    f1 = np.full((n, n), np.inf, np.float32)
    f2 = np.full((n, n), np.inf, np.float32)
    for px, py in pts:
        dx = np.abs(xx - px); dx = np.minimum(dx, n - dx)
        dy = np.abs(yy - py); dy = np.minimum(dy, n - dy)
        d = np.sqrt(dx * dx + dy * dy)
        f2 = np.where(d < f1, f1, np.minimum(f2, d))
        f1 = np.minimum(f1, d)
    return norm(f2 - f1 if mode == "f2-f1" else f1)


def polar(n: int) -> tuple[np.ndarray, np.ndarray]:
    """Radius (1 at the inscribed circle) and angle for an n×n grid centred on the image."""
    c = (n - 1) / 2
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float64)
    dx, dy = (xx - c) / (n / 2), (yy - c) / (n / 2)
    return np.sqrt(dx * dx + dy * dy), np.arctan2(dy, dx)


def smooth_edge(r: np.ndarray, radius: float, soft: float) -> np.ndarray:
    t = np.clip((radius - r) / max(soft, 1e-6), 0, 1)
    return t * t * (3 - 2 * t)


def canvas(n: int = HEAD, mode: str = "L") -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new(mode, (n * SS, n * SS), 0)
    return img, ImageDraw.Draw(img)


def down(img: Image.Image, n: int = HEAD) -> Image.Image:
    return img.resize((n, n), Image.LANCZOS)


def gray(a: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(a * 255 + 0.5, 0, 255).astype(np.uint8), "L")


def star_points(cx, cy, r_out, r_in, k, rot=-math.pi / 2):
    pts = []
    for i in range(2 * k):
        rr = r_out if i % 2 == 0 else r_in
        a = rot + i * math.pi / k
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


# --------------------------------------------------------------------------------------------- heads

def h_basic() -> dict[str, Image.Image]:
    n = HEAD
    r, th = polar(n)
    out = {
        "soft_disk": gray(np.exp(-(r / 0.42) ** 2 * 2.2) * (r < 1)),
        "ring_bokeh": gray(smooth_edge(r, 0.92, 0.04) * (0.55 + 0.45 * np.clip((r - 0.6) / 0.32, 0, 1) ** 2)),
    }
    img, d = canvas()
    m = int(n * SS * 0.08)
    d.rectangle([m, m, n * SS - m, n * SS - m], fill=255)
    out["square"] = down(img)
    img, d = canvas()
    d.rounded_rectangle([m, m, n * SS - m, n * SS - m], radius=n * SS // 5, fill=255)
    out["rounded_square"] = down(img)
    img, d = canvas()
    d.ellipse([n * SS * 0.36, m, n * SS * 0.64, n * SS - m], fill=255)
    out["chisel"] = down(img)
    img, d = canvas()
    s = n * SS
    d.polygon([(s / 2, s * 0.08), (s * 0.94, s * 0.86), (s * 0.06, s * 0.86)], fill=255)
    out["triangle"] = down(img)
    return out


def h_grain() -> dict[str, Image.Image]:
    out = {}
    r, th = polar(HEAD)
    for i, (beta, thr) in enumerate([(0.5, 0.35), (0.6, 0.45), (0.45, 0.55), (0.7, 0.3)], 1):
        name = f"grain_disk_{i:02d}"
        g = fbm(HEAD, beta, rng(name))
        cov = np.clip((g - thr) / (1 - thr), 0, 1) ** 0.7
        out[name] = gray(cov * smooth_edge(r, 0.95, 0.15))
    for i in (1, 2):
        name = f"charcoal_disk_{i:02d}"
        g = fbm(HEAD, 0.55, rng(name), aniso=(1.0, 4.0))
        cov = np.clip((g - 0.4) / 0.6, 0, 1) ** (0.6 + 0.2 * i)
        out[name] = gray(cov * smooth_edge(r, 0.95, 0.2))
    return out


def ragged(name: str, n: int, roughness: float, grain_thr: float, beta: float = 0.9) -> np.ndarray:
    r, th = polar(n)
    rg = rng(name)
    edge = 0.82 + roughness * (fbm(n, 1.5, rg)[n // 2, :][((th + math.pi) / (2 * math.pi) * (n - 1)).astype(int)] - 0.5)
    g = fbm(n, beta, rg)
    return smooth_edge(r, edge, 0.05) * np.clip((g - grain_thr) / (1 - grain_thr), 0, 1) ** 0.5


def h_chalk() -> dict[str, Image.Image]:
    out = {f"chalk_{i:02d}": gray(ragged(f"chalk_{i:02d}", HEAD, 0.25 + 0.1 * i, 0.25 + 0.08 * i)) for i in (1, 2, 3)}
    sq = ragged("pastel_square", HEAD, 0.15, 0.2)
    yy, xx = np.mgrid[0:HEAD, 0:HEAD]
    box = ((np.abs(xx - HEAD / 2) < HEAD * 0.38) & (np.abs(yy - HEAD / 2) < HEAD * 0.38)).astype(float)
    out["pastel_square"] = gray(np.maximum(sq, 0) * 0 + box * fbm(HEAD, 0.8, rng("pastel_square_g")) ** 0.6)
    out["crayon"] = gray(ragged("crayon", HEAD, 0.2, 0.42, beta=0.4))
    return out


def bristles(name: str, n: int, count: int, shape: str, gaps: float = 0.0) -> Image.Image:
    r = rng(name)
    img, d = canvas(n)
    s = n * SS
    for i in range(count):
        t = (i + r.random()) / count
        if shape == "fan":
            a = math.radians(-60 + 120 * t)
            x0, y0 = s / 2, s * 0.95
            ln = s * (0.6 + 0.3 * r.random())
            x1, y1 = x0 + ln * math.sin(a), y0 - ln * math.cos(a)
        elif shape == "round":
            ang = r.random() * 2 * math.pi
            rad = math.sqrt(r.random()) * s * 0.42
            x0 = x1 = s / 2 + rad * math.cos(ang)
            y0 = y1 = s / 2 + rad * math.sin(ang)
        else:  # flat / rake / dry: vertical strands across the width
            spread = 0.84 if shape != "rake" else 0.9
            x0 = x1 = s * (0.5 - spread / 2 + spread * t)
            if shape == "rake" and i % 3:
                continue
            y0 = s * (0.1 + 0.1 * r.random())
            y1 = s * (0.9 - 0.1 * r.random())
        if gaps and r.random() < gaps:
            continue
        w = max(SS, int(s * ((0.03 + 0.05 * r.random()) if shape == "round" else (0.006 + 0.02 * r.random()))))
        alpha = int(140 + 115 * r.random())
        d.line([(x0, y0), (x1, y1)], fill=alpha, width=w)
        d.ellipse([x0 - w / 2, y0 - w / 2, x0 + w / 2, y0 + w / 2], fill=alpha)
        d.ellipse([x1 - w / 2, y1 - w / 2, x1 + w / 2, y1 + w / 2], fill=alpha)
    return down(img.filter(ImageFilter.GaussianBlur(SS * 0.7)), n)


def h_bristle() -> dict[str, Image.Image]:
    return {
        "bristle_flat": bristles("bristle_flat", BIG_HEAD, 70, "flat"),
        "bristle_flat_wide": bristles("bristle_flat_wide", BIG_HEAD, 80, "flat"),
        "bristle_round": bristles("bristle_round", BIG_HEAD, 150, "round"),
        "bristle_round_soft": bristles("bristle_round_soft", BIG_HEAD, 80, "round"),
        "bristle_fan": bristles("bristle_fan", BIG_HEAD, 40, "fan"),
        "bristle_rake": bristles("bristle_rake", BIG_HEAD, 36, "rake"),
        "bristle_dry": bristles("bristle_dry", BIG_HEAD, 70, "flat", gaps=0.45),
        "bristle_fine": bristles("bristle_fine", BIG_HEAD, 12, "flat"),
    }


def h_knife() -> dict[str, Image.Image]:
    n = HEAD
    yy, xx = np.mgrid[0:n, 0:n] / (n - 1)
    ridges = 0.75 + 0.25 * np.sin(yy * math.pi * 14)
    trap = (np.abs(xx - 0.5) < (0.2 + 0.25 * yy)) & (yy > 0.12) & (yy < 0.88)
    out = {"knife": gray(trap * ridges)}
    loaded = trap * np.clip(ridges * (0.6 + 0.6 * fbm(n, 1.2, rng("knife_loaded"))), 0, 1)
    out["knife_loaded"] = gray(loaded)
    stripes = 0.6 + 0.4 * (np.sin(xx * math.pi * 22) > 0)
    roll = (np.abs(xx - 0.5) < 0.45) & (np.abs(yy - 0.5) < 0.12)
    out["roller"] = gray(roll * stripes * (0.8 + 0.2 * fbm(n, 0.8, rng("roller"))))
    return out


def h_sponge() -> dict[str, Image.Image]:
    r, _ = polar(HEAD)
    out = {}
    for name, cnt, thr in [("sponge_coarse", 40, 0.45), ("sponge_kitchen", 120, 0.4), ("sponge_natural", 70, 0.5)]:
        w = worley(HEAD, cnt, rng(name))
        out[name] = gray(np.clip((w - thr) / (1 - thr), 0, 1) ** 0.5 * smooth_edge(r, 0.9, 0.2))
    return out


def splatter(name: str, n: int, drops: int, drips: int) -> Image.Image:
    r = rng(name)
    img, d = canvas(n)
    s = n * SS
    for _ in range(drops):
        rad = s * 0.012 * (1 - r.random()) ** (-1 / 1.2)
        rad = min(rad, s * 0.14)
        dist = min(abs(r.normal(0, 0.15)) * s, s * 0.47 - rad)
        a = r.random() * 2 * math.pi
        x, y = s / 2 + dist * math.cos(a), s / 2 + dist * math.sin(a)
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=255)
    for _ in range(drips):
        x = s * (0.35 + 0.3 * r.random())
        y0 = s * 0.5
        y1 = y0 + s * (0.2 + 0.25 * r.random())
        w = s * (0.01 + 0.02 * r.random())
        d.line([(x, y0), (x, y1)], fill=255, width=int(w))
        d.ellipse([x - w, y1 - w, x + w, y1 + w], fill=255)
    return down(img, n)


def h_splatter() -> dict[str, Image.Image]:
    out = {f"splatter_{i:02d}": splatter(f"splatter_{i:02d}", HEAD, 20 + 25 * i, (i % 3)) for i in (1, 2, 3, 4)}
    r = rng("spray")
    pts = r.normal(0.5, 0.17, (400, 2))
    img, d = canvas()
    s = HEAD * SS
    for x, y in pts:
        rad = s * 0.004 * (1 + r.random())
        d.ellipse([x * s - rad, y * s - rad, x * s + rad, y * s + rad], fill=int(120 + 135 * r.random()))
    out["spray"] = down(img)
    out["drips"] = splatter("drips", HEAD, 8, 6)
    return out


def h_design() -> dict[str, Image.Image]:
    s = HEAD * SS
    out = {}
    img, d = canvas(); d.rounded_rectangle([s * 0.1, s * 0.42, s * 0.9, s * 0.58], radius=s * 0.08, fill=255); out["dash"] = down(img)
    img, d = canvas(); d.ellipse([s * 0.3, s * 0.3, s * 0.7, s * 0.7], fill=255); out["dot"] = down(img)
    img, d = canvas()
    d.polygon([(s * 0.1, s * 0.42), (s * 0.6, s * 0.42), (s * 0.6, s * 0.25), (s * 0.92, s * 0.5),
               (s * 0.6, s * 0.75), (s * 0.6, s * 0.58), (s * 0.1, s * 0.58)], fill=255)
    out["arrow"] = down(img)
    img, d = canvas(); d.line([(s * 0.3, s * 0.2), (s * 0.7, s * 0.5), (s * 0.3, s * 0.8)], fill=255, width=int(s * 0.12), joint="curve"); out["chevron"] = down(img)
    img, d = canvas(); d.rounded_rectangle([s * 0.12, s * 0.3, s * 0.88, s * 0.7], radius=s * 0.2, outline=255, width=int(s * 0.08)); out["chain_link"] = down(img)
    img, d = canvas()
    for i in range(12):
        a = i * math.pi / 6
        cx, cy = s / 2 + s * 0.3 * math.cos(a), s / 2 + s * 0.3 * math.sin(a)
        d.ellipse([cx - s * 0.1, cy - s * 0.1, cx + s * 0.1, cy + s * 0.1], outline=255, width=int(s * 0.025))
    d.ellipse([s * 0.38, s * 0.38, s * 0.62, s * 0.62], outline=255, width=int(s * 0.03))
    out["lace"] = down(img)
    img, d = canvas()
    w = int(s * 0.07)
    d.line([(s * 0.3, s * 0.3), (s * 0.7, s * 0.7)], fill=255, width=w)
    d.line([(s * 0.7, s * 0.3), (s * 0.3, s * 0.7)], fill=255, width=w)
    out["stitch"] = down(img)
    yy, xx = np.mgrid[0:HEAD, 0:HEAD] / (HEAD - 1)
    band = np.abs(yy - 0.5) < 0.3
    out["ribbon"] = gray(band * (0.55 + 0.45 * np.cos((yy - 0.5) * math.pi * 1.6)))
    return out


STAMP_COLORS = {
    "star": (250, 200, 60), "diamond": (150, 220, 255), "heart": (235, 70, 100), "flower": (240, 130, 200),
    "leaf": (90, 170, 70), "snowflake": (200, 230, 255), "note": (60, 60, 70), "cloud": (235, 240, 250),
    "droplet": (70, 150, 230), "bubble": (170, 220, 250), "butterfly": (250, 150, 60), "grass": (80, 160, 60),
    "rose": (210, 40, 70), "berry": (120, 40, 120),
}


def shade(mask: Image.Image, color: tuple[int, int, int], name: str) -> Image.Image:
    """Colour stamp: base colour with a soft top-left highlight and bottom-right shadow, alpha = mask."""
    n = mask.size[0]
    yy, xx = np.mgrid[0:n, 0:n] / (n - 1)
    light = 1.15 - 0.45 * (xx * 0.5 + yy * 0.5)
    var = 0.92 + 0.08 * fbm(n, 1.0, rng("shade_" + name))
    rgb = np.clip(np.array(color, float)[None, None, :] * (light * var)[..., None], 0, 255)
    a = np.asarray(mask, float)
    return Image.fromarray(np.dstack([rgb, a]).astype(np.uint8), "RGBA")


def h_stamps() -> dict[str, Image.Image]:
    s = HEAD * SS
    masks: dict[str, Image.Image] = {}
    img, d = canvas(); d.polygon(star_points(s / 2, s / 2, s * 0.46, s * 0.19, 5), fill=255); masks["star"] = img
    img, d = canvas(); d.polygon([(s / 2, s * 0.05), (s * 0.8, s / 2), (s / 2, s * 0.95), (s * 0.2, s / 2)], fill=255); masks["diamond"] = img
    img, d = canvas()
    d.ellipse([s * 0.1, s * 0.15, s * 0.52, s * 0.57], fill=255); d.ellipse([s * 0.48, s * 0.15, s * 0.9, s * 0.57], fill=255)
    d.polygon([(s * 0.13, s * 0.45), (s * 0.87, s * 0.45), (s / 2, s * 0.9)], fill=255); masks["heart"] = img
    img, d = canvas()
    for i in range(6):
        a = i * math.pi / 3
        cx, cy = s / 2 + s * 0.24 * math.cos(a), s / 2 + s * 0.24 * math.sin(a)
        d.ellipse([cx - s * 0.19, cy - s * 0.19, cx + s * 0.19, cy + s * 0.19], fill=200)
    d.ellipse([s * 0.38, s * 0.38, s * 0.62, s * 0.62], fill=255); masks["flower"] = img
    img, d = canvas()
    lens = [(s / 2 + s * 0.44 * math.cos(t), s / 2 + s * 0.2 * math.sin(t) * (1 - 0.3 * math.cos(t)))
            for t in np.linspace(0, 2 * math.pi, 96, endpoint=False)]
    d.polygon(lens, fill=255)
    d.line([(s * 0.08, s / 2), (s * 0.9, s / 2)], fill=150, width=int(s * 0.015))
    masks["leaf"] = img.rotate(35, resample=Image.BICUBIC)
    img, d = canvas()
    for i in range(6):
        a = i * math.pi / 3
        x1, y1 = s / 2 + s * 0.45 * math.cos(a), s / 2 + s * 0.45 * math.sin(a)
        d.line([(s / 2, s / 2), (x1, y1)], fill=255, width=int(s * 0.05))
        for k in (0.5, 0.75):
            bx, by = s / 2 + s * 0.45 * k * math.cos(a), s / 2 + s * 0.45 * k * math.sin(a)
            for da in (-0.6, 0.6):
                d.line([(bx, by), (bx + s * 0.1 * math.cos(a + da), by + s * 0.1 * math.sin(a + da))], fill=255, width=int(s * 0.035))
    masks["snowflake"] = img
    img, d = canvas(); d.ellipse([s * 0.15, s * 0.62, s * 0.5, s * 0.9], fill=255); d.rectangle([s * 0.43, s * 0.12, s * 0.5, s * 0.76], fill=255)
    d.polygon([(s * 0.5, s * 0.12), (s * 0.82, s * 0.26), (s * 0.82, s * 0.38), (s * 0.5, s * 0.26)], fill=255); masks["note"] = img
    img, d = canvas()
    for cx, cy, rr in [(0.3, 0.6, 0.2), (0.5, 0.45, 0.26), (0.72, 0.58, 0.2), (0.5, 0.65, 0.2)]:
        d.ellipse([s * (cx - rr), s * (cy - rr), s * (cx + rr), s * (cy + rr)], fill=255)
    masks["cloud"] = img
    img, d = canvas(); d.ellipse([s * 0.25, s * 0.42, s * 0.75, s * 0.92], fill=255); d.polygon([(s * 0.27, s * 0.6), (s * 0.73, s * 0.6), (s / 2, s * 0.06)], fill=255); masks["droplet"] = img
    img, d = canvas(); d.ellipse([s * 0.1, s * 0.1, s * 0.9, s * 0.9], fill=110); d.ellipse([s * 0.13, s * 0.13, s * 0.87, s * 0.87], fill=60)
    d.ellipse([s * 0.28, s * 0.22, s * 0.42, s * 0.36], fill=255); masks["bubble"] = img
    img, d = canvas()
    for sx in (-1, 1):
        d.ellipse([s / 2 + sx * s * 0.02 - (s * 0.4 if sx < 0 else 0), s * 0.12, s / 2 + sx * s * 0.02 + (s * 0.4 if sx > 0 else 0), s * 0.55], fill=255)
        d.ellipse([s / 2 + sx * s * 0.02 - (s * 0.3 if sx < 0 else 0), s * 0.48, s / 2 + sx * s * 0.02 + (s * 0.3 if sx > 0 else 0), s * 0.85], fill=220)
    d.rectangle([s * 0.48, s * 0.2, s * 0.52, s * 0.8], fill=255); masks["butterfly"] = img
    img, d = canvas()
    gr = rng("grass")
    for i in range(9):
        x0 = s * (0.15 + 0.7 * i / 8)
        x1 = x0 + s * (gr.random() - 0.5) * 0.3
        d.polygon([(x0 - s * 0.03, s * 0.95), (x0 + s * 0.03, s * 0.95), (x1, s * (0.1 + 0.3 * gr.random()))], fill=255)
    masks["grass"] = img
    img, d = canvas()
    for k, (rr, val) in enumerate([(0.44, 170), (0.33, 210), (0.22, 240), (0.11, 255)]):
        pts = star_points(s / 2, s / 2, s * rr, s * rr * 0.75, 5 + k, rot=k * 0.6)
        d.polygon(pts, fill=val)
    masks["rose"] = img
    img, d = canvas()
    for cx, cy in [(0.38, 0.45), (0.62, 0.45), (0.5, 0.65), (0.5, 0.3)]:
        d.ellipse([s * (cx - 0.17), s * (cy - 0.17), s * (cx + 0.17), s * (cy + 0.17)], fill=255)
    masks["berry"] = img
    return {f"stamp_{k}": shade(down(v), STAMP_COLORS[k], k) for k, v in masks.items()}


def h_watercolor() -> dict[str, Image.Image]:
    r, th = polar(HEAD)
    g = fbm(HEAD, 1.0, rng("salt_blossom"))
    rays = 0.5 + 0.5 * np.cos(th * 23 + g * 6)
    salt = smooth_edge(r, 0.9, 0.3) * (0.4 + 0.6 * rays * (1 - r)) * (0.7 + 0.3 * g)
    stain_edge = 0.86 + 0.08 * (fbm(HEAD, 1.4, rng("circle_stain")) - 0.5)
    stain = smooth_edge(r, stain_edge, 0.03) * (0.35 + 0.65 * np.clip((r - stain_edge + 0.2) / 0.2, 0, 1) ** 2)
    img, d = canvas()
    s = HEAD * SS
    for i in range(5):
        a = i * 2 * math.pi / 5
        cx, cy = s / 2 + s * 0.22 * math.cos(a), s / 2 + s * 0.22 * math.sin(a)
        d.ellipse([cx - s * 0.16, cy - s * 0.24, cx + s * 0.16, cy + s * 0.24], fill=255)
    floral = np.asarray(down(img), float) / 255 * (0.75 + 0.25 * fbm(HEAD, 1.2, rng("floral_stencil")))
    return {"wc_salt_blossom": gray(salt), "wc_circle_stain": gray(stain), "wc_floral_stencil": gray(floral)}


# --------------------------------------------------------------------------------------------- particles

def particles() -> dict[str, Image.Image]:
    n = 64
    r, th = polar(n)
    out = {
        "p_soft": gray(np.exp(-(r / 0.5) ** 2 * 3) * (r < 1)),
        "p_hard": gray(smooth_edge(r, 0.8, 0.08)),
        "p_spark": gray(np.clip(np.exp(-r * 4) + 0.9 * np.exp(-(np.abs(np.sin(2 * th)) * r) * 18) * (1 - r).clip(0), 0, 1)),
    }
    dy = np.mgrid[0:n, 0:n][0] / (n - 1)
    out["p_droplet"] = gray(smooth_edge(r * (1 + 0.6 * (0.5 - dy).clip(0)), 0.7, 0.08))
    out["p_smoke"] = gray(smooth_edge(r, 0.95, 0.6) * fbm(n, 1.4, rng("p_smoke")))
    return out


# --------------------------------------------------------------------------------------------- textures

def stripes(n, freq, angle_deg, duty=0.5):
    yy, xx = np.mgrid[0:n, 0:n] / n
    a = math.radians(angle_deg)
    # integer frequencies along both axes keep the pattern periodic
    kx, ky = round(freq * math.cos(a)), round(freq * math.sin(a))
    ph = (xx * kx + yy * ky) % 1.0
    return (ph < duty).astype(float)


def textures() -> dict[str, Image.Image]:
    n = TEX
    yy, xx = np.mgrid[0:n, 0:n] / n
    t: dict[str, np.ndarray] = {}
    # paper
    t["paper_smooth"] = 0.85 + 0.15 * fbm(n, 1.2, rng("paper_smooth"))
    t["paper_cold_press"] = norm(fbm(n, 1.0, rng("cp_a")) * 0.6 + worley(n, 300, rng("cp_b")) * 0.4)
    t["paper_rough"] = norm(fbm(n, 0.8, rng("rough_a")) + 0.5 * worley(n, 150, rng("rough_b")))
    t["paper_watercolor"] = norm(fbm(n, 1.3, rng("wcp_a")) * 0.5 + (1 - worley(n, 80, rng("wcp_b"))) * 0.5)
    t["paper_kraft"] = norm(fbm(n, 1.0, rng("kraft"), aniso=(1, 6)) * 0.7 + fbm(n, 0.5, rng("kraft_b")) * 0.3)
    t["paper_rice"] = norm(0.7 + fbm(n, 1.1, rng("rice"), aniso=(12, 1)) * 0.5 + fbm(n, 1.1, rng("rice_b"), aniso=(1, 12)) * 0.3)
    # grain
    t["grain_graphite"] = fbm(n, 0.3, rng("graphite"), aniso=(1, 2))
    t["grain_charcoal"] = fbm(n, 0.6, rng("charcoal"), aniso=(1, 4))
    t["grain_chalk"] = np.clip((fbm(n, 0.7, rng("chalk")) - 0.25) / 0.75, 0, 1)
    t["grain_crayon"] = np.clip((fbm(n, 0.4, rng("crayon"), aniso=(1, 3)) - 0.3) / 0.7, 0, 1) ** 0.6
    t["grain_sand"] = fbm(n, 0.1, rng("sand"))
    t["grain_stucco"] = norm(fbm(n, 1.8, rng("stucco")) + 0.3 * fbm(n, 0.4, rng("stucco_b")))
    # fabric
    weave = (np.sin(xx * 2 * math.pi * 64) * np.sin(yy * 2 * math.pi * 64))
    t["fabric_canvas"] = norm(np.abs(weave) * 0.7 + fbm(n, 0.8, rng("canvas")) * 0.3)
    t["fabric_linen"] = norm(np.abs(np.sin(xx * 2 * math.pi * 96)) * 0.4 + np.abs(np.sin(yy * 2 * math.pi * 80)) * 0.3 + fbm(n, 1.0, rng("linen"), aniso=(4, 1)) * 0.3)
    t["fabric_denim"] = norm(stripes(n, 90, 45, 0.55) * 0.6 + fbm(n, 0.6, rng("denim")) * 0.4)
    t["fabric_corduroy"] = norm((0.5 + 0.5 * np.sin(xx * 2 * math.pi * 40)) ** 2 * 0.8 + fbm(n, 0.8, rng("cord"), aniso=(1, 8)) * 0.2)
    t["fabric_fiber"] = fbm(n, 1.2, rng("fiber"), aniso=(10, 1))
    # hatching & graphics
    t["hatch_lines"] = stripes(n, 48, 45, 0.35) * (0.7 + 0.3 * fbm(n, 0.8, rng("hatch")))
    t["hatch_cross"] = np.maximum(stripes(n, 48, 45, 0.3), stripes(n, 48, -45, 0.3)) * (0.7 + 0.3 * fbm(n, 0.8, rng("xhatch")))
    sc = Image.new("L", (n, n), 0)
    dr = ImageDraw.Draw(sc)
    r = rng("scratch")
    for _ in range(260):
        x, y = r.random() * n, r.random() * n
        a = r.normal(0.3, 0.25)
        ln = 20 + 80 * r.random()
        for ox in (-n, 0, n):
            for oy in (-n, 0, n):
                dr.line([(x + ox, y + oy), (x + ox + ln * math.cos(a), y + oy + ln * math.sin(a))], fill=int(120 + 135 * r.random()), width=1)
    t["hatch_scratch"] = np.asarray(sc, float) / 255
    t["grunge"] = np.clip((fbm(n, 1.1, rng("grunge")) - 0.45) * 3, 0, 1) * np.clip(fbm(n, 0.5, rng("grunge_b")) * 1.4, 0, 1)
    g = fbm(n, 0.6, rng("debris"))
    t["debris"] = (g > np.quantile(g, 0.93)).astype(float) * (0.5 + 0.5 * fbm(n, 1.0, rng("debris_b")))
    cells = 64
    tone = fbm(n, 1.8, rng("news"))
    fx, fy = (xx * cells) % 1 - 0.5, (yy * cells) % 1 - 0.5
    halftone = (np.sqrt(fx * fx + fy * fy) < 0.62 * np.sqrt(tone)).astype(float)
    t["newspaper"] = halftone * 0.85 + fbm(n, 0.4, rng("news_b")) * 0.15
    # patterns
    row = (yy * 16).astype(int)
    bx = (xx * 8 + (row % 2) * 0.5) % 1
    by = (yy * 16) % 1
    mortar = (bx < 0.06) | (by < 0.12)
    t["brick"] = norm(np.where(mortar, 0.15, 0.8) + 0.2 * fbm(n, 0.9, rng("brick")))
    cx, cy = xx - 0.5, yy - 0.5
    rr = np.sqrt(np.minimum(cx ** 2, (1 - np.abs(cx)) ** 2) + np.minimum(cy ** 2, (1 - np.abs(cy)) ** 2) * 0.05)
    t["wood"] = norm(0.5 + 0.5 * np.sin(yy * 2 * math.pi * 24 + fbm(n, 1.6, rng("wood"), aniso=(6, 1)) * 10))
    t["pebbles"] = worley(n, 90, rng("pebbles"), "f2-f1")
    iso = np.maximum(np.maximum(stripes(n, 32, 0, 0.06), stripes(n, 32, 60, 0.06)), stripes(n, 32, -60, 0.06))
    t["grid_iso"] = iso
    t["grid_square"] = np.maximum(stripes(n, 32, 0, 0.06), stripes(n, 32, 90, 0.06))
    t["checker"] = (((xx * 16).astype(int) + (yy * 16).astype(int)) % 2).astype(float)
    t["velvet"] = norm(fbm(n, 1.4, rng("velvet")) ** 2 + 0.2 * fbm(n, 0.6, rng("velvet_b")))
    del rr
    return {k: gray(v) for k, v in t.items()}


# --------------------------------------------------------------------------------------------- presets

def catalog_files() -> dict[str, str]:
    """`presets` group: path under `presets/` → file text, built by the tools/catalog package."""
    tools_dir = str(ROOT / "tools")
    if tools_dir not in sys.path:
        sys.path.insert(0, tools_dir)
    import catalog

    return catalog.render()


# --------------------------------------------------------------------------------------------- driver

GROUPS = {
    "heads": lambda: {**h_basic(), **h_grain(), **h_chalk(), **h_bristle(), **h_knife(), **h_sponge(),
                      **h_splatter(), **h_design(), **h_stamps(), **h_watercolor()},
    "particles": particles,
    "textures": textures,
    "presets": catalog_files,
}

#: Groups that write text files (path → text) instead of images.
JSON_GROUPS = {"presets"}


def generate(out: Path, only: str | None) -> dict:
    manifest = {"schema": 1, "assets": []}
    for group, fn in GROUPS.items():
        if only and group != only:
            continue
        d = out / group
        if group in JSON_GROUPS:
            for rel, text in sorted(fn().items()):
                p = d / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                with p.open("w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
            continue
        d.mkdir(parents=True, exist_ok=True)
        for name, img in sorted(fn().items()):
            img.save(d / f"{name}.png", optimize=True)
            manifest["assets"].append({"ref": f"asset:{group}/{name}.png", "mode": img.mode, "size": img.size[0]})
    if not only:
        (out / "assets.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=list(GROUPS))
    ap.add_argument("--check", action="store_true", help="regenerate into a temp dir and compare bytes")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    a = ap.parse_args()
    if a.check:
        tmp = Path(tempfile.mkdtemp())
        try:
            generate(tmp, a.only)
            bad = []
            for f in sorted(p for p in tmp.rglob("*") if p.is_file()):
                ref = a.out / f.relative_to(tmp)
                if not ref.exists() or not filecmp.cmp(f, ref, shallow=False):
                    bad.append(str(f.relative_to(tmp)))
            print("OK" if not bad else "MISMATCH:\n  " + "\n  ".join(bad))
            return 1 if bad else 0
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    m = generate(a.out, a.only)
    total = sum(p.stat().st_size for p in a.out.rglob("*.png"))
    print(f"{len(m['assets'])} assets, {total / 1e6:.2f} MB -> {a.out}")
    if not a.only or a.only == "presets":
        print(f"{len(list((a.out / 'presets').rglob('*.json')))} preset files -> {a.out / 'presets'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
