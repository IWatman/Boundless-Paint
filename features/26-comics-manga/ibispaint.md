# ibisPaint: манга и комикс

Источник: ibispaint.com/lecture №29, 38, 63, 66, 157, 170, 178, 184–187.

## Manga Manuscript (тип холста)
- New Canvas → Manga Manuscript → Manuscript Type: **Fanzine**, **Manga**, **American Comicbook** (+ размеры A5/B5/B4… и dpi). Custom = свои значения (предупреждение о непригодности для типографии).
- Направляющие:
  - **Inner frame** (синий, safe area для текста и лиц);
  - **Final size** (зелёный, обрез);
  - **Bleed** (красный, вылеты).
- Settings (View → Manga Manuscript Settings):
  - единицы mm/in;
  - Manuscript W/H;
  - Final W/H;
  - Bleed width;
  - Inner frame W/H;
  - Inner offset X/Y;
  - Resolution;
  - **Color mode**.
- Показ направляющих: View → Manga Manuscript.
- Формула: px = inch × dpi, mm/25.4.
- Рекомендации:
  - 350 dpi для цвета, 600+ для серого и Ч/Б;
  - dpi кратные 1200/N (600, 400, 300) против муара;
  - не масштабировать холст;
  - тон не делать серым;
  - **нерастеризованный скринтон сохраняет размер точки при ресайзе**.

## Color Mode холста (№69/178)
- Color.
- **Gray Scale** (256 оттенков).
- **Black & White** (1-bit: серое только через скринтон, без антиалиаса точек).
- Экспорт в CMYK (№207) см. 23.

## Frame Divider tool
- Add Frame:
  - Horizontal Space, Vertical Space (поля);
  - Frame Thickness;
  - Border Color.
- Edit (до деления): **Horizontal Interval / Vertical Interval** (гаттеры).
- Деление: свайп по линии раскадровки.
- Select (рамкой, мультивыбор), drag ручек = форма.
- Edit выбранного: Border Color, Border Thickness, **Fill Color** (+opacity) панели.

## Инструменты манги (№185)
- Screentone layer: blend «Screen Tone», паттерны Dot L6…L16 (L12/L16 для печати), плотность = яркость цвета.
- Filters Draw: Radial Line, Speed Line (фокус-ручка + слайдеры) с выделением по панели (Magic Wand по слою рамок).
- **Outline pen / balloon pen**: кисть типа Double, контур ≈5 px.
- Jagged-баллоны: Elliptical ruler + Wedge/Flash brush. Shape tool → Speech Bubble.
- Text: **вертикальный / горизонтальный**, Left/Center/Right.
- Filter Artistic → **Manga Background** (фото → манга-фон: Contrast, Edge Darkness, Screen Tone Size…).
- Perspective Array Ruler (1/2/3pt, Initial Position, Face/Vertex knobs, Array Selection), Array Ruler.

## Материалы (Material tool)
- Категории:
  - Tracing Sketches (Whole Body, Mini, Partial, Building);
  - **Manga Balloon**;
  - Background (Anime Background Gray и др.);
  - Cloth Patterns (Color);
  - Screen tones.
- Поиск по имени. Материал кладётся слоем, ресайз → clip/mesh/multiply.

## Публикация
- Online Gallery → Comic:
  - banner 1380×440;
  - icon 1024×768;
  - story icon 150×150;
  - ≥8 страниц на главу;
  - формат номера главы;
  - категория;
  - язык;
  - статус Continue/Complete.
- Печать: имя «Title + номер», Share в нужном формате.
- Multiple pages comic в Online Gallery (№157).

## Что добавить к IP (у IP есть только Panels)
- Тип проекта «Manuscript» с bleed/trim/safe и пресетами.
- Color mode холста (Gray/BW) как реальный режим, не только proofing.
- Frame Divider: поля, гаттеры H/V, fill панели, мультивыбор (слить с IP Panels).
- Вертикальный текст.
- Векторный скринтон как свойство слоя (размер точки в физических единицах).
- Speed/Radial line фильтры.
- Manga Background фильтр.
- Баллоны (шейп + библиотека).
- Многостраничность? В ibis её нет (одна страница = одно произведение + публикация глав). В IP тоже нет. **Решение за нами.**
