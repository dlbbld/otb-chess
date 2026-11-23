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

**Status**: Requirement recorded on 2026-11-23, implementation proposed below, **not implemented**. Awaiting review of the proposal. Planned as a release of its own, developed on `add-sounds`.

### Requirement

1. **Piece sound.** A sound is played when a piece is released on a square, as on Lichess.
2. **Clock sound.** A sound is played when the clock is pressed.
3. **Two options on the entry screen**, each on or off, both on by default:
   - sound for a moved piece;
   - sound for the clock press.

### Reading of the requirement (to confirm)

The requirement leaves these points open. The proposal assumes:

- **Both players hear both.** A player hears their own piece and clock, and the opponent's, as two people at one board would. Lichess also plays the opponent's move.
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

The player's own board plays it at the release, without waiting for the server. The opponent's board plays it when it applies the same event from `opponentBoardEvent`. That message already arrives for every event and needs no change; the board applies it only while the opponent is to move, and the sound follows the board.

**Clock sound** — when a lever that is up is pressed down:

- The player's own lever while it is their turn: played at the press, without waiting for the server. The press counts as a press whatever the arbiter then rules (accepted, illegal, incomplete).
- A press of the opponent's lever that registers (the wrong clock press): played for both players.
- A press of a lever that is already down is a physical no-op and stays silent, as it is today.
- The opponent hears a press through a new server message (below), not by inference from the dozen messages that can follow a press.

### Proposed implementation

**Playback: Web Audio API, two short recorded samples.**

- New `static/js/sounds.js` with one small class. It creates one `AudioContext`, fetches and decodes the two samples once into `AudioBuffer`s when the game page loads, and plays one by starting a new `AudioBufferSourceNode`. This starts within a few milliseconds and lets sounds overlap; an `<audio>` element is slower to start and cuts itself off when retriggered.
- New `static/sounds/piece.wav` and `static/sounds/clock.wav`: mono, 44.1 kHz, 16 bit, each under 0.25 s and so under about 20 KB. WAV has no decoder delay or padding and every browser decodes it; at this length compression gains nothing.
- `StaticFileHandler.guessContentType` gets `.wav` → `audio/wav`. Today it would serve the files as `application/octet-stream`.
- **Browser autoplay rule.** A page may not start audio before the user has interacted with it; until then the `AudioContext` is suspended. The module resumes it on the first `pointerdown` or `keydown` on the game page. A sound requested before that is dropped, not queued. In practice a player's own first sound always follows their own pointer press. The opponent's very first move can be silent for a player who has not yet touched the page (browsers differ; Safari is the strictest). This is accepted and stated in the manual.
- A failure to load or play a sound never affects the game: errors are caught and the game goes on silently.

**Settings.**

- Entry screen (`static/index.html`): a block "Sounds" with two checkboxes, "Sound for a moved piece" and "Sound for the clock press", outside the "Create a Game" section so it visibly applies to joining and returning as well.
- Saved in `localStorage` as `otbSoundPiece` and `otbSoundClock` (`on` / `off`) on every change; a missing value means on. If `localStorage` is unavailable both are on.
- `game.js` reads them once when the page loads.

**Client hooks.**

- `board.js`: after `emitEvent` for the four placing events, and in `applyOpponentEvent` for the same four, call `sounds.play('piece')`.
- `game.js` `onClockButtonPressed`: call `sounds.play('clock')` where the press is sent to the server (own lever on the own turn; the opponent's lever while the opponent's clock is running).
- `game.js`: handler for the new `clockPressHeard` message plays `clock`.

**Server.** One new message, `clockPressHeard`, without content, sent to the *other* player:

- from the clock-press handler for every press it accepts for evaluation, before the ruling is sent;
- from the wrong-clock-press handler when the press registers.

Nothing else on the server changes, and the message carries no rule meaning.

### Where the samples come from (decision needed)

Lichess is open source, but that does not cover all of its sounds. Its licence file (`COPYING.md` in `lichess-org/lila`, read on 2026-11-23) says:

- The default sounds, the set `standard` that a visitor hears on Lichess, are not in its list of free files; the file names "the other sounds" under "Exceptions (non-free)". They cannot be used here.
- The sets `sfx`, `piano`, `nes` and `futuristic` by Enigmahack are AGPLv3+. A file from them could be shipped with this GPL-3.0 project: it stays under the AGPL, the author is credited, and the source of the app must be offered to the users of the site, which the public repository does.
- Lichess has no sound for a clock press, so that one needs another source in any case.

1. **Own recordings (preferred for this app).** A piece set down on a wooden board and a press of a real chess clock, recorded with a phone, cut and normalised. Authentic, and the rights are clear.
2. **CC0 recordings** from a public sound library, with source and licence recorded in `static/sounds/README.md`.
3. **The piece sound from the Lichess set `sfx`** (AGPLv3+, see above), with the clock sound from option 1 or 2.
4. **Synthesised in code** (a short filtered noise burst for the piece, a double click for the clock). No files and no licence question, but it sounds artificial. Usable as a stand-in until recordings exist; the module does not care where its two buffers come from.

### Tests

- **Java**: `StaticFileHandler` serves `.wav` as `audio/wav`; server tests that `clockPressHeard` goes to the opponent and only the opponent for an accepted press, for a press ruled illegal, and for a registered wrong clock press, and is not sent for a press that does not register.
- **Playwright** (`tests/e2e/sounds.spec.ts`): the module keeps a list of the sounds it started (`window.otbSounds.played`, with whether the audio context was running), which the tests read on both boards:
  - both options are on by default on the entry screen;
  - White releases a piece: `piece` on both boards; White presses the clock: `clock` on both boards;
  - a capture and a piece brought back from the side play `piece`; a piece taken off the board, a touched piece, Revert and a restored position play nothing;
  - with "moved piece" off for one player, that board plays no `piece` and still plays `clock`, and the other board plays both; the same for "clock press" off;
  - the options survive a reload of the entry screen and are honoured when joining a game;
  - `/sounds/piece.wav` and `/sounds/clock.wav` are served with an audio content type.
- **By hand, before the merge**: listening on the Windows machine and on a phone, since no automated test hears anything.

### Documentation with the change

`SPECIFICATION.md` (start screen, new section "Sounds"), `MANUAL.md` (the two options, the first-sound limitation), changelog at the release.

### Points for the review

- Is a dedicated `clockPressHeard` message right, or should the opponent's clock sound be derived from the existing messages?
- Should the own clock sound wait for the server instead of playing at once? At once feels like a real clock; waiting can never play for a press the server ignores.
- Is silence for `CLICK` right? A piece lifted and put back on its own square makes a sound on a real board, but the app cannot tell that from a mere touch.
- Should the opponent's sounds be separately switchable, or is "both players' sounds follow my two options" enough?
