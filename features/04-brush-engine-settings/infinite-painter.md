# Infinite Painter: Brush Editor (параметры движка кисти)

Источник: User Guide /painter/brushes/settings/*. Больше 100 параметров. Где в доке нет диапазона, стоит «—».

## Каркас редактора
- 5 вкладок: **Stroke**, **Head**, **Texture**, **Paint**, **Special**.
- Кнопки:
  - Cancel;
  - Share (`.prbr`);
  - Save as… (копия);
  - Confirm.
- Live preview. Не показывает цветовой jitter и блендинг.
- Shortcut: Shift+Ctrl+B.

### Dynamic curve (общий виджет)
- Output (параметр) ← Input (источник). Тап по заголовку = вкл/выкл.
- График X=input, Y=output. Invert (зеркально по X). Пресеты формы, крайний левый = линейный (дефолт).
- Узлы: тап по кривой добавляет, drag двигает, drag на соседа удаляет.
- Источники:
  - **Pressure**: без поддержки = 100%, кривая игнорируется;
  - **Velocity**: работает и пальцем;
  - **Tilt**: 0 = вертикально, горизонт = 100%.
- Глобальная калибровка Pressure / Velocity / Tilt (Settings) накладывается на кривые кистей.

## Stroke
| Параметр | Смысл | Диапазон |
|---|---|---|
| Size | диаметр головы, px | max зависит от холста × Size range |
| Opacity | максимальная непрозрачность мазка | 0–100% |
| Smoothness | сглаживание траектории (стабилизатор) | — |
| Adhesion | сколько «материала» копится, прежде чем лечь. Высокое = контрастные «комковатые» мазки | — |
| Size range | множитель Size, задаёт максимум для динамики | 0.0–4.0 |
| Wet edges | края мазка плотнее середины | — |
| Glaze strokes | >0: перекрытие в пределах одного мазка не накапливается (только при opacity<100% и на прозрачном) | — |
| Blending mode | режим наложения цвета кисти, 30 шт. = layer modes, у каждого **Strength** (сила отличия от Normal) | дефолт Normal |
- Dynamics (размер): кривые Pressure, Velocity, Tilt + **Tilt Offset** (направление роста размера относительно кончика; + как карандаш, − как аэрограф).
- Jitter: **Size jitter**.
- Профили по длине мазка: **Size profile**, **Flow profile**.
  - X = длина мазка (не 0–100%), сетка 3×3: начало (фикс. длина), середина (растягивается), конец (фикс. длина);
  - на коротком мазке секции примерно равны. Invert и пресеты есть.
- Fill section (только fill-кисти): **Draw preview** (глобально). Вкл = заливка видна в реальном времени, выкл = после завершения мазка.

## Head
- Source:
  - bitmap (тап = выбор, + = импорт);
  - Remove;
  - **Color** (использовать RGBA битмапа; игнорируется в Blend, Erase, Clone);
  - **Invert**;
  - **Tonal curve** (кривая яркости источника);
  - **Colorize** (сколько цвета брать из битмапа, только при Color).
- Head:
  - **Application style**: как штампы взаимодействуют вдоль мазка (опции в доке не перечислены);
  - **Flow**: скорость подачи пигмента, есть на тулбаре, динамика;
  - **Spacing**: 0–200% ширины головы;
  - **Structure**: имитация импасто-рельефа;
  - **Softness**: 0 = жёсткий край;
  - **Depth**: прозрачность головы как height-map (щетина), при 100% может не класть цвет;
  - **Angle**: 0–360°, дефолт 0;
  - **Rotation**: −100 (Reverse) … 0 (Fixed) … +100 (Tangent, по направлению);
  - **Stylus rotation**: угол от наклона или азимута стилуса. Пальцем угол = начальное направление мазка;
  - **Orient to screen**: начальный угол относительно экрана, а не холста.
- Dynamics: **Flow** curves, **Scatter** curves (Pressure, Velocity, Tilt).
- Jitter:
  - Initial: **Angle**;
  - Continuous: **Scatter**, **Angle**, **Flow**.

## Texture
- Source: то же, что у Head (bitmap, Remove, Color, Invert, Colorize, Tonal curve).
- **Style**:
  - Fixed: тайлы встык по холсту;
  - Rotate: тайлы по направлению мазка;
  - Warp: по мазку с деформацией.
- **Depth**: насколько текстура ограничивает нанесение. 0 = почти не видна, высокое = только светлые участки. Есть динамика.
- **Scale**: 0–2.0, дефолт 1.
- **Stretch**: 1×–50× вдоль мазка.
- **Structure**: импасто только текстуры.
- **Softness**: гауссово размытие текстуры.
- **Angle**: поворот текстуры относительно головы.
- **Scale with size**: масштаб текстуры следует за динамическим размером.
- Dynamics:
  - Depth curves;
  - **Gradation**: спад эффекта от кончика к хвосту при наклоне. 0 = равномерно.
- Jitter:
  - Initial: Position;
  - Continuous: Position, Scale, Angle.

## Paint
- Mix:
  - **Mix-in**: подхват нижних цветов с заменой цвета кисти (кисть «теряет краску»);
  - **Dilution**: подхват без замены, 100% = чистый блендер (водянисто, восково);
  - **Wet paint**: при Mix-in/Dilution переносится и прозрачность, иначе альфа на месте.
- Blend:
  - **Blur**: усреднение подхваченного цвета;
  - **Pull**: длина смаза. 0 = быстро меняется, 100 = очень медленно, грязнит;
  - **Flow**: множитель только для блендинга;
  - **Sample lower layers**.
- Dynamics: 3 кривые **Dilution** (pressure/velocity/tilt). Позволяют переключаться между «красить» и «смешивать» давлением или наклоном.
- Color jitter (HSB):
  - Initial: Hue, Saturation, Brightness на старте мазка;
  - Continuous: Hue, Saturation, Brightness по точкам.

## Special
- **Watercolor** (switch):
  - Bleed out (насколько растекается);
  - Glaze («количество воды», не путать с Glaze strokes);
  - Mix-in;
  - Dryout (скорость затухания растекания).
- **Screentone** (switch):
  - Spacing (шаг сетки snap, px);
  - Angle 0–180°;
  - Scale with size.
- **Particles** (только у particle-кистей, без тоггла):
  - Attractors 1–64;
  - Particles 1–400;
  - Radius (отступ аттракторов от пути);
  - Particle Size 0.00–0.10 от размера кисти;
  - Overshoot 0.00–2.00 (вылет за аттрактор, брызги на поворотах);
  - Particle head = любой битмап;
  - Size кисти = диаметр всей системы;
  - случайность берётся из Continuous Scatter вкладки Head.
- **Filters / Color adjustments в кисти**:
  - селектор («NO EFFECT»);
  - target **Layer** (Color + Filters применяются к активному слою под кистью) или **Brush** (Filters применяются к самому мазку), только один;
  - **Strength** −100…+100, 0 обычно нейтрально, дублируется круговым слайдером на тулбаре вместо цвета;
  - Remove.
- Эффекты применяются после Confirm. Некоторые несовместимы между собой и с Harmony/Fill.
- Из списка библиотеки видны ещё эффекты [harmony] и 3D (Twisty/Tube/Ribbon 3D). Их параметры в доке не описаны, это реверс по поведению.

## Вывод для реализации
Модель кисти = stamp-based движок:
- голова: битмап + tonal curve + softness + depth + structure;
- spacing, scatter, rotation;
- текстура: 3 стиля маппинга;
- smudge-модель (mix-in, dilution, pull, blur, wet);
- кривые dynamics (pressure, velocity, tilt) на size, flow, scatter, depth, dilution;
- профили по длине мазка;
- HSB-jitter;
- blend mode кисти + strength;
- спецрежимы: watercolor-симуляция, screentone-snap, particles, harmony, fill, brush-as-filter.
