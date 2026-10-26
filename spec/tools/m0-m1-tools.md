# Спеки инструментов M0–M1

## T1. Навигация холста
- 2 пальца: pan + pinch zoom (0.05×–64×, по центру жеста) + rotate (свободно, snap к 0/90/180/270 ±3°).
- Быстрый pinch и отпускание (scale < 0.8 за < 250 мс) = fit. Двойной тап 1 пальцем = fit (настраивается).
- Холст рендерится с mipmap при zoom < 1, nearest при zoom ≥ 4 (опция pixel view, иначе linear).
- Фон вокруг холста: #2B2B2B (dark) / #D0D0D0 (light).
- Приёмка: 60 fps при pan на холсте 4096² с 10 слоями.

## T2. Кисть (Paint)
- Параметры M1: size, opacity, flow, spacing, hardness, pressure → size (кривая linear, min 20%), pressure → flow (выкл), smoothing (EMA), taper start/end, color.
- Головы: процедурная круглая (hardness → профиль `smoothstep(1, h, r)`).
- Stroke buffer + cap opacity.
- Пальцем pressure = 1. Стилусом используются `getPressure()` и история (`getHistoricalX`) для каждой точки.
- Приёмка:
  - прямая линия без зазоров при spacing ≤ 25%;
  - круглые концы;
  - opacity 50% не накапливается внутри мазка;
  - undo убирает мазок целиком.

## T3. Ластик (Erase)
- Та же кисть, композит destination-out. Своя выбранная кисть для режима.

## T4. Blend (smudge, M2) — в M1 кнопка видна, неактивна.

## T5. Undo / Redo
- Кнопки слева снизу. Жесты 2-тап (undo) и 3-тап (redo): касания в пределах 250 мс, без движения > 20 px.
- Long-press undo = очистить слой (с подтверждением-тостом и возможностью undo).
- Лимит 100 шагов.

## T6. Main toolbar
- Paint · Blend · Erase · Size (virtual slider + попап: size, hardness, angle) · Color (тап = Color panel, drag вверх/вниз = яркость, drag наружу = пипетка в M2) · Opacity (virtual slider + попап: opacity, flow, hardness. Выбранный пункт управляет слайдером).
- 3 пальца вертикально = size, горизонтально = opacity.
- HUD с кольцом размера.

## T7. Color panel
- Вкладки:
  - HSB wheel (стиль default: кольцо + квадрат S-B);
  - Sliders (HSB / RGB + hex);
  - Palette (одна стандартная палитра + recent 10).
- Previous/current свотч. Добавление цвета в палитру.

## T8. Layer panel
- Справа, тоггл в top bar. Collapsed (превью) / expanded.
- Add, delete, duplicate, clear, merge down, reorder (long-press drag), visibility, opacity 0–100, blend (12: normal, multiply, screen, overlay, darken, lighten, color dodge, color burn, soft light, hard light, add, difference), alpha lock, clip, rename.
- Paper: цвет, вкл/выкл.
- Лимит слоёв по формуле GAPS.

## T9. Проекты и галерея
- Home: «Новый холст» (размер: экран / пресеты / свой, px), Recent.
- Projects: сетка превью, открыть, rename, duplicate, delete (с подтверждением). Без папок в M1.
- Автосейв при выходе из Studio и при `onStop`. Ручной Save.

## T10. Экспорт
- Options → Export: PNG (прозрачный фон да/нет), JPG (quality). Через `ACTION_CREATE_DOCUMENT` (SAF) или Share.
