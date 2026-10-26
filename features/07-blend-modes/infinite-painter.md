# Infinite Painter: режимы наложения

Источник: User Guide /painter/layers/blending/*.

- 30 режимов. Passthrough не считается, 3 маскирующих режима входят в число.
- Одни и те же режимы для слоёв, групп и кистей (Brush Editor → Stroke → Blending mode + Strength).
- Для кисти режим сравнивает цвет мазка с активным слоем.
- Opacity-слайдер виден рядом с селектором, работает в реальном времени.

| Группа | Режимы |
|---|---|
| Normal | Normal, (Passthrough: только группы) |
| Darkening | Darken, Multiply, Color Burn, Linear Burn (A+B−1), Darker Color |
| Lightening | Lighten, Screen, Color Dodge, Linear Dodge, Lighter Color |
| Contrast | Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix |
| Comparative | Difference, Exclusion, Negative, Subtract, Divide |
| HSL | Hue, Saturation, Color, Luminosity |
| Masking (уникальные) | Erase, Mask, Line Art |

## Маскирующие режимы
- **Erase**: альфа слоя «вырезает» слои ниже, неразрушающе. Кисть в Paint с этим режимом работает как ластик.
- **Mask**: яркость слоя маскирует нижние. Тёмное скрывает, белое и прозрачное показывают. Эквивалент «группа + маска», только быстрее.
- **Line Art**: как Mask, но самые тёмные части остаются видимыми как линия. Можно стопкой класть несколько слоёв лайнарта, каждый со своей маской.

## Заметки к реализации
- Стандартные режимы совпадают с PS/Procreate. Брать публичные формулы (W3C Compositing & Blending Level 1 и стандартные формулы для Vivid/Linear/Pin Light/Hard Mix/Darker/Lighter Color/Subtract/Divide).
- **Negative**: нестандартный. Предположительно 1−|1−A−B| (как в Krita «Negation»). Проверить эмпирически.
- Учитывать gamma-correction режим (05-color): смешение в линейном пространстве.
