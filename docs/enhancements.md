# Enhancements (non-deviation)

This document tracks improvements to the otb-chess that are *not* fixes for FIDE deviations. Deviation-related improvements live alongside their parent entry in [`fide-deviations.md`](fide-deviations.md) under "Path to compliance" sections.

Companion: [`SPECIFICATION.md`](../SPECIFICATION.md) for current behaviour, [`fide-deviations.md`](fide-deviations.md) for FIDE relationship, [`design-principles.md`](design-principles.md) for cross-cutting design rules, [`future-ideas.md`](future-ideas.md) for speculative directions.

---

## E-001 — Configurable auto-resume timeout after a paused claim resolution

**Background**: This enhancement only becomes relevant if [E-003](#e-003) (interactive wrong-side claim resolution) is implemented. Today, wrong-side claims are simply rejected with a private error to the claimant — no clock pause, no resume step, so no resume timeout to configure.

**Proposed**: A start-screen setting "auto-resume after paused claim: N seconds" (e.g. default 5s). When set, after a paused claim resolution the game resumes automatically after the timeout instead of requiring dual consent.

**Rationale**: If the system ever moves to interactive wrong-side claim resolution (E-003), the dual-consent step might be unnecessary friction in casual play. Make it configurable.

**Status**: Conditional on E-003 — without it, no resume to time out.

---

## E-002 — Cap on draw offers at the recommended time

**Today**: [A-001](fide-deviations.md#a-001) caps wrong-time draw offers at three (third = loss). Offers made at the recommended time (after own move, before clock-press) face no per-game limit — only whatever annoyance threshold the opponent personally tolerates.

**Question to think about**: Should the system also cap correctly-timed offers? Options:

- A per-game cap ("you may offer N times").
- A cooldown ("at most once every X moves").
- A cap that engages only after the opponent has rejected once or twice.
- No cap at all — leave it to opponent acceptance/rejection alone.

**Hard part**: Distinguishing "annoying" from "legitimate change of mind after position changes meaningfully" has no clean signal from the position alone.

**Status**: Think-about-it, not decide-now. Deferred until usage patterns motivate a specific shape.

---

## E-003 — Interactive wrong-side claim resolution

**Today**: When a player who does *not* have the move clicks a claim button, the system returns a private rejection to the claimant ("You cannot claim a draw when not having the move.") and — per [A-003](fide-deviations.md#a-003--wrong-time-draw-claim-escalation) — escalates on repetition: warning on the second wrong-time claim, loss of the game on the third. The buttons stay enabled so the fault can be made (and learned from). The opponent is notified only when the third claim ends the game. The clock is not paused. The first-claim handling is FIDE-aligned: in OTB chess an arbiter would tell the offender "it's not your move" and play would continue without interruption.

**Idea on the table**: A more interactive resolution where wrong-side claims are visible to both players:

- Pause the clock when a wrong-side claim arrives.
- Show the offender: "You cannot claim a draw when not having the move."
- Show the having-the-move player: "[opponent] tried to claim a draw, but it isn't their move."
- Resume by both-player consent (or after [E-001](#e-001)'s timeout if implemented).

**Why might this be wanted**: It surfaces the social-fabric layer that the [`future-ideas.md`](future-ideas.md) distraction-and-complaint mechanics also speak to — making the system's events feel less mechanical. A wrong-side claim is, in OTB terms, a small drama; rendering it as a beat in the game (rather than swallowing it silently) might fit "crazy chessboard" better than "minimal-intervention chessboard."

**Why it's not the default**: It's MORE intrusive than FIDE rather than less. It also opens questions about timing manipulation (could the wrong-side player exploit pauses to disrupt the opponent's thinking?) that the simple-rejection model avoids by construction.

**Status**: Speculative. If pursued, it pairs with E-001 as a configurability layer.

---

## E-004 — Sounds for a released piece and for the clock press

**Status**: Implemented on `add-sounds` on 2026-11-24, not yet released. Requirement recorded on 2026-11-23; the proposal was reviewed by Codex on 2026-11-23 and changed as listed under *Decisions and review*. Current behaviour is specified in [`SPECIFICATION.md`](../SPECIFICATION.md#sounds); this entry keeps the requirement and why it was built this way.

### Requirement

1. **Piece sound.** A sound is played when a piece is released on a square, as on Lichess.
2. **Clock sound.** A sound is played when the clock is pressed.
3. **Two options on the entry screen**, each on or off, both on by default:
   - sound for a moved piece;
   - sound for the clock press.

### Decisions and review

Decided by the project owner on 2026-11-23:

- **Both players hear both.** A player hears their own piece and clock, and the opponent's, as two people at one board would.
- **The two samples are supplied by the project owner** (`piece_release.wav`, `clock_press.wav`). How they are stored is recorded in [`static/sounds/README.md`](../static/sounds/README.md).

The other sources that were considered: recordings under CC0 from a public sound library, sounds synthesised in code, and the Lichess sounds. On the last, Lichess is open source, but that does not cover all of its sounds. Its licence file (`COPYING.md` in `lichess-org/lila`, read on 2026-11-23) says:

- The default sounds, the set `standard` that a visitor hears on Lichess, are not in its list of free files; the file names "the other sounds" under "Exceptions (non-free)". They cannot be used here.
- The sets `sfx`, `piano`, `nes` and `futuristic` by Enigmahack are AGPLv3+. A file from them could be shipped with this GPL-3.0 project: it stays under the AGPL, the author is credited, and the source of the app must be offered to the users of the site, which the public repository does.
- Lichess has no sound for a clock press.

No Lichess file is used.

Taken over from the review:

- **The clock sound waits for the server, also for the player's own press.** The proposal played the own press at once. But a request the client sends does not show that a lever went down: the client also sends a press of the opponent's lever that is already down, and of the own lever during a pause, while a press during the short delay before the game resumes after a restoration does count although the clock shows a pause. Only the server knows. It costs one round trip; in exchange both boards always agree on what was heard. The piece sound stays immediate.
- **A dedicated message, `clockPressHeard`, sent to both players.** The existing messages cannot carry the sound: a checkmate arrives as `opponentMoved` without any press, one press can produce a resumed game, a ruling and a clock update, the restart after a wrong clock press comes from a timer, and a reconnect resends the restored position.
- **The audio output is woken on every gesture, for the life of the page**, not once on the first `pointerdown`: the gesture a browser accepts from a finger or pen is the end of the contact, and a phone takes the output away again when the page goes to the background.
- **The tests observe the audio output**, not a list kept by the sounds module, and go through the entry screen.
- **Storage that cannot be used** must not stop the creator's board from being set up (the unguarded `lastGameId` write).
- Confirmed as proposed: the table of board events including silence for a touched piece, short mono PCM WAV samples, the options belonging to the player and not to the game, one sound per kind, no control during the game, no volume setting, no separate options for the opponent's sounds.

### Reading of the requirement

- **The options belong to the player, not to the game.** Each player sets them in their own browser. They are saved in that browser and are not sent to the server, so one player can play with sound and the other without. They apply whether the player creates a game, joins one, or returns to a running one.
- **One sound per kind.** A capture sounds like any other released piece. No sounds for anything else (game start, game end, draw offer, low time, arbiter messages).
- **No control during the game and no volume setting.** The options are on the entry screen only; the volume is the device's.

### When each sound plays

**Piece sound** — when a held piece is set down on a square of the board:

| Board event | Sound |
|---|---|
| `DRAG_MOVE`, `DRAG_CAPTURE` (piece released on another square) | yes |
| `RESTORE_TO_EMPTY`, `RESTORE_TO_OCCUPIED` (piece brought back from the side onto the board) | yes |
| `CLICK` (piece touched or put back on its own square) | no |
| `REMOVE` (piece taken off the board), `DRAG_START`, `DRAG_HOVER` | no |
| Position set by the server (`boardUpdate`, Revert, restored position, reconnect, rematch) | no — nobody released a piece |

The player's own board plays it at the release, without waiting for the server. The opponent's board plays it when it applies the same event from `opponentBoardEvent`. The server does not forward every event (not those of a player who is not to move, and none during the delay before the game resumes), and the board applies an event only while the opponent is to move; the sound follows the board. A piece put back by hand during a restoration is forwarded and is heard once on each board. Castling makes two sounds, king and rook.

A touched piece stays silent although a piece lifted and put back makes a sound on a real board: the app cannot tell the two apart, and the rules treat both as a touch.

**Clock sound** — when a lever that is up is pressed down. The server decides, and both players hear it from the same message:

- The player's own lever, when the arbiter rules on the move: an accepted move, an illegal move, a press without a move, an incomplete move. Also the press that ends the game.
- The opponent's lever while the opponent's clock is running (the wrong clock press), all three times.
- Silent: a lever that is already down (the own one when it is not the player's turn, the opponent's when their clock is not running), any press while the game is paused (the position is to be restored, the players are asked whether they are ready, the pause after a wrong clock press), after the game, or with the time expired. A press during the short delay before the game resumes after a restoration is the exception among the pauses: it restarts the clock and is ruled on, so it is heard.
- A player who is not connected at that moment does not hear the press later.

### Implementation

**Playback: Web Audio API, two short recorded samples.**

- `static/js/sounds.js`: `OtbSoundSettings` (the two options) and `GameSounds`. `GameSounds` creates one `AudioContext`, fetches and decodes the samples the player has switched on into `AudioBuffer`s when the game page loads, and plays one by starting a new `AudioBufferSourceNode`. This starts within a few milliseconds and lets sounds overlap; an `<audio>` element is slower to start and cuts itself off when retriggered. With both options off, no audio context is created and nothing is fetched.
- `static/sounds/piece.wav` and `static/sounds/clock.wav`: mono 16-bit PCM, 0.07 s and 0.15 s, 1.4 KB and 14 KB. WAV has no decoder delay or padding and every browser decodes it; at this length compression gains nothing.
- `StaticFileHandler.guessContentType`: `.wav` → `audio/wav`.
- **Browser autoplay rule.** A page may not start audio before the user has acted on it; until then the `AudioContext` is suspended. The module calls `resume()` inside the handler of every gesture a browser accepts (`pointerdown`, `pointerup`, `mousedown`, `touchend`, `keydown`, `click`), and again when the page returns to the foreground. A sound requested before the first gesture, or while a sample is still being decoded, is dropped, not queued. A sound requested while the output is waking up is played if the output is there within 250 ms and dropped otherwise. So the opponent's first move can be silent for a player who has not yet touched the game page, and after a phone was locked the first sound may be missing until the player touches the page. This is accepted and stated in the manual.
- A failure to create the audio output, to load a sample or to play a sound never affects the game: errors are caught and the game goes on silently.

**Settings.**

- Entry screen (`static/index.html`): a section "Sounds" of its own with two checkboxes, "Sound for a moved piece" and "Sound for the clock press", below "Join a Game", so it visibly applies to creating, joining and returning alike.
- Saved in `localStorage` as `otbSoundPiece` and `otbSoundClock` (`on` / `off`) on every change; a missing value means on. If `localStorage` cannot be used both are on.
- `game.js` reads them once when the page loads.

**Client hooks.**

- `board.js`: `ChessBoard.onPiecePlaced`, called at the player's own four placing events and in `applyOpponentEvent` when one of the same four is applied. `game.js` plays `piece` there.
- `game.js`: the handler for `clockPressHeard` plays `clock`. Nothing else does; `onClockButtonPressed` only sends the press.

**Server.** One new message, `clockPressHeard`, without content, sent to both players:

- from the clock-press handler when `GameSession.consumeClockPressRegistered()` says the press was ruled on, before everything else the press causes;
- from the wrong-clock-press handler when the press registers, before the result of a press that loses the game.

`GameSession` records whether the last press of the own lever was ruled on, because the answer for a press that is not ruled on has the same type (`INCOMPLETE_MOVE`) as the answer for a genuinely incomplete move. The message carries no rule meaning.

### Tests

- **Java**: `TestClockPressRegistered` (session: which presses register), `TestClockPressHeardServer` (exact counts, recipients and order for an accepted move, an illegal move, a press without a move, a press that loses the game, each wrong clock press including the losing third; none for a lever that is down, the wrong turn, a finished game, the restoration pause, the Ready wait and the pause after a wrong clock press; one for the press during the delay before the game resumes and none for the delayed resume; none replayed to a returning player; none for a checkmate without a press; one per board for the first press of a rematch), `TestStaticFileHandler` (`.wav` is served as `audio/wav`; the samples are short mono PCM).
- **Playwright** (`tests/e2e/sounds.spec.ts`): two first-time visitors in two isolated browser contexts who use the real Create, Join and Return controls. An observer on the audio output counts what each page really started: a decoded sample, connected to the output, started while the output was running. The tests assert exact counts on both boards for a touch, a move, a capture, a piece taken off, both placements from the side, an illegal move with Revert and the resumed game, a piece put back by hand, a wrong clock press with its pause and restart, levers that are down, a checkmate without a press, a rematch and a reload; each option alone, kept over a reload and honoured on joining and returning; a listener who has not touched the page; a sample that fails to load; a browser that refuses storage; the content type of the samples.
- The rest of the browser suite starts with both sounds off (see `playwright.config.ts`), and the sounds tests use a stand-in audio context where the browser needs seconds to open the audio output (`tests/e2e/README.md`).
- **By ear, before the release**: on the Windows machine and on an iPhone, also after locking and reopening the phone. No automated test hears anything.

### Not part of this

Separate options for the opponent's sounds, a control during the game, a volume setting, sounds for other events, and playing the board by touch (its drag handlers are mouse-based).
