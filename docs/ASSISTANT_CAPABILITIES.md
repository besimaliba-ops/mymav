# MyMav assistant: capability audit

Audited on branch `claude/premium-glass-design` (2026-10-14). Status: **Working** = real provider, used by the assistant today;
**Partial** = real code exists but not wired to the assistant or incomplete; **Missing** = nothing exists; **Blocked** = needs an
account, key, paid service or hardware access that is not in the repo/environment.

How a request flows today: glasses VTXT / typed keyboard -> `GlassesHomeController.handleAssistantUtterance` (a chain of regex
intents) -> `MyMavAssistant` (tiny rule set) -> `MymavAi.ask` (the user's own OpenAI or Gemini key, plain chat with 8 turns of history,
no tools, no search, no sources) -> speech bubble (`AskPager` paging).

| # | Capability | Status | What exists / provider | Gap |
|---|---|---|---|---|
| 1 | One assistant, follow-ups, states | Partial | Regex router + OpenAI/Gemini chat with short history; thinking/answer states; cancel via long tap | No intent/tool layer, no searching/error states, typed phone input not routed the same way |
| 2 | Current web answers + sources | Missing | No search provider. Gemini key present as an option | Needs grounding (Gemini Google Search tool) or a search API; no source list on phone |
| 2 | Pictures | Partial | Wikipedia page thumbnails (keyless) | No attribution/source links, no next/previous, no failure states |
| 3 | Translation | Missing | LLM could answer; nothing dedicated | Pronunciation, playback (no TTS anywhere), Conversate captions |
| 3 | Units / currency | Missing | Money mini-app only seeds a demo quote; no FX fetch | Real FX provider + timestamp/cache; unit maths |
| 3 | Camera questions | Missing | Phone camera used by Food/Identify; no glasses-camera capture in repo | Glasses camera API unverified (hardware), vision call |
| 3 | Navigation | Missing | Location used only for weather (open-meteo) | Routing provider + key, HUD turn screen |
| 3 | Calendar read | Partial | READ_CALENDAR, 48 h briefing read | Create events needs WRITE_CALENDAR + flow |
| 3 | Reminders/tasks | Working | Tasks bridge via voice (`MymavTaskIntent`) | Distinct from calendar events: keep |
| 3 | Notes search | Partial | NoteBridge.find by title match | Date ranges, summaries, references |
| 3 | Meeting recorder | Missing | Conversate is a stub with demo cues (no STT) | Recording, transcription provider, summary |
| 3 | Messages by voice | Partial | Notify listener reads notifications | No send path, no contacts/confirmation flow; platform send APIs limited |
| 3 | Sports / news | Partial | Football uses a mock feed | Real feed (needs provider/key); news via keyless RSS possible |
| 3 | Spotify | Partial | Real client: search, play track, next/prev, play/pause, current track (Web API + auth) | Not reachable by voice |
| 3 | Personal memory | Missing | `mymind`/`memorysight` are tiny unrelated stores | Memory store, phone screen, on/off |
| 3 | Proactive help | Missing | Timers exist | Opt-in nudges, quiet hours, dedupe |
| 3 | Smart home | Blocked | Nothing configured | Needs an authenticated hub (e.g. Home Assistant URL + token) |
| 3 | Learning mode | Missing | Notes store exists | Flashcards/quiz + progress |
| 4 | Permissions | Partial | CAMERA, RECORD_AUDIO, location, READ_CALENDAR, notifications declared | WRITE_CALENDAR, contacts, SMS not declared; denial handling per feature |


## Status after implementation (2026-10-15)

Verification: **Sim** = seen on the Everysight simulator speech bubble; **Phone** = seen on the phone; **Not run** = built but
not exercised by me. Nothing was checked on physical glasses.

