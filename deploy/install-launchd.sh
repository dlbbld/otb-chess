#!/bin/bash
# Install the OTB Chess LaunchDaemons (app + Caddy [+ cloudflared]) so the server runs at boot.
# Must be run with sudo (LaunchDaemons live in /Library/LaunchDaemons, owned by root).
#
#   sudo deploy/install-launchd.sh                  # app + Caddy
#   sudo deploy/install-launchd.sh --with-cloudflared   # also the tunnel (requires go-live setup)
set -euo pipefail

SRC="/Users/chess-server/Claude/otb-chess/deploy/launchd"
DEST="/Library/LaunchDaemons"
RUN_USER="chess-server"
LABELS=(io.github.dlbbld.otbchess.app io.github.dlbbld.otbchess.caddy io.github.dlbbld.otbchess.cloudflared)

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo:  sudo $0 [--with-cloudflared]" >&2
  exit 1
fi

# Log directory owned by the run user so the daemons can write to it.
install -d -o "$RUN_USER" -g staff "/Users/$RUN_USER/Library/Logs/otb-chess"

with_cloudflared=0
[ "${1:-}" = "--with-cloudflared" ] && with_cloudflared=1

for label in "${LABELS[@]}"; do
  if [ "$label" = "io.github.dlbbld.otbchess.cloudflared" ] && [ "$with_cloudflared" -ne 1 ]; then
    echo "skip  $label  (pass --with-cloudflared after the tunnel + ~/.cloudflared/config.yml exist)"
    continue
  fi
  cp "$SRC/$label.plist" "$DEST/$label.plist"
  chown root:wheel "$DEST/$label.plist"
  chmod 644 "$DEST/$label.plist"
  launchctl bootout system "$DEST/$label.plist" 2>/dev/null || true
  launchctl bootstrap system "$DEST/$label.plist"
  launchctl enable "system/$label"
  echo "loaded  $label"
done

echo
echo "Status:"
for label in "${LABELS[@]}"; do
  launchctl print "system/$label" >/dev/null 2>&1 \
    && echo "  $label: loaded" \
    || echo "  $label: not loaded"
done
echo "Health: curl -s http://localhost:8080/api/health"
