# ibisPaint: режимы наложения

Источник: ibispaint.com/lecture №83. 28 режимов (у app store заявлено 27, разница в версиях).

| Группа | Режимы |
|---|---|
| Normal | Normal |
| Dark | Darken, Multiply, Color Burn, Linear Burn, Darker Color |
| Light | Lighten, Screen, Color Dodge, Linear Dodge, **Add**, Lighter Color |
| Contrast | Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix |
| Variations | **Invert** (инверсия нижнего, цвет верхнего не важен), Difference, Exclusion, Subtract, Divide |
| Color | Hue, Saturation, Color, Luminosity |

## Отличия от IP
- Есть у ibis, нет у IP:
  - **Add**: отличается от Linear Dodge при opacity<1, ярче, для свечения;
  - **Invert**.
- Есть у IP, нет у ibis: Negative, Erase, Mask, Line Art, Passthrough.
- У ibis в списке blend mode есть отдельный пункт **Screentone** (применить скринтон к слою), см. 21.

## Объединённый набор для реализации
Normal, Passthrough (группы), Darken, Multiply, Color Burn, Linear Burn, Darker Color, Lighten, Screen, Color Dodge, Linear Dodge, Add, Lighter Color, Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix, Difference, Exclusion, Negative, Invert, Subtract, Divide, Hue, Saturation, Color, Luminosity, Erase, Mask, Line Art. Итого **33** режима.
