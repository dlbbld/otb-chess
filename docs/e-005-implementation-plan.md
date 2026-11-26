# E-005 — Implementation plan: free placement of the pieces and J'adoube

**Status**: Reviewed draft of 2026-11-25. The capture-first clarification and the proposed delivery order are included below. The first playable case (capture-first), the binding of an own piece and J'adoube are built since 2026-11-26, and the option is on by default; section 8 says what exists and where it differs from this plan. Written against `jadoube` with the released `main` (0.5.1) merged in. The owner decided the four rulings of section 1 on 2026-11-25; the details marked *(proposal)* in section 2 are not confirmed.

**What this is**: the plan for the requirement [E-005](enhancements.md#e-005--free-placement-of-the-pieces-and-jadoube). E-005 says what the players get; this says how it is built, in which order, and how it is tested. Where E-005 left a ruling open, section 1 records the owner's decision. Where it leaves a detail open, section 2 proposes one and marks it *(proposal)*.

**How it was written**: by reading E-005 against the code as it is: `static/js/board.js`, `static/js/game.js`, `GameSession`, `ArbiterEngine`, `MidPlayValidator`, `TouchMoveEvaluator`, `GameWebSocketServer`, and the sections of `SPECIFICATION.md` on board events, the released-piece rule and the evaluation at the clock press. Nothing was run.

---

## 1. Decisions of the owner

These four are rulings. E-005 did not answer them, and the code cannot be written without an answer. The owner decided all four on 2026-11-25 by accepting the proposals as they stood; E-005 now contains them.

### Q1 — May the arbiter name a square that gives no legal move?

E-005 lets the arbiter say *"Place it on c4 or on d4"* and also says an illegal square of the two "cannot be chosen". If c4 is illegal and the player follows the call, an illegal move is counted. What the arbiter says when neither square is legal is not written. [A-010](fide-deviations.md#a-010--a-release-and-a-touch-are-judged-in-the-order-they-happened) pins that the player must be able to follow every ruling, and `RuleFuzzer` checks it.

**Decided**: the arbiter names only the squares the player is bound to, which are the ones that give a legal move. If none does, he names no square: *"It is not clear on which square your bishop is. Place your pieces more carefully."* The player may then make any legal move with the piece. This tells the player which square is legal, but the second ruling of E-005 ("Your rook was released on d1 and e1. Place it on d1 or on e1.") has to name the bound squares anyway, so nothing more is given away.

### Q2 — A piece let go across the border of its own square

A pawn on e2 is picked up and let go across the border of e2 and e3. By the text of E-005 it is bound to the legal moves that end on e2 or e3, which leaves only e2-e3. Today the same handling is a click: the pawn is touched and must move, to any square.

**Decided**: if the square the piece came from is one of the squares its inner circle lies on, the release binds to nothing. The piece is touched, as today, and it lies unclear, so the arbiter makes the call at the clock press. The arbiter cannot tell a piece put back carelessly from a move.

### Q3 — A second unclear release on other squares

The rook from a1 is let go across d1 and e1, picked up again and let go across f1 and g1. It is bound to d1 and e1, but the call names the squares the piece lies on now.

**Decided**: the first binding stands, as the first release does today (FIDE 4.7). While a binding exists the call names the bound squares, not the squares the piece lies on: *"It is not clear on which square your rook is. It was released on d1 and e1. Place it on d1 or on e1."*

### Q4 — An unclear placement of an opponent's piece

The call says "your bishop", and a binding to "the legal moves that end on these squares" means nothing for a piece that is not the player's. Today a click on an opponent's piece is a touch, taking it off the board is the first half of a capture, and dragging it to another square is ruled on at once (*"You moved an opponent's piece. That is not allowed."*, [A-007](fide-deviations.md#a-007--moving-an-opponents-piece-escalation), with restoration and a ladder).

**Decided**:

- The inner circle still lies on the piece's own square: it is touched and lies unclear. At the clock press the arbiter makes the call for "your opponent's bishop"; the only way out is to put it back clearly on its square or to capture it. It counts on the ladder for unclear placements.
- The inner circle no longer lies on its own square: the piece was moved. Ruled on at once as today, with the position restored: the piece that is put back is centred.

#### Capture-first clarification from the review

After 1.e4 d5, White intends exd5. White lifts Black's pawn on d5, carries it half a square towards e5 and lets go on the line. Both players can see where it landed. Neither the path of the drag nor the player's intent decides the ruling. Apply Q4 on **release**, using the pawn's original d5 square even if it is picked up again.

| What White does with the black pawn | What happens |
|---|---|
| Carries it across the line with the mouse still down | Show the piece in hand. No call, touch adjudication or change to the captured material yet. |
| Releases it between d5 and e5, with the inner circle still on d5 | Give an immediate instruction. “Black's pawn is still on the board. To capture it, take it off the board. Otherwise put it back on d5.” Show Black a passive notice. The pawn stays where released and counts as touched. |
| Picks it up and releases its centre outside the board | Record the removal from d5. White can finish exd5 with the pawn from e4. Check legality and touch order as for an ordinary capture. |
| Returns it clearly to d5 | Clear the instruction. The original touch still counts. A normal capture onto d5 remains possible. |
| Releases it elsewhere on the board with the inner circle wholly clear of d5 | Apply the existing immediate opponent-piece ruling (A-007), including its restoration and escalation. |

**Proposed handling of the instruction.** White's clock keeps running. The release costs no step on either ladder. Pressing the clock with the pawn still unclear invokes the existing Q4 pause and counts once on the unclear-placement ladder. If White first places the e4 pawn on d5 while Black's pawn still straddles the line, keep both physical pieces and the instruction. White can finish by taking the black pawn off. Evaluate the finished capture once both pieces are accounted for. An earlier clear release by White still enters the release history. The server must never infer removal or checkmate from the black pawn's absence in the clear-square map.

**Board edge.** Use E-005's centre rule for the first version. The piece can hang partly outside and still be on the board. Once its centre is outside, removal is complete even if the artwork overlaps the rim. Requiring the entire image to clear the rim would change the previously agreed edge rule. The half-square example inside the board needs no such change.

### Two corrections to E-005

Both are taken over into E-005.

- **Three squares are possible.** E-005 said the inner circle lies on two squares, "or four at a corner". Near a corner it can cross both borders without reaching the fourth square: at 20% the radius is 0.22 of a square, and a centre 0.2 away from each of the two borders is 0.28 away from the corner. The squares to choose from are the squares the circle really lies on: one (at the edge of the board), two, three or four.
- **"The rules do not change" (requirement 4) holds only in part.** A clear placement is ruled on exactly as today. The binding of an unclear release, and the ruling without a restoration that enforces it, are new in the released-piece logic. Section 2.3 lists every change.

### Readings of E-005 that this plan relies on

Marked *(reading)* in E-005 and not confirmed one by one: the inner circle replaces the "sloppy" placement; the board cannot be used during the pause for a call; after adjusting without J'adoube the piece stays where it was shifted to; a click that does not shift the piece is no adjustment; an unused J'adoube ends with the clock press and the button does nothing while the game is paused. The plan takes all of them as written.

---

## 2. Design

### 2.1 The placement and the measure

- **Units**: a placement is the centre of a piece as two whole numbers, `x` along the files from a to h and `y` along the ranks from 1 to 8, with 1024 units to a square. The board is 8192 units wide. The numbers are the same for both players and do not depend on the size of the screen or on a turned board; each page converts them to its own pixels.
- **Radius**: the piece image is 56 of the 64 pixels of a square (`static/css/board.css`), 0.875 of a square. For a placement exactness of 20% the inner circle has a radius of 0.875 × √(0.20 / π) = 0.2208 of a square: **226 units**, 14.1 pixels on today's board. The server computes the radius once from the percentage and sends it to both pages.
- **Clear**: the centre is on the board and at least the radius away from every border of its square. A centre exactly the radius away is clear.
- **Unclear**: otherwise, with the centre on the board. The squares to choose from are the squares of the board the circle lies on.
- **Off the board**: the centre is outside the board. The piece goes to the side, as today.
- **One rule, two places**: the page needs the rule at once (capture, sound), the server decides. Both compute it in whole numbers, so they cannot differ by rounding. A file of sample points with the expected answer is read by a Java test and by a browser test, so the two stay the same.

### 2.2 Where the layer sits

Today the page keeps one piece per square, sends the square of every event and the whole position as square → piece with each event and each clock press, and the server rules on that. With free placement:

- **The server keeps the placements**: for every piece on the board its number, kind, `x` and `y` (`GameRoom`, in place of the reported position). It needs them to decide clear or unclear, to give a page that reloads the exact picture, to see a shift on a square, and to undo an adjustment.
- **Pieces on the board have a number.** A piece on no square cannot be named by a square. The server numbers the pieces whenever it sets up a position; the page that brings a piece in from the side takes the next free number, and the server refuses one in use.
- **The page sends what the hand did**: which piece, and where it was let go. The server works out the rest. The position the rules look at is derived from the placements: the squares of the clear pieces. At most one clear piece stands on a square, because a clear release on a square with a clear piece puts that piece to the side, as a capture does today.
- **The server turns each release into an event the rules know**:

| Before the pick-up | After the release | Event for the rules |
|---|---|---|
| clear on A | clear on A | `CLICK` (A), as today; the server notes whether the piece was shifted |
| clear on A | clear on B | `DRAG_MOVE` or `DRAG_CAPTURE`, as today |
| clear on A | off the board | `REMOVE`, as today |
| from the side | clear on B | `RESTORE_TO_EMPTY` or `RESTORE_TO_OCCUPIED`, as today |
| clear on A, or from the side | unclear | **new** `RELEASE_UNCLEAR`: the piece, the square it left (none from the side), the squares it lies on |
| unclear | clear on B | **new** `PLACE_AFTER_UNCLEAR`: the piece, the square it once left, B, the piece it displaced |
| unclear | unclear again | `RELEASE_UNCLEAR` again |
| unclear | off the board | `REMOVE` |

- **What the two new events mean to the rules**: `RELEASE_UNCLEAR` empties the square the piece left, like `REMOVE`, and is a touch of the piece on that square. `PLACE_AFTER_UNCLEAR` puts the piece on its square, like `RESTORE_*`, but carries the square it came from, so a move finished this way is recognised as that move and as a release (FIDE 4.7). A piece from the side keeps "no square", which is what marks a promotion today.
- Keep the original square, piece identity and first touch until an unclear piece is resolved. In particular, `RELEASE_UNCLEAR` of a victim must not enter the list of removed opponent pieces used by `MidPlayValidator`. A later `REMOVE` supplies its original square exactly once. Store draw order along with the placements so either player can reload an overlap and pick up the same top piece. Reconnecting while a piece is held returns it to the last acknowledged placement. A hover never changes that placement.
- Ordinary handling, an armed adjustment, and manual restoration already have different consequences for the same gesture. Dispatch by that context before applying Q4. An unsuccessful announced adjustment goes back to its prior placement. A piece positioned during manual restoration goes through the restoration check.
- **The rules never see a position with an unclear piece.** Every place that evaluates the position checks for unclear pieces first (2.4, 2.5).

### 2.3 What changes in the rules

With the option off, none of this is reached: no new event occurs and the server rules on the reported position as today.

1. **Two event types** in `BoardEventType`. Most `switch` statements over the type have no default branch, so the compiler names each one that must handle them (in `ArbiterEngine`, `TouchMoveEvaluator`, `FirstTouchInvariant` and `GameWebSocketServer`). The compiler does not find the two in `ArbiterEngine` that have a default branch (where a touched piece stands now, and the squares of an illegal move), nor the places that compare with one type by `if`: `MidPlayValidator`, `CastlingAttemptDetector`, `GameSession.recordEvent` and `MessageConverter`. These have to be read by hand.
2. **The binding of an unclear release** (`ArbiterEngine.ReleaseReplay`). *(proposal)* It is the union of what a clear release on each of the squares would have bound the player to at that moment, by the rules of today. This settles the special moves without rules of their own:
   - A king let go across f1 and g1 is bound to Kf1 and to castling, if both are legal.
   - A pawn let go across squares of the last rank is bound to nothing, because a pawn on the last rank is half a promotion today. The same holds for the square of an en passant capture.
   - A release that breaks the touch-move obligation in force, or the move owed after a rejected claim, binds to nothing, as today.
   - No square gives a legal move: bound to nothing (decided in E-005).
   - The first binding of the turn stands (Q3). The square the piece came from among the squares: bound to nothing (Q2).
3. **A clear release that breaks the binding commits to nothing.** The rook bound to d1 and e1 and then put on b1 has not made the move Rb1. This is the filter that exists today for a release that breaks touch-move.
4. **New rulings** (`ArbiterResponseType`): unclear placement, unclear placement with loss of the game, piece not on its bound squares, adjusting without J'adoube, the same with loss of the game. None of them restores a position, so `RestoreTargetInvariant` is not involved.
5. **New results** (`GameResultType`): loss by unclear placements, by J'adoube in the opponent's time, by adjusting without J'adoube.
6. **Unchanged**: legal moves, touch-move, the released-piece rule for clear releases, restoration, claims, offers, `FirstTouchInvariant`.

### 2.4 At the clock press

In `GameSession.ruleOnClockPress`, after the checks of today (game running, the player's turn, no pause, time not expired) and before the move owed after a rejected claim and the ordinary evaluation:

1. **A piece lies unclear**: the call. It counts on the ladder. The arbiter names the bound squares (Q1, Q3). Several unclear pieces are named in one call and count once *(proposal)*.
2. **A piece is bound and is not on one of its bound squares** (on another square, or off the board): the arbiter names the bound squares. The piece stays where it is. It does not count.
3. **Otherwise**: as today.

**The pause for a call** *(proposal)*: one mechanism for the three new calls (the two above and adjusting without J'adoube). The clock stops. The board is locked, as in the short pause after a restoration, because the owner decided that the player corrects on their own running clock. With *Continue clock without confirmation* the clock restarts after the arbiter's pause (`OTB_WRONG_CLOCK_PAUSE_MS`, 5 s); without it both players are asked whether they are ready. A press in the pause is not ruled on. The pause after a clock press without a move stays as it is; there the board remains usable by the specification, so it is not merged into this one.

**The ladders**, per player for the whole game:

| Ladder | A step is | Steps | Limit |
|---|---|---|---|
| Unclear placement | a clock press, or a draw offer with a move, while a piece lies unclear | call; call with warning before the last; loss of the game | entry-screen option, 3 by default, 1 to 10 or unlimited |
| J'adoube in the opponent's time | a press of the button while not on move | admonition, warning, loss | 3 |
| Adjusting without J'adoube | a piece shifted on its square after the move is made | admonition, warning, loss | 3 |

**Circles**: the bound squares are circled in **blue** from the call until the piece stands on one of them (decided by the owner on 2026-11-26; the proposal was green). Blue is the colour of a piece let go across a border; green stays the colour of touch-move and of the move that was already made.

### 2.5 Other moments that read the board

E-005 orders only the clock press. *(All proposals.)*

- **Automatic end of the game** (`evaluateForAutoEnd`): not while a piece lies unclear, and not for a move that breaks a binding.
- **Draw offer together with a move**: ruled on like a clock press. With an unclear piece the call is made, it counts, and the offer is dropped without a word, as for any offer with an invalid move today.
- **The move owed after a rejected claim**: the unclear call comes first, then the check of today.
- **Restoring a position by hand**: a piece let go unclear is simply not yet on its square. No call, no step on a ladder; the position counts as restored when every piece stands clearly where it belongs. **Revert** puts back, centred, only the pieces that do not stand clearly on their square of the position; every other piece stays exactly where it lies (decided by the owner on 2026-11-26: the arbiter moves only what has to be put back).
- **Claims**: they do not read the pieces on the board. Unchanged.

### 2.6 J'adoube

- **The button** is in the row of action buttons, only in a game with free placement, and can always be pressed.
- **Pressed in the own time**, no pause running, no piece held: armed. Both players read *"White: J'adoube."* The button is disabled until the adjustment is over.
- **The adjustment**: the next piece on the board the player picks up, their own or the opponent's. The page sends it as an adjustment, not as a board event. The server:
  - accepts it if the piece stood clearly on a square and is let go clearly on the same square; the new placement goes to both pages;
  - otherwise undoes it: both pages put the piece back where it lay. No penalty.
  - Either way J'adoube is used up.
- **It is not a touch**: the adjustment is not added to the turn's events. So it creates no touch-move obligation, does not end the right to accept a draw offer, and does not end the right to claim (the page sets "touched this turn" for a board event, not for an adjustment).
- **A piece that lies unclear cannot be adjusted** *(proposal)*: it stands on no square, so the adjustment is always undone. It has to be picked up without J'adoube to finish the move.
- **Pieces at the side are not affected**: picking one up while J'adoube is armed is the ordinary handling, and J'adoube stays armed.
- **Unused**, it ends with the clock press and with any ruling that pauses the game.
- **Pressed in the opponent's time**: nothing is armed; the ladder of 2.4. No pause.
- **Adjusting without J'adoube, after the move is made**: the move is made once a legal move is latched for the turn (`finalMoveCommitment`, as today). From then on a piece that is picked up and let go clearly on its own square at another spot is an adjustment without J'adoube: the call at once, the pause, the ladder. The piece stays where it was shifted to. Before the move is made the same handling is a touch, as today, and the piece stays shifted.
- **A click never shifts a piece** *(proposal)*: a pick-up moves the piece only once the mouse has travelled more than 1/16 of a square (4 pixels today). Below that the piece is set down exactly where it was. "Shifted" then simply means that the placement changed, and a trembling hand on a click cannot cost a game.

### 2.7 Messages between page and server

| Message | Change |
|---|---|
| `createGame` | `freePlacement` (true or false) and `maxUnclearPlacements` (1 to 10, or −1 for unlimited). **A message without `freePlacement` creates a game of today**, so every existing server test and tool stays on the old path without an edit; the entry screen sends `true` by default. |
| `gameCreated`, `gameJoined`, `gameStarted`, `resync`, `rematchStarted`, `boardUpdate`, `positionRestored` | in a game with free placement: the placements with their numbers, and once the radius |
| `boardEvent` | in a game with free placement: the number of the piece and where it was let go. The server derives the event of 2.2, and refuses coordinates that are no whole numbers in range and piece numbers it does not know, counted against the connection like other malformed messages |
| `opponentBoardEvent` | carries the placement, so the piece lies on the opponent's board exactly where it was let go |
| `clockPress`, `offerDraw` | the server uses its own placements; the reported position is not used in a game with free placement |
| new `jadoube` | the button |
| new `adjust` | the adjustment of 2.6 |
| new to the pages | J'adoube announced, adjustment undone, and the rulings of 2.3 through the existing path for arbiter responses |

The piece in the opponent's hand is shown as today, at the centre of the square the mouse is over; only the placement after the release is exact *(proposal: unchanged in the first version)*.

A rematch keeps the two options, like the other options of the game.

### 2.8 The page

- **A second way to draw the board** (`board.js`): the pieces are no longer children of their square but lie in one layer over the 64 squares, positioned in parts of the board, so the layer scales and turns with the board. The later piece is drawn on top.
- **Picking up**: the piece whose image is under the mouse, the top one first. The piece is held where it was grabbed, not by its centre, so it does not jump on the pick-up and the placement after the release is where the image was let go.
- **Side areas**: a piece that lies unclear counts as on the board, like a piece in the hand today.
- **Sound** *(proposal)*: the piece sound for every release on the board that changes where the piece lies, clear or unclear, also for an adjustment; not for a click.
- **Entry screen**: *Free placement of the pieces* (checkbox, on) and *Number of unclear placements causing game loss* (shown only with the checkbox on), in the form of the existing options.
- **The J'adoube button** and the new messages.
- A clear piece keeps a `data-square`, so the existing browser tests find it as before.

### 2.9 With the option off

The page draws the board as today, sends the messages of today, and shows no J'adoube button. The server takes the old path. `RuleFuzzer`, `TouchMoveOracle` and the Java suite never send `freePlacement`, so their passing unchanged is the proof that nothing moved.

---

## 3. Phases

Each phase ends with its tests green, committed and pushed on `jadoube`. Nothing reaches `main` before the release PR.

| Phase | Content | Done when |
|---|---|---|
| **0. Decisions** | The owner answers Q1 to Q4. E-005 is brought up to date (three squares, requirement 4, the answers). | **Done on 2026-11-25**: E-005 has no open ruling. The proposals of section 2 go to the review. |
| **1. The measure** | Units, radius, clear / unclear / off the board, the squares to choose from; in Java and in the page; the shared file of sample points. | Both read the file and agree, including corners with three squares, the edge of the board and the exact limit. |
| **2. The rules** | The two events, the binding, the two rulings at the clock press, the ladder with its option, the pause for a call, the guards of 2.5. Cover each ruling in the session and in its playable journey. | Section 4, Java tests for the rules. The old suites pass unchanged. |
| **3. The server** | Options at `createGame`, placements in the room, deriving the events, the messages of 2.7 without J'adoube, reload and rematch. | Section 4, server tests. |
| **4. The page** | The layer of pieces, picking up and letting go, both boards, side areas, circles, sounds, messages, entry screen. Exercise free placement through an explicit game option while building it. | New browser tests for free placement; the whole existing browser suite passes with free placement on. |
| **5. J'adoube** | Button, arming, adjustment and undo, the ladder for the opponent's time, adjusting without J'adoube. Rules, server and page together. | Section 4, the J'adoube tests at both levels. |
| **6. Documents and release** | Section 5; full verification; manual test on Windows through `start.bat` (default branch `jadoube`). | By `RELEASING.md`. |

**Reviewed delivery order.** Use phases 2–4 as a list of components, and deliver them together for one playable case at a time. Start with the capture-first journey in Q4, from mouse release through the session and back to both boards. This exercises the distinction between held, on the board and removed before adding the binding for the player's own pieces. Keep the lobby default on the current game during development. Enable free placement by default once the rulings and J'adoube work together and the full suite passes. Each playable change ships with its Java and Playwright regression in the same commit.

---

## 4. Tests

By the project's policy every change of behaviour has Java tests and browser tests in the same commit.

**Java**

- **The measure**: the sample file; the radius for 10%, 20% and 30%.
- **Deriving the events**: every row of the table in 2.2; one clear piece per square; a clear release next to an unclear piece removes nothing.
- **Capture-first sequence**: White pawn e4, black pawn d5, White to move. Release the victim on the d5/e5 boundary. Assert the immediate instruction, original touch, active clock, zero penalties and both pieces still in play. Branch from there to off-board removal followed by exd5, return followed by a direct capture, an unresolved clock press (one count), and displacement beyond d5 (A-007 only). Repeat with the capturer set on d5 before the victim is removed. Check the final position, move history and turn, including when the original touch has no legal capture.
- **The binding**: the rook across d1 and e1 (E-005); the bishop that can reach neither square; only one of the squares legal; the square the piece came from among the squares; a second unclear release; with a touch-move obligation in force; with a move owed after a rejected claim; the king across f1 and g1; a pawn across squares of the last rank; the square of an en passant capture; a promoted piece from the side let go unclear.
- **The clock press**: the order of 2.4; the ladder with limits 1, 3 and unlimited; the warning before the last step; the pause with and without automatic resume; a press in the pause; the bound piece on another square, at every press, without counting; no automatic end with an unclear piece; the draw offer with an unclear piece.
- **J'adoube**: armed only in the own time, not in a pause, not while a piece is held; own and opponent's piece; undone on another square, unclear, off the board and for an unclear piece; no touch-move obligation, a pending draw offer stays acceptable, the right to claim stays; unused, it ends at the clock press; the ladder in the opponent's time.
- **Adjusting without J'adoube**: the moved piece, another own piece, an opponent's piece; before the move it is a touch; a click is nothing; the ladder.
- **Server**: the options and their limits; a message without `freePlacement` gives a game of today; malformed coordinates and unknown piece numbers; a reload in the middle of a turn returns every placement, the unclear ones too; a rematch keeps the options and starts centred.
- **`RuleFuzzer`**, a second mode with free placement: random releases, clear and unclear, and J'adoube. It keeps its checks, above all that what the arbiter told the player to do is then accepted (the check behind Q1). New: a game in which every release is clear gets the same rulings as the same game without free placement.
- **`TouchMoveOracle`**: unchanged, and run once more through the layer with centred placements.

**Browser** (Playwright; a new helper lets go of a piece at a given part of a square)

- A piece stays where it is let go, on both boards, on a turned board, and after a reload.
- A clear move off the centre is accepted. An unclear one: the call on both boards, the pause, the correction on the running clock, the move accepted.
- The ladder up to the loss of the game, with the option at 1 and at 3.
- The piece bound to two squares and put on a third.
- A capture by a clear release; an unclear release over an occupied square removes nothing and the pieces overlap.
- Start from the normal position, play 1.e4 d5 and replay White's capture with real mouse events while observing both boards. Hold the black pawn halfway, verify silence, release on the d5/e5 boundary, and verify the instruction, Black's notice, both clocks' turn indicators and an empty captured-pawn tray. Reload Black's board while the pawn straddles the line. Finish exd5 by taking the same pawn off first and placing White's pawn on d5. Verify one captured black pawn, acceptance and Black to move on both boards. Repeat the unresolved clock press and correction, and check the ordinary off-board-first capture with the option disabled. Include a rim drop with the centre just inside and just outside.
- Checkmate ends the game on a clear release and not on an unclear one.
- Revert centres the pieces it puts back and leaves the others where they lie.
- J'adoube: the journeys of E-005 one by one, including the adjustment that is undone and the three ladders.
- Entry screen: both options; with free placement off there is no J'adoube button and a piece is centred.
- **The existing suite** runs with free placement on, because that is what players get; its drags go from centre to centre and are clear. A second Playwright project runs a part of it with the option off (`smoke`, `rules`, `touch-move`, `released-piece-commitment`, `promotion`) *(proposal)*.

Windows-only launcher tests are not touched by this work.

---

## 5. Documents

- `SPECIFICATION.md`: a section on free placement; changes to the start screen, the board events, the capture mechanics, the mirroring of the opponent's hand, the released-piece rule (the binding), the evaluation at the clock press (the two rulings before it) and the automatic end.
- `docs/fide-deviations.md`: D-004 becomes the path taken, with what remains of the deviation; new entries for the ruling on unclear placement and for adjusting without J'adoube, as E-005 announces.
- `MANUAL.md`, `CHANGELOG.md`, the status of E-005, `tasks.md`.
- `docs/design-principles.md`: the calls name squares and ask for a placement. They react to a violation, which P-003 allows; a line there says so.

---

## 6. Risks

- **The measure exists twice**, in Java and in the page. Held together by whole-number arithmetic and the shared sample file.
- **The old game must not move.** Held by the missing-field rule of 2.7, the untouched suites, the fuzzer's comparison of both modes, and the second Playwright project.
- **The existing browser tests on a board drawn differently.** The pieces leave their squares in the page's structure; the helpers find pieces through the square. Phase 4 is not done before the whole suite passes.
- **A fourth kind of pause.** The game already has the pause for a restoration, after a press of the opponent's clock and after a press without a move, each with its own bookkeeping. The pause for a call is built once for the three new calls and must give way to the others in the same way they give way to each other today.
- **Q1 and the fuzzer.** The arbiter names only squares that give a legal move, so the fuzzer's check "what the arbiter said is accepted" holds for the new calls without an exception. The call that names no square gives the player nothing to follow; the fuzzer checks there that a legal move with the piece is accepted.

---

## 7. Not part of this plan

As in E-005: playing by touch on a phone, and any change to the rulings that exist today. Also not planned: showing the piece in the opponent's hand more exactly than by square, and a limit on J'adoube in the own time.

---

## 8. State of the implementation

**Built on 2026-11-26**: the first playable case (capture-first), then the binding of an own piece let go unclearly (Q1 to Q3) with the second ruling at the clock press and the circles (blue since the owner's decision of the same day), then J'adoube with its two ladders. Free placement is a game option on the entry screen and **on by default** since the binding was built (the owner's decision of 2026-11-26, ahead of the order of the review, to make testing easier). The version in development is shown as `v0.6.0 beta`.

| Part | Where |
|---|---|
| The measure (2.1) | `PlacementMeasure`, `static/js/placement.js`, shared samples in `tests/placement-samples.json` |
| The placements and the layer (2.2) | `PlacementBoard`, kept by `GameRoom`; `GameWebSocketServer.handleFreeBoardEvent` |
| The binding of an unclear release (2.3) | `ArbiterEngine.findUnclearBinding` (in `ReleaseReplay`), `BoardEvent.releasedOn` |
| The two calls at the clock press, the ladder and the pause (2.4) | `GameSession.ruleOnPlacement`; option `maxUnclearPlacements` |
| The circles of the bound squares | `GameSession.getBoardMarks`, `BoardMark.Color.BLUE` |
| A restored position (2.5) | `PlacementBoard.arrange` |
| The half-removed victim (Q4, capture-first) | `PlacementBoard.halfRemovedPiece`, message `placementNotice` |
| J'adoube (2.6) | `GameSession.pressJadoube`, `useJadoube`, `ruleOnAdjustingWithoutJadoube`; `PlacementBoard.adjust`; messages `jadoube`, `jadoubeWrongTime`, `adjustmentUndone` |
| The page (2.8) | `board.js` (layer of pieces), `game.js`, entry screen |
| Tests | `TestPlacementMeasure`, `TestPlacementBoard`, `TestUnclearBinding`, `TestUnclearBindingRulings`, `TestJadoube`, `TestFreePlacementServer`, `tests/e2e/free-placement.spec.ts`, `tests/e2e/jadoube.spec.ts` |

**Where the code differs from sections 2.2 to 2.4**

- **No new event type.** The review asked that a half-removed victim never looks removed. So a piece let go unclearly *keeps counting on the square it stood on*: for the rules it was touched there (a `CLICK`) and is still on the board. Placed clearly afterwards it gives the event of today from that square (`DRAG_MOVE`, `DRAG_CAPTURE`), taken off it gives `REMOVE`, put back it gives nothing more. The rules therefore see no square as empty because its piece lies across a border. For an own piece the `CLICK` also carries the squares it was let go across (`BoardEvent.releasedOn`): everything that treats a click as a touch stays as it is, and only the binding reads the squares. `RELEASE_UNCLEAR` and `PLACE_AFTER_UNCLEAR` were not needed.
- **The binding** is computed where the released-piece rule is (`ReleaseReplay`), from what a clear release on each of the squares would commit to at that moment, as proposed in 2.3. In addition a square binds when the piece set down there would complete a legal move although it did not come straight from its square of the turn start (a piece that was first put elsewhere). The binding is in force until a clear release honours it; from then on the released-piece rule of today decides, with no change to it. A clear release that breaks the binding commits to nothing, a move that breaks it is not latched as made and ends no game.
- **A binding ends with the rule window.** A restoration after an illegal move or a touch-move ruling forgets the releases of the turn today; it forgets the binding with them. Touch-move remains.
- **A piece under a capturer.** A piece placed clearly on a square whose piece lies unclear captures it for the rules (`DRAG_CAPTURE`), while that piece stays where it lies and counts on no square. Taking it off is then no event; the game can end only once it is off.
- **An opponent's piece carried wholly off its square and let go unclearly** is given to the rules as moved to the square its centre is on, so the ruling of A-007 follows at once.
- **The pause for a call** uses the mechanism of the pause after a clock press without a move, with the board locked in addition. Both calls about a placement use it and share the response type `UNCLEAR_PLACEMENT`; only the first counts. A release that reaches the server in the pause is not applied, and the page is sent where the pieces lie (`placementsSync`).
- **The page sends no position** in a game with free placement: `clockPress` and `offerDraw` are ruled on the server's own record.

**J'adoube as built** (2.6, with these details)

- **No message of its own for the adjustment.** The page sends the release as always; the server knows that J'adoube is said and treats the next piece of the board that is let go as the adjusted one. A new message is only the button (`jadoube`).
- **The button on both pages.** While J'adoube is said and not used, the button is disabled for both players, so the opponent cannot step on the ladder for the opponent's time in that moment. The pages are told when it ends (`jadoube` with `armed: false`): used, the clock press, or a ruling that pauses the game.
- **Undone**: the player reads *"The adjustment is undone: an adjusted piece stays on its square."*, or for a piece that lay unclear *"… a piece that is not clearly on a square cannot be adjusted."* The opponent's board puts the piece back without a word.
- **Adjusting without J'adoube** is seen by the placement layer: a piece that stood clearly on a square and is set down clearly on the same square at another spot (`Release.shifted`). It is still recorded as a touch, as today, and once the move is made the call follows at once. The call uses the pause for a call about a placement.
- **The texts of the two ladders**: *"You can only adjust pieces in your own time."* and *"You cannot adjust pieces without saying J'adoube."*, the second time with *"Warning: the next time you lose the game."*; the opponent reads *"Your opponent said J'adoube in your time."* and *"Your opponent adjusted a piece without saying J'adoube."*
- **In a pause** the button does nothing for either player, and costs the opponent no step.

**The wording of the calls** *(the writer's, where E-005 gives no text)*

- Bound, lying where it was let go: *"It is not clear on which square your rook is. Place it on d1 or on e1, and place your pieces more carefully."* With one bound square: *"Place it on d1, and place your pieces more carefully."*
- Bound by an earlier release and lying elsewhere now: *"It is not clear on which square your rook is. It was released on d1 and e1. Place it on d1 or on e1. Place your pieces more carefully."* The reminder at the end is said in every call for an unclear placement; E-005's example leaves it out.
- **Only the bound squares are named, also in "It was released on …"** (Q1). A rook let go across d1 and e1 that can only go to d1 gets *"Your rook was released on d1. Place it on d1."* The sentence then names less than where the piece lay.
- Released clearly before (FIDE 4.7) and picked up again: *"It is not clear on which square your rook is. It was released on d1. Place it on d1. Place your pieces more carefully."*
- Several unclear pieces: one sentence pair per piece, then the reminder once; the call counts once.
- The second ruling, for the opponent: *"Your opponent's rook was released on d1 and e1. It has to be placed on one of them."*

**Known gaps**

- A piece from the side (a promoted piece) let go unclearly counts on no square and is bound to nothing; the call names no square for it.
- A page that is reloaded inside the short pause for a call has a usable board until the clock restarts; the server refuses what it does and sends the placements back.
- Three browser tests that send the frames of the centred game by script (`cloud-review`, `final-pass`, `midplay-expiry`) run with the option off.

**Not built**: the free-placement mode of `RuleFuzzer`, and the documents of section 5.
