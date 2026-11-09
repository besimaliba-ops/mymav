# Panel asset spec (surface inventory)

Inventory of every glass surface the glasses UI draws today, for preparing clean transparent PNG surfaces.
Canvas is **640 × 400** HUD pixels (the Everysight Maverick display). Coordinates are canvas pixels; sizes were read from the
current code on `claude/premium-glass-design` (commit `39dde58` plus the removal of the Home outer frame). Nothing here changes a
layout or a font setting. Sizes marked with `≈` depend on label text width and are estimates; everything else is exact.

## 1. What a surface PNG must contain

- Straight (non-premultiplied) RGBA PNG, transparent outside the rounded silhouette with a **1 px antialiased edge**; no stray pixels outside it.
- **Body:** a very faint neutral (not blue-saturated, not milky) fill so the scenery stays visible; calm and even, with at most a barely lifted upper area.
- **Top-left shine:** subtle, hugging the rounded corner and the two adjoining edges, fading smoothly to zero alpha well inside the panel (no texture boundary).
- **Bottom-right reflection:** visibly weaker and smaller than the top-left.
- **Not baked in:** text, icons, scenery, sample data, the outline/border, focus or selection colours, app accent colours, gaze/dwell progress. Those are drawn live in code above/below the surface.
- Edge: the single fine rounded edge is drawn in code (1 px, `PANEL_STROKE_BRIGHT`); do not bake a thick chrome rim or a second border.
- Corner proportions must not be distorted: deliver each unique size at its own dimensions (or an exact integer-scaled version), never a stretched master.
- Layer order at runtime: body/surface → live content (text, icons, controls) → focus/selection outline → gaze progress.
- Suggested filenames: `surface_<w>x<h>_r<radius>.png` (e.g. `surface_298x135_r18.png`); use the ID column below in a manifest.

## 2. Corner-radius and stroke constants (code)

| Constant | Value |
|---|---|
| `PANEL_RADIUS` (Home widgets, Ask card) | 18 |
| `PILL_RADIUS` / pills | height / 2 |
| App main panel | 22 |
| Default card (`GlassKit.card`) | 16 (list rows / song rows / task rows 14) |
| Keyboard key | 12 (`KEY_RADIUS`) |
| Edge stroke | 1 px (`PANEL_STROKE` / `PANEL_STROKE_BRIGHT`) |
| Focus stroke | 2 px (`FOCUS_STROKE`), drawn in code |

## 3. Every unique surface

"Texture today" is the size of the single cached shine texture the current code uploads for that panel (longest edge ≤ 150 px, drawn scaled up). It is for reference only.

