# ibisPaint: import, export, formats

Source: ibispaint.com/lecture №21, 22, 28, 32, 33, 84, 119, 137, 188, 205, 206, 207, 210.

## Image export (Save Image)
- File Type: **PNG** (a Transparent toggle), **JPEG**, **PSD** (Preserved Layers / Flattened).
- **Super-resolution ×2** (an AI upscale on export, PNG).
- **AI Disturbance** (noise against AI training, an intensity slider).
- **Color Space**:
  - RGB;
  - **CMYK** (PSD);
  - **Grayscale 8-bit**;
  - **Monochrome 1-bit**.
- CMYK:
  - **Color Profile** (loadable print shop profiles);
  - **Rendering Intent** (Perceptual / Relative Colorimetric [the default] / Saturation / Absolute);
  - **Black Point Compensation** (off with Absolute).
- **Save Layer as Transparent PNG** (a layer or a folder).

## Project formats
- **IPV**: the artwork + the timelapse history, cross-platform.
- **IPVZ**: a project with animation and audio.
- **ipcfg**: a backup of the settings (brushes, palettes).
- **CLIP**: an upload to the Clip Studio cloud (the layer structure; text and tone are rasterized).
- **PSD import**: opening a PSD as an artwork.
- Brushes: a **QR code** PNG.

## Video
- The timelapse to the photo album. YouTube upload.
- Animation: **MOV, MP4**. The online gallery strips the sound.

## Transfer
- IPV via: the ibis cloud, Dropbox and others, AirDrop, Finder, Quick Share, USB/MTP.

## What to add to IP
- CMYK PSD export with an ICC profile, intent, BPC.
- Grayscale 8 / 1-bit export.
- PSD Flattened.
- Super-resolution (optional, a local model).
- AI-Disturbance (optional, our own implementation of adversarial noise is needed).
- Export of a single layer/folder to PNG.
- A settings backup.
- Video formats for animation.
- Our own formats: project (a ZIP container like IP's PNTR), brush, pack, palette, settings.
- Import and export of **OpenRaster (.ora)** and **PSD** for compatibility. The decision is ours, the originals have no ORA.