| Capability | Status | Provider / how | Verified |
|---|---|---|---|
| One assistant (voice, glasses keyboard, phone "Ask MyMav" field) | Working | `AssistantRouter` in front of `MymavAi`; follow-ups via 8-turn history | Sim: follow-up ("when was she born"). Phone field: renders, typing not run |
| States, cancel | Working | Thinking/searching/answer/error screens; CANCEL pill now kept during thinking; `cancelAssistant()` | Sim: cancelled request shows no late answer |
| Web search + sources | Working if Gemini quota allows | Gemini + Google Search grounding; sources on phone card | Error path (HTTP 429) seen honestly; a successful grounded answer was not seen |
| Pictures with attribution | Working | Wikipedia / Wikimedia Commons, credit + license on phone | Earlier commit, Sim + Phone |
| Translation | Working (AI) | `MymavAi.generate`, labelled AI | Sim |
| Live translated captions | Built | Android SpeechRecognizer + AI translation, provisional/final | Sim: start/stop only; speech not run |
| Units / currency | Working | Local maths; frankfurter.dev with timestamp, cached label | Sim |
| Camera questions | Blocked | No glasses camera frames in this build (simulator: NO SIGNAL); answers say so | Sim |
| Navigation | Working | Phone location, Nominatim places, FOSSGIS OSRM foot routes, turn updates in the bubble | Sim: nearby, route, stop. Rerouting/stale-fix paths not run |
| Calendar read/create | Working | CalendarContract, confirmation + read-back | Sim + earlier Phone check |
| Notes search + summary | Working | NoteBridge.search, AI summary of matched notes only, references on phone | Sim + Phone |
| Meeting recorder | Working | Foreground mic service, Gemini transcript + summary, Notepad | Phone + Sim: silent 10 s recording end to end. Spoken content not run |
| Messages by voice | Working (SMS), reply-only for WhatsApp/Instagram | Contacts + SmsManager; NotificationReplyStore | Sim: permission denied, unknown contact, disambiguation, confirmation, cancel. A real send was not run |
| Calls | Working | Confirmed, then opens the dialler (user presses call) | Sim to the confirmation |
| Sports | Working | ESPN scoreboard feed, LIVE/FINAL/SCHEDULED + update time | Sim (fixtures) |
| News | Working | Google News RSS | Earlier, Sim |
| Spotify | Working | Existing Web API client | No active device on the phone, so playback success was not seen |
| Personal memory | Working | `MymavMemory`, phone card, on/off | Sim + Phone |
| Proactive help | Built | Opt-in; quiet hours, cap, snooze/dismiss, once a day | Test nudges only; timed firing not run |
| Smart home | Blocked | No hub connected; answers say so | Sim |
| Learning mode | Working | AI cards from notes only, progress saved | Sim |
| Read aloud | Built | Phone TextToSpeech, opt-in | Engine start in logcat; audio not heard |


## Completion pass (2026-10-15 ~02:30): final labels

Labels: **V** = Verified on phone/simulator (real service responses unless stated). **A** = Implemented but awaiting a specific test
or account setup. **H** = Hardware-dependent, deferred until December (physical glasses). "Simulated" means the input was injected
or came from the simulator, not from a person or the real device. Nothing here has been run on physical glasses.

