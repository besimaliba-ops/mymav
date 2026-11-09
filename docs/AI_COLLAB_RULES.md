# Working with multiple AI tools on MyMav

Modules
- PHONE UI (Android app): composeApp/, miniapps/*
- GLASSES UI + logic: glasses-api/ (shared models/prefs), glasses-mav2/ (HUD screens, fonts, SDK session)
- Do-not-touch without asking: glasses-mav2/.../hud/HudFonts.kt, HudPrimitives.kt, HudEncodedCache.kt,
  Mav2SdkSession.kt, assets/fonts/*, tools/*.bat, tools/make_sif2.py, .gitignore

Rules
1. Never work on `main` or `claude/premium-hud-polish`. Make your own branch: `cursor/<task>` or `chatgpt/<task>`.
2. `git status` first. If there are uncommitted changes you didn't make, stop and say so.
3. Small commits with clear messages; no mass reformatting or renaming.
4. Don't change glasses code when asked for a phone change (and vice versa).
5. Finish by listing the files you changed. Don't merge; the owner merges.

## User-verified checkpoint (do not merge)

User-verified checkpoint **`d98c097`** on `cursor/typography-rollout-89ea` (17 Oct 2026). Keep the current look, data and settings. See `docs/HANDOVER.md`.

## Glasses resource table (protected)

SDK 0.2.0 shares a **~48–50 row** glasses cache (type-4 fonts + type-3 images). Resident images with `CachedManualDelete` evicted font faces while paging; labels then jumped size/face.

- Glasses-side image cache (`HudImageCache`) **must stay off**. Textures upload `NotCached`.
- Packed-PNG **encode** cache (`HudEncodedCache`) **stays on** (phone memory only; it does not take glasses slots).
- Future USB / upload-speed work must target that shared-table failure (quota, isolation, measured Home-page cycle), **not** turning `HudImageCache.enabled` back on.