| ID | Surface | Width × height | Radius | Content padding / layout | Screens | Texture today |
|---|---|---|---|---|---|---|
| S1 | Status pill (date/time) | 228 × 36 | 18 | text inset 12; label max 196 wide | Home board | 114×18 (÷2) |
| S2 | Status pill (phone + glasses battery) | 252 × 36 | 18 | content centred: phone icon 24 + %, 1 px divider, glasses icon 14 + %; gap 8, divider zone 26 | Home board | 126×18 (÷2) |
| S3 | HOME pill | 130 × 34 | 17 | house icon 14 + 6 gap + 'HOME' centred (≈40 left/right) | Every app screen except the keyboard (Socials, Health, Workouts, Weight, Running, Songs, Lists, Tasks, Identify, App-enter, Lyrics). Fixed gaze-hit rect x482 y28 — never moves | 130×34 (÷1) |
| W1 | Home widget 1×1 (Small) | 298 × 135 | 18 | left/right 16, top 12 (title); 36 px icon badge at +12/+8; body top +54, bottom 10 | Home board: Socials, Health, Spotify, Conversate and all other Small widgets | 149×68 (÷2) |
| W2 | Home widget 2×1 (Wide) | 608 × 135 | 18 | as W1 | Home board (Wide widgets) | 122×27 (÷5) |
| W3 | Home widget 1×2 (Tall) | 298 × 282 | 18 | as W1 | Home board (Tall widgets) | 149×141 (÷2) |
| W4 | Home widget 2×2 (Large) | 608 × 282 | 18 | as W1 | Home board (Large widgets) | 122×57 (÷5) |
| A1 | Ask answer card, collapsed | 440 × 96 | 18 | left 18, text block vertically centred (3 × 26) | Home board Ask overlay (follows mascot) | 147×32 (÷3) |
| A2 | Ask answer card, expanded | 496 × 268 | 18 | as A1; image slot 200×150 | Home board Ask overlay at x136 y52 | 124×67 (÷4) |
| A3 | Ask action pill, collapsed (3 pills) | 140 × 40 | 20 | label centred, bold | Ask overlay; w=(440−10(n−1))/n → n2 215, n4 102.5, n5 80 | 140×40 (÷1) |
| A4 | Ask action pill, expanded (3 pills) | 158.67 × 40 | 20 | label centred | Ask overlay expanded; w=(496−10(n−1))/n | 80×20 (÷2) |
| P1 | App main panel | 608 × 380 | 22 | outer margin 16 / 10; content x32; header y24; footer y354–362 | Socials, Health, Workouts, Weight, Songs, Identify, App-enter | 122×76 (÷5) |
| P2 | App main panel (Running) | 592 × 360 | 22 | inset 24 / 20 | Running workout | 148×90 (÷4) |
| C1 | Socials message card — empty / inbox with filters | 576 × 216 | 16 | inset 14; avatar 52 at +14/+12; empty-state icon + text centred | Socials | 144×54 (÷4) |
| C2 | Socials message card — message + reply row + filters | 576 × 160 | 16 | as C1 | Socials (message selected) | 144×40 (÷4) |
| C3 | Socials message card — message, filters hidden | 576 × 216 | 16 | as C1 | Socials detail / reply stages | 144×54 (÷4) |
| C4 | Socials message card with media | 372 × 160 | 16 | as C1 | Socials (photo/video message) | 124×54 (÷3) |
| C5 | Socials media card | 192 × 160 | 16 | image inset 10 | Socials (photo/video message) | 96×80 (÷2) |
| C6 | Socials reply row | 576 × 44 | 16 | left 16; action pill at right, inset 8 | Socials (message) | 144×11 (÷4) |
| B1 | Socials action pill (OPEN/REPLY/SEND/MIC) | 148 × 36 | 18 | label centred | Socials (message) | 148×36 (÷1) |
| T1 | Filter pill (All / WhatsApp / Instagram) | ≈92 / 118 / 112 × 36 | 18 | width = text + 44, min 92; gap 14; row centred in 576; y302 | Socials | — |
| T2 | Segmented tab track (Health) | 576 × 32 | 16 | 4 segments of 144; selected inset 3 → 138×26, r13; y72 | Health | 144×8 (÷4) |
| T3 | Segmented tab track (GlassKit default) | 576 × 36 | 18 | selected inset 3 | Default only; Health overrides to 32 high | 144×9 (÷4) |
| L1 | List row | 608 × 52 | 14 | left 16 + 5 px colour stripe; scrollbar x628 | List apps | 122×11 (÷5) |
| L2 | List empty card | 608 × 150 | 18 | icon 56 at +24/+24, text x116 | List apps | 122×30 (÷5) |
| L3 | List detail card | 608 × 222 | 16 | inset 16; image 190×142 at +14/+12 | List apps (detail) | 122×45 (÷5) |
| L4 | List action pill | ≤170  (=(608−10(n−1))/n) × 38 | 19 | label centred, tracked | List apps | — |
| L5 | Task row | 608 × 48 | 14 | checkbox 20 at x30; text x64 | Tasks board | 122×10 (÷5) |
| L6 | Task empty card | 608 × 88 | 16 | text x48 | Tasks board | 122×18 (÷5) |
| L7 | Task pill (ADD/DUE/DONE/BACK) | 140 × 36 | 18 | gap 12 from x16; label centred | Tasks board | 140×36 (÷1) |
| L8 | Song row | 576 × 46 | 14 | text x48 (card x32 + 16) | Song list | 144×12 (÷4) |
| L9 | Song empty card | 576 × 200 | 16 | text x48 | Song list | 144×50 (÷4) |
| L10 | BACK button | 96 × 32 | 16 | label centred | Song list | 96×32 (÷1) |
| K1 | Workout choice card | 576 × 92 | 16 | title x52; metric button right | Workout select | 144×23 (÷4) |
| K2 | Workout metric button (RUN / LIFT) | 124 × 36 | 18 | label centred | Workout select | 124×36 (÷1) |
| K3 | Weight card, no exercises | 576 × 230 | 16 | text x52 | Weight session | 144×58 (÷4) |
| K4 | Weight card, current exercise | 576 × 190 | 16 | text x52 | Weight session | 144×48 (÷4) |
| K5 | Running metrics card | 560 × 94 | 16 | three columns at x60 / 250 / 430 | Running workout | 140×24 (÷4) |
| K6 | Identify reference card | 135 × 139 | 14 | image inset 6, caption +10; 4 cells, gap 12, y144 | Identify result | 135×139 (÷1) |
| K7 | App-enter metric card | 212 × 150 | 16 | text x412 (+16) | App-enter | 106×75 (÷2) |
| Y1 | Lyrics header panel | 608 × 138 | 20 | inset 16 | Lyrics | 122×28 (÷5) |
| Y2 | Lyrics body panel | 608 × 214 | 20 | inset 16 | Lyrics | 122×43 (÷5) |
| Y3 | Lyrics Prev / Next button | 52 × 30 | 15 | x156 / x300, y114 | Lyrics | 52×30 (÷1) |
| Y4 | Lyrics Play / Search / Liked pill | 84 × 30 | 15 | x212 / x356 / x444, y114 | Lyrics | 84×30 (÷1) |
| G1 | Keyboard letter key (1 unit) | 56 × 46 | 12 | gap 6; ROW_Y 102; left 16 | Gaze keyboard — letters | 56×46 (÷1) |
| G2 | Keyboard wide bottom-row key (56n+6(n−1)) | 118 / 180 / … × 46 | 12 | as G1 | Gaze keyboard — letters | — |
| G3 | Keyboard function key | 118 × 42 | 12 | y314; gap 6; fn_cat 126 wide | Gaze keyboard — all modes | 118×42 (÷1) |
| G4 | Keyboard suggestion chip | 200 × 34 | 14 | x12 + i·208, y62 | Gaze keyboard — letters | 100×17 (÷2) |
| G5 | Keyboard suggestion / mode strip | 616 × 34 | 14 | text x26 | Gaze keyboard | 124×7 (÷5) |
| G6 | Keyboard title / buffer panel | 276 × 48 | 16 | x118 y8 | Gaze keyboard | 138×24 (÷2) |
| G7 | Keyboard BACK / CANCEL pill | 96 × 44 | 22 | x12 y8 | Gaze keyboard | 96×44 (÷1) |
| G8 | Keyboard VTXT / VNOTE pill | 108 × 44 | 22 | two-line label; x404 / x520 | Gaze keyboard | 108×44 (÷1) |
| G9 | Keyboard emoji category key | 196 × 52 | 12 | 3 columns, gap 10 | Gaze keyboard — categories | 98×26 (÷2) |
| G10 | Keyboard emoji key | 92 × 44 | 12 | gap 8 / 6 | Gaze keyboard — emoji / search | 92×44 (÷1) |
| G11 | Keyboard emoji utility keys | 220 / 120 / 100 × 44 | 12 | gap 8 | Gaze keyboard — emoji / search | — |

