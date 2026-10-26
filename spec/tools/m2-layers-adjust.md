# Спеки M2: слои, маски, режимы наложения, коррекции

Источники: features/06-layers, 07-blend-modes, 17-adjustments; spec/document-model.md, blend-and-color.md, filters.md, file-formats.md, UI-SHELL.md.
Что уже есть (M1): `RasterLayer`, `GroupNode` (passthrough по умолчанию), clip, alpha lock, 33 режима в `BlendMode`/`BlendMath`/шейдере (в UI 12), `Compositor.flatten`, команды в `LayerCommands.kt`, manifest schema 1.
Пометка **решение** = выбрано нами, вопрос закрыт.

## Порядок работ (стадии)
| Стадия | Содержимое | Зависит от |
|---|---|---|
| S1 core | `MaskGrid`, `LayerMask`, `AdjustmentLayer`, новые поля `Node`, команды, Compositor (маски, коррекции, фикс ADD), manifest schema 2, тест-векторы | переход CPU-тайлов на half (можно параллельно: новый код работает через scratch FloatArray) |
| S2 render | R8-текстуры масок, маски групп, фикс passthrough-базы клипа, шейдер коррекций + LUT, parity-тест на эмуляторе | S1 |
| S3 UI панели | группы (drag in/out, свернуть), мультивыбор, миниатюры масок, активная цель, список режимов с превью, свойства, теги, lock, reference | S1 |
| S4 маски-рисование | кисть/ластик/заливка в маску, маска из выделения, слой → выделение | S1, S2, спека Selection (M2) |
| S5 коррекции | workspace Adjustments (деструктивно), редакторы Curves и Levels, adjustment layers в UI | S1, S2; gradient map использует редактор градиента из спеки Fill |

---

## L1. Группы
- Создание:
  - кнопка «Группа» в шапке expanded-панели: новая группа над активным слоем, активный слой внутрь;
  - pinch (сведение двух пальцев) по ≥2 подряд идущим строкам = сгруппировать их (как у IP);
  - мультивыбор → «Сгруппировать».
- Дефолт новой группы: blend **passthrough**, opacity 100%, имя «Группа N», `expanded = true`.
- Вложенность без лимита. Отступ в панели 12 dp на уровень, визуально максимум 5 уровней (глубже отступ не растёт) — **решение**.
- Свернуть/развернуть: тап по шеврону слева от превью. Свёрнутая группа с активным узлом внутри: акцентная точка на иконке папки.
- Режим и opacity группы:
  - passthrough: дети рендерятся инлайн в аккумулятор родителя. Adjustment внутри действует на всё ниже группы. Opacity < 1 = lerp(до, после) (уже реализовано);
  - любой другой режим: offscreen-рендер, затем blend как одного слоя. Adjustment внутри действует только на содержимое группы.
- Клип в группах:
  - группа может быть базой клипа. Альфа базы = альфа изолированного рендера группы, в т. ч. для passthrough (CPU так и делает). **GPU сейчас не клипает к passthrough-базе (`baseIsolated=false`), исправить** — **решение**;
  - группа может быть клипнута (контент группы × альфа базы);
  - клипнутый узел первым в группе (базы нет) не рисуется, в строке иконка-предупреждение.
- Перенос в группу и из неё (drag в панели, long-press + drag уже есть):
  - зона строки обычного слоя: верхняя половина = над, нижняя = под;
  - строка группы: верхние 25% = над группой, средние 50% = **внутрь** (наверх списка детей, свёрнутая группа подсвечивается и раскрывается через 600 мс), нижние 25% = под группой;
  - горизонтальный сдвиг пальца влево > 48 dp во время drag = подняться на уровень выше (выход из группы), вправо > 48 dp под группой = войти в неё последним сверху — **решение** (аналог свайпа ibis);
  - запрет: группу нельзя положить в саму себя или в потомка.
- Действия группы (options): Rename, Blend, Opacity, Hide, Duplicate (глубокая копия), **Copy merged** (новый растровый слой над группой = изолированный композит, группа цела), **Flatten** (группа → один растровый слой), Merge down, Ungroup, Clip, Select (выделение по суммарной альфе), Mask ▸, Delete, Свойства….
- Flatten:
  - результат = изолированный рендер детей; blend/opacity/clip/маска группы переносятся на новый слой; имя группы;
  - для passthrough-группы режим результата normal. Если внутри были adjustment/маскирующие режимы, вид может измениться: тост «Вид мог измениться» — **решение**.
