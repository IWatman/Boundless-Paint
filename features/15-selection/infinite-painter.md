# Infinite Painter: выделения

Источник: User Guide /painter/selections/*.

## Модель
- Выделение = **8-bit маска** (мягкие края возможны). Работает как трафарет для кистей, заливок, фильтров. Само может трансформироваться и редактироваться.
- Selection workspace: пока он открыт, **обычные инструменты редактируют маску**, а не слой. Работают кисти, Pattern tools, Liquify, фильтры (для 8-bit полезны не все), трансформация маски.

## Workspace (общий тулбар)
- Список инструментов (тап по имени текущего).
- Режим:
  - **Normal (add)**;
  - **Subtract**.
- Cancel (закрыть и очистить), Confirm (закрыть, выделение активно).
- Операции над маской, операции над содержимым. Тулбар скроллится.
- Top bar:
  - Selection menu (только если есть выделение): те же операции + Mask appearance, без инструментов;
  - тоггл Active (красный). Выключение не стирает маску. Клавиша `\`.

## Инструменты (8 + слой)
- **Lasso**: свободный контур.
- **Rectangle**: ручки rotate, resize, move, stretch. Stamp.
- **Circle**: от центра. Stretch → эллипс. Move, resize, rotate. Stamp.
- **Polyline**:
  - тап = узел;
  - drag = свободная линия;
  - hold ~1 с = precision-перетаскивание узла;
  - ✓.
- **Path**: Bézier (drag = smooth, tap = cusp, тап по узлу = toggle, drag на соседа = удалить), close, ✓.
- **Wand**:
  - как bucket, но создаёт выделение;
  - тап = preset tolerance, drag = Live Tolerance;
  - tolerance общий с Fill (1–100%);
  - sampling multi/single layer;
  - source layer.
- **Brush**: рисовать выделение любой кистью. Paint добавляет, Erase убирает. Цвет игнорируется.
- **Color range**: тап по цвету → все похожие цвета на текущем слое, несвязные тоже. Свой tolerance, независимый.
- **Select Layer** (Layer options): непрозрачные пиксели слоя, группы или маски.

## Совместимость с гайдами (таблица из доки)
- Rectangle, Circle, Polyline — это Lasso с другим типом шейпа.
- Brush selection совместим со всем.
- Wand и Color range ни с чем не совместимы.
- Lasso совместим только с Symmetry. Rect/Circle добавляют Lines/Ellipse/Pen guides и Grids.

## Boolean (Layer options → Boolean, когда выделение уже есть)
- A = выделение, B = область слоя:
  - Select (заменить);
  - Add;
  - Subtract A−B;
  - Reverse Subtract B−A;
  - Intersect;
  - Difference (XOR).

## Операции над маской
- Select all.
- Invert.
- Clear.
- Transform mask (все режимы трансформации).
- **Expand** / **Contract**: слайдер 0% → max 6 px, применять повторно.
- **Feather** (= blur маски).

## Операции над содержимым
- Transform.
- **Duplicate**: на новый слой без имени, opacity, blend и маски. Пустое выделение = весь слой.
- **Isolate** (cut → new layer).
- **Copy Merged**: со всех видимых слоёв, сразу открывается в Transform. Пустое = весь холст.
- **Delete** (только активный слой).
- **Clipboard**: во внутренний постоянный буфер-библиотеку (см. 23).
- **Make Brush**: фрагмент → Brush Creator как head.
- После операции выделение очищается. Повторно использовать маску: сохранить её в layer mask.

## Mask appearance
- **Marching ants** или **Color overlay** (дефолт серый 50%). Цвет и opacity оверлея (100% удобно для ручной правки маски).
