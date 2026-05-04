# Dumb Chessboard — Specification

## Context

The chess library (clean-chess) has two validation pipelines: SAN (for PGN import) and MoveSpecification (for programmatic moves). The "dumb chessboard" is a third pipeline — an educational electronic chessboard that simulates physical board play. The player must execute all actions manually. The board acts as a silent observer during play and evaluates at clock press.

---

## Core Principles

1. **The board never leaks information through its behavior.** The board must not reveal whether a move is legal or illegal through restrictions or behavior differences.
2. **The arbiter is reactive, not proactive.** The arbiter only states what the player did wrong, never proactively instructs what to do right.
3. **The arbiter escalates one step at a time.** Minimum information at each intervention. Rules are only revealed when violated.
4. **The player does everything.** Like a physical board — no automatic piece removal, no automatic rook moves for castling, no inference.
5. **The player learns by making mistakes.** The board allows errors so it can educate afterwards.
6. **Complete freedom with own pieces during play.** The player can move their own pieces freely — including moving them back to the origin square. No freezing, no restrictions. All evaluation happens at clock press.
7. **One mid-play intervention exists for opponent pieces: dragging them across the board.** The player may **remove** opponent pieces from the board (capture-by-removal — see below) and may **click** them (touch-move tracking only). Dragging an opponent piece from one square to another is never part of a legal sequence and triggers an immediate arbiter intervention.
8. **Respect the player's sphere of control.** The player is always in control of their own pieces. The board and arbiter never intrude into this sphere. Even when a specific move must be executed (e.g. after a rejected draw claim), the player physically makes the move themselves.

---

## Game Setup

### Start screen

The player configures the game on a single screen before clicking **Create**:

1. **Side selection** — White or Black.
2. **Time control:**
   - **Presets:** 3+0, 3+2, 5+0, 5+3, 15+0, 15+10, 30+0 (default: **30+0**).
   - **Custom:** manual standard time and increment.
3. **Maximum illegal moves before game loss** — dropdown with values **1, 2, 3, …, 10, Unlimited**. Default **2** (FIDE rule).
   - "Unlimited" disables the game-loss escalation; illegal moves still incur the per-move penalty time.
4. **Restoration mode** — radio choice for what happens after a position has to be restored following an arbiter intervention:
   - **Auto-resume after restoration (default).** When the position is restored to the start of the turn (either via the player's manual restoration or the "Do this for me" button), the clock resumes immediately on the side that has the move.
   - **Manual continue (ready-handshake).** After restoration, both players must click **Ready to continue** before the clock restarts. Used when the players want to confirm they have agreed on the position.

5. **Starting position FEN** (optional). A text field on the start screen accepts an
   arbitrary FEN. Behaviour:
   - **Empty:** standard initial position; the player's chosen side is honoured.
   - **Valid FEN:** the game starts from that position. The side to move in the FEN
     plays first, so the **creator is given that side** regardless of the colour
     they originally selected — they will be the first to move. The second player
     to join gets the other colour.
   - **Invalid FEN:** the server rejects the create request and returns the
     chess-library validation reason via the standard error channel
     ("Invalid FEN: …"). No game is created. The player can correct the FEN and
     try again.

   Validation is performed by the chess library (`new Board(fenString)` →
   `FenParserAdvanced.parseFenAdvanced` → `FenAdvancedValidationException`); the
   exception's message is forwarded verbatim so the player sees the specific
   reason (illegal piece placement, malformed castling rights, etc.).

### Joining

6. The creator receives an **8-character game code** with a **Copy code** button.
7. The second player opens the join page; the **game code field auto-fills** from the URL or from the creator's clipboard share, so the second player only confirms.
8. Both browsers connect via WebSocket — the game begins.

---

## Layout (final)

```
+------------------------------+--------+
|                              | info   |
|                              | panel  |
|           BOARD              |--------|
|         (8 × 8 +             | clock  |
|        rank/file labels)     |        |
|                              |--------|
|                              | bottom |
|                              | spacer |
+------------------------------+--------+
        action buttons row
```

- **Board on the left** with rank/file labels along the inside edge.
- **Right column** is a CSS grid `1fr auto 1fr`: top spacer (info/messages), clock (middle), bottom spacer. The clock sits at the **vertical middle of the board** by construction.
- **Clock side depends on view (FIDE positioning):**
  - **White's view:** clock on the **right** of the board (= White's right hand).
  - **Black's view:** clock on the **left** of the board (= Black's right hand).
  - Achieved by toggling the class `clock-on-left-view` on `.game-layout` when the board is flipped.
- **Game messages (info panel)** sit in the upper spacer of the right column, **directly above the clock**, hugging the clock's top edge.
- **Action buttons** (Offer Draw, Resign, Request Piece, Claim Threefold, Claim 50-Move, Display PGN, Flip Board) live in a single row beneath the board.

The board must always remain visible. There is no popup overlay for the game result — the result is shown inline (e.g. `1-0`, `0-1`, `½-½`) with reason text in the info panel.

### Off-board side areas

Both players see all off-board pieces from both sides. From each player's perspective, **own off-board pieces are on the right, opponent's are on the left** — like a real board.

| | Left of board | Right of board |
|---|---|---|
| White's view | Black's off-board pieces | White's off-board pieces |
| Black's view | White's off-board pieces | Black's off-board pieces |

Pieces in the side area are draggable back onto the board.

---

