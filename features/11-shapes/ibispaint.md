# ibisPaint: Shape tool и Drawing tools

Источник: ibispaint.com/lecture №51, 82, 93, 105–109, 212, 214, 216, 217.

## Shape Tool (тулбар-кнопка, иконка = текущая фигура)
- OFF = свободное рисование (+ Smart Shape).
- Фигуры:
  - Line;
  - Rectangle;
  - **Rounded Rectangle** (Radius 1–50% короткой стороны, жёлтая ручка, автоуменьшение радиуса);
  - Circle;
  - Ellipse;
  - **Regular Polygon** (Corners до 16);
  - **Thin Arrow**;
  - **Speech Bubble**.
- Общие опции:
  - **Draw Line by Brush** (форма, толщина, opacity кисти) или простая линия;
  - **Fill**;
  - **Fill with Current Color** (или отдельный цвет заливки);
  - opacity кисти 0 = только заливка без контура;
  - **Anti-aliasing** заливки.
- Edit shape (кнопка сверху после рисования): ручки формы и размера, ручка rotate. ✓ или смена инструмента = фиксация.
- На vector layer фигура остаётся редактируемой.

### Thin Arrow
- Drag от начала до острия.
- **Arrowhead Drawing Order** (для кистей с Fade): One Stroke / From the Tip / From Both Ends.
- Fill (залитый треугольник).
- Ручки: острие (длина и направление), концы, середина (изгиб = кривая стрелка).

### Speech Bubble
- Овальный баллон с хвостом к точке отпускания.
- Ручки хвоста:
  - tip;
  - base (только внутри баллона);
  - base edge (ширина);
  - middle (изгиб).

## Smart Shape (распознавание)
- Держать в конце мазка → пунктирный кандидат. Продолжая держать, drag правит угол и размер. Отпустить = коррекция.
- Иконки кандидатов: тап = переключить.
- Распознаются: curve, line, rectangle, regular polygon, circle, ellipse, polyline.
- Settings:
  - **Time for Activation** 0.1–5.0 с;
  - Bézier Correction Strength;
  - Polyline Correction Strength;
  - **Shape Priority** по каждой фигуре;
  - Reset.

## Drawing tools (в окне Stabilizer)
- Straight Line.
- Rectangle.
- Circle.
- Ellipse.
- Regular Polygon (до 16 углов).
- **Bézier Curve**: тап = точка прохождения, drag точки, drag линии = move, 2 пальца = zoom/rotate, ✓.
- **Polyline**: то же.
- На векторе конвертируются в Polyline/Curve для правки вершин.

## Сопоставление с IP
- IP: Line, Rectangle, Circle, Path, Arc, Stamp, shape detection (8 фигур), перспектива шейпов.
- Добавить из ibis:
  - Rounded Rectangle;
  - Regular Polygon;
  - Arrow;
  - Speech Bubble;
  - Fill/Outline-опции прямо в шейпе (у IP только через fill-brush);
  - Shape Priority и время активации детекции;
  - кандидаты-альтернативы;
  - векторные шейпы на vector layer.