- Ungroup: дети встают на место группы в том же порядке. Opacity/режим группы теряются. Маска группы: если есть, предлагается «Применить к каждому» (маска копируется детям-без-маски, детям с маской — пересечение) или отмена — **решение**.

## L2. Мультивыбор в панели
- Вход: кнопка «Выбор» (иконка чек-листа) в шапке expanded-панели. У строк появляются чекбоксы, тап по строке = переключить. Выход: та же кнопка или Back — **решение** (у IP мультивыбора нет, у ibis есть только в векторе).
- Активный слой (цель кисти) один. Первый отмеченный становится активным.
- Нижняя панель действий выбора: Сгруппировать · Объединить · Дублировать · Удалить · Скрыть/Показать · Clip вкл/выкл · Transform (открывает Transform с этими слоями, спека Transform).
- Drag любого отмеченного = перенос всех отмеченных, их относительный порядок сохраняется; вставка единым блоком в точку drop.
- Объединить выбранные:
  - рендер выбранных узлов в порядке стека (каждый со своим режимом, opacity, маской, клипом к базе, если база тоже выбрана) в новый растровый слой на прозрачном фоне;
  - место и имя = верхний выбранный, режим normal, opacity 100% — **решение**;
  - adjustment в выборке действует только на выбранные ниже него.
- Выбор узла внутри группы и самой группы одновременно: группа поглощает детей (дети снимаются с выбора).

## L3. Маски слоя
### Модель
- Одна растровая маска на узел: raster, group, adjustment. Значение 0..1 (R8): 1 = видно, 0 = скрыто.
- `MaskGrid`: тайлы 256² R8 + `defaultValue` (0 или 255) для отсутствующих тайлов. Маска «всё видно» не хранит ни одного тайла — **решение**.
- Свойства: `enabled` (деф. true), `linked` (деф. true: Transform двигает маску вместе со слоем), `density` 0–100% (деф. 100; эффективное значение `1 − density·(1 − m)`).

### Создание (Layer options → Маска ▸)
| Пункт | Результат |
|---|---|
| Показать всё (деф. при тапе «Маска», если нет выделения) | default = 255, тайлов нет |
| Скрыть всё | default = 0 |
| Из выделения (деф., если выделение активно) | копия маски выделения, default = 0 |
| Из альфы слоя | m = α пикселя (для группы — α изолированного рендера), default = 0 |
- После создания активной целью становится маска.

### Рисование в маске
- Активная цель = маска: все растровые инструменты пишут в маску. Цвет кисти → яркость `Lum(color)` (0.3/0.59/0.11).
- Paint: `m' = m·(1−a) + Lum·a`, где a = покрытие мазка (stroke buffer α × opacity).
- Erase: пишет 0 (скрывает) — **решение**: визуально как ластик, но неразрушающе.
- Fill, градиент, фильтры и коррекции (деструктивные) тоже применяются к маске как к серому изображению.
- Свотч цвета в main toolbar при активной маске показывается в сером. Пипетка берёт серое значение.
- Alpha lock на маску не действует.

### Опции маски (тап по миниатюре маски у активной маски)
- Density (слайдер), Invert, Залить чёрным, Очистить (белым), Вкл/Выкл (выключенная: миниатюра перечёркнута красным крестом), **Показать маску** (холст показывает маску серым вместо композита, до повторного тапа), Apply, Delete, Linked (иконка-цепь между миниатюрами), Select (маска → выделение).
- Apply:
  - raster: `α' = α·m_eff` (premult: все каналы × m_eff), маска удаляется;
  - group: маска применяется к каждому ребёнку (как Ungroup-логика), если в группе есть adjustment/не-растровые узлы — пункт недоступен — **решение**;
  - adjustment: недоступно (маска и есть способ локализовать).
- Delete без применения. Обе команды отменяемы.

