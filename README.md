# Painter: материалы для clean-room реализации

Цель: свой растровый редактор. Функционал = **Infinite Painter ∪ ibisPaint**. Интерфейс = **структура Infinite Painter** (визуал свой). Без монетизации, всё бесплатно и открыто.

## Структура
```
00-clean-room/
  PROCESS.md        правила clean-room: роли, что можно и что нельзя
  SOURCES.md        откуда что взято
features/           сырой функционал по областям, своими словами
  01-projects-gallery/       проекты, галерея, версии, типы холстов
  02-workspace-ui-gestures/  Studio, тулбары, меню, жесты
  03-brushes/                библиотека кистей, управление, создание
  04-brush-engine-settings/  все параметры движка кисти, стабилизатор
  05-color/                  цвет, пикеры, палитры, пипетка, конвейер
  06-layers/                 слои, группы, маски, clipping, спецслои, вектор
  07-blend-modes/            режимы наложения (объединённый список 33)
  08-text/                   текст
  09-fill/                   заливки, градиенты, паттерн-заливка
  10-guides-rulers/          гайды и линейки
  11-shapes/                 фигуры, распознавание
  12-symmetry/               симметрия
  13-perspective-grid/       перспектива
  14-view-navigation/        вид, навигатор, референсы, сетка
  15-selection/              выделения
  16-transform/              трансформации
  17-adjustments/            цветокоррекция, кривые
  18-filters/                фильтры (~110 объединённых)
  19-liquify-warp/           пластика, mesh
  20-cloning/                клонирование
  21-patterns-materials-tones/ паттерн-инструменты, материалы, скринтоны
  22-canvas-ops/             crop, resize, straighten, панели
  23-import-export-formats/  импорт, экспорт, форматы
  24-timelapse-animation/    таймлапс, анимация, видео
  25-input-stylus-settings/  настройки, стилус, клавиатура, UI-контролы
  26-comics-manga/           манга и комикс
  27-cloud-account-ai-online/ онлайн, аккаунт, ИИ
  (в каждой папке: infinite-painter.md + ibispaint.md)
spec/
  UI-SHELL.md       UI-каркас по модели IP: куда что встраивается
  FEATURE-MAP.md    объединённая карта функций → место в UI → приоритет P0–P3
  GAPS.md           все открытые вопросы → ответы (закрыто)
  DECISIONS.md      архитектурные решения (Android, Kotlin+Compose+GLES)
  brush-engine.md   единая модель кисти
  document-model.md модель документа, история
  blend-and-color.md формулы 33 режимов, цвет
  file-formats.md   свои форматы, PSD
  filters.md        все фильтры с нашими параметрами
  ui-components.md  дизайн-система
  assets.md         свои кисти, шрифты, палитры
  test-corpus.md    тесты и приёмка
  tools/            спеки инструментов по вехам
  ROADMAP.md        вехи M0–M5
  PLAN-M0-M1.md     пошаговый план первой вехи
```

## Как читать
1. `spec/UI-SHELL.md`: каркас.
2. `spec/FEATURE-MAP.md`: что строим и в каком порядке.
3. Детали области в `features/NN-*/`. В ibispaint.md в конце каждого файла раздел «Что добавить к IP».
4. `spec/GAPS.md`: следующий шаг.
