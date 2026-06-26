# OTB Chess — Server Setup (iMac)

How the public server at **https://play.otb-chess.app** was set up on the iMac. The installation was
done manually (trial and error), not scripted; this is the reproducible record. The repeatable
*deployment* pieces (launchd plists, installer) live in [`deploy/`](deploy/); this file is the
end-to-end story including the one-off and browser steps.

```
Internet → Cloudflare (TLS, WAF/rate-limit, [Access]) → Cloudflare Tunnel (cloudflared, outbound only)
        → Caddy (127.0.0.1:9000, single origin) → Java app (127.0.0.1:8080 HTTP + :8081 WebSocket)
```

Host: macOS (Apple Silicon), user **`chess-server`**, repo at
`/Users/chess-server/Claude/otb-chess`. Cloudflare account: `otbchessmail@gmail.com`,
domain `otb-chess.app`, GitHub: `dlbbld`.

---

## 1. Prerequisites already on the machine

Git, Temurin **JDK 21**, **Maven 3.9**, **Node** (for the Playwright e2e). Verify:

```bash
git --version; java -version; mvn -v | head -1; node -v
```

**Homebrew gotcha:** Homebrew on this Mac is installed under a *different* user account (`ahmed`),
so `brew install` fails for `chess-server` (`/opt/homebrew` not writable) and taking ownership would
break it for the other user. → We install the few needed CLIs as **per-user binaries** instead (§2).

## 2. Per-user CLIs → `~/.local/bin`

Put a user-local bin on `PATH`:

```bash
mkdir -p ~/.local/bin
printf '\n# user-local bin\nexport PATH="$HOME/.local/bin:$PATH"\n' >> ~/.zprofile
export PATH="$HOME/.local/bin:$PATH"
```

Install the official precompiled binaries (Apple Silicon / arm64):

```bash
# GitHub CLI (gh) — from https://github.com/cli/cli/releases (macOS arm64 .zip)
#   unzip and copy bin/gh to ~/.local/bin/gh

# Caddy
curl -L "https://caddyserver.com/api/download?os=darwin&arch=arm64" -o ~/.local/bin/caddy
chmod +x ~/.local/bin/caddy

# cloudflared — from https://github.com/cloudflare/cloudflared/releases
#   download cloudflared-darwin-arm64.tgz, tar xzf, move cloudflared to ~/.local/bin/cloudflared
```

Verify: `gh --version && caddy version && cloudflared --version`.

## 3. GitHub auth + clone (private repo)

```bash
gh auth login            # GitHub.com → HTTPS → "Login with a web browser" (device code)
gh auth setup-git        # make git use gh's credentials
git config --global user.name  "Daniel Bächli"
git config --global user.email "62600114+dlbbld@users.noreply.github.com"

cd /Users/chess-server/Claude
gh repo clone dlbbld/otb-chess -- --branch publish-server-beta
```

## 4. Build the app

```bash
cd /Users/chess-server/Claude/otb-chess
mvn -DskipTests package      # -> target/otb-chess.jar (+ target/lib/)
```

The app binds **127.0.0.1 only** by default (`OTB_BIND_HOST`), ports `OTB_HTTP_PORT=8080` /
`OTB_WS_PORT=8081`. Static files are served with `Cache-Control: no-store` (see §9).

## 5. Caddy — single origin

[`Caddyfile`](Caddyfile) listens on `:9000` bound to loopback and reverse-proxies static → `:8080`
and `/ws` → `:8081`. Local check (dev/prod parity):

```bash
java -jar target/otb-chess.jar &                 # app on 127.0.0.1:8080/8081
caddy run --config Caddyfile                      # origin on http://localhost:9000
node tools/smoke-ws.mjs ws://localhost:9000       # -> "OK: gameCreated ..."
```

## 6. Cloudflare — DNS + Tunnel  (browser + CLI)

Prereq: the domain **`otb-chess.app`** is added to the Cloudflare account.

```bash
cloudflared tunnel login          # browser opens -> AUTHORIZE the otb-chess.app zone (writes ~/.cloudflared/cert.pem)
cloudflared tunnel create otb-chess   # writes ~/.cloudflared/<TUNNEL_ID>.json
```

Create `~/.cloudflared/config.yml`:

```yaml
tunnel: <TUNNEL_ID>
credentials-file: /Users/chess-server/.cloudflared/<TUNNEL_ID>.json
ingress:
  - hostname: play.otb-chess.app
    service: http://127.0.0.1:9000
  - service: http_status:404
```

```bash
cloudflared tunnel route dns otb-chess play.otb-chess.app   # creates the CNAME
cloudflared tunnel run otb-chess                            # foreground test; later run via launchd (§10)
```

## 7. Cloudflare — Access (optional private gate)  (browser)

Used during the private beta; **removed** to go public. To re-enable a private gate:

