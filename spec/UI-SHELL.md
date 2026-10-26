# UI-каркас (по модели Infinite Painter)

Интерфейс повторяет структуру IP. Визуальный стиль свой (см. 00-clean-room/PROCESS.md). Функции ibisPaint встраиваются в слоты этого каркаса (FEATURE-MAP.md, колонка «Где в UI»).

## Экраны
1. **Home**: вкладки Discover · Home · Projects (свайп).
   - Home: Blank / From image / Pattern / Challenge / **Animation** / **Manuscript** + Recent.
   - Projects: галерея с папками, контекстное меню, шаблоны, корзина, мультивыбор.
2. **New Project creator**: имя, ориентация, бумага, размер (px/mm/cm/in + dpi), пресеты, лимит слоёв, тип.
3. **Studio**: основной экран.
4. **Settings**: полноэкранная панель разделов.
5. **Playback**: таймлапс.

## Studio: постоянные элементы
| Зона | Элемент |
|---|---|
| Top bar слева | Home · Creative Tools · Editing Tools · ⚙ Tool settings (условно) |
| Top bar центр | Custom toolbar (докинг иконок, планшеты) · Selection menu + тоггл (условно) · Reference menu (условно) |
| Top bar справа | Layers toggle · Options menu |
| Справа сверху | **Mini-toolbars** активных инструментов (стек) |
| Main toolbar (плавающий, док к краям или угол) | Paint · Blend · Erase · (Clone) · Size (virtual slider) · Color · Opacity/Flow/Softness (virtual slider) · Recent colors strip |
| Снизу слева | Undo · Redo · History slider |
| Сверху | HUD (значения, кольцо размера) |
| Справа | Layer panel (collapsed / expanded) |
| Плавающие виджеты | Navigator · Color wheel · References · **Floating swatch** · **Timeline** (анимация) |

## Модальные workspaces (свой тулбар внизу или сверху, Cancel/Confirm)
Selection · Fill · Transform · Adjustments · Filters · Liquify · Text · Panels/Frame divider · Clipboard · Crop/Resize/Straighten · Pattern tools · **Vector edit** · **Smart filters**.

## Меню
- **Creative Tools**:
  - Tools: Fill, Text, Clone, **Vector**, **Special pens**;
  - Guides: Lines, Ellipse, Hatching, Lazy, Pen, **Radial**, **Array**, **Perspective array**;
  - Shapes: Line, Rect, **Rounded rect**, Circle, **Polygon**, Path, Arc, **Arrow**, **Balloon**;
  - Symmetry;
  - Perspective;
  - View (+ **Grid overlay**, **Pixel view**).
- **Editing Tools**:
  - Selection;
  - Transform;
  - Adjustments;
  - Curves;
  - **Levels**;
  - Filters;
  - Liquify;
  - Patterns;
  - **Materials**;
  - Canvas (Crop, Resize, Straighten, Panels, **Canvas size**, **Color mode**, **Manuscript settings**).
- **Options**: New · Open · Save · Import · Record · Export · Community · Classroom · Settings · **Animation settings** (в анимационном проекте).

## Жесты (базовые IP + ibis)
- 1 палец/стилус: инструмент. 2 пальца: pan, zoom, rotate. Быстрый pinch: fit.
- 2-тап undo, 3-тап redo, 4-тап immersive.
- 3 пальца вертикально: size, горизонтально: opacity.
- Long-press: назначаемое (дефолт пипетка, лупа, задержка).
- Touch-Alt (палец при стилусе): модификатор.
- **Cancel stroke**: держа мазок, тап вторым пальцем.
- Двойной тап: назначаемое.

## Принципы контролов (дизайн-система)
Linear / circular / virtual / dynamic sliders, selector list, curve graph, toggle (icon-toggle), mini-toolbar, tool settings menu. Описание в features/25-input-stylus-settings/infinite-painter.md.

## Открытые UI-вопросы
- Где жить анимационному таймлайну: нижний док-виджет (предлагается) или отдельный workspace.
- Vector tool: отдельный workspace (как Transform) или режим кисти на векторном слое.
- Desktop-раскладка (мышь, клавиатура): у IP только Android/iPad. Берём шорткаты обеих программ + Space/Z/R.
