# M2: кисти (движок, каталог, UI)

Основа: spec/brush-engine.md (параметры), spec/GAPS.md §B «Кисти» (формулы), spec/brush-catalog.md (каталог). Здесь: как это реализовать в M2. Всё, что помечено «решение», выбрано здесь и обязательно для реализации.

## 0. Архитектура M2
- **Штампы на CPU в `:core`**, GPU только композит (stroke buffer как превью-текстура). GPU-штампы появятся позже и только если бенчмарк (§5) провален.
- **Хранение.** Тайлы слоя хранятся в half (8 Б/px). Мазок работает с float-копиями:
  - stroke buffer: float32 RGBA premult (как сейчас), тайлы из пула `TilePool` (1 тайл = 1 МБ, лимит пула 96 тайлов, «решение»);
  - `LayerWorkingSet`: при первом касании тайл слоя декодируется в float-тайл (half → float через LUT 65536). `before` = копия half-тайла (512 КБ). На `end()` float кодируется обратно.
- **Поток.** Мазок считается на отдельном stroke-потоке (не UI, не GL). `takeDirty()` синхронизирован. Рабочие потоки: `N = clamp(cores − 2, 1, 4)`.
- **Параллелизм по тайлам.** Кадр ввода → `DabGenerator` последовательно строит `DabBatch` (позиции, размеры, цвета, все random) → бининг штампов по тайлам → каждый тайл в своём потоке, штампы внутри тайла строго в порядке индекса.
  - Ядра, которые читают соседние пиксели или результат прошлого штампа (smudge, filter, watercolor deposit с mix_in), идут с барьером на каждый штамп: фаза чтения (сбор в scratch) → фаза записи. Тайлы одного штампа параллельно.
- **Детерминизм.** RNG без состояния: `rnd(ch, i) = mix64(seed ⊕ i·0x9E3779B97F4A7C15 ⊕ ch·0xC2B2AE3D27D4EB4F) → [0,1)`. Здесь `i` = индекс штампа (или точки), `ch` = номер канала (size, angle, scatter…), `seed` = хеш (strokeIndex, brushId). Результат бит-в-бит не зависит от числа потоков и от того, как ввод разбит на пачки.
- **Повторный рендер на pen-up.** Сырые точки мазка хранятся. Если включён after_smoothing или профиль длиннее мазка (§1.6), на `end()` буфер очищается и мазок рисуется заново по финальной траектории. Один механизм на оба случая.
- **Пути композита.**
  - Буферный (по умолчанию): штампы → stroke buffer → на `end()` в слой с cap = opacity и blend mode кисти.
  - Прямой (buildup, smudge, mix_in/dilution > 0, filter, clone): штампы сразу в `LayerWorkingSet`. Opacity умножается на flow, cap нет («решение»).

## 1. Движок: группы фич
Обозначения: `m` = покрытие маски штампа в пикселе 0..1, `d` = текущий диаметр, `θ` = направление пути, `s` = длина дуги от начала мазка.

### 1.1 Head: bitmap и процедурные
- **Алгоритм.**
  - Голова = `HeadImage`: 8-bit gray (маска) или RGBA8 (цветная) + mip-пирамида до 1×1.
  - Пиксель холста переводится в UV головы обратным преобразованием: сдвиг → поворот −angle → масштаб `1/(d·sx)`, `1/(d·sy)`.
  - Mip-уровень `L = clamp(floor(log2(srcSize / d)), 0, max)`, выборка билинейная. Процедурная круглая голова остаётся аналитической (текущий `DabRenderer`).
  - head_curve (LUT 256) и invert применяются к маске один раз при загрузке. Hardness для bitmap = softness: маска размывается Гауссом σ = (1 − hardness)·0.15·srcSize (кешируется на 8 уровнях).
- **Aspect** a ∈ [−1,1]: при a > 0 `sy = 1 − 0.95a`, при a < 0 `sx = 1 − 0.95|a|`.
- **AA и dots.** antialias = false: `m = m ≥ 0.5 ? 1 : 0`, центр штампа снапается к центру пикселя (пиксель-перо). dots = true: spacing не меньше 100%.
- **Depth** (щетина): `m' = sat((h − k)/(1 − k))`, где `k = depth·(1 − p)`, h = маска как height-map.
- **Structure** (импасто): в stroke buffer есть плоскость высоты H, `H = max(H, m·structure)`. На композите нормаль из `∇H` (Собель), Ламберт со светом (−0.5, −0.6, 0.62), яркость ×(1 ± 0.25).
- **Параметры:** head_source, head_color, head_invert, head_curve, hardness, aspect, angle, antialias, dots, depth, structure.
- **Данные:** PNG головы, mip-пирамида (≈ 1.33× размера).
- **CPU:** d² пикселей на штамп. Bitmap ≈ 2× дороже аналитического круга. Mip ограничивает чтение текстуры.
- **Приёмка:** голова 256² при d = 16 и d = 512 без алиасинга и муара. Поворот на 90° даёт тот же штамп, повёрнутый (ошибка ≤ 1/255 после округления). Пиксель-перо 1 px даёт ровно 1 px без полутонов.

### 1.2 Rotation
`angle = base + rotationTerm + jitter`. rotationTerm зависит от rotation_mode:

| mode | rotationTerm |
|---|---|
| fixed | 0 |
| tangent | `f·θ`, f = rotation_follow/100 ∈ (0,1] |
| reverse | `−f·θ` (rotation_follow < 0) |
| stylus-azimuth | azimuth. Пальцем = направление первых 8 px мазка |
| stylus-tilt | `azimuth · tilt/(π/2)` (наклон сильнее → голова сильнее следует азимуту) |
| twist | twist (barrel). Если устройство его не даёт, 0 |
| random | `360°·rnd(ANGLE, i)` |

- `θ` сглажен EMA (α = 0.35) по шагам пути, чтобы не дёргался на изломах.
- orient_screen: к углу прибавляется поворот вида (`StrokeContext.viewRotation`).
- **Приёмка:** в tangent-режиме при f = 1 вытянутая голова всегда смотрит вдоль окружности (ошибка ≤ 3°).

### 1.3 Jitter, scatter, particles-scatter
- **Jitter** (continuous, на штамп; r = rnd(канал, i)):
  - размер ×(1 − j·r);
  - flow ×(1 − j·r);
  - opacity штампа ×(1 − j·r);
  - угол += j·180°·(2r − 1);
  - шаг ×(1 + 0.5·j·(2r − 1));
  - scatter: сдвиг ⊥ θ на `j·d·(2r − 1)` и вдоль θ на `0.25·j·d·(2r′ − 1)`.
