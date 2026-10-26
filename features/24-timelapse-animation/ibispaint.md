# ibisPaint: таймлапс и анимация

Источник: ibispaint.com/lecture №20, 22, 182, 188, 210, 211.

## Таймлапс
- Пишется всегда (история IPV). Playback в галерее.
- **Save Artwork at This Point**: из любой точки таймлапса → новый артворк.
- Экспорт видео в фотоальбом и соцсети. YouTube-аплоад.
- Время рисования показывается.

## Animation mode (холст Animation)
- **FPS** при создании.
- **Animation bar**: + frame, drag = порядок, тап = frame settings (**Duration**, Duplicate, Delete).
- **Onion skin**:
  - Display Mode (прошлые/будущие);
  - Number of Onion Skins;
  - First Opacity, Last Opacity (результат = произведение);
  - Color.
- Play: **Loop / Ping pong / One-shot**.
- Save Animation Video.

## Video mode (конвертация из Animation, необратимо)
- 60 fps фиксировано. Clip с duration 0 удаляется. Ping-pong недоступен.
- Timeline: playhead в центре, time ruler, pinch или Ctrl+колесо = zoom, drag ruler = высота области.
- Tracks:
  - **Drawing track** (клипы встык, без зазоров; пустой клип = пауза);
  - **Audio track** (клипы свободно);
  - минимум 1 drawing track.
- Clip:
  - Add (после выбранного, та же длина);
  - long-press drag (в пределах треков того же типа);
  - handle = длительность.
  - Drawing clip settings: Duration, Frame Count.
  - Audio clip settings: Volume, Duplicate, **Reselect** (relink), Delete.
- Audio:
  - импорт аудио или аудиодорожки из видео;
  - range > файла = зацикливание;
  - Track: Mute, Volume, Delete;
  - **Snap Clip**;
  - общая Volume;
  - итоговая громкость = clip + track + master.
  - Broken link warning.
- Экспорт MOV/MP4, проект IPVZ.

## Для объединённой реализации
- У IP анимации нет. Берём модель ibis целиком, UI делаем в стиле IP: нижний timeline-виджет + mini-toolbar.
