# Clean-room process

Goal: an independent implementation of a bitmap editor. Functionality = the union of Infinite Painter (IP) and ibisPaint. UI modelled on IP. Without copying code, assets, texts, binary formats or trademarks.

## Roles
| Role | Who | What they do | What they do NOT do |
|---|---|---|---|
| **Spec team** ("dirty") | researchers | read public documentation, use the apps as users, write functional specs in their own words | do not write product code |
| **Reviewer / legal** | a separate person | checks the specs: no copied text, code, assets or protected expressions | — |
| **Impl team** ("clean") | developers | implement only from the specs in `features/` and `spec/` | do not open the original apps, their APK/IPA, decompilation or network dumps |

One person can combine spec and impl only with documented isolation. For a true clean-room, different people are better.

## Allowed sources for the spec team
- Public manuals: docs.infinitestudio.art, ibispaint.com/lecture, store pages.
- Observing app behavior as a user (black-box): UI screenshots for ourselves, measurements, videos. Only a description of behavior goes into the spec.
- Public standards: W3C Compositing & Blending, PSD spec (Adobe File Format Specification), OpenRaster, ICC, ISO 216 (paper sizes).
- Well-known algorithms: flood fill, Gaussian, Lanczos, Voronoi, Bézier, MLS/mesh warp, LUT curves.

## Forbidden
- Decompiling and disassembling APK/IPA/EXE, extracting resources.
- Copying built-in brushes, textures, papers, patterns, palettes, fonts (except OFL fonts from the original source, Google Fonts), materials, icons.
- Reverse engineering proprietary formats (.pntr, .prbr, .przp, .clrs, .ipv, .ipvz, .ipcfg) for compatibility. We make our own formats.
- Verbatim transfer of doc texts or UI strings. We write in our own words.
- Brand and product names in the UI, built-in brush names as they are.
- ibis network APIs (cloud, gallery, QR brush format).

## UI like IP: where the boundary is
- Functional layout (brush mode toolbar, Creative/Editing menus, mini-toolbars, gestures) is an idea and a workflow, and it can be reproduced.
- Cannot be copied: icons, exact graphic styles, brand colors, logos, texts.
- We build our own visual language within the same structure.
- Before release, legal consultation on trade dress in the target jurisdictions.

## Artifacts
1. `features/<NN-area>/infinite-painter.md` and `ibispaint.md`: raw functionality by source (spec team).
2. `spec/`: the combined functional specification for the impl team.
3. `00-clean-room/SOURCES.md`: source log.
4. Review log: who checked which document and when.

## Checklist before handing a spec to impl
- [ ] No verbatim quotes longer than a few words.
- [ ] No built-in asset or brand names in the requirements (only in reference sections, marked as such).
- [ ] Numbers and ranges are marked: from docs or from observation.
- [ ] Unknowns are marked as `TBD / measure`.
