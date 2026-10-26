# Infinite Painter: кисти (библиотека, режимы, управление)

Источник: User Guide /painter/brushes/*. Параметры движка лежат в `04-brush-engine-settings/`.

## Понятие кисти
- Кисть: любой инструмент, который в реальном времени следует за стилусом или пальцем. Это и перо, и мастихин.
- Кисть может красить, смешивать, стирать или копировать пиксели (clone).
- Анатомия:
  - head bitmap (или дефолтный круглый);
  - texture (опционально);
  - параметры и эффекты (>100);
  - результат пишется только в активный слой;
  - текстура бумаги (Paper layer) влияет на все мазки независимо от текстуры кисти.

## Режимы кисти (на main toolbar)
- **Paint** (B): кладёт цвет. Спецэффекты работают только здесь. Чистые блендеры (Colorless Blender, Palette Knife) цвет не кладут.
- **Blend** (K): размазывает цвета активного слоя. Спецэффекты игнорируются. Вместо цвета кнопка-шестерёнка, она открывает:
  - Blur (0 = перенос отдельных пикселей);
  - Pull (длина мазка-смаза);
  - Flow (множитель к Flow головы только при блендинге);
  - Sample lower layers: глобально для всех Blend-кистей. Сэмплит активный и нижние видимые слои, пишет только в активный.
- **Erase** (E): стирает в прозрачность. Работает любая кисть, эффекты игнорируются.
- **Clone**: квазикисть (см. 20-cloning).
- Для каждого режима выбирается своя кисть. Любая кисть доступна в любом режиме.
- Пальцу можно назначить другой режим, чем стилусу (Settings).
- Очистить слой целиком: Clear в опциях слоя или long-press Undo.

## Brush selector
- Открывается повторным тапом по иконке режима.
- Части окна:
  - текущая папка;
  - превью текущей кисти + кнопки управления;
  - список папок (скролл);
  - кисти папки (тап = выбрать для текущего режима).
- Спецпапки:
  - **Recent**: кисти этого проекта, автоматически;
  - **Favorites**: отдельные для каждого режима, сердечко-тоггл, при первом запуске предзаполнены;
  - **Created**: новые кисти;
  - **Downloads**: скачанные или импортированные;
  - Custom: без лимита, иконка = первые 2 символа имени или эмодзи в начале имени;
  - Built-in: со своими иконками.
- Кнопки:
  - Add (создать или импортировать);
  - Edit (Brush Editor);
  - Options;
  - Favorite;
  - Reset (заводские настройки).

## Brush options menu
- Rename: можно вернуть оригинальное имя у встроенных.
- Duplicate: копия помнит родителя.
- Share: экспорт `.prbr`.
- Reset.
- Remove: убрать из папки. Custom-кисть возвращается в Created/Downloads, встроенная в исходную папку.
- Delete: навсегда, для custom.
- Hide: для встроенных, удалить их нельзя.

## Папки
- Создание, Rename (и встроенных тоже), Share (вся папка → пак `.przp`, имя папки = имя пака), Hide (кнопка «показать скрытые»), Delete (кисти возвращаются в исходные контейнеры).
- Удалённые встроенные кисти восстанавливаются по папке: long-press → Restore.
- Имена папок: любой Unicode.
- Перенос кисти: long-press + drag на папку.
- Импортированный `.przp` = новая папка.

## Быстрые настройки
- Size: виртуальный слайдер (drag от кнопки), клавиши `[` `]`, HUD.
- Size popup:
  - live preview;
  - Size;
  - Angle головы;
  - Color blending mode кисти;
  - кнопка Brush Editor.
- Opacity popup:
  - Opacity (на весь мазок);
  - Flow (на каждый штамп);
  - Softness (размытие края головы).
  - Тап по имени параметра назначает его на виртуальный слайдер и жест 3 пальца по горизонтали.
- Изменения пишутся в определение кисти сразу при закрытии попапа. Undo нет, есть Reset.

## Создание кисти
- Пути:
  - Add brush → Brush Creator;
  - скачать из Community;
  - импорт стороннего пака;
  - из выделения (fragment → голова кисти).
- **Brush Creator**:
  - имя;
  - тип-пресет (общий шаблон);
  - превью мазка;
  - head bitmap (пусто = круг);
  - texture (опционально);
  - Confirm → папка Created;
  - дальше доводка в Editor.
- Селектор head/texture:
  - встроенные пресеты + секция Imported;
  - импорт из Gallery, Camera, Files, Pixabay search, Clipboard;
  - удаление импортированного: long-press → Delete. Предупреждение: битмап может использоваться несколькими кистями.
- Семантика битмапа: яркость = покрытие, белый красит, чёрный прозрачен. Тоггл Invert.
  - Встроенные головы хранятся black-on-transparent и инвертируются по умолчанию.
  - Принимаются: white/black, black/white, white/transparent, black/transparent, full-color (± alpha).

## Community и сторонние кисти
- Community: бесплатно, простой список без категорий и поиска, сорт по дате, мультивыбор, уже скачанные подсвечены.
- Сторонние паки: `.przp` (набор) и `.prbr` (одна кисть). Импорт через Files. Пак не распаковывать, иначе повреждение.
- Совместимости с ABR (Photoshop) и Procreate нет.

## Процедурные кисти
- **Fill brushes**: без головы, принимают фильтры и цветокоррекции. Screentone и particles не работают, watercolor ограничен. См. 09-fill.
- **Harmony brushes**: алгоритмические. Рисуют паттерн тонких линий по траектории, плотность зависит от длины и формы мазка. Головы нет, Size = толщина линий.
  - Список: Sketchy, Shaded, LongFur, ShortFur, Ribbons, Curvy, Grid, Circles, Squares.

## Встроенная библиотека (v7, ~168–172 кистей, 16 категорий)
В [скобках] спецэффект кисти.
- **Pencils (14)**: 2B, HB, 6B, Proko, Technical, Coarse, Concept, Waxy, Old Pencil; Cloth/Fiber Shader, Hatching, Cross Shader [screentone], Scratch Shader.
- **Pens (20)**: Solid, Soft, Fine Tip, Manga Inker, Tapered Inker, Old/Coarse/Dry Inker, Leaky, Pilot [particle], Gel, Velocity Pen [particle], Fountain, Fineliner, Signature, Slanted Inker, Torn Ink, Ink Wash [watercolor], Velocity Inker [watercolor], Pixel Pen.
- **Calligraphy (8)**: Monoline, Oval Lettering, Soft Taper, Floral Pistil, Chalk Signage, Split Nib, Raised Edge, Right Slant.
- **Markers (10)**: Dry Ink, Chisel Tip, Broad, Glazed, Wet, Colorless Blender, Sketch, Round, Brush Marker, Highlighter.
- **Paint (26)**: oil, acrylic, gouache, rollers, knives (Paint Stroke, Thick Angular, Clay Roller, Dry Brush, Filbert, Flat Oil, Gouache, Palette Knife, Loaded Knife, Thick Rake, Stucco Roller, Velocity Splatter [particle] и др.).
- **Watercolor (9)**: Wet Paper, Light/Heavy Wash, Heavy Bleed, Mostly Water, Chemical Burn, Salt Blossom, Circle Stains, Floral Stencil. Почти все [watercolor].
- **Sprayers (12)**: airbrush soft/medium/hard, Spray Paint, Splatter [particle], Blood/Water Splatter, Flecks, Circle Drip, Sand Blaster, Cloudy, Hard Round.
- **Chalks (7)**, **Charcoals (7)**.
- **Design (6)**: пунктиры, точки, стрелки (Cut Out, Long Dashes, Doodle Dots, Flow Arrow, Arrows, Arrow Chain).
- **Fills (6)**: Solid, Screentone, Debris, Grunge, Scratch, Checker.
- **Glow (10)**: Light Pen, Neon, Sparkler [particle], Soft Glow, Bokeh, Scene Glow, Combustion, Flame Ribbon, Light Hairs, Stardust [particle].
- **Misc (13)**: 3D-кисти Twisty/Tube/Ribbon 3D, Newspaper Transfer, губки, Smoke, Outliner, штампы Roses и Stars.
- **Screentone (7)**: Dash 45, Denimite, Dot 45, Halftone, Tone Shader, V/H Lines.
- **Textures (8)**: Bricks, Wood, Pebbles, Isometric/Standard Grid и др.
- **Harmony (9)**: список выше.

Примечание для clean-room: имена встроенных кистей не переносить, делать свою библиотеку по категориям.
