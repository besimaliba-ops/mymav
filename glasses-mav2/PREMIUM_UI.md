# Premium glasses UI handoff

Branch: `chatgpt/premium-glasses-ui`
Base: `546d41f7461bea9b731ca8485751441fffd3c7d3`

## Changes

- Shared glass surfaces: blue-black gradient, restrained sheen, single-pixel outer border and softer inset rim. Applies to screens using HudPrimitives.
- Twenty image-based jewel badges; 36px home badges and 56px app entry badges.
- Home focus uses sharp/soft image pairs. Only selection changes move the pairs; dwell updates do not upload or decode badges. Softness is filtered downsampling, not a live backdrop blur.
- Home titles retain natural casing and have width reserved for artwork/video labels. App headings use the existing antialiased font system.
- Entry dissolve shortened from 200ms to 140ms; existing failure cleanup retained.
- Existing widget grid, gaze hit regions, font preference, functional brand logos, activity rings and status glyphs retained. This is a styling pass, not a replacement carousel or every-icon redesign.

## Validation

Passed git diff --check; verified all 20 source crop rectangles are inside the RGBA atlas and their centres contain artwork. Reviewed title, badge, metric and HOME pill bounds. Protected files and phone modules unchanged.

Compilation attempted with `bash gradlew :glasses-mav2:compileDebugKotlin -Pmaverick.useSdk=true --offline`. Blocked before compilation: Gradle 8.13 is absent and services.gradle.org is unreachable here. No emulator or physical glasses test was available. Smoothness, optical brightness and texture stability are not yet device-verified.

On Windows, run `./gradlew.bat :glasses-mav2:compileDebugKotlin -Pmaverick.useSdk=true` using the existing SDK credentials. Then test all home pages, gaze focus/dwell, app entry/HOME, preview/video tiles, all font sizes, repeated page changes and reconnect. Verify soft/sharp badges do not swap textures or obscure titles. Judge brightness outdoors as well as indoors.

## Import without merging

From the MyMav repo root, with a local bundle file:

```powershell
git status
```

Stop if there are uncommitted changes. When clean:

```powershell
git fetch PATH\TO\MyMav-premium-glasses.bundle chatgpt/premium-glasses-ui:chatgpt/premium-glasses-ui
git switch chatgpt/premium-glasses-ui
```

If the destination branch already exists, stop and inspect it; do not force the fetch. Do not merge automatically. This bundle is incremental and requires the base commit above, which was in the uploaded repository.

## Artwork provenance

Created with the built-in image-generation tool. Asset: `src/sdk/assets/hud/premium-app-badges.png`. Source atlas is 1254 x 1254 RGBA; measured cell centres are recorded in PremiumHudIcons.kt.

Prompt: Create a production UI texture atlas, a square transparent PNG containing exactly 20 separate glossy 3D circular app badges in a 4-column by 5-row grid. Premium jewel glass domes, strong colored gradient depth, small white top-left specular shine, crisp chunky raised white symbols, straight-on view, no perspective tilt, no text or labels. Row-major symbols: purple notification bell, green music note, blue speech bubble, orange fork and knife; violet shopping bag, cyan camera lens, green soccer ball, red heart pulse; teal dumbbell, blue overlapping screens, violet landscape photo, amber checklist; emerald shopping basket, orange chef hat, gold notepad, purple eye; green currency coins, cyan brain, blue calendar, orange stopwatch. Symbols readable at 36px; transparent background.


## Shared glass design system (GlassKit) — how new apps and widgets use it

All glasses app screens compose `GlassKit` (glasses-mav2/.../hud/GlassKit.kt) instead of drawing their own chrome:

- `appPanel` one smoked-glass panel per screen (single fine edge; never stack outlines).
- `header(title, iconId, subtitle)` 36 px icon from `PremiumHudIcons`, natural-case title, muted subtitle. Draw the HOME pill with `HudPrimitives.drawHomePill` (fixed gaze-hit position x482,y28,130x34; never move it).
- `tabs`, `card(selected)`, `button`, `progress`, `message` (empty/loading/error), `hero`, `rows`, `footer`.
- Text: white primary, `HudTheme.InkMuted`/LightBlue support, cyan (`FocusGlow`) for selection only. Body text 0.68+ and pill labels 0.62 (the hard floor is `MIN_TEXT_SCALE` 0.46, for dense captions only); no tracked all-caps hint strings. Always pass `maxWidth` so labels fit.
- Colour types: `HudPrimitives.label` takes `M2Color`; `labelInk` takes a `UInt` ink. Do not mix them in one `if`.
- Selected state = lit edge + faint wash (`card(selected = true)`); do not add `focusGlow` rings around cards.
- Keep geometry that gaze hit-testing depends on (row y/step, pill positions) when restyling a screen.
- Never ship a healthy/ready state without data: widgets use `HomeWidget.live`; apps show `message(...)`.
- Mascots: see `docs/MASCOT_ASSET_SPEC.md`.

### Coverage checklist (branch claude/premium-glass-design)
Home board + status bar, Socials widget/app, Health widget/app: redesigned, simulator-verified (Bold/Standard).
Tasks, Shopping/Recipes/Notepad lists, Workouts, Running, Weight, Songs, Identify, Lyrics, App-enter, Keyboard (text sizes only):
restyled on the kit; Tasks, Shopping list and Keyboard simulator-verified. Others compile-verified only.
Not yet restyled: Ask modal/pills, mascot artwork (blocked on assets), phone-side publisher states (Spotify playing/paused, Conversate status).


## HudText.layoutTokens (font workaround)

Appends a short zero-width-character run to every drawn string so the simulator's layout cache cannot reuse a
string drawn earlier at a different font/size. Simulator-observed only; its behaviour on physical glasses is
**unverified**. Switch off with `HudText.layoutTokens = false`. Not a supported SDK mechanism.

## Layer order (every panel)

panel body -> gradient + top-left sheen + bottom-right reflection -> content (icons, text, values, controls) ->
focus ring / dwell indicators (kept outside the content area, e.g. the Spotify dwell bar sits under the pill).
Note: thin rects drawn *inside* a gloss-covered pill are not visible on the simulator; draw progress outside it.
