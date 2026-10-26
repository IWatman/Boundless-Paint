# Infinite Painter: настройки, ввод, стилус, клавиатура, UI-контролы

Источник: User Guide /painter/technical/*.

## Application settings
1. **Account**: имя, email, Log out.
2. **Interface**:
   - тема Dark/Light;
   - стиль color wheel;
   - **Scale of interface**;
   - Hide navigation bar;
   - Split Tools menu;
   - Recent Colors strip.
3. **Language**: 14 языков (EN, DE, ES, FR, ID, IT, JA, KO, NL, PL, pt-BR, RU, zh-CN, zh-TW). Дефолт системный.
4. **Workflow**: Rotate canvas (вкл/выкл поворот двумя пальцами), Show layer changes (слой мигает при выборе).
5. **Rendering**:
   - **Low Latency Mode** (Disabled / Active / Fastest);
   - Color mode (proofing, дефолт Standard);
   - Gamma correction;
   - **Deep color 64-bit** (дефолт on).
6. **Saving**: Save changes prompt, History 1–10 / Unlimited / off (**дефолт 6**).
7. **New project**: Timelapse on/off, Video quality 720p…4K.
8. **Gestures**: Double tap function, Long press function (назначаемые: пипетка, flip, fit и т.д.).
9. **Hardware**: Volume keys → функция (например, листать слои), Disable Back.
10. **Stylus** (появляется после обнаружения pressure-стилуса):
    - функция Finger (Eyedropper, Blend, Erase, move canvas);
    - front button;
    - back button.
11. **Brush settings**:
    - Draw cursor (контур головы);
    - Shape detection;
    - глобальные Pressure / Velocity / Tilt (тап = отключить для всех кистей);
    - глобальные калибровочные кривые.
12. **About**.

## Стилус
- Capacitive: без давления. Active: давление и наклон.
- Первый же контакт активного стилуса разделяет роли: стилус рисует, палец навигирует.
- S Pen работает из коробки, боковая кнопка назначается.
- Калибровка: кривые давления и наклона глобально.
- Touch-Alt (палец + стилус) см. 02.

## Клавиатура
| Действие | Клавиша |
|---|---|
| New / Open / Save | N / Ctrl+O / Ctrl+S |
| Paint / Blend / Erase / Eyedropper | B / K / E / I |
| Size −/+ | [ / ] |
| Layers | F7 |
| Tools menu / Brush editor / Color | T / Shift+Ctrl+B / C |
| Transform / Solid fill / Gradient / Path | V / F / G / P |
| Color adjustments / Curves | Ctrl+B / Ctrl+U |
| Undo / Redo | Ctrl+Z / Shift+Ctrl+Z |
| Flip / Fit | H / Shift+Ctrl+O |
| Lock transparency / Selection on-off | / / \ |
| Lasso / Rect / Brush sel / Wand | L / M / Q / W |
| Invert sel / Clear sel | Shift+Ctrl+I / Ctrl+D |
| Шпаргалка | удерживать Ctrl |

## Типы UI-контролов (дизайн-система IP, повторить в нашем UI)
- **Button**: tap, иногда long-press, иногда жест (виртуальный слайдер).
- **Linear slider**:
  - имя, трек, ручка, зоны −/+ на концах, значение с единицами (%, °, px);
  - drag + пузырь со значением;
  - тап по краю = шаг (1% для 0–100, около 3–4° для углов);
  - тап по числу = ввод с клавиатуры (в некоторых).
- **Circular slider** (в тулбарах): дуга + число в центре. Drag влево-вправо или вниз-вверх. Тап открывает линейный попап.
- **Virtual slider**: скрытый (Size и Opacity на main toolbar).
- **Dynamic slider** (размер текста): возвращается в центр, относительное изменение.
- **Selector list** (dropdown).
- **Curve graph** (XY, узлы).
- **Toggle**: switch или icon-toggle (активный = красный). На custom toolbar показывает состояние.
- **Mini-toolbar**: справа сверху, для инструментов без своего тулбара. Стекуются. Главная иконка = закрыть.
- **Tool settings menu** (шестерёнка слева в top bar): собирает настройки всех активных инструментов.

## Технические требования (v7.2 Android)
- Android 9+, рекомендуется 6 GB RAM, pressure-стилус.
- Проект целиком в RAM: лимиты холста и слоёв зависят от памяти.
- PNTR = ZIP-архив файлов проекта, кросс-платформенный.

## Пресеты холста (полный список)
- Photo @300dpi: 3R 1500×1050, 4R 1800×1200, 4D 1800×1350, 5R 2100×1500, 6R 2400×1800, 8R 3000×2400, S8R 3600×2400, 10R 3600×3000, S10R 4500×3000, 11R 4200×3300, S11R 5100×3300, 12R 4500×3600, S12R 5400×3600.
- ISO:
  - A2 5613×3969 и A3 3969×2806 (240 dpi);
  - A4 3508×2480, A5 2480×1748, A6 1748×1240, A7 1240×874, A8 874×614, A9 614×437, A10 437×307 (300 dpi).
- US @300: Letter 3300×2550, Legal 4200×2550, Tabloid 5100×3300, Poster 7200×5400.
- Other @600: Business card 2079×1299, Postcard 3600×2400.
- Social: 2160² Square, Stories 1080×1920, Banner 2560×1440.
- Video: 720p, 1080p, Film 2K 2048×1556, 4K 4096×2160.
