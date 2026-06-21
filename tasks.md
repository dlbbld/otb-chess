# Tasks

Live-planning source of truth. Mark items done in place (check, don't delete); keep an unshipped
release's tasks until it ships.

## Current release — migrate to ashlar-chess 19.0.0

Bump otb-chess from ashlar-chess **18.1.0 → 19.0.0**. Branch: `migrate-to-ashlar-chess-19.0.0`.

**Nature of 19.0.0:** surface-only — *no rule/parser/analysis/adjudication behavior changes* (every
verdict and report is identical to 18.1.0). Heavily binary-incompatible but mechanical: type renames
and `Type.method(x)` → `TypeUtility.method(x)` / dropped `calculate`/`get` prefixes. **Let the
compiler drive:** bump the version, `mvn compile`, fix each error against the 19.0.0 CHANGELOG
breaking list (`C:\Users\danie\git\ashlar-chess\CHANGELOG.md`, `## [19.0.0]`).

### Step 1 — bump the dependency
- [ ] `pom.xml`: ashlar-chess `<version>18.1.0</version>` → `19.0.0`. No `<repositories>` block today (Maven Central). If it doesn't resolve, `mvn install` ashlar-chess 19.0.0 from `C:\Users\danie\git\ashlar-chess` into the local `.m2`. (That working copy may be on a 20.0.0 branch — trust the **19.0.0 CHANGELOG**, not its live source, for the 19.0.0 API.)

### Step 2 — fix the breaking changes (otb-chess's ashlar API surface, mapped to 19.0.0 renames)
- [ ] **`new Board(String fen)` REMOVED** → `Board.fromFenStrict(String)` (strict — what start-screen FEN validation wants). `new Board()` (initial) and `new Board(Fen)` remain. Sites: `OtbChessServer.validateFenResponse`, `GameWebSocketServer.handleCreateGame`, and **many tests** (`new Board("…fen…")`). Verify what `fromFenStrict` throws and update the `catch` in `handleCreateGame` (currently `FenAdvancedValidationException | FenRawValidationException`); `validateFenResponse` catches `Exception` so it's safe.
- [ ] **`Board.getHavingMove()` → `getSideToMove()`** (side-to-move vocabulary). Many sites: `GameSession`, `GameWebSocketServer` (`sendGameEnded` mover, `sendArbiterResponse` opponentMoved, `sendBoardUpdate`, …).
- [ ] **`LegalMove.havingMove()` → `movingSide()`**; **`LegalMove.pieceCaptured()` → `capturedPiece()`**. Site: `GameWebSocketServer.sendArbiterResponse` (`move.havingMove()`).
- [ ] **`CastlingUtility.calculateIsCastlingMove(spec)` → `isCastlingMove(spec)`** (boolean `calculate`-prefix drop). Verify the other CastlingUtility methods used (`calculateKingCastlingFrom/To`) — may have changed in the data-carrier pass.
- [ ] **`PgnCreate.createPgnString(board)`** — PGN export moved to the `to*` idiom; verify the new name. Site: `GameSession.exportPgn`.
- [ ] **`getPerformedHalfMoveCount()` → `getPerformedMoveCount()`**, and `HalfMove` / `getHalfMoveList()` are gone — grep otb-chess (`BitboardPositions`, `GameSession`) and fix if used.
- [ ] **Protocol caveat (cosmetic `toString()` change):** `Square.toString()` now → `a1`, `Piece.toString()` → FEN letter. **Verify `MessageConverter` (JSON board protocol) uses `Piece.name()` / `Square.getName()` (explicit accessors, unchanged), NOT `toString()`** — else the client wire format silently breaks. `getName()`/`getLetter()`/`getNumber()` unchanged.
- [ ] Confirm direct `isUnwinnableQuick/Full(Side)` aren't called (otb-chess uses `Adjudicator`, which is unchanged; `board.isInsufficientMaterial()/(Side)` unchanged). Methods not in the changelog breaking list (`canClaim*`, `MoveSpecification.promotionPieceType()/castlingMove()/toSquare()`, `LenientSanParser.parseText`, `BitboardPosition.afterMove/get/withRelocatedPiece`) are unchanged — leave them.

### Step 3 — verify
- [ ] Kill leftover servers first (they break Playwright via `reuseExistingServer`): `for p in 8080 8081; do pid=$(netstat -ano | grep LISTENING | grep ":$p " | awk '{print $5}' | head -1); [ -n "$pid" ] && taskkill //F //PID $pid; done`
- [ ] `mvn clean test` → expect **139/139** (online first run for the 19.0.0 fetch; `-o` after).
- [ ] `npm run e2e` → expect **62 passed** (builds `target/otb-chess.jar`, Playwright runs the server). On a stale class-version error, `mvn clean`.

### Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
