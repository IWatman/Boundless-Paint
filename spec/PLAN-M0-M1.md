# План реализации M0–M1

Цель M1: на Android-планшете можно создать холст, рисовать кистью и ластиком со стилусом (давление), работать со слоями, менять цвет, делать undo, сохранять, открывать из галереи и экспортировать PNG/JPG.

## 0. Окружение (всё внутри `Painter/`)
| Шаг | Действие | Размер |
|---|---|---|
| 0.1 | `.venv` (готово) | — |
| 0.2 | Gradle 8.11.1 → `.tools/gradle-8.11.1/`, `GRADLE_USER_HOME=.tools/gradle` | ~140 MB + кэш ~1.5 GB |
| 0.3 | Android SDK для сборки: используем существующий `%LOCALAPPDATA%\Android\Sdk` (platform 34, build-tools 35) только на чтение. Путь в `local.properties` | 0 |
| 0.4 | Эмулятор: отдельный SDK root `.tools/android-sdk` (cmdline-tools → emulator + system-images;android-34;google_apis;x86_64), AVD в `.tools/avd` (`ANDROID_AVD_HOME`). Планшетный профиль 2560×1600 | ~4 GB |
| 0.5 | Скрипт `tools/env.ps1`: выставляет переменные окружения для сессии | — |
| 0.6 | `git init` + `.gitignore` (.venv, .tools, build). Коммиты только по твоей команде | — |
- Альтернатива 0.4: твой Android-планшет по USB или Wi-Fi debugging, тогда эмулятор не нужен.

## 1. Каркас (M0)
| Шаг | Результат | Проверка |
|---|---|---|
| 1.1 | `settings.gradle.kts`, version catalog, модули `:core` (kotlin-jvm), `:app` (android). AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, compileSdk/targetSdk 34, minSdk 29 | `gradlew :core:test`, `:app:assembleDebug` зелёные |
| 1.2 | `:app`: MainActivity, Compose-навигация Home ↔ Studio, тема (ui-components.md) | APK запускается |
| 1.3 | Studio-каркас: top bar (Home, Tools ×2, Layers, Options), main toolbar, undo/redo, пустая layer panel | визуальная проверка |
| 1.4 | `CanvasView : GLSurfaceView` (GLES 3.0+), рендер шахматки и прямоугольника холста, матрица вида (pan, zoom, rotate, fit) | жесты работают, 60 fps |

## 2. Ядро (`:core`, JVM-тестируемо)
| Шаг | Компонент | Тесты |
|---|---|---|
| 2.1 | `Half` (float16 ↔ float), `Tile` (256², FloatArray RGBA premult на CPU), `TileGrid` (sparse map) | round-trip, границы |
| 2.2 | `Document`, `RasterLayer`, `GroupNode` (только root), Paper | структура |
| 2.3 | `BlendModes` CPU-референс (12 режимов M1, все 33 в M2) | таблица формул |
| 2.4 | `Command`, `History` (снапшоты тайлов, лимит) | undo/redo хэши |
| 2.5 | `InputPoint`, `Stabilizer` (EMA), `StrokeResampler` (spacing, taper) | spacing ±1%, шум ↓ |
| 2.6 | `BrushParams` (подмножество M1), `DabGenerator` (size/flow от давления) | значения кривых |
| 2.7 | `CpuDabRenderer` (референс: штамп в tile-буфер) + `StrokeCompositor` (cap opacity, erase) | golden-PNG мазка |
| 2.8 | `ProjectIO` (директория проекта: manifest.json + tiles LZ4), экспорт PNG через абстракцию encoder | save→load хэши |
| 2.9 | Цвет: HSB/RGB/hex конверсии | round-trip |

## 3. GPU-рендер (`:app/render`)
| Шаг | Результат |
|---|---|
| 3.1 | Пул текстур RGBA16F 256², загрузка dirty-тайлов из `:core` |
| 3.2 | Композит стека слоёв (шейдер blend 12 режимов, opacity, clip, alpha lock) во framebuffer холста → экран с матрицей вида |
| 3.3 | Рендер штампов на GPU (instanced quads) в stroke buffer. Композит stroke buffer поверх активного слоя в превью. Pen-up: запекание в тайлы и readback в `:core` для истории |
| 3.4 | Обработка `MotionEvent`: stylus/finger, история точек, pressure, predicted (androidx.input), жесты 2/3 пальца |

## 4. UI M1
| Шаг | Результат |
|---|---|
| 4.1 | Main toolbar: режимы, virtual sliders size и opacity, попапы, HUD |
| 4.2 | Color panel (wheel, sliders, hex, палитра, recent) |
| 4.3 | Layer panel (T8) |
| 4.4 | Home / New canvas / Projects gallery (T9) |
| 4.5 | Options: Save, Export PNG/JPG (T10), Settings-заглушка (pressure curve, palm mode) |

## 5. Приёмка M1
- Все JVM-тесты зелёные.
- APK собирается и ставится на эмулятор или устройство.
- Ручной чек-лист из tools/m0-m1-tools.md: каждый пункт «Приёмка».
- Отчёт тебе: что работает, что нет, скриншоты с эмулятора.

## Риски
- Эмулятор плохо держит GLES 3 + RGBA16F рендер-таргеты. Fallback: RGBA8 + dithering на эмуляторе, half-float на устройстве.
- Стилус и давление на эмуляторе не проверить, нужно реальное устройство.
- Производительность readback тайлов после мазка: делаем асинхронно (PBO).