Notes
- Home widgets that carry preview artwork draw the artwork and a dark scrim over the surface; the surface itself is unchanged.
- The Home board also shows the page dots and mascot above the tiles; neither is a surface.
- Rows without a surface (Health metric rows, hero value, lyric lines) are plain text on the app panel.
- Count of unique sizes in this table: 55 (several share a corner radius and differ only in size).

## 4. Texture and image-size limits measured so far

These come from the skipped-texture investigation on the Everysight simulator (SDK 0.2.0, 640×400). They are observed behaviour, not published SDK limits, and no physical-glasses measurement has been made yet.

| Finding | Evidence |
|---|---|
| Each `createHudImage` uploads its own texture with cache scope `NotCached` and is re-uploaded every time a screen is built. | `hudImageResource` → `newImageResource(..., M2CacheScope.NotCached)`; logcat `cacheScope=NotCached`. |
| The SDK logs `onResourceResult[during M2ImageBytes]: SKIPPING texture` when a screen is rebuilt (or replaced) before an upload is acknowledged; the affected image then never appears until the next full build. | Logcat lines such as `SKIPPING texture ... slot=42 ... Png \| 86x36` and `... slot=41 ... Png \| 114x18`, together with `M2Screen draw SKIPPING: pendingRelease=true`. |
| Texture slots on the Home board were in use up to about slot 41–42 of a 127-slot cache (`slotCache=127`). The Home board rebuilds more than once on entry. | Logcat `slot=41/42 slotCache=127`. |
| Large per-panel textures are risky: full-image panel surfaces (e.g. 608×380) and nine-slice full-size surfaces produced skipped textures and a missing HOME pill on the Health screen. | Health screen test with full-image surfaces (missing HOME pill, skipped textures). |
| Many small textures per build also failed: with 2 shine images per panel (8 on Home plus the status pills) the Conversate icon intermittently failed to appear (skipped upload). | Visible on Home after `cmd home`, matching a skipped texture. |
| Stable configuration today: **one** shine texture per panel, generated at 1/q resolution with **q = ceil(longest edge / 150)** and drawn scaled up, so no panel texture is larger than 150 px on its longest edge (e.g. a 298×135 tile → 149×68, a 608×380 panel → 152×95). After this change Home re-entered repeatedly showed all icons; one decorative status-pill texture (114×18) was still skipped once in three re-entries. | Repeated `cmd home` runs, logcat count of `SKIPPING texture`. |
| Glasses-side resident image cache (`HudImageCache`) **must stay off**. `CachedManualDelete` reuse evicted type-4 fonts from the shared ~48–50 row table and made Home type jump after paging (checkpoint `d98c097`). Packed-PNG encode cache (`HudEncodedCache`) stays **on** (phone-side only). USB/upload opts must not re-enable resident textures. | `HudImageCache.enabled = false`; `hudImageResource` → `NotCached`. |
| Premium app badges are requested only at 36, 56 or 72 px; other sizes return nothing (this is why the Home Health heart vanished at 30 px). | `PremiumHudIcons.image`. |
| The largest installed text size is 32 px (ramp 16 / 20 / 24 / 32), so hero numbers cannot exceed that without a font change. | `HudFonts` ramp. |

