from PIL import Image, ImageDraw
import math, pathlib

S = 2048
# Background gradient
bg = Image.new("RGB", (S, S), (14, 18, 40))
px = bg.load()
for y in range(S):
    for x in range(S):
        t = (x + y) / (2 * S)
        r = int(16 + 30 * t)
        g = int(22 + 18 * t)
        b = int(48 + 58 * t)
        px[x, y] = (r, g, b)

# Rounded mask
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=430, fill=255)
icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
icon.paste(bg, (0, 0), mask)

mark = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(mark)

# Lemniscate brush stroke: variable-width round dabs along the curve.
cx = cy = S / 2
for i in range(1201):
    t = 2 * math.pi * i / 1200
    x = cx + 0.58 * S * math.cos(t) + 0.02 * S * math.cos(3 * t)
    y = cy + 0.28 * S * math.sin(2 * t) + 0.02 * S * math.sin(5 * t)
    # taper the ends of the loop and vary the width like a loaded brush
    w = (68 + 28 * math.sin(t * 2 + 0.7) ** 2) * (4.2 / 4)
    # gradient along the loop: teal -> violet -> coral
    u = (math.sin(t) + 1) / 2
    if u < 0.5:
        f = u / 0.5
        col = (int(80 + (123 - 80) * f), int(227 + (97 - 227) * f), int(194 + (255 - 194) * f), 255)
    else:
        f = (u - 0.5) / 0.5
        col = (int(123 + (255 - 123) * f), int(97 + (107 - 97) * f), int(255 + (107 - 255) * f), 255)
    d.ellipse([x - w, y - w, x + w, y + w], fill=col)

# A few paint flecks
for (fx, fy, fr) in [(0.22, 0.26, 18), (0.80, 0.25, 13), (0.82, 0.74, 22), (0.18, 0.76, 11)]:
    x, y = fx * S, fy * S
    d.ellipse([x - fr, y - fr, x + fr, y + fr], fill=(255, 236, 210, 230))

# Soft inner highlight on the stroke
icon = Image.alpha_composite(icon, mark)

master = icon.resize((512, 512), Image.LANCZOS)
qa = pathlib.Path(".tools/qa")
qa.mkdir(parents=True, exist_ok=True)
master.save(qa / "boundless-icon.png")

res = pathlib.Path("app/src/main/res")
dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, size in dens.items():
    folder = res / f"mipmap-{name}"
    folder.mkdir(parents=True, exist_ok=True)
    master.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher.png")
    master.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher_round.png")

# Adaptive foreground: just the mark, with the standard 108 dp canvas and 72 dp safe zone.
fg_master = Image.new("RGBA", (S, S), (0, 0, 0, 0))
fg_master.alpha_composite(mark.resize((S, S), Image.LANCZOS))
# Scale the mark into the central 66% for the adaptive safe zone.
small = Image.new("RGBA", (S, S), (0, 0, 0, 0))
m2 = fg_master.resize((int(S * 0.68), int(S * 0.68)), Image.LANCZOS)
small.alpha_composite(m2, ((S - m2.width) // 2, (S - m2.height) // 2))
for name, size in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    folder = res / f"mipmap-{name}"
    folder.mkdir(parents=True, exist_ok=True)
    small.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher_foreground.png")

adaptive = res / "mipmap-anydpi-v26"
adaptive.mkdir(parents=True, exist_ok=True)
for round_name in ["ic_launcher", "ic_launcher_round"]:
    (adaptive / f"{round_name}.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n', encoding="utf-8")
(res / "values" / "colors.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    '    <color name="ic_launcher_background">#10142B</color>\n</resources>\n', encoding="utf-8")
print("icon generated")
