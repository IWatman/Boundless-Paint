# UI-компоненты (дизайн-система)

Структура и поведение по модели IP (features/25-input-stylus-settings/infinite-painter.md). Визуал свой.

## Тема
- Dark (дефолт) и Light. Material 3 токены, свой акцент **#4F8CFF** (не красный как в IP).
- Активный элемент: акцентный цвет + заливка 16%.
- Скругления: 12 dp панели, 8 dp кнопки. Иконки 24 dp, тап-зона ≥ 44 dp.
- Шрифт UI: системный (Roboto).

## Компоненты
| Компонент | Поведение |
|---|---|
| IconButton | tap. Опционально long-press (вторичное действие, подсказка-тултип) |
| IconToggle | active = акцент. Работает и в custom toolbar |
| LinearSlider | label, трек, value + единицы. Drag с пузырём значения. Тап по краю = ±шаг. Тап по числу = ввод |
| CircularSlider | дуга + число, drag в любом направлении. Тап = попап с LinearSlider |
| VirtualSlider | невидимый: drag от кнопки вверх/вниз (или влево/вправо для горизонтального тулбара), HUD показывает значение |
| JogSlider | ручка возвращается в центр, скорость изменения ∝ отклонению (размер текста) |
| SelectorList | dropdown |
| CurveEditor | XY-график, узлы (tap add, drag, drag onto neighbor = delete), пресеты, invert |
| MiniToolbar | справа сверху, стек. Первая иконка = инструмент (тап = закрыть) + тогглы |
| ToolSettingsMenu | шестерёнка в top bar: секции всех активных инструментов |
| BottomWorkspaceBar | для модальных workspace: Cancel · инструменты · Confirm |
| Popup panel | якорится к кнопке, закрывается тапом вне |
| FloatingWidget | drag, pinch scale, двойной тап = flip (для navigator/ref), закрытие перетаскиванием на ✖ в центре |
| HUD | верх-центр: значение + кольцо размера кисти в масштабе холста |
| Toast | undo/redo-сообщения (опционально) |

## Main toolbar
- Вертикальный, дефолт у левого края.
- Порядок: Paint · Blend · Erase · [Clone] · ─ · Size · Color · Opacity.
- Перетаскивание двумя пальцами, снап к краям. Компактный режим в нижнем углу (M5).
