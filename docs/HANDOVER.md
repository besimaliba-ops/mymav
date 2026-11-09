# MyMav handover

User-verified checkpoint: **`d98c097`** on `cursor/typography-rollout-89ea` (17 Oct 2026). **Do not merge.** Keep the current look, data and settings.

Bundle: `bundles/mymav-typography-rollout-d98c097.bundle` (`b6d5ab7..d98c097`).

## Glasses image cache (do not reverse)

SDK 0.2.0 shares a **~48–50 row** resource table (type-4 fonts and type-3 images). Resident textures uploaded as `CachedManualDelete` evicted font faces when Home pages built new images. `HudFonts.pick` then fell through to stock scale or a neighbour size — the same screen looked like a different font after paging.

**Must stay off:** `HudImageCache.enabled` in `HudPrimitives.kt` (confirmed `false` at this checkpoint and at HEAD). Every HUD texture uses `M2CacheScope.NotCached`.

**Stays on:** phone-side packed-PNG encode cache (`HudEncodedCache`). That is process memory only; it does not occupy glasses slots.

USB / upload-speed work must start from this failure (reserved font quota, isolated slots, or a measured full Home-page cycle that keeps fonts). Turning the resident image cache back on is not an optimisation.

Protected: `HudFonts.kt`, `HudPrimitives.kt`, `HudEncodedCache.kt`, `Mav2SdkSession.kt` — see `docs/AI_COLLAB_RULES.md`.

## Also verified at `d98c097`

Sans hierarchy, heading shadows off, Conversate measured wrap, Health nested hearts + phone Daily snapshot, navigation regressions after cycling every Home page (Gallery does not auto-open). Do not restyle these.

Outstanding (not blocking): Ask live, Navigation/Health/Fitness **apps**, live Shop/Food/Identify scans, Spotify, gaze keyboard, Health ring **fill** (needs phone goals), physical camera/mic (December).
