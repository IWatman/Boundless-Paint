# ibisPaint: импорт, экспорт, форматы

Источник: ibispaint.com/lecture №21, 22, 28, 32, 33, 84, 119, 137, 188, 205, 206, 207, 210.

## Экспорт изображения (Save Image)
- File Type: **PNG** (Transparent toggle), **JPEG**, **PSD** (Preserved Layers / Flattened).
- **Super-resolution ×2** (ИИ-апскейл при экспорте, PNG).
- **AI Disturbance** (шум против дообучения ИИ, слайдер интенсивности).
- **Color Space**:
  - RGB;
  - **CMYK** (PSD);
  - **Grayscale 8-bit**;
  - **Monochrome 1-bit**.
- CMYK:
  - **Color Profile** (загружаемые профили типографий);
  - **Rendering Intent** (Perceptual / Relative Colorimetric [дефолт] / Saturation / Absolute);
  - **Black Point Compensation** (выкл при Absolute).
- **Save Layer as Transparent PNG** (слой или папка).

## Проектные форматы
- **IPV**: артворк + история таймлапса, кросс-платформенный.
- **IPVZ**: проект с анимацией и аудио.
- **ipcfg**: бэкап настроек (кисти, палитры).
- **CLIP**: аплоад в Clip Studio cloud (структура слоёв; текст и тон растеризуются).
- **PSD import**: открытие PSD как артворк.
- Кисти: **QR-код** PNG.

## Видео
- Таймлапс в фотоальбом. YouTube upload.
- Анимация: **MOV, MP4**. Онлайн-галерея режет звук.

## Перенос
- IPV через: облако ibis, Dropbox и др., AirDrop, Finder, Quick Share, USB/MTP.

## Что добавить к IP
- Экспорт CMYK PSD с ICC-профилем, intent, BPC.
- Grayscale 8 / 1-bit экспорт.
- PSD Flattened.
- Super-resolution (опционально, локальная модель).
- AI-Disturbance (опционально, нужна своя реализация adversarial noise).
- Экспорт отдельного слоя/папки в PNG.
- Бэкап настроек.
- Видео-форматы анимации.
- Свои форматы: проект (ZIP-контейнер как у IP PNTR), кисть, пак, палитра, настройки.
- Импорт и экспорт **OpenRaster (.ora)** и **PSD** для совместимости. Решение за нами, у оригиналов ORA нет.
