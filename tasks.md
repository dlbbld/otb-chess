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

## Current release — migrate to ashlar-chess 19.0.0

Bump otb-chess from ashlar-chess **18.1.0 → 19.0.0**. Branch: `migrate-to-ashlar-chess-19.0.0`.

**Nature of 19.0.0:** surface-only — *no rule/parser/analysis/adjudication behavior changes* (every
verdict and report is identical to 18.1.0). Heavily binary-incompatible but mechanical: type renames
and `Type.method(x)` → `TypeUtility.method(x)` / dropped `calculate`/`get` prefixes. **Let the
compiler drive:** bump the version, `mvn compile`, fix each error against the 19.0.0 CHANGELOG
breaking list (`C:\Users\danie\git\ashlar-chess\CHANGELOG.md`, `## [19.0.0]`).

### Step 1 — bump the dependency
- [x] `pom.xml`: ashlar-chess `18.1.0` → `19.0.0`. Resolved straight from Maven Central — no local `mvn install` needed.

### Step 2 — fix the breaking changes (otb-chess's ashlar API surface, mapped to 19.0.0 renames)
- [x] **`new Board(String fen)` REMOVED** → `Board.fromFenStrict(String)`. Sites: `OtbChessServer.validateFenResponse`, `GameWebSocketServer.handleCreateGame`, and many tests. `handleCreateGame` catch: the strict-FEN exceptions are `StrictFenFieldValidationException` (package-private, **uncatchable by name**) / `StrictFenSemanticValidationException`, both `extends UsageException → RuntimeException`. The old specific catch and the `RuntimeException` fallback did the **identical** thing (surface "Invalid FEN"), so collapsed to a single `catch (RuntimeException)`. `validateFenResponse` catches `Exception` — unchanged, safe.
- [x] **`Board.getHavingMove()` → `getSideToMove()`** — ashlar `board.*` calls only (ArbiterEngine, GameSession, CastlingAttemptDetector, GameWebSocketServer). otb-chess's **own** `GameSession.getHavingMove()` and the `"havingMove"` JSON property are deliberately kept (client wire-protocol vocabulary).
- [x] **`LegalMove.havingMove()` → `movingSide()`**; **`LegalMove.pieceCaptured()` → `capturedPiece()`**. Sites: ArbiterEngine, CastlingAttemptDetector, PositionComparator, TouchMoveEvaluator, GameWebSocketServer.sendArbiterResponse; tests TestPositionComparator/TestArbiterEngineEdgeCases.
- [x] **`CastlingUtility.calculateIsCastlingMove(spec)` → `isCastlingMove(spec)`**. `calculateKingCastlingFrom/To` confirmed unchanged (still on `CastlingUtility`).
- [x] **`PgnCreate.createPgnString(board)` → `toPgnString(board)`** (`GameSession.exportPgn`).
- [x] **`getPerformedHalfMoveCount` / `HalfMove` / `getHalfMoveList`** — not used anywhere in otb-chess (grep clean). Nothing to do.
- [x] **Protocol caveat:** verified `MessageConverter` uses `Piece.name()` / `position.get(sq).name()` / `Piece.valueOf(...)` / `square.getName()` — **never `toString()`**. Wire format safe. (Plus the extra compiler-surfaced rename `Square.calculate(String)` → `Square.parse(String)` in MessageConverter.)
- [x] Confirmed no direct `isUnwinnableQuick/Full(Side)` calls; `canClaim*`, `MoveSpecification.*`, `BitboardPosition.afterMove/get` etc. unchanged — left alone.
- [x] **Extra renames the compiler surfaced (not in the changelog's spelled-out list / CHANGELOG was inexact):**
  - `Piece.calculateKingPiece(Side)` / `calculateRookPiece(Side)` / `calculate(Side, PieceType)` → **`Piece.of(Side, PieceType.X)`**. (The CHANGELOG said "consolidated into `PieceUtility`", but the polish pass returned the factory to the enum as `Piece.of` — `PieceUtility` does not exist in the jar.) Sites: ArbiterEngine, CastlingAttemptDetector, TouchMoveEvaluator (added `import …board.enums.PieceType`).
  - `Square.calculateKingSideRookOriginalSquare(Side)` / `calculateQueenSideRookOriginalSquare(Side)` → moved to **`SquareUtility`** (same method names). Sites: CastlingAttemptDetector, TouchMoveEvaluator (added `import …board.enums.SquareUtility`).
  - `LenientSanParser.parseText(String, Board)` → **`parse(...)`** (returns `LenientSanParseResult`; `.moveSpecification()` accessor unchanged). Site: `DrawClaimManager`.

### Step 3 — verify
- [x] Killed leftover servers on 8080/8081 first.
- [x] `mvn clean test` → **139/139** ✅
- [x] `npm run e2e` → **62 passed** ✅ (env had no `node_modules`; ran `npm install` + `npx playwright install chromium` first, then the suite).

### Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