| Capability | Label | What was actually seen | Not proven |
|---|---|---|---|
| Typed question in the phone Ask field | V | Typed with injected key events, submitted with the keyboard Send action; field clears, button becomes Cancel while working and returns when the answer arrives (`build/_evidence/shot-typed-ask-answer.jpg`) | Soft-keyboard (IME) appearance was not captured; hardware key events were used |
| Question, follow-up, answer | V | "Jane Austen" then "when was she born" (simulator bubble, earlier); "who wrote Pride and Prejudice" typed on the phone | Pictures-on-request sequence re-run through the phone field: not repeated this pass (pictures were verified earlier on sim + phone) |
| Cancel and error recovery | V | `acancel` during thinking: no late answer; Gemini quota error shown with the exact fix; phone field shows "No answer in 75 s" notice on timeout (code path, not triggered) | The 75 s timeout notice itself was not triggered |
| Current web answer | V (ChatGPT) / A (Gemini) | Real ChatGPT web search (bitcoin price, changes between runs, timestamp). ChatGPT returns no page links for finance widgets, so the card says "Live ChatGPT web search, no page links returned" and never shows page links it didn't get. Unsourced model answers are labelled "AI answer - not searched" (`shot-typed-ask-search.jpg`) | A cited news answer with page links not yet seen |
| Gemini 429 diagnosis | V | Every flash model returns `RESOURCE_EXHAUSTED` ("exceeded your current quota ... billing") for Google Search grounding only; plain generation works. This is exhausted quota, not a short rate limit, so retrying cannot help (rate limits are retried up to 2x). Fix: enable billing on the key's project in Google AI Studio (aistudio.google.com), or use a key from a billed project | Whether billing clears it |
| Mascot (Pip) in activation, thinking, answers, pictures | V earlier (Sim) | Verified during the mascot task; avatar preference was not changed in this pass | Not re-checked in this pass |
| Notes search and summary | V | Phone + sim | |
| Memory | V | Phone + sim | |
| Calendar read / create / read-back | V | Event created and read back; test event deleted afterwards by id | Time-zone edge cases (travel), duplicate prevention after reconnect: A |
| Calendar reminders | A | Reminder row is written (METHOD_ALERT); firing was not observed | |
| Navigation (simulated phone location) | V (route, live turns, stop) | Real Nominatim + OSRM responses; location was the phone's real fix | Rerouting off-route, stale or missing fix, reconnect: A (needs a walk or mock-location test) |
| Sports | V | ESPN scoreboard data | Live match in progress not seen |
| News, currency, units, translation text | V | Sim | |
| Meeting recorder | V (silent 10 s, error path only) / A (spoken) | Silent recording saves nothing and says so. Spoken sample with discussion + decision + task: not run, I cannot play or hear audio here | See guided test G1 |
| Conversate captions + translation | A | Start/stop verified; spoken input not run | Guided test G2 |
| Read aloud | A | TTS engine starts; audibility not heard | Guided test G3 |
| Messages (SMS) | V (flow) / A (send) | Disambiguation, explicit confirmation, cancel, permission-off. Nothing was sent | Guided test G4 (you send to a contact you pick) |
| Calls | V to confirmation | | |
| Proactive nudges | V (test nudges) / A (scheduled) | Test nudges fired; quiet-hours code present | A real scheduled nudge, quiet-hours suppression and restart de-dupe: A (set a window 2 minutes ahead; see G5) |
| Spotify | A (blocked) | Client implemented; no active device so playback not seen | Open Spotify on the phone, start any track, then say "next track" |
| Smart home | Blocked (unconfigured) | Refuses honestly | Needs a hub URL + token |
| Learning mode | V | Sim | |
| Brightness slider (phone to glasses) | V | 47% restored | |
| Camera: SDK frames | V (simulator feed) / H (glasses) | `Evs.visionService.startCapture` returned true, `isCameraOpen=true`, 640x480 JPEG frames at about 18 fps in the simulator. No extra Android permission is needed for this path; the SDK manifest asks only for Bluetooth, location, internet, foreground service | Frames from physical glasses: H |
| Camera: image understanding | V (source: simulator feed) | "What am I looking at" grabs a frame, sends it to a vision model, answers "blurry, light area one side..." and states "Image from the SDK camera feed (simulator - not physical glasses)" (`shot-camera-vision-answer.jpg`) | Phone-camera image source not built; glasses camera: H |

### Camera investigation (exact findings)

- SDK path: `Evs.visionService` (`M2AIVisionService`): `startCapture(M2CaptureOptions)`, `takePicture()`, `registerListener(IM2AIVisionEvents)` giving `onFrameReceived(IM2AIFrame)`, `onError(Errors, msg)`; `IM2AIFrame.toJpegData()`.
- Options are a sealed type (`M2ContinuousOptions`, `M2StillOptions`, `M2VideoOptions`); we use `M2ContinuousOptions(640x480 x2, Center, Auto exposure, Lowest compression, pauseWhenBusy=true)`.
- Simulator: frames arrive. The simulator's "NO SIGNAL" is the display video, not a statement about the camera API; do not conclude the physical glasses cannot supply frames.
- What is missing: (1) a physical pairing to see real frames, (2) latency/bandwidth and battery over BLE/Wi-Fi, (3) the image source is currently hard-coded as "simulator" in `Mav2Camera.sourceLabel`; change it when real glasses are connected. The probe and the grab live in new files (`Mav2CameraProbe.kt`, `Mav2Camera.kt`), so no protected file (`Mav2SdkSession.kt`) was touched.
- Debug: `camprobe` command logs the raw behaviour under tag `DBGCAM`.

### Guided tests awaiting you (not run: no audio in or out for me)

- **G1 Spoken meeting.** Settings, Meeting recorder, Start. Say: "We discussed moving the launch. We decided to ship on the second release. Sam will update the notes." Stop. Expect a summary with separate Discussion, Decisions and Tasks lines and no owner or date you did not say.
- **G2 Captions + translation.** Say "start captions in Spanish", speak two sentences, "stop captions", then start again. Expect provisional then final lines, translated, and a clean restart.
- **G3 Read aloud.** Turn on Read aloud, ask a short question. Expect speech from the intended speaker; say "stop" mid-answer and it should cut off. This does not prove glasses audio routing.
- **G4 Message.** Say "text <a contact you choose> hello test". Expect the contact choice if ambiguous, then a confirmation; nothing is sent until you say yes.
- **G5 Proactive.** Settings, Proactive: set quiet hours to cover now and wait: expect no nudge. Move them away and wait the window: expect one, and none after an app restart on the same day.

