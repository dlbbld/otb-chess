# Tasks

Live-planning source of truth. Mark items done in place (check, don't delete); keep an unshipped
release's tasks until it ships.

## Publish the server (beta)

Architecture (reviewed): iMac running native `launchd` services → Cloudflare Tunnel
(`cloudflared`, no inbound ports — fits the guest network) → Caddy single public origin
(static + `/ws`) → Java app (HTTP 8080 + WebSocket 8081 in one JVM, in-memory state).
A Linux VPS with a Dockerfile is the later portability path, not the initial runtime.

**Status (2026-06-27): public release on hold.** The full server stack is built, deployed, hardened,
and verified (incl. unattended reboot — see [`SETUP.md`](SETUP.md) §11), and it was briefly opened to
the public. Decision: **hold the public launch** until several known gameplay flaws are fixed and the
game is properly play-tested, so the first impression is strong. Re-gate the deployment with
Cloudflare Access (or take it down) in the meantime; a privacy policy will be re-added before any
public launch. Released as **v0.1.1** (still beta).

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
- [x] Join-code hardening: 12-char secure-random base32 codes (~60 bits) via `SecureRandom`, collision-guarded with `putIfAbsent` (regenerate on clash); creator's side set on the room before it's visible so a racing joiner can't steal the colour. (was `UUID…substring(0,8)` = 32 bits, plain `put`.) Unit-tested in `TestServerHardening`.
- [x] WebSocket `Origin` check (anti-CSWSH): allowlist = `https://play.otb-chess.app` (default) + `OTB_WS_ALLOWED_ORIGINS` + loopback (any scheme/port) for dev. **Decision on absent `Origin`:** allow it (non-browser tools/smoke tests omit it; browsers always send it, so a *mismatch* is the attack signal). Disallowed handshakes closed with policy code. Unit-tested.
- [x] WebSocket input validation: guard malformed frames — oversized payload cap (`OTB_MAX_MSG_CHARS`, 64 KB), invalid JSON, missing/non-primitive `type`, and `createGame`/`joinGame` required-field + range checks; clean error replies instead of NPEs (verified `handleJoinGame` no longer NPEs on a missing `gameId`).
- [x] App-level rate limiting: per-connection violation counter (via `WebSocket` attachment); connection closed after `OTB_MAX_VIOLATIONS` (default 30) malformed/invalid requests, incl. join-code misses (throttles scanning) — covers what Cloudflare can't (post-upgrade frames).
- [x] Edge rate limiting (Cloudflare): free-plan rate-limiting rule `per-ip-flood` on zone `otb-chess.app` — match `URI Path starts with /` (free plan exposes no Hostname field), **10,000 requests / 10s** per IP, action Block for 10s. (Initially 100/10s and verified tripping 429s on a burst; raised to 10,000 so legitimate bursts / full e2e runs from one IP aren't throttled.) Covers `/ws` churn + abusive paths via the single allowed free rule.
- [x] CORS cleanup: removed `Access-Control-Allow-Origin: *` from `StaticFileHandler` (same-origin app).
- [x] Resource bounds: cap concurrent games (`OTB_MAX_GAMES`, default 1000) and reap rooms created-but-never-joined past `OTB_ROOM_TTL_MS` (default 30 min) via a daemon maintenance scheduler.
- [x] Usage logging (minimal): log **only** two events — a game being **created** and a game being **joined**. Nothing else: no move counts, results, rule-violation counts, board/PGN state, names, emails, or IPs.
  - [x] Privacy policy was written (`PRIVACY.md`) then **removed for now** while the public launch is on hold; to be re-added before going public.
  - [x] Implement on the server: `UsageLog` writes `<ISO-8601>\t<event>\t<gameId>` per create/join, 30-day purge on a daily schedule (`OTB_USAGE_LOG`, `OTB_USAGE_RETENTION_DAYS`). Verified create↔join pair on one game id; best-effort (I/O errors never break gameplay).
- [x] WebSocket resilience / reconnect (fixes "first game froze, clock didn't start"): a waiting creator's socket was idled out by Cloudflare and the client had no reconnect, so `gameStarted` went to a dead socket. Fix: app-level heartbeat (`keepalive`/`pong`, 25 s) to prevent the idle drop; client auto-reconnect (exponential backoff) that re-attaches via a secret per-seat token → server `resume` → `resync` (board/turn/clocks/state); plus a disconnect grace period (`OTB_DISCONNECT_GRACE_MS`, 12 s) before the opponent is told "disconnected". Verified locally: drop-while-waiting (resumed socket then receives `gameStarted`) and mid-game drop (resync returns `IN_PROGRESS` + correct turn/clock/board).
- [x] Stable join code across refresh (high priority): refreshing the create page no longer mints a new code (which orphaned the shared code on slow connections). The per-tab session (gameId + secret token) is persisted, so a refresh *resumes* the same game via the server `resume` path; a new game comes only from the lobby (which clears the saved session) or after Abort. The refreshed creator re-sees the same code. Verified: resume returns the identical code; client wiring in `game.js`/`index.html` reuses the reconnect infrastructure (no server change).
- [x] Access + WebSocket end-to-end smoke test (release gate): verified manually through Cloudflare Access (create, join from a second browser, play moves) and backed by an automated Playwright suite — **67/67 e2e tests pass** against the live server (rules, touch-move, released-piece, draws, flag-fall, castling, claims).

### Go-live setup (host + edge)
- [x] Add a domain to Cloudflare (free plan) for the named tunnel. Domain `otb-chess.app`; public hostname `play.otb-chess.app` (CNAME → tunnel, created via `cloudflared tunnel route dns`).
- [x] `cloudflared` named tunnel → Caddy single origin (ingress config). Tunnel `otb-chess` (id `5843474d-…`), `~/.cloudflared/config.yml` ingress `play.otb-chess.app` → `http://127.0.0.1:9000`. **Verified end-to-end over the public URL**: `/`, `/api/health`, `/api/version` = 200, and a full `createGame→gameCreated` over `wss://play.otb-chess.app/ws`. Caddy bound loopback-only on `:9000` (port-only listener matches the tunnel-forwarded Host).
- [x] `launchd` services on the iMac (app jar, Caddy, `cloudflared`): start on boot, KeepAlive; disable sleep/App Nap (`pmset`) so it serves unattended. Installed as LaunchDaemons in `/Library/LaunchDaemons` via `deploy/install-launchd.sh --with-cloudflared`; all three `running` (RunAtLoad + KeepAlive). `pmset -a sleep 0 disablesleep 1` applied. After rebuilding the jar: `sudo launchctl kickstart -k system/io.github.dlbbld.otbchess.app`.
- [x] Cloudflare Access (invited emails) for the private beta. Self-hosted app `OTB Chess Beta` on `play.otb-chess.app`, Allow policy `Beta testers` (Emails: otbchessmail@gmail.com), email one-time-PIN login (team `noisy-field-e40c.cloudflareaccess.com`). **Verified**: unauthenticated `/` and `/api/health` → 302 to the Access login, and `wss /ws` is blocked. *Remaining (Phase 2 / edge):* WAF + rate-limit rules.

## iMac host setup (publish-server-beta)
- Toolchain present on the iMac: Git, Temurin JDK 17, Maven 3.9, Node 25 (built-in `WebSocket`).
- Homebrew on this Mac belongs to another user account, so per-user CLIs are installed to `~/.local/bin` (on `PATH` via `~/.zprofile`): `gh` (GitHub CLI, authed as `dlbbld`), `caddy` v2.11.
- Run the app alone (loopback): `java -jar target/otb-chess.jar` → http://127.0.0.1:8080 (WS on :8081). Helper scripts: `otb-start` / `otb-stop`.
- Run the single origin locally (dev/prod parity): start the app, then `caddy run --config Caddyfile` from the repo root → http://localhost:9000 (proxies static→8080, `/ws`→8081).
- Smoke test: `node tools/smoke-ws.mjs ws://localhost:9000` (or `wss://<prod-host>` once live).
- Deploying updates: static (HTML/JS/CSS) is served with `Cache-Control: no-store` and read from disk per request, so client changes go live on a normal browser refresh. Java changes need `mvn -DskipTests package` + `sudo launchctl kickstart -k system/io.github.dlbbld.otbchess.app`. **One-time gotcha (resolved):** Cloudflare had edge-cached the old `game.js` (origin sent no cache headers), silently serving stale client code after deploys; fixed by the `no-store` header + a one-off Cloudflare **Purge Everything**. If a deploy ever looks stale again, verify the origin with a `?v=` cache-buster, then purge.
- Remaining for go-live (needs the Cloudflare account + a domain): `cloudflared` named tunnel → Caddy origin; add domain to Cloudflare; `launchd` plists for app + Caddy + cloudflared (KeepAlive, disable sleep/App Nap); Cloudflare Access for the invited-email beta. Optional Phase 1 leftover: Dockerfile (VPS portability path).

## Backlog (not scheduled)

- [x] **User-friendly message when opening/joining a game that is not active.** When a player opens a
  game that is no longer playable — game ended, code not found, expired/reaped, or the server
  restarted — the UI showed a technical error. Now replaced with a calm, friendly message plus a
  **Back to lobby** button. Server (`handleJoinGame`) sends a dedicated `joinFailed` message with a
  reason (`not_found` / `ended` / `full`) instead of a raw `error`; the not-found case still counts
  toward the scan-throttle budget. Client (`game.js`) routes `joinFailed`, `resumeFailed`, and the
  no-game-code case through one `showGameUnavailable(message)` helper (clears the saved session,
  shows the message, renders the Back-to-lobby button). e2e coverage added in `lobby.spec.ts`
  (not-found, full, ended, no-id — all assert the message + the way back).
- [x] **Name the origin square in the released-piece message when ambiguous.** With e.g. knights on
  c3 and g5 both able to reach e4, "you already released the knight on e4" didn't say WHICH knight.
  When more than one piece of the same kind could have legally reached the release square, the
  message now names the origin: "you already released the knight **from c3** on e4" (SAN-style
  disambiguation, only when ambiguous; the "put the knight back on e4" instruction is unchanged).
  New message keys `arbiter.released_piece.from.player/opponent`; `ReleasedPieceContext` carries
  `fromSquare` (NONE for side-area/promotion placements); ambiguity computed in `ArbiterEngine`
  from the legal moves. Unit-tested (ambiguous + unambiguous cases in `TestArbiterEngine`).
- [x] **Show the time control with its FIDE discipline on the board page.** A muted label like
  `5+3 • Blitz` (Lichess-style display) in the right column's lower spacer — vertically centered
  below the clock, about level with the bottom edge of the board, on the board side in both views.
  FIDE classification (Appendices A/B, initial + 60× increment): blitz ≤ 10 min, rapid < 60 min,
  classical ≥ 60 min — so 30+0 is *rapid*. Server-rendered (`TimeControl.fideCategory/label/
  displayLabel`, unit-tested incl. boundaries in `TestTimeControl`) and sent as `timeControlLabel`
  on `gameCreated`/`gameJoined`/`resync` (so a refresh keeps it); unused, FIDE-misnamed
  `TimeControl` presets (`CLASSICAL_30_0`) dropped. e2e asserts both players see `3+0 • Blitz`.
- [x] **Fix "vdevelopment" shown below the board in dev runs.** `start.bat` (`mvn exec:java`) runs
  straight from `target/classes`, so the jar manifest's `Implementation-Version` doesn't exist and
  `/api/version` fell back to `development`. The version now comes from a Maven-filtered
  `version.properties` on the classpath (works in every run mode: `java -jar`, `start.bat`, IDE),
  with the manifest as fallback. Verified `{"version":"0.1.1"}` in both run modes; pinned by
  `TestOtbChessServer` (fails if the filtering ever stops running).
- [x] **Wrong-time draw claims escalate instead of locking the buttons** (documented as
  [A-003](docs/fide-deviations.md) — mirrors A-001 for wrong-time offers). Teaching philosophy:
  the claim buttons stay *enabled* for the player not having the move so the fault can be made
  and learned from. Escalation (counted per player across the game, no reset): 1st wrong-time
  claim → rejection; 2nd → rejection + warning ("your next draw claim when not having the move
  loses the game"); 3rd → **loss** (`WRONG_TIME_CLAIM_GAME_LOST`, personalised messages: offender
  "you have been warned … you lose the game", opponent "repeatedly requested to claim a draw …
  and so has lost the game"). Opponent hears nothing on the first two (private mistakes). New
  `wrongTime` flag on `drawClaimResult` keeps the client from locking; with-move buttons skip the
  SAN prompt when not on move. Unit tests (escalation + persistence across turns) and e2e (full
  three-press flow, buttons asserted enabled between presses).
- [x] **Accurate opponent message when a rejected claim becomes a draw offer + repeat-claim
  escalation** (documented as [A-004](docs/fide-deviations.md)). (1) The opponent of a rejected
  claim no longer sees a claim notice overwritten by a bare "your opponent offers a draw" — one
  streamlined message: "Your opponent claimed a draw by <threefold repetition of the current
  position / … with the move X / the 50-move rule …>, but the claim is not valid. It still counts
  as a draw offer. Do you accept the draw?" (`drawOffered` carries the claim-specific text;
  `drawClaimOpponent` is skipped for the conversion case). (2) A second claim on the same move no
  longer disables the buttons: warning immediately ("You cannot make more than one draw claim on
  your move. You are warned: …"), next repeat loses (`REPEAT_CLAIM_GAME_LOST`, A-003-style
  personalised messages). Violations counted per player across the game; a legitimate single claim
  on a later move is never a violation. Claim buttons now stay enabled for the whole game. Bonus
  fix: a pending draw-offer panel is hidden on `gameEnded`. Unit + e2e coverage.
- [x] **Passive game-information window below the clock.** Ground rule (face-to-face principle):
  what a player would *see* happen at a real board but must not act on goes in a new quiet window
  below the clock (`#opponentInfoPanel`, new `opponentInfo` WS message); action-relevant messages
  (game end, corrections, draw offers) stay in the arbiter window above the clock. Wired for the
  claim escalations: wrong-time claim presses 1–2 (A-003) and the repeat-claim warning (A-004) now
  inform the opponent passively ("Your opponent claimed a draw while not having the move…", "…has
  been warned…"); the game-ending third press stays in the standard window. Info clears when the
  episode closes (own move, opponent move, board update, game end). Time-control label stays
  centered below it. Unit (opponentInfo on warning/rejection, empty on loss and merit rejections)
  + e2e (both windows asserted on both boards, clearing pinned).
- [x] **Cross-move accumulation documented + claim-after-touch escalation (A-005).** (1) A-003's
  wrong-time claim count explicitly accumulates over *different* moves (claim on move 10 →
  rejection, move 12 → warning, move 15 → loss); documented in SPECIFICATION.md ("Procedurally
  wrong claims: three escalation ladders") and A-003, pinned by unit + e2e tests playing real
  moves between the claims. (2) NEW: claiming after touching/moving a piece before the clock
  press (FIDE 9.4 — e.g. move made on the board, clock not yet pressed) now escalates with the
  identical ladder instead of a flat error: rejection → warning → loss
  (`CLAIM_AFTER_TOUCH_GAME_LOST`), counted across moves, opponent informed passively on the first
  two, with-move buttons skip the SAN prompt once a piece was touched (client tracks
  `touchedThisTurn`). SPECIFICATION.md claim sections rewritten to the current three-ladder
  behavior (stale once-per-turn / touch-before-claim texts replaced); defensive null-SAN guard in
  `DrawClaimManager`. Unit + e2e for both same-move and across-moves scenarios.

## Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
