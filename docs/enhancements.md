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
- **The two samples are supplied by the project owner** (`piece_release - wood.wav`, `clock_press.wav`). The piece sample is of a wood board; it replaced a first one of an alabaster board on 2026-11-24. How they are stored is recorded in [`static/sounds/README.md`](../static/sounds/README.md).

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
- `static/sounds/piece.wav` and `static/sounds/clock.wav`: 16-bit PCM, stereo and mono, 0.07 s and 0.15 s, 7 KB and 14 KB. WAV has no decoder delay or padding and every browser decodes it; at this length compression gains nothing.
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
- The rest of the browser suite starts with both sounds off (see `playwright.config.ts`), and the sounds tests observe the browser's own Web Audio - on the default output device, or opened without an output device where that device makes the browser wait for seconds; a stand-in audio context is the last resort (`tests/e2e/README.md`).
- **By ear, before the release**: on the Windows machine and on an iPhone, also after locking and reopening the phone. No automated test hears anything.

### Not part of this

Separate options for the opponent's sounds, a control during the game, a volume setting, sounds for other events, and playing the board by touch (its drag handlers are mouse-based).

---

## E-005 — Free placement of the pieces and J'adoube

**Status**: Draft requirement, dictated by the owner on 2026-11-25 and written down the same day; the measure was replaced by the owner's inner circle later that day, and the open points were decided one by one in the same conversation. Reviewed on 2026-11-25. Implementation is pending. Written against the released `main` at 0.4.0; `jadoube` now holds 0.5.1. The implementation plan is [`e-005-implementation-plan.md`](e-005-implementation-plan.md) (reviewed draft of 2026-11-25). The four rulings it asked about were decided by the owner on 2026-11-25 and are written into this entry, as are its two corrections (three squares, requirement 4). Where the owner's words left a choice, the reading taken is marked *(reading)*. The percentage is the owner's first value and is expected to change.

**Relation to the Laws**: this entry is the path to compliance for [D-004](fide-deviations.md#d-004--jadoube--piece-adjustment-is-not-modeled) (FIDE 4.2.1, 4.2.2). The ruling on unclear placement is arbiter policy and gets an entry of its own in `fide-deviations.md` when it is built.

### Requirement

1. A piece stays where the player lets go of it. It is no longer centred on a square, and it can lie partly on up to three other squares.
2. The arbiter counts unresolved unclear placements at the clock press. A player who sets an opponent's piece down halfway through removing it gets an instruction at release, as described under *Captures and overlapping pieces*.
3. A game option on the entry screen, **on by default**, chosen by the player who creates the game. With the option off the game is exactly as it is today: pieces are centred, there is no J'adoube button, none of the rulings below exists.
4. The feature is a layer in front of the existing game: every placement is turned into "on square X" or "on no square" before the rules look at it. A clear placement is ruled on exactly as today: the rules for legal moves, touch-move, the released piece, restoration, claims and offers do not change for it. New in the rules are only the binding of a piece released in an unclear placement and the rulings below that enforce it.
5. A J'adoube button lets the player on move adjust one piece without it counting as touched.

### The measure: the inner circle

- **Inner circle**: a circle around the centre of the piece. Its area is the **placement exactness**, a percentage of the area of the piece: **20%** to start with.
- **Clear placement**: the inner circle lies completely inside one square. The piece is on that square, and a move to it is made.
- **Unclear placement**: the inner circle reaches over a border. The piece is on no square, and the move is not made.
- **Squares to choose from** after an unclear placement: the squares the inner circle lies on. These are two across a border; near a corner three or four (three when the circle crosses both borders without reaching the fourth square); at the edge of the board fewer, down to one.
- **The area of the piece** is the piece image, the same for every piece. The percentage is one number and can be tuned.
- **At the edge of the board**: a piece released with its centre outside the board is taken off the board, as today. If the centre is on the board and the inner circle reaches over the edge, the placement is unclear, with only the squares on the board to choose from.

There is nothing in between: a placement is clear or unclear. *(Reading: the inner circle replaces the lines at 80% and 60% and with them the "sloppy" placement that the arbiter only reminded of.)*

What 20% means on today's board, with the area of the piece taken as its image (56 of the 64 pixels of a square in each direction): the inner circle has a diameter of 28 pixels, a little less than half a square. The placement is clear as long as the centre of the piece is at least 14 pixels away from every border, that is, shifted by at most 18 pixels (0.28 of a square) from the centre of the square in each direction. At that limit a quarter of the piece image hangs over the border. A smaller percentage is more lenient: 10% allows 22 pixels, 30% allows 15.

The same rule in other words: only the centre of the piece and its distance to the nearest border count. Both boards and the server can compute it exactly, in parts of a square, whatever the size of the screen.

### During the turn

- The opponent sees every piece exactly where it lies, on the turned board mirrored accordingly.
- A release in a clear placement is a release on that square, with everything that follows from it today (released-piece rule, capture, automatic end of the game).
- A release in an unclear placement is no move yet: nothing is captured, nothing ends. The piece counts as touched, and it counts as **released on the squares its inner circle lies on** (decided by the owner on 2026-11-25): from then on, already before the clock press, it may only be placed on one of these squares. Example: White lets go of the rook from a1 with the inner circle across the border of d1 and e1; only Rd1 and Re1 remain, Rb1 does not.
  - The player is bound to the **legal** moves that end with the piece on one of these squares. If only some of the squares are legal, the illegal ones cannot be chosen, and the arbiter does not name them (decided by the owner on 2026-11-25).
  - If none of these squares gives a legal move, the release binds to nothing (decided by the owner on 2026-11-25): the player may take the piece to another square and make a legal move with it, and is not forced into an illegal move. Touch-move remains. This is what a release on an illegal square means today. Example: White lets go of the bishop from c1 across the border of c4 and d4 and it can reach neither; any legal bishop move remains.
  - **The square the piece came from is among these squares**: the release binds to nothing (decided by the owner on 2026-11-25). The piece is touched, as after a click today, and it lies unclear. The arbiter cannot tell a piece put back carelessly from a move. Example: White picks up the pawn on e2 and lets go of it across the border of e2 and e3; any legal move of the pawn remains.
  - **A second unclear release on other squares**: the first binding stands, as the first release does today (FIDE 4.7) (decided by the owner on 2026-11-25). Example: the rook from a1 is let go across d1 and e1, picked up again and let go across f1 and g1; it remains bound to d1 and e1.
  - **An opponent's piece** (decided by the owner on 2026-11-25): if its inner circle still lies on its own square, it is touched and lies unclear; the player has to put it back clearly on its square or capture it. If its inner circle no longer lies on its own square, it was moved: the arbiter rules on it at once as today ([A-007](fide-deviations.md#a-007--moving-an-opponents-piece-escalation)), and the restored position is centred.
- **No correction without J'adoube** (the owner's correction of 2026-11-25; before, this was allowed): once a piece is released in a clear placement, the player may not shift it on its square without J'adoube, however badly it stands. Example: White plays e2-e4, the pawn lies clearly but not nicely on e4, and White picks it up and sets it down again on e4. See *Adjusting without J'adoube* below. Setting it down on another square is what it is today (released-piece rule). A piece released in an unclear placement is not meant: it has to be picked up again to finish the move.
- Promotion, castling and en passant have no rule of their own: every piece that is set down is measured. To be confirmed in the implementation plan.
- Positions the arbiter sets up are centred: the starting position, a reverted or restored position, a rematch.

