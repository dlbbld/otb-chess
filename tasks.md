# Tasks

Live-planning source of truth. Mark items done in place (check, don't delete); keep an unshipped
release's tasks until it ships.

## Publish the server (beta)

Architecture (reviewed): iMac running native `launchd` services → Cloudflare Tunnel
(`cloudflared`, no inbound ports — fits the guest network) → Caddy single public origin
(static + `/ws`) → Java app (HTTP 8080 + WebSocket 8081 in one JVM, in-memory state).
Private beta gated by Cloudflare Access. A Linux VPS with a Dockerfile is the later
portability path, not the initial runtime.

### Done
- [x] Remove the testing-only `/api/lastGameId` endpoint and lobby join-code prefill (`fdf067b`).
- [x] Open-source licensing: GPL-3.0-only `LICENSE`, two-line SPDX headers on every Java file via `tools/java-license-headers.ps1` (`-Check`/`-Fix`, ported from ashlar-chess), enforced by `TestLicenseHeaders`; `.gitattributes` pins LF; license + ashlar-chess attribution in `pom.xml`/`README.md`; copyright 2026; Java-only headers (`dcb2d79`).

### Phase 1 — make it deployable (single origin, not yet publicly exposed)
- [x] Dynamic WebSocket URL: derive from the page origin as `wss://<host>/ws` (replaces the hardcoded `ws://localhost:8081` in `static/js/websocket.js`). **Deviation:** kept a direct `ws(s)://<host>:8081` fallback when the page is served straight from the Java HTTP server on `:8080` (no proxy), so the plain `java -jar` dev flow still works without Caddy. Single-origin (any port other than 8080) uses `/ws`.
- [x] Configurable bind host + ports via env (12-factor); bind to `127.0.0.1` only so the app is reachable only through the proxy/tunnel. Env vars: `OTB_BIND_HOST` (default `127.0.0.1`), `OTB_HTTP_PORT` (8080), `OTB_WS_PORT` (8081), `OTB_STATIC_DIR` (`static`). Verified both sockets bind loopback-only.
- [x] `/api/health` endpoint (liveness + WebSocket-listening check) for the uptime/container probe. Returns `200 {"status":"ok","websocket":true}` when the WS port is bound, `503 {"status":"degraded",...}` otherwise (via `GameWebSocketServer.isListening()`).
- [x] Make the `/ws` contract explicit: client connects to `/ws`, Caddy proxies `/ws*` → :8081; smoke test added at `tools/smoke-ws.mjs` (opens `<base>/ws`, creates a game, asserts `gameCreated`). **Decision:** the server-side path assertion was left *lenient* (the Java WebSocket server accepts any path) so the `:8081` direct-dev fallback keeps working; tighten to require `/ws` only if/when the fallback is dropped.
- [x] Caddy reverse proxy: one local origin serving static (→8080) and `/ws` (→8081); dev/prod parity verified locally. Config at `Caddyfile` (listen `OTB_CADDY_LISTEN`, default `:9000`; `auto_https off` since TLS terminates at Cloudflare). Verified: `/`, `/api/health`, `/api/version`, static assets all 200 through Caddy, `/ws` returns 101, and a full `createGame`→`gameCreated` round-trip succeeds through the proxy.
- [x] Dockerfile as the portability/VPS artifact (build and keep it; the iMac runtime stays native `launchd`). Multi-stage (Maven build → JRE), env-configurable, `OTB_BIND_HOST=0.0.0.0` for containers, HEALTHCHECK on `/api/health`; `.dockerignore` added. **Not build-tested locally** (Docker is not installed on the iMac — this is the VPS path).

### Phase 2 — hardening gate (before turning Cloudflare Access off to open public)
- [ ] Join-code hardening: widen the code (≥12 chars / secure-random base32) and add a collision guard (`putIfAbsent`) on creation. Current: `UUID.randomUUID().toString().substring(0, 8)` = 32 bits, plain `put`.
- [ ] WebSocket `Origin` check (anti-CSWSH): allowlist = {production host, beta host, localhost/dev}. Decide missing-`Origin` handling — browsers send it on the WS handshake; non-browser clients (tools, smoke tests) may not, so reject on mismatch and decide the policy for absent.
- [ ] WebSocket input validation: guard malformed frames (missing/wrong-type fields, oversized payloads). E.g. `handleJoinGame` NPEs on a missing `gameId`.
- [ ] App-level rate limiting: throttle failed `joinGame` attempts and malformed frames — Cloudflare cannot inspect post-upgrade WebSocket frames.
- [ ] Edge rate limiting (Cloudflare): `/ws` connection-attempt churn and obviously abusive HTTP paths.
- [ ] CORS cleanup: remove `Access-Control-Allow-Origin: *` from `StaticFileHandler` (unneeded for a same-origin app), alongside the `Origin` validation — or fold into the Phase 1 single-origin work.
- [ ] Resource bounds: reap abandoned/never-joined rooms and cap concurrent games (unbounded in-memory state on a home box).
- [ ] Privacy logging: minimal per-game usage record (start/end time, move count, result, illegal-move/draw-claim counts; no names, emails, IPs, or full move list) with fixed retention (~90 days). Write `PRIVACY.md` documenting **app-collected** data separately from **Cloudflare Access** data (Cloudflare processes invited emails + access logs during the gated beta).
- [ ] Access + WebSocket end-to-end smoke test (release gate): load the page through Access, create a game, join from a second browser/account, keep a WebSocket open, and play moves.

### Go-live setup (host + edge)
- [x] Add a domain to Cloudflare (free plan) for the named tunnel. Domain `otb-chess.app`; public hostname `play.otb-chess.app` (CNAME → tunnel, created via `cloudflared tunnel route dns`).
- [x] `cloudflared` named tunnel → Caddy single origin (ingress config). Tunnel `otb-chess` (id `5843474d-…`), `~/.cloudflared/config.yml` ingress `play.otb-chess.app` → `http://127.0.0.1:9000`. **Verified end-to-end over the public URL**: `/`, `/api/health`, `/api/version` = 200, and a full `createGame→gameCreated` over `wss://play.otb-chess.app/ws`. Caddy bound loopback-only on `:9000` (port-only listener matches the tunnel-forwarded Host).
- [ ] `launchd` services on the iMac (app jar, Caddy, `cloudflared`): start on boot, KeepAlive; disable sleep/App Nap (`pmset`) so it serves unattended. **Ready:** plists at `deploy/launchd/`, installer `deploy/install-launchd.sh`, docs `deploy/README.md`. App plist verified under launchd (boot launch + KeepAlive respawn confirmed). **Remaining:** run `sudo deploy/install-launchd.sh` (needs your password) + `pmset` no-sleep; cloudflared daemon waits on the tunnel config.
- [ ] Cloudflare Access (invited emails) for the private beta; WAF + rate-limit rules at the edge.

## iMac host setup (publish-server-beta)
- Toolchain present on the iMac: Git, Temurin JDK 21, Maven 3.9, Node 25 (built-in `WebSocket`).
- Homebrew on this Mac belongs to another user account, so per-user CLIs are installed to `~/.local/bin` (on `PATH` via `~/.zprofile`): `gh` (GitHub CLI, authed as `dlbbld`), `caddy` v2.11.
- Run the app alone (loopback): `java -jar target/otb-chess.jar` → http://127.0.0.1:8080 (WS on :8081). Helper scripts: `otb-start` / `otb-stop`.
- Run the single origin locally (dev/prod parity): start the app, then `caddy run --config Caddyfile` from the repo root → http://localhost:9000 (proxies static→8080, `/ws`→8081).
- Smoke test: `node tools/smoke-ws.mjs ws://localhost:9000` (or `wss://<prod-host>` once live).
- Remaining for go-live (needs the Cloudflare account + a domain): `cloudflared` named tunnel → Caddy origin; add domain to Cloudflare; `launchd` plists for app + Caddy + cloudflared (KeepAlive, disable sleep/App Nap); Cloudflare Access for the invited-email beta. Optional Phase 1 leftover: Dockerfile (VPS portability path).

## Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
