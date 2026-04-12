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
7. **Only one mid-play intervention exists:** Moving an opponent piece triggers immediate arbiter intervention. The player can only move their own pieces.
8. **Respect the player's sphere of control.** The player is always in control of their own pieces. The board and arbiter never intrude into this sphere. Even when a specific move must be executed (e.g. after a rejected draw claim), the player physically makes the move themselves.

---

## Game Setup

1. The player opens the board and chooses the color they want to play.
2. The player selects a time control:
   - **Presets:** 3+0, 3+2, 5+0, 5+3, 15+0, 15+10, 30+0 (default: 30+0)
   - **Custom:** manually set standard time and increment
3. The player receives a game code to share with the second player, with a "Copy code" button.
4. The second player enters the code and joins.
5. Both players are connected via separate browser windows — the game begins.

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
| **Clock press** | Player presses the clock to signal turn is complete |

### Capture Mechanics

- When a piece is dragged onto an occupied square, the displaced piece is automatically moved to a side area.
- Pieces in the side area can be dragged back onto the board.
- A piece can be removed from the board by dragging it off the board edge.

### Freedom of Movement

- The player can move their own pieces freely during their turn — including moving a piece back to its origin square.
- No board freezing, no restrictions on own piece movement.
- Moving an opponent piece is the only action that triggers immediate arbiter intervention.

### Off-Board Pieces (Side Areas)

Both players see all off-board pieces from both sides:
- **White's view:** Left side = Black off-board pieces, Right side = White off-board pieces
- **Black's view:** Left side = White off-board pieces, Right side = Black off-board pieces
- In physical terms: each player's own off-board pieces are on their right side, the opponent's on their left — like a real board.

---

## Touch-Move Rules

Touch-move is evaluated by scanning the action sequence at clock press. The first touch-move obligation found applies.

### Touching own piece
- The first own piece touched (clicked or grabbed) that has legal moves establishes the obligation: must move that piece.
- If the touched piece has no legal moves, no obligation — continue scanning.

### Touching opponent piece
- If an opponent piece is touched that can be legally captured, the player must capture it.
- The arbiter does not say which piece must capture — only that the opponent piece must be captured.

### Touch-move accumulation
- Touching an opponent piece (must capture) and then touching an own piece that can perform that capture: both obligations combine.

### Touch-move persistence
- Touch-move obligations persist across arbiter interventions. If a player touched a piece, made an illegal move, restored the position, and resumed — the touch-move obligation from the original touch still applies.

### Draw claims and touch-move
- Draw claims on the board must be rejected if the player has already touched a piece.

---

## Two-Layer Evaluation at Clock Press

### Layer 1: Board State Comparison — Legality Check
- Compare the board position before the move to the board position after.
- Enumerate all legal moves, compute the resulting position for each, compare with the player's board state.
- If a match is found → the move is identified.
- If no match → illegal move.

### Layer 2: Action Sequence — Touch-Move Check
- If a touch-move obligation exists, the matched move must satisfy it.
- If not satisfied → touch-move violation (not counted as illegal move).

### After Invalid Evaluation

1. The arbiter explains the violation with a prominent message (red for errors, yellow for instructions).
2. The arbiter instructs: "Please restore the position to the beginning of the move."
3. A "Do this for me" button is available — restores the position automatically.
4. After restoration, the arbiter asks: "Are you ready to continue?"
5. Both players get a "Ready to continue" button.
6. Both must click before the clock restarts and play resumes.

### Touch-Move Violation vs. Illegal Move
- **Touch-move violation** → no penalty, does not count toward the illegal move rule.
- **Illegal move (first)** → +2 minutes to opponent's clock.
- **Illegal move (second)** → game lost.

---

## Special Moves

### En Passant
- The player moves their pawn diagonally to the empty square behind the opponent pawn.
- The player removes the opponent pawn from the board.
- Position check validates at clock press.

### Castling
- Castling is only initiated by moving the king. Moving the rook first is a rook move.
- Player moves king two squares, then moves the rook.
- Position check validates both pieces are in correct castling positions.

### Promotion
**Piece supply:**
- At game start, one extra queen is placed on each side of the board.
- No automatic replacement when used.

**"Request Piece" button:**
- Displays all pieces (pawn, rook, knight, bishop, queen, king) — no filtering, no hints.
- The requested piece appears in the side area for the player to drag onto the board.

**Execution:**
- Player moves pawn to promotion rank, removes pawn, places a piece from the side area.
- Or: drags a piece from the side area onto the pawn's square (pawn displaced to side).

---

## Game Endings

