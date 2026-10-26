# Infinite Painter: симметрия

Источник: User Guide /painter/symmetry/*.

## Общее
- 4 типа: Vertical, Horizontal, Radial, Kaleidoscopic.
- Работает в реальном времени для всех режимов кисти (Paint, Blend, Erase, Clone), а также для Guides, Shapes и Liquify.
- Работает в Pattern projects.
- Закрытие: иконка в mini-toolbar. Настройки: шестерёнка Tool settings.
- **Lock** (кнопка на холсте): фиксирует ось или центр и прячет ручки. Разблокировка через Tool settings.

## Vertical / Horizontal (single-axis)
- Это один инструмент с двумя пресетами. Угол оси любой.
- Ручки: rotate, move.
- Settings:
  - Lock axis;
  - **Crop on axis** (мазок обрывается на оси);
  - Reset (ось в центр холста).

## Radial
- Поворотные копии вокруг центра, не зеркальные.
- **Planes 2–16**.
- **Fan angle**: сектор, дефолт 360°. Сектор ориентирован от точки начала каждого мазка.
- Settings: Lock axes, Reset.

## Kaleidoscopic
- Зеркала по N осям через центр.
- **Planes 2–16**. Ручка поворота набора осей со snap 0/45/90°.
- Settings:
  - Lock;
  - **Crop on axes**;
  - Reset center.