- **Initial** (раз на мазок, i = 0): угол, HSB.
- **Count** (1–16): на каждый шаг пути выдаётся count штампов, у каждого свой индекс `i·16 + k`.
- **Color jitter.** hue += Jh·180°·(2r − 1), sat и bri += J·(2r − 1), clamp. Initial + continuous складываются. Пересчёт цвета на штамп, а не на пиксель.
- **Scatter particles** (ibis):
  - на каждый шаг particle_count (1–64) мини-штампов в круге радиуса d/2;
  - радиус точки `ρ = (d/2)·u^q`, где `q = 0.5·2^(deviation/50)`: 0 = равномерно по площади, + к центру, − к краю;
  - размер = particle_size·d (или абсолютный, px);
  - angle_from_center: угол = atan2 смещения.
- **CPU:** count × particle_count штампов. Лимит «решение»: ≤ 4096 штампов на кадр. Избыток переносится на следующий кадр, очерёдность сохраняется.
- **Приёмка:** jitter = 0 даёт бит-в-бит мазок M1. Одинаковый seed даёт одинаковый мазок. Среднее смещение scatter ≈ 0 (|μ| < 0.05·d на 10⁴ штампов).

### 1.4 Texture
- **Значение текстуры.** `t ∈ [0,1]`: gray-текстура 512², тайлится. Обработка: invert, tex_curve (LUT), softness (Гаусс σ = softness·0.08·512, кеш), scale, stretch (×stretch вдоль оси u), tex_angle.
- **Режимы** (откуда берутся UV):

| tex_mode | UV |
|---|---|
| canvas-fixed | `(x, y)/(512·scale)` в координатах документа |
| rotate-with-stroke | координаты документа, повёрнутые на угол штампа вокруг его центра |
| warp-along-stroke | `u = s`, `v` = знаковое расстояние до пути (из локальной системы штампа) |
| moving | UV относительно центра штампа (текстура едет со штампом и размазывается) |

- **Применение:** `m' = m·(1 − depth·(1 − t))`, depth [dyn].
- **Gradation:** depth ×(1 − g·τ), τ = 0 на кончике, 1 на хвосте вдоль оси наклона.
- **Lower limit L.** Потолок альфы слоя в пикселе: `cap = L + (1 − L)·(1 − depth·(1 − t))`. Повторный мазок не поднимает альфу выше cap. «Решение»: дефолт **100** (потолка нет), а не 0 из brush-engine.md. Пресеты карандашей ставят 30–60. Таблицу в brush-engine.md поправить.
- **Jitter текстуры** (pos / scale / angle): initial = смещение UV на мазок, continuous = на штамп.
- tex_scale_with_size: scale ×(d / size).
- **CPU:** +1 билинейная выборка на пиксель штампа (≈ +30%).
- **Приёмка:** canvas-fixed даёт одинаковый узор у двух пересекающихся мазков. Шва на краю тайла 512 нет (разница соседних пикселей ≤ 2/255). warp при stretch = 10 даёт волокна вдоль кривой.

### 1.5 Dynamics
- **Источники** (все 0..1):

| источник | как считается |
|---|---|
| pressure | после глобальной калибровки (§4.3). Палец = 1 |
| velocity | `sat(v/5)`, v = скорость в экранных px/мс, EMA α = 0.3 («решение») |
| tilt | tilt / (π/2) |
| azimuth | azimuth / 2π |
| twist | twist / 2π |
| random | rnd(DYN, i) |
| progress | `sat(s / 300 px)` («решение») |

- **Цели:** size, flow, opacity, scatter, angle, hardness, tex_depth, mix_in, dilution, color_jitter, blur.
- **Связь** = `DynamicsLink(source, target, curve: Curve)`. Curve уже есть (монотонный кубический, min).
- **Как сочетаются связи одной цели:**
  - произведение для size, flow, opacity, hardness, tex_depth, mix_in, dilution, blur;
  - множитель амплитуды для scatter и color_jitter;
  - для angle: `+= 360°·(curve(x) − curve(0))`.
- **Совместимость с M1:** pressureToSize и pressureToFlow остаются полями и внутри превращаются в связи.
- **Tilt offset:** центр штампа сдвигается по азимуту на `tilt·offset·d/2`.
- Глобальные тогглы Settings «pressure / velocity / tilt off» подменяют источник на 1 (pressure) или 0 (остальные).
- **Приёмка:** связь с кривой y = 1 ничего не меняет. velocity→size уменьшает толщину на быстром отрезке синтетического мазка.

### 1.6 Taper и профили
- **Taper:** уже есть.
  - taper_shape: linear `f = t`, smooth `1 − (1 − t)²`;
  - size ×lerp(1, f, taper_size), alpha ×lerp(1, f, taper_opacity).
- **Size / flow profile** = Curve на x ∈ [0,1], 3 секции:
  - x ∈ [0, ⅓] → первые Ls px (деф. 60);
  - [⅓, ⅔] → середина, тянется;
  - [⅔, 1] → последние Le px (деф. 60).
- **Хвост профиля.** Последние Le px держатся до pen-up (как taperEnd). Если мазок короче Ls + Le, секции делятся поровну и мазок перерисовывается (§0).
- **Приёмка:** профиль «тонко-толсто-тонко» на мазке 1000 px: середина постоянна на 880 px.

### 1.7 Wet edges, glaze, adhesion, blend mode
- **Glaze** g: в буфере `a = lerp(over(a_buf, a_dab), max(a_buf, a_dab), g)`. RGB по тем же весам.
- **Wet edges** w: на композите (и в превью для dirty-тайлов с ореолом 4 px):
  - `e = sat((α − box₄(α))·4 + 0.5)`;
  - `α' = α·(1 − w·(1 − e))`;
  - box₄ = сепарабельный box-blur радиусом 4, 2 прохода.
- **Adhesion** a: в буфере плоскость загрузки L, `L = over(L, m·flow)`, видимая альфа = `smoothstep(a, 1, L)`.
- **Blend mode кисти:** буфер композитится через `BlendMath.blendPixel(mode)`, `out = lerp(normal, mode, strength)`.
- **separate_strokes = false:** буфер живёт между мазками, пока не сменились инструмент, кисть или слой. cap opacity общий. Каждый мазок всё равно отдельный шаг undo (патч = разница с прошлым состоянием).
- **Приёмка:**
  - glaze = 1 и opacity 50%: самопересечение не темнее одиночного прохода;
  - wet edges = 100: на плоской заливке край на ≥ 30% плотнее центра;
  - multiply-кисть по белому = цвет кисти.

