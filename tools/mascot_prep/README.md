# Mascot sprite preparation (Pip, Bolt, Nova, Orbit)

Reproducible pipeline used to turn the four 1254x1254 artwork sheets (7 cells each, transparent background with stray
low-alpha noise) into the 32 files in `glasses-api/src/main/assets/avatar/`. Run order (Python 3, numpy, scipy, opencv,
Pillow, scikit-image), with the sheets saved as `src/pip.png`, `src/nova.png`, `src/orbit.png`, `src/bolt.png` in the
working directory:

1. `seg.py`   - isolate the 7 figures (alpha>200 components; everything else is discarded), fill holes, decontaminate edge colour.
2. `reg.py`   - register every cell to idle (scale + translation, trimmed luminance match on the BODY) so raised hands never move the body.
3. `align.py` - warp cells into the shared idle canvas.
4. `blink.py`/`compose.py` - blink frames = idle everywhere except the eyelid/brow zone.
5. `jumpfix.py` - lift the jump's feet to the shared baseline (see docs/MASCOT_ASSET_SPEC.md, "Production notes").
6. `export.py` - one scale per character, anchor (72, 138), 144 px RGBA + 192 px thumbnail.
7. `validate.py` - size/mode, margins, single component, baseline, body drift, blink-only-in-eyes checks.

Paths are relative to the working directory; the sheets themselves are not stored in the repo.
