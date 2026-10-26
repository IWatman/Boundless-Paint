# ibisPaint: слои

Источник: ibispaint.com/lecture №5, 24, 29–32, 34, 95, 96, 135, 153, 156, 163, 173, 180, 191, 195.

## Layer Window
- Строка слоя: видимость, **Reorder handle** (drag), opacity.
- **Selection Layer** (розовый, всегда сверху): выделение рисуется кистью и ластиком, отображается голубым. Цвет на нём недоступен.
- Кнопки:
  - **Clear Layer** (с выделением: только выделенное);
  - **Invert Layer Color** (негатив, с выделением: только выделенное);
  - **Move Layer** (transform);
  - **Flip Layer H / V**;
  - Merge Down;
  - Delete;
  - Edit Adjustment Layer;
  - Add Layer;
  - **Import from Photo Library**;
  - **Flip Canvas H / V** (все слои);
  - **Clipping**;
  - **Alpha Lock** (не для папок и векторных);
  - Opacity.
- Command menu (︙):
  - **Clear White (Grayscale)**: в серый + белое становится прозрачным (для скана лайнарта);
  - **Clear White (Color)**: белое становится прозрачным с сохранением цвета;
  - **Select Opacity** (выделение по альфе);
  - Rename;
  - **Rasterize** (вектор → растр);
  - **Save Layer as Transparent PNG** (слой или папку в фотоальбом).
- Add Special Layer:
  - Add Folder;
  - **Add Vector Layer**;
  - Add Adjustment Layer;
  - Duplicate (слой или папка);
  - **Add Layer from Folder** (копия-композит папки);
  - **Add Layer from Canvas** (композит всего холста новым слоем сверху).
- Background row:
  - Background Color;
  - **Transparent (Light / Dark)** шахматка;
  - **Canvas Paper**.
- Blending mode list (+ пункт **Screentone**).
- **Floating Layer Window** (планшет/ПК): drag за Move Bar, ресайз за углы и края, Close.
- Settings:
  - Layer Row Height (обычная и floating);
  - Drag Left Side to Reorder;
  - **Thumbnail: Whole / Only Contents**;
  - Set Added Layer as Current.
- Число слоёв без лимита (ограничено памятью).

## Папки
- Add Folder над выбранным слоем.
- Вход в папку: свайп reorder-handle вправо (только слой прямо под папкой). Выход: свайп влево (нижний слой папки).
- Тап по превью = свернуть/развернуть. Rename.
- **Move Folder**: трансформ всех слоёв папки вместе. Если внутри vector или adjustment, диалог: Yes (растеризовать), No (трансформировать только то, что можно), Cancel.

## Vector layer
- Линии хранятся как вершины и пути («brush shapes»). Можно масштабировать без потерь, после рисования менять кисть, толщину, цвет.
- Создание: Add Vector Layer или кисти Vector Dip Pen / Vector Felt Tip Pen (автоматически создают слой).
- Vector tool:
  - тап = выбрать фигуру;
  - drag по диагонали = мультивыбор;
  - тап повторно = снять;
  - drag/zoom/rotate выбранного;
  - Edit (кисть, тип линии, цвет).
- Vector Command menu:
  - **Edit Vertices**;
  - Bring to Front / Send to Back / Reorder Shapes;
  - Flip H/V;
  - Deselect;
  - Delete;
  - **Convert to Polyline** (прямые, rect, polygon из Drawing Tool);
  - **Convert to Curve** (круг, эллипс);
  - **Break Loop / Make Loop**;
  - **Simplify** (Strength, Angle Threshold, Show Points).
- Edit Vertices:
  - drag вершин;
  - выделение рамкой;
  - Delete Selected Vertices;
  - long-press по вершине = режим ручек.
  - Ручки: **Smooth** (связаны, симметричная длина), **Normal** (связаны, длины раздельно), **Angle** (независимы).
- Плотность вершин зависит от скорости мазка.
- **Vector Eraser**:
  - Mode: **Up to Intersection** (до пересечения) / **Entire Line**;
  - Line End: Tapered / Round;
  - обвести короткую линию = удалить.
- Растровый ластик на векторе создаёт невидимые **eraser shapes** (маскирующие фигуры, их можно двигать). При merge могут скрыть линии нижнего слоя, лечится через Bring to Front.
- На векторе недоступно: Blur, Smudge, Alpha Lock, Perspective/Mesh transform, фильтры. Нужно Rasterize (необратимо).

## Adjustment layer
- Add Special Layer → Add Adjustment Layer → выбор фильтра из Filter Selector → параметры → ✓.
- Действует на все слои ниже. **Readjust**. Сила = opacity слоя.
- Settings: Save Adjustment Layer Parameters, Reset.
- Список типов = подмножество фильтров (Adjust Color и др.), см. 17/18.

## Clipping и Alpha Lock
- Clipping к слою ниже. Alpha Lock сохраняет альфу пикселей.

## Screentone (свойство слоя)
- Blend mode «Screentone» превращает слой в растр-тон (паттерн, например Dot L6). Плотность тона = яркость цвета.
- Красится кистью или заливкой. 46 тонов (store listing). Подробнее в 21.

## Canvas Paper
- Фон-текстура (22+ бумаг: Cloth, Fabric, Gauze, Steel Plate, Glass, Carpet, Japanese Paper, Mat 1–4, Normal 1–3, Rough 1–4, Streak 1–2, Watercolor 1–2, Crumpled 1–2). Регулируется opacity. Затемняет холст.
- Background color холста-подложки (Settings) — это цвет рабочей области, не арта.

## Contents Layer Selection
- Пипетка или quick eyedropper показывает кнопку с именем слоя, на котором нарисован пиксель под курсором. Тап = сделать текущим.
- Alt+Shift+drag (Option+Shift на Mac). Отключается в Settings.

## Что добавить к IP-модели
- Vector layer + vector tool + vector eraser.
- Selection Layer как редактируемый слой (у IP маска редактируется в workspace, это эквивалент).
- Clear White, Invert Layer, Flip layer/canvas H/V, Add Layer from Canvas/Folder, Save Layer as PNG.
- Contents Layer Selection.
- Thumbnail: Only Contents.
- Screentone как свойство слоя.
- Transparent bg light/dark.
