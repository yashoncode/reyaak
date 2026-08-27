#!/usr/bin/env python3
"""Generate the Android launcher icon set from art/icon-source.png.

Run from the repo root:  python tools/make_icons.py

The source is a glowing cursor glyph inside a rounded-square badge on a dark
navy field. Adaptive icons (API 26+, which is our minSdk) supply their own mask,
so a baked-in rounded rect would get clipped by the system shape. This script
therefore throws the badge away and rebuilds the icon in layers:

  background  a flat fill of the badge interior navy
  foreground  the glyph alone, its alpha keyed from luminance

Because the background layer IS the navy the glyph was drawn against, the dark
fringe around a partially-transparent glow pixel is invisible in the composite,
so a plain luminance key is enough and no unpremultiply step is needed.

Legacy (pre-26) icons keep the badge, since nothing masks them.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "art" / "icon-source.png"
RES = ROOT / "app" / "src" / "main" / "res"

LUM_COEFF = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)

# Alpha ramp for the glyph key. Interior median luminance is ~20 and the glyph
# sits above ~90, so this keeps the soft glow while dropping the flat field.
KEY_LO, KEY_HI = 24.0, 135.0

# How far in from the badge edge to crop, as a fraction of badge width.
#
# This CANNOT be a brightness decision: the badge stroke is a glowing outline
# that peaks at luminance ~252, exactly as bright as the glyph, so any threshold
# that keeps the glyph also keeps the stroke, which then shows up as a squircle
# ghost inside the system mask. Measured on the source, the stroke and its inward
# bleed die out by ~140px of an 868px badge (the 100-140px band falls to p99=79),
# and the glyph core starts no closer than ~146px from the edge. 0.15 sits in
# that gap.
STROKE_INSET = 0.15

# Adaptive layers are 108dp; only the middle 72dp is guaranteed visible under
# every mask, and under a circular mask the safe region is the circle inscribed
# in that 72dp, not the square. A near-square glyph therefore has to fit the
# circle by its DIAGONAL: 72/sqrt(2) = 50.9dp, i.e. 0.47 of the layer. Filling
# 0.62 put the glyph corners outside the circle, which is what read as a
# cropped, zoomed-in icon on a launcher that masks to a circle.
ADAPTIVE_DP = 108
GLYPH_FILL = 0.46

# density bucket -> (adaptive layer px, legacy icon px, notification icon px)
#
# Notification small icons are 24dp and must be a white-on-transparent
# silhouette, the system tints them and discards colour entirely.
DENSITIES = {
    "mdpi": (108, 48, 24),
    "hdpi": (162, 72, 36),
    "xhdpi": (216, 96, 48),
    "xxhdpi": (324, 144, 72),
    "xxxhdpi": (432, 192, 96),
}


def luminance(rgb: np.ndarray) -> np.ndarray:
    return rgb @ LUM_COEFF


def bbox_above(lum: np.ndarray, threshold: float) -> tuple[int, int, int, int]:
    """Bounding box (x0, y0, x1, y1) of everything brighter than `threshold`."""
    cols = np.where(lum.max(axis=0) > threshold)[0]
    rows = np.where(lum.max(axis=1) > threshold)[0]
    if not len(cols) or not len(rows):
        raise SystemExit("nothing above threshold, is the source image blank?")
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def smoothstep(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    t = np.clip((x - lo) / (hi - lo), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"missing source image: {SOURCE}")

    src = Image.open(SOURCE).convert("RGB")
    arr = np.asarray(src).astype(np.float32)
    lum = luminance(arr)

    # 1. Locate the badge against the surrounding dark field.
    badge = bbox_above(lum, lum.min() + 18.0)
    bw = badge[2] - badge[0]

    # 2. The interior navy, sampled inside the badge away from the glyph.
    inset = int(0.10 * bw)
    inner = arr[badge[1] + inset:badge[3] - inset, badge[0] + inset:badge[2] - inset]
    flat = inner.reshape(-1, 3)
    ilum = luminance(flat)
    navy = flat[ilum < np.percentile(ilum, 35)].mean(axis=0)
    navy_hex = "#%02X%02X%02X" % tuple(int(round(v)) for v in navy)

    # 3. Crop well inside the badge stroke so neither it nor its inward glow
    #    survives into the glyph layer, see STROKE_INSET.
    stroke = int(STROKE_INSET * bw)
    ix0, iy0 = badge[0] + stroke, badge[1] + stroke
    ix1, iy1 = badge[2] - stroke, badge[3] - stroke
    interior = arr[iy0:iy1, ix0:ix1]
    ilum2 = luminance(interior)

    # 4. Key the glyph out by luminance and trim to its own bounds.
    alpha = smoothstep(ilum2, KEY_LO, KEY_HI)
    # Floor the near-transparent tail to zero. Against the navy background layer
    # it is invisible either way, but the monochrome layer derives from this
    # alpha and would otherwise carry a faint rectangular haze.
    alpha[alpha < 0.06] = 0.0
    gx0, gy0, gx1, gy1 = bbox_above(alpha * 255.0, 26.0)
    glyph_rgb = interior[gy0:gy1, gx0:gx1]
    glyph_a = alpha[gy0:gy1, gx0:gx1]

    rgba = np.dstack([glyph_rgb, glyph_a * 255.0]).astype(np.uint8)
    glyph = Image.fromarray(rgba, mode="RGBA")

    # 5. The legacy icon keeps the badge, nothing masks pre-26 icons.
    legacy = src.crop(badge)

    print(f"source        {src.size[0]}x{src.size[1]}")
    print(f"badge bbox    {badge}  ({bw}px)")
    print(f"interior navy {navy_hex}")
    print(f"glyph         {glyph.size[0]}x{glyph.size[1]}")

    written = 0
    for bucket, (adaptive_px, legacy_px, notif_px) in DENSITIES.items():
        out = RES / f"mipmap-{bucket}"
        out.mkdir(parents=True, exist_ok=True)

        # Adaptive foreground: glyph centred, scaled into the safe zone.
        canvas = Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0))
        target = adaptive_px * GLYPH_FILL
        scale = min(target / glyph.size[0], target / glyph.size[1])
        gw, gh = max(1, round(glyph.size[0] * scale)), max(1, round(glyph.size[1] * scale))
        canvas.paste(
            glyph.resize((gw, gh), Image.LANCZOS),
            ((adaptive_px - gw) // 2, (adaptive_px - gh) // 2),
        )
        canvas.save(out / "ic_launcher_foreground.png")

        # Monochrome layer for themed icons (Android 13+): the glyph silhouette.
        mono = Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0))
        sil = Image.new("RGBA", (gw, gh), (255, 255, 255, 255))
        sil.putalpha(glyph.resize((gw, gh), Image.LANCZOS).getchannel("A"))
        mono.paste(sil, ((adaptive_px - gw) // 2, (adaptive_px - gh) // 2))
        mono.save(out / "ic_launcher_monochrome.png")

        # Legacy square and round icons.
        square = legacy.resize((legacy_px, legacy_px), Image.LANCZOS)
        square.save(out / "ic_launcher.png")

        rnd = square.convert("RGBA")
        mask = Image.new("L", (legacy_px * 4, legacy_px * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy_px * 4 - 1, legacy_px * 4 - 1), fill=255)
        rnd.putalpha(mask.resize((legacy_px, legacy_px), Image.LANCZOS))
        rnd.save(out / "ic_launcher_round.png")

        # Notification small icon: the same silhouette, in its own drawable
        # bucket. Padded to ~88% so it does not touch the 24dp bounds.
        nout = RES / f"drawable-{bucket}"
        nout.mkdir(parents=True, exist_ok=True)
        stat = Image.new("RGBA", (notif_px, notif_px), (0, 0, 0, 0))
        inner_px = max(1, round(notif_px * 0.88))
        gs = min(inner_px / glyph.size[0], inner_px / glyph.size[1])
        sw, sh = max(1, round(glyph.size[0] * gs)), max(1, round(glyph.size[1] * gs))
        white = Image.new("RGBA", (sw, sh), (255, 255, 255, 255))
        white.putalpha(glyph.resize((sw, sh), Image.LANCZOS).getchannel("A"))
        stat.paste(white, ((notif_px - sw) // 2, (notif_px - sh) // 2))
        stat.save(nout / "ic_stat_reyaak.png")

        written += 5

    # Background colour + the adaptive icon descriptors.
    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">{navy_hex}</color>\n'
        "</resources>\n",
        encoding="utf-8",
    )

    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    descriptor = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (RES / "mipmap-anydpi-v26" / name).write_text(descriptor, encoding="utf-8")

    print(f"wrote {written} PNGs across {len(DENSITIES)} densities, plus 3 XML resources")

    # A composite preview, so the result can be eyeballed rather than assumed.
    preview_px = 432
    bg = Image.new("RGBA", (preview_px, preview_px), tuple(int(round(v)) for v in navy) + (255,))
    fg = Image.open(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png")
    bg.alpha_composite(fg)
    circle = Image.new("L", (preview_px, preview_px), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, preview_px - 1, preview_px - 1), fill=255)
    masked = bg.copy()
    masked.putalpha(circle)
    sheet = Image.new("RGBA", (preview_px * 2 + 24, preview_px), (128, 128, 128, 255))
    sheet.alpha_composite(bg, (0, 0))
    sheet.alpha_composite(masked, (preview_px + 24, 0))
    out_preview = ROOT / "art" / "icon-preview.png"
    sheet.convert("RGB").save(out_preview)
    print(f"preview (square mask | circle mask) -> {out_preview.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