### Рендер
- Маска умножает вклад узла: `f = opacity · m_eff · clipAlpha`.
- Raster: в blendPass `src *= f`.
- Isolated group: маска применяется к результату offscreen.
- Passthrough group: `acc = lerp(accДо, accПосле, opacity·m_eff)` (расширение существующего lerp-пути до попиксельного фактора).
- Adjustment: `acc = lerp(acc, adj(acc), f)`.
- Клип: альфа базы для клипнутых = `α_base · m_eff(base)` — **решение** (как в PS).
- GPU:
  - текстура тайла маски `GL_R8`, грузится как у пикселей (dirty tiles). Отсутствующий тайл → uniform `u_maskDefault`, без текстуры;
  - если у базы клипа есть маска и над ней есть клипнутые: база рендерится во временный target (пиксели × маска), он идёт как `u_clip`.
- CPU: `Compositor.compositeInto(..., mask: MaskGrid?, maskDensity)`. Пустой тайл src по-прежнему пропускается.

### Активная цель в UI
- Строка слоя с маской: `[превью слоя][⛓][превью маски]`.
- Активная цель обведена акцентной рамкой 2 dp (свой цвет темы, не красный IP). Неактивная — серая 1 dp.
- Тап по превью маски = цель «маска», по превью слоя = цель «слой».
- HUD при переключении: «Маска: <имя слоя>». В top bar у Layers toggle маленький бейдж «M», пока цель = маска.

## L4. Режимы наложения в UI
- Все 33 режима доступны для raster, group, adjustment (для adjustment режим применяется к `adj(acc)` против `acc`). Passthrough только у групп.
- Выбор: чип режима в Layer options → bottom sheet списка:
  - заголовки групп: Обычные · Затемнение · Осветление · Контраст · Сравнение · Цвет (HSL) · Маскирующие;
  - порядок внутри группы = порядок `BlendMode` (shaderIndex);
  - у каждого пункта превью 40×40 dp: фрагмент 64×64 px вокруг центра bbox слоя, смешанный с нижним композитом в этом режиме. Считается на CPU из уменьшенных (1/4…1/16) слоя и фона, один раз при открытии листа — **решение**;
  - тап = режим применяется сразу (live на холсте), лист открыт. Закрытие = одна команда `SetNodeProps` (все тапы внутри сессии — один шаг undo).
- Бейдж режима на тайле: короткое имя (≤ 4 символа, свои сокращения, напр. «Mul», «Scr», «Ovl»), если не Normal/Passthrough.
- `BlendMode.M1` удаляется, список строится по `BlendMode.Group`.

### Паритет GPU ↔ CPU
- **ADD (долг 5)**: каноническая формула = CPU (`BlendMath`, совпадает с blend-and-color.md): `C = αs(1−αb)·Cs + αb·min(1, Cb + Cs·αs)`, `αo = αs + αb(1−αs)`. Без клампа к αo, как сейчас в шейдере. При αb = 1 формулы совпадают, при αb < 1 шейдер клампит иначе. Шейдер переписать — **решение**.
- Общие тест-векторы `core/src/test/resources/vectors/blend.json`:
  - сетка: αb, αs ∈ {0, 0.25, 0.5, 1}; Cb, Cs ∈ {0, 0.2, 0.5, 0.8, 1} по каналам (разные на R/G/B, чтобы ловить HSL-режимы); opacity ∈ {1, 0.5};
  - значения в premult, ≈ 400 пар × 33 режима;
  - файл генерируется задачей `:core:genVectors` из `BlendMath` и коммитится. JVM-тест сверяет `BlendMath` с файлом (регрессия).
- androidTest `BlendParityTest` (эмулятор/устройство, GLES 3):
  - dst/src грузятся в RGBA16F-текстуры N×1;
  - прогоняется тот же `BLEND_FS` с каждым `u_mode`;
  - `glReadPixels(GL_RGBA, GL_FLOAT)`, сравнение с ожидаемым;
  - допуск 2e-3 (half). Для color_dodge/color_burn/divide/vivid_light на точках Cs ∈ {0,1} допуск 1e-2.
- Тот же механизм для коррекций: `vectors/adjust.json` (по 6 наборов параметров на тип), `AdjustParityTest`.
- Края, закреплённые в формулах (одинаково в CPU и GLSL): `cb`, `cs` клампятся в 0..1 после unpremultiply; деление на α только при α > 0, иначе цвет 0.

