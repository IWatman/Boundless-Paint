# Режимы наложения и цвет

Обозначения: Cb = нижний (backdrop), Cs = верхний (source), значения 0..1, unpremultiplied. Результат B(Cb,Cs). Затем общая формула source-over с альфами (W3C Compositing Level 1):
`Co = (1−αb)·Cs + αb·B(Cb,Cs)`; `αo = αs + αb·(1−αs)`; итог `C = αs·Co + (1−αs)·αb·Cb` (premultiplied form).

## Сепарабельные
| Режим | B(Cb,Cs) |
|---|---|
| normal | Cs |
| darken | min(Cb,Cs) |
| multiply | Cb·Cs |
| color_burn | Cb==1→1; Cs==0→0; else 1−min(1,(1−Cb)/Cs) |
| linear_burn | max(0, Cb+Cs−1) |
| lighten | max |
| screen | Cb+Cs−Cb·Cs |
| color_dodge | Cb==0→0; Cs==1→1; else min(1, Cb/(1−Cs)) |
| linear_dodge | min(1, Cb+Cs) |
| add | Cb + Cs·αs без клампа альфы-взвешивания: результат = min(1, Cb + Cs·αs), αo как normal (ярче linear_dodge на низкой opacity) |
| overlay | hard_light(Cs,Cb) |
| soft_light | W3C formula (D(Cb)) |
| hard_light | Cs≤.5 → multiply(Cb,2Cs) else screen(Cb,2Cs−1) |
| vivid_light | Cs≤.5 → color_burn(Cb,2Cs) else color_dodge(Cb,2Cs−1) |
| linear_light | clamp(Cb+2Cs−1) |
| pin_light | Cs≤.5 → min(Cb,2Cs) else max(Cb,2Cs−1) |
| hard_mix | (vivid_light(Cb,Cs) < .5) ? 0 : 1 |
| difference | abs(Cb−Cs) |
| exclusion | Cb+Cs−2CbCs |
| negative (negation) | 1−abs(1−Cb−Cs) |
| invert | 1−Cb (Cs игнорируется, сила = αs) |
| subtract | max(0, Cb−Cs) |
| divide | Cs==0 → 1; min(1, Cb/Cs) |

## Несепарабельные (W3C: SetLum/SetSat/ClipColor, Lum = .3R+.59G+.11B)
- hue, saturation, color, luminosity.
- darker_color / lighter_color: выбрать целиком Cb или Cs по сумме Lum.

## Маскирующие
- **erase**: результат = Cb, αo = αb·(1−αs).
- **mask**: m = Lum(Cs)·αs + (1−αs) (белое/прозрачное = 1). Результат = Cb, αo = αb·m. Сам слой не виден.
- **line_art**: как mask, плюс dark = 1−Lum(Cs). Видна чёрная линия: C = mix(Cb_masked, black, dark·αs). Итоговая альфа = max(αb·m, dark·αs).
- **passthrough**: только у групп.

## Конвейер цвета
- Хранение: linear? **Нет.** Пиксели в sRGB-кодировке (half-float, premultiplied). Это совпадает с ожиданием художников и PSD.
- Gamma-correct (тоггл документа): перед смешением слоёв и штампов декодируем sRGB → linear, смешиваем, кодируем обратно. Дефолт **off**.
- Proofing (только дисплей): sRGB / Grayscale / Line Art / CMYK (через ICC LUT 3D) / color-blind (матрицы Brettel/Viénot).
- Color mode документа: gray = на композите и при рисовании цвет проецируется в Lum; bw = порог 0.5 при экспорте, на холсте серое превью тона.
- Пикеры: HSB, RGB, LAB (D65, через XYZ), CMYK (наивная формула для слайдеров; ICC только при экспорте).