### 1.8 Smudge и Blend-режим (mix_in, dilution, pull, wet, blur)
- **Pickup-буфер P** (RGBA, дискретизация головы: `n = min(64, ceil(d))`², в локальной системе штампа).
- **На каждый штамп:**
  1. C = цвет под штампом в сетке n² (билинейно из источника).
  2. Источник = активный слой или, при sample_lower, «нижний композит» + активный. Нижний композит считается лениво по тайлам и кешируется на мазок.
  3. `C' = lerp(C, mean(C), blur)`, mean взвешен маской.
- **Blend-режим** (цвет не кладётся):
  - `dst = lerp(dst, P, m·flow·blend_flow)` (премультиплированно, альфа переносится всегда);
  - затем `P = lerp(P, C', 1 − pull/100·0.98)`;
  - первый штамп мазка инициализирует `P = C'`.
- **Paint с mix_in / dilution** (резервуар R = один цвет, формулы GAPS):
  - `S = mean(C')`;
  - `R = lerp(R, S, 1 − pull)`;
  - `paint = lerp(paint, S, mix_in·m̄)`;
  - нанесённый цвет = `lerp(paint, R, dilution)`;
  - wet = false: альфа слоя под штампом не уменьшается (`a_out = max(a_dst, over)`);
  - wet = true: альфа тоже смешивается с S.
- **ibis Water:** color_mix → mix_in, wetness → dilution + wet, dragging → pull.
- **Путь:** прямой, барьер на каждый штамп.
- **CPU:** n² выборок + d² записей на штамп. Spacing не меньше 8% («решение»: у smudge-пресетов 8–15%).
- **Приёмка:**
  - палец по границе красное|синее даёт плавный градиент, длина смаза растёт с pull;
  - dilution = 100 без mix_in не вносит цвет кисти (ΔE < 1 от исходных цветов);
  - blur = 0 переносит детали (шахматка остаётся различимой на первых 3 штампах).

### 1.9 Watercolor
- **Модель** (CPU, «решение» вместо GPU из GAPS).
  - Мокрая сетка на 1/2 разрешения: ячейка 2×2 px, тайл 128²;
  - вода W, пигмент P (RGBA premult);
  - wet-сетка одна на растровый слой.
- **Нанесение:** `W += m·flow`, `P += color·m·flow·0.5`. При mix_in добавляется `mix_in·m·layerColor` (подхват нижнего).
- **Тик 30 Гц** (на рабочих потоках, по активным тайлам с ореолом 1 ячейка):
  - диффузия: `P += 0.2·bleed·Σ₄ min(W, Wₙ)·(Pₙ − P)/4` (явная схема, устойчива при коэффициенте ≤ 0.25);
  - край: перенос пигмента по `−∇W` с силой glaze·0.1, даёт потемнение каймы;
  - испарение: `W −= (0.15 + 1.35·dryout/100)·dt` в секунду;
  - зерно бумаги: при осаждении P умножается на `lerp(1, t_paper, 0.4)`.
- **Сушка и фиксация.**
  - Ячейка с W < 0.01 сухая. Тайл, где все ячейки сухие, апскейлится билинейно и композитится в слой.
  - Мазок фиксируется в undo, когда высох весь или прошло 4 с после pen-up.
  - Любая другая команда (undo, смена слоя, сохранение, фильтр) сначала досушивает мгновенно.
  - Новый мазок по мокрому = wet-into-wet (та же сетка).
- **Превью:** P → stroke buffer тайлы.
- **Лимит:** 64 активных тайла, при превышении досушивается самый старый.
- **Приёмка:**
  - капля на сухом расплывается, радиус растёт с bleed и останавливается не позже 4 с;
  - край темнее центра при glaze > 50;
  - два мокрых мазка смешиваются, мокрый по сухому нет;
  - тик ≤ 8 мс на 16 активных тайлах (эталонный девайс).

### 1.10 Particles
- **Система** (шаг = шаг пути, не время, поэтому детерминирована):
  - n_a аттракторов (1–64) на пружине к точке пути + смещение `radius·d/2·dirₖ`, dirₖ из rnd;
  - n_p частиц (1–400), частица j привязана к аттрактору j mod n_a;
  - `v = v·damp + 0.3·(target − x)`, `damp = 0.5 + 0.24·overshoot`;
  - случайный импульс = continuous scatter головы.
- **Рисование:** каждая частица штампует particle-головой (d·p_size) по отрезку от прошлой позиции, spacing 50%.
- **CPU:** n_p·длина/шаг штампов. При n_p > 100 шаг пути ≥ d/4.
- **Приёмка:** на крутом повороте при overshoot = 2 брызги вылетают за контур дальше, чем при 0.

### 1.11 Harmony
- **Общее.**
  - Своя реализация: точки мазка (после стабилизатора, шаг ≥ 2 px) хранятся в пространственном хеше с ячейкой R.
  - Новая точка соединяется с прошлыми по правилам варианта.
  - Линии рисует `LineRenderer`: капсула толщиной size, AA по расстоянию до отрезка.
  - Альфа линии ∝ (1 − dist/R). Head не используется.
  - Параметры: size (толщина), spread (×R, деф. 1), density (×p, деф. 1). История до 4000 точек.
- **Варианты:**

| вариант | R, px | правило |
|---|---|---|
| sketchy | 80 | с прошлыми, p = 0.3, линия укорочена на 20% с обоих концов, α·0.3 |
| shaded | 100 | со всеми в R, α·0.08 |
| web | 60 | со всеми в R, p = 0.5, α·0.2 |
| chrome | 70 | как sketchy, цвет линии = цвет ± 30% яркости по rnd |
| short fur / long fur | 40 / 120 | от точки к `p + (pⱼ − p)·(−0.5..1.5)·rnd`, p = 0.6, α·0.25 |
| ribbon | — | 32 «пружины» с жёсткостью 0.05–0.4, линии между последовательными позициями |
| curvy | 90 | квадратичная Безье к pⱼ через смещённую середину |
| grid | 60 | только оси: горизонтальные и вертикальные отрезки к проекциям pⱼ |
| circles / squares | — | на каждой точке фигура радиусом ∝ скорости (2–60 px), квадрат повёрнут на θ |

- **CPU:** O(k) соседей на точку, k ≤ 64 («решение»: ближайшие).
- **Приёмка:** плотность растёт там, где мазок возвращается на себя. Результат детерминирован.