### Automatic (after every valid move, in order)
1. **Checkmate** → "White/Black won the game by checkmate."
2. **Stalemate** → "The game is drawn by stalemate."
3. **Dead position** → "The game is drawn. Neither player can checkmate the opponent." (Uses CUA: `WinnableAnalyzer`)
4. **Fivefold repetition** → "The game is drawn by fivefold repetition."
5. **75-move rule** → "The game is drawn by the 75-move rule."

### Resignation
- No confirmation. A resign is final, like in real chess.
- Arbiter checks winnability: if the opponent cannot checkmate → draw instead of loss.

### Flag Fall
- Arbiter checks winnability: if the opponent cannot checkmate → draw.

### Illegal Move Game Loss
- Second illegal move by the same player → game lost.

---

## Draw Claims

### Threefold Repetition / 50-Move Rule

Two variants each:

**"Claim on board":** Current position already qualifies → arbiter accepts or rejects.

**"Claim with move":** Player enters a move in SAN notation. If the position after that move qualifies → accepted. If rejected → the specified move must still be played (patient loop: restore, ready, try again).

### Draw Offer

- Player must complete their move before offering.
- Draw offer triggers move evaluation (same as clock press).
- If the move is invalid, the draw offer is dropped (does not count as repeated).
- Opponent loses right to accept after touching a piece.
- **Escalating penalties for repeated offers:**
  1. First offer → normal, forwarded to opponent.
  2. Second → arbiter information: "You cannot repeat the draw offer."
  3. Third → arbiter warning: "The next repeated draw offer will lose the game."
  4. Fourth → game lost.

---

## Clock

- Standard time + increment per move.
- Default: 30+0.
- Paused automatically during arbiter interventions.
- After intervention: both players must click "Ready to continue" before clock restarts.
- No player-initiated pause.

---

## Three Planned Modes (Future)

1. **Practice board** — Immediate, helpful feedback. Educational.
2. **Tournament board with arbiter** — Board acts as arbiter. Evaluation at clock press as described above.
3. **Tournament board without arbiter** — Player must claim illegal moves. Touch-move self-enforced.

---

## Move Validation — Detailed Error Messages

### Step 1: Basic Checks
1. Source square is empty → "You must move a piece"
2. Source square has opponent's piece → "You must move your own piece"

### Step 2: Piece-Specific Validation

#### Pawn — Non-capturing (same file)
- Backwards → "A pawn cannot move backwards"
- On starting rank, one square: destination has own piece → "A pawn cannot move onto own pieces"; opponent piece → "A pawn cannot capture moving forward"
- On starting rank, two squares: intermediate not empty → "For a two square move, the square before the pawn must be empty"
- Not starting rank, more than one square → "A pawn can only move one square forward"

#### Pawn — Non-adjacent rank
- → "A pawn can only move to adjacent ranks"

#### Pawn — Adjacent rank, not diagonal
- → "A pawn can only capture diagonally"

#### Pawn — Capturing (diagonal)
- Empty → "When moving diagonally, a pawn must capture an opponent piece"
- Own piece → "A pawn cannot capture own pieces"
- Opponent king → "The king can never be captured"

#### Rook
- Not reachable → "A rook can only move horizontally or vertically"
- Path blocked → "A rook cannot jump over pieces"
- Own piece → "A rook cannot move onto own pieces"
- Opponent king → "The king can never be captured"

#### Knight
- Not reachable → "A knight can only move in an L-shape"
- Own piece / opponent king → same as rook

#### Bishop
- Not reachable → "A bishop can only move diagonally"
- Path blocked → "A bishop cannot jump over pieces"
- Own piece / opponent king → same as rook

#### Queen
- Not reachable → "A queen can only move horizontally, vertically or diagonally"
- Path blocked / own piece / opponent king → same as rook

#### King
- More than one square → "The king can only move one square at a time in each direction"
- Own piece / opponent king → same as rook

### Step 3: King Safety
- King was in check, move doesn't resolve → "The move is not valid because it leaves the king in check"
- King was not in check, move exposes → "The move is not valid because it puts the king in check"

---

# Implementation Details

This section captures implementation decisions and GUI details clarified during development.

## Architecture

- **Separate Maven project** (`dumb-chessboard`) depending on `clean-chess`.
- **All business logic in Java.** Frontend is thin presentation only.
- **Java built-in HttpServer** (port 8080) for static files.
- **Java-WebSocket library** (port 8081) for real-time two-player communication.
- **Gson** for JSON serialization.

## Layout (Lichess-style)

- Board on the left, right panel on the right.
- Right panel: opponent clock (top) → game info area (middle) → own clock (bottom).
- Game result shown inline between the clocks (e.g. "1-0", "0-1", "1/2-1/2" with reason text). No popup overlay — the board must always remain visible.

## Clocks

