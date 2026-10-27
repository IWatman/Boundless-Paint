# Infinite Painter: brushes (library, modes, management)

Source: User Guide /painter/brushes/*. The engine parameters live in `04-brush-engine-settings/`.

## The concept of a brush
- A brush: any tool that follows the stylus or finger in real time. That includes both a pen and a palette knife.
- A brush can paint, blend, erase or copy pixels (clone).
- Anatomy:
  - a head bitmap (or the default round one);
  - a texture (optional);
  - parameters and effects (>100);
  - the result is written only to the active layer;
  - the paper texture (Paper layer) affects all strokes regardless of the brush texture.

## Brush modes (on the main toolbar)
- **Paint** (B): lays down color. Special effects work only here. Pure blenders (Colorless Blender, Palette Knife) lay down no color.
- **Blend** (K): smudges the colors of the active layer. Special effects are ignored. Instead of the color there is a gear button, which opens:
  - Blur (0 = transfer of individual pixels);
  - Pull (the length of the smudge stroke);
  - Flow (a multiplier for the head Flow, only when blending);
  - Sample lower layers: global for all Blend brushes. It samples the active and the lower visible layers and writes only to the active one.
- **Erase** (E): erases to transparency. Any brush works, effects are ignored.
- **Clone**: a quasi-brush (see 20-cloning).
- A separate brush is chosen for each mode. Any brush is available in any mode.
- A finger can be assigned a different mode than the stylus (Settings).
- Clearing a whole layer: Clear in the layer options or a long-press on Undo.

## Brush selector
- Opened by tapping the mode icon again.
- Parts of the window:
  - the current folder;
  - a preview of the current brush + control buttons;
  - the list of folders (scrolling);
  - the brushes of the folder (tap = select for the current mode).
- Special folders:
  - **Recent**: the brushes of this project, automatically;
  - **Favorites**: separate for each mode, a heart toggle, pre-filled on first launch;
  - **Created**: new brushes;
  - **Downloads**: downloaded or imported ones;
  - Custom: no limit, the icon = the first 2 characters of the name or an emoji at the start of the name;
  - Built-in: with their own icons.
- Buttons:
  - Add (create or import);
  - Edit (Brush Editor);
  - Options;
  - Favorite;
  - Reset (factory settings).

## Brush options menu
- Rename: built-in brushes can be given back their original name.
- Duplicate: the copy remembers its parent.
- Share: export to `.prbr`.
- Reset.
- Remove: take it out of the folder. A custom brush returns to Created/Downloads, a built-in one to its original folder.
- Delete: permanently, for custom brushes.
- Hide: for built-in ones, they cannot be deleted.

## Folders
- Creation, Rename (built-in ones too), Share (the whole folder → a `.przp` pack, the folder name = the pack name), Hide (a "show hidden" button), Delete (the brushes return to their original containers).
- Deleted built-in brushes are restored per folder: long-press → Restore.
- Folder names: any Unicode.
- Moving a brush: long-press + drag onto a folder.
- An imported `.przp` = a new folder.

## Quick settings
- Size: a virtual slider (drag from the button), the `[` `]` keys, the HUD.
- Size popup:
  - live preview;
  - Size;
  - Head angle;
  - the brush color blending mode;
  - the Brush Editor button.
- Opacity popup:
  - Opacity (for the whole stroke);
  - Flow (per dab);
  - Softness (blurring of the head edge).
  - Tapping a parameter name assigns it to the virtual slider and to the horizontal 3-finger gesture.
- Changes are written into the brush definition as soon as the popup closes. There is no undo, only Reset.

## Creating a brush
- Paths:
  - Add brush → Brush Creator;
  - download from Community;
  - import a third-party pack;
  - from a selection (fragment → brush head).
- **Brush Creator**:
  - name;
  - a type preset (a common template);
  - stroke preview;
  - head bitmap (empty = a circle);
  - texture (optional);
  - Confirm → the Created folder;
  - further refinement in the Editor.
- The head/texture selector:
  - built-in presets + an Imported section;
  - import from Gallery, Camera, Files, Pixabay search, Clipboard;
  - deleting an imported one: long-press → Delete. Warning: the bitmap may be used by several brushes.
- Bitmap semantics: brightness = coverage, white paints, black is transparent. An Invert toggle.
  - Built-in heads are stored black-on-transparent and are inverted by default.
  - Accepted: white/black, black/white, white/transparent, black/transparent, full-color (± alpha).

## Community and third-party brushes
- Community: free, a simple list with no categories or search, sorted by date, multi-select, already downloaded ones are highlighted.
- Third-party packs: `.przp` (a set) and `.prbr` (a single brush). Import through Files. Do not unpack a pack, otherwise it gets corrupted.
- There is no compatibility with ABR (Photoshop) or Procreate.

## Procedural brushes
- **Fill brushes**: no head, they accept filters and color adjustments. Screentone and particles do not work, watercolor is limited. See 09-fill.
- **Harmony brushes**: algorithmic. They draw a pattern of thin lines along the trajectory, and the density depends on the length and shape of the stroke. There is no head, Size = the line thickness.
  - The list: Sketchy, Shaded, LongFur, ShortFur, Ribbons, Curvy, Grid, Circles, Squares.

## Built-in library (v7, ~168–172 brushes, 16 categories)
In [brackets] the special effect of the brush.
- **Pencils (14)**: 2B, HB, 6B, Proko, Technical, Coarse, Concept, Waxy, Old Pencil; Cloth/Fiber Shader, Hatching, Cross Shader [screentone], Scratch Shader.
- **Pens (20)**: Solid, Soft, Fine Tip, Manga Inker, Tapered Inker, Old/Coarse/Dry Inker, Leaky, Pilot [particle], Gel, Velocity Pen [particle], Fountain, Fineliner, Signature, Slanted Inker, Torn Ink, Ink Wash [watercolor], Velocity Inker [watercolor], Pixel Pen.
- **Calligraphy (8)**: Monoline, Oval Lettering, Soft Taper, Floral Pistil, Chalk Signage, Split Nib, Raised Edge, Right Slant.
- **Markers (10)**: Dry Ink, Chisel Tip, Broad, Glazed, Wet, Colorless Blender, Sketch, Round, Brush Marker, Highlighter.
- **Paint (26)**: oil, acrylic, gouache, rollers, knives (Paint Stroke, Thick Angular, Clay Roller, Dry Brush, Filbert, Flat Oil, Gouache, Palette Knife, Loaded Knife, Thick Rake, Stucco Roller, Velocity Splatter [particle], etc.).
- **Watercolor (9)**: Wet Paper, Light/Heavy Wash, Heavy Bleed, Mostly Water, Chemical Burn, Salt Blossom, Circle Stains, Floral Stencil. Almost all [watercolor].
- **Sprayers (12)**: airbrush soft/medium/hard, Spray Paint, Splatter [particle], Blood/Water Splatter, Flecks, Circle Drip, Sand Blaster, Cloudy, Hard Round.
- **Chalks (7)**, **Charcoals (7)**.
- **Design (6)**: dashes, dots, arrows (Cut Out, Long Dashes, Doodle Dots, Flow Arrow, Arrows, Arrow Chain).
- **Fills (6)**: Solid, Screentone, Debris, Grunge, Scratch, Checker.
- **Glow (10)**: Light Pen, Neon, Sparkler [particle], Soft Glow, Bokeh, Scene Glow, Combustion, Flame Ribbon, Light Hairs, Stardust [particle].
- **Misc (13)**: the Twisty/Tube/Ribbon 3D brushes, Newspaper Transfer, sponges, Smoke, Outliner, the Roses and Stars stamps.
- **Screentone (7)**: Dash 45, Denimite, Dot 45, Halftone, Tone Shader, V/H Lines.
- **Textures (8)**: Bricks, Wood, Pebbles, Isometric/Standard Grid, etc.
- **Harmony (9)**: the list above.

Note for the clean room: do not carry over the built-in brush names; build our own library by category.