## L5. Adjustment layers (неразрушающие)
- Добавление: шапка панели → «+ спец. слой» → Коррекция ▸ тип. Слой встаёт над активным. Если активный клипнут или включён флаг «Клипнуть к слою ниже» в меню, новый слой сразу клипнут — **решение**.
- Сила = opacity слоя (как у ibis). Отдельного Strength нет: параметры по умолчанию = без эффекта — **решение**.
- Имя по умолчанию = название типа, переименовать можно.
- Действие:
  - на всё ниже в пределах родителя: isolated-группа ограничивает, passthrough пропускает;
  - clipped: `f` умножается на альфу базы, т. е. меняется только область базы (PS-модель). В M2 это и есть «только базовый слой» — **решение**;
  - не может быть базой клипа (клипнутые над ним рисуются без базы, иконка-предупреждение).
- Коррекции не создают альфу из нуля и не меняют α. Прозрачные тайлы аккумулятора пропускаются — **решение**.
- Повторная настройка: тап по активному adjustment-слою → options → «Настроить…» открывает workspace Adjustments в режиме слоя. Live-превью через тот же узел. Confirm = `SetAdjustParams`, Cancel = откат.

### Типы M2 (диапазоны: имя диапазон (деф.))
| kind | Параметры | Формула (кратко) | Исполнение |
|---|---|---|---|
| `brightness_contrast` | brightness −100–100 (0), contrast −100–100 (0) | b>0: `c+(1−c)·b/200`; b<0: `c·(1+b/200)`; contrast вокруг 0.5: `k = c≥0 ? 1+3c/100 : 1+c/100` | LUT |
| `exposure` | exposure −5–5 EV (0), offset −0.5–0.5 (0), gamma 0.2–5 (1) | sRGB→linear, `·2^ev + offset`, linear→sRGB, `^(1/gamma)` | LUT |
| `hue_saturation` | hue −180–180° (0), saturation −100–100 (0), lightness −100–100 (0), colorize (off), colorHue 0–360 (0), colorSat 0–100 (25) | в HSL; colorize задаёт H,S, L сохраняется | шейдер |
| `vibrance` | vibrance −100–100 (0), saturation −100–100 (0) | вес vibrance `1 − S_pixel` | шейдер |
| `temperature_tint` | temperature −100–100 (0), tint −100–100 (0) | Lab: `b* += 0.3·t`, `a* += 0.3·tint`, L сохраняется | шейдер |
| `color_balance` | shadows/midtones/highlights × (cr, mg, yb) −100–100 (0), preserveLum (on) | веса по L: `sh = smoothstep(.5,0,L)`, `mid = 1−|2L−1|`, `hi = smoothstep(.5,1,L)`; сдвиг канала `v/100·0.25·w` | шейдер |
| `levels` | канал rgb/r/g/b; на канал: inBlack 0–253 (0), inWhite 2–255 (255, ≥ inBlack+2), gamma 0.1–9.99 (1), outBlack 0–255 (0), outWhite 0–255 (255) | сначала r/g/b, потом rgb | LUT |
| `curves` | каналы rgb, r, g, b; точки (x,y ∈ 0..1), 2–16 на канал | монотонный кубик (Fritsch–Carlson); сначала r/g/b, потом rgb. Канал A только в деструктивном режиме | LUT |
| `gradient_map` | стопы 2–16 (pos 0..1, цвет RGBA) (чёрный→белый), reverse (off) | `t = Lum`, линейная интерполяция в sRGB, α' = α·α_стопа | LUT по Lum (1D) |
| `invert` | variant rgb / luminance (rgb) | rgb: `1−c`; luminance: L в HSL → `1−L` | LUT / шейдер |
| `posterize` | levels 2–64 (8) | `round(c·(n−1))/(n−1)` | LUT |
| `threshold` | level 0–255 (128), smooth 0–100 (0) | по Lum, smooth = ширина smoothstep в единицах уровня | шейдер |
| `black_white` | wR, wG, wB −200–200% (30, 59, 11), tint (off), tintColor | `g = Σ w·c`, клампинг; tint = multiply-тонирование | шейдер |
- Grayscale = `black_white` с дефолтами. Одиночные пункты IP (Brightness, Contrast, Gamma, Saturation, Hue, Temperature, Tint) в меню — ярлыки на эти kinds с одним видимым слайдером.
- Color balance, Levels, Gradient map, Posterize, B&W перенесены из M3 (filters.md) в M2 — **решение**.
- Levels Auto: по гистограмме композита под слоем (деструктивно — по цели), отсечение 0.1% с каждого края на канал.
- LUT: `AdjustLut.build(params): FloatArray` в :core, 1024 значения × 4 (R, G, B, общий). Одна функция для CPU и GPU (GPU грузит как текстуру 1024×1 RGBA16F) — источник паритета — **решение**.
- GPU: программа `ADJUST_FS` (`u_kind`, uniform-массив параметров, `u_lut`, `u_mask`, `u_clip`, `u_opacity`, `u_mode`): `a = adj(unpremult(d))`, `b = blend(u_mode, d, premult(a, d.a)·f)`. Шейдерные формулы зеркалятся в `AdjustMath` (CPU).
- CPU: `object AdjustMath { fun applyTile(p: AdjustParams, lut: FloatArray?, px: FloatArray) }` (premult in/out, scratch FloatArray — не зависит от half-хранения). Параллельно по тайлам: `TileJobs.parallel(keys)` на `Dispatchers.Default`.

