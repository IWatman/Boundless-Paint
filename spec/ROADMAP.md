# Дорожная карта (Android)

Каждая веха = рабочая сборка APK + зелёные JVM-тесты.

> M0 и M1 завершены 2026-10-26, детали и долги в spec/STATUS.md. План M2: spec/PLAN-M2.md (там же изменения объёма).

## M0: Каркас
- [ ] Gradle-проект `app/` (Kotlin, Compose, minSdk 29, target 36), локальный GRADLE_USER_HOME.
- [ ] Модули: `:app` (UI), `:core` (чистый Kotlin: модель документа, математика, undo, алгоритмы, тестируемо на JVM), `:render` (GLES 3 рендер). Для простоты на старте `:core` и `:app`, рендер внутри app.
- [ ] Экраны Home / Studio (заглушки), навигация.
- [ ] GL-холст: pan, zoom, rotate двумя пальцами, fit.

## M1: Рисование (MVP ядро)
- [ ] Тайловый растровый слой (CPU float16-буферы + GPU-текстуры).
- [ ] Stamp-кисть: size, opacity, flow, spacing, hardness, pressure → size/flow, стабилизатор.
- [ ] Erase, undo/redo (жесты 2/3 пальца), main toolbar (Paint/Blend/Erase, size, color, opacity virtual sliders).
- [ ] Color panel: HSB wheel + sliders + hex + история.
- [ ] Layer panel: add, delete, reorder, visibility, opacity, blend (12 базовых), alpha lock, clip, merge.
- [ ] Сохранение и загрузка `.pnt`, галерея проектов, экспорт PNG/JPG.

## M2: Паритет с основой IP + полный стоковый набор кистей
- **Полный движок кисти** (весь spec/brush-engine.md, включая watercolor, particles, harmony, double, color stamp, 3D, filter brushes, screentone snap). GPU-штампы.
- **Стоковый каталог ≈236 кистей** (spec/brush-catalog.md), покрытие стока IP и ibis. Brush Editor UI, браузер кистей с папками, избранным и поиском. Clone-кисть. Настройки стилуса (кривая, калибровка, кнопки).
- Пипетка (long-press), selection (lasso, rect, ellipse, wand, brush) + операции.
- Transform (basic, anchor, distort, warp, мультислой), bucket (tolerance, gap, expand, reference layer), градиенты.
- Группы, маски, все 33 blend mode, adjustment layers, коррекции, кривые.
- Guides (lines, ellipse, lazy, pen, hatching), shapes (вкл. правильный многоугольник) + detection, symmetry, perspective grids (1/2/3 точки, изо, 2D).

## M3: Расширение
- Фильтры (P1-набор), filter layers, liquify, text, криволинейная перспектива, panels/frame divider, crop, resize, straighten.
- PSD in/out, таймлапс, настройки, стилус (palm rejection, кнопки), шорткаты.

## M4: Добавки ibis
- Vector layer, линейки radial/array, rounded rect/polygon/arrow/balloon, lasso fill/eraser, surrounding fill.
- Анимация с onion skin, manuscript-холст, screentone-слой, color mode документа, CMYK-экспорт.
- Синхронизация с Google Drive.

## M5: Полировка
- Остальные фильтры, pattern tools, материалы, video mode, локализация, производительность.
