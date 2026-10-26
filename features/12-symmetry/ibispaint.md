# ibisPaint: симметрия

Источник: ibispaint.com/lecture №47, 48. Реализована через Ruler tool.

- **Mirror Ruler**: Phase (угол оси), position. Зеркало.
- **Kaleidoscope Ruler**: Division (кол-во секторов), Phase (угол). Кружево.
- **Radial Ruler**: не симметрия, а лучи (см. 10).
- **Array Ruler**: трансляционная «симметрия» в сетке до 10×10 с перспективой (см. 10).

## Сопоставление с IP
- IP Symmetry покрывает Mirror/Kaleidoscope и даёт больше: Radial rotation, Fan angle, Crop on axis, 2–16 планов.
- Новое из ibis: Array (трансляционная) симметрия → добавить как 5-й тип Symmetry «Array/Grid» с перспективной рамкой.