### 1.12 Double (контур и тень)
- **Алгоритм:**
  - тело = буфер мазка (альфа A, цвет body_color или текущий);
  - тень = `S = sat(shadow_size + 0.5 − EDT(A ≥ 0.5))`, сдвинутая на (distance, angle);
  - EDT по Фельценшвальбу, на bbox dirty-тайлов + ореол `shadow_size + distance` (≤ 64 px);
  - композит: shadow_color·S под телом;
  - texture_on_shadow: S × t.
- **Приёмка:** контур равномерной толщины ±0.5 px вокруг петли. distance = 0 даёт чистый аутлайн.

### 1.13 Color stamp (цветной штамп)
- **Алгоритм:** голова RGBA. Сдвиг цвета считается один раз на мазок (и заново на штамп, если включён continuous color jitter; кеш по hue с шагом 1°):
  - `h′ = h + (h_cur − h_base)`;
  - `S′ = min(S, S·S_cur / max(S_base, 0.01))`, V′ аналогично;
  - colorize: смесь исходного и сдвинутого.
- **Приёмка:** при текущем цвете = base_color штамп совпадает с битмапом (ΔE < 1). При синем текущем красный штамп становится синим и не становится ярче исходного.

### 1.14 Screentone snap
- **Алгоритм:**
  - сетка с шагом g (px, или `g·d/size` при scale_with_size), повёрнута на angle;
  - для узлов в зоне штампа копится `c = max(c, m·flow)` в карте узлов мазка;
  - в узле рисуется точка с диаметром `g·sqrt(c)·1.13` (площадь ∝ покрытию). Режимы line и cross рисуют штрих длины g и толщины `g·c`;
  - перерисовка узла только при росте c (через max-оп).
- **Приёмка:** мягкая кисть даёт градиент из растущих точек, все центры точно на узлах сетки.

### 1.15 Fill brush
- **Алгоритм:**
  - траектория = полигон (замыкается последней точкой);
  - каждый кадр растеризация even-odd: сканлайны, 4 подстроки на пиксель для AA;
  - контент: solid, pattern (текстура canvas-fixed) или procedural (шум);
  - draw preview off: заливка только на pen-up.
  - На fill-кистях не работают harmony, particles, screentone.
- **CPU:** O(площадь bbox). При bbox > 4 Мpx превью 10 Гц.
- **Приёмка:** восьмёрка заливается по even-odd (пересечение пустое). Край AA.

### 1.16 Filter brush (blur, glow, sharpen)
- **target = layer** (прямой путь):
  - `out = lerp(src, F(src), m·flow·|strength|)`;
  - blur = 3 прохода box, радиус `r = 1 + 0.06·d·|strength|`, по региону штампа + ореол r из текущего слоя;
  - strength < 0 у blur = sharpen (unsharp mask, amount |strength|·1.5);
  - glow: `src + blur(src·sat((lum − 0.6)/0.4), r)·strength` (Add). strength < 0 = тёмный ореол (Multiply).
- **target = stroke:** F применяется к буферу перед композитом (glow-кисти: ядро + размытый ореол, blend Add/Screen).
- **CPU:** (d + 2r)²·6 на штамп. Spacing у пресетов ≥ 20%.
- **Приёмка:** blur-кисть на шахматке сглаживает только под мазком. strength 0 = no-op бит-в-бит.

### 1.17 3D stamp (tube, ribbon, twisty)
- **Tube:**
  - нормаль `n = (x, y, sqrt(1 − r²))` в локальной системе диска;
  - цвет `c·(0.35 + 0.65·max(0, n·L)) + 0.4·(n·H)^24`, L как в structure;
  - в буфере z-плоскость, пиксель пишется если `z_new ≥ z_buf` → гладкая трубка при spacing 5%.
- **Ribbon:** плоская полоса ⊥ θ, нормаль поворачивается на `s·twist_rate` (деф. 1°/px).
- **Twisty:** tube × полосы по `φ = atan2(nₓ, n_z) + s·twist_rate`, 4 полосы.
- **Приёмка:** на прямой трубке блик непрерывен, тёмная сторона снизу-справа.

### 1.18 Clone mode (`StrokeMode.CLONE`)
- **Источник:**
  - слой (деф. активный) или «все видимые»;
  - снимок источника берётся на begin, поэтому клонировать сам в себя безопасно;
  - offset задаётся long-press в Clone-режиме (точка источника), курсор-перекрестие на источнике.
- **Aligned** (деф.): offset постоянен для всех мазков. **Non-aligned:** каждый мазок начинается от точки источника.
- **Пиксель:** `src(p − offset)` (билинейно) × m·flow.
- **Artistic:** цвет штампа = mean(src под штампом), дальше как Paint.
- **Приёмка:** клон без смещения на чистом слое воспроизводит источник (ΔE < 1).

### 1.19 Режимы инструмента
- Paint, Blend (§1.8), Erase (destination-out с полной маской: head, texture, jitter; color stamp/double/fill/harmony в Erase дают только маску), Clone.
- В Blend и Erase спецэффекты (watercolor, particles, harmony, double, 3D) выключены.

## 2. Этапы реализации
| # | Этап | Состав | Зависит |
|---|---|---|---|
| S0 | Каркас | half-тайлы + `LayerWorkingSet`, `TilePool`, `TileScheduler`, hash-RNG, `PathWalker` (из StrokeResampler), `DabGenerator`, `StrokeContext`, `Brush` + `BrushCodec`, повторный рендер на pen-up, lazy rope, after_smoothing, бенчмарк | M1 |
| S1 | Штамп | bitmap-головы и mip, aspect, rotation modes, AA и dots, jitter, count, color jitter, dynamics (все источники и цели), taper shape, профили, tilt offset | S0 |
| S2 | Текстура | 4 режима, depth, lower limit, gradation, jitter | S1 |
| S3 | Эффекты мазка | glaze, wet edges, adhesion, structure, depth, blend mode кисти, separate_strokes | S1 |
| S4 | Смешивание | Blend-режим, mix_in, dilution, pull, wet, blur, sample lower, ibis Water | S1 |
| S5 | Режимы и фильтры | Erase с полной маской, filter brush (blur, sharpen, glow), Clone | S4 |
| S6 | Спец-1 | color stamp, scatter particles, double, screentone snap, fill brush | S2, S3 |
| S7 | Спец-2 | particles, harmony (`LineRenderer`) | S1 |
| S8 | Акварель | `WetSimulation` | S2, S4 |
| S9 | 3D | tube, ribbon, twisty | S3 |
| S10 | Каталог | `gen_assets.py`, ≈236 пресетов, превью | S1–S9 по мере готовности (категории подключаются, когда готовы их фичи) |
| U1 | Браузер кистей | §4.1 | S0 (codec) |
| U2 | Редактор | §4.2 | S1, U1 |
| U3 | Стилус | §4.3 | S0 |
| U4 | Сохранение состояния | §4.4 | U1 |

