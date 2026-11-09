#!/usr/bin/env python3
"""Generate runtime glass surface PNGs for the four panel styles (Neutral, Clear Gloss, Soft Frost, Smoked Glass).

Same formulas as the supplied MyMav glass packs (build_assets.py for Neutral, make_style_packs.py for the other three):
straight-alpha RGBA, 1 px antialiased silhouette, faint body, soft top-left and weaker bottom-right reflections, no
border/text/scenery. Each file is written at its runtime size (longest edge <= 150 px, q = ceil(longest / 150)) and is
named for its destination geometry: surface_<w>x<h>_r<radius>.png.

Usage: glass_surfaces.py OUT_ROOT WxHrR [WxHrR ...] [--force]
  OUT_ROOT/<neutral|clear|frost|smoked>/surface_WxH_rR.png ; existing files are kept unless --force.
Requires numpy + Pillow.
"""
import math, sys
from pathlib import Path
import numpy as np
from PIL import Image

# (dir, body alpha, upper lift, TL max, BR max, body RGB)   -- Neutral uses its own reflection widths (pack v1)
STYLES = {
    'neutral': None,
    'clear': (.10, .035, .34, .17, [205, 213, 218]),
    'frost': (.19, .055, .25, .12, [190, 199, 207]),
    'smoked': (.26, .045, .36, .17, [32, 43, 54]),
}

def render_neutral(w, h, r):
    s = 4
    y, x = np.mgrid[0:h * s, 0:w * s].astype(float); x = (x + .5) / s; y = (y + .5) / s
    qx = np.abs(x - w / 2) - (w / 2 - r); qy = np.abs(y - h / 2) - (h / 2 - r)
    dist = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r
    mask = np.clip(.5 - dist, 0, 1)
    inset = np.maximum(-dist, 0); depth = max(3, min(h * .15, 18))
    tl = np.exp(-((x - r) / (max(r * 2, w * .22))) ** 2 - ((y - r) / (max(r * 1.5, h * .30))) ** 2)
    br = np.exp(-((x - (w - r)) / (max(r * 1.5, w * .14))) ** 2 - ((y - (h - r)) / (max(r, h * .22))) ** 2)
    shine = (.23 * tl + .13 * br) * np.exp(-(inset / depth) ** 2)
    body = .075 + .02 * np.exp(-y / max(h * .25, 1))
    alpha = (body + shine) * mask
    a = np.zeros((h * s, w * s, 4), dtype=np.uint8)
    for c, v in enumerate([192, 199, 204]): a[:, :, c] = v
    a[:, :, 3] = np.round(alpha * 255).astype('uint8'); a[a[:, :, 3] == 0, :3] = 0
    return Image.fromarray(a, 'RGBA').resize((w, h), Image.Resampling.LANCZOS)

def render_style(w, h, r, st):
    body, lift, tlmax, brmax, color = st; s = 4
    y, x = np.mgrid[0:h * s, 0:w * s].astype(float); x = (x + .5) / s; y = (y + .5) / s
    qx = np.abs(x - w / 2) - (w / 2 - r); qy = np.abs(y - h / 2) - (h / 2 - r)
    dist = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r
    mask = np.clip(.5 - dist, 0, 1); depth = max(3, min(h * .18, 23))
    tl = np.exp(-((x - r) / max(r * 2, w * .27)) ** 2 - ((y - r) / max(r * 1.5, h * .36)) ** 2)
    br = np.exp(-((x - (w - r)) / max(r * 1.5, w * .17)) ** 2 - ((y - (h - r)) / max(r, h * .24)) ** 2)
    shine = (tlmax * tl + brmax * br) * np.exp(-(np.maximum(-dist, 0) / depth) ** 2)
    b = body + lift * np.exp(-y / max(h * .28, 1)); alpha = (b + shine * (1 - b)) * mask
    rgb = (np.array(color)[None, None, :] * b[:, :, None] * (1 - shine[:, :, None]) + np.array([226, 233, 239])[None, None, :] * shine[:, :, None]) / np.maximum((b + shine * (1 - b))[:, :, None], 1e-6)
    a = np.zeros((h * s, w * s, 4), dtype='uint8'); a[:, :, :3] = np.clip(rgb, 0, 255); a[:, :, 3] = np.round(alpha * 255); a[a[:, :, 3] == 0, :3] = 0
    return Image.fromarray(a, 'RGBA').resize((w, h), Image.Resampling.LANCZOS)

def main(argv):
    force = '--force' in argv
    args = [a for a in argv if a != '--force']
    out = Path(args[0]); specs = []
    for t in args[1:]:
        w, rest = t.split('x'); h, r = rest.split('r'); specs.append((int(w), int(h), int(r)))
    made = 0
    for d, st in STYLES.items():
        (out / d).mkdir(parents=True, exist_ok=True)
        for w, h, r in specs:
            f = out / d / f'surface_{w}x{h}_r{r}.png'
            if f.exists() and not force: continue
            im = render_neutral(w, h, r) if st is None else render_style(w, h, r, st)
            q = math.ceil(max(w, h) / 150); sz = (math.ceil(w / q), math.ceil(h / q))
            im.resize(sz, Image.Resampling.LANCZOS).save(f); made += 1
    print('written', made)

if __name__ == '__main__':
    main(sys.argv[1:])
