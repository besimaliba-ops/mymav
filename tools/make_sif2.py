#!/usr/bin/env python3
"""Rasterise a TTF/OTF into Everysight SIF2 (2bpp) + metadata JSON, ASCII 32..126.
usage: make_sif2.py out_dir  (generates the Inter ramp used by HudFonts)"""
import json, struct, sys, os
from PIL import Image, ImageDraw, ImageFont

FACES = {  # name -> font file
    "regular": "/usr/share/fonts/opentype/inter/Inter-Medium.otf",
    "bold": "/usr/share/fonts/opentype/inter/Inter-Bold.otf",
    "italic": "/usr/share/fonts/opentype/inter/Inter-MediumItalic.otf",
    "bolditalic": "/usr/share/fonts/opentype/inter/Inter-BoldItalic.otf",
}
# Glasses allow only 24 active fonts and a 48-entry cache shared with images,
# so each face ships just 6 sizes (HudFonts picks the nearest).
SIZES = [16, 18, 20, 24, 28, 32]
FIRST, LAST = 32, 126

def build(path, size):
    # Inter's cap height is a touch larger than Roboto's; shave 4% so lines match the stock rhythm.
    px = max(8, round(size * 0.96))
    font = ImageFont.truetype(path, px, layout_engine=ImageFont.Layout.BASIC)
    baseline = round(size * 0.80)
    max_h = round(size * 1.05)
    recs, blobs, widths = [], [], {}
    for cp in range(FIRST, LAST + 1):
        ch = chr(cp)
        adv = max(1, round(font.getlength(ch)))
        widths[str(cp)] = adv
        W = px * 3
        im = Image.new("L", (W, W), 0)
        ox, oy = px, px * 2
        ImageDraw.Draw(im).text((ox, oy), ch, fill=255, font=font, anchor="ls")
        bb = im.getbbox()
        if cp == 32 or bb is None:
            recs.append((1, 1, 0, baseline, adv)); blobs.append(b"\x00" * 8); continue
        x0, y0, x1, y1 = bb
        xo = x0 - ox; yo = baseline + (y0 - oy)
        w, h = x1 - x0, y1 - y0
        if xo < 0:  # keep xOffset unsigned: pad bitmap on the left
            pad = -xo; w += pad; xo = 0
            crop = Image.new("L", (w, h), 0); crop.paste(im.crop((x0, y0, x1, y1)), (pad, 0))
        else:
            crop = im.crop((x0, y0, x1, y1))
        if yo < 0:
            crop = crop.crop((0, -yo, w, h)); h += yo; yo = 0
        w = min(w, 255); h = min(h, 255)
        px_ = crop.load()
        rb = (w * 2 + 7) // 8
        data = bytearray()
        for y in range(h):
            row = bytearray(rb)
            for x in range(w):
                a = px_[x, y]
                lvl = min(3, int(a / 255 * 3 + 0.5))
                row[(x * 2) // 8] |= lvl << ((x * 2) % 8)
            data += row
        while len(data) % 8: data.append(0)
        recs.append((w, h, xo, yo, adv)); blobs.append(bytes(data))
    n = LAST - FIRST + 1
    table_end = 12 + n * 8
    off = table_end + 4
    out = bytearray(b"SIF2" + struct.pack("<HBBHH", max_h, 2, 1, FIRST, LAST))
    offs = []
    for b in blobs:
        offs.append(off); off += len(b)
    for (w, h, xo, yo, adv), o in zip(recs, offs):
        out += bytes([w, h, xo, yo, adv, 0]) + struct.pack("<H", o)
    out += b"\x00\x00\x00\x00"
    for b in blobs: out += b
    return bytes(out), json.dumps({"max_height": max_h, "char_width": widths})

if __name__ == "__main__":
    od = sys.argv[1]; os.makedirs(od, exist_ok=True)
    for name, path in FACES.items():
        for s in SIZES:
            sif, js = build(path, s)
            open(f"{od}/{name}{s}.sif2", "wb").write(sif)
            open(f"{od}/{name}{s}.json", "w").write(js)
    print("ok")