Параллельно можно вести S2 ∥ S3 ∥ S4 ∥ S7 и U1 ∥ U3.

## 3. API, формат, хранение
### 3.1 Kotlin (`app.painter.core.stroke`, новые файлы)
```kotlin
// Модель. M1-поля BrushParams остаются на верхнем уровне (совместимость тестов и JSON), новые группы вложены.
@Serializable data class BrushParams(/* M1 поля */, val taperShape: TaperShape = SMOOTH,
    val head: HeadParams = HeadParams(), val jitter: JitterParams = JitterParams(),
    val texture: TextureParams? = null, val paint: PaintParams = PaintParams(),
    val effects: EffectParams = EffectParams(), val special: SpecialParams? = null,
    val dynamics: List<DynamicsLink> = emptyList(), val profiles: Profiles? = null)
@Serializable sealed interface SpecialParams  // Watercolor, Particles, Harmony, Double, ColorStamp, Screentone, Fill, Filter, ThreeD
enum class DynSource { PRESSURE, VELOCITY, TILT, AZIMUTH, TWIST, RANDOM, PROGRESS }
enum class DynTarget { SIZE, FLOW, OPACITY, SCATTER, ANGLE, HARDNESS, TEX_DEPTH, MIX_IN, DILUTION, COLOR_JITTER, BLUR }
@Serializable data class DynamicsLink(val source: DynSource, val target: DynTarget, val curve: Curve)
@Serializable data class Brush(val schema: Int = 1, val id: String, val parentId: String?, val name: String,
    val category: String, val tags: List<String> = emptyList(), val params: BrushParams, val resources: ResourceRefs)
@Serializable data class ResourceRefs(val head: String? = null, val texture: String? = null, val particle: String? = null)

class ImageData(val width: Int, val height: Int, val channels: Int /*1|4*/, val pixels: ByteArray) // декодирует app
class HeadImage(src: ImageData, curve: Curve?, invert: Boolean) { fun level(d: Float): Int; fun sample(level: Int, u: Float, v: Float): Float; fun sampleRgba(level: Int, u: Float, v: Float, out: FloatArray) }
class TextureImage(src: ImageData, p: TextureParams) { fun sample(u: Float, v: Float): Float }
class BrushResources(val head: HeadImage?, val texture: TextureImage?, val particle: HeadImage?)
fun interface ResourceResolver { fun image(ref: String): ImageData }   // "asset:heads/x.png" | "brush:head.png"

data class StrokeContext(val zoom: Float, val viewRotation: Float, val seed: Long,
    val lowerComposite: ((TileKey) -> Tile?)? /*для sample_lower*/, val paperTexture: TextureImage?,
    val clone: CloneSource? = null, val threads: Int = 4)
class PathSample { var x = 0f; var y = 0f; var pressure = 1f; var tilt = 0f; var azimuth = 0f; var twist = 0f
    var timeMs = 0L; var distance = 0f; var dirX = 1f; var dirY = 0f; var velocity = 0f; var taper = 1f; var index = 0 }
fun interface PathSink { fun onSample(s: PathSample): Float /* следующий шаг, px */ }
class PathWalker(params: BrushParams, sink: PathSink) { fun begin(p: InputPoint); fun add(p: InputPoint); fun end() }
class Dab { var x = 0f; var y = 0f; var d = 0f; var angle = 0f; var sx = 1f; var sy = 1f; var flow = 1f; var hardness = 0.8f
    var r = 0f; var g = 0f; var b = 0f; var a = 1f; var texDepth = 0f; var mixIn = 0f; var dilution = 0f; var blur = 0f; var index = 0 }
class DabBatch(capacity: Int = 4096) { val size: Int; operator fun get(i: Int): Dab; fun clear() }
class DabGenerator(brush: Brush, res: BrushResources, ctx: StrokeContext, color: Rgba) { fun emit(s: PathSample, out: DabBatch): Float /*шаг*/ }
interface DabKernel { val orderDependent: Boolean; fun renderTile(t: FloatArray, tx: Int, ty: Int, dab: Dab) }
  // MaskKernel(OVER|GLAZE|ERASE|ALPHA_LOCK), SmudgeKernel, FilterKernel, CloneKernel, TubeKernel, ScreentoneKernel
class TileScheduler(threads: Int) : AutoCloseable { fun run(batch: DabBatch, target: TileAccess, kernel: DabKernel) }
interface StrokeEffect { val halo: Int; fun apply(buffer: TileGrid, keys: Set<TileKey>) } // WetEdges, Double, Glow, Structure
interface PrimitiveGenerator { fun onSample(s: PathSample, out: PrimitiveSink) } // Particles, Harmony, Fill
class LineRenderer { fun line(t: TileAccess, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, r: Float, g: Float, b: Float, a: Float) }
class WetSimulation(width: Int, height: Int) { val active: Boolean; fun deposit(dab: Dab, color: Rgba); fun step(dtMs: Int); fun dryAll(); fun takeCommit(): TilePatch? }
enum class StrokeMode { PAINT, BLEND, ERASE, CLONE }
class StrokeEngine(doc: Document, layerId: String, brush: Brush, res: BrushResources, color: Rgba,
    mode: StrokeMode, ctx: StrokeContext)  // begin/add/end/cancel/takeDirty/buffer/previewOpacity как в M1
class InputCalibration(var pressureCurve: Curve, var maxPressure: Float = 1f) { fun pressure(raw: Float): Float; fun observeStrokePeak(p: Float) }
```
- **Изменения контрактов** (согласовать):
  - конструктор StrokeEngine вместо BrushParams принимает Brush + BrushResources + StrokeContext. Старый конструктор остаётся как обёртка;
  - в InputPoint добавляется `twist: Float = 0f`;
  - StrokeMode расширяется до BLEND и CLONE;
  - `Tile` получает half-хранилище (отдельная задача raster, сразу в начале S0).
- **Кодек:** `core/io/BrushIO`:
  - `decode(json): Brush`, `encode(Brush): String`;
  - `readPbrush(zip: InputStream): Pair<Brush, Map<String, ByteArray>>`, `writePbrush(...)`;
  - `readPack(...)` для `.pbrushes`.

