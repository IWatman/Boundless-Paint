# Форматы файлов

Все собственные форматы = ZIP (store для бинарных тайлов, deflate для JSON) с `manifest.json` и полем `"schema": <int>`. Чтение: неизвестные поля игнорируются, новая schema читается с миграцией.

## Проект `.pnt`
```
manifest.json      { schema:1, app:"painter", id, name, type, width, height, dpi, colorMode, created, modified,
                     paper{...}, nodes:[...дерево узлов, см. document-model...], selection?, guides, references[],
                     palettes[], recentBrushes[], stats{}, timelapse{enabled, quality, followNav} }
thumb.png          512 px по длинной стороне
layers/<nodeId>/tiles.idx      бинарный индекс: [u16 tx, u16 ty, u32 offset, u32 len] × N
layers/<nodeId>/tiles.bin      тайлы: half-float RGBA premultiplied, 256×256, LZ4-frame на тайл
masks/<nodeId>/tiles.*         R8, LZ4
vector/<nodeId>.json           shapes (M4)
refs/<id>.png|jpg              референсы
history/<n>/...                save points (полная копия layers+manifest, без history)
timelapse/frames.mp4|chunks/   сегменты H.264 + index.json
```
- Хранение в приложении: распакованная директория `files/projects/<id>/` (быстро). `.pnt` только для экспорта и синка.
- Атомарная запись: пишем в `<id>.tmp/`, затем rename. Crash-backup = журнал команд с последнего сохранения (`journal.bin`).

## Кисть `.pbrush` / пак `.pbrushes`
- `.pbrush`: `brush.json` (см. brush-engine.md §6) + `head.png`, `texture.png`, `particle.png` (8-bit gray или RGBA).
- `.pbrushes`: `manifest.json {name, brushes:[file]}` + вложенные `.pbrush`.

## Палитра `.ppal`
JSON: `{schema:1, name, colors:[{"hex":"#RRGGBBAA","name"?}]}`. Импорт также из GIMP `.gpl` и `.ase` (публичные спецификации), M5.

## Настройки `.pcfg`
JSON-дамп настроек + список пользовательских кистей, палитр, шорткатов.

## PSD (M3)
По Adobe Photoshop File Format Specification (публичная).
- Экспорт: 8-bit RGB (CMYK 8-bit в M4).
  - растровые слои;
  - группы (lsct): normal / passthrough;
  - opacity, blend (маппинг ниже), clipping, маски слоя;
  - текст → растр + имя;
  - adjustment → растр (запекаем отдельным слоем с режимом), если нет PS-эквивалента.
  - Paper → нижний слой «Paper».
- Импорт:
  - растровые слои, группы, маски, clipping, opacity, blend;
  - всё прочее растеризуется из композитного превью слоя или пропускается.
- Маппинг blend:
  - normal→norm, darken→dark, multiply→mul, color_burn→idiv, linear_burn→lbrn, darker_color→dkCl;
  - lighten→lite, screen→scrn, color_dodge→div, linear_dodge→lddg, lighter_color→lgCl;
  - overlay→over, soft_light→sLit, hard_light→hLit, vivid_light→vLit, linear_light→lLit, pin_light→pLit, hard_mix→hMix;
  - difference→diff, exclusion→smud, subtract→fsub, divide→fdiv;
  - hue→hue, saturation→sat, color→colr, luminosity→lum;
  - add→lddg (приближение);
  - negative, invert, erase, mask, line_art → запекание.

## Растровые
- PNG: 8-bit RGBA / RGB, опц. 16-bit.
- JPG: quality 1–100.
- WebP: lossy или lossless.
- Gray 8-bit, 1-bit PNG (M4).
- ZIP слоёв: PNG на слой + `layers.json` (порядок, opacity, blend).