### December hardware checklist

1. Pairing: Bluetooth pair, reconnect after glasses sleep, after phone Bluetooth off/on, after app restart.
2. Permissions on phone: Bluetooth connect/scan, location, microphone, notifications, contacts, SMS, calendar.
3. Camera: `camprobe` and "what am I looking at" with real glasses; check frame rate, latency, battery, error codes; update `Mav2Camera.sourceLabel`.
4. Microphone: glasses mic via `M2MicService` for the meeting recorder and captions (phone mic was used here).
5. Audio routing: read-aloud and Spotify on glasses speakers vs phone; stop/cancel behaviour.
6. Real gaze input: dwell targets, the 1 s look to summon, edge paging, CANCEL pill reachable.
7. Brightness and readability: indoors, outdoors, auto brightness; the phone slider uses a 0 to 255 scale (percent = value/255).
8. Fonts and texture uploads: fonts, mascot sheets, panel atlases load on the real display; reconnect re-uploads.
9. Reconnects: mid-navigation, mid-recording and mid-answer.

## Navigation app and Home widget (2026-10-15)

Labels: **V** verified on the Android emulator with simulated GPS (all such tests are *simulated*), **A** awaiting a check I could not run, **H** hardware-dependent (December, physical glasses).

**What exists**
- Navigation is a normal glasses app (`nav`, cyan arrow icon) in the launcher and the phone Apps list. Idle: "Where to?" (voice/keyboard), travel mode (Walk/Cycle/Drive), recents. With a journey running, opening it resumes that journey (no restart, no duplicate). "Open/show navigation" via the assistant uses the same app.
- Home widget `nav` (Small default; Wide/Tall/Large supported) appended after existing widgets, so nothing is replaced or reordered. States: Idle ("Where to?"), Calculating ("Finding route…" + destination), Navigating (turn arrow, distance to next turn, street, arrival, remaining), Rerouting ("Updating route…"), GPS unavailable ("Waiting for location", retained info marked stale), Arrived ("You've arrived"). Speed and detail are full-app only. Tap/gaze opens Navigation.
- HOME returns to Home while guidance continues; widget, phone overlay and map screen read one journey state (`MymavNav.state`). Stop ends guidance and returns the widget to idle.
- Speed: stored in m/s, shown as km/h or mph from that one value (1.6 m/s = 5.8 -> "6 km/h"). The earlier "30 km/h" phone capture came from a separate simulated teleport between two fixes, not the 1.6 m/s walk. Speed is never clamped to a mode limit: a genuine sensor speed is shown as reported. A speed derived from fixes is shown as "--" when the fix jumped (implied > 70 m/s, or a leap far beyond the fix accuracy that the previous speed does not explain) or when the gap since the previous fix exceeds 6 s.

**Status**
| Item | Label |
|---|---|
| Widget idle, calculating, navigating, GPS lost, arrived (simulated) | V |
| HOME keeps journey running; reopen continues it (simulated) | V |
| Poor GPS shows "--" speed (simulated) | V |
| Widget order/size persisted (`nav` last, Small) | V |
| Rerouting widget state (forced by a test hook; real reroute finishes too fast to observe), Stop -> idle, widget Wide size kept across an app restart, voice 2D/3D/north-up/heading-up (simulated) | V |
| Bike/car OSRM endpoints, alternatives | A |
| Glasses-screen visuals (simulator not inspected by me): see screenshot checklist | A |
| Gaze selection of nav pills, glasses/phone GPS, speaker voice, reconnect mid-navigation | H (December) |

**Limits:** a journey does not survive an app restart; bike/car use the same public OSRM service and are untested.

### 2D / 3D map (2026-10-15)
- Map presentation (3D perspective or flat 2D top-down) is separate from Follow / Overview / Minimal; every combination works and switching never touches the journey (verified: same remaining distance and ETA before and after).
- 2D also has heading-up or north-up (the walker arrow turns instead of the map in north-up). 2D shows roads, route, chevrons, position, destination and flat building footprints.
- Where: phone Navigation screen (3D / 2D, North-up / Heading-up) and the phone overlay; glasses Navigation app rows "Map: 3D/2D" and "2D direction"; in the glasses map the Backward gesture toggles 2D/3D (so "repeat" is by voice or phone); header chip shows 3D / 2D N / 2D H; voice: "switch to 2D", "3D map", "north up", "heading up". Choice is saved (verified across an app restart).
- Status: phone 2D and 3D follow + overview captured from the same journey (simulated). Glasses-screen 2D: frames computed (logged) but not seen by me: A (screenshots needed). Physical glasses: H.

