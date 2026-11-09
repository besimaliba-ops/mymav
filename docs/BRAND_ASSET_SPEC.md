# Glossy brand medallions (WhatsApp, Instagram, Spotify)

Drop-in replacements for the existing masters the HUD already loads (`BrandHudIcons`), in `composeApp/src/main/assets/brand/`:

| File | Brand |
|---|---|
| `brand/whatsapp-clean.png` | WhatsApp |
| `brand/instagram-clean.png` | Instagram |
| `brand/spotify-clean.png` | Spotify |

## Format
- 512 x 512 px, PNG, 32-bit RGBA, straight (non-premultiplied) alpha, transparent corners, sRGB.
- Glossy circular medallion with the ORIGINAL brand glyph geometry and colours (no redrawn/cropped logos, no music note
  for Spotify). Restrained top-left specular highlight and a faint bottom-right reflection, both inside the medallion.
- Medallion diameter 480 px centred (16 px transparent padding on every side). The glyph sits inside with the brand's own
  proportions. No outer glow, drop shadow, outline stroke, text or background scenery (additive display: the glow would
  look like a milky halo).
- The app halves-downscales the master to the exact size, so keep detail bold: the glyph must read at 30 px.

## Display sizes (px, square, drawn 1:1)
| Use | Size |
|---|---|
| Home Socials tile (WhatsApp, Instagram), normal tile | 60 |
| Home Socials tile, short tile | 46 |
| Home Spotify tile, normal | 72 |
| Home Spotify tile, short tile | 52 |
| Socials app header (WhatsApp, Instagram) | 36 (currently 30, will move to 36) |
| Spotify song list / lyrics header | 36 |

Optional: also supply a 144 x 144 version (`*-144.png`) hand-tuned for the 72 px use if downscaling looks soft.

## Status (2026-10-14)

Delivered and installed: `whatsapp-clean.png`, `instagram-clean.png`, `spotify-clean.png` (512x512 RGBA, 480 px
medallion, 16 px transparent padding, stray rim pixels removed, no colour outside the circle). Glyph geometry
was compared with the previous marks and matches (Instagram changes from a rounded square to the circular
medallion by design; Spotify arcs are slightly heavier). Previous masters remain in git history.
Checked at 72/60/52/46/36 px on the HUD blue and in the simulator (Home tiles, Socials header).
