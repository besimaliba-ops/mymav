# MyMav

Companion app for **Everysight Maverick AI / AI Pro** glasses. Mini-apps (Notify, Spotify, Conversate, Food, Shop, Identify, and others) run inside one shell that owns SDK init and the simulator or BLE connection.

This snapshot is meant for a friend with their own Maverick glasses (or the Windows Glasses Simulator). **Bring your own keys.** Nothing in this repo is a shared Everysight SDK key, GitHub Packages token, or AI API key.

## Layout

| Module | Role |
| --- | --- |
| `composeApp` | Phone shell (Glasses / Apps / Settings + HUD preview) |
| `glasses-api` | `GlassesSession`, `MiniApp`, registry |
| `glasses-stub` | Default backend (builds without GitHub Packages) |
| `glasses-mav2` | Real `maverick-ai-sdk:0.2.0` when `-Pmaverick.useSdk=true` |
| `miniapps/*` | Notify, Spotify, Conversate, Food, Shop, Identify, Football, Fitness, Mirror, Gallery, Tasks, and related |

Android **API 30+**. Package `com.mymav.app`.

## What you need

1. **Your Everysight `sdk.key`** (required for the simulator **and** physical glasses). Request a Maverick AI / AI Pro developer key at https://www.everysight.com/pages/sdk — portal: https://everysight.github.io/maverick-ai-docs/getting-started/api-key/
2. **GitHub Packages access** (required to download the real SDK). A GitHub personal access token with `read:packages`.
3. **JDK 17+** (Android Studio’s bundled JBR is fine) and the Android SDK. The Everysight SDK expects NDK **28.2.13676358**.
4. Either:
   - **Windows Glasses Simulator 0.2.0**, or
   - **Physical Maverick AI / AI Pro glasses** + an Android phone with USB debugging.

Do **not** reuse anyone else’s `sdk.key`, Gradle `gpr.key`, OpenAI / Gemini / SerpAPI keys, or keystore passwords.

## 1. Place your `sdk.key`

Copy **your** key file (the name must stay `sdk.key`) to:

```
composeApp/src/main/assets/sdk.key
```

That path is gitignored. The app will not talk to the simulator or glasses without it.

## 2. GitHub Packages (real SDK)

In `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`), add **your** credentials:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_PAT_WITH_read_packages
```

Never commit this file. Create a classic PAT at https://github.com/settings/tokens with scope `read:packages`.

Maven repo used by this project:

`https://maven.pkg.github.com/everysight-maverick-AI/mav-ai-android-maven`

Artifact: `com.everysight.mav2:maverick-ai-sdk:0.2.0`.

## 3. Windows Glasses Simulator

Public download (no login):

- Windows: https://storage.googleapis.com/evs-pub/maverickai/simulator/v0.2.0/GlassesSimulator-0.2.0-x64-win.exe
- macOS / Linux builds are listed at https://everysight.github.io/maverick-ai-docs/guides/simulator/

Optional checksum helper (Git Bash / WSL):

```bash
./tools/simulator/download-simulator.sh
```

SHA256 of the Windows 0.2.0 exe: `8d640d9b6eb28e9eed2f1194076ad37a1931f7c63bcadd2e43db7d9e2c70f174`.

Run the simulator. Dashboard: http://127.0.0.1:8765/ · WebSocket: `ws://127.0.0.1:8765/ws`. Allow inbound **TCP 8765** if the phone is on another device. Note the PC’s LAN IPv4 (same Wi‑Fi as the phone).

## 4. Build and install

If Gradle says **JAVA_HOME is not set**:

```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
```

From this repo (PowerShell or cmd), **real Everysight SDK** (what you want on glasses or the simulator):

```bat
gradlew.bat :composeApp:assembleDebug -Pmaverick.useSdk=true
gradlew.bat :composeApp:installDebug -Pmaverick.useSdk=true
```

Physical phone helper (skips emulators; uses the first USB-debugging phone, or pass `-Serial`):

```powershell
.\tools\install-phone.ps1
```

Stub SDK (UI only, no glasses/sim connection):

```bat
gradlew.bat :composeApp:assembleDebug
```

On a POSIX shell:

```bash
./gradlew :composeApp:installDebug -Pmaverick.useSdk=true
```

## 5. Run against the Windows simulator

1. Start Glasses Simulator 0.2.0 on the PC.
2. Install MyMav on a phone or emulator (`-Pmaverick.useSdk=true`).
3. Open **Settings → Connection**. Set the simulator host:
   - Emulator on the same PC: `10.0.2.2` (and `adb reverse tcp:8765 tcp:8765` if you use `127.0.0.1` instead)
   - Physical phone: the PC’s **Wi‑Fi IPv4**, not `127.0.0.1`
4. Tap **Save & connect simulator**.
5. **Apps** tab — tap to open; hold-drag to reorder; **··· → Size for glasses home** (1×1 / 1×2 / 2×1 / 2×2).
6. **Glasses** tab — show glasses home and HUD preview.

On-lens:

- **1s gaze on HOME** (sim: Touch Injection **Up** / D-pad up) or long-tap → glasses home.
- **1s left/right edge gaze** → next/previous home page.
- Gaze keyboard: ABC + emoji, DEL / CLEAR; used by Notify reply and Spotify search.

## 6. Run against physical Maverick glasses

1. Same build (`-Pmaverick.useSdk=true`) with **your** `sdk.key`.
2. Phone Bluetooth on; glasses powered and in range.
3. In MyMav **Settings → Connection**, tap **Device / BLE** (not the simulator host).
4. Grant BLE / location permissions if Android asks.
5. Wait until status shows connected. Then open **Glasses** home as above.

Physical glasses are required for real eye tracking, IMU, and on-device camera/mic. The Windows simulator covers HUD layout, tap, and a PC camera/mic stand-in.

## Optional AI keys (typed in the app, never committed)

In **Settings → Assistant & AI**, paste **your** keys if you want live Ask / Shop prices:

- ChatGPT (OpenAI)
- Gemini
- SerpAPI (Shop / Identify → Find prices)

These are stored in app prefs on the phone. Do not put them in `local.properties`, `.env`, or the repo.

## Notify (WhatsApp / Instagram)

Apps → **Notify** → grant notification access → **Start companion**. Leave the sticky notification running. Battery: **Unrestricted** on OEM phones that kill background apps. Only WhatsApp and Instagram are mirrored. Without those apps, use **Inject test notification** on the Notify screen.

## Deep links

`mymav://mini/<id>` — `notify`, `spotify`, `conversate`, `food`, `shop`, `identify`, `football`, `fitness`.

## Docs in this repo

Everysight portal: https://everysight.github.io/maverick-ai-docs/  
SDK hub: https://github.com/everysight-maverick-AI/sdk

In-tree notes: `docs/HANDOVER.md` (HUD checkpoint), `docs/ASSISTANT_CAPABILITIES.md`, asset specs under `docs/`.
