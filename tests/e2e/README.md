# End-to-end tests (Playwright)

Two-player browser tests for the OTB Chess web app. Each test opens **two isolated
browser contexts** (white + black = two real sessions) against one shared Java server.

## Prerequisites

- **Node.js** (LTS) and npm.
- **JDK 17+** and Maven on PATH (the same toolchain that runs the app).

## One-time setup

```bash
npm install
npx playwright install chromium
```

## Running

```bash
npm run e2e          # headless; trace + video saved only on failure
npm run e2e:headed   # watch both browser windows play live
npm run e2e:ui       # interactive runner — step, inspect locators, time-travel (best for debugging)
npm run e2e:debug    # Playwright Inspector, pause per action
npm run e2e:report   # open the HTML report of the last run
```

The Java server is started automatically by Playwright (`webServer` in
[`playwright.config.ts`](../../playwright.config.ts)) on the **dedicated e2e ports
`18080`/`18081`** — deliberately NOT the normal dev ports, so a dev server on
`8080`/`8081` (e.g. `start.bat`) can keep running while the suite executes. The suite
tests the packaged jar (`npm run e2e` rebuilds it; after Java changes rebuild before
`npx playwright test`). Inspect a failed run with:

```bash
npx playwright show-trace test-results/<...>/trace.zip
```

## Layout

```
playwright.config.ts     # runner config + auto-start of the Java server
tests/e2e/
  helpers/app.ts         # createGame(), joinGame(), startTwoPlayerGame()
  helpers/board.ts       # dragPiece(), pressClock(), expectPiece(), expectEmpty()
  smoke.spec.ts          # create -> join -> white e2-e4 -> black sees it
```

## Notes

- Tests run **serially** (`workers: 1`): the game server is stateful (shared game rooms).
  Each test creates its own game and reads the code from the DOM (`.game-code-value`), so
  games stay isolated.
- The board is a custom mouse-drag widget, so `dragPiece` drives real `page.mouse` events.
- Use a **custom FEN** (`createGame({ fen })`) to start rule scenarios one move away from the
  thing under test — short scenarios are fast and stable.
