# ibisPaint: UI, жесты, клавиатура

Источник: ibispaint.com/lecture №04, 81, 151, 167, 197, 199.
UI ibisPaint НЕ переносим, интерфейс будет как у IP. Здесь только функции, которых в IP нет.

## Структура (справочно)
- Main toolbar: Brush/Eraser, Tool Select, Properties, Color, Full Screen, Layer, Back.
- Tool Select (15):
  - Transform;
  - Magic Wand;
  - Lasso;
  - Filter;
  - Brush;
  - Eraser;
  - Smudge;
  - Blur;
  - Special Pen;
  - Bucket;
  - Vector;
  - Text;
  - Frame Divider;
  - Eyedropper;
  - Canvas.
- Top-right: View Menu, Selection Area tool, Stabilizer, Ruler, Material.
- Tool Select: позиция Left / Right / Popup (1–3 колонки), подписи вкл/выкл.
- Floating Layer Window (планшет/ПК): перемещаемое, ресайзимое окно слоёв.
- Reference Window (см. 14).

## Жесты (новые относительно IP)
- **Cancel drawing**: держа рисующий палец, тапнуть вторым. Мазок отменяется (у IP нет).
- Quick Eyedropper: long-press, лупа, задержка настраивается. Отмена вторым пальцем.
- 2 пальца тап = undo, 3 = redo (как IP).
- Мышь/ПК:
  - Space+drag = pan;
  - Z+drag или Alt+Space = zoom;
  - R+drag или Shift+Space = rotate;
  - колесо = zoom, Shift+колесо = rotate (инверсия направления в настройках);
  - ПКМ = quick eyedropper;
  - **Alt+Ctrl+drag = размер кисти**.
- Trackpad: pan или zoom по настройке.
- Shift+/ = список шорткатов. `[` `]` = размер. Раскладка ANSI/JIS.
- **Show Message on Undo** (тост), **Sound** (звук undo).
- **Crosshair** у кончика пера при hover. Курсор кисти: None / Thickness mark / Brush pattern.
- **Palm rejection** (см. 25).

## Что взять в IP-UI
- Cancel-drawing жест.
- Floating layer window на планшетах (у IP панель слоёв уже плавающая, можно дать опцию открепления).
- Курсор-паттерн кисти и crosshair при hover.
- Alt+Ctrl+drag размер на десктопе.
