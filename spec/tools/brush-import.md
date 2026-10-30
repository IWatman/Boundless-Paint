# Brush import and export

Goal: let users bring brushes from other painting apps. Imported brushes land in the **Imported** folder of the brush browser (a pack becomes its own folder). Heads and textures transfer exactly; behaviour parameters are mapped approximately, because every engine is different.

## Formats

| Format | App | Basis for reading | What we map |
|---|---|---|---|
| `.pbrush`, `.pbrushes` | Painter | our own format (`core/io/BrushIO`) | everything |
| PNG, JPG | any | standard images | a head (grayscale = coverage) or a texture |
| `.abr` | Photoshop | publicly documented by third parties (sampled brush sections, descriptor blocks) | sampled tips, spacing, angle, roundness, scatter, size/opacity jitter, part of the pressure dynamics |
| `.brushset`, `.brush` | Procreate | ZIP with shape/grain PNGs and an XML/binary plist (open container formats) | shape → head, grain → texture, spacing, jitter, taper, pressure size/opacity |
| `.kpp`, `.bundle` | Krita | open source format: a PNG preview with the preset XML in a text chunk; a bundle is a ZIP with a manifest | brush tip (auto or predefined), texture, spacing, size/opacity curves |
| `.gbr`, `.gih`, `.vbr` | GIMP | officially documented formats | gray or RGBA tips, image-hose sets (as a random-head brush), parametric tips |
| `.myb` | MyPaint | open JSON | radius, hardness, opacity, spacing (dabs per radius), pressure curves, smudge |
| `.sut` | Clip Studio Paint | an SQLite database; table layout documented by the community | brush tips (material images) and basic parameters |

Not supported: Infinite Painter and ibisPaint brush files. Their formats are closed and undocumented, and reading them would require taking their apps apart, which the clean-room rules forbid ([00-clean-room/PROCESS.md](../../00-clean-room/PROCESS.md)).

Import works only on files the user provides. We never ship brushes or images taken from other products.

## Rules
- Parsers live in `core/io/import/` (pure Kotlin, JVM-tested with small hand-made fixtures we create ourselves, never third-party brush files).
- Every parser is defensive: size limits, no unbounded allocations, a malformed file gives a readable error and imports nothing.
- Unknown parameters are dropped silently. The import report lists the brush count and anything skipped.
- Names come from the file (or the file name); the category is "Imported".

## Order
1. `.pbrush` / `.pbrushes` import and export from the brush browser (SAF open, Share), PNG/JPG as a head or texture.
2. `.abr` and Procreate `.brushset` / `.brush`.
3. Krita `.kpp` / `.bundle`, GIMP `.gbr` / `.gih` / `.vbr`, MyPaint `.myb`.
4. Clip Studio `.sut`.