Not yet measured: exact per-texture byte limit, maximum simultaneous uploads before skipping, and behaviour on the physical glasses. Treat the guidance below as conservative.

## 5. Recommended delivery for the PNG surfaces

1. Prepare each unique **size** above (not one master) as a transparent PNG at 1× HUD pixels.
2. Prefer to keep the runtime texture to **≤ 150 px on the longest edge**: either also supply a half-resolution (÷2) version for surfaces whose longest edge is above 150, or accept that the app downsamples them. Only the body and soft reflections need to survive downscaling; the 1 px edge is drawn in code.
3. Keep the file count down by reusing one surface for panels of the same size (e.g. all four Home tiles use W1; all keyboard letter keys use G1; all filter pills of the same width share one file).
4. Small controls (pills, keys, chips, buttons — heights ≤ 52) can use a plain stretch-safe pill/rounded-rect with the body and shine only in the corners; they must stay under the budget above because a screen can show dozens of them (the keyboard shows ~35 keys; avoid one texture per key — one shared texture per size).
5. Do not bake the cyan selection outline, app-colour border or dwell bar: focus and app accents stay as separate code layers.

## 6. Verified dynamic widths (neutral-glass pack v1 trial)

Checked against the running code on the simulator (Classic/Sans text as configured at the time):
- **T1 filter pills** (Socials, height 36, radius 18, width = text + 44, min 92): **All 92, WhatsApp 120, Instagram 118**. The pack estimated 112 for Instagram and shipped no 120; `surface_120x36_r18.png` was rendered with the pack's `build_assets.py` `render(120,36,18)`. Widths snap to a packaged artwork only when within +6 px.
- **Surfaces in use for the Home / Socials / Health trial (18 files, ≈85 KB decoded as PNG):** S1 228×36, S2 252×36, S3 130×34, W1 298×135, W2 608×135, W3 298×282, W4 608×282, P1 608×380, C1/C3 576×216, C2 576×160, C4 372×160, C5 192×160, C6 576×44, T2 576×32, T1 92/118/120×36 (112 unused). Selected segments and the selected filter pill stay code-drawn accent fills.
- Only the surfaces a screen needs are decoded and uploaded; nothing is preloaded. The keyboard and every unlisted control still use the code-drawn body.

## 7. Surface sizes added for the full rollout