1. **[one.dash.cloudflare.com](https://one.dash.cloudflare.com)** → complete Zero Trust onboarding
   (team name, **Free** plan; a payment method is required even for Free).
2. **Access controls → Applications → Add an application → Self-hosted.**
   - Name `OTB Chess Beta`; public hostname subdomain `play`, domain `otb-chess.app`.
   - Add a policy: Action **Allow**, Include → **Emails** → the invited addresses.
   - Login method: **One-time PIN** (email) is on by default — no identity provider needed.

**To go public:** Access controls → Applications → `OTB Chess Beta` → **⋯ → Delete**. The site then
serves with no login. (Re-create the application to re-gate.)

## 8. Cloudflare — rate limiting  (browser)

Zone-level (NOT the account-level WAF, which is a paid add-on):

1. Cloudflare dashboard → select the **`otb-chess.app`** domain → **Security → WAF → Rate limiting rules → Create**.
2. Match: field **URI Path** · **starts with** · `/`  (Free plan exposes no Hostname field; the zone
   only serves this app, so matching all paths is fine).
3. When rate exceeds **10,000 requests / 10 seconds**, characteristic **IP**, action **Block** for 10s.
   (Started at 100; raised to 10,000 so normal bursts / test runs from one IP aren't throttled.)

## 9. Cloudflare — caching gotcha (important)

Cloudflare edge-caches static `.js`/`.css`/`.html` by default, so deployed client changes were
silently served stale. Fixed by having the origin send **`Cache-Control: no-store`** on static
responses (`StaticFileHandler`) **plus a one-time** Cloudflare **Caching → Configuration →
Purge Everything**. If a deploy ever looks stale again: load `…/js/app.js?v=<random>` (cache-buster)
to confirm the origin is fresh, then Purge Everything.

## 10. Run as services (launchd) + never sleep

```bash
cd /Users/chess-server/Claude/otb-chess
sudo deploy/install-launchd.sh --with-cloudflared   # installs 3 LaunchDaemons (app, Caddy, cloudflared)
sudo pmset -a sleep 0 disablesleep 1                 # serve unattended on AC power
```

Daemons run as `chess-server` with `RunAtLoad` + `KeepAlive` (start on boot, restart on crash).
Details + plists: [`deploy/README.md`](deploy/README.md). Verify:

```bash
launchctl print system/io.github.dlbbld.otbchess.app | grep state
curl -s http://localhost:8080/api/health
```

## 11. Reboot survival — VERIFIED (unattended, FileVault disabled)

**Final state:** FileVault was **disabled** (`sudo fdesetup disable`; the SSD stays hardware-encrypted
at rest on Apple Silicon, it just auto-unlocks at boot). An unattended `sudo reboot` was then tested
**without logging in**: the site came up on its own — the tunnel connected within ~10 s of boot and
`https://play.otb-chess.app` was reachable from another device with nobody logged into the iMac.
(Bonus: Jump Desktop remote access also reconnects at boot now, for the same reason.)

> Note: FileVault is whole-disk, so disabling it applies to **all** accounts on this Mac (incl. the
> other user). Account login passwords are unaffected. Re-enable with `sudo fdesetup enable` if you
> later prefer at-boot encryption over unattended reboots.

### History (the FileVault caveat, now resolved)

Before disabling FileVault, a reboot left the daemons **down until the FileVault password was entered
at the console** (the encrypted Data volume — jar, `Caddyfile`, `~/.cloudflared` — was unreadable at
boot), surfacing as Cloudflare **Error 1033** until login. Disabling FileVault removed that gate.

**Why:** this Mac has **FileVault on**, so the APFS **Data volume** (which holds the jar, `Caddyfile`,
and `~/.cloudflared`) stays encrypted at boot until the FileVault password is entered. Until then
**no LaunchDaemon can run**, so `play.otb-chess.app` returns Cloudflare **Error 1033** (tunnel
unresolvable). Observed: boot at 21:28, tunnel down at 21:31, all three daemons started ~21:32 the
instant the disk was unlocked at login — then everything worked with no further action. (Relocating
the files wouldn't help: the whole Data volume is encrypted until unlock.)

So:
- **Planned reboot:** fine — enter the FileVault password once at the console and the server fully
  self-recovers (no other steps).
- **Unattended reboot (power outage, nobody at the keyboard):** the server will stay **down** until
  someone unlocks the disk.

**To make reboots fully unattended**, disable FileVault (security trade-off — the whole disk,
including any personal files on this Mac, is then unencrypted at rest):

```bash
sudo fdesetup disable
```

Decide based on whether unattended uptime or at-rest encryption matters more for this machine.

Verify after any reboot (once unlocked):
```bash
launchctl print system/io.github.dlbbld.otbchess.cloudflared | grep state   # running
curl -s http://localhost:8080/api/health                                    # {"status":"ok",...}
curl -s -o /dev/null -w '%{http_code}\n' https://play.otb-chess.app/        # 200
```

## 12. Deploying updates

- **Static** (HTML/JS/CSS): served `no-store` from disk → a normal browser refresh shows changes.
- **Java**: rebuild + restart the app daemon:
  ```bash
  mvn -DskipTests package
  sudo launchctl kickstart -k system/io.github.dlbbld.otbchess.app
  ```

## Reference

- Logs: `~/Library/Logs/otb-chess/{app,caddy,cloudflared,usage}.log`
- Tunnel config + creds: `~/.cloudflared/config.yml`, `~/.cloudflared/<TUNNEL_ID>.json`, `cert.pem`
- App env: `OTB_BIND_HOST`, `OTB_HTTP_PORT`, `OTB_WS_PORT`, `OTB_CADDY_LISTEN`, `OTB_MAX_GAMES`,
  `OTB_ROOM_TTL_MS`, `OTB_MAX_MSG_CHARS`, `OTB_MAX_VIOLATIONS`, `OTB_WS_ALLOWED_ORIGINS`,
  `OTB_USAGE_LOG`, `OTB_USAGE_RETENTION_DAYS`, `OTB_DISCONNECT_GRACE_MS`
