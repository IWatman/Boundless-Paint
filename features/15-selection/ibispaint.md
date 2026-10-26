# ibisPaint: выделения

Источник: ibispaint.com/lecture №9, 24, 52, 162, 169.

- Инструменты: **Lasso**, **Magic Wand** (настройки как у Bucket: strength, expansion, gap, reference layer, boundary), **Selection Layer** (рисовать выделение кистью и ластиком), **Color Range**, Select Opacity (по альфе слоя).
- Режимы: **Set** (заменить), **Add**, **Subtract**.
- Команды: Invert Selection, Clear.
- Selection Area window:
  - **Expand/Contract** (слайдер);
  - **Cut**;
  - **Copy** (всех слоёв, merged);
  - **Paste**;
  - Remove Selection Area.
- **Color Range**:
  - до **5 reference points** (перемещаемые);
  - **Fuzziness**;
  - **Range**;
  - Show Selection.
- Отображение: пунктир + голубая заливка на Selection Layer.
- Transform выделенного: drag, pinch, Rotate ON.
- Settings: «Save Selection Area» (выделение сохраняется после выхода в галерею).

## Что добавить к IP
- Режим Set (явная замена) в дополнение к Add/Subtract. Intersect есть в boolean IP.
- Color Range с несколькими эталонными точками + Fuzziness/Range (у IP один тап + tolerance).
- Copy/Cut/Paste в системный буфер (у IP постоянная библиотека Clipboard, обычного copy-paste нет). **Нужны оба.**
- Сохранение выделения между сессиями.
