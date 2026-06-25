# Tasks

Live-planning source of truth. Mark items done in place (check, don't delete); keep an unshipped
release's tasks until it ships.

## Publication prep

- [x] Remove the testing-only `/api/lastGameId` endpoint and lobby join-code prefill so multiple games can be created and joined independently on a public server.
- [ ] Add privacy-conscious game logging for publication: enough to understand whether the app is used, without collecting unnecessary player data. Candidate fields: game start/end time, move count, result, and rule-violation counts.
- [x] Open-source licensing: GPL-3.0-only `LICENSE`, two-line SPDX headers on every Java file via `tools/java-license-headers.ps1` (`-Check`/`-Fix`, ported from ashlar-chess), enforced in the build by `TestLicenseHeaders`. License + ashlar-chess attribution declared in `pom.xml` and `README.md`. Copyright year 2026 (project inception). Headers cover Java only (TS/JS excluded, as in ashlar-chess).

## Current release — migrate to ashlar-chess 19.0.0

Bump otb-chess from ashlar-chess **18.1.0 → 19.0.0**. Branch: `migrate-to-ashlar-chess-19.0.0`.

**Nature of 19.0.0:** surface-only — *no rule/parser/analysis/adjudication behavior changes* (every
verdict and report is identical to 18.1.0). Heavily binary-incompatible but mechanical: type renames
and `Type.method(x)` → `TypeUtility.method(x)` / dropped `calculate`/`get` prefixes. **Let the
compiler drive:** bump the version, `mvn compile`, fix each error against the 19.0.0 CHANGELOG
breaking list (`C:\Users\danie\git\ashlar-chess\CHANGELOG.md`, `## [19.0.0]`).

### Step 1 — bump the dependency
- [x] `pom.xml`: ashlar-chess `18.1.0` → `19.0.0`. Resolved straight from Maven Central — no local `mvn install` needed.

### Step 2 — fix the breaking changes (otb-chess's ashlar API surface, mapped to 19.0.0 renames)
- [x] **`new Board(String fen)` REMOVED** → `Board.fromFenStrict(String)`. Sites: `OtbChessServer.validateFenResponse`, `GameWebSocketServer.handleCreateGame`, and many tests. `handleCreateGame` catch: the strict-FEN exceptions are `StrictFenFieldValidationException` (package-private, **uncatchable by name**) / `StrictFenSemanticValidationException`, both `extends UsageException → RuntimeException`. The old specific catch and the `RuntimeException` fallback did the **identical** thing (surface "Invalid FEN"), so collapsed to a single `catch (RuntimeException)`. `validateFenResponse` catches `Exception` — unchanged, safe.
- [x] **`Board.getHavingMove()` → `getSideToMove()`** — ashlar `board.*` calls only (ArbiterEngine, GameSession, CastlingAttemptDetector, GameWebSocketServer). otb-chess's **own** `GameSession.getHavingMove()` and the `"havingMove"` JSON property are deliberately kept (client wire-protocol vocabulary).
- [x] **`LegalMove.havingMove()` → `movingSide()`**; **`LegalMove.pieceCaptured()` → `capturedPiece()`**. Sites: ArbiterEngine, CastlingAttemptDetector, PositionComparator, TouchMoveEvaluator, GameWebSocketServer.sendArbiterResponse; tests TestPositionComparator/TestArbiterEngineEdgeCases.
- [x] **`CastlingUtility.calculateIsCastlingMove(spec)` → `isCastlingMove(spec)`**. `calculateKingCastlingFrom/To` confirmed unchanged (still on `CastlingUtility`).
- [x] **`PgnCreate.createPgnString(board)` → `toPgnString(board)`** (`GameSession.exportPgn`).
- [x] **`getPerformedHalfMoveCount` / `HalfMove` / `getHalfMoveList`** — not used anywhere in otb-chess (grep clean). Nothing to do.
- [x] **Protocol caveat:** verified `MessageConverter` uses `Piece.name()` / `position.get(sq).name()` / `Piece.valueOf(...)` / `square.getName()` — **never `toString()`**. Wire format safe. (Plus the extra compiler-surfaced rename `Square.calculate(String)` → `Square.parse(String)` in MessageConverter.)
- [x] Confirmed no direct `isUnwinnableQuick/Full(Side)` calls; `canClaim*`, `MoveSpecification.*`, `BitboardPosition.afterMove/get` etc. unchanged — left alone.
- [x] **Extra renames the compiler surfaced (not in the changelog's spelled-out list / CHANGELOG was inexact):**
  - `Piece.calculateKingPiece(Side)` / `calculateRookPiece(Side)` / `calculate(Side, PieceType)` → **`Piece.of(Side, PieceType.X)`**. (The CHANGELOG said "consolidated into `PieceUtility`", but the polish pass returned the factory to the enum as `Piece.of` — `PieceUtility` does not exist in the jar.) Sites: ArbiterEngine, CastlingAttemptDetector, TouchMoveEvaluator (added `import …board.enums.PieceType`).
  - `Square.calculateKingSideRookOriginalSquare(Side)` / `calculateQueenSideRookOriginalSquare(Side)` → moved to **`SquareUtility`** (same method names). Sites: CastlingAttemptDetector, TouchMoveEvaluator (added `import …board.enums.SquareUtility`).
  - `LenientSanParser.parseText(String, Board)` → **`parse(...)`** (returns `LenientSanParseResult`; `.moveSpecification()` accessor unchanged). Site: `DrawClaimManager`.

### Step 3 — verify
- [x] Killed leftover servers on 8080/8081 first.
- [x] `mvn clean test` → **139/139** ✅
- [x] `npm run e2e` → **62 passed** ✅ (env had no `node_modules`; ran `npm install` + `npx playwright install chromium` first, then the suite).

### Notes
- Workflow: commit locally per verified change; push when the feature is complete (reviewed on the remote); PRs only when asked.
- Don't regress the recent UX: end-of-game / draw messages are personalised per player ("you" vs "your opponent") from `gameEnded` (`mover`/`actor`/`drawReason`). Principle: minimal info during play, clear "who did what" on results.