- Displayed on the right side of the board, aligned vertically.
- Dark background, monospace font.
- Active clock: white background with green indicator bar (lever) on the left edge.
- Low time (<30s): red background.
- Inactive: dark with grey lever.

## Pieces

- SVG pieces in Lichess style: White pieces filled white with black outlines, Black pieces filled black with white internal details.

## Resign

- No confirmation dialog. Clicking resign immediately sends the resignation, like real chess.

## Arbiter Messages

- Displayed in the game info panel between the clocks.
- Error messages (illegal move, touch-move violation): red, bold.
- Instructional messages (restore position, ready to continue): yellow.
- Normal messages: white on dark background.

## Position Restoration Flow

1. Player makes illegal move → arbiter shows error message.
2. Arbiter instructs: "Please restore the position." + "Do this for me" button.
3. If clicked: server sends the original position, client restores automatically.
4. Arbiter asks: "Are you ready to continue?"
5. Both players get "Ready to continue" button — both must click.
6. Game resumes, buttons disappear, clock starts.

## Off-Board Pieces

- Two side areas flanking the board: left and right.
- Each player sees opponent's off-board pieces on the left, own on the right.
- Pieces are draggable from side area back onto the board (drag and drop, no text input).
- Extra queen provided at game start for each side.
- "Request Piece" button shows all 6 piece types (including pawn and king as invalid choices).

## Game Code

- 8-character UUID substring.
- Displayed with "Copy code" button that copies to clipboard.

## WebSocket Protocol

**Inbound (client → server):** createGame, joinGame, boardEvent, clockPress, offerDraw, acceptDraw, rejectDraw, claimDraw, resign, requestPgn, restorePosition, readyToContinue.

**Outbound (server → client):** gameCreated, gameJoined, gameStarted, move_accepted, opponentMoved (includes board state), boardUpdate, clockUpdate, illegal_move, touch_move_violation, incomplete_move, illegal_move_game_lost, restoreRequired, positionRestored, waitingForReady, waitingForOpponentReady, gameResumed, drawOffered, drawRejected, drawClaimResult, gameEnded, pgn, error, opponentDisconnected.

## Key Implementation Classes

| Class | Responsibility |
|---|---|
| `PositionComparator` | Enumerates legal moves, compares resulting positions with player's board state |
| `TouchMoveEvaluator` | Scans action sequence for first touch-move obligation |
| `ArbiterEngine` | Two-layer evaluation: position comparison + touch-move |
| `IllegalMoveTracker` | Tracks illegal move count per side |
| `MidPlayValidator` | Validates opponent piece movement and piece restoration during play |
| `GameSession` | Central orchestrator: board, clock, arbiter, draw, resign, ready-to-continue |
| `ClockManager` | Time control with increment, nanoTime precision |
| `DrawOfferManager` | Draw offer lifecycle with escalating penalties |
| `DrawClaimManager` | Threefold and 50-move claims using SAN validation |
| `GameWebSocketServer` | WebSocket server handling all message types |
| `MessageConverter` | JSON to/from domain types (StaticPosition, BoardEvent) |

## Test Coverage

76 tests across 8 test classes:
- `TestPositionComparator` (10) — basic move types
- `TestPositionComparatorEdgeCases` (7) — multi-piece moves, missing pieces, castling variants
- `TestTouchMoveEvaluator` (15) — touch-move scanning, castling, satisfaction
- `TestArbiterEngine` (12) — two-layer evaluation, all response types
- `TestArbiterEngineEdgeCases` (7) — fumbling, no-legal-moves touch, counter tracking
- `TestGameSession` (13) — full game flow, checkmate, draw claims, resign
- `TestGameSessionFlow` (6) — ready-to-continue, illegal-then-valid, touch-move persistence
- `TestMessageConverter` (6) — round-trip serialization, edge cases

## Known Regression Prevention

| Bug | Root Cause | Test |
|---|---|---|
| Board flip loses pieces | `buildBoard()` replaced state before saving | Frontend-only (manual test) |
| Second player can't see moves | Lobby created game on separate WebSocket | Frontend flow fix (manual test) |
| NONE-to-NONE StaticPosition error | `createChangedPosition` rejects no-op updates | `TestMessageConverter.testRoundTripPositionWithManyEmptySquares` |
| Touch-move not recognizing castling | Castling `fromSquare` is NONE | `TestTouchMoveEvaluator.testCastlingSatisfiesKingTouchObligation` |

## Open Items

1. **Practice mode** — more lenient, immediate helpful feedback.
2. **Tournament mode without arbiter** — player must claim illegal moves.
3. **Clock lever visual** — physical clock lever simulation.
4. **Move history display** — step-through with arbiter interventions.
5. **Insufficient material** — simple cases vs. CUA detection relationship.