### 3.2 brush.json (schema 1)
```json
{ "schema": 1, "id": "pencil.graphite_hb", "parentId": null, "name": {"ru": "Графит HB", "en": "Graphite HB"},
  "category": "pencils", "tags": ["графит", "graphite", "sketch"],
  "params": { "size": 6, "sizeMax": 60, "opacity": 0.9, "flow": 0.55, "spacing": 0.08, "hardness": 0.9,
    "head": {"source": "bitmap", "aspect": 0, "rotationMode": "stylus-tilt", "depth": 0.2},
    "jitter": {"size": 0.05, "angle": 1.0},
    "texture": {"mode": "canvas-fixed", "depth": 0.7, "scale": 0.5, "lowerLimit": 0.4},
    "dynamics": [ {"source": "PRESSURE", "target": "FLOW", "curve": {"points": [[0,0],[1,1]], "min": 0.1}},
                  {"source": "TILT", "target": "SIZE", "curve": {"points": [[0,0],[1,1]], "min": 0.5}} ] },
  "resources": { "head": "asset:heads/grain_disk_01.png", "texture": "asset:textures/graphite_grain.png" } }
```
- **name:** строка или `{ru, en}`. Пользовательские кисти сохраняют строку.
- **Чтение:** неизвестные поля игнорируются (`ignoreUnknownKeys`), отсутствующие = дефолты. На `schema > 1` действует миграция.
- **id:** у встроенных кистей стабильный slug `<category>.<name>`, у пользовательских UUID.
- **Ссылки на ресурсы:** `asset:` = из APK, `brush:` = файл внутри `.pbrush`. При экспорте `asset:`-ресурсы копируются внутрь архива и переписываются на `brush:`.

### 3.3 Хранение
```
app/src/main/assets/brushes/
  index.json              { schema:1, categories:[{id, name{ru,en}, icon, brushes:[id...]}], favorites:{paint:[], blend:[], erase:[], clone:[]} }
  presets/<category>/<id>.json
  heads/*.png  textures/*.png  particles/*.png
files/brushes/
  library.json            { folders:[{id, name, brushIds}], hidden:[], favorites{mode:[ids]}, order, quick:{id:{size,opacity,flow,hardness}} }
  user/<uuid>/brush.json + *.png        // Created / Imported; правка встроенной = override с parentId
cache/brush_previews/<id>_<paramsHash>.png
```
- Reset встроенной кисти = удалить override и `quick[id]`. Reset пользовательской = вернуть снимок на момент создания (`user/<uuid>/original.json`).

## 4. UI (структура IP, визуал свой: ui-components.md)
### 4.1 Браузер кистей
- **Открытие:** повторный тап по активному режиму (Paint, Blend, Erase, Clone). Popup-панель 360×560 dp у тулбара, на телефоне bottom sheet на 85% высоты.
- **Шапка:** превью текущей кисти (мазок 300×64 dp) и кнопки: Edit, ♡, Options (⋯), Add (+). Ниже поле поиска.
- **Слева:** колонка папок 96 dp.
  - Recent: 20 последних кистей этого проекта (manifest `recentBrushes`);
  - Favorites: свои для каждого режима, предзаполнены из index.json;
  - Created, Imported;
  - 20 категорий каталога;
  - папки пользователя.
- **Справа:** список кистей, строка 56 dp: иконка, имя, превью мазка 160×40 dp.
  - Превью рендерится в фоне (S-кривая, давление 0.2 → 1 → 0.2, текущий цвет, на тёмном и светлом фоне), кеш в cache/brush_previews;
  - тап = выбрать кисть для текущего режима;
  - long-press + drag = перенос в папку.
- **Поиск:** подстрока без учёта регистра по ru/en именам и tags во всех папках. Результат плоским списком с подписью категории.
- **Options кисти:** Rename (у встроенной Restore name), Duplicate (parentId = исходная), Export `.pbrush` (SAF или Share), Reset, Move to folder, Remove from folder, Hide (встроенные) или Delete (пользовательские, с подтверждением).
- **Папки:** создать, переименовать, Export `.pbrushes`, Hide, Delete (кисти возвращаются в исходные контейнеры). Встроенную папку можно восстановить: long-press → Restore.
- **Import:** через `ACTION_OPEN_DOCUMENT` (`.pbrush`, `.pbrushes`) → папка Imported. Пак = новая папка. Head/texture можно импортировать из PNG.

### 4.2 Редактор кисти
- **Открытие:** Edit в браузере, кнопка в Size popup, Shift+Ctrl+B. Планшет: правая панель 440 dp, холст виден. Телефон: полный экран.
- **Шапка:**
  - имя (тап = rename);
  - **пад превью** 160 dp: рисуется пером, кнопка «очистить», по умолчанию эталонная S-кривая; превью без color jitter и smudge (смешивание показывается по фону-полосам 4 цветов);
  - кнопки: Cancel, Save as (копия в Created), Export, Reset, Confirm.
- **Вкладки:**

| Вкладка | Содержимое |
|---|---|
| Stroke | size, size range (min/max), opacity, smoothing, smoothing_speed, after_smoothing, lazy, taper (start, end, shape, size, opacity), profiles (size, flow), glaze, wet edges, adhesion, blend mode + strength, buildup, separate strokes, dynamics size (pressure, velocity, tilt) + tilt offset, size jitter |
| Head | выбор головы (сетка встроенных + Imported + «+»), color, invert, tonal curve, colorize, base color, hardness, aspect, spacing, flow, angle, rotation mode/follow, orient to screen, antialias, dots, depth, structure, dynamics (flow, scatter, angle, hardness), jitter (initial angle; continuous scatter, angle, flow, opacity, spacing; count), scatter particles |
| Texture | выбор, mode, depth, lower limit, scale, stretch, angle, softness, structure, invert, curve, scale with size, gradation, jitter, AA, dynamics depth |
| Paint | mix_in, dilution, wet, pull, blur, blend flow, sample lower (глобально), dynamics (mix_in, dilution, blur), color jitter HSB (initial и continuous) |
| Special | один тип из списка (none, watercolor, particles, harmony, double, color stamp, screentone, fill, filter, 3D) и его параметры |

- **Контролы:** LinearSlider, CurveEditor для каждой связи (заголовок-тоггл вкл/выкл, кнопки пресетов: linear, ease-in, ease-out, S, invert).
- **Live preview:** перерисовка через 100 мс после последнего изменения, на stroke-потоке, отменяемая.
- Изменения из Size и Opacity popup пишутся в `quick` сразу при закрытии попапа (как в IP, без undo, с Reset).

