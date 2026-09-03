# Changelog

All notable changes to OTB Chess are documented here.

## 0.1.4 — Abort Button Regression Guard — Unreleased

Post-launch maintenance: strengthen the regression tests around the readable Abort button restored
in `0.1.3`. This does not change gameplay or the released button's appearance.

### Test hardening

- Check the Abort button's rendered label, minimum width, and lack of horizontal clipping for
  White and Black creators, including a waiting-page refresh.
- Check widths only for rendered board-control buttons, and require that set to be non-empty.
- Carry forward the workflow instructions requiring finished fixes to be committed and pushed,
  with explicit reporting of whether they have reached `main`.

Recovered from `7910bdd` on `claude/reading-session-3b64af`; the obsolete cross-machine handover
document is intentionally excluded. Verified before PR with all 213 Java tests and all 125
Playwright tests passing against the rebuilt `0.1.4` jar.

## 0.1.3 — Public Beta Launch — 2026-09-03

OTB Chess is now available to try at [play.otb-chess.app](https://play.otb-chess.app/):
an educational chessboard that simulates physical board play with an arbiter.

This is experimental beta software, provided as-is without warranty. Bugs and rule-handling errors
may occur, and availability is not guaranteed. It is intended for learning and testing, not official
tournament adjudication.

### Highlights

- Restore a clearly labelled **Abort game** button beside the share code while the creator waits
  for an opponent. It returns to the lobby, works for either colour, survives refresh, and disappears
  when the opponent joins.
- Clarify the starting-page description to explicitly mention the arbiter.
- Announce public-beta availability, with the play URL and experimental/no-warranty notice.

### Deployment

The public site was reachable without login when this release was prepared, running `0.1.2`.
Publishing this source release does not deploy the iMac server; update and restart it separately
using [SETUP.md §12](SETUP.md#12-deploying-updates), then verify `/api/version` reports `0.1.3`.

## 0.1.2 — Rules and Game-Flow Hardening — 2026-07-15

This beta release continued the work toward basic feature completeness. It hardened FIDE/OTB rule
handling and game flow around draw claims and offers, abandonment/rematch behavior, clock-press
edge cases, castling, touch-move/released-piece recovery, en passant, promotion, and opponent-piece
handling. It also polished the in-game controls and player-facing messages.

## 0.1.1 — 2026-06-27

First release that turns OTB Chess into a **networked, two-player application** (previously a local
app). Still **beta and not publicly released** — the deployment runs access-gated while remaining
gameplay issues are worked through before a public launch.

### Added — online play & deployment

- Server runtime: HTTP server (static + JSON API) and WebSocket game server in one JVM with
  in-memory game state.
- Single public origin via **Caddy** (static → `:8080`, `/ws` → `:8081`); **Cloudflare Tunnel**
  (`cloudflared`) deployment with no inbound ports; native **`launchd`** services (start on boot,
  KeepAlive) and a portability **Dockerfile**.
- 12-factor config: env-configurable bind host + ports (loopback by default), `/api/health` probe,
  and a client WebSocket URL derived from the page origin.
- WebSocket **resilience**: app-level heartbeat plus client auto-reconnect with server-side state
  resync, so a dropped socket recovers instead of freezing.
- **Stable join code across refresh**: reloading the page resumes the same game instead of minting a
  new code.

### Added — hardening

- Secure 12-char base32 join codes with a collision guard.
- WebSocket `Origin` allow-listing (anti-CSWSH).
- Input/frame validation (oversized frames, malformed envelopes, and malformed in-game frames →
  clean validation errors).
- Per-connection rate limiting plus a Cloudflare edge rate-limit rule.
- Resource bounds: concurrent-game cap and reaping of abandoned, never-joined rooms.
- Minimal, anonymous usage logging (game create/join only).
- Static assets served `Cache-Control: no-store`.

### Fixed

- **Released-piece rule (FIDE 4.7):** a released move stayed final across repeated reverts; a
  different move (e.g. `a2-a3` after releasing the pawn on `a4`) is no longer wrongly accepted, and
  the revert restores the committed move. The "your move is final — press the clock" guidance now
  shows only to the player on move.
- Cloudflare was serving stale cached JavaScript after deploys (resolved by `no-store` + a one-time
  cache purge).

### Notes

- Verified by 146 unit tests and 67 Playwright end-to-end tests (run on localhost and against the
  live edge).
- Reboot survival verified (daemons + tunnel auto-recover unattended).
- A privacy policy will be re-added when the service is opened to the public.

## 0.1.0

Initial local OTB Chess application (arbiter, board, game rules) prior to networked play.