- **36 new unique geometries**, each generated for all four styles, so **144 new PNG files** (36 × 4), not 144 distinct shapes. After this change each style folder holds 54 files (Neutral 55, it carries one extra geometry from the first pack): `composeApp/src/main/assets/glass/<neutral|clear|frost|smoked>/surface_<w>x<h>_r<r>.png`.
- They cover the remaining screens: Tasks, list apps (Shopping, Recipes, Notepad), Workouts, Running, Weight, Songs, Identify, Lyrics, App-enter, the Ask card and pills, and the text screens (Memory, Money, Mymind, TextLines).
- `tools/glass_surfaces.py OUT_ROOT WxHrR ... [--force]` renders any missing size for all four styles with the same formulas as the packs. `GlassSurface.resolve()` uses the exact file, else the same height and radius within 6 px (nearest width), and logs `no packaged surface` when nothing fits, so a missing size is visible in logcat instead of silently falling back.
- Still code-drawn (no surface file): the gaze keyboard (G1–G11), by request, until a safe shared-resource approach is verified.

## 8. Panel colour (tint), scopes and caching

- **Independent of style.** Style (`panel_style`), opacity (`glass_opacity_pct`) and colour (`tint_default`, `tint_strength`, `tint_ovr_<scope>`) are separate preferences. Changing one never resets another.
- **Tint is applied in memory when a surface is decoded** (`PanelTint.apply`), never baked into a pack, so there is no asset per colour. Only the panel body is tinted (about the 35th-percentile alpha level and the area away from the corner highlights); the top-left and bottom-right reflections stay silver-white. Alpha is kept (the body gains a little alpha with strength), so the scenery still shows. Text, logos, Health rings and other graphics are drawn separately and are never tinted.
- **Colours:** Neutral (none), Ice Blue, Cyan, Emerald, Rose, Violet, Amber, and a custom colour stored as `#RRGGBB`. **Strength** is 10–100 % (default 60).
- **Scopes (most specific wins):** `widget:<catalog id>` for a Home widget (stable id such as `notify`, `fitness`, `spotify`, `tasks`, not its page position, so reordering keeps colours attached), `app:<id>` for an app's panels, cards, buttons and pills (plus `app:ask` for the Ask overlay), then the default tint. A Home widget and its app are separate scopes.
- **Cache:** decoded surfaces live in a bounded LRU keyed by style, file, opacity, tint and strength; nothing is cached per colour on disk. `MymavHudPrefs.glassVersion` changes with style, opacity or any tint setting and forces a full screen rebuild (the glasses screen is never patched in place). The phone coalesces changes (minimum 500 ms, at most one rebuild per ~1.8 s, latest value wins): 14 rapid changes produced 3 rebuilds in the simulator.
- **Focus stays distinct:** the Home selection outline is a cyan 2 px line in code; app-accent outlines and control focus are drawn above the surface. With a Cyan tint the selection outline is still visible but only by edge crispness, not hue.

## 9. Known limits and findings

- **Label length:** the glasses draw any single text label longer than about 44 characters as blank, whatever its width (measured: 43 characters render, 45 do not; the codebase's `takeForHud(44)` matches). Screens must cap or wrap dynamic text by character count. Fixed in Lyrics, Songs, App-enter, Weight and the text screens; the general guard belongs in `HudText.make` (a protected file, not changed).
- **Display-on race:** redundant `turnDisplayOn()` calls were echoed as a DISPLAY state change while the first uploads were in flight, and the SDK dropped them (`SKIPPING texture`, e.g. the status pill). `Mav2SdkSession` now skips the call while a request is in flight (10 s) or the SDK reports the display on, and after a real wake request waits 1 s for the echo before building. The flag is cleared on ready, unready, connect, disconnect and stop. Simulator only: 6 sends (startup + 5 standby to wake) gave 0 skipped textures; real-glasses timing is unmeasured.
- **Strength 100 % with opacity ≥ 250 %** makes small coloured status text (for example "Done" on a Rose panel) low-contrast; the default of 60 % is comfortable.
- **Glasses image cache must stay off** (checkpoint `d98c097`): the shared ~48–50 row table evicted fonts when images were `CachedManualDelete`. Packed-PNG encode cache stays on. Do not re-enable resident textures as a USB optimisation.
- **Not measured:** physical glasses, real Bluetooth timing, and any texture limits beyond the simulator.