## Clock — visual & behavioural

### Visual design (DGT 3000-inspired)

- **Red wedge body**, viewed mostly head-on with a slight forward tilt (`perspective` + `rotateX`) for a 3D feel; no exposed side face. Subtle vertical gradient (highlight on top, recessed at bottom) plus inset shadows give the wedge depth.
- **Two LCD displays** stacked vertically inside the wedge: one for each player's remaining time. Greenish-yellow LCD background, dark monospace digits.
- **One central rocker (lever) between the displays**, drawn in white/cream with a vertical seam, to evoke the DGT 3000 mechanical button. Pressing the rocker on either side acts as that side's clock-press button.
- **Time display orientation:** both LCDs render the time **horizontally** — `30:00` reads left-to-right on each display, in both views, regardless of which side owns the LCD. (Earlier 90°/180°/270° rotations were removed.)
- **Active state:** the active half is highlighted (lighter LCD).
- **Low time (<30 s):** the active half flashes red (`pulse-red` keyframes).
- **Pause overlay:** when the clock is paused (arbiter intervention, restoration handshake, between turns), a large translucent **PAUSE** label is drawn as an absolute overlay over the wedge body. The time digits themselves stay in their normal horizontal orientation.

### Position relative to the board

Always physically on White's right side of the board:

- White's view → right column.
- Black's view → left column (achieved by reordering: `flex-direction: row-reverse` on the wedge plus moving the `.right-column` to the left of the board via `order: -1`).

The two LCDs and the rocker keep the same relative placement so that "the LCD nearest the board" stays nearest the board after the flip.

### Time control

- Standard time + per-move increment, Fischer-style.
- Default: **30+0**.
- Presets and custom values, see "Game Setup".
- Tick at 1 Hz (`scheduledAtFixedRate(1000ms)` in `GameWebSocketServer`).

### Clock state

