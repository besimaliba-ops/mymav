# Mascot production asset spec (Pip, Bolt, Nova, Orbit)

Status: **assets delivered 2026-10-14 (see Production notes at the end); simulator-verified, physical glasses not yet tested.** Original brief: `MymavAvatarArt` loads production sprites from
`glasses-api/src/main/assets/avatar/<character>/<cell>.png` when present. Until all seven cells of a character
exist, that character uses the procedural renderer as a clearly interim fallback
(`MymavAvatarArt.hasProductionSprites(preset)` reports which). The redesign is NOT complete for mascots until
these files are supplied and verified.

## Files: 7 pose PNGs PER character (28 across all four) plus 1 thumbnail per character (4) = 32 files

Folders: `pip/`, `bolt/`, `nova/`, `orbit/`. Cell file names (all lowercase):

| Cell | File | Pose |
|---|---|---|
| 0 | `idle.png` | Neutral, eyes open, both hands relaxed at sides, feet together. Reference pose for proportions. |
| 1 | `blink_half.png` | Identical to idle but eyelids half closed. |
| 2 | `blink.png` | Identical to idle but eyes fully closed (happy curved lids). |
| 3 | `wave_low.png` | Right hand (viewer's left) raised to hip/chest height, other hand relaxed. |
| 4 | `wave_mid.png` | Same hand at shoulder height. |
| 5 | `wave_high.png` | Same hand above the head level of the face. |
| 6 | `jump.png` | Both arms raised, BOTH white hands fully visible, feet lifted/tucked. |

## Image requirements

- 144 x 144 px, PNG, 32-bit RGBA, straight (non-premultiplied) alpha. The app downsamples to 72 x 72 for the
  glasses (2x source for crisp antialiasing); a 72 x 72 file is also accepted.
- Transparent background; body opaque everywhere inside the silhouette; antialiased edge, no halo, no baked
  drop shadow or reflection, no outline stroke, no text.
- Character centred horizontally, feet baseline identical in every cell (y = 138 of 144), at least 4 px clear
  margin on all sides in EVERY pose, so antenna tips, Saturn ring, hands and feet are never cropped.
- Same body size, position and proportions in all 7 cells of a character (only face lids, arms and feet change).
  Idle-to-wave transitions must not shift the body.
- Appearance (all four): solid golden glossy toy body, blue eyes, white hands, gold feet, restrained specular
  highlight (no strong reflections). Eyes and hands must keep their own colours (accent choice never recolours them).
- Characters: **Pip** single round-tipped antenna; **Bolt** lightning-bolt antenna; **Nova** rounded sun-crown
  nubs; **Orbit** Saturn-like ring around the body with face and both hands readable in every pose
  (ring must not hide either hand in `jump.png`).

## Texture budget (measured against the existing safe design)

Resident cost is 7 textures x 72 x 72 x 4 B = 141 KB per character; only the selected character is decoded and
uploaded (once, at connect/preset change). Do not exceed 7 cells or 72 px on-glass size: a large atlas previously
overflowed the glasses command queue and corrupted neighbouring logos. Extra animation frames need a new
measurement before being added.

## Phone Settings picker thumbnails (narrow exception)

For each character also supply `thumb.png` (192 x 192, same rules, idle pose). Place beside the cells.

## Anchors, frame order and timing

- Anchor: the feet-centre point is (72, 138) in the 144 px source in every cell; body centre line x = 72. The app
  places every cell at the same screen position, so nothing may drift between cells.
- Safe box: all opaque pixels inside x 4..140, y 4..140.
- Frame order = cell number 0..6 in the table above. Sequences the app plays from these seven cells:
  - Idle: cell 0, with a 1-2 px float done by moving the image (not by frames).
  - Blink: 0 -> 1 (60 ms) -> 2 (80 ms) -> 1 (60 ms) -> 0, at random 2.5-5 s intervals.
  - Wave: 3 -> 4 -> 5 -> 4 -> 3 -> 4 -> 5 -> 4 -> 3 at about 110 ms per cell, then back to 0.
  - App-entry jump: cell 6 held for the whole jump (both hands visible throughout).
  - Page change and Ask: cell 0 stays centred (no sideways slide).
- Cells 1, 2 differ from cell 0 only in the eyelids; cells 3-5 only in the waving arm; cell 6 in both arms and feet.

## Thumbnail

`thumb.png` per character: 192 x 192 RGBA, idle pose, same rules (4 px+ margin, transparent, antialiased), used by the
phone Settings character picker.

## Exact file list

`avatar/pip/{idle,blink_half,blink,wave_low,wave_mid,wave_high,jump,thumb}.png` and the same under `bolt/`, `nova/`, `orbit/`.

## Status

The old procedural mascot remains only as a temporary fallback. The new characters are NOT implemented until these
files are delivered and the motion is tested on the simulator and glasses.

## Production notes (delivered 2026-10-14)

All 32 files are in `glasses-api/src/main/assets/avatar/<pip|bolt|nova|orbit>/`, 144x144 (thumb 192x192) 32-bit RGBA.
Source: four 1254x1254 sheets; pipeline in `tools/mascot_prep/`, evidence in `docs/verification/mascots/`.

- Cleaning: exactly one opaque component per cell is kept (alpha>200, holes filled); all other pixels (red/yellow
  noise, low-alpha fringe) are dropped. Edge colour is rebuilt from interior pixels, so there is no halo on white or
  blue backgrounds. Bodies are fully opaque (source alpha was 250-254).
- Alignment: each cell was registered to idle on the BODY (scale + translation, residual ~1 grey level), not on its own
  silhouette. Source shifts of up to 20 px (wave) and up to 2.6% scale (jump) were removed. Residual body-centroid
  drift at 144 px is <= 0.4 px for blink/wave (0.0 for blinks); see `validation.txt`.
- Scale: one scale per character (limited by the jump's arm span so every pose keeps the 4 px margin): Pip 0.3349,
  Nova 0.3387, Orbit 0.3233, Bolt 0.3245 (body about 79-89 px wide at 144). Anchor: feet centre (72, 138); idle bottom
  row is y=138 in every non-jump cell; all opaque pixels are inside x 4..139, y 4..139.
- Blink cells: idle pixels everywhere except the eyelid/brow zone (the source sheets redraw the antenna tip, mouth and
  gloss slightly in the blink cells, which would flicker). They differ from idle only inside y 47..104, x 36..109.
- Jump feet (artwork correction): in Pip, Orbit and Bolt the source jump pose has the feet hanging 6-12 source px
  BELOW the idle feet (outside the safe box once the body is anchored). The two feet were moved up as rigid pieces
  (Pip 12, Orbit 9, Bolt 15 source px; about 3-5 px at 144) so their bottom sits at the shared baseline; Nova's feet
  were already lifted and are untouched. The feet remain splayed as drawn, not together.
- Known artwork variance (not correctable without redrawing): eye position differs by up to ~1 px at 144 (about
  0.5 px at 72) between idle and some wave/jump cells; Pip's jump hands are drawn as mittens (no fingers) unlike its
  wave hands; Orbit's ring is drawn slightly lower in the jump. Narrow arm/body gaps in a few cells are semi-transparent
  (alpha 60-125) by design of the source.
- Counts: "144 x 144 px, 32 files" = 4 characters x (7 poses + 1 thumbnail).

### Motion observed in the simulator (app's own sequencing; `DBGAVATAR` trace, `atrace` debug command)

All four characters behave identically: blink 0 -> 1 -> 2 -> 1 -> 0 in 100 ms steps; wave uses cells 3/4/5; page
change keeps cell 0 at x=320 (no sideways slide); Ask summon drops straight down (y 40 -> 108, x unchanged) and Ask
return goes back to y=40, cell 0, x=320; app entry holds cell 6 for all 10 jump steps. Differences from the timings in
this spec (not changed here): the app's blink steps are 100 ms each (spec: 60/80/60), and its wave dwell pattern is
3,4,4,3,1,1,... in controller frames rather than 3,4,5,4 at 110 ms. Physical-glasses motion and texture timing are not tested.