### 4.3 Стилус и ввод (Settings → Stylus, раздел появляется после первого события TOOL_TYPE_STYLUS/ERASER)
- **Калибровка давления** (`InputCalibration`, хранится по устройству: `InputDevice.descriptor`):
  - **Авто** (вкл. по умолчанию): `p_cal = min(1, p_raw / p_max)`;
    - p_max = 95-й перцентиль пиковых давлений последних 20 мазков, clamp [0.4, 1], старт 1.0;
    - p_max обновляется только вверх быстро (сразу) и вниз медленно (EMA 0.1 на мазок);
    - пример: XP-Pen с пиком ≈ 0.78 через 3–5 сильных мазков получает полный диапазон.
  - **Мастер калибровки:** «нажмите 3 раза слабо и 3 раза сильно» → p_min (деф. 0) и p_max фиксируются, авто выключается.
  - **Кривая давления** поверх p_cal: CurveEditor с живой точкой текущего давления. Пресеты Soft (γ 0.6), Normal (linear, дефолт), Firm (γ 1.6). Так же кривые velocity и tilt.
  - **Тогглы:** pressure, velocity, tilt для всех кистей. Weak sensitivity = порог 0.02: ниже штамп не рисуется.
- **Кнопки пера:**
  - читаются `MotionEvent.getButtonState()`: BUTTON_STYLUS_PRIMARY, BUTTON_STYLUS_SECONDARY;
  - для драйверов в мышином режиме при toolType = STYLUS также BUTTON_SECONDARY и BUTTON_TERTIARY;
  - события ACTION_BUTTON_PRESS/RELEASE работают и в hover.
- **Действия кнопок:**

| Действие | Тип |
|---|---|
| none | — |
| eyedropper | удерживать |
| erase | удерживать: временный режим Erase |
| blend | удерживать |
| pan / zoom | удерживать + вести |
| size | удерживать + вести по горизонтали |
| undo, redo | клик |
| previous tool | клик |
| brush browser | клик |
| custom toolbar tool | клик |

  - «Решение»: дефолт primary = eyedropper (удерживать), secondary = undo (клик).
- **Обратная сторона пера** (TOOL_TYPE_ERASER) → режим Erase со своей кистью и размером. При возврате кончика восстанавливается прежний режим.
- **Палец при активном стилусе:** Draw, Navigate only (дефолт после первого касания пером), Eyedropper, Blend, Erase.
- **Palm rejection:**
  - Off;
  - Stylus only (дефолт, = «палец не рисует»);
  - Hover reject: касания пальцем игнорируются, пока перо в hover и 300 мс после.
  - Всегда: ACTION_CANCEL и FLAG_CANCELED откатывают мазок. Касание с `getTouchMajor() > 40 dp` = ладонь.
- **Прочее:** touch offset (dx, dy ±20 px), курсор (none, контур головы, crosshair в hover), предсказание точек (вкл), отбрасывать stylus-мазок без давления (уже есть).

### 4.4 Сохранение состояния инструментов
- **Файл** `files/settings/tool_state.json`. Запись с debounce 500 мс, в `onStop` сразу.
  - Содержимое: `{schema, mode, brushByMode{paint, blend, erase, clone}, color, prevColor, recentColors[10], paletteId, blendPanel{blur, pull, flow, sampleLower}, eyedropper{sampleAll}, clone{sourceLayer, aligned}}`.
  - Размер, opacity, flow, hardness хранятся в `library.json → quick[brushId]` (у каждой кисти свои).
- **Стилус:** `files/settings/input.json`: калибровка по устройствам, кнопки, palm mode, finger action, кривые.
- **При старте Studio:** восстановить всё. Если кисть удалена, взять дефолтную кисть режима из index.json.
- **Проект:** `recentBrushes` в manifest.

## 5. Каталог и ассеты: `tools/gen_assets.py`
- **Запуск:** `.\.venv\Scripts\python tools\gen_assets.py [--only heads|textures|presets] [--check]`. Зависимости: numpy, Pillow (`tools/requirements.txt`).
- **Детерминизм:** `np.random.default_rng(seed)`, seed = crc32(имя ассета). `--check` перегенерирует во временную папку и сравнивает байты: CI-проверка, что ассеты воспроизводимы.
- **Тайлинг:** все текстуры тайлятся. Синтез спектральный через FFT: белый шум × фильтр 1/f^β, затем обратное FFT. Периодичность получается по построению. Ворли-шум с периодическими расстояниями.
- **Формат:**
  - головы 256² (крупные щетины 512²), gray 8-bit: белое = покрытие (мы храним white-on-black, invert не нужен);
  - цветные штампы RGBA 256²;
  - текстуры 512² gray;
  - бюджет всех ассетов ≤ 8 МБ в APK.
- **Отрисовка фигур:** через Pillow ImageDraw с суперсэмплингом 4× и даунскейлом LANCZOS.

| Вид | Кол-во | Генерация |
|---|---|---|
| Круги и базовые | 6 | soft disk (Гаусс), square, rounded square, ellipse-chisel, ring (боке: край ярче), triangle |
| Зернистый диск (карандаш, уголь) | 6 | disk × (fbm β = 0.5 > порог), анизотропия для угля (растяжение шума 1:4) |
| Мел, пастель, мелок | 5 | disk с рваным краем (радиус + fbm по углу) × крупное зерно с порогом |
| Щетина | 8 | N = 12–80 штрихов-капсул со случайной шириной, длиной и альфой; варианты flat, round, fan (веер), rake (гребёнка), dry (пропуски) |
| Мастихин, валик | 3 | трапеция с рёбрами (sin-профиль), прямоугольник с полосами |
| Губки | 3 | Ворли F1 с порогом, 3 масштаба (coarse, kitchen, natural) |
| Брызги и спрей | 6 | диски со степенным распределением радиусов (α = 2.2) + 1–3 подтёка; спрей = 400 точек Гаусс |
| Частицы | 5 | soft dot, hard dot, spark (4-лучевая звезда), droplet, smoke (fbm-капля) |
| Дизайн | 8 | dash, dot, arrow, chevron, chain link, lace motif (кольца по окружности), stitch (крестик), ribbon-сегмент |
| Штампы цветные | 14 | звезда, ромб, сердце, цветок, лист, снежинка, нота, облако, капля, пузырь, бабочка, трава, роза (слои лепестков-полигонов с градиентом), ягода. base_color в пресете = средний hue |
| Акварель | 3 | salt blossom (радиальные лучи + шум), circle stain (кольцо с тёмным краем), floral stencil |
| Текстуры бумаги | 6 | smooth, cold press, rough, watercolor (fbm + Ворли), kraft (волокна), rice paper (длинные волокна) |
| Текстуры зерна | 6 | graphite grain (высокочастотный β = 0.3 + анизотропия), charcoal, chalk, crayon wax, sand, stucco |
| Ткани | 5 | canvas weave (sin-переплетение + шум), linen, denim (саржа 45°), corduroy (полосы), cloth fiber |
| Штриховка и графика | 6 | hatching, crosshatch, scratch (случайные отрезки), grunge (fbm с порогами), debris, newspaper (полутон + шум) |
| Узоры | 7 | brick, wood (кольца `sin(r·k + fbm)`), pebbles (Ворли F2 − F1), isometric grid, square grid, checker, velvet |

