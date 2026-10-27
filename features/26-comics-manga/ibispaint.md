# ibisPaint: manga and comics

Source: ibispaint.com/lecture №29, 38, 63, 66, 157, 170, 178, 184–187.

## Manga Manuscript (a canvas type)
- New Canvas → Manga Manuscript → Manuscript Type: **Fanzine**, **Manga**, **American Comicbook** (+ sizes A5/B5/B4… and dpi). Custom = your own values (a warning that it is unsuitable for a print shop).
- Guides:
  - the **Inner frame** (blue, the safe area for text and faces);
  - the **Final size** (green, the trim);
  - the **Bleed** (red, the bleed area).
- Settings (View → Manga Manuscript Settings):
  - the mm/in units;
  - Manuscript W/H;
  - Final W/H;
  - Bleed width;
  - Inner frame W/H;
  - Inner offset X/Y;
  - Resolution;
  - **Color mode**.
- Showing the guides: View → Manga Manuscript.
- The formula: px = inch × dpi, mm/25.4.
- Recommendations:
  - 350 dpi for color, 600+ for gray and B/W;
  - dpi that are multiples of 1200/N (600, 400, 300) against moiré;
  - do not scale the canvas;
  - do not make the tone gray;
  - **a non-rasterized screentone keeps the dot size on resize**.

## The canvas Color Mode (№69/178)
- Color.
- **Gray Scale** (256 shades).
- **Black & White** (1-bit: gray only via a screentone, without dot anti-aliasing).
- Export to CMYK (№207) see 23.

## Frame Divider tool
- Add Frame:
  - Horizontal Space, Vertical Space (the margins);
  - Frame Thickness;
  - Border Color.
- Edit (before dividing): **Horizontal Interval / Vertical Interval** (the gutters).
- Dividing: a swipe along a storyboard line.
- Select (with a frame, multi-select), dragging the handles = the shape.
- Editing the selected one: Border Color, Border Thickness, the **Fill Color** (+opacity) of the panel.

## Manga tools (№185)
- A Screentone layer: the "Screen Tone" blend, Dot L6…L16 patterns (L12/L16 for print), the density = the brightness of the color.
- Filters Draw: Radial Line, Speed Line (a focus handle + sliders) with a selection by panel (Magic Wand by the frame layer).
- **Outline pen / balloon pen**: a Double-type brush, the outline ≈5 px.
- Jagged balloons: the Elliptical ruler + the Wedge/Flash brush. Shape tool → Speech Bubble.
- Text: **vertical / horizontal**, Left/Center/Right.
- Filter Artistic → **Manga Background** (a photo → a manga background: Contrast, Edge Darkness, Screen Tone Size…).
- Perspective Array Ruler (1/2/3pt, Initial Position, Face/Vertex knobs, Array Selection), Array Ruler.

## Materials (the Material tool)
- Categories:
  - Tracing Sketches (Whole Body, Mini, Partial, Building);
  - **Manga Balloon**;
  - Background (Anime Background Gray and others);
  - Cloth Patterns (Color);
  - Screen tones.
- Search by name. A material is placed as a layer, resize → clip/mesh/multiply.

## Publishing
- Online Gallery → Comic:
  - a banner 1380×440;
  - an icon 1024×768;
  - a story icon 150×150;
  - ≥8 pages per chapter;
  - the chapter number format;
  - the category;
  - the language;
  - the Continue/Complete status.
- Print: the name "Title + number", Share in the required format.
- Multiple pages comic in the Online Gallery (№157).

## What to add to IP (IP has only Panels)
- A "Manuscript" project type with bleed/trim/safe and presets.
- A canvas color mode (Gray/BW) as a real mode, not only proofing.
- Frame Divider: margins, H/V gutters, panel fill, multi-select (merge with IP Panels).
- Vertical text.
- A vector screentone as a layer property (the dot size in physical units).
- Speed/Radial line filters.
- The Manga Background filter.
- Balloons (a shape + a library).
- Multiple pages? ibis does not have it (one page = one artwork + publication of chapters). IP does not have it either. **The decision is ours.**
