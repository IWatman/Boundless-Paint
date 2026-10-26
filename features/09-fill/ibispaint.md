# ibisPaint: заливки

Источник: ibispaint.com/lecture №12, 13, 82, 139, 196, 72–74 (gradation filters).

## Bucket tool (окно по кнопке «%»)
- **Strength**: допуск похожести к стартовой точке (0% = точное совпадение, 100% = весь слой).
- **Unpainted Recognition**: аккуратная заливка внутри линий неравной толщины.
- **Expansion** (дефолт **1.5 px**): расширение/сужение области. Отрицательное значение для баллонов. Закрывает белые щели у антиалиаса.
- **Gap Recognition**: закрывает мелкие разрывы в линиях.
- **Continuous Filling**: свайп по холсту заливает все пересечённые области.
- **Under Line**: заливка под линией (цвет заходит под лайнарт).
- **Boundary**: Color (RGBA) / Opacity.
- **Reference Layer**: Canvas (дефолт) / Current Layer / Specific Layer.
- **Easy Settings** (упрощённый режим).
- Reset. Опция «Save Bucket Settings» в Settings.

## Подрежимы Bucket
- **Bucket** (fill).
- **Eraser Bucket**: тап = стереть область (сделать фон прозрачным).
- **Surrounding Fill**: обвести область пальцем → залить все замкнутые области внутри.
- **Surrounding Eraser**: то же, стирание.
- Их опции:
  - **Scrape** (сужение, против перелива на мягких линиях, дефолт 1.0);
  - Boundary;
  - Strength;
  - Gap Recognition;
  - Expansion;
  - **Fill from Underside**;
  - Reference Layer.

## Special Pen
- **Lasso Fill**: обвёл → залито. Opacity.
- **Lasso Eraser**: обвёл → стёрто. Опция **Erase All Layers** (все видимые слои).

## Градиенты
- Отдельного gradient-инструмента нет. Через фильтры Draw: Parallel / Concentric / Radial Line Gradation (см. 18). Плюс airbrush + clipping.

## Что взять в IP-модель
- Expansion (±px).
- Gap Recognition.
- Unpainted Recognition.
- Continuous Filling.
- Under Line / Fill from Underside.
- Boundary: color или opacity.
- Eraser Bucket.
- Surrounding Fill/Eraser.
- Lasso Fill/Eraser (+ all layers).
- В IP уже есть: Source layer = Reference Layer, Live Tolerance, все градиенты, Pattern fill.
