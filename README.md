# OTB Chess

**OTB** stands for **over the board** — chess played on a physical board with a real arbiter, as
opposed to a computer playing the moves for you.

An online, real-time implementation of over-the-board chess with a real **arbiter** that enforces the
FIDE rules a physical game relies on: touch-move, the released-piece rule (4.7), illegal-move
handling, clock discipline, draw claims, and more. Two players, one game code, over WebSocket.

## Status

**Public beta — try it at [play.otb-chess.app](https://play.otb-chess.app/).**
An educational chessboard that simulates physical board play with an arbiter.

OTB Chess is experimental software, provided as-is without warranty. Bugs and rule-handling errors
may occur, and availability is not guaranteed. It is intended for learning and testing, not official
tournament adjudication.

See the [Manual](MANUAL.md) for how to create, join, and play a game.
See [0.1.4 — Abort Button Regression Guard](CHANGELOG.md#014--abort-button-regression-guard--2026-09-03)
for this release.

## Documentation

- [Manual](MANUAL.md): how to create, join, and play a game.
- [Manifesto](MANIFESTO.md): why this project exists.
- [Specification](SPECIFICATION.md): detailed rule and behavior reference.

## How it works

- **Java** application server: an HTTP server for the static web app and a WebSocket server for
  real-time game communication, in one JVM with in-memory game state.
- **[Caddy](https://caddyserver.com/)** fronts the app as a single origin (static + `/ws`).
- **[Cloudflare Tunnel](https://www.cloudflare.com/products/tunnel/)** (`cloudflared`) can expose that
  origin with no inbound ports (TLS + rate-limiting at the edge). The public beta does not require
  a login.
- Chess move legality and position logic come from
  **[ashlar-chess](https://github.com/dlbbld/ashlar-chess)**.

## Run locally

Requires a JDK (17+) and Maven.

```bash
mvn -DskipTests package
java -jar target/otb-chess.jar      # http://localhost:8080  (WebSocket on :8081)
```

Open http://localhost:8080. Configuration is via environment variables (`OTB_BIND_HOST`,
`OTB_HTTP_PORT`, `OTB_WS_PORT`, …); see `src/main/java/.../server/OtbChessServer.java`.

Deployment notes (Caddy, Cloudflare Tunnel, `launchd` services) are in [`deploy/`](deploy/), and a
portability/VPS container image is provided by the [`Dockerfile`](Dockerfile).

## Tests

```bash
mvn test                 # JUnit unit tests
npm install && npx playwright test   # Playwright end-to-end tests (tests/e2e)
```

## License

[GNU General Public License v3.0 only](LICENSE). Copyright (C) 2026 Daniel Baechli.
Includes/depends on [ashlar-chess](https://github.com/dlbbld/ashlar-chess).
