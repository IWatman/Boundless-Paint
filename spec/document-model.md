# Модель документа

## Project
```
Project {
  id: UUID, name, created, modified, type: regular | pattern | challenge | animation | manuscript
  width, height (px), dpi, colorMode: rgb | gray | bw
  paper: { color, textureId?, texDepth, texOpacity, texScale, visible }
  root: GroupNode (стек слоёв; для animation: frames[] каждый со своим root + общий bgLayer?)
  manuscript?: { trim, bleed, safe, offset, unit }
  pattern?: { tileW, tileH }  // холст = 2× тайл
  guides: { symmetry?, perspective?, grid?, ruler? } // сохраняются
  references: [ { id, imageRef, transform, visible } ]
  palette collection, recent brushes, recent colors
  timelapse: { enabled, quality, followNav, chunks[] }
  stats: { strokes, timeMs, distancePx, undos }
}
```

## Узлы слоёв
```
Node { id, name, visible, opacity, blendMode, clipped(to below), hideInRecording, locked, mask?: Mask }
RasterLayer : Node { tiles: TileGrid(RGBA16F, 256²), alphaLock, virtualTransform?: {source: TileGrid, xform: Affine|Mesh} }
VectorLayer : Node { shapes: [VectorShape] }          // P2
TextLayer   : Node { text, font, size, color, outline{mode,width,color}, align, case, spacing{letter,word,line}, justify, vertical, background{color,opacity,marginX,marginY}, frame{x,y,w,rot} }
PanelsLayer : Node { frame{w,h,margin}, gutter{w, h}, color, dividers:[Segment], fills:{panelId: color} }
AdjustmentLayer : Node { kind, params }               // любая коррекция или фильтр
FilterLayer = AdjustmentLayer (kind = filter.*)
ScreentoneProp (на RasterLayer): { pattern: dot|line|..., lpi, angle, physical: bool }  // P2
GroupNode : Node { children: [Node], passthrough: bool (blendMode=passthrough), expanded }
Mask { tiles: TileGrid(R8), enabled, linked, strength }
```
- Paper не узел, а свойство проекта (нельзя рисовать и двигать).
- Clipping: узел с `clipped=true` обрезается альфой ближайшего нижнего не-clipped соседа (база). Adjustment-база недопустима.
- Порядок композита: снизу вверх. Группа с normal-режимом рендерится в offscreen, passthrough рендерится инлайн.

## Тайлы
- 256×256, RGBA16F premultiplied. Пустой тайл = null (не хранится).
- CPU-копия хранится только для сохранения и undo, в сжатом виде (LZ4 на half-float).
- GPU: текстурный пул, LRU-выгрузка при нехватке памяти.
- Лимит памяти: показывать «макс. слоёв» = budget / (w·h·8 байт · коэффициент заполненности 0.5).

## Selection
- Отдельная маска R8 TileGrid уровня документа + флаг active. Режимы set/add/subtract/intersect.

## История (undo)
- Command pattern: `apply()` / `revert()`.
- Пиксельные команды хранят до-снапшоты затронутых тайлов (сжатые).
- Структурные команды (add/move/delete layer, props) хранят дельты.
- Сессионная группировка: один мазок = одна команда. Liquify-сессия = одна команда.
- Лимит: 100 шагов или 512 MB, что раньше.
- Версии проекта (save points): полные снапшоты в `history/` архива, лимит 0–10 / ∞ (дефолт 6).

## Таймлапс
- Запись = поток событий «команда применена» + периодические кадры композита низкого разрешения (дефолт 1080p по длинной стороне), 1 кадр на команду, не чаще 30/с.
- Экспорт: перекодирование в MP4 (MediaCodec H.264) нужной длительности.

## Анимация (P2)
- frames: [Frame{ id, durationFrames, root }], fps, playMode loop|pingpong|once, onion{before, after, firstOpacity, lastOpacity, colors}.
- Video mode (P3): tracks drawing/audio.
