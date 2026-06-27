# Deploying OTB Chess on the iMac (native `launchd`)

Runtime architecture:

```
Internet → Cloudflare (TLS + WAF/rate-limit) → Cloudflare Tunnel (cloudflared, outbound only)
         → Caddy (localhost:9000, single origin) → Java app (127.0.0.1:8080 + :8081)
```

(The site is currently **public**. It can optionally be gated with Cloudflare Access — see the
"Cloudflare Access" section of [`../SETUP.md`](../SETUP.md).)

All three processes run as **LaunchDaemons** (start at boot, restart on crash). The Java app and
Caddy need no Cloudflare account; `cloudflared` is wired up during go-live.

## Prerequisites (already installed on this iMac)
- Temurin JDK 17 — `/Library/Java/JavaVirtualMachines/temurin-17.jdk`
- `caddy`, `cloudflared` in `~/.local/bin`
- Built jar: from the repo root run `mvn -DskipTests package` → `target/otb-chess.jar`

## 1. Install the app + Caddy daemons
```bash
cd /Users/chess-server/Claude/otb-chess
mvn -DskipTests package          # ensure target/otb-chess.jar is current
sudo deploy/install-launchd.sh   # app + Caddy (cloudflared skipped until go-live)
```
Verify:
```bash
curl -s http://localhost:8080/api/health     # {"status":"ok","websocket":true}
node tools/smoke-ws.mjs ws://localhost:9000  # OK: gameCreated ...
launchctl print system/io.github.dlbbld.otbchess.app | grep state
```

## 2. Keep the iMac awake (serve unattended)
```bash
sudo pmset -a sleep 0 disksleep 0 disablesleep 1   # never sleep on AC
# Re-enable later with: sudo pmset -a disablesleep 0 sleep 1
```
(The daemons set `ProcessType=Interactive`, so macOS does not throttle them in the background.)

## 3. Cloudflare go-live (needs the Cloudflare account + a domain)
```bash
cloudflared tunnel login                       # browser auth; writes ~/.cloudflared/cert.pem
cloudflared tunnel create otb-chess            # writes <TUNNEL_ID>.json credentials
```
Create `~/.cloudflared/config.yml`:
```yaml
tunnel: <TUNNEL_ID>
credentials-file: /Users/chess-server/.cloudflared/<TUNNEL_ID>.json
ingress:
  - hostname: chess.example.com        # your domain
    service: http://localhost:9000     # the Caddy origin
  - service: http_status:404
```
Route DNS and install the tunnel daemon:
```bash
cloudflared tunnel route dns otb-chess chess.example.com
sudo deploy/install-launchd.sh --with-cloudflared
```
Then in the Cloudflare dashboard: **Zero Trust → Access → Applications** → add `chess.example.com`,
policy = the invited beta emails. Smoke-test through the edge once DNS propagates:
```bash
node tools/smoke-ws.mjs wss://chess.example.com
```

## Operating the daemons
```bash
# logs
tail -f ~/Library/Logs/otb-chess/{app,caddy,cloudflared}.log
# restart one service after a rebuild
sudo launchctl kickstart -k system/io.github.dlbbld.otbchess.app
# stop + remove all
for s in app caddy cloudflared; do
  sudo launchctl bootout system/io.github.dlbbld.otbchess.$s 2>/dev/null
  sudo rm -f /Library/LaunchDaemons/io.github.dlbbld.otbchess.$s.plist
done
```

> Note: after rebuilding the jar (`mvn package`), restart the app daemon with `launchctl kickstart`
> so it picks up the new artifact.
