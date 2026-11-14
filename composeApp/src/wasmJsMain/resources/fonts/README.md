# Bundled CJK subset — `vade-sans-sc.woff2`

The Web (wasmJs) build ships one font file so that **Chinese text has glyphs without any
network access**. This file is that font, plus everything needed to reproduce and licence it.

| | |
|---|---|
| file | `vade-sans-sc.woff2` |
| bytes | **1,012,640** |
| sha256 | `64e469070eaa77ad8a6b77e8032e54803450d953026e7ac25ad974e56f65b209` |
| glyphs / cmap entries | 7,556 / 7,548 |
| unitsPerEm | 1000 |
| family (name id 1/16) | **Vade Sans SC** |
| PostScript name (id 6) | `VadeSansSC-Regular` |
| coverage | GB2312 (7,445 code points) + ASCII + the punctuation the UI uses |
| licence | SIL Open Font License 1.1 — full text in `OFL-VadeSansSC.txt` (ships next to the font) |

## Why a bundled file at all

Web has no system fonts: Skia/CanvasKit can only rasterise glyphs whose bytes it was given.
CMP's own fallback (`androidx.compose.ui.platform.NotoFontDownloader`, `internal`, base URL
`internal`) pulls Noto subsets from `https://fonts.gstatic.com/s/` **at runtime** — 101
subsets for Noto Sans SC — so a Chinese screen is tofu until the CDN answers and **stays
tofu offline**. It cannot be configured, so the only lever is "the requested family already
covers the codepoints": the entry point loads this file before the first composition
(`composeApp/src/wasmJsMain/kotlin/vade/ui/VadeFontWasm.kt`). Root-cause write-up:
`shots/g-font-recon/RECON.md`; verification: `shots/g-font-bundle2/RESULT.md`.

GB2312 (not a micro-subset of today's strings): a 13 KB "only the characters on screen today"
subset is tofu again the moment someone writes a new sentence. 1.0 MB covers every simplified
Chinese character in common use plus the punctuation inventory.

## Provenance

Upstream: **Noto Sans SC**, `google/fonts` → `ofl/notosanssc`.

* `NotoSansSC[wght].ttf` (variable, wght 100–900): **17,772,300 B**,
  sha256 `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`
* `OFL.txt` from the same directory: **4,388 B**,
  sha256 `1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9`
* Copyright line kept verbatim in the subset's `name` table (id 0):
  `(c) 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'.`

OFL 1.1 permits use, modification and redistribution (including inside a product) as long as
the copyright notice and licence travel with it and a **modified** version does not use the
Reserved Font Name. Subsetting *is* a modification, so the family was renamed
`Noto Sans SC` → **`Vade Sans SC`** (name ids 1, 3, 4, 6, 16, 17 rewritten; ids 0, 5, 7–14
untouched). The licence text is shipped in the web bundle at
`fonts/OFL-VadeSansSC.txt`, i.e. one URL away from the font it covers.

HarmonyOS Sans is deliberately **not** used: its licence only allows unmodified copies, and a
subset (or a woff2 conversion) is a modification.

## Reproducing it

```bash
# 1) upstream
curl -L -o NotoSansSC[wght].ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"
curl -L -o OFL.txt "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/OFL.txt"

# 2) static instance at the weight the UI uses for body copy
python -m fontTools.varLib.instancer "NotoSansSC[wght].ttf" wght=400 -o NotoSansSC-Regular.ttf

# 3) GB2312 charset (+ ASCII + UI punctuation), one code point per line-less blob
python - <<'PY'
out = []
for lead in range(0xA1, 0xF8):
    for trail in range(0xA1, 0xFF):
        try:
            out.append(bytes([lead, trail]).decode("gb2312"))
        except UnicodeDecodeError:
            pass
extra = "".join(chr(c) for c in range(0x20, 0x7F)) + "·—…“”‘’°×÷±≈≤≥→←↑↓•§¶†‡"
open("gb2312-chars.txt", "w", encoding="utf-8").write("".join(dict.fromkeys("".join(out) + extra)))
PY

# 4) subset -> woff2
python -m fontTools.subset NotoSansSC-Regular.ttf --text-file=gb2312-chars.txt \
  --flavor=woff2 --layout-features=kern,liga,ccmp --desubroutinize --no-hinting \
  --name-IDs='*' --name-legacy --name-languages='*' --notdef-outline --recommended-glyphs \
  --output-file=vade-sans-sc.woff2

# 5) rename the family (see above) with fontTools: name ids 1,3,4,6,16,17
```

The one-shot script that did 2–5 (and printed every number in the table) lived outside the
repo at `D:/Hermes/cache/scratch/fontbuild/build_font.py`; the numbers above are its output.

## Serving

`src/wasmJsMain/resources/**` ends up in `composeApp/build/processedResources/wasmJs/main/`,
which `wasmJsBrowserDistribution` merges into `build/dist/wasmJs/productionExecutable/` — the
font is therefore fetched as a plain relative URL (`fonts/vade-sans-sc.woff2`) from the same
origin as `index.html`. No CDN, no `@font-face`, no Service Worker: the bytes go straight into
`androidx.compose.ui.text.platform.Font(identity, data)`.