## L6. Деструктивные коррекции
- Editing Tools → Adjustments (workspace): нижний тулбар — вкладки Свет · Цвет · Тон, в них типы L5 + одиночные ярлыки. Над тулбаром — панель слайдеров активного типа. Curves и Levels — отдельные пункты Editing Tools (UI-SHELL), открывают тот же workspace сразу на своём редакторе.
- Цель: активный слой или активная маска. С активным выделением — только внутри (`m_sel` как f). На группе/adjustment пункт недоступен (тост «Выберите растровый слой») — **решение**.
- Сессия: можно менять несколько типов подряд, они копятся цепочкой в порядке применения. Последний тип и его параметры запоминаются.
- Live-превью: renderer подменяет текстуру слоя на `adj(layer)` в blend-проходе (`setAdjustPreview(layerId, chain, selection?)`), без CPU. Throttle 1 кадр.
- Контролы: «Скрыть эффект» (удержание глаза = до/после), Reset параметра (двойной тап по слайдеру), Cancel, Confirm. Кнопка **«Как слой»**: вместо запекания создаёт adjustment layer(ы) над целью с теми же параметрами (клипнутые, если цель — не единственный видимый) — **решение**.
- Quick masks (focus, tilt-shift, тоновые) — M3 вместе с фильтрами. В M2 только глобальное выделение.
- Confirm: CPU-применение по тайлам параллельно → `TilePatch` → `TilePatchCommand`. Если > 300 мс — неблокирующий индикатор на холсте, ввод блокируется. Результат CPU должен совпадать с превью (тест-векторы L4).

### Редактор кривых
- Квадратный график ≥ 280 dp, сетка 4×4, диагональ-ориентир, гистограмма цели под кривой (выбранный канал; для RGB — яркость).
- Каналы чипами: RGB · R · G · B · A (A только деструктивно). Каждый канал рисуется своим цветом. Неактивные каналы видны тонкими линиями.
- Узлы:
  - тап по пустому месту = добавить узел на кривую в этой x;
  - drag = двигать (x ограничен соседями с зазором 0.01; концевые двигаются по x и y);
  - drag узла за край графика или на соседа = удалить;
  - удалены все внутренние узлы = прямая.
- Выбранный узел: подпись «вход → выход» (0–255), стрелки ±1 по тапу.
- Пресеты (выпадающий список): Прямая, Светлее, Темнее, Контраст+, Контраст−, Негатив, Негатив яркости (инверсия только RGB-кривой). «Сохранить пресет…» — свои пресеты с именем, хранятся в настройках (`.pcfg`).
- Сброс канала / сброс всех.

### Редактор Levels
- Гистограмма, 3 бегунка входа (black, gamma, white), 2 бегунка выхода, канал-чипы RGB/R/G/B, кнопка Auto, числовые поля.

