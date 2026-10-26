# ibisPaint: трансформации

Источник: ibispaint.com/lecture №35, 36, 153, 154, 156.

## Translate Scale
- Тогглы: Scaling, Rotation, **Repeat** (показ повтора).
- Числовые: **Movement X/Y**, **Zoom**, **Angle**.
- **Interpolation** (6 методов):
  - Nearest Neighbor (пиксель-арт);
  - Average Pixel (для уменьшения);
  - Bilinear;
  - Bicubic;
  - Lanczos 2;
  - Lanczos 3.
- **Target**: текущий слой или весь холст.
- Show Frame around Object: тач вне рамки = навигация.
- Жесты: corner = scale, inside = move, rotation knob, 2 пальца вне рамки = холст.

## Perspective Form
- 4 угла.
- Repeat: Off / Inner / Horizon / Full (зеркальное чередование).
- Repeat X/Y, Phase X/Y, Interpolation.

## Mesh Form
- Division X/Y, Smoothness, Draw Order.

## Прочее
- Flip layer H/V, flip canvas H/V (кнопки в Layer window).
- Move Folder (все слои папки).
- Vector и adjustment слои при деформации растеризуются (с подтверждением).

## Что добавить к IP
- Числовой ввод (X, Y, zoom, angle).
- Выбор интерполяции (6 методов).
- Target = весь холст (у IP мультивыбор слоёв, это эквивалент).
- Vertical flip (у IP только горизонтальный).
- Repeat-тайлинг в Perspective.
- Mesh N×M.
