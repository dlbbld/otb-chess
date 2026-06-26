# OTB Chess

**OTB** stands for **over the board** — chess played on a physical board with a real arbiter, as
opposed to a computer playing the moves for you.

Play over-the-board–style chess online, with a real **arbiter** that enforces the FIDE rules a
physical game relies on: touch-move, the released-piece rule (4.7), illegal-move handling, clock
discipline, draw claims, and more. Two players, one game code, real-time over WebSocket.

## ▶️ Play

**https://play.otb-chess.app**

Create a game, share the code with your opponent, and play.

### Try it solo — play both sides yourself

You can be both players on one computer: create the game in a normal window, then join with the code
from a **private/incognito window** (or a different browser). A private window is a separate session,
so it acts as the second player — two normal tabs in the same browser would share one session and act
as the same player.

## How it works

- **Java** application server: an HTTP server for the static web app and a WebSocket server for
  real-time game communication, in one JVM with in-memory game state.
- **[Caddy](https://caddyserver.com/)** fronts the app as a single origin (static + `/ws`).
- **[Cloudflare Tunnel](https://www.cloudflare.com/products/tunnel/)** (`cloudflared`) exposes that
  origin publicly with no inbound ports, with TLS and rate-limiting at the edge.
- Chess move legality and position logic come from
  **[ashlar-chess](https://github.com/dlbbld/ashlar-chess)**.

## Run locally

Requires a JDK (21+) and Maven.

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

## Privacy

The app records only minimal, anonymous usage (game create/join events); see [`PRIVACY.md`](PRIVACY.md).

## License

[GNU General Public License v3.0 only](LICENSE). Copyright (C) 2026 Daniel Baechli.
Includes/depends on [ashlar-chess](https://github.com/dlbbld/ashlar-chess).
