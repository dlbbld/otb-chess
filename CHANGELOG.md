# Changelog

All notable changes to OTB Chess are documented here.

## 0.5.1 — Quick Restart — 2026-11-25

A maintenance release for the host: the server is back within seconds after a restart. Nothing
changes for the players during a game. The change has a Java test and a browser test that kills
and restarts a real server.

### Fixed

- **The restarted server takes its port over at once.** At the deployment of 0.5.0 the site
  answered 502 for about 50 seconds: the new process could not open its WebSocket port ("Address
  already in use") while the connections of the stopped process were still expiring, gave up
  after 10 seconds, and was started again until it succeeded. The server now asks to reuse its
  address. On Windows nothing changes: the problem does not exist there, and the option would
  let a second server open a port that is in use.

### Known limits

- Games live in the server's memory. A restart ends the running games; the pages say that the
  game is no longer available and lead back to the start screen.

## 0.5.0 — Two-Tab Play and Fairer Rulings — 2026-11-25

Both sides of a game can be played from two tabs of one browser, a page that is reloaded after the
game shows how it ended, and three rulings of the arbiter are fairer than before. The release
answers the first feedback from a tester on the live site. The changes have Java tests and browser
tests of their journeys.

### Added

- **Both sides from two tabs of one browser.** Create the game in one tab, open the start page in
  a second tab, enter the code and join. Each tab keeps its own side, also after a reload and
  through a rematch. A private window or a second browser is no longer needed to try the app
  alone. Two games in two tabs of one browser do not disturb each other either.
- **The finished game after a reload.** A player who reloads the page after the game sees the
  board as it stood, the final times and the result, with a way back to the start screen. So does
  a player who left and comes back after the game was decided.
- **Green circles for a move that was already made.** A player who completes a move, changes the
  position again and presses the clock is told that the move was already made; its start and end
  square are circled on both boards until the clock press accepts it.

### Changed

- **A pause after a clock press without a move.** The press is still an illegal move
  (FIDE 7.5.3), but the arbiter now stops the clock for the ruling, as for every other one. The
  clock restarts after a few seconds, or both players confirm that they are ready, depending on
  the game's setting. A press during the pause is not ruled on, so a double click no longer loses
  the game at once. A further press after the pause still is the next illegal move.
- **Touch-move comes first.** A player who touches a piece that can move and then plays another
  piece gets a touch-move violation for the touched piece, also when that other move is illegal
  and also after an illegal move was taken back. No illegal move is counted for it. Before, this
  was an illegal move, and after a Revert a second one, which lost the game. An illegal move of
  the touched piece itself is an illegal move as before.
- **"Return to game" on the start screen** is offered only for a running game that is not open in
  any tab of the browser.
- **A custom starting position is checked more strictly**, by Ashlar Chess 22.2.0 (before:
  20.0.0): a double check that no single move can create, split runs of empty squares (`44`)
  and leading zeros in the two counters are refused, with the library's reason. The draw on flag fall or resignation when the opponent cannot
  checkmate comes from the library's new unwinnability engine.

### Fixed

- A second press of the clock that arrives after the move was accepted no longer replaces
  "Move accepted. Opponent's turn." with "It is not your turn."
- A page reloaded after the game no longer comes back with an empty board (the player who
  joined) or silently creates a new game (the player who created it).
- In two tabs of one browser, a reload no longer gives a tab the seat of the other tab.

### Known limits

- Both tabs of one browser share the sound options: with the sounds on, each sound is played by
  both tabs.
- Two-tab play needs a page served over HTTPS or from localhost. On a page reached over plain
  HTTP from another device, a second tab takes the first tab's seat over instead of joining.
- A finished game is shown again until the server removes it: 30 minutes after the end, once
  both players have left. After that the page says that the game is no longer available.
- Two-tab play and the reload after the game are tested in Chromium only.

## 0.4.0 — Piece and Clock Sounds — 2026-11-24

The board and the clock can now be heard, as at a real board: a piece set down on a square, and a
clock lever pressed down. Both players hear both. The change has Java tests and browser tests of
its journeys.

### Added

- **A sound when a piece is set down on a square**, on both boards. The player's own board plays
  it at the release, the opponent's board when it shows the same piece set down.
  - Heard: a move, a capture, a piece brought back from the side, a piece put back by hand while
    a position is restored. Castling makes two sounds.
  - Silent: a piece that is only touched or put back on its own square, a piece taken off the
    board, and a position set by the server (Revert, a reconnect, a rematch).
- **A sound when a clock lever is pressed down**, on both boards, at the moment the server has
  registered the press - also for the player's own press.
  - Heard: the own lever whenever the arbiter rules on the move (accepted, illegal, no move made,
    incomplete), and the opponent's lever pressed by mistake while their clock is running.
  - Silent: a lever that is already down, a press while the game is paused or over, and the clock
    restarted by the arbiter.
- **Two options on the start screen**, under *Sounds*: *Sound for a moved piece* and *Sound for
  the clock press*. Both are on at first. They are saved in the browser, apply to every game the
  player creates, joins or returns to, and do not change what the opponent hears.

### Changed

- The server sends the new message `clockPressHeard` to both players for every clock press that is
  heard, before anything else the press causes.
- `.wav` files are served as `audio/wav`.

### Fixed

- The creator's board is set up also in a browser that refuses to store data for the page.
  Before, creating a game there left the creator without a board.

### Known limits

- A browser plays sounds on a page only after the player has clicked or touched it. The
  opponent's first move can therefore be silent for a player who has not yet touched the game
  page, and a phone can be silent after its screen was locked until the page is touched again.
- There is no volume setting and no switch during the game.

## 0.3.0 — Board Circles and Early Draw Offers — 2026-11-23

The arbiter now points at the squares it rules on, and it rules on a draw offer that comes too
early when the offer is made. Each change has Java tests and a browser test of its journey.

### Added

- **Circles on the board for the arbiter's rulings**, on both boards, in the style of Lichess.
  - Green: the piece a touch obliges the player to move or capture - the first touched own piece
    that can move, the first touched opponent piece that can be captured, both together when the
    one must capture the other, king and rook for castling.
  - Red: the start and the end square of an illegal move, when the board shows exactly one move
    or a castling attempt. Several displaced pieces are not marked, and neither are two pieces of
    the same kind that both moved.
  - Green stays until the move is accepted, also through an illegal move made meanwhile. Red
    stays until the position is restored. The end of the game takes the circles off the board,
    except for the illegal move that lost it.
  - A ruling on a move shown with a draw offer is marked like one on a clock press, and a page
    that reconnects gets the current circles.

### Changed

- **A draw offer made before both players have made a move is declared not valid at once
  (FIDE 5.2.3).** Before, the offer was passed on and *Accept* was then refused. Now the arbiter
  tells both players when the offer is made, the opponent is not asked to accept anything, and
  nothing stays on the table. Black's first move with an offer is a real offer: both have then
  made a move.
- Such early offers are counted like offers at the wrong time: the second on the same move is not
  considered and brings the warning, the third loses the game. The count starts again with the
  next move.
- A rejected draw claim does not count as a draw offer before both players have made a move. The
  claim itself is rejected as before, with the two minutes for the opponent.

### Fixed

- An illegal move that is repeated after the Revert is given its reason again - before, the
  second ruling, which in a default game loses the game, said only "Illegal move." The same holds
  for an illegal castling that is repeated, and for a different illegal move after a Revert.

### Rules interpretation

- [`docs/fide-deviations.md`](docs/fide-deviations.md): A-001 extended (offers before both
  players have made a move).

## 0.2.0 — Game Lifecycle and Draw Guards — 2026-11-18

This release is the result of several review passes over the whole game: how a game starts, ends,
is paused, reconnected and replayed, and how draw offers and touch-move rulings are decided. Each
fix has Java tests and a browser test of its journey.

### Fixed

**Endings and results**

- A player whose time has run out can no longer be saved by a late move with increment, a draw
  claim, a draw offer or an abandonment claim. Every action that can decide the game looks at the
  clocks first.
- A result is final. A late resignation or draw acceptance no longer replaces it, the result is
  announced once, and a result of the previous game cannot end a rematch.
- A mate played during a pause ends the game instead of leaving it without a result.
- The final clock values are sent once, directly before the result.

**Reconnect, seats and rematch**

- A seat has one connection. A game opened in a second window takes the seat; the first window is
  taken out of the game and says so. Before, the old window could still act - a player could agree
  a draw alone.
- A refresh during a restoration, before or after *Ready to continue*, no longer strands both
  players. A pending draw offer, the pause state and the physical board come back after a reconnect.
- A player's absence counts from the start of the game. A creator who dropped before the opponent
  joined is not adjudicated seconds into the game, the joiner is told and can claim the win, and a
  player who reconnects while the opponent is away is told again with the time left.
- A reconnect keeps the player's seat across a rematch, also when the start of the rematch was
  missed, and keeps a board turned with *Flip Board*.

**Pauses**

- Simultaneous *Ready to continue* presses, an obsolete wrong-clock timer and a clock press inside
  the automatic resume delay no longer lock a board or restart the wrong clock.
- During any pause draw claims and draw offers are not adjudicated: no penalty time, no step on an
  escalation ladder.
- The restart of the clock after a wrong clock press is announced on both boards.

**Draw offers**

- An offer made while the opponent's offer can still be accepted is its acceptance, also during a
  pause and for the offer implied by a rejected draw claim (A-009).
- Only the player the offer was made to can answer it. An offer made with an invalid move no longer
  cancels the offer that was pending.
- The move made together with an accepted draw offer is in the game record, and the PGN ends with
  the result.

**Touch-move and released piece**

- One ruling per turn: a release and a touch are judged in the order the player acted (FIDE 4.7,
  4.3, 4.4; A-010). "Your move is final" is said only when the clock press will be accepted.
- A flag fall comes before a misconduct ruling made at the same moment.

**Rooms and server**

- Finished and half-started rooms are reclaimed, so the server no longer fills up; a connection
  has one game and gives up its waiting room when it creates another.
- A starting position that is already decided is refused. Two players joining at once get one seat.
- A socket that closes during a send no longer costs the other player the result.
- A turn records at most 500 piece movements. The cost of a ruling no longer grows with the cube
  of the turn's length.
- A resignation needs a running game.

### Changed

- **A draw can be agreed only after both players have made at least one move (FIDE 5.2.3).** Before
  that, *Accept* and an answering offer are refused with a message and the offer stays open.
- Creating a game while playing a running one is refused.

### Added

- `RuleFuzzer` and `TouchMoveOracle`, two opt-in test tools: random games against a model of the
  client, and every touch sequence of a set of positions against an independent reading of the
  rules. Both run clean on this release (20,000 games; 33,793 cases).
- `OTB_ENDED_ROOM_TTL_MS`: how long a finished room is kept for a rematch (default 30 minutes).
- The manual-testing launcher takes its branch from `OTB_TEST_BRANCH` or its first argument.

### Rules interpretation

- [`docs/fide-deviations.md`](docs/fide-deviations.md): A-006 extended (nothing is adjudicated
  during the pause after a wrong clock press; the restart is announced), A-009 (an offer answering
  an offer), A-010 (order of release and touch).

## 0.1.6 — Touch-Move and Release Guards — 2026-09-22

This release fixes three arbiter faults found in play, and adds two fail-closed checks that stop
the game rather than let it continue on a move the arbiter cannot justify.

### Fixed

- A touch that binds keeps binding. Touching a piece that can move — including during an earlier
  illegal move in the same turn — is no longer overridden by a later king-and-rook castling touch.
  In the reported sequence 1. g3 e5 2. Bg2 d5 3. Nf3 Nc6, `Nb1-b3`, clock, knight back, castling
  short, the knight on b1 must move.
- A piece counts as released "as a legal move" only when the whole board matches that move. The
  beginner castling `Ke1-b1`, `Ra1-c1`, clock is now one illegal move with its penalty, instead of
  a released-piece violation whose restore target was the illegal position itself. The same fault
  hid the penalty whenever an illegal displacement was followed by a legal-looking move, such as a
  bishop jumping its own pawn and then an ordinary knight move.
- A piece held in a player's hand is no longer listed as captured material on the opponent's
  screen while it is off its square.
- An illegal move that takes a shielding piece off the king's line now states "it exposes the own
  king to check" instead of predicting what it would do.

### Added

- `FirstTouchInvariant`: before any move is accepted, an independent check that it honours the
  first binding touch (FIDE 4.3). A violation stops the move instead of recording it.
- `RestoreTargetInvariant`: every position the arbiter asks the player to restore must be the turn
  start, one legal move from it, or a legal castling intermediate.
- The chess library's illegal-move reasons and the arbiter's restatements now live in
  `messages/illegal-move-reasons.properties`, paired per reason.

### Rules interpretation

- A-008 in [`docs/fide-deviations.md`](docs/fide-deviations.md): king and rook touched around an
  illegal king placement. The clock press is one illegal move; after the restoration the touches
  bind under FIDE 4.4.1 and queenside castling must be played.

### Documentation

- The release procedure names where the version is pinned, the changelog anchor form, the branch
  hardcoded in the manual-testing launcher, and the case of a branch older than the last release.
- Deploying updates ([SETUP.md §12](SETUP.md#12-deploying-updates)) covers what it was missing:
  moving the production checkout to the published tag, the scoped ACL and `NOPASSWD` grant an
  operator other than `chess-server` needs (including the `delete_child` gotcha that failed the
  0.1.4 deploy), rolling back, and verifying through the public URL and WebSocket rather than
  localhost alone.

## 0.1.5 — Released Move Finality Guard — 2026-09-22

This release closes a released-piece loophole that could replace an already final king move with
castling after an arbiter recovery.

### Fixed

- Once a legal move has been completed by releasing the piece, later touches cannot retroactively
  create a different obligation. In the reported Black sequence `Ke8-f8`, `Rh8-e8`, clock,
  Revert, clock, the final move is Kf8.
- The final move is latched independently of Revert and readiness state until the turn ends.
- Every session path that changes the official position now checks the latched move first. This
  blocks a conflicting move even if a future arbiter-evaluation bug incorrectly accepts it.

### Manual-testing workflow

- `start.bat` fetches the latest pushed `codex/further-hardening` commit and serves it from the
  stable worktree. A failed fetch stops startup, and local uncommitted edits remain isolated.
- The release procedure is now documented in `RELEASING.md`, including the naming split between
  the PR title (`NAME`), changelog (`VERSION — NAME`), GitHub release title (`VERSION`), and the
  PR-body and release-notes headings (`NAME`).

The gameplay fix passed all 217 Java tests and all 127 Playwright tests. The launcher follow-up
passed two focused Windows Java cases plus a Playwright test that launched successive pushed
snapshots. Release-version checks pin `0.1.5` in Java, the version API, and both web page footers.
The final release-preparation build passed all 219 Java tests and the two focused release/launcher
Playwright tests; the packaged jar reports `0.1.5`.

## 0.1.4 — Abort Button Regression Guard — 2026-09-03

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
