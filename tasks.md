# Tasks

Live-planning source of truth. Mark items done in place (check, don't delete); keep an unshipped
release's tasks until it ships.

## After 0.5.0

Branch `quick-restart` (2026-11-25), created from the released `main` (`06c4f8f`). The default branch of
`start.bat` is unchanged (`fixes-and-improvements`, which is 0.5.0); this branch is served by
`start.bat quick-restart`.

- [x] **The restarted server binds its port at once** (2026-11-25). Found at the deployment of 0.5.0: after
  `launchctl kickstart -k` the site answered 502 for about 50 seconds. The new process could not bind the
  WebSocket port (`Address already in use`) while the connections of the killed process were expiring, gave up
  after 10 seconds, and was started again by launchd until it succeeded. The WebSocket library switches
  `SO_REUSEADDR` off unless asked; the server now asks for it, except on Windows, where a port with expiring
  connections can be bound anyway and the option would let a second server bind a port in use. Reproduced
  with the released jar by `tests/e2e/server-restart.spec.ts` (a real server process killed with two players
  connected and started again on the same ports: not up within 8 seconds before, up at once now, three runs);
  `TestQuickRestart` pins the option. An orderly stop inside one JVM does not reproduce it.
- [x] Verification on macOS: full Java suite (539 run, 0 failures, 4 skipped: the Windows-only launcher
  tests), full browser suite against the rebuilt jar (261 passed, 1 skipped, no retries). Not run: anything
  on Windows, where the option is left off and nothing changes; `RuleFuzzer` and `TouchMoveOracle` (no rule
  and no message handling changed).
- Not live: the site serves 0.5.0 without this fix, so the next restart there can again take about a minute
  (`SETUP.md` §12 says so).

## 0.5.0 — Two-Tab Play and Fairer Rulings

- [x] Scope: everything on `fixes-and-improvements` since `0.4.0` (section below) - the pause after a clock
  press without a move, touch-move before an illegal move of another piece, green circles for the move that
  was already made, the finished game after a reload, both sides from two tabs of one browser, Ashlar Chess
  22.2.0. `0.5.0` instead of `0.4.1` (decided with the owner on 2026-11-25): two-tab play and the finished
  game on reload are features, the server has a new message (`resumeEnded`), and the seat a browser stores
  has a new form.
- [x] Prepare version `0.5.0`, the dated changelog and README link, and the version pins
  (`TestOtbChessServer`, `version.spec.ts`, the deploy example in `SETUP.md`). Use **Two-Tab Play and Fairer
  Rulings** for the PR title and its opening heading, title the GitHub release `0.5.0`, and begin its notes
  with the release name.
- [x] Verification on macOS at the release commit: full Java suite (537 run, 0 failures, 4 skipped: the
  Windows-only launcher tests), `TouchMoveOracle` (33,793 cases, 0 differences), `RuleFuzzer` (20,000 games,
  0 findings), full browser suite against the rebuilt jar (260 passed, 1 skipped, no retries, `sounds.spec.ts`
  in audio mode `'real'`); the jar reports `0.5.0` (`version.properties`, manifest, and `version.spec.ts` for
  `/api/version`, lobby and board). Not run: anything on Windows; no browser other than Chromium.
- [x] Manual test by the owner before the merge (2026-11-25): passed, release approved. Asked to check
  through `start.bat` (default branch `fixes-and-improvements`): version `0.5.0`, two tabs of one browser with
  a reload of each, a reload after the game, a double click on the clock without a move.
- [ ] Merge the PR, tag `0.5.0` on the merge commit, publish the GitHub release, deploy by `SETUP.md` §12.
  Publication and deployment are reported in the conversation, not here.

## Fixes and improvements (after 0.4.0)

Development continues on `fixes-and-improvements` (2026-11-25), created from the released `main` (`1f5c17a`);
it is the default branch of `start.bat`. `jadoube` is kept for another purpose.

Feedback from a tester on the live site (0.4.0, 2026-11-25): "Everything seems to break if I press the clock
twice." Not reproducible by the owner on a local server. 42 combinations of clock presses were played through
in the browser against a local 0.4.0 server with the default game settings: own and opponent's lever, by
either player, once, twice and three times, slowly and faster than the server answers, with no move, a legal
move, an illegal move or only a touched piece on the board, before the opponent joined, during the pause to
restore a position, right after Revert, during the pause after a wrong clock press, by both players together,
and after the game. No script error, no lost connection and no game that could not be continued. Findings:

- [x] **Pause after a clock press without a move** (2026-11-25). The arbiter stops the clock for the ruling as
  for every other one: with automatic resume the clock restarts after the arbiter's pause
  (`OTB_WRONG_CLOCK_PAUSE_MS`, 5 s) and the ruling stays in view with its last sentence changed; without it both
  players are asked whether they are ready. A press in the pause is not ruled on and not answered, so a double
  click is one illegal move. After the restart a further press without a move is the next illegal move, as
  before.
- [x] Decided 2026-11-25 (Codex's reading of FIDE 7.5.1-7.5.5 and of the Arbiters' Manual, taken as
  authoritative): illegal moves made in the same turn add up. Two presses without a move lose a default game,
  and so does a press without a move after the Revert of an illegal move, also in the short delay before the
  clock restarts. Nothing was changed here.
- [x] **A late second press is not answered** (2026-11-25): a press that reaches the server when the player's
  lever is down (not their turn, or the game is over) gets no answer, so "It is not your turn." no longer
  replaces "Move accepted. Opponent's turn." The page takes two clicks on the same lever within 400 ms as one
  press, unless the player did something else in between.
- [x] **Touch-move before a further illegal move** (2026-11-25, A-011 in `docs/fide-deviations.md`). Reported
  journey: 1. e4, Black Ng8-g6 (illegal), Revert, Nb8-b6 with the clock was a 2nd illegal move and lost the
  game. Now a touch-move violation for the knight on g8: the move that replaces one the arbiter took off the
  board has to be made with the touched piece, and another piece moved instead is not counted as an illegal
  move. Unchanged: an illegal move of the touched piece itself.
- [x] **The same before any ruling on the turn** (decided by the owner on 2026-11-25): Black touches the knight
  on g8, plays the knight from b8 to b6 and presses the clock - a touch-move violation, green circle on g8, and
  no illegal move is counted. Before, this was the 1st illegal move. The touched piece is followed through the
  board events: picked up and put back it stands where it was touched; a touch of a piece that cannot move binds
  to nothing.
- [x] **Blue circles for the move that was already made** (2026-11-25): a released-piece ruling circles the
  start and end square of the move that was completed with the release, on both boards, until the position is
  restored.
- [x] **Green instead of blue** (decided by the owner on 2026-11-25: no third colour). The circles of the move
  that was already made are green, like the touched piece: one colour for what the player has to play. They
  therefore stay as green does - through the Revert, on the start and end square of the move that is on the
  board again, until the clock press accepts it (as blue they went at the Revert; confirmed by the owner). `BoardMark.Color.BLUE`, the
  style and the page's third colour are removed. Verified on macOS: full Java suite (537 run, 0 failures, 4
  skipped), and the eleven browser specs about circles, touch-move and released pieces against the rebuilt jar
  (109 passed, no retries); the full browser suite was not rerun for this.
- [x] **The own game cannot be joined from the same browser** (2026-11-25; removed again the same day, see
  *Two tabs of one browser can play each other* below): the start screen refuses a code
  that is the game this browser is already in, changes nothing, and points to a private window or another
  browser and to the manual (`MANUAL.md`, *Playing both sides yourself*). Client only: the server cannot tell
  two tabs of one browser from two players, so there is no Java test for it.
- [x] Verification on macOS with all of the above: full Java suite (527 run, 0 failures, 4 skipped: the
  Windows-only launcher tests), `TouchMoveOracle` (33,793 cases, 0 differences), `RuleFuzzer` (20,000 games,
  0 findings), full browser suite (250 passed, 1 skipped, no retries). Not run: anything on Windows; the
  launcher's default branch was changed in `start.bat` and `TestWindowsLauncher`, whose tests run on Windows
  only.
- [x] **Ashlar Chess 22.2.0** (2026-11-25, from 20.0.0; the latest release). The app's code needed no change.
  What a player can notice, from the library's releases 21.0.0 to 22.2.0: a custom starting FEN is checked more
  strictly - a check no single move can create (two rooks, three checkers), more promoted pieces than missing
  pawns, split empty-square runs (`44`), and leading zeros in the two counters are refused with the library's
  reason (`TestStartingFenStrictness`, `lobby.spec.ts`); the draw instead of a loss on flag fall or resignation
  (the opponent cannot checkmate) comes from the library's new unwinnability engine, written from the FUN 2022
  paper; the lenient SAN of a draw claim keeps the named piece and origin.
- [x] **The restated king-in-check reasons stay** (decided by the owner on 2026-11-25). Asked for: remove the workaround, since the
  wording was fixed in the library. It is not: 22.2.0, and the library's `main` after it, still say "it would
  leave the own king in check" and "it would expose the own king to check" (`ValidateNewMove.kingSafetyMessage`),
  so `illegal-move-reasons.properties` still turns them into "it leaves ..." and "it exposes ...". Without the
  pairs the arbiter's ruling would speak of a move that "would leave" the king in check again.
  `TestIllegalMoveReasons.theLibraryStillWordsTheseReasonsConditionally` now pins the library's own text, so the
  upgrade that rewords it fails that test and names the file to clean up.
- [x] Verification on macOS with Ashlar Chess 22.2.0: full Java suite (533 run, 0 failures, 4 skipped: the
  Windows-only launcher tests), `TouchMoveOracle` (33,793 cases, 0 differences), `RuleFuzzer` (20,000 games,
  0 findings), full browser suite against the rebuilt jar, whose manifest names `ashlar-chess-22.2.0.jar`
  (251 passed, 1 skipped, no retries). Not run: anything on Windows.
- [x] **A reload after the game shows the finished game** (2026-11-25). Second report from the tester: "I
  clicked the clock during my turn, twice and the board bugged and all the pieces disappeared from it."
  Reproduced on a local server: the game ends (in 0.4.0 two presses without a move lose at once), the player
  who joined reloads the page - empty board, "This game has already ended." That the tester reloaded is an
  assumption. The creator's page created a new game on such a reload. Now the server answers a `resume` into
  an ended game with `resumeEnded` (board as it stood, final times, the result as announced) instead of
  refusing it; no seat is given back, so nothing can be done from that page and no rematch offered. Also for
  the leaver of an abandoned game who returns ("You left the game and lose." with the board, before an empty
  board). The page keeps its seat after the game, marked as ended. Not covered: a game the server has already
  removed (30 minutes after the end, both players gone) still comes back as "no longer available" with an
  empty board.
- [x] **Two tabs of one browser can play each other** (asked for by the owner on 2026-11-25; replaces the
  refusal above, which is removed with its message, style and tests). Each page notes its own seat in its
  history entry, so a reload takes exactly that seat; the browser's seats are a list in `localStorage`
  (`otbSeats`, before one `otbSession`); a page holds a Web Lock per seat, so the start screen and a newly
  opened page know which games are open in a tab (`static/js/seats.js`, SPECIFICATION *Joining*). "Return to
  game" is offered only for a running game that no tab has open - in a second tab beside the creator's it no
  longer appears, and the code joins the game as the opponent. Two separate browsers behave as before
  (reload, lost tab and return by the start screen or by the code, second window takes the seat over).
  Client only: the server cannot tell two tabs from two players, so there is no Java test for this part.
  Known: both tabs share the sound options, so with the sounds on each sound is played by both tabs. Without
  the Locks API (page served neither over HTTPS nor from localhost) no seat counts as held: a second tab
  then takes the creator's seat over instead of joining.
- [x] Creating or joining a *different* game in a second tab of the same browser no longer replaces the
  seat of the first tab (same change; `same-browser.spec.ts`).
- [x] Verification on macOS with the two changes above: full Java suite (537 run, 0 failures, 4 skipped: the
  Windows-only launcher tests), `RuleFuzzer` (20,000 games, 0 findings), full browser suite against the
  rebuilt jar (260 passed, 1 skipped, no retries). `TouchMoveOracle` not run: no ruling changed. Not run:
  anything on Windows, and no browser other than Chromium - the page's own seat and the locks are browser
  features, so Firefox and Safari are untested.