### At the clock press

In this order:

1. **A piece in an unclear placement**: the move is not made. The arbiter **pauses the game** to make the call (decided by the owner on 2026-11-25): he names the squares the piece is bound to and asks the player to decide and to place the piece properly: *"It is not clear on which square your bishop is. Place it on c4 or on d4, and place your pieces more carefully."*
   - The arbiter names only the squares the player is bound to, which are the ones that give a legal move (decided by the owner on 2026-11-25). If the piece is bound to nothing, he names no square: *"It is not clear on which square your bishop is. Place your pieces more carefully."* The player may then make any legal move with the piece.
   - If the piece was bound by an earlier release, the call names the bound squares, not the squares the piece lies on now: *"It is not clear on which square your rook is. It was released on d1 and e1. Place it on d1 or on e1."*
   - For an opponent's piece the call says "your opponent's bishop", and it counts on the same ladder.
   - The arbiter then starts the clock again, and the player corrects the placement **while their own clock is running** *(reading: a short pause for the call and a restart by the arbiter, as after a wrong clock press, [A-006](fide-deviations.md#a-006--wrong-clock-press-pressing-the-opponents-clock-escalation); the board cannot be used during the pause)*.
   - The player decides by placing the piece clearly on one of the named squares (or, if none was named, on any square) and pressing the clock again; the move is then ruled on as any other (accepted, illegal move, touch-move, …).
   - **The piece is still unclear at the next clock press**: the same intervention again, and it counts again (decided by the owner on 2026-11-25: every clock press with a piece in an unclear placement is a step on the ladder, as every clock press with an illegal move counts today).
   - This counts on a ladder of its own, per player for the whole game (confirmed by the owner for the inner circle on 2026-11-25): admonition, then the warning that the next time loses the game, then **loss of the game**. Entry-screen option for the number: 3 by default, 1 to 10, or unlimited (same form as *Number of illegal moves causing game loss*).
2. **A piece that is bound by an unclear release now stands clearly on a square it is not bound to**: the arbiter intervenes again in the same way and names the squares the piece may go to: *"Your rook was released on d1 and e1. Place it on d1 or on e1."* The piece stays where the player put it; the player takes it to one of the named squares on their running clock. The arbiter intervenes at every clock press until this is resolved (decided by the owner on 2026-11-25). It applies whether or not the arbiter had already named the squares. It does **not** count on the ladder (decided by the owner on 2026-11-25), like a released-piece violation today.
3. **Every piece is clear and none is bound elsewhere**: the move is ruled on as today.

### Captures and overlapping pieces

- A piece released in a clear placement on a square where an opponent's piece stands captures it: the opponent's piece leaves the board as today.
- A piece released in an unclear placement over an occupied square removes nothing. The two pieces overlap, the later one drawn on top, and the arbiter steps in at the clock press (unclear placement). If the occupied square is one of the squares to choose from, choosing it is the capture.
- Two pieces that are both clearly on neighbouring squares can still overlap on the screen, at 20% by up to half a piece image. That is allowed; J'adoube is there to straighten them.
- **Removing the opponent's piece first.** White lifts Black's pawn on d5 to play exd5. Carrying it halfway towards e5 makes no call. Letting go on the line leaves it on the board. The arbiter immediately tells White to take it off to continue the capture or put it back on d5. Black sees where the pawn lies. A return preserves the touch. A release with its centre off the board completes the removal. A release elsewhere on the board with the inner circle clear of d5 gets the existing immediate opponent-piece ruling.
- *Proposed for the first version:* the immediate instruction leaves White's clock running and costs no penalty. A clock press with the victim still unclear gets the usual counted call. If White puts the capturing pawn on d5 first, both pieces remain visible until the victim is removed. See the capture-first example in [the implementation plan](e-005-implementation-plan.md#capture-first-clarification-from-the-review).

### Automatic end of the game

A move that ends the game without a clock press today (checkmate, stalemate, …) does so when its piece is released in a clear placement. An unclear placement is no move, so nothing ends.

### J'adoube

- A **J'adoube** button on the board, only in a game with free placement. It can always be pressed.
- **In the own time**: the press arms it and the button is disabled. Both players are told (*"White: J'adoube."*). The next piece the player presses the mouse button on is the adjusted piece, from the press until the release. It may be an own piece or a piece of the opponent (decided by the owner on 2026-11-25; FIDE 4.2.1). It does not count as touched. With the release the button is enabled again; every further adjustment needs a new J'adoube. One J'adoube, one piece.
  - The piece must end in a clear placement on the square it was on. If it is released on another square or in an unclear placement, the adjustment is **undone**: the piece lies again where it lay before, without penalty.
  - A move cannot be made this way: a piece carried to another square under J'adoube is put back and is neither moved nor touched.
- **When in the own time** (decided by the owner on 2026-11-25): at any moment while no piece is held, also after a piece was touched or moved in this turn. Example: White plays Nf3 and, before pressing the clock, straightens the bishop on c1 or a piece of the opponent. *(Proposed with it: a J'adoube that was pressed and not used ends with the clock press, and the button does nothing while the game is paused for a ruling.)*
- **In the opponent's time**: nothing is armed. The arbiter says *"You can only adjust pieces in your own time."*, on a three-step ladder per player for the whole game: admonition, warning, loss of the game.
- **No limit** on J'adoube in the own time in the first version.
- **Without J'adoube, before the move is made** nothing changes: a piece that is clicked is touched and must be moved or captured, however badly it stands.
- **Adjusting without J'adoube, after the move is made** (decided by the owner on 2026-11-25): the player shifts the piece they have moved on its square. The arbiter intervenes at once, not at the clock press: *"You cannot adjust pieces without saying J'adoube."* The game is paused for the call and then resumes; the move stands, the piece is on its square, and the player is free to press the clock. On a three-step ladder per player for the whole game: admonition, warning, loss of the game.
  - *(Reading)* The piece stays where the player shifted it to; nothing is put back. If it lies unclear after the shift, that is the unclear placement at the clock press.
  - The same holds for any other piece that the player shifts on its square after their move is made, an own one or one of the opponent (decided by the owner on 2026-11-25): same ruling, same ladder. Today such a late touch has no consequence, because the move is already made.
  - *(Reading)* A click that does not shift the piece is no adjustment.
- The two J'adoube ladders (J'adoube in the opponent's time, adjusting without J'adoube) are separate, and each is fixed at three like the other ladders; the entry-screen option is only for unclear placements.

### Entry screen

- *Free placement of the pieces* (checkbox, on by default; game option).
- *Number of unclear placements causing game loss* (1 to 10, default 3, Unlimited; game option, only with free placement).

### Open points

No ruling is open. The four found while writing the implementation plan (whether the arbiter may name a square that gives no legal move, a piece let go across the border of its own square, a second unclear release on other squares, an unclear placement of an opponent's piece) were decided by the owner on 2026-11-25 by accepting the proposals of the plan as they stood. The owner decided the earlier open points on the same day; the last five (area of the piece, edge of the board, the two J'adoube ladders, no limit on J'adoube, promotion and the like) also by accepting the proposals as they stood. The passages marked *(reading)* or *(proposed)* here, and the details marked *(proposal)* in section 2 of the plan, are the writer's and were not confirmed one by one.

### Not part of this

Playing by touch on a phone (the board is mouse-only, and a finger is far less precise), and any change to the rulings that exist today.
