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
- [ ] Dynamic WebSocket URL: derive from the page origin as `wss://<host>/ws` (replaces the hardcoded `ws://localhost:8081` in `static/js/websocket.js`).
- [ ] Configurable bind host + ports via env (12-factor); bind to `127.0.0.1` only so the app is reachable only through the proxy/tunnel.
- [ ] `/api/health` endpoint (liveness + WebSocket-listening check) for the uptime/container probe.
- [ ] Make the `/ws` contract explicit: client connects to `/ws`, Caddy proxies `/ws*` → :8081, optional server-side path assertion; add a deployment smoke test that opens `wss://<host>/ws`.
- [ ] Caddy reverse proxy: one local origin serving static (→8080) and `/ws` (→8081); verify dev/prod parity locally.
- [ ] Dockerfile as the portability/VPS artifact (build and keep it; the iMac runtime stays native `launchd`).

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
- [ ] Add a domain to Cloudflare (free plan) for the named tunnel.
- [ ] `cloudflared` named tunnel → Caddy single origin (ingress config).
- [ ] `launchd` services on the iMac (app jar, Caddy, `cloudflared`): start on boot, KeepAlive; disable sleep/App Nap (`pmset`) so it serves unattended.
- [ ] Cloudflare Access (invited emails) for the private beta; WAF + rate-limit rules at the edge.

## Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