### Panel pill / metrics edges (2026-10-15)
- Cause: panels without a packaged glass artwork (e.g. 188x32 pills, the 568x48 metrics card) used a rectangular reflection texture whose glow was only masked at two corners, so it spilled past capsule ends, and was generated at reduced resolution.
- Fix (`GlassSurface.kt`): the shine is masked by the real rounded silhouette (inset inside the border), generated at full resolution for controls up to 60k px, clipped with the same corner radius, and carries a thin contour highlight (strong top-left, weaker bottom-right) that fades along the edge. One texture per control, no extra uploads. Offline render of the same maths: 0 shine pixels outside the silhouette. In-simulator check: A.

### Long-route overload fix (2026-10-15)
- Found from a real emulator run: the emulator's own location was ~2,090 km from Covent Garden, so a 2,639-point "walking" route was previewed. The glasses screen was handed thousands of chevron drawables and crashed the Everysight SDK (`IndexOutOfBounds` in `M2DrawableList`, 5,275 items); the phone map redrew a huge thick path every 0.8 s (main thread blocked ~0.8-1.1 s per frame).
- Fix: routes longer than 40 km walking / 250 km cycling / 1,000 km driving are refused with "N km by walking is too far, try driving"; overview thins the polyline to ~300 segments and spaces chevrons so at most ~25 appear. Verified (simulated start ~90 km away): walking refused, driving preview 86 km drew 24 chevrons with no dropped frames. Roads are not drawn in a very wide overview (route line and destination only).
- Not fixed in the SDK: the crash was a race inside `M2DrawableList`; the app now keeps drawable counts small. Other earlier crashes: A (none were in the log buffer).

## Mini-apps integration pass (2026-10-15, branch `cursor/mymav-miniapps-integration`)

From Claude tip `04a2d35`. Labels: **V** phone/emulator, **A** awaiting spoken/vision sample, **H** physical glasses.

| Mini-app | Status | What landed | Verified |
|---|---|---|---|
| Conversate | Working (phone mic) | Real `ConversationEngine`: SpeechRecognizer, CueGate + cue client on MyMav Settings AI keys, HUD listening/processing/caption/cue/error; `MymavMicOwner` with captions + meeting | V: Apps → Conversate → Start → Status Listening → Stop. Spoken cues/captions: A. Glasses PCM/Whisper: H |
| Food | Working (phone CameraX / glasses frame) | Live capture → `MymavScanVision` (shared with Identify); dietary prefs; ready→result + cancel/permission/no-camera/provider-error; source labelled | V: phone Food screen Ready, AI ok. Capture/analyse with real meal: A. Physical glasses camera: H |
| Shop | Working (same vision path) | Live capture → product estimate; no checkout; prices only when visible | V: Shop Ready screen. Product sample: A |
| Identify | Working when frame+key | Uses shared `MymavScanVision` instead of demo success catalogue | A (needs glasses/sim frame) |
| Football | Working | ESPN scoreboard via public `MymavScores` (same as Ask MyMav sports); favourites migrate from `maverick_favourites`; 45s refresh; no mock scores | V: SCHEDULED Premier League fixtures from ESPN with update time. Live match in progress: A |

**Mic:** only one of Captions / Meeting recorder / Conversate may own the mic. **Camera:** `ScanCameraSession` keeps Food and Shop from competing. **HW deferred:** glasses mic PCM, physical glasses camera frames, Dec pairing checklist above still apply.

## HUD checkpoint `d98c097` (2026-10-17)

User-verified on `cursor/typography-rollout-89ea`. **Do not merge.** Appearance / data / settings frozen at this commit. In-repo notes: `docs/HANDOVER.md`. Protected-file list: `docs/AI_COLLAB_RULES.md`.

| Item | Status |
|---|---|
| Softer Sans, heading shadows off, Conversate measured wrap, Health heart widget | Verified (glasses sim + phone Daily) |
| Fonts stable after cycling every Home page including Gallery | Verified |
| Glasses-side `HudImageCache` | **Must stay off** (`enabled = false` at HEAD). Shared 48–50 row table evicted fonts when images were `CachedManualDelete`. |
| Packed-PNG `HudEncodedCache` | **Stays on** (phone encode only; no glasses slots) |
| USB / texture-upload speed | Point work at the shared-table failure (quota / isolation / measured page cycle). Do **not** re-enable resident image cache. |
