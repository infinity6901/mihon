#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-static-review}"
mkdir -p "$OUT_DIR"

{
  echo "# Static review hints (heuristic, informational)"
  echo
  echo "## NFC & Colorizer files"
  find app/src/main/java/eu/kanade/tachiyomi/data/nfc \
       app/src/main/java/eu/kanade/tachiyomi/ui/reader/colorizer \
       app/src/main/java/eu/kanade/presentation/components/NfcDialogs.kt \
       app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsNfcScreen.kt \
       2>/dev/null | sort || true
  echo
  echo "## TODO / FIXME markers in custom NFC & Colorizer files"
  grep -rnE 'TODO|FIXME|HACK' \
       app/src/main/java/eu/kanade/tachiyomi/data/nfc \
       app/src/main/java/eu/kanade/tachiyomi/ui/reader/colorizer \
       app/src/main/java/eu/kanade/presentation/components/NfcDialogs.kt \
       app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsNfcScreen.kt \
       2>/dev/null || echo "(none)"
  echo
  echo "## Potential hardcoded LAN IPs or secrets"
  grep -rnE '192\.168\.|10\.0\.|BEGIN (RSA|EC|PRIVATE) KEY' \
       app/src/main/java/eu/kanade/tachiyomi/data/nfc \
       app/src/main/java/eu/kanade/tachiyomi/ui/reader/colorizer \
       2>/dev/null || echo "(none)"
} > "$OUT_DIR/static-review.txt"

cat "$OUT_DIR/static-review.txt"
