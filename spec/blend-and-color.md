# Blend modes and color

Notation: Cb = backdrop (lower), Cs = source (upper), values 0..1, unpremultiplied. Result B(Cb,Cs). Then the general source-over formula with alphas (W3C Compositing Level 1):
`Co = (1−αb)·Cs + αb·B(Cb,Cs)`; `αo = αs + αb·(1−αs)`; final `C = αs·Co + (1−αs)·αb·Cb` (premultiplied form).

## Separable
| Mode | B(Cb,Cs) |
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
| add | Cb + Cs·αs without alpha-weighted clamping: result = min(1, Cb + Cs·αs), αo as in normal (brighter than linear_dodge at low opacity) |
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
| invert | 1−Cb (Cs ignored, strength = αs) |
| subtract | max(0, Cb−Cs) |
| divide | Cs==0 → 1; min(1, Cb/Cs) |

## Non-separable (W3C: SetLum/SetSat/ClipColor, Lum = .3R+.59G+.11B)
- hue, saturation, color, luminosity.
- darker_color / lighter_color: pick Cb or Cs entirely by the Lum sum.

## Masking
- **erase**: result = Cb, αo = αb·(1−αs).
- **mask**: m = Lum(Cs)·αs + (1−αs) (white/transparent = 1). Result = Cb, αo = αb·m. The layer itself is not visible.
- **line_art**: like mask, plus dark = 1−Lum(Cs). A black line is visible: C = mix(Cb_masked, black, dark·αs). Final alpha = max(αb·m, dark·αs).
- **passthrough**: groups only.

## Color pipeline
- Storage: linear? **No.** Pixels are in sRGB encoding (half-float, premultiplied). This matches artists' expectations and PSD.
- Gamma-correct (document toggle): before blending layers and dabs we decode sRGB → linear, blend, encode back. Default **off**.
- Proofing (display only): sRGB / Grayscale / Line Art / CMYK (via a 3D ICC LUT) / color-blind (Brettel/Viénot matrices).
- Document color mode: gray = on the composite and while drawing the color is projected to Lum; bw = threshold 0.5 on export, on the canvas a gray tone preview.
- Pickers: HSB, RGB, LAB (D65, via XYZ), CMYK (naive formula for the sliders; ICC only on export).
