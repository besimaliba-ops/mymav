# Panel colour and rollout verification (2026-10-14)

Branch `claude/premium-glass-design`. **Everything below was checked on the Everysight Maverick AI Simulator (SDK 0.2.0, 640×400) with the debug build on the USB phone. Nothing has been checked on physical glasses.** Pictures in `docs/verification/2026-10-14/` are **window captures** of the simulator window (not native 640×400 captures) or **window captures** of the phone app (PixelCopy of the app window). Settings used: style Smoked unless stated, glass opacity 290 %, font Modern / Compact (the saved font was never changed).

Legend: PASS = seen working in the simulator · FAIL · NOT TESTED · HW = needs physical glasses.

## Screens (simulator)

| Screen | State | Result | Notes / capture |
|---|---|---|---|
| Home | all four styles | PASS | sheet-4 (Neutral, Clear Gloss, Soft Frost, Smoked); outer frame gone, both battery pills and HOME house unchanged |
| Home Health tile | cached reading | PASS | orange "steps · <time>" while cached; "No data yet" when none; startup preview shows the cached value |
| Health summary | live | PASS | "Live from Health Connect" |
| Socials | empty (All / WhatsApp / Instagram), WhatsApp message, Instagram message, media, detail, WhatsApp filter, notification access off | PASS | sheet-1 |
| Tasks, Shopping, Recipes, Notepad | rows, pills, focus | PASS | sheet-3 (Tasks), sheet-2 (Recipes) |
| Workouts select, Running, Weight | | PASS | sheet-2 |
| Songs | long title | PASS after fix | titles >44 chars were blank; now `takeForHud` |
| Lyrics | short and 49-char line | PASS after fix | 49-char current lyric was blank; now wrapped by characters |
| Identify | 3 images | PASS | sheet-2 |
| Conversate, Spotify, Memory, Money, Mymind App-enter | | PASS | |
| Memory / Money / Mymind / TextLines text screens | restyled | PASS | sheet-3; long lines wrap to ≤38 chars |
| Ask overlay | card and AUDIO/TEXT/CANCEL pills | PASS | sheet-2 |
| Gaze keyboard | unchanged (by request) | NOT TESTED | no visual change made |
| All of the above | physical glasses, BLE timing | HW | |

## Colour controls

| Check | Result | Evidence |
|---|---|---|
| Phone card: swatches, custom picker, strength, preview in the selected style, Home/Apps lists, Use default, Reset colours | PASS | phone-window-* images |
| Default tint applies live to every screen | PASS | sheet-5 |
| Per-widget override saved by id (`tint_ovr_widget:fitness`) | PASS | Health emerald, others rose |
| Reorder (in memory, saved order untouched): colour stays with the widget | PASS | sheet-5 |
| App override (`tint_ovr_app:tasks`) inherited by its rows and pills; other apps keep the default | PASS | Tasks violet, Shopping rose |
| "Use default" returns the widget/app to the default | PASS | |
| Reset colours clears default, strength and all overrides; style, opacity and font kept | PASS | persisted keys read back |
| Style change keeps colour; colour change keeps style | PASS | prefs read back through four styles |
| Restart persistence | PASS | app restarted by a rebuild; keys and glasses identical |
| Rapid changes | PASS | 14 changes in ~3 s → 3 rebuilds; a 1.8 s picker drag → 2 rebuilds; latest value won |
| Reconnect (drop / rejoin) with colour set | PASS | Cyan @60 % restored, no skipped textures |
| Text, logos, Health ring, mascot not recoloured; corner reflections stay silver | PASS (visual) | |
| Home selection outline stays distinct | PASS with note | clear on Rose/Violet; with Cyan the cyan outline is visible by edge only |
| Readability | PASS with note | at 100 % strength and opacity 290 %, small coloured status words ("Done") are low-contrast |

## Logcat and the startup upload failure

- Logcat access: works (helper-written dump). Last run (20:48:57–20:59:59): **0** `SKIPPING texture`, **0** `no packaged surface`, no crashes.
- Startup status-pill failure: both earlier skips (114×18) had the DISPLAY echo in the same millisecond as the upload start. Fix in `Mav2SdkSession` (see PANEL_ASSET_SPEC §9). Simulator test: startup + 5 standby→wake cycles, 0 skips, display wakes each time (`SCREEN ON`). A wake test without the 1 s settle did lose one texture, which is why the settle exists. **HW:** the 1 s settle and the 10 s in-flight window are simulator-derived.

## Settings (your data)

Snapshot before testing (persisted): `font_style=Sans` (legacy key), `font_style_v2=Modern`, `text_size=Compact`, `glass_opacity_pct=290`, `panel_style=Smoked`, `tint_default=neutral`, `tint_strength=100`, no overrides. After testing the same map was read back, identical; applied values match.
