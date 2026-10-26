# Стоковый каталог кистей (цель M2)

Цель: функциональное покрытие стоковых кистей **Infinite Painter** (168 шт., features/03-brushes/infinite-painter.md) и **ibisPaint** (стоковый набор ≈142 типа по описанию в сторе, известные из уроков позиции). Имена, головы, текстуры и параметры свои. Ассеты генерируются `tools/gen_assets.py`.

Колонка «Движок» = какие фичи brush-engine нужны. Всё это переносится в M2.

| # | Категория | Кол-во | Что покрываем (функционально) | Движок |
|---|---|---|---|---|
| 1 | Карандаши | 16 | графит разной мягкости (HB…6B), технический, грубый, восковой, концепт-карандаш, «старый»; шейдеры ткань и волокно, штриховка, перекрёстная штриховка (tone), процарапывание. ibis: карандаш, цветной карандаш | текстура (canvas-fixed), pressure→flow, tilt→size/angle, depth |
| 2 | Ручки и тушь | 24 | ровная, мягкая, тонкая, манга-перо (тапер), чернильные с текстурой (старое, грубое, сухое), протекающая, гелевая, перьевая, файнлайнер, подпись, наклонное перо, рваная тушь, размывка тушью (watercolor), скоростные (velocity→size), пиксельное перо 1 px. ibis: dip pen hard/soft, felt tip hard/soft, digital pen, wedge (клин), flash (вспышка) | taper, velocity, aspect/angle, particles, watercolor |
| 3 | Каллиграфия | 10 | монолиния, овальное перо, мягкий тапер, флористический пестик, вывесочный мел, раздвоенное перо, приподнятый край, правый наклон; ibis: плоское перо, ленточное | aspect + fixed angle, tilt, azimuth |
| 4 | Маркеры | 12 | сухой, скошенный, широкий, глазурный, мокрый, бесцветный блендер, скетч-маркер, круглый, кисть-маркер, хайлайтер; ibis: маркер, outline-маркер | glaze strokes, wet edges, blend mode multiply, dilution |
| 5 | Краски | 30 | мазок, толстый угловой, глиняный валик, сухая кисть, сухая тушь, кошачий язык, плоская масляная, глазурный акрил, гуашь, тушь-акварель, мастихин нагруженный и обычный, старые серии (гуашь, масло, лак, глазурь), валик, полосатое масло, штукатурный валик (сухой и мокрый), толстый кошачий язык, толстое масло, грабли, тонкие волоски, брызги (velocity), мокрая гуашь; ibis: oil, fan brush (веер), flat brush (real), round | mix-in, dilution, pull, wet, structure (импасто), depth (щетина), texture warp |
| 6 | Акварель | 12 | мокрая бумага, лёгкая и тяжёлая заливка, сильное растекание, «почти вода», химический ожог, соляной цветок, круглые пятна, флористический трафарет; ibis: watercolor (bleed), watercolor (wet), watercolor (dry) | **watercolor sim** (bleed, glaze, mix-in, dryout), ibis Water type |
| 7 | Аэрографы и спреи | 15 | брызги крови и воды, крапинки, капли, пескоструй, частицы-брызги, облака, баллончик, аэрограф мягкий, средний, жёсткий, жёсткий круглый; ibis: airbrush normal/triangle, spray | scatter, particles (ibis scatter), jitter |
| 8 | Мел и пастель | 9 | мел, треснувший мел, мел-шейдер, школьный мел, пастель круглая и квадратная, мокрая пастель; ibis: crayon (мелок), oil pastel | texture depth, lower limit |
| 9 | Уголь | 9 | жест (виноградная лоза), угольный карандаш, мягкий, жёсткий, палочка, грубый, ива; ibis: charcoal thin/thick | texture, depth, tilt |
| 10 | Дизайн | 10 | вырезка, длинный пунктир, точки-дудл, стрелка потока, стрелки, цепь стрелок; ibis: lace (кружево), chain (цепь), stitch (строчка), ribbon | spacing + dots, rotation tangent, color stamp, array billboard |
| 11 | Заливочные | 6 | сплошная, скринтон, мусор, гранж, царапины, шахматка | fill brush + pattern |
| 12 | Свечение | 12 | световое перо, неон, искры (particles), мягкое свечение, боке, свечение сцены, горение, огненная лента, световые волоски, звёздная пыль; ibis: glow pen, sparkle | blend add/screen, double type (неон = ядро + ореол), particles |
| 13 | Разное и 3D | 15 | 3D-скрученная, трубка, лента; газетный перенос; каракули; губки (сухая, кухонная, натуральная); дым; аутлайнер; штампы еды, роз, звёзд; ibis: outline (fade), double outline | 3D shading stamp, **double type**, color stamps |
| 14 | Скринтоны | 9 | штрих 45°, деним, точка 45°, полутон, тон-шейдер, вертикальные и горизонтальные линии; ibis: dot/line tone brush | screentone snap |
| 15 | Текстуры | 10 | бэксплэш, кирпич, вельвет, глазурь, галька, дерево, изометрическая и стандартная сетка; ibis: texture stamp, cloth | texture + color head |
| 16 | Гармония (процедурные) | 9 | скетч, тушёвка, длинный и короткий мех, ленты, кривые, сетка, круги, квадраты | **harmony** |
| 17 | Штампы и цветные (ibis) | 12 | звезда, светлый ромб, сердце, цветок, лист, снежинка, нота, облачко, капля, пузырь, бабочка, трава | **color stamp** (base color hue-shift), jitter angle/size, scatter |
| 18 | Векторные перья (ibis) | 2 | векторное перо, векторный маркер | → M4 (vector layer). В M2 ставятся как растровые аналоги |
| 19 | Ластики | 6 | мягкий, жёсткий, текстурный, пиксельный, по контуру (stroke-cut на векторе в M4), кисть-ластик крупный | erase mode |
| 20 | Смешивание и размытие | 8 | палец, мягкий палец, размытие, мокрый блендер, бесцветный, мастихин-блендер, ibis smudge (finger), ibis blur | blend mode, filter brush (blur) |
| — | **Итого** | **≈236** | | |

## Фичи движка, которые переезжают в M2
Watercolor sim, particles, scatter-particles, harmony, double type, color stamp (base hue-shift), 3D shading stamp, filter brushes (blur, glow), screentone snap, fill brushes, texture modes warp/rotate/moving, structure/depth, glaze, wet edges, adhesion, velocity/tilt/azimuth dynamics, size/flow profiles.

## Открыто
- Точный стоковый список ibisPaint публично не опубликован. Пункты ibis выше взяты из уроков (Dip Pen, Felt Tip, Digital Pen, Airbrush, Wedge, Flash, Outline (Fade), Light Diamond, Star, Watercolor, Flat Brush, Charcoal, Crayon, Lace, Chain, Vector Dip/Felt Tip). Если есть доступ к приложению, скриншоты окна кистей (по категориям) позволят закрыть список 1:1.