## L7. Прочие функции слоя
| Функция | Поведение | Где в UI |
|---|---|---|
| Lock (`locked`) | запрещает пиксели, маску, Transform, Delete, Merge (в т. ч. как нижний). Видимость, opacity, режим, порядок можно. Lock группы действует на всех потомков. Инструмент на locked-слое: тост «Слой заблокирован» | toggle в options, бейдж-замок |
| Слой → выделение (Select) | raster: по α; group: по α изолированного рендера; маска: по m. Режим = текущий режим Selection (replace/add/subtract/intersect) | options «Выделить» |
| Flatten документа | все видимые узлы → один растровый слой «Слой 1», скрытые удаляются после подтверждения; Paper не трогается | Layer panel ⋮ → «Свести изображение» |
| Merge down | см. ниже | options |
| Свойства слоя | диалог: имя, opacity, режим, тег, lock, alpha lock, clip, reference, hide in recording | options → «Свойства…» |
| Цветовые теги | none, red, orange, yellow, green, blue, violet, gray. Полоса 4 dp у левого края строки, в свёрнутой панели — точка | диалог свойств, long-press по полосе — быстрый выбор |
| Reference | один узел на документ (raster или group). Для Fill и Wand с опцией «Источник: референс». Включение на другом снимает со старого (одна команда) | toggle в options, бейдж |
| Миниатюры | обновление через 250 мс после последней команды, только для изменённых узлов (по `Tile.version`). Группа обновляется, если изменился ребёнок. 64 px по длинной стороне, фоновый поток. Настройка «Миниатюра: весь холст / только содержимое (bbox)» (деф. весь холст) | — |
| Delete | отменяемо (отличие от IP) — **решение** | options, мультивыбор |
- Merge down:
  - raster на raster: как в M1, маски обоих сначала применяются;
  - на группу ниже: группа сначала flatten;
  - adjustment на raster/группу ниже: коррекция запекается в нижний (с маской, opacity, режимом), adjustment удаляется;
  - нижний = adjustment: пункт недоступен — **решение**.
- Лимит слоёв (`StudioViewModel.layerLimitFor`): adjustment-слои и группы не считаются; маска = 1/8 слоя (1 Б/px против 8). Счётчик в панели: `растровые + маски/8` из лимита. Формулу пересчитать после перехода на half (долг 1).

---

## Модель данных (:core/doc, эскиз)
```kotlin
// core/raster — новый общий тип (нужен и Selection)
class MaskGrid(val width: Int, val height: Int, var defaultValue: Byte /*0 или -1(255)*/) {
    fun get(key: TileKey): MaskTile?; fun getOrCreate(key: TileKey): MaskTile /*заполнен defaultValue*/
    fun put(key: TileKey, t: MaskTile?); fun keys(): Set<TileKey>; fun copy(): MaskGrid
    fun valueAt(x: Int, y: Int): Float
}
class MaskPatch(val nodeId: String, val before: Map<TileKey, MaskTile?>, val after: Map<TileKey, MaskTile?>)

class LayerMask(val tiles: MaskGrid) {
    var enabled = true; var linked = true; var density = 1f
    fun deepCopy(): LayerMask
}

enum class ColorTag { NONE, RED, ORANGE, YELLOW, GREEN, BLUE, VIOLET, GRAY }
enum class EditTarget { LAYER, MASK }

sealed class Node(...) {
    // +
    var mask: LayerMask? = null
    var colorTag: ColorTag = ColorTag.NONE
    var reference: Boolean = false
}

class AdjustmentLayer(id: String, name: String, var params: AdjustParams) : Node(id, name)

// core/adjust
@Serializable sealed class AdjustParams { abstract val kind: String }
@Serializable @SerialName("curves") data class CurvesParams(
    val rgb: List<Pt> = LINEAR, val r: List<Pt> = LINEAR, val g: List<Pt> = LINEAR,
    val b: List<Pt> = LINEAR, val a: List<Pt> = LINEAR) : AdjustParams()
// ... по data class на каждый kind из L5
@Serializable data class UnknownAdjust(val raw: JsonObject) : AdjustParams()  // неизвестный kind: без эффекта, сохраняется как есть

class Document { /* + */ var activeTarget: EditTarget = EditTarget.LAYER
                 val referenceNode: Node? }
```
- Мультивыбор — состояние ViewModel/UI (`selectedIds`), не документа. Команды принимают списки id — **решение**.
- Selection документа (`Document.selection: MaskGrid?`) вводит спека Selection, здесь только используется.

