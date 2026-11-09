# Offline batch — Memory Sight, Money, Mymind & HUD polish

Branch: `cursor/offline-batch-memory-money-mymind-9f08`  
SDK debug builds: `-Pmaverick.useSdk=true`  
Proof shots: `media/offline-batch-*.png`

## Avatar modes (Settings)

Open **Settings** → **Mode** chips:

| Mode | Behaviour |
|------|-----------|
| **Active** | Full motion — page runs, jumps into apps, top-bar dock. |
| **Sleep** | Bottom-left dock; no swipe/jump. **1s gaze** still summons the speech bubble. |
| **Off** | Completely hidden on glasses home. |

Modes persist across launches.

## Phone “Back to home”

In any mini-app, **Back to home** returns to the phone’s **Glasses** tab only.

- Glasses mini-app HUD **stays open**.
- Glasses home is restored only via glasses **HOME** (pill / long-tap).

## Memory Sight (Revu-equivalent)

**Apps → Memory Sight** (also on glasses home board as **Memory**).

- Remembers belongings + place/time/context through the day (Revu-style).
- Runs in the background while MyMav is alive; emulator uses a **mock timeline** (keys, wallet, headphones) persisted locally.
- **Push timeline to glasses HUD** shows today’s sightings on glass.
- Phone leave does **not** close the glasses Memory HUD.

## Money Exchange

**Apps → Money**.

- Gaze **1s** at a price (AI camera) → convert to **home currency** (default **GBP**).
- Change home currency in the **Home currency** field on the Money screen.
- Foreign currency from symbol when available; emulator: **Demo gaze: EUR €12.50 → GBP**.
- Background mock + **Push quote to glasses HUD**.

## Mymind

**Apps → Mymind**.

- Remembers conversations; people stubs (voice/face recognition mocked).
- Extracts tasks/dates (personal + fun).
- Pages: **Daily / Weekly / Monthly** — summaries + todos.
- Background mock while MyMav runs; **Push … board to glasses HUD**.

## HUD polish (this batch)

- Brand logos / Health rings: no white/black source plates; letterbox matched to frosted panel (avoids optical-clear holes).
- Widget panels: denser opacity + soft gloss shine (home + mini HUDs).

## Emulator install

```bat
gradlew.bat :composeApp:installDebug -Pmaverick.useSdk=true
```

AVD: `MyMav_API34`. Headless boot may need `-no-window -gpu guest` if a crash dialog blocks GUI boot.
