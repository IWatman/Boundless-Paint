# ibisPaint: параметры кисти и стабилизатор

Источник: ibispaint.com/lecture (№118 «Details of Brush Parameters», №50 «Stabilizer», №200, №201). Диапазоны в доке не даны.

## Вкладки Brush Window
- Basic.
- Fade.
- Shape.
- Jitter.
- Type (только у Brush).
- Dynamic.
- Settings.
- Texture (у Eraser, Smudge, Blur).

## Basic / Fade
- **Thickness**, **Opacity**.
- Thickness of Start / End (доля от Thickness). Opacity of Start / End.
- **Force Fade Out**: усиленный спад на конце мазка.
- Blur по длине мазка:
  - **Blurring Shape** (форма размытия, off = выкл);
  - Blurring Degree;
  - Blurring of Start / Middle / End;
  - **Jitter Blurring**.

## Shape
- **Brush Pattern** (форма головы).
- **Spacing** (доля от Thickness).
- **Pattern Opacity**.
- **Opacity Saturation**: в пределах мазка не превышать Opacity. Off = темнеет с каждым проходом.
- **Decrease Opacity**.
- **Initial Angle**, **Following Rotation** (по направлению мазка).
- **Aspects** (сплющивание головы).
- **Antialias**.
- **Separate Each Stroke**: off = перекрывающиеся мазки сливаются.
- Доп. опции:
  - **Dots** (рисовать дискретными точками);
  - **Add Opacity** (Add-смешение штампов);
  - **Constant Opacity**;
  - Spacing for <3 px;
  - **MSAA** (качественный край);
  - **Bind Center on Elliptical Ruler** (голова смотрит в центр эллипса);
  - **Billboard Display on Array Ruler** → Fixed Thickness, Array in Perspective (кисти-цепочки и кружево корректно в перспективе);
  - Max Spacing for 30 px+ (убрать швы).

## Jitter
- Jitter Position, Thickness, Opacity, Spacing, Angle.
- **Scatter**:
  - Absolute Particle Size (px) / Particle Size;
  - Particle Density;
  - Particle Deviation (<0 наружу, >0 к центру);
  - Angle Based on Center.

## Type (тип кисти)
- **Mono**: белизна паттерна = альфа, цвет из текущего.
- **Water**: смешение с нижним.
  - Color Mix;
  - Wetness (смешение альфы, светлее);
  - Dragging (протяжка цвета).
- **Double**: контур/тень вокруг мазка (две краски).
  - Select Shadow Color;
  - Body Color;
  - Absolute Shadow Size / Shadow Size;
  - Shadow Angle;
  - Shadow Distance;
  - Apply Texture Shadow.
- **Color**: цветной паттерн-штамп. Формула: `Final = Pattern + Selected − Base`, hue сдвигается на разницу base↔selected, S/B не ярче оригинала.

## Texture
- Texture Pattern.
- Opacity.
- **Lower Limit**: 0% = текстуру нельзя закрасить повторным проходом, выше = можно.
- Absolute Texture Size (от оригинала или от холста).
- Scale, Angle.
- **Moving**: текстура фиксирована на холсте или едет с мазком (размытие).
- Texture Antialiasing (выкл для пиксель-арта).
- Invert.

## Dynamic
- Speed → Thickness / Opacity / Blurring.
- Pressure → Thickness / Opacity / Blurring.
- Слайдеры, не кривые.
- Глобальная кривая давления в Settings (график с узлами), Weak Sensitivity.

## Settings
- **Min / Max Thickness** (границы слайдера).
- **Blending Mode** кисти.
- Rename.
- Reset (basic → заводские, custom → состояние при добавлении).

## Stabilizer
- Set for Current Brush (per-brush или общий).
- **Method**:
  - **Real Time**: Constant (сила не зависит от скорости) + Fast Strokes (растёт со скоростью), можно сочетать. Есть Use Legacy Stabilization;
  - **After**: сглаживание всей линии после отпускания, слайдер силы.
- **Fade (force taper)**:
  - Force Fade;
  - Start Length;
  - End Length;
  - Fade Shape: Smooth (скруглённый кончик) / Linear (острый).
  - Per-brush: Custom Force Fade, Custom Stabilizer Settings.

## Дополнительные инструменты-кисти (в Tool Select)
- Eraser (своя кисть + Texture tab).
- **Smudge** (палец).
- **Blur**.
- Special Pen: Liquify Pen, Lasso Fill, Lasso Eraser, Copy Pen (см. 19/09/20).

## Свои паттерны кисти (Brush Pattern editor)
- My Gallery → New → Brush Pattern. Нужен логин.
- Template-кисть → тип (Mono / Color / Texture Pattern / Blurring Shape) → размер → редактор = обычный холст со слоями → Test Drawing → автозагрузка на сервер → регистрация custom brush.
- В Color-паттерне задаётся **Base Color**.
- Паттерны появляются в категории «Original» в Shape, Type и Fade табах. Работают для Eraser, Smudge, Blur.