## Команды истории (core/history)
| Команда | Данные | Заметки |
|---|---|---|
| `GroupNodes(ids, groupName)` | id новой группы, исходные (parent, index) каждого | разные родители: группа встаёт на место верхнего |
| `Ungroup(groupId, maskPolicy)` | группа целиком, индексы детей, изменённые маски детей | |
| `MoveNodes(ids, newParentId, index)` | исходные (parent, index) | заменяет `MoveNode` (старый = частный случай) |
| `DeleteNodes(ids)` | узлы целиком + позиции, старый activeId | |
| `DuplicateNodes(ids)` | | |
| `MergeNodes(ids, blender)` / `MergeDown` (расширить) | удалённые узлы, до-тайлы нижнего | `blender` принимает маску и adjustment-обработчик, без прямой зависимости от `core/blend` |
| `FlattenGroup(id)`, `FlattenDocument` | исходное поддерево | |
| `CopyMerged(groupId)` | id нового слоя | |
| `AddMask(nodeId, init)`, `DeleteMask(nodeId)`, `ApplyMask(nodeId)` | маска целиком / до-тайлы слоя | |
| `MaskPatchCommand(patch)` | `MaskPatch` | мазок, fill, invert, деструктивная коррекция на маске |
| `SetMaskProps(nodeId, enabled, linked, density)` | до/после | |
| `AddAdjustmentLayer(params, parentId, index, clipped)` | | |
| `SetAdjustParams(id, before, after)` | | одна на сессию «Настроить…» |
| `SetNodeProps` (расширить) | + colorTag, reference, locked, name, blend, opacity | reference: снимает флаг со старого узла в той же команде |
| `SetActiveTarget` | не команда: не попадает в историю | |
| деструктивная коррекция | `TilePatchCommand` | существует |
- `StrokeEngine` получает цель `StrokeTarget.Layer(id) | StrokeTarget.Mask(nodeId)`. Для маски `end()` сводит buffer в `MaskGrid` и возвращает `MaskPatch`.

## manifest.json, schema 2
```json
{ "schema": 2, "activeId": "…", "activeTarget": "layer",
  "nodes": [
    { "id": "…", "kind": "raster", "colorTag": "none", "reference": false,
      "mask": { "enabled": true, "linked": true, "density": 1.0, "default": 255 } },
    { "id": "…", "kind": "adjustment", "blendMode": "normal", "opacity": 1.0, "clipped": false,
      "adjust": { "kind": "curves", "rgb": [[0,0],[0.5,0.6],[1,1]], "r": [[0,0],[1,1]] } },
    { "id": "…", "kind": "group", "blendMode": "passthrough", "expanded": false, "children": [] } ] }
```
- Тайлы масок: `masks/<nodeId>/tiles.idx` + `tiles.bin`, тот же формат индекса, R8 65536 Б на тайл, LZ4-frame. Тайлы, равные `default` целиком, не пишутся.
- `NodeKind.ADJUSTMENT = "adjustment"`. Неизвестный `kind` узла при чтении → пропуск с логом. Неизвестный `adjust.kind` → `UnknownAdjust` (рендерится как пустой, пишется обратно как был).
- Миграция 1 → 2: поля с дефолтами, без преобразований. Запись всегда schema 2.
- PSD-маппинг adjustment-слоёв — M3, вне этой спеки.

## Изменения рендера (app/render)
- `SnapNode`: + `kind` (raster/group/adjust), `mask: SnapMask?(enabled, density, default)`, `adjust: SnapAdjust?(kindIndex, uniforms FloatArray, lut FloatArray?)`. LUT и uniforms готовятся в `DocumentSync` вне GL-потока.
- Тайлы масок: отдельный пул `GL_R8`. Upload по dirty-ключам `MaskTile.version`, как у пикселей.
- `BLEND_FS`: + `u_mask`, `u_useMask`, `u_maskDefault`, `u_maskDensity`; `f *= mask_eff`. Предпросмотр мазка в маску: `u_preview = 3` (буфер смешивается в значение маски в шейдере, не в цвет).
- Lerp-путь passthrough: попиксельный фактор `opacity · mask_eff`.
- Новая программа `ADJUST_FS` (L5). Деструктивное превью = adjust-проход над текстурой слоя во временный target перед blendPass.
- Фикс: клип к passthrough-группе рендерит изолированную альфу базы, как CPU.
- Фикс: ADD по канонической формуле.
- «Показать маску»: display-проход выводит маску активного узла серым.
- Бюджет: adjustment = 1 полноэкранный проход на тайл. Цель — 60 fps при pan на 4096² с 10 слоями + 3 adjustment + 3 маски.