- **Running on side X** during X's thinking time.
- **Switched** atomically when X's clock-press is accepted (`MOVE_ACCEPTED`).
- **Stopped** during arbiter interventions (illegal move, touch-move violation, released-piece violation, restoration handshake).
- **Paused** (visual PAUSE overlay) whenever the clock is stopped between turns.
- **No player-initiated pause.**
- **Flag fall:** the LCD shows `0:00` (final clock update is sent before the `gameEnded` message so the client doesn't display a stale `0:01`).

### Clock-press semantics

- A click **only registers when**:
  - it lands on the player's **own** rocker side, **and**
  - it is the player's **own turn**.
- Otherwise the click is silently ignored — the cursor and DOM behaviour are identical for both halves so the board cannot leak whose lever is whose. No "do not press your opponent's clock" feedback.
- A clock press is the trigger for full move evaluation (see "Two-Layer Evaluation at Clock Press").

---

## GUI Events

The board records a sequence of events during a player's turn:

| Event | Description |
|---|---|
| **Click** | Player clicks a piece and releases on the same square (touch) |
| **Drag-move** | Player drags a piece from square A to empty square B, releases |
| **Drag-capture** | Player drags a piece from square A to occupied square B, releases (displaced piece goes to side area) |
| **Remove** | Player drags a piece off the board (piece goes to side area) |
| **Restore-to-empty** | Player drags a piece from side area onto an empty square |
| **Restore-to-occupied** | Player drags a piece from side area onto an occupied square (displaced piece goes to side area) |
| **Drag-start** | (Cosmetic, real-time mirroring only.) Player begins a drag — emitted on the wire so the opponent's screen can mirror the lifted piece. |
| **Drag-hover** | (Cosmetic, real-time mirroring only.) The dragged piece's mouse position has changed to a different square. Throttled per-square — the wire receives at most one event per (square, piece) transition. |
| **Clock press** | Player presses the clock to signal turn is complete |

`Drag-start` and `Drag-hover` are **display-only** — they are not run through the arbiter, the move recorder, or the auto-end check.

### Capture mechanics

- When a piece is dragged onto an occupied square, the displaced piece is automatically moved to a side area.
- Pieces in the side area can be dragged back onto the board.
- A piece can be removed from the board by dragging it off the board edge.

### Real-time opponent drag mirroring

While the opponent is moving, the player sees a translucent floating piece following the opponent's cursor on the player's own screen — the digital equivalent of seeing your opponent hover their hand over the board.

- `DRAG_START` empties the source square on the observer's side and spawns a floating piece.
- `DRAG_HOVER` (square-throttled) moves the floating piece to the centre of the new square on the observer's screen. Look-up is by square name in the observer's own DOM, so it works correctly even when the two players have flipped boards.
- Any non-cosmetic event (`DRAG_MOVE`, `DRAG_CAPTURE`, `REMOVE`, `RESTORE_*`, `CLICK`) ends the floating-piece visualisation on the observer's side and applies the new state.
- Forwarded events are suppressed during restoration-resume-pending (the moving player has agreed to a restored position; the observer has already updated).

### Freedom of movement

- The player can move their own pieces freely during their turn — including moving a piece back to its origin square.
- No board freezing, no restrictions on own piece movement.
- Dragging an opponent piece from one square to another is the only mid-play intervention on opponent pieces. Removing an opponent piece off the board is allowed (used for capture-by-removal, see "Capture mechanics" above).

---

## Touch-Move Rules

Touch-move is evaluated by scanning the action sequence at clock press. The first touch-move obligation found applies.

### Touching own piece

- The first own piece touched (clicked or grabbed) that has legal moves establishes the obligation: must move that piece.
- If the touched piece has no legal moves, no obligation — continue scanning.

### Touching opponent piece

- If an opponent piece is touched that can be legally captured, the player must capture it.
- The arbiter does not say which piece must capture — only that the opponent piece must be captured.

### Touch-move violation message

When the player presses the clock without satisfying the obligation, the message names the **piece type and square** that was touched, e.g.

> _"You touched the white knight on g1 — you must make a legal move with that piece."_
>
> _"You touched the black bishop on c8 — you must capture it."_

### Touch-move accumulation

Touching an opponent piece (must capture) and then touching an own piece that can perform that capture: both obligations combine.

### Touch-move persistence

Touch-move obligations persist across arbiter interventions. If a player touched a piece, made an illegal move, restored the position, and resumed — the touch-move obligation from the original touch still applies.

### Released-piece rule (FIDE 4.7)

Touching is one step short of committing. **Releasing a piece on a legal target square** commits the player to that move (or to one of the moves consistent with that release):

- After the player drops a piece on a square that completes a legal move, the *committed move set* is the set of legal moves that end with that piece on that square.
- Any subsequent manipulation that would change the final position to something **not** in the committed move set is a **released-piece violation**.
- Restoration after a released-piece violation restores the board to the **release position** (not the start of the turn) — the player must complete a legal move from the committed set.
- A castling attempt is treated as a multi-step legal move: the king's release on its castled square commits to castling; the rook drag then completes the move.
- The committed-release detection is scoped per turn: it resets at the start of each turn and after any restoration that legitimately rewinds back to the start of the turn.

### Draw claims and touch-move

- Draw claims on the board must be rejected if the player has already touched a piece.

---

## Castling

### Mechanics on the dumb board

Castling is performed physically as **two consecutive piece movements**:

1. The player **drags the king first** from its starting square to the castled square (g1/c1 for White, g8/c8 for Black).
2. The player **then drags the rook** from its starting square to its castled square (f1/d1 or f8/d8).
3. The player **presses the clock**.

If the final board position matches the legal castled position, the move is accepted as castling.

### King-first rule

The king **must** be moved first. If the player moves the rook first and then the king, the move **is not accepted as castling** — it is treated as a regular rook move (followed by a king move). If the rook move itself creates a binding touch-move or released-piece obligation, that obligation applies.

### Illegal castling attempts

When the king-first physical pattern is detected but the castling itself is not legal, the arbiter reports **"castling is not possible"** prefixed to the concrete reason from the chess library, e.g.:

- "castling is not possible: the king is in check"
- "castling is not possible: the king would travel over a field that is in check"
- "castling is not possible: the king would end in check"
- "castling is not possible: the squares between king and rook are not empty"
- "castling is not possible: castling rights are missing"

### Touch-move consequence of an illegal castling attempt

Because castling counts as a king move, an illegal castling attempt counts as **touching the king**.

- After the position is restored:
  - If the king has any legal move → the player **must make a legal move with the king**. The arbiter message says so explicitly.
  - **Special case:** if the king has **no** legal moves, the player is **not** forced to move the rook just because the rook was also moved as part of the failed castling attempt. The player may make any legal move with any piece. The arbiter message reflects this.

### Released-piece interaction

To avoid double-punishment for a failed castling attempt, the released-piece rule is **bypassed** when:

- a king-then-rook drag pattern is detected, **and**
- the would-be castling is illegal, **and**
- the king's release square is not itself the destination of a legal regular king move.

In every other case the released-piece rule applies normally.

### Castling-specific released-piece message

When the king's release on its castled square commits the player to a castling that **is** legal but the rook hasn't been moved (or was placed wrongly), the released-piece message is castling-specific rather than the generic "put the piece back" wording. Example for kingside white:

> _"Released-piece violation: You released the king on g1, which initiates kingside castling, and castling is legal. Under the released-piece rule, the king must stay on g1. Please complete the castling by moving the rook from h1 to f1 and pressing the clock."_

The king is not moved back — it stays on the castled square because that's where it belongs in the committed move. The player is told exactly which rook move completes the castling.

---

## En Passant

- The player moves their pawn diagonally to the empty square behind the opponent pawn.
- The player removes the opponent pawn from the board (drags it off the board to the side area).
- The position check at clock press validates the en passant move; the order in which the two manipulations are made does not matter.

---

## Promotion

### Piece supply

- At game start, **one extra queen of each color** is placed in the side area.
- No automatic replacement when used.

### Request Piece button

- Displays all 6 piece types (pawn, rook, knight, bishop, queen, king) — no filtering, no hints. The player chooses the piece they need; the arbiter does not police the choice (e.g. promoting to a king is rejected by ordinary move validation, not by the request UI).
- The requested piece appears in the side area for the player to drag onto the board.

### Execution

- Player moves the pawn to the promotion rank, removes the pawn from the board, places the requested piece on the promotion square.
- Or: the player drags a piece from the side area directly onto the pawn's square (the pawn is displaced to the side).

---

## Two-Layer Evaluation at Clock Press

### Layer 1 — Board-state comparison (legality)

- Compare the board position before the move to the board position after.
- Enumerate all legal moves, compute the resulting position for each, compare with the player's board state (`PositionComparator.findMatchingMoves`).
- If a match is found → the move is identified.
- If no match → illegal move.

### Layer 2 — Action sequence (touch-move)

- If a touch-move obligation exists, the matched move must satisfy it.
- If not satisfied → touch-move violation (not counted as illegal move).

### Released-piece check (FIDE 4.7)

- If the player has committed to a released-piece move set (see "Released-piece rule") and the final position is **not** in that set → released-piece violation. Restoration target is the release position.

### Auto-end on game-ending moves

To match the experience of a real board, certain game-ending moves end the game **without waiting for the clock press**:

- **Triggers:** checkmate, stalemate, dead position, fivefold repetition, 75-move rule.
- After every non-cosmetic board event, if the current physical position matches a legal move and that move would result in a game-ending position, the move is accepted and the game is ended immediately.
- The auto-end path **must not have side effects** on the illegal-move counter — only the clock-press path goes through the full arbiter pipeline.
- Released-piece violations and unsatisfied touch-move obligations still suppress auto-end (they go through the standard clock-press flow so the player gets the proper feedback).

### After invalid evaluation

1. The arbiter explains the violation with a prominent message (red for errors, yellow for instructions).
2. The arbiter instructs: _"Please restore the position to the beginning of the move."_ (or, for a released-piece violation: _"…to the release position."_).
3. A **"Do this for me"** button is available — restores the position automatically.
4. After restoration:
   - **Auto-resume mode (default):** the clock resumes immediately; the player just plays.
   - **Manual mode:** both players see _"Are you ready to continue?"_ with a **Ready to continue** button. Both must click before the clock restarts.

### Touch-move violation vs. illegal move

- **Touch-move violation** → no penalty, does not count toward the illegal move rule.
- **Released-piece violation** → no penalty, does not count toward the illegal move rule.
- **Illegal move** → penalty time is added to the **opponent's** clock; counts toward the illegal-move limit.

### Illegal-move counter and messaging

- The configured limit (start screen, default **2**) determines when an illegal move ends the game.
- The arbiter message reports the cumulative count and the consequence of the next illegal move, e.g.
  > _"This is your 1st illegal move. Your next illegal move will lose the game."_ (limit 2)
  >
  > _"This is your 3rd illegal move. Your 5th illegal move will lose the game."_ (limit 5)
- When the limit is reached, the message is _"You have made N illegal moves. You lose the game."_
- When the limit is **Unlimited**, the message stops at the count and never threatens game loss.

---

## Game Endings

### Automatic (after every valid move, in order)

1. **Checkmate** → "White/Black won the game by checkmate."
2. **Stalemate** → "The game is drawn by stalemate."
3. **Insufficient material** → "The game is drawn by insufficient material. Neither player can checkmate." (clean-chess `isInsufficientMaterial()`, a fast structural test — FIDE 9.4 / 5.2.2).
4. **Fivefold repetition** → "The game is drawn by fivefold repetition."
5. **75-move rule** → "The game is drawn by the 75-move rule."

The full unwinnability search (`UnwinnableFullAnalyzer`, the deep CUA helpmate search) is **not** used in the in-game pipeline. Positions that are dead by exhaustive search but not by insufficient material continue, and the players resolve them via stalemate / fivefold / 75-move / claim — consistent with the dumb-board's "evaluate at clock press" model.

### Resignation

- No confirmation. Resign is final.
- Arbiter checks winnability via the **fast** `isUnwinnableQuick(opponent)` (microsecond-scale structural analysis): if the opponent cannot checkmate by any series of legal moves → draw instead of loss. `POSSIBLY_WINNABLE` is treated as winnable.
- Draw message: _"{Side} resigned, but because {Opponent} has no possible win, the game is a draw."_
- Loss message: _"{Side} resigns. {Opponent} wins the game."_

### Flag fall

- Arbiter checks winnability via `isUnwinnableQuick(opponent)`: if the opponent cannot checkmate → draw.
- Draw message: _"{Side}'s time has elapsed, but because {Opponent} has no possible win, the game is a draw."_
- Loss message: _"{Side} loses on time. {Opponent} wins the game."_
- Final clock update is sent **before** the `gameEnded` message so the LCD shows `0:00`, not `0:01`.

### Illegal move game loss

- When the configured illegal-move limit is reached (default 2) → game lost.

---

## Draw Claims

### Threefold repetition / 50-move rule — two variants each

#### "Claim on board"

- The current position already qualifies → arbiter accepts.
- Otherwise rejected with a specific reason (e.g. _"Threefold repetition claim rejected. The position has not occurred three times."_).

#### "Claim with move"

The player enters a move in **SAN notation** in an inline panel. The server processes the claim in this fixed order:

1. **SAN validation first.** The supplied SAN is validated against the current position via clean-chess's `SanValidation.validateSan(...)`. The move is **not performed** for validation — `validateSan` checks the move's legality without mutating the board. If the SAN fails:
   - Result: `invalidMove`. Message: _"Invalid move: «clean-chess reason». Please enter a legal move for the claim."_
   - The SAN-input panel stays open and is re-prompted with the input cleared and refocused.
   - The claim attempt is **not yet committed** — the player is just typo-correcting.
2. **Feasibility short-circuit.** If the SAN is legal, ask clean-chess whether **any** legal move from the current position could possibly satisfy the rule:
   - `board.canClaimThreefoldRepetitionRuleWithOwnMove()` for threefold,
   - `board.canClaimFiftyMoveRuleWithOwnMove()` for the 50-move rule.
   If neither → reject the claim immediately, without performing the player's move. Message: _"Claim rejected, because no move from the current position can lead to a threefold repetition. Please play."_ (or the 50-move equivalent).
3. **Per-move check.** Otherwise, perform the move speculatively on the internal board, check the rule, and unperform — the board state is restored regardless of outcome.

#### Outcomes (after both filters pass)

The player's SAN is echoed verbatim in the message so both players see exactly which move was claimed:

| Outcome | Claimer message | Opponent message | Game-end description |
|---|---|---|---|
| **Accepted** | _"Your claim was accepted."_ | _"Your opponent requested a draw for threefold repetition after the move «SAN»."_ (or 50-move variant) | _"The game is drawn by threefold repetition."_ (in the result panel — short, no duplication of the long claim text) |
| **Rejected — legal SAN but rule not satisfied** | _"Claim rejected, because there is no threefold repetition after the mentioned move «SAN». Please play."_ + `mustExecuteMove` | _"Your opponent claimed a draw by threefold repetition after the move «SAN». The claim was rejected."_ | (none — game continues; the player must still play the specified move) |
| **Rejected — short-circuit** (no move could satisfy) | _"Claim rejected, because no move from the current position can lead to a threefold repetition. Please play."_ | _"Your opponent claimed a draw by threefold repetition after the move «SAN». The claim was rejected."_ | (none) |

The arbiter **never silently accepts an illegal SAN** — the player learns from clean-chess's exact reason.

#### Once-per-turn limit (FIDE 9.2 / 9.3)

A player may make **at most one claim per move**. This includes both "on board" and "with move" attempts; an invalid-SAN attempt does **not** consume the allowance (the player hasn't completed an attempt yet). When a player tries a second claim on the same move:

- Claimer: _"You have already made a draw claim on this move. Only one claim per move is allowed."_
- Opponent: _"Your opponent attempted a second draw claim on the same move. The claim was rejected."_
- Counter resets at the start of the next turn.

#### Cancel button is removed once a claim is committed

- Before the first **"Claim with Move"** click in a turn, the SAN-input panel shows a **Cancel** button.
- The moment the player presses **"Claim with Move"** (regardless of SAN validity), the cancel button is hidden. The player must enter a legal SAN to complete the claim — they cannot back out.
- If the SAN is invalid the panel re-prompts without the cancel button.
- The next time the SAN-input panel opens (next turn, after the claim resolves), the cancel button is restored.

#### Rejected claim becomes a draw offer (FIDE 9.5)

A rejected claim that came through the proper FIDE channel (claim-on-board, or claim-with-move with a legal SAN) is treated as a regular draw offer to the opponent:

- The session registers the offer via the standard `DrawOfferManager` correct-time path (no escalation penalty — the player had the move).
- The opponent receives the standard **drawOffered** broadcast with Accept/Reject buttons. Touch-piece invalidation works as for any other correct-time draw offer.
- Cases that do **not** convert to a draw offer: `invalidMove` (SAN never validated), pre-claim errors (game not in progress, not on move), and second-claim-on-same-move rejections.

### Draw offer (FIDE 9.1.2.1)

#### Correct-time offer

- Made **after the player has executed their move and before pressing the clock**.
- Travels to the opponent normally; the opponent gets an Accept/Reject panel.

#### Wrong-time offers

- Made at any other moment (opponent's turn, or the player's own turn before they've made a move).
- The offer **still counts** per FIDE 9.1.2.1, but **escalating penalties** apply:
  1. **First wrong-time offer (info):** message worded depending on whether the offerer has the move:
     - If the offerer has the move (case A): _"…the draw offer should be made after making your move and before pressing the clock. Not following this procedure could lead to a warning. The offer still counts as a draw offer."_
     - If not on move (case B): _"…the draw offer should be made on your own turn. Not following this procedure could lead to a warning. The offer still counts as a draw offer."_
  2. **Second wrong-time offer (warning):** _"You are offering a draw at the wrong time. The next wrong-time draw offer will lose the game."_
  3. **Third wrong-time offer:** game lost. _"You have repeatedly offered a draw at the wrong time. You lose the game."_

#### Repeated offers

Independently of wrong-time tracking, **repeated offers on the same draw** escalate:

1. **Repeat 1 (info):** _"You cannot repeat the draw offer on the same move."_
2. **Repeat 2 (warning):** _"You cannot repeat the draw offer. The next repeated draw offer will lose the game."_
3. **Repeat 3:** game lost.

#### Loss of right to accept

The opponent loses the right to accept the offer when they "do something equivalent to a move" — but the trigger depends on how the offer was made:

- **Offer made at the correct time** → opponent's **first piece touch** invalidates the offer (FIDE 9.1.2.1).
- **Offer made at the wrong time** → invalidation only when the opponent has **legally released a piece** (i.e. completed the released-piece commitment) — merely touching pieces while deciding their move shouldn't penalise them, since they were already mid-thinking when the offer arrived.

In either case the offerer is told why the offer is no longer acceptable.

#### Offer-with-invalid-move

If the player offers a draw together with a clock-press but their move is invalid (illegal, touch-move, released-piece), the draw offer is **silently dropped**. It does not count as a repeated offer.

---

## Mid-Play Interventions

These are checked during play (not only at clock press):

| Intervention | Trigger | Behaviour |
|---|---|---|
| **Opponent-piece drag (square → square)** | Player drags an opponent piece from one square to another (DRAG_MOVE / DRAG_CAPTURE) | Arbiter intervenes immediately with a "you may only move your own pieces" message + restoration. |
| **Opponent-piece removal** | Player drags an opponent piece off the board | **No intervention.** The board observes silently; this is the first step of a capture-by-removal sequence. The square is added to `removedSquaresThisTurn` so it can be restored from the side area later if the player changes their mind. |
| **Released-piece commitment violation** | Player has committed a release and a subsequent manipulation moves them off all positions consistent with the committed move set | Restore to release position. |
| **Position change after restoration** | After a "Do this for me" or manual restoration, the player makes a board change that drifts away from the agreed restored position before resuming | Arbiter intervenes; restoration is repeated. |

---

## Restoration Flow

1. Player commits a violation (illegal, touch-move, released-piece).
2. The arbiter shows a red error message explaining what happened.
3. The arbiter instructs the player to restore the position.
4. **"Do this for me"** button — clicking sends the original (or release-) position to the client which restores it automatically. The opponent is told the restoration happened.
5. Once the physical board matches the target restoration position:
   - **Auto-resume mode:** clock resumes immediately. The arbiter shows _"Position restored. Continue."_
   - **Manual mode:** _"Position restored. Are you ready to continue?"_ with a **Ready to continue** button. Both players must click before the clock restarts. The clock stays paused (PAUSE overlay) until both have confirmed.

---

## Opponent Disconnect

- When one side's WebSocket closes, the remaining player is told _"Your opponent disconnected."_
- Any in-flight opponent-drag visualisations are dropped on the surviving side.
- The game state is preserved server-side; reconnection (future) would resume from where it stopped.

---

## Internal Errors & Developer Console

User-visible messages must never leak technical detail (exception class names, stack traces, JVM jargon). The server distinguishes two error categories:

### Intentional user errors
Expected outcomes routed through `sendError(conn, message)` — game not found, game is full, "you are not in a game", unknown message type, invalid FEN. The player sees the direct text, since it's actionable.

### Unexpected exceptions
Anything that escapes a handler (the catch-all in `onMessage`, the clock-tick swallow, etc.) goes through `sendInternalError(conn, e, context)`:

- **Server side:** the full stack trace is logged to stderr.
- **Wire:** the `error` message carries two fields:
  - `message` — a friendly generic line: _"We are sorry — the arbiter lost his concentration for a moment and could not handle the situation. Please try again."_
  - `devDetail` — `"<context>: <exception class + message>"`, for diagnostics.

### Developer console (frontend)

A small `#devConsole` panel pinned to the bottom of the viewport. Hidden by default; appears the first time a `devDetail` arrives and stays. Distinct from the arbiter panel: dim background, monospace, timestamped. Has **clear** and **hide/show** controls.

The error-message handler renders the friendly line in the arbiter panel and routes any `devDetail` exclusively to the developer console. Technical text never enters the user-visible UI.

---

## Capture-by-Removal

The board allows the **physical capture sequence**: lift the opponent piece off the board, then move your own piece onto the now-empty square. Both events are observed silently during play — there is no mid-play intervention. At clock press the position is evaluated as usual:

- The resulting position equals the position after the legal capture move → accepted as a normal capture (the same outcome as a single DRAG_CAPTURE).
- The position does not match any legal move → standard illegal-move flow (restoration, counter increment, penalty).

This complements the existing capture path where the player drags their own piece onto the opponent's square (DRAG_CAPTURE auto-displaces the opponent piece).

**En passant** uses the same physical sequence: lift the opponent pawn off the board, then move the capturing pawn diagonally onto the now-empty target square (the en passant square, which is empty in any case).

### Special rule (planned, not yet implemented) — the king cannot be captured

If the player drags the **opponent's king** off the board at any moment, the arbiter must intervene **immediately** with:

> _"You are not allowed to remove the opponent's king from the board. Please restore."_

This is tracked in **Open Items** below.

### Side note — piece displacement detection (deferred)

The current rule allows the player to remove an opponent piece without subsequently moving onto its square. The position at clock press will then not match any legal move and the standard illegal-move flow fires — but the message wording is generic (it does not specifically point out that an opponent piece was removed without a corresponding capture). A future refinement should:

- Detect "piece displacement" — opponent pieces removed during the turn that are not consistent with a single legal move from the starting position.
- Surface a more specific message ("You removed the {piece} on {square} but did not capture it. Please restore.").

This is tracked in **Open Items** below.

---

## WebSocket Protocol

### Inbound (client → server)

`createGame`, `joinGame`, `boardEvent` (incl. cosmetic `DRAG_START` / `DRAG_HOVER`), `clockPress`, `offerDraw`, `acceptDraw`, `rejectDraw`, `claimDraw`, `resign`, `requestPgn`, `restorePosition`, `readyToContinue`.

### Outbound (server → client)

`gameCreated`, `gameJoined`, `gameStarted`, `move_accepted`, `opponentMoved` (with full board state), `boardUpdate`, `clockUpdate` (white time, black time, side currently running), `opponentBoardEvent` (for real-time mirroring), `illegal_move`, `touch_move_violation`, `released_piece_violation`, `incomplete_move`, `illegal_move_game_lost`, `revert_opponent_piece`, `revert_restoration`, `position_change`, `restoreRequired`, `positionRestored`, `waitingForReady`, `waitingForOpponentReady`, `gameResumed`, `drawOffered`, `drawOfferInvalidated`, `drawRejected`, `drawAcceptRejected`, `wrongTimeDrawOffer`, `repeatedDrawOffer`, `drawClaimResult` (incl. `invalidMove` / `mustExecuteMove`), `drawClaimOpponent` (per-player split: opponent-side notification of the claim event), `gameEnded`, `pgn`, `error` (with optional `devDetail` for unexpected exceptions), `opponentDisconnected`.

For `move_accepted`, the `move` block carries `from`, `to`, `piece`. **Castling moves** additionally carry `castling: KING_SIDE | QUEEN_SIDE`; their `from`/`to` are resolved to the king's actual squares (the `MoveSpecification` from/to of a castling move are `Square.NONE`, which would otherwise crash on `getName()`).

For `gameJoined` and `gameStarted`, a `havingMove` field carries the side to move at game start (necessary for custom-FEN games where Black may be to move first).

---

## Architecture

- **Separate Maven project** (`dumb-chessboard`) depending on **clean-chess 3.0**.
- **All business logic in Java.** Frontend is thin presentation only.
- **Java built-in `HttpServer`** on port **8080** for static files.
- **Java-WebSocket library** on port **8081** for two-player real-time communication.
- **Gson** for JSON serialization.
- The server is single-process; sessions are kept in memory and identified by the 8-character game code.

### Centralised messages (slice 1: arbiter + opponent)

User-visible rule messages flow through a typed message infrastructure rather than freeform string literals scattered across the code:

- `MessageKey` — type-safe enum of message keys; each constant carries its property key and `MessageSeverity` (INFO / WARNING / ERROR / SUCCESS).
- `MessageSeverity` — colour/severity contract used by the frontend.
- `Messages.get(key, args...)` — single English `messages.properties` loaded explicitly as UTF-8; fail-loud on missing keys.
- `ArbiterResponse` carries structured records (`IllegalMoveDetail`, `ReleasedPieceContext`, `ReleasedPieceCastlingContext`) and renders both player-facing and opponent-facing messages from the same data — no string surgery.
- The `CUSTOM_INFO` / `CUSTOM_WARNING` / `CUSTOM_ERROR` keys are transitional escape hatches for messages not yet migrated; severity is preserved.

Slice 1 covers all arbiter messages (touch-move, released-piece, illegal-move, position-change). Game-flow / draw-offer / draw-claim messages are still inline literals — slated for a follow-up slice.

### Three planned modes (future)

1. **Practice board** — immediate, helpful feedback. Educational.
2. **Tournament board with arbiter** — current mode; evaluation at clock press.
3. **Tournament board without arbiter** — player must claim illegal moves; touch-move self-enforced.

---

## Key Implementation Classes

| Class | Responsibility |
|---|---|
| `PositionComparator` | Enumerates legal moves, compares resulting positions with the player's board state. |
| `TouchMoveEvaluator` | Scans action sequence for the first touch-move obligation; recognises failed castling attempts where the king has no legal moves (via `CastlingAttemptDetector`). |
| `CastlingAttemptDetector` | Shared helper that recognises a king-then-rook drag pattern; used by `TouchMoveEvaluator` and `ArbiterEngine`. |
| `ArbiterEngine` | Two-layer evaluation: position comparison + touch-move + released-piece + castling-attempt explanation. Builds structured `IllegalMoveDetail` / `ReleasedPieceContext` records used by typed message rendering. |
| `IllegalMoveTracker` | Tracks illegal-move count per side; configurable limit (1–10 or unlimited, default 2). |
| `MidPlayValidator` | Validates opponent-piece movement and piece-restoration during play. Allows opponent-piece **removal** (capture-by-removal); blocks opponent-piece drag-on-board. |
| `DrawOfferManager` | Draw-offer lifecycle: correct-time, wrong-time A/B, repeat counter, wrong-time counter, escalating penalties (info → warning → game lost). |
| `DrawClaimManager` | Threefold and 50-move claims. Validates SAN first (clean-chess `SanValidation`), then short-circuits via `canClaim…WithOwnMove()`, then performs/checks. Returns per-player + opponent + game-end messages. |
| `GameSession` | Central orchestrator: board, clock, arbiter, draw, resign, ready-to-continue, restoration state machine, must-execute-move, **per-turn claim ledger** (FIDE 9.2/9.3 once-per-turn limit), rejected-claim → draw-offer conversion. |
| `ClockManager` | Time control with increment, nanoTime precision. |
| `GameRoom` | Two WebSocket connections + the session; routes messages by side. |
| `GameWebSocketServer` | WebSocket server handling all message types. `sendInternalError` routes friendly text to the user and `devDetail` to the developer console. |
| `MessageConverter` | JSON to/from domain types (`StaticPosition`, `BoardEvent`). |
| `MessageKey` / `MessageSeverity` / `Messages` | Typed message infrastructure (slice 1: arbiter + opponent); UTF-8 properties + `MessageFormat`. |

---

## Test Coverage

**113 tests across 9 test classes** (current):

- `TestPositionComparator` (10) — basic move types.
- `TestPositionComparatorEdgeCases` (7) — multi-piece moves, missing pieces, castling variants.
- `TestTouchMoveEvaluator` (16) — touch-move scanning, castling, satisfaction, failed-castling-with-no-king-moves.
- `TestArbiterEngine` (27) — two-layer evaluation, all response types, released-piece (castling, back-to-origin, first-release-wins, castling-specific message, rook-moved-to-wrong-square), illegal-move count messaging, illegal castling reason + king obligation.
- `TestArbiterEngineEdgeCases` (7) — fumbling, no-legal-moves touch, counter tracking.
- `TestGameSession` (29) — full game flow, checkmate, draw claims, resign, custom-FEN starting position, capture-by-removal, threefold/50-move claim short-circuits, SAN-validation-before-short-circuit ordering, accepted-claim per-player messages + short game-end description, second-claim-on-same-move rejection, rejected-claim registers draw offer, invalid-SAN doesn't lock the turn.
- `TestGameSessionFlow` (8) — ready-to-continue, illegal-then-valid, touch-move persistence, released-piece restoration, touch-move-after-restoration.
- `TestMessageConverter` (6) — round-trip serialization, edge cases.
- `TestGameWebSocketServer` (3) — typed `ArbiterResponse` rendering, opponent-message-not-derived-from-player-prose regression test.

---

## Known Regression Prevention

| Bug | Root cause | Test |
|---|---|---|
| Board flip loses pieces | `buildBoard()` replaced state before saving | Frontend (manual) |
| Second player can't see moves | Lobby created the game on a separate WebSocket | Frontend flow fix (manual) |
| `NONE`-to-`NONE` `StaticPosition` error | `createChangedPosition` rejects no-op updates | `TestMessageConverter.testRoundTripPositionWithManyEmptySquares` |
| Touch-move not recognising castling | Castling `fromSquare` is `NONE` | `TestTouchMoveEvaluator.testCastlingSatisfiesKingTouchObligation` |
| Failed castling double-punishment | Released-piece rule fired on king release in addition to failed-castling | `TestArbiterEngine.testFailedAdjacentCastlingAttemptWithNoKingMovesDoesNotBindRook` |
| Castling broadcast crash with `NonePointerException` | `MoveSpecification.from/toSquare` are `Square.NONE` for castling; `Square.NONE.getName()` throws | Manual; safeguarded by `CastlingUtility.calculateIsCastlingMove` branch in `sendArbiterResponse` |
| Flag-fall LCD shows `0:01` | Final `clockUpdate` was sent after `gameEnded` | Manual |
| Auto-end incremented illegal-move counter | `evaluateForAutoEnd` was reusing `evaluateClockPress` | `TestGameSessionFlow` (released-piece interaction tests) |
| `Invalid move:` in claim hid the SAN panel | Frontend hid the panel on submit; rejected-with-invalid-move never re-prompted | `TestGameSession.testClaimWithInvalidSanIsRejectedAsInvalidMove` (+ 50-move counterpart) |
| Misleading "put the king back" on castling-released-piece | Generic released-piece message used regardless of castling commitment | `TestArbiterEngine.testReleasedPieceViolationCastlingRookMovedToWrongSquare` |
| Asymmetric clock-press latency on opening | `isDeadPositionFull()` (deep CUA) ran on every legal-completing event and clock press | Replaced with `isInsufficientMaterial()`; visible speed-up in `TestGameSession` (≈50× faster) |
| Opponent claim message derived from player text | `formatOpponentIllegalMove` did `String.replace`-based pronoun rewriting on already-rendered prose | `TestGameWebSocketServer.testOpponentIllegalMoveMessageUsesOpponentReasonNotPlayerMessage` |
| Claim-accepted text duplicated under result panel | `GameResult.description` reused the long claim-accepted text | `TestGameSession.testAcceptedClaimCarriesShortGameEndDescriptionAndPerPlayerMessages` |
| Second draw claim on same move silently allowed | No per-turn ledger | `TestGameSession.testSecondClaimOnSameMoveIsRejected` |
| Rejected claim didn't become a draw offer | No conversion path from `DrawClaimResult` to `DrawOfferManager` | `TestGameSession.testRejectedClaimRegistersDrawOfferToOpponent` |
| Internal exceptions leaked technical text into the arbiter panel | Catch-all sent raw `e.getMessage()` to the client | `sendInternalError` separates friendly `message` from `devDetail`; manual verification via dev console |

---

## Open Items

1. **Practice mode** — more lenient, immediate helpful feedback.
2. **Tournament mode without arbiter** — player must claim illegal moves.
3. **King-cannot-be-captured immediate intervention.** Today, dragging the opponent's king off the board is silently allowed; the resulting position fires the generic illegal-move flow only at clock press. Per spec the arbiter should intervene immediately when the king is removed: _"You are not allowed to remove the opponent's king from the board. Please restore."_
4. **Piece-displacement detection** (deferred). When the player removes an opponent piece without subsequently moving onto its square, the position at clock press doesn't match any legal move and the standard illegal-move flow fires — but with a generic message. Future refinement should detect this specific case and produce a wording that names the removed piece and asks the player to either complete the capture or restore the piece.
5. **Centralised messages — slice 2.** Arbiter and opponent messages are now typed (`MessageKey`, `Messages.get`); game-flow / draw-offer / draw-claim / game-result strings are still inline literals. Slated for a follow-up slice that finishes the migration and removes the transitional `CUSTOM_*` keys.
6. **Move history display** — step-through with arbiter interventions.
7. **Reconnect after disconnect** — current state is preserved server-side; reconnection flow not yet implemented.