- **Пресеты:** `tools/catalog/*.py`, по файлу на категорию. Таблица dict: базовый шаблон («pencil», «ink», «paint», «airbrush»…) + overrides. Скрипт пишет `presets/<cat>/<id>.json` и `index.json`. Ручной JSON не правится.
  - Каждый пресет обязан иметь ru/en имя (свои, не из оригиналов), tags, ссылки только на сгенерированные ассеты.
- **Векторные перья (кат. 18):** в M2 ставятся как растровые аналоги с тегом `vector-later`. Аутлайн-ластик (кат. 19) в M2 = обычный жёсткий ластик.
- **Порядок наполнения:**
  1. после S1–S3: карандаши, ручки, каллиграфия, маркеры, ластики, мел, уголь, дизайн, текстуры;
  2. после S4–S5: краски, смешивание;
  3. после S6–S9: остальные категории.

## 6. Производительность и gate для GPU
- **Бенчмарк** `core/src/test/.../StrokeBench` (тег `bench`, не в обычном прогоне) + экранный счётчик в debug-сборке.
- **Сценарии:**
  - A: 1000 px, size 20, spacing 10%;
  - B: size 300, soft, spacing 5%;
  - C: size 500, bitmap + texture, spacing 8%;
  - D: smudge, size 200;
  - E: watercolor, 16 тайлов.
- **Цели** на эталонном планшете (XP-Pen MDP1221 / эмулятор ×0.5):
  - CPU на кадр ≤ 8 мс для A–C при скорости пера 2000 px/с;
  - D ≤ 12 мс;
  - E: тик ≤ 8 мс.
- **Gate:** если сценарий B или C провален после оптимизаций (SIMD-дружелюбные циклы, LUT, bbox-кламп), заводится задача «GPU-штампы для MaskKernel». Остальные ядра остаются на CPU.

## 7. Приёмка M2 (кисти)
- [ ] Все ≈236 пресетов грузятся. Каждый рисует непустой мазок на эталонной траектории без исключений (автотест на JVM + прогон на устройстве).
- [ ] Каждая группа §1 проходит свою приёмку.
- [ ] Детерминизм: 1 и 4 потока, ввод одной пачкой и по точке дают бит-в-бит одинаковый буфер.
- [ ] Undo откатывает любой мазок, включая smudge, filter, clone и акварель после высыхания.
- [ ] Бенчмарк §6 в целях или заведена gate-задача.
- [ ] Браузер: поиск, избранное по режимам, recent проекта, папки, hide/restore, duplicate, export/import `.pbrush` и `.pbrushes` (круговой обмен без потерь).
- [ ] Редактор: все параметры §4.2 меняют превью. Reset возвращает заводские значения, Save as создаёт копию с parentId.
- [ ] XP-Pen: после 5 сильных мазков полный нажим даёт p_cal ≥ 0.98.
- [ ] Кнопки пера и обратная сторона-ластик работают по настройке. Palm rejection не даёт ни одного мазка ладонью за 2 минуты рисования.
- [ ] После force-stop восстанавливаются режим, кисти по режимам, размеры, цвет, recent colors.
- [ ] `gen_assets.py --check` проходит. Ассеты ≤ 8 МБ. В репозитории нет чужих ассетов и имён.

## 8. JVM-тесты (`core/src/test/kotlin/app/painter/core/stroke/…`, `…/io/`)
| Тест | Проверка |
|---|---|
| HashRngTest | равномерность (χ², 10⁴), независимость каналов, стабильность значений |
| PathWalkerTest | M1-тесты резамплера зелёные; шаг из sink; direction и velocity на прямой и окружности |
| DeterminismTest | 1 против 4 потоков, пачка против точек → равные буферы |
| HeadImageTest | mip-уровни; поворот на 90° точный; aspect; AA off даёт 0/1 |
| DynamicsTest | каждый источник × цель; произведение связей; кривая y = 1 нейтральна; калибровка 0.78 → 1 |
| JitterScatterTest | jitter 0 = M1; среднее смещение ≈ 0; deviation смещает радиусы к центру и к краю |
| TextureTest | 4 режима UV; тайлинг без шва; lower limit задаёт потолок; gradation |
| TaperProfileTest | linear против smooth; профиль на 1000 px; повторный рендер короткого мазка |
| EffectsTest | glaze без накопления; wet edges край > центра; adhesion порог; blend mode multiply |
| SmudgeTest | градиент на границе; pull ↑ даёт смаз длиннее; dilution 100 без цвета кисти; wet и альфа |
| WatercolorTest | масса пигмента сохраняется при диффузии (±1%); высыхание ≤ 4 с; фиксация патча |
| ParticlesTest | детерминизм; overshoot увеличивает разлёт |
| HarmonyTest | все 11 вариантов рисуют; число линий ≤ k на точку |
| DoubleTest | толщина контура ±0.5 px |
| ColorStampTest | base = cur → без изменений; S и V не растут |
| ScreentoneTest | центры точек на узлах; площадь ∝ покрытию |
| FillBrushTest | even-odd на восьмёрке; AA край |
| FilterBrushTest | strength 0 = no-op; blur только под маской; sharpen повышает контраст |
| CloneTest | клон без смещения воспроизводит источник; aligned и non-aligned |
| HalfTileTest | float → half → float ошибка ≤ 1e-3 на [0,1]; working set коммитит и откатывает |
| BrushIOTest | round-trip JSON и `.pbrush`; неизвестные поля игнорируются; asset: → brush: при экспорте |
| CatalogLoadTest | все presets/*.json парсятся, ресурсы существуют, id уникальны, у каждого ru/en имя |
