# ibisPaint: Liquify Pen, Mesh/Perspective Form

Источник: ibispaint.com/lecture №35, 36, 138, 154.

## Liquify Pen (Special Pen)
- Параметры: **Thickness**, **Strength**.
- Режимы:
  - **Drag** (push);
  - **Shrink** (pinch);
  - **Expand** (bloat);
  - **Smoothen** (сгладить деформацию);
  - **Revert** (восстановить).
- Работает как обычная кисть-инструмент (без отдельного workspace).

## Transform → Perspective Form
- 4 угла.
- Perspective on/off.
- **Repeat**:
  - Off;
  - Inner (тиражирование внутри четырёхугольника);
  - Horizon (внутри и снаружи, до горизонта);
  - Full (как Horizon, зеркально чередуясь).
- **Repeat X/Y** (кол-во), **Phase X/Y** (сдвиг).
- Interpolation on/off.
- Применение: текстура пола в перспективе.

## Transform → Mesh Form
- **Division X/Y** (сетка).
- **Smoothness**.
- **Draw Order** (порядок наложения ячеек).
- Применение: складки юбки и пр.

## Сопоставление с IP
- IP Liquify: Move, Pinch, Bloat, Swirl CW/CCW, Restore, Rewind + сетка + Symmetry.
- Добавить из ibis:
  - Strength;
  - Smoothen (аналог Rewind, но локальный);
  - быстрый режим Liquify-кисти без входа в workspace (опционально).
- IP Warp (3×3 Bézier) против ibis Mesh (N×M + smoothness + draw order): **объединить** = Warp с настраиваемой сеткой.
- Perspective Form Repeat (Inner/Horizon/Full) — нет у IP. **Добавить** в Distort как опцию тайлинга.