## Приёмка
- Группы:
  - вложить на 3 уровня drag'ом, вынуть обратно. Undo/redo каждого шага;
  - passthrough-группа с hue-коррекцией внутри меняет слои под группой, normal-группа — нет;
  - клип к passthrough-группе выглядит одинаково на GPU и в экспортированном PNG (CPU).
- Мультивыбор: выбрать 3 несмежных слоя → Сгруппировать → Объединить; один шаг undo на действие.
- Маски:
  - создать каждым из 4 способов. Кисть с серым 50% даёт полупрозрачность 50% ± 1/255;
  - Erase в маске скрывает, Paint белым возвращает. Пиксели слоя не меняются;
  - выкл/вкл, Apply, Delete — отменяемы;
  - маска группы прячет всю группу;
  - экспорт PNG = экран (CPU = GPU в пределах 1/255).
- Режимы: все 33 в списке с превью; `BlendParityTest` зелёный на эмуляторе для всех режимов.
- Adjustment:
  - 13 типов создаются, настраиваются, сохраняются, открываются после перезапуска с теми же параметрами;
  - opacity 0 = без эффекта; клипнутый влияет только в пределах альфы базы;
  - drag слайдера на 4096² с 10 слоями ≥ 30 fps.
- Деструктивно:
  - превью = результат Confirm (`AdjustParityTest`);
  - с выделением меняется только внутри;
  - 4096² полный слой: Confirm ≤ 1.5 с на среднем устройстве;
  - «Как слой» даёт тот же вид, что Confirm.
- Кривые: добавить/двигать/удалить узлы, все пресеты, свой пресет переживает перезапуск.
- Lock блокирует мазок и Transform. Reference — единственный в документе. Миниатюры обновляются ≤ 0.5 с после мазка.

## JVM-тесты (core/src/test)
| Пакет | Тесты |
|---|---|
| `raster` | MaskGrid: default-тайлы, copy, valueAt; MaskPatch apply/revert |
| `doc` | deepCopy с маской/тегом/reference; AdjustmentLayer deepCopy; reference единственный |
| `blend` | `BlendVectorsTest` (все 33 по `vectors/blend.json`); ADD при αb < 1; compositeInto с маской (density, default 0/255, отсутствующий тайл) |
| `blend/Compositor` | маска raster / isolated group / passthrough group; клип к базе с маской; клип к passthrough-группе; adjustment: в isolated группе не выходит наружу, в passthrough выходит; clipped adjustment в пределах альфы базы; adjustment не трогает прозрачное; adjustment как база = клипнутые без базы не рисуются |
| `adjust` | `AdjustVectorsTest`; identity для каждого kind при дефолтах (Δ ≤ 1e-6); кривые: монотонность, 2 точки = прямая, удаление узлов; levels: in/out границы, gamma; posterize n уровней; gradient_map reverse; Auto levels на синтетической гистограмме; сериализация round-trip каждого kind; UnknownAdjust round-trip |
| `history` | GroupNodes/Ungroup (одинаковые и разные родители), MoveNodes multi с сохранением порядка, запрет вложить группу в потомка, DeleteNodes multi, MergeNodes, FlattenGroup (passthrough → normal), CopyMerged, Merge down adjustment на raster (запекание), Merge на adjustment запрещён, AddMask×4 init, ApplyMask raster/group, MaskPatchCommand, SetMaskProps, SetAdjustParams, SetNodeProps reference снимает старый, lock блокирует пиксельные команды |
| `stroke` | мазок в маску: Paint серым, Erase = 0, alpha lock игнорируется; cancel не меняет маску |
| `io` | schema 1 читается как 2; round-trip: маски (default 0/255, разреженные тайлы), adjustment всех kinds, colorTag, reference, activeTarget; неизвестный kind узла пропускается |
