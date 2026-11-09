# Revu / Memory Sight product brief (from Revu Presentation + task)

PDF at docs/Revu_Presentation.pdf is largely visual; behavior captured here for Memory Sight:

## Product
AI camera remembers **belongings** and **where they are** through the day. Open the app to **recall location / time / context**.

## Core loop
1. Background vision while MyMav foreground service runs (app need not be open).
2. Detect / tag personal items (keys, wallet, headphones, bag, …).
3. Persist a timeline: item → place/context → timestamp.
4. Recall UI: search or browse “Where did I leave X?”

## Emulator
Mock camera detections + persisted memory timeline when live camera is limited.
