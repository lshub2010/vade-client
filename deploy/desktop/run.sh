#!/usr/bin/env bash
# A5 · launcher for the packaged desktop app (免安装 app-image).
# Build it first:  gradle :composeApp:createDistributable   (see README.md)
# Extra args are passed through to the app, e.g.  ./run.sh --dark
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR/../../composeApp/build/compose/binaries/main/app/vade-ui"

# Windows app-image layout: vade-ui.exe at the root; Linux/macOS: bin/vade-ui.
if [ -x "$APP_DIR/vade-ui.exe" ]; then
  exec "$APP_DIR/vade-ui.exe" "$@"
fi
if [ -x "$APP_DIR/bin/vade-ui" ]; then
  exec "$APP_DIR/bin/vade-ui" "$@"
fi
echo "[vade] app-image not found: $APP_DIR" >&2
echo "[vade] build it first:  gradle :composeApp:createDistributable" >&2
exit 1