- Not broken: presses before the opponent joined and after the game (ignored), during the pause to restore a
  position ("please restore the position", no penalty), during the pause after a wrong clock press ("please
  wait"), several fast presses of the opponent's lever (counted once per pause), both players pressing together.

## 0.4.0 — Piece and Clock Sounds

- [x] Scope: everything on `add-sounds` since `0.3.0` (section below) - the sound for a piece set down and the
  sound for a clock press, on both boards, with the two options on the start screen; the creator's board in a
  browser that refuses storage. `0.4.0` instead of `0.3.1`: the release adds a feature and a server message.
- [x] Prepare version `0.4.0`, the dated changelog and README link, and the version pins
  (`TestOtbChessServer`, `version.spec.ts`, the deploy example in `SETUP.md`). Use **Piece and Clock Sounds**
  for the PR title and its opening heading, title the GitHub release `0.4.0`, and begin its notes with the
  release name.
- [x] Verification on macOS at the release commit: full Java suite (502 run, 0 failures, 4 skipped: the
  Windows-only launcher tests), and `version.spec.ts` and `sounds.spec.ts` against the rebuilt jar
  (`/api/version`, lobby and board show `v0.4.0`; the 12 sounds tests in each audio mode). The full browser
  suite (240 passed, 1 skipped) and `RuleFuzzer` (20,000 games, 0 findings) passed at `d7dc180`; since then
  only the piece sample, the helper of the sounds tests, documents and release metadata changed.
  `TouchMoveOracle` not run: no ruling changed.
- [x] Full browser suite at the release commit `4154c4d` on macOS: 240 passed, 1 skipped (the Windows-only
  launcher test), no retries, `sounds.spec.ts` in audio mode `'real'`.
- [x] Reported from the manual test on Windows (2026-11-24): with the sounds on in the window that creates the
  game and off in an incognito window that joins, the sounds of both players are heard, and the same the other
  way round. No defect found: the board with the sounds on plays both players' pieces and clock presses, as
  decided, and two windows on one computer share the loudspeaker. `sounds.spec.ts` now replays the journey both
  ways round, per board (14 tests): the board with the sounds off opens no audio output and plays nothing.
- [x] Manual test by the owner on Windows on 2026-11-24, before the merge: both sounds heard in the app, in
  Chrome and in Edge. With the sounds on in one browser and off in the other, muting the browser with the
  sounds on in the Windows volume mixer left silence, and muting the other one changed nothing - both ways
  round. The owner then asked for the release.
- [ ] Not done before the release: listening on an iPhone, including after locking and reopening the phone.
- [ ] Not run for this release: the Windows-only launcher tests (4 Java, 1 browser) and the full suites on
  Windows. The launcher and its tests are unchanged since `ee98572`, where the launcher tests passed on Windows.
- Publication (merge, tag, GitHub release) and deployment are reported in the conversation, not here
  (`RELEASING.md`, *Manual test and publish*, step 5).

## Sounds (after 0.3.0)

- [x] Development continues on `add-sounds` (2026-11-23), created from the tip of `further-improvements`: the
  released `main` plus the publication note of `0.3.0`. `further-improvements` is deleted on GitHub and locally.
  `start.bat` serves `add-sounds` by default; `TestWindowsLauncher`, the README, `RELEASING.md` and the agent
  instructions name it. Not run: the Windows-only launcher tests (they need the Windows machine).
- [x] `RELEASING.md`: after the merge no commit goes on the release branch and nothing is recorded in
  `tasks.md`; the release branch is checked and deleted when it is no longer needed; the next development
  branch starts from the released `main`.
- [x] Requirement and proposed implementation of the sounds written down as
  [E-004](docs/enhancements.md#e-004--sounds-for-a-released-piece-and-for-the-clock-press).
- [x] Review of the E-004 proposal (Codex, 2026-11-23) and the decisions it asked for: the two samples are
  supplied by the project owner, and both players hear both players' pieces and clock. From the review: the
  clock sound waits for the server also for the own press, `clockPressHeard` goes to both players, the audio
  output is woken on every gesture, the tests observe the audio output. Recorded in E-004.
- [x] **Sounds implemented (2026-11-24).** A piece set down on the board and a clock lever pressed down are
  heard on both boards; two options on the entry screen, saved in the browser, both on by default.
  `static/js/sounds.js`, `static/sounds/`, `ChessBoard.onPiecePlaced`, `GameSession.consumeClockPressRegistered`,
  message `clockPressHeard`, `.wav` served as `audio/wav`. Also from the review: the creator's board is set up
  when the browser refuses storage (the `lastGameId` write is guarded). `SPECIFICATION.md` (*Sounds*),
  `MANUAL.md` and E-004 describe it.
- [x] The clock sample is stored without its first 0.14 s (room noise and lever travel before the click), so
  that the click is not heard late; the piece sample is stored as supplied (`static/sounds/README.md`).
- [x] Tests: `TestClockPressRegistered` (12), `TestClockPressHeardServer` (15), `TestStaticFileHandler` (5),
  `sounds.spec.ts` (12). `TestBoardMarksServer` now expects `clockPressHeard` before the ruling. The other
  browser tests start with both sounds off (`playwright.config.ts`).
- [x] Verification on macOS at the commit: `mvn test` runs 502 Java tests, 0 failures, 4 skipped (Windows-only
  launcher tests); `CI=1 npx playwright test --retries=0` against the rebuilt jar: 240 passed, 1 skipped
  (Windows-only); `RuleFuzzer` 20,000 games, 0 findings. The storage test fails without the `lastGameId` guard.
  Not run: `TouchMoveOracle` (no ruling changed). The entry screen with the Sounds section was looked at in a
  browser.
- [x] Piece sample replaced (2026-11-24): `piece.wav` is now the owner's recording of a piece set down on a wood
  board (`piece_release - wood.wav`, stereo, 24 kHz, 0.07 s, stored unchanged); the first one was of an
  alabaster board. `TestStaticFileHandler` accepts mono or stereo.
- [x] By ear, before the release: both sounds on the Windows machine - done, see the `0.4.0` section above. The
  iPhone is open there.
- [x] The sounds with the browser's own Web Audio, verified on this Mac on 2026-11-24. At first the test browser
  needed 20 s to open the audio output here (`new AudioContext()` blocked that long; the default output is the
  virtual device of the remote desktop) and suspended it again after 10 s, so `sounds.spec.ts` ran with its
  stand-in audio context only. The spec now has a third mode, `'no-device'`: the browser's own Web Audio opened
  without an output device, which opens at once and keeps running. All 12 tests pass in each of the three
  modes (`'real'` chosen by itself in the last run, where the default device opened at once; `'no-device'` and
  `'stand-in'` forced with `OTB_E2E_AUDIO`). `launcher.spec.ts` (Windows-only, a server of its own) still opens
  its page with sounds on.
- [x] From the same review, not part of the sounds: the cleanup commands of `RELEASING.md` step 6 (delete the
  release branch by name after a check) had gaps. Dropped instead of repaired: the procedure no longer deletes the
  branch at all; that is a manual task after the release. Step 6 now says only that.
- [x] Release the sounds on their own, when asked: asked on 2026-11-24, see `0.4.0` above.

## 0.3.0 — Board Circles and Early Draw Offers

- [x] Scope: everything on `further-improvements` since `0.2.0` (sections below) - the circles for the
  arbiter's rulings with the fixes from their two reviews, the reason of a repeated illegal move, and the draw
  offer before both players have moved. `0.3.0` instead of `0.2.1`: the release adds a feature and changes how
  an early draw offer is answered.
- [x] Prepare version `0.3.0`, the dated changelog and README link, and the version pins
  (`TestOtbChessServer`, `version.spec.ts`, the deploy example in `SETUP.md`). Use **Board Circles and Early
  Draw Offers** for the PR title and its opening heading, title the GitHub release `0.3.0`, and begin its notes
  with the release name.
- [x] Verification on macOS at the release commit: full Java suite, and `version.spec.ts` against the rebuilt
  jar (`/api/version`, lobby and board show `v0.3.0`), and `TouchMoveOracle` (33,793 cases, 0 differences).
  The full browser suite (228 passed, 1 skipped) and `RuleFuzzer` (20,000 games, 0 findings) passed at
  `f77b893`; the release commit changes metadata only.
- [x] Verified the release candidate `dfb41d4` through `start.bat` on Windows on 2026-11-23. The API, lobby
  and both boards reported 0.3.0. Both players saw the early draw offer rejected without an Accept/Reject panel.
  Both boards circled the touched g1 knight in green, showed g1 and g3 in red on the illegal move, restored green
  after Revert and cleared the circles when Nf3 was accepted. The Windows Java and Playwright launcher tests passed.
- [x] Ran both full suites on native Windows at `dfb41d4`. All 471 Java tests and all 229 Chromium Playwright
  tests passed, with zero skips or retries. The rebuilt JAR reports 0.3.0. `RuleFuzzer` found no issues in
  20,000 games and `TouchMoveOracle` found no differences across 33,793 cases.
- [x] Published on 2026-11-23: PR #19 merged into `main` as `3f14e32`, tag `0.3.0` on that merge commit, GitHub
  release `0.3.0` with the changelog entry as its notes. `further-improvements` was replaced by `add-sounds` on
  the same day (see *Sounds*).
- [x] Deployed `0.3.0` to the production host on 2026-11-23 ([SETUP.md §12](SETUP.md#12-deploying-updates)).
  The live version, the health endpoint, the WebSocket smoke test and a two-player early draw offer through
  the public URL were verified.
- [ ] Open, not part of this release: low contrast of the rings on dark squares, the rare reconnect race, the
  castling attempt that is detected twice (see *Fixes after the board-circle reviews*); sounds (see *Planned
  after the next release*).

## Further improvements (after 0.2.0)

- [x] Development continues on `further-improvements`, created from the released `main` (`9436251`).
  `start.bat` serves this branch by default; `TestWindowsLauncher`, the README, `RELEASING.md` and the agent
  instructions name it. Not run: the Windows-only launcher tests (they need the Windows machine).
- [x] **Circles for the arbiter's rulings (2026-11-18).** With a ruling on a clock press, both boards circle the
  squares it speaks of, in the style of Lichess. Green: the piece a touch obliges the player to move or capture
  (the first touched own piece that can move; the first touched opponent piece that can be captured; own piece
  and its capture together; king and rook for castling). Red: start and end square of an illegal move, when the
  board shows exactly one move or a castling attempt. Green stays until the move is accepted, red until the
  position is restored; a new ruling replaces them. `ArbiterResponse.boardMarks`, `ArbiterEngine.illegalMoveSquares`,
  `GameSession.getBoardMarks`, message `boardMarks`, `ChessBoard.setMarks`.
- [x] Decisions taken, for review: the circles are on both boards; own piece and the piece it must capture are
  both marked, and so are king and rook for a castling obligation; a castling attempt is marked on the king's
  squares; a reconnecting page gets the current circles; released-piece rulings and a moved opponent piece are
  not marked.
- [x] Tests: `TestBoardMarks` (15), `TestIllegalMoveSquares` (7), `TestBoardMarksServer` (5),
  `board-marks.spec.ts` (4). Verification on macOS: `mvn -q package` runs 435 Java tests, 0 failures, 4 skipped
  (Windows-only launcher tests); `CI=1 npx playwright test --retries=0`: 209 passed, 1 skipped (Windows-only);
  `RuleFuzzer` 20,000 games, 0 findings; `TouchMoveOracle` 33,793 cases, 0 differences.

### Board-circle review on Windows

- [x] Tested `0c1c90d` on Windows on 2026-11-21. All 436 Java tests and all 210 repository Playwright tests
  passed, including the four coloring scenarios. The rule fuzzer reported zero findings in 20,000 games and the
  touch-move oracle reported zero differences in 33,793 cases. Separate two-player checks reproduced the issues below.
- [x] Verified on both players' boards that a binding touch circles the piece in green, an illegal move circles its
  endpoints in red, and the circles survive a board flip and reconnect. Checked the rendered colors through `start.bat`.
- [x] Suppress misleading red circles when two pieces of the same kind have moved. From the starting position, drag
  the g1 knight to g3, then the b1 knight to g1 and press the clock. Both boards circle b1 and g3 as a single move.
  Fixed (see below): nothing is circled.
- [x] Keep the king's red circles when an illegal castle is repeated after Revert. Castling out of check twice in the
  same turn circles the king's squares the first time and loses them on the game-ending second attempt.
  Fixed (see below): the second attempt is circled too.
- [x] Resolve stale green circles when **Offer Draw** triggers an illegal-move ruling. Touch g1, play e2–e4, press the
  clock and revert. Play g1–g3 and offer a draw. Both boards keep the green g1 circle while the arbiter orders a revert.
  Fixed with the review fixes below: g1 and g3 are circled red, and g1 is green again after the Revert.
- [ ] Review what green identifies during recovery. An unfinished castle can circle the empty king origin while the
  king is already on g1. After an illegal move replaces green with red, Revert clears both colors even when the
  original touch obligation still binds. The rings also have low contrast on dark squares (green 1.7:1, red 2.7:1).
  The castling circle and the lost green circle are fixed with the review fixes below; the contrast is open.

### Fixes after the board-circle reviews

- [x] **Review of the circles (2026-11-21).** Four defects found by a review of `0c1c90d` and fixed:
  - a ruling on the move shown with a draw offer (instead of a clock press) drew no circles — it is marked like
    one on a clock press (`GameSession.markRuling`, sent by `handleCorrectTimeDrawOffer`);
  - the green circle of a touched piece was lost with an illegal-move ruling although the obligation went on — it
    stays, the red circles are added, and red covers green on a shared square until the position is restored;
  - a king that was put on its castling square already (only the rook still to be moved) was circled on the empty
    square it left — it is circled where it stands (`ArbiterResponse.castlingKingSquare`);
  - circles of an open ruling stayed on the final boards when the game ended another way (resignation, flag fall,
    ...) — the end of the game takes them off; only the illegal move that loses the game stays marked.
- [x] Decision taken, for review: a game that ends while circles are shown loses them on both boards, also the red
  ones of an illegal move that is still on the board (the opponent resigns during the restoration).
- [ ] Left open from that review: a reconnect that overlaps a restoration by milliseconds can be sent circles that
  were just removed (`resumeSeat` holds the room lock, `handleBoardEvent` / `handleRestorePosition` do not);
  `ArbiterEngine.illegalMoveSquares` looks for the castling attempt a second time per illegal move.
- [x] Tests: `TestBoardMarks` (24), `TestBoardMarksServer` (7), `board-marks.spec.ts` (13 — now also own piece
  with its capture, both castling cases, the losing illegal move and the rematch in the browser, and the Offer
  Draw journey of the Windows review replayed literally).
  Verification on macOS: `mvn -o test` runs 446 Java tests, 0 failures, 4 skipped (Windows-only launcher tests);
  `CI=1 npx playwright test --retries=0`: 217 passed, 1 skipped (Windows-only), before the Offer Draw journey
  was added as a test; `board-marks.spec.ts` (13 passed) was run again with it. Not run: `RuleFuzzer`,
  `TouchMoveOracle` (no rule is evaluated differently; only what is circled changed), and the launcher test
  changed on Windows in `b3732a5` (Windows-only).
- [x] **The two open circle issues of the Windows review (2026-11-21).**
  - Two pieces of the same kind that both moved are no longer circled as one move:
    `ArbiterEngine.illegalMoveSquares` follows every piece through the board events (drags, and the side area) and
    marks a move only when every other piece stands on its own square. Events that do not lead to the position on
    the board say nothing, and the position decides alone as before.
  - An illegal castling repeated after the Revert is circled again: the circles look only at the events since the
    board was last set back (`ActionSequence.markPositionRestored`, set by `GameSession.completeRestoration`).
    What was touched is still taken from the whole turn.
- [x] Seen while fixing: the *reason* of an illegal move ("Illegal move because ...") was inferred from all events
  of the turn (`inferAttemptedMove`), so it was missing when the move was repeated after a Revert: Nb1-b3 was
  answered "Illegal move: the knight cannot move in this way." the first time and "Illegal move." the second -
  in a default game on the very ruling that loses the game. Fixed: the reason is read from the events since the
  board was last set back, like the circles. An illegal castling repeated after the Revert and a different
  illegal move after a Revert get their reason too. Tests: `TestArbiterEngine` (3), `TestGameSession` (1),
  `rules-extra.spec.ts` (2, Nb1-b3 / Revert / Nb1-b3 replayed with and without losing the game).
- [x] Tests: `TestBoardMarks` (27), `TestIllegalMoveSquares` (13), `board-marks.spec.ts` (15, both journeys of the
  Windows review replayed literally). Verification on macOS: `mvn -o test` runs 455 Java tests, 0 failures,
  4 skipped (Windows-only launcher tests); `CI=1 npx playwright test --retries=0`: 220 passed, 1 skipped
  (Windows-only). Not run: `RuleFuzzer`, `TouchMoveOracle` (no ruling changed, only what is circled).

### Draw offers before both players have moved (2026-11-23)

- [x] **An offer before both players have made a move is declared not valid at once (FIDE 5.2.3).** Reported
  journeys: (1) the game starts, White offers a draw, Black gets the Accept/Reject panel, and *Accept* is answered
  "A draw can be agreed only after both players have made at least one move (FIDE 5.2.3)."; (2) the same after
  White's first move when Black offers. The offer was passed on although it could never be accepted. Now the
  arbiter intervenes when the offer is made: the offerer reads "The draw offer is not valid: a draw can be
  agreed only ...", the opponent reads "Your opponent offered a draw. The offer is not valid: ..." in the info
  window, no panel opens and nothing stays on the table. `DrawOfferManager.offerDrawTooEarly`,
  `GameSession.rejectEarlyOffer`.
- [x] Counting, as decided: early offers go on the same per-move ladder as the wrong-time offers - the second on
  the same move is not considered and brings the warning, the third loses the game
  (`WRONG_TIME_OFFER_GAME_LOST`); the count starts again with the next move. An early offer made the right way
  (with the move, before the clock) counts too; the move itself stays and is completed with the clock press.
  Black's first move with an offer is not early: both have then made a move.
- [x] Same cause, also fixed: a **rejected draw claim** on the first move was passed on as a draw offer
  (FIDE 9.5) with the same dead Accept button. Before both players have moved it is now rejected as usual (two
  minutes, the claim of the move is used, a named move must be played) but is no offer; both players are told
  "It does not count as a draw offer: ...". `DrawClaimManager.processClaim(..., drawCanBeAgreed)`,
  `DrawClaimResult.rejectedBeforeDrawCanBeAgreed`.
- [x] Decisions taken, for review: the opponent is informed passively (info window, nothing to answer); the game
  result of the third early offer keeps the wording "... by repeatedly offering a draw at the wrong time"; an
  early offer shown with an invalid move is ruled on as that move, as before; *Accept* and an answering offer
  keep their own 5.2.3 check as a safeguard; in a game from a custom position the moves of this game count, as
  before.
- [x] Tests: `TestFinalPassRegressions` (9 on this rule), `TestFinalPassServerRegressions` (5), new
  `early-draw-offer.spec.ts` (6, both reported journeys replayed literally), the old first-move journey in
  `final-pass.spec.ts` rewritten. 33 Java tests and 18 browser tests that offered or claimed on the first move
  now let both players move first (`DrawTestSupport.bothPlayersMove`, `bothPlayersMove`, `bothKingsMove` for
  the king-and-rook positions). Verification on macOS: `mvn -o test` runs 470 Java tests, 0 failures, 4 skipped
  (Windows-only launcher tests); `CI=1 npx playwright test --retries=0`: 228 passed, 1 skipped (Windows-only);
  `RuleFuzzer` 20,000 games, 0 findings. Not run: `TouchMoveOracle` (no touch-move ruling changed) and the
  Windows-only launcher tests.

### Planned after the next release

- [ ] **Sounds, in a release of their own.** A sound when a piece is released on a square (as on Lichess) and a
  sound when the clock is pressed. Two options on the entry screen, each on or off, default on: sound for a
  moved piece, sound for the clock press. Requirement and proposal:
  [E-004](docs/enhancements.md#e-004--sounds-for-a-released-piece-and-for-the-clock-press); tracked under
  *Sounds (after 0.3.0)*.

## 0.2.0 — Game Lifecycle and Draw Guards

- [x] Scope: everything on `latest-ai-model-review` since `0.1.6` - the model review of `0.1.6`, its
  follow-up passes, the cloud review, the rematch and expiry races, and the final pass with its three
  notes (sections below). `0.2.0` instead of `0.1.7`: the release changes behaviour (a draw only after
  both players have moved, one connection per seat, one game per connection), not only bugs.
- [x] Prepare version `0.2.0`, the dated changelog and README link, and the version pins
  (`TestOtbChessServer`, `version.spec.ts`, the deploy example in `SETUP.md`). Use **Game Lifecycle and
  Draw Guards** for the PR title and its opening heading, title the GitHub release `0.2.0`, and begin
  its notes with the release name.
- [x] Verification on macOS at the release commit: full Java suite, and `version.spec.ts` against the
  rebuilt jar (`/api/version`, lobby and board show `v0.2.0`). The full browser suite, `RuleFuzzer` and
  `TouchMoveOracle` passed at `f224714`; the release commit changes metadata only.
- [ ] Not done before the merge, at the owner's instruction to cut the release: the manual test through
  `start.bat` on Windows. The Windows-only launcher tests last ran on the Windows machine at `3fa512a`;
  the launcher has not changed since.

## Latest AI model review — 2026-11-17

- [x] Review release `0.1.6` at `a92ac68` on `latest-ai-model-review` for substantial
  bugs and missing regression coverage. Inspect game/session/arbiter logic, board and
  lobby UI, WebSocket handling, reconnection, clocks, room lifecycle, tests, and
  deployment configuration. Use a separate worktree at `../otb-chess-model-review`.
  The review identified the five release-worthy defects below; all five are now fixed
  on the review branch. Findings and line references describe the reviewed snapshot.

### Confirmed findings

- [x] **P1 — Expired time can be revived by increment.** `GameSession.pressClockButton`
  accepts a move without first deciding flag fall. `ClockManager.switchClock`
  ticks the remaining time down to zero and then adds increment unconditionally.
  Flag fall is otherwise polled once per second, creating a window in any increment
  game where a late move saves a player who has run out of time. A direct Java probe
  observed zero remaining time, `MOVE_ACCEPTED`, then 2000 ms and no flag fall.
  A live WebSocket reproduction used a 100 ms initial clock, waited 180 ms, and
  received the same accepted move and 2000 ms. The short initial clock compresses
  the reproduction of the same expiry boundary in normal games.
  Relevant code is `GameSession.java` lines 250–289 and `ClockManager.java` lines 55–69.
  Required coverage should exercise presses immediately before and after expiry,
  with increment, through the session and both live boards.

- [x] **P1 — Refresh during manual restoration can strand both players.** With
  auto-resume disabled, play e2–e5, press the clock, click Revert, and refresh White
  while both players have Ready to continue buttons. Both Ready buttons disappear,
  the clock remains stopped, and White's clock press says to wait until both players
  are ready. Reproduced through real browser clicks and page reload. The server's
  resync includes the broad IN_PROGRESS state but omits restoration/readiness state.
  The resync handler clears the returning player's controls, and opponentReconnected
  clears the other player's controls. Relevant code is `GameWebSocketServer.java`
  lines 1325–1355 and `static/js/game.js` lines 336–370 and 797–801.
  Required coverage should reload each seat during restoration, each readiness
  phase, and an unpressed move, and verify a playable, consistent state on both boards.

- [x] **P1 — Checkmate during a wrong-clock pause corrupts game state.** Start from
  `7k/8/5KQ1/8/8/8/8/8 w - - 0 1`. Black presses White's clock, triggering the
  admonishment pause, and White plays Qg7# during that pause. The board remains
  enabled. `evaluateForAutoEnd` applies the mating move and then calls switchClock
  on a stopped clock, throwing before endGame runs. Reproduced in Java and in the
  browser. Java showed a checkmated board with Black to move, IN_PROGRESS, and a
  null result. Both browsers had no result panel, and White saw the generic internal
  error. Relevant code is `GameSession.java` lines 294–355, especially 342–353.
  Required coverage should exercise automatic endings during every supported pause
  and ensure move, clock, result and both clients transition consistently.

- [x] **P1 — Finished results can be overwritten by later actions.** With White's
  draw offer pending, White resigns and Black then accepts the offer. The server
  replaces Black's win with a draw. A subsequent Black resignation replaces that
  with White's win. Java reproduced all three results. The live WebSocket path
  broadcast RESIGNATION with Black winning followed by DRAW_AGREEMENT. In ordinary
  clients, actions already sent before receipt of the ending can arrive in this order.
  `acceptDraw` and `resign` lack a finished-state guard, and `endGame` overwrites its
  result unconditionally. Relevant code is `GameSession.java` lines 529–541, 746–764,
  and 1021–1025. Required coverage should verify result immutability under delayed,
  repeated and competing terminal messages, including both clients' score and reason.

- [x] **P2 — Finished rooms permanently consume server capacity.** The room cap
  counts every entry in gameRooms, while cleanup removes only WAITING_FOR_PLAYERS
  rooms. Completed and abandoned games persist after both sockets close. At the
  default cap, 1000 retained completed rooms prevent all new games until restart.
  A server-handler probe with OTB_MAX_GAMES=2 and OTB_ROOM_TTL_MS=1 created, joined,
  ended and disconnected two games, explicitly ran cleanup, and observed two ENDED
  rooms and a capacity error on the third creation. Relevant code is
  `GameWebSocketServer.java` lines 267–270 and 1501–1510. Required coverage should
  verify capacity recovery after an ended-room retention period while preserving
  the supported rematch window and active rooms.

### Fixes and regression coverage

- [x] Adjudicate expiry before accepting a clock press; the clock itself refuses to add
  increment after expiry. Undo a speculative acceptance if expiry occurs during evaluation,
  preserving the original position and the released-piece safety checks.
- [x] Replay restoration/readiness controls to both seats on reconnect, preserving already
  acknowledged readiness. Retain the physical board separately from the committed chess
  position so refreshing an unpressed or invalid move cannot silently reset one board.
- [x] Finalize automatic endings without switching a paused clock. Ending a game clears
  recovery state and pending offers; delayed actions preserve the first result and metadata.
- [x] Reap finished, disconnected rooms after `OTB_ENDED_ROOM_TTL_MS` (default 30 minutes),
  periodically and before capacity checks. Keep active rooms and connected rematch players.
  Document the retention setting in `SETUP.md`.
- [x] Add Java regressions in `TestReviewRegressions` and `TestReviewServerRegressions`:
  expired/live increments, paused mate, immutable endings, reconnect readiness and physical
  position, retention duration, active/connected protection and capacity reclamation.
- [x] Add browser regressions in `review-regressions.spec.ts` and `room-retention.spec.ts`:
  both seats refreshing before Revert, before Ready and after one Ready acknowledgement;
  unpressed move refresh; paused mate; late terminal messages; expiry between ticker checks;
  and a capped isolated server that retains rematches and then admits a new game after cleanup.
- [x] `mvn -q package` passes all 257 Java tests on native Windows, including existing
  invariant and launcher coverage plus the new regression tests.
- [x] `CI=1 npx playwright test --retries=0` passes all 144 browser tests in 7.3 minutes
  on native Windows/Chromium against the rebuilt jar. Full coverage is required because
  these fixes touch shared clocks, game endings, recovery and room lifecycle. All 11 new
  browser regressions pass; no retries or skipped tests were needed.

### Follow-up review of the fixes — 2026-11-17

- [x] Review the fix commit (`262b666`) on macOS. The five fixes hold; `mvn -q package` and the
  full browser suite passed unchanged. The review found one regression and two remaining gaps,
  all fixed below.
- [x] **Regression — a clock press during the auto-resume delay locked the board.** The guard
  that keeps the delayed resume silent after the game ended also silenced it when a clock press
  had restarted the clock first. A press that did not end the turn (e.g. pressing without a
  move) then left the player with a running clock and a disabled board until a page refresh.
  The session now reports a resume caused by a clock press, and the server announces it at
  once, before the arbiter's answer; the delayed resume stays silent only when nothing is left
  to resume.
- [x] **Gap — expired time could still be escaped or reversed.** Only the clock press and draw
  acceptance adjudicated expiry first. In the up-to-one-second gap before the periodic check, a
  valid draw claim still drew for a player whose time had run out, and a resignation or a
  penalty action (wrong-time offer, wrong clock press) lost the game for the player whose
  opponent had already flagged. Every player action that can decide the game now adjudicates
  flag fall first.
- [x] **Gap — the result was announced more than once.** Draw by agreement sent `gameEnded`
  twice, and every late action re-sent it, overwriting a rematch offer on both boards. The
  result is now announced once per game, and gameplay actions arriving after it are dropped
  without a reply. Rematch, PGN export and reconnect messages are unaffected.
- [x] Java regressions added to `TestReviewRegressions` and `TestReviewServerRegressions`;
  browser regressions added to `review-regressions.spec.ts` (early clock press, claim after
  expiry, resignation after the opponent's expiry, single result announcement with late
  actions during a rematch offer). The four browser regressions fail against `262b666`.
- [x] `mvn -q package` passes all 267 Java tests on macOS (4 Windows-only launcher tests
  skipped). `CI=1 npx playwright test --retries=0` passes 147 browser tests in 3.4 minutes
  (1 Windows-only launcher test skipped). Full coverage was required because the changes touch
  clocks, game endings, restoration and message dispatch.
- [x] Pending draw offer missing after recipient refresh (present before `262b666`): fixed
  in the Windows follow-up below. Reconnect now restores the offer and its original explanation.

### Windows follow-up and remaining draw-offer fix — 2026-11-17

- [x] Fast-forward the review branch to Claude's `42ba578`, retaining all three follow-up fixes.
- [x] Restore pending draw offers after the recipient refreshes or reconnects. Store the
  original recipient message, including rejected-claim explanations; replay only to the
  recipient after resync. Clear stale client panels before replay. Do not register a new offer
  or reset touch/release invalidation, so reconnect cannot restore a lost right to accept.
- [x] Add three Java regressions and five browser regressions covering both recipient colours,
  acceptance, rejection followed by another refresh, converted-claim wording, and invalidated
  offers. All three Java regressions and all five browser regressions fail against `42ba578`
  without the fix and pass with it.
- [x] `mvn -q package`: all 270 Java tests pass on native Windows with no skips, including
  all six `TestWindowsLauncher` cases. The focused browser run passes all five new regressions
  and `tests/e2e/launcher.spec.ts`, which runs the real Windows launcher twice with isolated
  remotes and dedicated ports.
- [x] `CI=1 npx playwright test --retries=0`: all 153 browser tests pass on native
  Windows/Chromium in 8.3 minutes, with no skips or retries, against the rebuilt jar. Full
  coverage includes Claude's four follow-up regressions and the Windows launcher, because
  reconnect state, offers and the earlier shared game-flow fixes need integration coverage.
- Remaining unchecked historical items concern manual release acceptance, production deployment,
  and the public-service privacy notice. Automated Windows coverage does not mark those done.

### Second review pass — 2026-11-17

- [x] Review `d877341` again, concentrating on interactions between session transitions and
  server dispatch: overlapping pauses, restoration, penalties, simultaneous Ready/join requests,
  draw claims/offers, reconnect/rematch lifecycle, starting positions and board event handling.
  Cross-check the documented arbiter policies and intentional FIDE deviations. Four substantial
  defects and one lower-priority starting-position edge case are confirmed below. All five
  are now fixed on the review branch; the findings describe the reviewed snapshot.

- [x] **P1 — Simultaneous Ready confirmations can lock the moving player's board.**
  `GameWebSocketServer.handleReadyToContinue` calls the synchronized `playerReady`, then sends
  its reply outside that transition's lock. Two concurrent confirmations can therefore deliver
  `gameResumed` followed by stale `waitingForOpponentReady` to White. The latter handler disables
  White's board while White's clock is running and neither player has a Ready button.
  A concurrent Java handler probe reproduced this on round 2. A real WebSocket probe replayed
  the normal opponent-piece intervention -> Revert -> manual Ready journey and reproduced it
  on round 3. Relevant code: `GameWebSocketServer.java` 1210–1227 and `static/js/game.js` 492–494.
  Serialize the transition and its outbound messages, or make responses reject stale state.
  Required coverage: deterministic concurrent Java confirmations and both live boards remaining
  playable after simultaneous Ready, with no stale waiting message after resume.

- [x] **P1 — An obsolete wrong-clock timer overrides a later pause or turn.** Black presses
  White's clock; during the five-second admonishment pause White moves Black's e7 pawn to e6
  and clicks Revert with manual readiness enabled. When the old timer fires, White's clock
  restarts although both Ready buttons remain visible and neither player is ready. If both
  players become ready and White completes e4 before that callback, it instead switches the
  running clock back to White even though Black has the move. Both state failures were
  reproduced in Java; the waiting-Ready failure was also reproduced through real browser clicks
  using the production five-second pause. `GameSession.resumeAfterWrongClockPress` (894–902)
  only checks game state and its old side marker, not intervening restoration or turn changes.
  Required coverage: overlapping pauses and intervening turns; obsolete callbacks must neither
  charge time while waiting for Ready nor restart the wrong side's clock.

- [x] **P1 — Offering a draw during restoration can lose the game for the same illegal move
  twice.** From the initial position, play e2–e5 and press the clock. While Revert is displayed,
  click Offer draw. `offerDrawCorrectTime` re-evaluates the unchanged illegal position without
  checking the restoration/Ready flags, increments the illegal-move counter again and awards
  Black a win for White's "2nd illegal move". Confirmed in Java and on both live browser boards
  with the default two-illegal-move limit. Relevant code: `GameSession.java` 477–491. Clock
  presses already have the missing recovery-state guards. Required coverage: offers throughout
  restoration/readiness must not re-adjudicate the pending move or add another penalty.

- [x] **P1 — Concurrent joiners can both acquire the same seat.** The room-full check and
  seat/token assignment in `handleJoinGame` are not atomic. Two join requests to the same code
  can both receive `gameJoined` as Black with different tokens, and `gameStarted` is broadcast
  twice. One connection is overwritten as the room's Black player, leaving an admitted player
  without a usable seat. Confirmed by concurrent Java handlers on their first attempt and by
  real simultaneous WebSocket joins on round 4. Relevant code: `GameWebSocketServer.java`
  405–429. Required coverage: reserve the remaining seat and start the game atomically; exactly
  one joiner succeeds, the other gets a full-room response, and the first seat/token survives.

- [x] **P2 — Terminal starting FENs start an active game instead of producing a result.**
  Creating and joining `7k/6Q1/5K2/8/8/8/8/8 b - - 0 1` starts Black's clock and displays no
  result although Black is already checkmated and has no legal move. Confirmed through both
  browsers. `GameSession.startGame` (174–178) unconditionally starts the clock; automatic
  endings are checked only after subsequent board/move events. Reject terminal starting
  positions clearly, or adjudicate them before starting play. Required coverage: checkmate,
  stalemate and dead starting positions on both boards. Lower priority than the four above.

- [x] Verification: `SecondPassProbe.java` exercises overlapping pauses, wrong-side restart and
  the repeated illegal-move penalty; `JoinRaceProbe.java` exercises concurrent admission and
  readiness. Five targeted Playwright browser/live-WebSocket probes confirm the findings on
  native Windows/Chromium. Scripts, config and logs are under ignored `target/review/` in the
  review worktree (`second-pass.spec.ts`, `second-pass.config.ts`, `second-pass-browser.log`,
  `join-race.log`, `ready-race.log`, `live-join-race.log`, `live-ready-race.log`). These are bug
  reproductions, not passing regression coverage for fixes. The unchanged application retains
  the preceding 270-Java/153-browser passing baseline; those full suites were not repeated for
  this documentation-only review because the targeted probes expose paths that baseline misses.

### Second-pass fixes and regression coverage — 2026-11-17

- [x] Serialize Ready transitions and their replies under the room lock. Ignore duplicate or
  late confirmations once readiness is complete, so no waiting reply can disable a live board.
- [x] Cancel wrong-clock pauses when an intervention or turn supersedes them. Associate each
  delayed callback with its session and pause ID, and refuse to resume during restoration,
  readiness or another running turn. An obsolete callback cannot consume a newer pause.
- [x] Reject draw offers throughout restoration, manual readiness and the automatic resume
  delay with a specific explanation. Do not evaluate the pending move again, register an offer,
  consume escalation counts or add another penalty.
- [x] Reserve the remaining seat, issue its token and announce game start under one room lock.
  Exactly one concurrent joiner is admitted; the other receives the full-room response.
- [x] Reject already-terminal starting FENs before creating a room or starting any clock.
  Share validation between the lobby endpoint and WebSocket creation, covering checkmate,
  stalemate, insufficient material and the automatic 75-move draw. Valid playable FENs remain
  supported, including positions one move before mate.
- [x] Add 12 Java regression cases in `TestSecondPassRegressions` and
  `TestSecondPassServerRegressions`. Latches force the vulnerable Ready/join interleavings;
  session tests pin recovery guards, penalty counts, timer identity and the running side.
- [x] Add 10 browser regressions in `second-pass.spec.ts` and `second-pass-pauses.spec.ts`:
  simultaneous Ready and join requests from real pages, winning-seat reconnect, both boards
  remaining playable, repeated offers throughout recovery, and terminal FEN rejection through
  both entry points. Two isolated packaged servers use the production five-second wrong-clock
  pause to replay overlapping restoration and next-turn journeys without touching port 8080.
- [x] `mvn -q package`: all 282 Java tests pass on native Windows with no failures or skips,
  including the Windows launcher tests. All 10 new browser cases pass in the focused run.
- [x] `CI=1 npx playwright test --retries=0`: all 163 browser tests pass on native
  Windows/Chromium in 9.2 minutes, with no failures, skips or retries, against the rebuilt jar.
  Full coverage includes the Windows launcher and earlier review regressions; shared game
  flow, admission, clock callbacks and recovery require coverage beyond the focused cases.

### Review of the second-pass fixes — 2026-11-17

- [x] Review `d877341`, `3974c2b` and `ccd48cf` on macOS. The five second-pass fixes and the
  draw-offer replay after reconnect are correct for their targets; the Java suite and 162
  browser tests pass unchanged (the Windows-only launcher tests are skipped here). Three gaps of
  the same classes remained and are fixed below.
- [x] **A Revert message was accepted from anyone at any time.** `handleRestorePosition` checked
  neither the sender nor whether a restoration was pending. Not reachable through the UI, but a
  modified client could send it during the opponent's normal turn: the half-made move was reset
  and, with manual Ready, the game waited for both Ready confirmations while the mover's clock
  kept running. `GameSession.completeRestoration` now reports whether a restoration was pending
  and changes nothing otherwise; the server accepts Revert only from the player who has to
  restore. A repeated Revert can no longer withdraw the opponent's Ready confirmation either.
- [x] **Draw claims were still processed during a pause.** Offers were blocked during
  restoration, manual Ready and the automatic resume delay, claims were not. Through the UI:
  move an opponent piece, then claim on the board while Revert is showing - the claim was
  rejected, the opponent got two minutes and a draw offer during the pause. A claim with a move
  also restarted the claimer's clock while the game was paused. Claims in these pauses are now
  not considered: no penalty, no converted offer, no step on any claim ladder, and the one
  claim of the move stays available.
- [x] **Abort racing a join.** The abort's check and the room removal were not one step with
  the join's seat reservation: the creator could be told "Game aborted" while the joiner was
  put into a started game whose room was gone. Abort, join and the expiry of never-joined rooms
  now share the room lock, and a join re-checks that the room is still registered.
- [x] Add 11 Java cases in `TestThirdPassRegressions` and `TestThirdPassServerRegressions`
  (claims in all three pause phases, Revert from either player in normal play, Revert only by
  the restoring player and only once, forced abort/join interleaving) and 4 browser cases in
  `third-pass.spec.ts`. The Revert and claim browser cases fail on `ccd48cf`; the abort/join
  browser case checks that both pages agree on one outcome, the forced interleaving is in Java.
- [x] Verification on macOS: `mvn -o -q package` runs 292 Java tests with no failures (4
  Windows-only launcher tests skipped; counted from a freshly emptied report directory - an
  earlier figure of 293 included one stale report). `CI=1 npx playwright test --retries=0` ran 167 tests:
  165 passed, 1 skipped (Windows-only launcher), and the new abort/join case failed on a wrong
  expectation in the test itself (a refused abort leaves its refusal in the creator's message
  window). After correcting that expectation the four new cases passed 10 times each; the full
  browser suite was not repeated for that test-only edit.

### Third review pass — 2026-11-17

- [x] Own pass over `44bcc34`, aimed at interactions between the rules rather than single
  handlers. Method: `RuleFuzzer` (two simulated clients driving the real server with random
  moves, half-made moves, touches, clock presses, offers, claims, Revert/Ready, refreshes and
  timer firings, with state/board/instruction checks after every action) and `TouchMoveOracle`
  (touch-move rulings compared with the text of FIDE 4.3). Both are opt-in tools under
  `src/test`, outside the regular suite; see their class comments for how to run them.
- [x] What holds: over 20,000 random games no handler failed, no clock ran during a pause or
  stayed stopped, the player on move was never locked out, both boards and the server always
  showed the same position outside the case below, an untouched legal move was always accepted
  in every FIDE-conform order of execution (capture by removal first, en passant, castling,
  promotion in three orders), honest play always got a move accepted again, and a touch-move
  ruling was always satisfiable by moving the named piece. 33,793 touch-move cases match FIDE
  4.3 without a single difference.
- [x] Six inconsistencies were confirmed (fixed, see "Fixes for the third review pass" below). Each was first found by the fuzzer,
  reduced by hand and replayed through the real pages with ordinary clicks and drags (FEN for
  the castling cases: `r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1`). None produces a
  wrong game result on its own; all of them make the arbiter contradict itself or lose a draw
  offer. This pass records findings only; application code is unchanged.

- [x] **P1 — Contradictory castling orders: the king's release and a later rook touch pull to
  different sides.** White plays Ke1-g1, puts the king back on e1, moves Ra1-b1 and presses
  the clock. Arbiter: "Castling has been started ... you must complete the castling move by
  moving the rook from h1 to f1." After Revert, White castles kingside exactly as told:
  "Because you touched the king and the rook, and castling is legal, please perform the
  castling move." The same answer comes again for a fresh kingside castling from the start
  position; only queenside castling is accepted, which the arbiter never names. The released
  king (FIDE 4.7.1: only castling on that side remains) and the king-then-rook touch record
  (4.4.1, A-008) are evaluated independently: `findReleasedPieceViolation` reports the
  kingside lock first, the touch-move layer then demands the side of the first touched rook.
  Required: one ruling for the turn - the earlier commitment decides - and a message that names
  the side and the rook.
- [x] **P1 — "You must complete castling" after the rook was touched first.** White clicks the
  rook on a1, plays Ke1-c1 and presses the clock: "Castling has been started ... complete the
  castling move by moving the rook from a1 to d1." White does: "Touch-move violation: You
  first touched the rook on a1 ... but moved another piece ... move the rook from a1." By FIDE
  4.4.2 castling on that side was never available, so the first instruction is wrong, and the
  second message says the rook was not moved although it was. The released-piece lock ignores
  an earlier touch obligation; the fuzzer shows the same with an opponent piece touched first.
- [x] **P1 — "Your move is final. Press the clock to continue." when the clock press is then
  rejected.** Two ways: (a) White clicks the g2 pawn, plays h2-h3, then drags a Black pawn.
  After Revert both the restore and the resume message say the move is final; pressing the
  clock gives "Touch-move violation: You first touched the pawn on g2". (b) White plays Ke1-g1,
  moves the king on to f1 and presses the clock; after Revert the same "final" message appears
  although the rook still has to move, and the clock press repeats "Castling has been
  started". `restorationFromReleasedPiece` is set for every release that is legal in isolation
  (`findReleasedPieceCommitmentPosition` knows no touch obligation) and for an incomplete
  castling, and the server words the message from that flag alone. The fuzzer meets this in
  about one game in eleven. Required: say "final" only when a clock press on the restored
  position would be accepted.
- [x] **P1 — A counter offer replaces the pending draw offer instead of agreeing the draw.**
  White offers a draw; Black, with the offer on screen, presses Offer draw too. No draw. The
  single offer slot now holds Black's offer, Black's panel still shows White's: Accept answers
  "You cannot accept your own draw offer." Plausible in real play when both press at about the
  same time. Required: an offer made while the opponent's acceptable offer is pending is an
  acceptance.
- [x] **P1 — A player can reject, and thereby withdraw, their own draw offer.**
  `DrawOfferManager.rejectDraw` does not look at who rejects. In the case above Black presses
  Reject on the stale panel: Black's own offer is cancelled, White is told "Your opponent
  rejected the draw offer" and White's Accept panel disappears. Same result without a counter
  offer: Black offers during White's move, White plays e2-e4 (the old panel stays, by design),
  offers a draw with the move and then dismisses the old panel with Reject - White's fresh
  offer is gone and Black loses the Accept button with a false message. FIDE 9.1.2.1: an offer
  cannot be withdrawn. Required: only the recipient can reject; a panel whose offer was
  replaced must be closed or its buttons must act on nothing.
- [x] **P3 — A page refresh inside the 1.5 s automatic resume delay re-enables the board.**
  `resync` enables the board of the player on move and `sendRecoveryState` knows restoration
  and Ready but not the resume delay, while the server drops board events until the clock
  restarts: pieces moved in that window are neither recorded as touches nor shown to the
  opponent. Needs a refresh at exactly that moment; no wrong result seen.

### Fixes for the third review pass — 2026-11-17

- [x] **One ruling per turn for a release and a touch (findings 1 and 2).** `ArbiterEngine.findReleaseRuling`
  applies FIDE 4.7 and 4.3/4.4 in the order the player acted: a release binds only if it honours the touch
  obligation in force at that moment, and once a release binds, later touches change nothing. King released on
  g1, then the a1 rook moved: the arbiter asks for `Rh1-f1` and accepts it. Rook a1 touched first, then
  `Ke1-c1`: touch-move ruling naming the rook from the first clock press on, not counted as an illegal move.
  The castling touch-move message now names the rook and the side. Policy A-010.
- [x] **"Your move is final" only when the clock press is accepted (finding 3).** New
  `GameSession.isRestoredMoveFinal`; the server words the restore and resume messages from it. A started
  castling and a move that broke touch-move are restored with the ordinary wording; the latter is restored to
  the start of the turn, because it was never a move to go back to.
- [x] **Counter offer agrees the draw (finding 4).** An offer made while the opponent's offer can still be
  accepted is its acceptance - also the offer implied by a rejected draw claim. Policy A-009.
- [x] **Only the recipient can answer an offer (finding 5).** `DrawOfferManager.rejectDraw` refuses the offering
  side. When a player's own offer replaces an opponent's offer that could no longer be accepted, the server
  sends `drawOfferExpired` and the client closes the outdated panel.
- [x] **Refresh inside the automatic resume delay (finding 6).** The reconnecting player is sent the pause state,
  so the board stays locked until `gameResumed`. Resume, the delayed resume and the clock press are one room
  transaction each, so that notice cannot arrive after the message that ends the pause.
- [x] **Seventh inconsistency, found by the fuzzer once the others were out of the way:** a draw offer made
  together with an invalid move cancelled the offer that was already pending - the player's own earlier one
  (a withdrawal, FIDE 9.1.2.1) or the opponent's - while the panel stayed on the other screen and Accept
  answered "No draw offer is active". Now only the new offer is not registered.
- [x] Tests: `TestFourthPassRegressions` (16), `TestFourthPassServerRegressions` (7), `fourth-pass.spec.ts` (8;
  7 fail on `e29927a`, the eighth is a positive control). `RuleFuzzer`: 20,000 games with every check enabled,
  0 findings (before the fixes: about one game in eleven). `TouchMoveOracle`: 33,793 cases, 0 differences.
- [x] Verification on macOS: Java 315 run, 0 failures, 4 skipped (Windows-only launcher tests); Playwright
  174 passed, 1 skipped (Windows-only); the new and the third-pass browser tests repeated five times each.
- [ ] Not changed, noted for a decision: after a rejected draw claim with a move, a different move released
  and then disturbed is still restored to the released position (wording is now correct, the Revert after the
  next clock press brings the turn start back). Not reproduced as a player-visible contradiction.

### Cloud review of the review branch — 2026-11-17

- [x] A multi-agent cloud review of `latest-ai-model-review` against `main` at `dcd23e0` returned nine findings:
  six introduced by the branch, three already on `main`. Each was confirmed in the code before it was changed.
- [x] **No restore request on a finished game (finding 1).** A move owed after a rejected draw claim survived a
  flag fall found by the clock press itself; the server then sent `restoreRequired` on the ended game and
  paused its session again. `endGame` now clears the owed move, `enterWaitingForRestoration` ignores an ended
  game, and the server sends no restore instructions once the game is over.
- [x] **A socket that closes during a send no longer costs the other player the result (finding 2).** A socket
  can close between the open check and the send, and Java-WebSocket then throws. `GameRoom.send` skips such a
  socket; every server send goes through it. Before, the announce-once flag was set, the exception skipped
  `gameEnded` for both players, and every later action of the remaining player was dropped as late.
- [x] **A room left full without a game expires (finding 3).** The reaper no longer exempts full rooms: the room
  lock already keeps it from seeing a join in progress, so a full unstarted room is a failed join or rematch.
  Both players are told and unmapped. A joiner whose socket closes while being seated gives the seat back.
- [x] **Overlapping expiry sweeps (finding 4).** The sweep also runs on every `createGame`. Only the sweep that
  removes the room cleans up after it, and it removes only that room's mapping - a second sweep used to send
  "Game expired" again and could unmap the creator from a game created meanwhile.
- [x] **One replay per ruling (finding 5).** `ArbiterEngine.findFinalMoveCommitment` replayed the turn twice for
  every release prefix, so the cost of one board event grew with the cube of the turn's length (800 events
  without a committing release: over a minute of held session lock). `ReleaseReplay` carries the ruling
  forward in one pass. Same rulings as before on 21,188 randomised checks against the previous code.
- [x] **One final clock update (finding 6).** `sendGameEnded` owns the final clock update; the ticker no longer
  sends a second, identical one before a flag-fall result.
- [x] **Abandonment does not outrank a fallen flag (finding 7, on `main`).** `GameSession.abandon` checks the
  clocks first, like every other action that can decide the game. "Claim victory" pressed after the claimer's
  own time ran out, and the abandonment deadline, now end the game on time and announce that.
- [x] **Nothing is adjudicated during the pause after a wrong clock press (finding 8, on `main`).** Draw claims
  and offers are refused with "The game is paused. Please wait until the clock restarts." - no penalty time,
  no converted offer, no restarted clock, no step on a ladder. Policy A-006.
- [x] **An offer answering an offer made with a move agrees the draw (finding 9, on `main`).** While the
  opponent's offer can still be accepted, the "opponent has already made a move" refusal no longer applies.
  Policy A-009.
- [x] Tests: `TestCloudReviewRegressions` (11), `TestCloudReviewServerRegressions` (16), `cloud-review.spec.ts`
  (9). On `dcd23e0` 19 of the 27 Java tests and 6 of the 9 browser tests fail. The others are positive
  controls and, in the browser, the nearest player journey for the races it cannot force (findings 2, 3
  and 4); those races are pinned by the Java tests.
  `RuleFuzzer`: 3,000 games, 0 findings.
- [x] Verification on macOS: `mvn -q package` runs 342 Java tests, 0 failures, 4 skipped (Windows-only launcher
  tests). `CI=1 npx playwright test --retries=0`: 183 passed, 1 skipped (Windows-only), in 4.6 minutes.
  `TouchMoveOracle`: 33,793 cases, 0 differences.

### Concurrent endings and rematch recovery — 2026-11-18

- [x] Independently review `6b661ed` and reproduce four remaining P1 defects before changing code:
  a previous game's flag-fall callback disables a rematch; a resume validated before a rematch
  overwrites the opponent's swapped seat; losing `rematchStarted` loses the only usable reconnect
  token; and a third opponent-piece violation after expiry awards a win instead of a timeout draw
  against a lone king. The unchanged baseline passed 343 Java and 184 browser tests on Windows.
- [x] Bind result announcements to the exact game that produced them, checking under the room's
  rematch lock before stopping its ticker or claiming its announcement flag. Cover delayed old
  callbacks and identical result values from different games.
- [x] Validate resume tokens and game state inside the room lock, so a rematch cannot change the
  token's owner/colour between validation and attachment.
- [x] Preserve each player's reconnect identity across rematches, including a lost start message;
  restore the current colour, saved session and live UI from resync. Cover both colours, refresh
  after recovery and continued play on both boards.
- [x] Adjudicate flag fall before mid-play misconduct and announce it even when no board state
  accompanies the event. Cover the third-opponent-piece journey with a lone-king draw, the result
  on both boards and absence of restoration after expiry.
- [x] Add ten Java regressions in `TestRematchEndingRegressions`,
  `TestRematchReconnectRegressions`, `TestMidplayExpiryRegressions` and
  `TestMidplayExpiryServerRegressions`. All ten fail on `6b661ed` and pass with the fixes.
  `mvn -q package` passes all 353 Java tests on native Windows, with no failures or skips.
- [x] Add five browser cases in `rematch-concurrency.spec.ts` and `midplay-expiry.spec.ts`:
  lost rematch starts for both colours, a pre-rematch resume delivered after the swap, three
  rounds of competing endings/rematches followed by reconnect and real moves, and the literal
  two-warnings/Revert/Ready/expiry journey. The two lost-start cases, delayed resume and expiry
  case fail on `6b661ed` and pass with the fixes; the concurrent lifecycle case is a passing
  control, with the exact server interleaving forced in Java. The expiry case prepares the drag
  first and releases after expiry, checking that the third event precedes the result so the
  periodic ticker cannot mask the defect.
- [x] Run the full suites against the rebuilt jar on native Windows: `mvn -q package` passes
  all 353 Java tests; `CI=1 npx playwright test --retries=0` passes all 189 browser tests in
  16.3 minutes. No failures, skips or retries. Shared clocks, session transitions and reconnect
  state require the full suites. Independent review of the integrated changes found no further
  regressions in these fixes.
- [x] Commit and push all four fixes with their Java/browser regression coverage on
  `latest-ai-model-review`. The fixes are on the review branch, not on `main`.

### Final review pass before the release — 2026-11-18

- [x] Reviewed `6b661ed` (the fixes for the cloud review) and `3fa512a` (concurrent endings and rematch
  recovery) line by line, together with the code they touch: room expiry, joining, abandonment, the pause
  after a wrong clock press, the arbiter replay, result announcements, reconnect tokens. Both commits do what
  is recorded above, with two reservations for `6b661ed` (findings 3 and 6 below). No wrong ruling found.
- [x] Verification on macOS at `3fa512a`: `mvn -q package` runs 352 Java tests, 0 failures, 4 skipped
  (Windows-only launcher tests). `CI=1 npx playwright test --retries=0`: 188 passed, 1 skipped (Windows-only),
  in 5.6 minutes. `RuleFuzzer`: 20,000 games, 0 findings. `TouchMoveOracle`: 33,793 cases, 0 differences.
- [x] The arbiter rewrite of `6b661ed` (`ReleaseReplay`) compared with the engine of `dcd23e0` on 100,000 random
  turns: 2,982,694 rulings (final move, release lock, binding obligation, released-piece violation, clock
  press), 27,470 of the turns with a rule-window reset, 0 differences.
- [x] The tests of `6b661ed` that depend on a 50 ms clock, repeated 8 times under full CPU load: 0 failures.
- [x] Not run: the Windows-only launcher tests.

Findings. All nine are fixed (next section). Each was reproduced against the server, the first two also in the browser.
Findings 2, 5, 7, 8 and 9 are on `main` as well; finding 1 is on `main` for a hand-made message.

- [x] **1. P1 — A connection that no longer holds a seat still acts in the game: with the game open in two
  tabs a player agrees a draw alone.** A resume attaches the new socket to the seat, but the old socket stays
  open and keeps its entry in `playerGameMap` (`resumeSeat`). Its messages are handled with `Side.NONE`, and
  `DrawOfferManager.canAccept`, `acceptDraw` and `rejectDraw` only compare with the offering side. Replayed in
  the browser: Black opens the running game in a second tab (the lobby's Return to game, a duplicated tab, the
  bare `/game.html`); the old tab still looks alive, with enabled buttons and a standing clock. Black offers a
  draw in the new tab and presses Offer draw in the old one: "The game is drawn by agreement.", and White, who
  answered nothing, reads "Your opponent accepted the draw offer."
  Further: `rejectDraw` from the old tab removes the pending offer and tells nobody - the panel stays on the
  opponent's screen and Accept then answers "No draw offer is active."; Resign, and Offer draw with no offer
  pending, answer "We are sorry — the arbiter lost his concentration ..." (`Side.NONE` has no opposite side).
  On `main` the draw needs a hand-made `acceptDraw`; the counter offer of `dcd23e0` put it on a button.
  Required: a seat has one connection - a resume takes the old socket out of the game and tells it so, and no
  game action is accepted from a connection without a seat.
- [x] **2. P2 — A game starts against a player who is not there, and the abandonment deadline counts from the
  socket drop instead of from the start of the game.** The creator's socket drops while waiting for the opponent
  (tab closed, or the phone browser in the background while the code is being sent). `onClose` starts both
  timers although no game is running, and `joinRoom` seats the joiner without looking at the creator's
  connection. Replayed in the browser on the test server (grace 1.5 s, abandonment 6 s):
  (a) The joiner arrives 2.5 s after the drop: "Game started", no countdown (the notice went out at 1.5 s, to
  nobody), and less than 4 s into the game "Your opponent left the game. You win." The returning creator gets
  "This game has already ended." With the production values (12 s, 60 s) the creator loses 60 s after the
  drop, however short the game was: 2 s if the opponent joined after 58 s.
  (b) The joiner arrives 7.5 s after the drop: both timers are spent. No notice, no countdown, no Claim victory;
  the absent player's clock runs. The game ends only when that clock runs out or the present player resigns.
  A creator who returns finds the game running and the time gone.
  Related: a player who reconnects while the opponent is away is not told again. Since `3fa512a` the reconnect
  also stops the countdown (Claim victory was cleared before), so nothing on the screen says that the opponent
  is gone until the deadline ends the game.
  Required: a player who is absent when the game starts is treated as having left at that moment - notice,
  countdown, Claim victory and the abandonment deadline count from the start of the game; a player who
  reconnects is told when the opponent is away.
- [x] **3. P3 — The cost of one board event still grows with the square of the turn's length (finding 5 of the
  cloud review is only partly fixed).** `ReleaseReplay` replays the turn once per event, but every release in
  it that is part of a legal move still asks `TouchMoveEvaluator.findObligation`, which reads the whole turn
  so far. Measured with the pattern of the regression test (knight touched, pawn moved back and forth): 800
  events take 1.0 s in total (3 ms for the last ones), 1,600 events 6.4 s (10 ms), 3,200 events 44 s (39 ms
  each, under the session lock). The comment in `aLongUncommittedTurnIsRecordedPromptlyAndStillRuledOnCorrectly`
  ("must not grow with the square") says more than the code does. No player reaches such a turn by hand; a
  scripted client on the public server can keep a worker thread busy for as long as it likes. Required: bound
  the work - carry the replay and the obligation from event to event, or limit the board events of one turn.
- [x] **4. P3 — During a pause, answering the opponent's offer with Offer draw is refused, while Accept agrees
  the draw.** `offerDrawWrongTime` and `offerDrawCorrectTime` check the pause before `agreeDrawByCounterOffer`.
  Black offers a draw and then presses White's clock; White presses Offer draw: "The game is paused. Please
  wait until the clock restarts." White presses Accept: drawn. The same in a restoration pause. Contradicts
  A-009 and the comment in `GameWebSocketServer.offerDraw` ("whatever else is true of the moment"). Required:
  an offer that answers an acceptable offer agrees the draw in a pause as well.
- [x] **5. P3 — `resign` is accepted in a room whose game has not started.** Only with a hand-made message (the
  client hides the button). The session ends with "White resigns. Black wins the game."; the room keeps its
  free seat, a joiner is seated, `startGame` sets the ended session to IN_PROGRESS and the clock runs, while
  every move and clock press of both players is dropped as late, because the result was already announced.
  Required: a resignation needs a running game, and a join needs a room that is still waiting.
- [x] **6. P3 — A seat that is given back keeps its disconnect mark (new with `6b661ed`).** When the close
  callback of the lost joiner runs between the seating and the failed join confirmation, `disconnectedAt` of
  the seat stays set after `joinRoom` has given the seat back. The next joiner plays normally, but
  `GameRoom.isConnected` is false for them: after the game a rematch offer is answered "A rematch is no longer
  available - your opponent left the game." Needs that exact interleaving. Required: clear the mark with the
  seat.
- [x] **7. P3 — A draw is agreed before both players have made a move.** FIDE 5.2.3 allows the agreement only
  when both players have made at least one move. The app agrees a draw on the first move (offer, Accept), and
  `docs/fide-deviations.md` does not list this. Decision: enforce it, or record it as a deviation.
- [x] **8. P3 — The move made with a draw offer is missing from the game record when the offer is accepted.**
  White plays e2-e4, offers a draw and Black accepts before the clock press: both boards show the pawn on e4,
  the session board and the PGN do not have the move (the PGN also gives `*` as the result of this drawn
  game). Decision: apply the completed move when the draw is agreed.
- [x] **9. P3 — One connection can take every room.** `handleCreateGame` does not look at whether the
  connection already has a game: every `createGame` adds a room, and a room left behind this way is removed
  only when it expires (30 minutes), also after its connection has closed. 1,000 messages from one scripted
  client reach `OTB_MAX_GAMES`; until those rooms expire everybody else is answered "The server is at
  capacity. Please try again shortly." Running games are not affected. Required: a connection that creates a
  game gives up the waiting room it had.
- [x] Noted, no change proposed at first; all three fixed afterwards (see "The three notes" below). (1) When the game ends with an accepted move, two identical final clock
  updates are still sent before the result (clock press and automatic ending; the ticker path of finding 6
  of the cloud review is fixed). Harmless. (2) After the pause that follows a wrong clock press no message
  announces the restart: "The game is paused. Please wait until the clock restarts." stays in the arbiter
  window until the next message. The clock itself shows that it runs. (3) Since `3fa512a` every reconnect
  turns the board to the player's own colour, so a manual Flip board by White is undone by a reconnect (for
  Black it was so before).

### Fixes for the final review pass — 2026-11-18

The release is postponed for these: all nine findings are fixed, each with Java tests and a browser test of
its journey (finding 6: Java only, see there).

- [x] **A seat has one connection (finding 1).** `resumeSeat` takes the previous socket of the seat out of the
  game and tells it (`sessionReplaced`: "This game is now open in another window. This window no longer takes
  part."); the client freezes that window and stops its automatic reconnect, the saved session stays with the
  other window. No game action is accepted from a connection without a seat, and `DrawOfferManager` lets only
  the two players answer an offer. The window opened last holds the seat.
- [x] **A player's absence counts from the start of the game (finding 2).** `markAbsent` starts the notice and the
  abandonment deadline; timers belong to one absence (socket and time stamp), so those of a drop before the
  game are void. `joinRoom` marks a creator who is not connected as absent from the start of the game. After
  `resync` a reconnecting player is sent `opponentDisconnected` again with the time that is left (not inside
  the grace, where the pending notice arrives anyway, and not during an arbiter pause, where the player's
  own Revert or Ready prompt keeps the window).
- [x] **A turn records at most 500 board events (finding 3).** `GameSession.MAX_BOARD_EVENTS_PER_TURN`. A
  further event is not recorded and not forwarded, the sender reads "Too many piece movements in one move.
  Further movements are not recorded.", and it counts against the connection. The clock press is still ruled
  on. The limit was chosen over reworking the arbiter: it bounds every ruling, not only the one per event. The
  long-turn tests of the cloud review now use 400 events, and their comment says what the code does.
- [x] **An answering offer agrees the draw in a pause too (finding 4).** The counter offer is looked at before
  the pause in `offerDrawWrongTime` and `offerDrawCorrectTime`. Policy A-009.
- [x] **A resignation needs a running game (finding 5).** `resign` before the start is refused ("The game has not
  started."); only a room that still waits can be joined; `startGame` works once.
- [x] **The disconnect mark goes with the seat (finding 6).** Pinned in Java with the close callback running
  inside the failed send. No browser test: a page cannot make its socket close exactly while it is being
  seated, and the journey a browser can take (join, play, rematch) is the ordinary one that is covered.
- [x] **A draw is agreed only after both players have made a move (finding 7, FIDE 5.2.3).** A move on the board
  counts although its clock press is outstanding. Before that Accept and an answering offer are refused with
  "A draw can be agreed only after both players have made at least one move (FIDE 5.2.3)."; the offer stays on
  the table. Twelve Java tests and nine browser tests that agreed a draw on the first move now play a2-a3
  and a7-a6 first (`DrawTestSupport`, `ServerDrawTestSupport`, `bothPlayersMove`).
- [x] **The offered move and the result are in the record (finding 8).** When a draw is agreed, the completed
  move the offerer has on the board is performed first - after a rejected claim only the move that is owed.
  The PGN ends with `1-0`, `0-1` or `1/2-1/2` once the game is decided.
- [x] **A connection has one game (finding 9).** `createGame` gives up the room in which the connection still
  waits alone, and is refused in a running game ("You are already playing a game.").
- [x] Decisions taken while fixing, for review: the limit of 500 instead of an incremental arbiter (3); an
  answering offer that comes too early is refused, not registered as an offer of its own (7); the PGN result
  token (8); `createGame` refused during a running game (9); no absence notice during an arbiter pause (2).
- [x] Tests: `TestFinalPassRegressions` (13), `TestFinalPassServerRegressions` (21 methods, 30 cases),
  `final-pass.spec.ts` (12).
- [x] `RuleFuzzer` forgot nothing of an open touch-move ruling across a rematch; since a draw can be agreed
  during a restoration pause, it carried the ruling of the old game into the new one (2 of 20,000 games).
  The model now drops it at the rematch.
- [x] Verification on macOS with all fixes: `mvn -q package` runs 395 Java tests, 0 failures, 4 skipped
  (Windows-only launcher tests). `CI=1 npx playwright test --retries=0`: 200 passed, 1 skipped (Windows-only),
  in 5.9 minutes. `RuleFuzzer`: 20,000 games, 0 findings. `TouchMoveOracle`: 33,793 cases, 0 differences.
  Not run: the Windows-only launcher tests.
- [x] Not changed at first: the three notes at the end of the review above (double final clock update, no message
  when the clock restarts after a wrong clock press, Flip board undone by a reconnect). Fixed afterwards, see
  the next section.

### The three notes of the final review pass — 2026-11-18

- [x] **The final clock values go out once (note 1).** `sendClockUpdate` sends nothing for a game that has ended;
  the announcement of the result sends the final values (`sendClockValues`), directly before `gameEnded`. Before,
  a mate on the board and a game-ending clock press sent the same values twice.
- [x] **The restart after a wrong clock press is announced (note 2).** New message `clockRestarted`. The offender
  reads "Clock restarted. Game continues." in place of the admonishment, after the second press with the warning
  repeated (error style). The opponent's info window changes to "Your opponent pressed your clock. Your clock has
  restarted." The opponent's arbiter window is changed only if the arbiter had answered them "The game is
  paused. Please wait until the clock restarts." during the pause (`GameSession.consumeToldToWaitForClock`), so
  that a touch-move ruling standing there is not overwritten. A pause taken over by a restoration is not
  announced here; the restoration announces its own resume.
- [x] **Flip board survives a reconnect (note 3).** The client remembers that the player turned the board
  (`boardTurnedByPlayer`); `resync` keeps the board as the player has it. A new colour (rematch, also one missed
  while away) and a page loaded anew start from the own colour. Client only: no Java test.
- [x] Tests: `TestFinalPassRegressions` (+4), `TestFinalPassServerRegressions` (+6 methods, 9 cases),
  `final-pass.spec.ts` (+5). The clock-update test and three browser tests were run against the old code and
  fail there.
- [x] Verification on macOS: `mvn -q package` runs 408 Java tests, 0 failures, 4 skipped (Windows-only launcher
  tests). `CI=1 npx playwright test --retries=0`: 205 passed, 1 skipped (Windows-only). `RuleFuzzer`: 20,000
  games, 0 findings. `TouchMoveOracle`: 33,793 cases, 0 differences. Not run: the Windows-only launcher tests.

### Review baseline verification

- [x] `mvn -q package` on native Windows passed 245 Java tests, with zero failures,
  errors or skips. Existing coverage includes many gameplay regressions, but the
  five combined journeys above need dedicated regression tests when fixed.
- [x] Run focused review probes against unmodified application code. Local scripts
  and browser screenshots are retained under the ignored `target/review/` directory
  in the review worktree. `ReviewProbe.java` covers clock expiry, result overwrites
  and paused checkmate. `ReviewServerProbe.java` covers finished-room cleanup.
  `browser.cjs` covers the manual-ready refresh and paused-checkmate journeys.
  `wire.cjs` exercises expired time and result overwrite through the live server.
- [x] `CI=1 npx playwright test` passed all 133 existing browser tests in 7.2 minutes.
  Full coverage establishes the baseline across all reviewed game flows. The
  focused probes reproduced gaps that this passing baseline does not cover. Runtime
  validation is local Windows/Chromium. macOS launchd, Docker and the public proxy
  deployment received source/configuration review only.

## 0.1.6 — Touch-Move and Release Guards

- [x] Keep an earlier binding touch over the king-and-rook castling commitment, including a touch
  made during an illegal move earlier in the same turn. Added `FirstTouchInvariant`, an independent
  fail-closed re-check of FIDE 4.3 before any move is accepted, extended to 0.1.5's latched final
  move after the merge.
- [x] Stop listing a piece held in a player's hand as captured material on the opponent's screen.
- [x] Bind a release only when the whole board matches that legal move, so an illegal displacement
  followed by a legal-looking one is the illegal move it is, with its penalty. Added
  `RestoreTargetInvariant` over every restore target. Documented the FIDE 4.4.1 reading as A-008.
- [x] State the king exposure instead of predicting it, and move the library's reason wording into
  `messages/illegal-move-reasons.properties`.
- [x] Merge released `main` (0.1.5) into `claude/fixing` and reconcile both released-piece designs.
- [x] Prepare version `0.1.6`, the dated changelog and README link. Use **Touch-Move and Release
  Guards** for the PR title and its opening heading; use **0.1.6 — Touch-Move and Release Guards**
  in the changelog, title the GitHub release `0.1.6`, and begin its notes with the release name.
- [x] Fill the gaps in the release and deployment documentation found while following it: version
  pins, changelog anchor, launcher branch, a branch older than the last release, and deployment's
  update half. Deploy-operator access (ACL + `NOPASSWD` grant, `delete_child` gotcha) and the
  public-path verification came from the deployment session.
- [ ] Manually verify the pushed release candidate through `start.bat`, including the three
  reported journeys and the visible `v0.1.6`.
- [x] Merge PR #17, then tag and publish `0.1.6` (merge commit `a92ac68`, 2026-09-22).
- [ ] Deploy `0.1.6` (supersedes the pending `0.1.5` deployment; production is on `0.1.4`), then
  verify live health, version, and a two-player flow.

## 0.1.5 — Released Move Finality Guard

- [x] Start `codex/further-hardening` from current `main` for the next round of beta bug fixes.
- [x] Record the absolute branch-and-PR rule in `AGENTS.md` and `CLAUDE.md`: no direct commits
  or pushes to `main`, including documentation and task tracking. Keep the already-pushed
  branch-cleanup record (`20c3cb0`) as requested.
- [x] Keep a completed legal release final across later touches and Revert: Black's Ke8-f8,
  Rh8-e8, clock, Revert, clock must accept Kf8 and never replacement castling. Latch the final
  move independently of recovery state and guard every session move-application path.
  Cover the literal journey and repeated replacement attempts in Playwright, plus Java
  recovery-reset and deliberately faulty arbiter-acceptance regressions.
  Verified with `mvn -q package` (217 Java tests), the five focused released-piece e2e tests,
  and the full `npx playwright test` suite (127 passed, 6.4 minutes). Full browser coverage
  checks the shared acceptance, castling, claims, automatic-ending and restoration paths.
- [x] Make `start.bat` fetch and serve the latest pushed `codex/further-hardening` commit,
  stopping on fetch failure. Workflow: automated verification, branch push, manual testing,
  then PR merge. Keep the local working tree isolated from the served snapshot. Verified with
  two Windows Java launcher failure cases, the license-header check, and a focused Playwright
  test that launches the real server twice from an isolated remote and checks the browser sees
  each newly pushed snapshot while local edits and the local checkout remain intact. No game
  logic changed, so the focused launcher coverage suffices for this follow-up.
- [x] Prepare version `0.1.5`, the dated changelog and README link, and a repeatable release
  procedure. Use **Released Move Finality Guard** for the PR title and its opening heading; use
  **0.1.5 — Released Move Finality Guard** in the changelog, title the GitHub release `0.1.5`, and
  begin its notes with the release name.
  The release-preparation build passed all 219 Java tests;
  the two focused release/launcher Playwright tests passed; and the packaged jar reports `0.1.5`.
- [x] Manually verify the pushed release candidate through `start.bat`, including the reported
  Kf8/Revert journey and the visible `v0.1.5` (approved 2026-09-22).
- [x] Merge PR #16, tag and publish `0.1.5` (merge commit `d06d89b`, 2026-09-22).
- [ ] Deploy `0.1.5`, then verify live health, version, and a two-player flow.

## Publish the server (beta)

- [x] Restore the release naming convention: a release PR targets `main`, uses the descriptive name
  only as its title, and begins its body with that heading. Releases `0.1.3` through `0.1.6` use the
  version only as their GitHub title, while each descriptive name appears as the first heading in
  the release notes. Normalize release PRs #12 through #17 to that PR convention, and update
  `RELEASING.md` and the agent instructions so future releases keep this split.

Architecture (reviewed): iMac running native `launchd` services → Cloudflare Tunnel
(`cloudflared`, no inbound ports — fits the guest network) → Caddy single public origin
(static + `/ws`) → Java app (HTTP 8080 + WebSocket 8081 in one JVM, in-memory state).
A Linux VPS with a Dockerfile is the later portability path, not the initial runtime.

**Status (2026-09-22): public beta available.** The lobby, health endpoint, and version endpoint at
[play.otb-chess.app](https://play.otb-chess.app/) respond without login; the live server reports
`0.1.4`. Source release publication and production deployment remain separate. The previously
deferred privacy notice remains open.

### 0.1.4 — Abort Button Regression Guard

- [x] Delete the fully merged GitHub branches `aquaman-testing`, `publish-server-beta`, and
  `testing-hardening`; start `codex/abort-regression-guard` from released `main`.
- [x] Recover `7910bdd`'s Abort-legibility assertions, rendered-control width checks, and workflow
  instructions, excluding the obsolete handover document. Prepare matching `0.1.4` release notes.
- [x] Run the full Java and Playwright suites before opening the PR: `mvn -q package` passed all
  213 Java tests; `npx playwright test` passed all 125 e2e tests (6.5 minutes). The rebuilt local
  jar's `/api/version` reports `0.1.4`. No gameplay or application UI code changed.
- [x] Open PR #15 after full verification and finalize the dated changelog, README release link,
  and matching publication notes. Finalization changes documentation only.
- [x] After PR #15 was merged and `0.1.4` published, delete the GitHub branches
  `claude/reading-session-3b64af` and `codex/abort-regression-guard`. Verified the recovered tests
  and workflow instructions match `main`; only the intentionally excluded handover was unique.
  Local branches/worktrees and release tags were retained.

### 0.1.3 — Public Beta Launch

- [x] Prepare the named release: version, README, changelog, and matching PR/release-note text.
  Verified with 213 Java tests, 14 focused abort/lobby e2e tests, and `/api/version` returning `0.1.3`
  from the rebuilt local jar. The abort fix previously passed the full 124-test e2e suite; subsequent
  changes are wording and release metadata only.
- [x] Deployment was superseded by `0.1.4`; the live endpoint reported `0.1.4` on 2026-09-22.
- [ ] Restore an accurate public-service privacy notice, including hosting and logging details.

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

## Bug fixes (`claude/fixing`)

- [x] Review the branch after its merge of released `main` (`e0a994c`) and the latched-move
  first-touch follow-up (`f8a0d38`). Check the interaction between earlier touch obligations,
  completed-move finality, restore-target validation and held-piece rendering; run the full
  Java and Playwright suites before pushing the launcher update for manual testing. No blocking
  findings. `mvn -q package` passed all 241 Java tests; `npx playwright test` passed all 132
  browser tests (7.0 minutes), including both released Kf8 regressions and all three new bug
  journeys. Full coverage was required because these fixes share move/recovery and board UI paths.
- [x] Point `start.bat` at the latest pushed `claude/fixing` commit and align the launcher
  regression fixtures and current workflow documentation. Keep the version at `0.1.5` until
  the next release is prepared; identify this test branch by the launcher's branch/commit banner.
  The Java launcher test now checks failure when only the former testing branch is available;
  the browser test serves successive `claude/fixing` commits from an isolated Git remote.
- [x] Take the served branch out of `start.bat`: it resolves `OTB_TEST_BRANCH`, else the first
  argument, else the default `claude/fixing`, so a release no longer edits the launcher and an old
  testing branch can be deleted. `RELEASING.md` step 6 now sets the branch instead of editing files.
  The Java launcher test covers the default, both override forms and the failure guarantees, plus a
  branch-resolution contract check that runs on every platform; the browser test drives the real
  launcher twice against an overridden branch.
- [x] Make `latest-ai-model-review` the launcher's default testing branch: `claude/fixing` was
  fast-forwarded into it and deleted, so `start.bat`, its contract test and the documentation now
  name the new default.
- [ ] Manually test `latest-ai-model-review` on the ThinkPad before preparing and merging its
  release PR.
- [x] Run the Windows-only launcher coverage (`TestWindowsLauncher` and `tests/e2e/launcher.spec.ts`)
  on Windows before merging: completed 2026-11-17 on the Windows workspace with all six Java
  launcher cases and the real-launcher browser test passing, no skips. See the Windows follow-up.

- [x] **Castling accepted despite an earlier touch-move obligation.** After 1. g3 e5 2. Bg2 d5
  3. Nf3 Nc6, White played `Nb1-b3` (illegal), put the knight back and castled short; the castling
  was accepted. The king-then-rook castling commitment overrode the earlier knight touch. It now
  applies only when no earlier touch binds. Added `FirstTouchInvariant`, an independent fail-closed
  re-check of the first-touch rule before any move is accepted. Unit tests and the literal e2e
  journey pin both.
- [x] **Pressed piece shows as captured material on the opponent's screen.** While the player on
  move held a piece (e.g. White pressed the a2 pawn), the opponent's view listed it among the
  captured material beside the board until it was released. The side-area count now treats a
  piece lifted off its square as still in play. Client-only fix; the literal e2e journey (White
  holds a2, Black holds h7) pins both colours.
- [x] **Beginner castling Ke1-b1, Ra1-c1 got a released-piece violation instead of an illegal
  move.** The rook release counted as the legal Rc1 although the king already stood on b1, so the
  illegal move escaped its penalty and the "revert" target was the illegal board itself. A release
  now binds only when the whole board matches the legal move. Afterwards White must castle
  queenside (FIDE 4.4.1; interpretation documented as A-008). Added `RestoreTargetInvariant`, a
  fail-closed check of every restore target. Unit tests and the literal e2e journey pin it.
- [x] **Illegal-move reason stated the exposure conditionally.** A move that took a shielding
  piece off the king's line was reported as "it would expose the own king to check" — the library's
  wording for a move that was only proposed. The player made the move, so the arbiter now states
  "it exposes the own king to check", matching the existing "it leaves the own king in check".
  The conditional wording stays for a draw claim's presented SAN move, which was not played.

## Backlog (not scheduled)

- [x] **Mention the arbiter in the starting-page description.** Updated the sentence to
  "An educational chessboard that simulates physical board play with an arbiter."
  Java and focused Playwright coverage pin the exact wording.
- [x] **Make the waiting creator's Abort action discoverable again.** The control-bar redesign
  reduced the original text button to an ambiguous `!` icon below the board. Move an explicit
  **Abort game** button beside the share code / Copy-code action in the arbiter panel, where the
  creator is already looking while waiting. It must remove the unstarted room and return to the
  lobby, remain colour-symmetric, survive a waiting-page refresh, and disappear when play starts.
  Verified with a Java server test plus the literal lobby → create as White → abort → lobby browser
  journey; full unit and 124-test e2e suites pass.
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
- [x] **Rematch (Lichess-style).** After a normally ended game both result panels show a
  **Rematch** button. One player's click offers (their button freezes as "Rematch offered"); the
  opponent's button starts **blinking**; the opponent's click accepts. The rematch reuses the room
  and game id: same time control and settings, same starting position (original FEN for custom
  games), **colours swapped**; fresh per-seat reconnect tokens (refresh-resume works in the new
  game); counts as create+join in the usage log. Wire: `rematchOffer` in;
  `rematchOfferSent`/`rematchOffered`/`rematchStarted` out (race-safe per room; offer before game
  end rejected). Unit (`TestGameRoom`: seat/token/session reset, original-FEN restart) + e2e (full
  offer→blink→accept→swapped-colours→play flow, wrong-state rejection).
- [x] **Rematch position guarantee explicitly tested** (training-critical): a rematch restarts
  from the game's ORIGINAL starting position — the standard start for normal games, the custom
  FEN for custom-position games — never from where the previous game ended. Unit (`TestGameRoom`:
  standard + custom FEN, both after moves were played) and e2e (board-reset asserts in the
  standard rematch test; a dedicated custom-FEN rematch test proving the custom position returns
  on both boards with colours swapped and remains playable).
- [x] **Abandonment adjudication (player closes the browser).** Two timers from a mid-game
  disconnect: at `OTB_DISCONNECT_GRACE_MS` (12 s) the opponent is informed ("Your opponent has
  disconnected."); at `OTB_ABANDON_MS` (60 s) the game is adjudicated as abandoned like chess
  servers do — the leaver loses (`ABANDONMENT`, "«Side» left the game. «Side» wins."), unless the
  remaining player has no possible mate (same helpmate adjudication as resignation/flag fall) —
  then it's a draw with the insufficient-material / no-potential-mate reason. A resume defuses
  both timers; an already-decided game is left as it ended. Personalised client messages ("Your
  opponent left the game. You win." / draw variant). Unit (loss, draw, no-op when not running) +
  e2e (browser-close win, lone-king draw, refresh does NOT forfeit) with short test windows via
  Playwright env.
- [x] **No Rematch button after an abandonment** — the opponent is gone, there is nobody to
  accept. The client hides the button for `ABANDONMENT` endings (New Game stays; reset to visible
  for all other endings across rematch chains) and the server refuses hand-crafted offers ("A
  rematch is not available - your opponent left the game."). e2e asserts hidden button + server
  refusal on both abandonment endings (win and draw).
- [x] **Disconnect countdown + Claim victory.** The `opponentDisconnected` notice (12 s) now
  carries `abandonInMs`; the client shows a live countdown ("Your opponent has disconnected. The
  game will be ended in Ns.") plus a **Claim victory** button that applies the abandonment
  adjudication immediately (win — or draw when no mate is possible). Server accepts the claim
  only when the opponent has been gone past the grace and hasn't resumed; a resume sends the new
  `opponentReconnected` (clears countdown + button). Unit-level logic is the existing `abandon()`;
  e2e: countdown + claim → 1-0, rejection while connected, reconnect clears the countdown.
- [x] **Wrong clock press escalation (A-006).** Real-world modeling: the opponent's clock lever
  CAN be pressed. Registers only while the opponent's clock is running (lever up) — otherwise a
  physical no-op, like the real clock. Ladder per player across the game: 1st press → arbiter
  pauses the game + admonishes ("Please do not press your opponent's clock…"), clock restarts
  after `OTB_WRONG_CLOCK_PAUSE_MS` (5 s); 2nd → same + warning; 3rd → **loss**
  (`WRONG_CLOCK_PRESS_GAME_LOST`, personalised messages). Opponent informed passively on 1–2;
  presses during the pause are no-ops (not counted). Replaces the dead legacy
  `handleOpponentClockPressed` ready-handshake. Unit (`TestGameSession`: ladder, no-op cases,
  pause/resume) + e2e (full ladder with PAUSE indicator, dead-lever no-op).
- [x] **Testing policy made permanent**: repo-level `CLAUDE.md` (unit + e2e for every feature,
  same commit; long e2e runtimes explicitly acceptable) + memory updated.
- [x] **Wrong-time draw offers: per-move escalation, second offer "not considered" (A-001
  reworked).** Unlike the claim ladders, a wrong-time offer is only *semi*-illegal (FIDE 9.1.2.1:
  the offer is valid, the timing admonishable), so the count is **per move and never carries
  over**. 1st wrong-time offer of the move → a REAL offer (forwarded, opponent can accept/reject)
  + procedural note; 2nd on the same move → **not considered** (not forwarded), offerer warned
  ("your next draw offer on this move loses the game"), opponent informed passively; 3rd →
  **loss** (`WRONG_TIME_OFFER_GAME_LOST`, personalised messages; also fixes the old mislabeled
  `DRAW_AGREEMENT` loss result that never broadcast `gameEnded`). Docs: A-001 + SPECIFICATION
  wrong-time-offers section rewritten. Unit (full ladder incl. forwarded/not-forwarded state,
  per-move reset) + e2e (ladder on live boards with panel visibility + passive info; reset across
  a move pair). NOTE: the implementation itself was accidentally committed together with the
  promotion fix (`3b3c411`) via `git add -A`; this entry's commit adds the tests + docs. After
  b2xa1 (pawn parked on a1, promotion pending), dragging the a8 rook onto a1 + clock press was
  misread as a "legal release" — via BOTH matcher branches: the promotion match accepted the
  promoted piece from ANY square, and the normal match accepted Ra8xa1 from the turn-start
  legal moves although a1 held the player's own pawn. The resulting commitment's restore target
  was the tampered position itself → Revert did nothing, violation looped forever. Fix in
  `ArbiterEngine.isReleasePartOfLegalMove`: (1) a promotion completion must arrive from OFF the
  board (RESTORE_* events, no source square); (2) a normal release binds only if the destination
  square was untouched earlier in the turn (turn-start comparison — keeps captures/en passant/
  castling commitments intact). The tampering now adjudicates as a plain **illegal move** with a
  working Revert. Unit (regression + side-area completion accepted + untampered release still
  binds) and e2e (the literal reported journey incl. Revert + proper b2xa1=Q continuation, and a
  clean b2xa1=R with the rook from the side area). "Game started" is now a transient
  Lichess-style **banner** centered over the board (also used for "Rematch started"); the arbiter
  message keeps only the two short facts: who joined — the creator sees the opponent's colour
  ("Black joined."), the joiner keeps "You joined the game." — and whose clock runs ("Your clock
  has been started." / "Opponent's clock has been started."), with no "your turn" coaching (a
  running clock says it all; rematch clock lines shortened the same way). All four
  creator/joiner × first-move variants re-pinned exactly in `game-start-message.spec.ts` incl.
  banner show + fade; `expectGameStarted` helper keys on the clock fact. Previously a
  bare "Please complete your move." info. Now: standard illegal-move treatment — penalty time to
  the opponent, counts toward the limit (default 2 → the **second press in a row loses the
  game**), message "Illegal move: the clock was pressed without a move being made (FIDE 7.5.3).
  … Please make a move." (new `no_move` message-key variants — nothing to restore, so no
  restoration flow and the mover's clock keeps running; `IllegalMoveDetail.noMoveMade`). Spec's
  stale clock-press section rewritten. Unit (engine: keys/messages/count/game-lost; session:
  penalty + running clock + loss) + e2e (single press → penalty visible on the clock + game
  continues with a real move; two presses → 0-1 with "2nd illegal move by White"). A claim
  made while not having the move (or after touching a piece, or as a repeat on the same move)
  never reaches the rule machinery — only a claim examined on the merits (threefold/50-move
  check) can be *rejected*. The five passive-info texts now say "The claim was not considered."
  (merit rejections keep "…the claim is not valid. It still counts as a draw offer."). Wording
  note added to SPECIFICATION.md; exact texts pinned in unit tests, e2e asserts the new phrase
  and the absence of "rejected".
- [x] **Moved-opponent-piece escalation (A-007).** Dragging an opponent's piece (never legal) now
  escalates like the other misconducts instead of repeating the same notice forever: 1st → arbiter
  pauses + "Please restore the position" (existing Revert/auto-resume flow), opponent informed
  passively; 2nd → same + "Warning: the next time you move an opponent's piece, you lose the
  game."; 3rd → **loss** (`MOVED_OPPONENT_PIECE_GAME_LOST`, personalised messages). Counted per
  player across the game. Hooked into the mid-play validation via
  `GameSession.escalateMovedOpponentPiece`; generic `pendingOpponentInfo` channel added for
  mid-play passive notices. Unit (full ladder, info texts, clock pause) + e2e (three rounds with
  Revert/resume on live boards, 0-1 result). Invalid-restoration position changes keep the old
  single-message behavior (separate concern).
- [x] **Bug fix: no reconnect into an adjudicated (ended) game.** The leaver's closed tab never
  received `gameEnded`, so their localStorage still held the seat token — the lobby offered
  "Return to game" and `resume` happily resynced into the ENDED game. Now a resume into an ENDED
  game is refused (`resumeFailed`, "This game has already ended."), which also clears the stale
  session and thus the lobby banner. Running-game reconnects unaffected (pinned by the existing
  refresh/lobby-return tests). e2e replays the reported journey: leave → claim victory → leaver
  returns → banner (stale) → refusal → Back to lobby → banner gone.
- [x] **Way back into a running game from the lobby / a new tab.** The seat token lives in
  localStorage (survives new tabs and browser restarts), but only game.html used it — a player
  who lost their game tab and opened localhost:8080 was stranded in the lobby. Now the lobby
  shows a "Game in progress — Return to game" banner when a saved session exists (create/join
  still deliberately clear it), and a bare `/game.html` resumes from the saved session instead
  of bailing with "no game code". Stale sessions resolve via the existing resumeFailed →
  friendly-message → Back-to-lobby flow (which clears them, so no loop). e2e: new-tab reconnect
  flow + no banner without a session (client-only change — no Java to unit-test).
- [x] **Castling obligation wording.** The castling-in-progress message no longer says
  "Released-piece violation"; it now explains the FIDE 4.7.2 obligation directly:
  "Castling has been started..." plus the required rook move. Unit tests pin the exact player
  text and absence of the old label; e2e pins the live arbiter message.
- [x] **Rook-first castling restoration.** A rook-first castling attempt is no longer treated as
  castling or as an illegal king move. If the rook release is legal (`Rh1-f1`), that rook move is
  committed; the later king displacement is restored (`Ke1`), with a specific FIDE 4.4.2 message.
  Unit + e2e pin the message, restore target, Revert board state, and final clock press accepting
  the rook move.
- [x] **Incomplete-castling continuation without Revert.** If legal castling has been started by
  releasing the king on g1/c1/g8/c8 and the board already matches that required intermediate
  position, no Revert button is shown; the mover's clock resumes and the player completes castling
  by moving the rook. Opponent clock-press intervention notices now go to `opponentInfo` below the
  clock unless they are action-relevant.
- [x] **Natural king-in-check illegal-move wording.** The Ashlar validation reason
  "it would leave the own king in check" is normalized at the arbiter boundary to
  "Illegal move because it leaves the own king in check." Unit + e2e pin the player text and the
  absence of the old "would leave" wording.
- [x] **King-then-rook touch explains incomplete castling by touch obligation.** When a player
  touches king, then rook, then moves only the king to the castling square and presses the clock,
  the arbiter now uses the FIDE 4.4.a touch-castling reason instead of the later released-king
  reason. Unit + e2e pin the exact wording, hidden Revert button, passive opponent notice, and
  completion by moving the rook.
- [x] **En-passant removal-first satisfies opponent-piece touch.** Removing the en-passant-captured
  pawn before moving the capturing pawn now satisfies the opponent-piece/specific-capture obligation
  by checking the legal move's captured square, not only its destination square. Unit + focused e2e
  pin the c5xb6 e.p. journey.
- [x] **Revert after a completed move preserves the completed move.** If a player completes a legal
  move on the board and then displaces another piece before pressing the clock, the Revert target is
  the completed-move position, not the turn start. Example pinned: White plays e2-e4, then drags the
  black e7 pawn to e5; Revert keeps the white pawn on e4 and restores only the black pawn to e7.
  Unit + focused e2e replay the reported journey.
- [x] **Claim move validation hides parser internals + grouped board controls.** Claim-with-move
  validation now hides the lenient SAN parser instead of leaking parser internals. Board controls
  are split into three rows: Offer Draw/Resign, a claim row with four icon-led claim buttons, and
  bottom-row utilities (Request Piece, Display PGN, Flip Board). Unit + focused e2e pin the
  message; focused e2e pins the control grouping and text fit.
- [x] **Rematch offer expires when the offering player leaves.** If a player offers a rematch after
  a finished game and then closes the browser before the opponent accepts, the remaining player is
  told the opponent disconnected, the Rematch button becomes unavailable, and a crafted accept/
  rematch message is refused instead of starting a one-player rematch with the absent player's
  clock ticking. Unit coverage pins connected-seat state; focused rematch + abandonment e2e cover
  the reported flow and the existing running-game disconnect behavior.
- [x] **Board controls are icon-only with hover tooltips.** Removed visible text labels from the
  below-board control buttons while keeping accessible names and consistent hover tooltips. The
  resign icon is now a sideways king, matching the physical act of tipping the king over. Focused
  e2e pins the icon-only grouping, tooltip text, and compact button sizing.
- [x] **Display PGN button toggles the PGN panel.** Clicking Display PGN now closes the PGN panel
  when it is already open, while the panel's Close button still works. Focused controls e2e pins
  open, button-toggle close, reopen, and Close-button close.
- [x] **Invalid SAN draw claims are not considered.** If a claim-with-move SAN cannot be resolved
  to a legal move, the arbiter now says the claim was not considered because the presented move is
  not legal, closes the SAN panel, and leaves the player free to make any legal move. Legal SAN
  that fails the draw condition still rejects the claim and forces the specified move. Unit +
  focused claims e2e pin both sides.
- [x] **Claim-with-move cancellation counts as the move's claim.** The claim SAN panel now has
  short **Submit** and **Cancel** buttons. Cancel retracts the started claim, says it was not
  considered, closes the panel, creates no draw offer/penalty/forced move, but consumes the
  player's one claim on that move so the next claim triggers the repeat-claim ladder. Unit +
  focused claims e2e pin the server semantics and live UI flow.
- [x] **Opponent-piece touch beats later released-piece-looking moves.** Fixed the case with White
  rook b3 / black rook b6: removing the capturable opponent rook and then releasing the own rook
  on b5 is now a touch-move violation requiring capture on b6, not a released-piece commitment to
  b5. Revert returns to the turn start and the required capture can then be completed. Unit +
  focused touch-move e2e pin the reported journey.
- [x] **Specific-capture messages preserve touch order.** If the player first touches the opponent
  piece and then their own piece that can capture it, the touch-move message now says opponent
  first, own piece second, while still requiring the specific capture. Unit + focused e2e pin the
  rook/pawn order reported at g6/g7.
- [x] **Opponent-piece obligations can narrow after Revert.** If the player first touches a
  capturable opponent piece, restores after a violation, and then touches an own piece that can
  capture it, the obligation narrows to that specific capture. Other pieces that could also
  capture the opponent piece are then rejected. Unit + focused e2e pin the b3 knight/c2 pawn
  journey.
- [x] **Released-piece recovery message distinguishes the changed piece.** If a legal release
  stays in place but another piece is moved before the clock press, the message now asks to revert
  the position change after that release. If the released piece itself is moved again, the message
  still asks to put that piece back on its release square. Unit + focused e2e pin the a-pawn
  release / h-pawn change journey.
- [x] **Paused-clock clock presses no longer throw internally.** If the player presses the clock
  during the restoration auto-resume gap, the session now resumes the clock before evaluating the
  press; if the game is paused for restoration/ready/other reasons, it returns a clean paused
  message instead of reaching `ClockManager.switchClock()` with no running clock. Unit + focused
  e2e pin the immediate press after released-piece Revert.
- [x] **Incomplete en passant is illegal, not released-piece.** If Black has a pawn on b4 after
  White's c2-c4 and moves b4-c3 without removing the captured pawn on c4, the arbiter now reports
  an illegal incomplete en-passant capture instead of a released-piece violation. Unit + focused
  e2e pin the beginner b4/c4/c3 journey and preserve completed en passant.
- [x] **Not-considered wrong-time draw offers clear stale rejection notices.** After a first
  wrong-time draw offer is rejected, a second offer on the same move is passive information for
  the opponent; it now clears the opponent's old "You rejected the draw offer" arbiter message so
  only the below-clock info remains. Unit + focused wrong-time-offer e2e pin the flag and live
  screen behavior.
- [x] **Rejected claim-converted draw offers name the originating claim.** When an incorrect
  threefold or 50-move claim becomes a draw offer under FIDE 9.5 and the opponent rejects it, the
  claimant now sees that the rejected offer was automatically part of that claim. Unit coverage
  pins all four claim forms; focused claims e2e pins the live threefold-position and 50-move
  with-move rejection messages.
- [x] **Rejected claim messages tell the claimant about the draw offer.** For every considered but
  rejected threefold or 50-move claim, the claimant-side rejection message now also says the claim
  counts as a draw offer for the opponent, who can accept or reject it. Invalid/not-considered
  claims are unchanged. Unit coverage pins all four considered rejection forms; focused claims e2e
  pins the visible player messages.
- [x] **Accepted with-move claim messages name the rule and canonical SAN.** Successful threefold
  and 50-move claims made with a declared move now say which rule was claimed and print the
  canonical SAN for the resolved legal move, not the player's raw input. Unit coverage pins
  lowercase/spurious-check SAN normalization; focused claims e2e pins live threefold and 50-move
  accepted messages.
- [x] **Draw offers stay alive after the offerer presses the clock.** A correct-time draw offer,
  including one automatically created by a rejected threefold/50-move claim, now remains
  acceptable/rejectable after the offering player completes the move with the clock press and
  until the opponent touches a piece. The draw-offer panel now carries the offer text itself so
  the top arbiter message can change to "Your turn" without losing the offer context. Unit +
  focused e2e cover ordinary offers from both colours and claim-converted offers after the
  claimant's clock press.
- [x] **With-move claim rejections do not reveal whether any drawing move exists.** Once the
  submitted SAN resolves to a legal move, rejected threefold and 50-move claims now explain why
  that move failed and bind the player to that move; they no longer say that no move from the
  current position could satisfy the rule. Unit coverage pins the message and must-play state;
  focused claims e2e pins the visible player messages.
- [x] **Draw-offer text stays in the accept/reject panel.** Incoming draw offers, including
  rejected threefold/50-move claims converted to offers, now put their explanatory text only in
  the draw-offer panel from the start. The arbiter/status panel no longer duplicates the offer
  text and remains available for turn-flow messages such as "Your turn." Focused e2e covers
  ordinary offers, claim-converted offers, and wrong-time first offers.
- [x] **Touched-piece draw offers stay visible until the clock press.** When the recipient of a
  draw offer touches or moves a piece, the offer is no longer valid internally, but the
  Accept/Reject panel remains visible. If the recipient then clicks either button, they are told
  the offer is no longer valid because they touched a piece; the panel disappears when they
  complete their move by pressing the clock. Unit coverage pins accept/reject refusal after touch;
  focused e2e covers both colours.
- [x] **Illegal castling attempts explain the touch-move consequence.** When a player physically
  tries to castle on a side where castling is illegal, the message no longer says "Castling
  counts as a king move." It now follows FIDE 4.4.3/4.7.2: move the king if the king has legal
  moves; otherwise make any legal move. The touched rook does not bind after an illegal castling
  attempt. Unit and focused e2e cover lost-right and temporarily illegal castling branches.
- [x] **King release on castling square distinguishes legal start from illegal attempt.** Releasing
  the king on g1/c1/g8/c8 starts an incomplete castling move only when castling on that side is
  legal. If castling is temporarily or permanently illegal, the same release is adjudicated as an
  illegal castling attempt under FIDE 4.7.2, with the king-move/any-move consequence. Unit and
  focused e2e pin the attacked-path case.

## Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- The Playwright e2e suite runs its own server instance on **dedicated ports 18080/18081**, so a
  dev server on 8080/8081 (`start.bat`) can keep running while tests execute — no more killing the
  dev server for test runs. The suite tests the packaged jar: after Java changes run
  `npm run build:server` (or `npm run e2e`, which rebuilds) — a running `start.bat` picks up Java
  changes only on its own restart, static files immediately.
- **`start.bat` serves only APPROVED code**: it snapshots the latest commit into the
  `..\otb-chess-stable` worktree (created/refreshed automatically) and builds/serves from there —
  in-progress, uncommitted edits in the main working tree never reach manual testing, including
  static files. Restart `start.bat` after a new commit to pick it up. `start-dev.bat` keeps the
  old behavior (run the working tree as-is, in-progress code included).
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
