#!/usr/bin/env bash
set -euo pipefail
URL="https://storage.googleapis.com/evs-pub/maverickai/simulator/v0.2.0/GlassesSimulator-0.2.0-x64-win.exe"
EXPECTED="8d640d9b6eb28e9eed2f1194076ad37a1931f7c63bcadd2e43db7d9e2c70f174"
OUT="${1:-GlassesSimulator-0.2.0-x64-win.exe}"
curl -fL "$URL" -o "$OUT"
HASH=$(sha256sum "$OUT" | awk '{print $1}')
if [[ "$HASH" != "$EXPECTED" ]]; then
  echo "SHA256 mismatch: $HASH" >&2
  exit 1
fi
echo "OK $OUT"
