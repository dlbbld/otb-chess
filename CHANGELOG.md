# Changelog

All notable changes to OTB Chess are documented here.

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
