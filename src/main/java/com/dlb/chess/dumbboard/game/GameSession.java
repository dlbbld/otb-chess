package com.dlb.chess.dumbboard.game;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.dlb.chess.board.Board;
import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.common.model.MoveSpecification;
import com.dlb.chess.dumbboard.arbiter.ArbiterEngine;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponseType;
import com.dlb.chess.dumbboard.arbiter.MidPlayValidator;
import com.dlb.chess.dumbboard.event.ActionSequence;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.game.model.DrawClaimResult;
import com.dlb.chess.dumbboard.game.model.DrawClaimType;
import com.dlb.chess.dumbboard.game.model.GameResult;
import com.dlb.chess.dumbboard.game.model.GameResultType;
import com.dlb.chess.dumbboard.game.model.GameState;
import com.dlb.chess.dumbboard.game.model.TimeControl;
import com.dlb.chess.pgn.create.PgnCreate;
import com.dlb.chess.unwinnability.quick.enums.UnwinnableQuick;

/**
 * Central orchestrator for a dumb chessboard game.
 *
 * <p>Holds the game state and coordinates all managers: clock, arbiter, draw offers, draw claims.
 *
 * <p>Thread safety: methods are synchronized because WebSocket messages arrive from different threads.
 */
public class GameSession {

  private final Board board;
  private final ClockManager clock;
  private final ArbiterEngine arbiter;
  private final DrawOfferManager drawOfferManager;
  private final DrawClaimManager drawClaimManager;
  private final TimeControl timeControl;
  private final boolean autoResumeAfterRestore;

  private GameState state;
  private GameResult result;

  // Per-turn state
  private ActionSequence currentSequence;
  private StaticPosition positionBeforeTurn;
  private final Set<Square> removedSquaresThisTurn;

  // State for "must execute specified move" after rejected draw claim
  private MoveSpecification mustExecuteMove;

  // Ready-to-continue tracking (both players must click after arbiter intervention)
  private boolean waitingForReady;
  private boolean whiteReady;
  private boolean blackReady;
  private boolean waitingForRestoration;
  private boolean restorationResumePending;
  private StaticPosition restorationTargetPosition;

  public GameSession(TimeControl timeControl) {
    this(timeControl, com.dlb.chess.dumbboard.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES);
  }

  public GameSession(TimeControl timeControl, int maxIllegalMoves) {
    this(timeControl, maxIllegalMoves, true);
  }

  public GameSession(TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore) {
    this.board = new Board();
    this.clock = new ClockManager(timeControl);
    this.arbiter = new ArbiterEngine(maxIllegalMoves);
    this.drawOfferManager = new DrawOfferManager();
    this.drawClaimManager = new DrawClaimManager();
    this.timeControl = timeControl;
    this.autoResumeAfterRestore = autoResumeAfterRestore;

    this.state = GameState.WAITING_FOR_PLAYERS;
    this.result = null;
    this.currentSequence = new ActionSequence(Side.WHITE);
    this.positionBeforeTurn = board.getStaticPosition();
    this.removedSquaresThisTurn = new HashSet<>();
    this.mustExecuteMove = null;
    this.waitingForReady = false;
    this.whiteReady = false;
    this.blackReady = false;
    this.waitingForRestoration = false;
    this.restorationResumePending = false;
    this.restorationTargetPosition = positionBeforeTurn;
  }

  /**
   * Starts the game. Both players are connected.
   */
  public synchronized void startGame() {
    this.state = GameState.IN_PROGRESS;
    this.clock.startClock(Side.WHITE);
  }

  // ===== Mid-play event recording =====

  /**
   * Records a board event during play. Returns an arbiter response if mid-play intervention is needed.
   */
  public synchronized Optional<ArbiterResponse> recordEvent(Side side, BoardEvent event) {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }
    if (side != board.getHavingMove()) {
      return Optional.empty();
    }

    // Check mid-play validation
    final Optional<ArbiterResponse> midPlayResponse = MidPlayValidator.validate(event, side, positionBeforeTurn,
        removedSquaresThisTurn);
    if (midPlayResponse.isPresent()) {
      clock.stopClock();
      return midPlayResponse;
    }

    // Track removed opponent pieces
    if (event.type() == com.dlb.chess.dumbboard.event.BoardEventType.REMOVE
        || event.type() == com.dlb.chess.dumbboard.event.BoardEventType.DRAG_CAPTURE) {
      final Square removedSquare = event.type() == com.dlb.chess.dumbboard.event.BoardEventType.REMOVE
          ? event.square()
          : event.targetSquare();
      if (event.displacedPiece() != com.dlb.chess.board.enums.Piece.NONE
          && event.displacedPiece().getSide() != side) {
        removedSquaresThisTurn.add(removedSquare);
      }
    }

    currentSequence.addEvent(event);
    return Optional.empty();
  }

  // ===== Clock press =====

  /**
   * Player presses the clock. Triggers move evaluation.
   *
   * @param side          the side pressing the clock
   * @param afterPosition the current board state as seen on the physical board
   * @return the arbiter response
   */
  public synchronized ArbiterResponse pressClockButton(Side side, StaticPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return ArbiterResponse.incompleteMove("The game is not in progress.");
    }
    if (side != board.getHavingMove()) {
      return ArbiterResponse.incompleteMove("It is not your turn.");
    }

    // Special case: must execute specified move (after rejected draw claim)
    if (mustExecuteMove != null) {
      return evaluateMustExecuteMove(afterPosition);
    }

    // Normal evaluation
    final ArbiterResponse response = arbiter.evaluateClockPress(board, afterPosition, currentSequence);

    return handleArbiterResponse(response, side, false);
  }

  /**
   * Triggered after each board event during play. If the current physical position corresponds
   * to a legal move that immediately ends the game (checkmate, stalemate, dead position,
   * fivefold repetition, 75-move rule), the move is accepted and the game is ended without
   * waiting for a clock press. For any non-ending move, this returns empty and the player must
   * still press the clock as usual.
   *
   * <p>This must NOT have side effects on the illegal-move counter — intermediate positions
   * during piece manipulation are not "moves" and must not be recorded as illegal. Only the
   * clock press (or an offered draw, etc.) goes through the full {@link ArbiterEngine}
   * evaluation that records illegal moves.
   */
  public synchronized Optional<ArbiterResponse> evaluateForAutoEnd(Side side, StaticPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }
    if (side != board.getHavingMove()) {
      return Optional.empty();
    }
    // Skip during the patient-loop recovery from a rejected draw claim — that path requires the
    // mustExecuteMove flow at clock press, not auto-end.
    if (mustExecuteMove != null) {
      return Optional.empty();
    }

    // Position match — pure read, no side effects. Most intermediate positions during piece
    // manipulation will produce no match and we exit immediately.
    final java.util.Set<com.dlb.chess.model.LegalMove> matchingMoves =
        com.dlb.chess.dumbboard.core.PositionComparator.findMatchingMoves(board, afterPosition);
    if (matchingMoves.isEmpty()) {
      return Optional.empty();
    }
    final com.dlb.chess.model.LegalMove matchedMove = matchingMoves.iterator().next();

    // Released-piece guard (FIDE 4.7): if the player has already committed a release in this
    // turn and the current physical position is NOT one of the committed move's allowed final
    // positions, the player must not auto-finish a different move. The clock-press flow will
    // produce a RELEASED_PIECE_VIOLATION and the standard restoration recovery handles it.
    if (arbiter.hasReleasedPieceViolation(board, afterPosition, currentSequence)) {
      return Optional.empty();
    }

    // Touch-move check (also pure read). If the matched move would violate touch-move, leave
    // detection to the clock press so the existing arbiter feedback flow handles it.
    final java.util.Optional<com.dlb.chess.dumbboard.touchmove.TouchMoveObligation> obligation =
        com.dlb.chess.dumbboard.touchmove.TouchMoveEvaluator.findObligation(currentSequence, board);
    if (obligation.isPresent()
        && !com.dlb.chess.dumbboard.touchmove.TouchMoveEvaluator.satisfiesObligation(
            obligation.get(), matchedMove)) {
      return Optional.empty();
    }

    // Speculatively perform the matched move and check whether the resulting position ends the
    // game (checkmate, stalemate, dead position, fivefold, 75-move).
    final MoveSpecification spec = matchedMove.moveSpecification();
    board.performMove(spec);
    final Optional<GameResult> ending = checkAutomaticEndings();
    if (ending.isEmpty()) {
      // Not a game-ending move — leave evaluation to the clock press, undo our speculative move.
      board.unperformMove();
      return Optional.empty();
    }

    // Game-ending move: keep the move performed and finalize state.
    clock.switchClock();
    drawOfferManager.clearOffer();
    endGame(ending.get());
    startNewTurn();
    return Optional.of(ArbiterResponse.moveAccepted(matchedMove));
  }

  private ArbiterResponse handleArbiterResponse(ArbiterResponse response, Side side, boolean keepDrawOffer) {
    switch (response.type()) {
      case MOVE_ACCEPTED -> {
        // Perform the move on the internal board
        board.performMove(response.acceptedMove().get().moveSpecification());

        // Switch the clock
        clock.switchClock();

        // Clear draw offer unless we're keeping it (draw was just offered with this move)
        if (!keepDrawOffer) {
          drawOfferManager.clearOffer();
        }

        // Check for automatic game endings
        final Optional<GameResult> ending = checkAutomaticEndings();
        if (ending.isPresent()) {
          endGame(ending.get());
        }

        // Start new turn
        startNewTurn();
      }
      case ILLEGAL_MOVE -> {
        // Add penalty time to opponent
        clock.addPenaltyTime(side.getOppositeSide(), arbiter.getIllegalMoveTracker().getPenaltyTimeMs());
        clock.stopClock();
      }
      case ILLEGAL_MOVE_GAME_LOST -> {
        endGame(new GameResult(GameResultType.ILLEGAL_MOVE_GAME_LOST, side.getOppositeSide(),
            response.renderedPlayerMessage()));
      }
      case TOUCH_MOVE_VIOLATION -> {
        clock.stopClock();
      }
      case RELEASED_PIECE_VIOLATION -> {
        clock.stopClock();
      }
      case INCOMPLETE_MOVE -> {
        // Nothing to do
      }
      default -> {
        // Other types shouldn't occur at clock press
      }
    }

    return response;
  }

  private ArbiterResponse evaluateMustExecuteMove(StaticPosition afterPosition) {
    // Compute expected position after the specified move
    final StaticPosition expectedPosition = Board.createPositionAfterMove(positionBeforeTurn, board.getHavingMove(),
        mustExecuteMove);

    if (expectedPosition.equals(afterPosition)) {
      // Correct — perform the move
      final MoveSpecification executedMove = mustExecuteMove;
      final com.dlb.chess.model.LegalMove matchedLegalMove = board.getLegalMoveSet().stream()
          .filter(lm -> lm.moveSpecification().equals(executedMove))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("Specified move is not in the legal move set"));
      board.performMove(executedMove);
      mustExecuteMove = null;
      clock.switchClock();

      final Optional<GameResult> ending = checkAutomaticEndings();
      if (ending.isPresent()) {
        endGame(ending.get());
      }

      startNewTurn();
      return ArbiterResponse.moveAccepted(matchedLegalMove);
    }

    // Incorrect — instruct to revert
    clock.stopClock();
    return ArbiterResponse.incompleteMove(
        "The specified move was not executed. Please revert the position and play the specified move.");
  }

  // ===== Draw offers =====

  /**
   * Player offers a draw at the correct time (their turn, after making a move). The move is
   * validated but NOT performed and the clock does NOT switch — the player still has to press
   * the clock themselves to commit the move. This matches FIDE: the draw offer is communicated
   * after the move is made and before the clock is pressed; the clock press is a separate act.
   *
   * <p>Returns {@link ArbiterResponseType#MOVE_ACCEPTED} when the move is legal and the offer
   * has been registered (server should then forward the offer to the opponent and tell the
   * offering player to press the clock). Returns the appropriate intervention type if the
   * move is invalid (in which case the offer is dropped without penalty).
   */
  public synchronized ArbiterResponse offerDrawCorrectTime(Side side, StaticPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return ArbiterResponse.incompleteMove("The game is not in progress.");
    }

    // Validate the move first (no side effects on the move state — evaluateClockPress is
    // pure; the move-performing happens in handleArbiterResponse, which we skip on success).
    final ArbiterResponse moveResponse = arbiter.evaluateClockPress(board, afterPosition, currentSequence);

    if (moveResponse.type() != ArbiterResponseType.MOVE_ACCEPTED) {
      // Move is not valid — drop any pending offer state and apply the standard intervention
      // (illegal-move counter, restoration target, etc.) via handleArbiterResponse.
      drawOfferManager.dropOfferDueToInvalidMove(side);
      return handleArbiterResponse(moveResponse, side, false);
    }

    // Move is valid. Register the offer (handles repeat / game-lost penalty).
    final var result = drawOfferManager.offerDrawCorrectTime(side);
    if (result.gameLost()) {
      endGame(new GameResult(GameResultType.DRAW_AGREEMENT, side.getOppositeSide(), result.arbiterMessage()));
      return ArbiterResponse.illegalMoveGameLost(result.arbiterMessage());
    }
    if (!result.accepted()) {
      // Repeated offer (info or warning) — message returned, but the move is still pending
      // (the player has to press the clock to commit it).
      return ArbiterResponse.incompleteMove(result.arbiterMessage());
    }

    // Offer registered. The matched move is returned to the server but the move itself is
    // NOT performed and the clock does NOT switch — the player still owes a clock press.
    return moveResponse;
  }

  /**
   * Player offers a draw at the wrong time (not their turn, or no move made).
   * The offer is still valid per FIDE 9.1.2.1 and forwarded to the opponent,
   * but escalating penalties apply.
   *
   * @return the arbiter message (may be null if no penalty), and whether game is lost
   */
  public synchronized DrawOfferManager.DrawOfferResult offerDrawWrongTime(Side side) {
    if (state != GameState.IN_PROGRESS) {
      return DrawOfferManager.DrawOfferResult.repeated("The game is not in progress.");
    }

    final boolean offererHasMove = side == board.getHavingMove();
    final var result = drawOfferManager.offerDrawWrongTime(side, offererHasMove);
    if (result.gameLost()) {
      endGame(new GameResult(GameResultType.DRAW_AGREEMENT, side.getOppositeSide(), result.arbiterMessage()));
    }
    return result;
  }

  /**
   * Whether the on-move player has already made a legal release in this turn — i.e. a
   * release that corresponds to (the start of) a legal move from the position before turn.
   * Used by the server to detect Scenario 2 of the draw-offer flow: an offer arriving
   * after the opponent has committed a move via FIDE 4.7 is rejected outright.
   */
  public synchronized boolean hasReleasedPieceCommitment() {
    return arbiter.hasReleasedPieceCommitment(board, currentSequence);
  }

  /**
   * Player accepts a draw offer.
   *
   * @return the game result if accepted, empty if rejected. Check {@code getAcceptDrawRejectionMessage}.
   */
  public synchronized Optional<GameResult> acceptDraw(Side side) {
    final Optional<String> rejection = drawOfferManager.acceptDraw(side);
    if (rejection.isPresent()) {
      this.lastAcceptDrawRejection = rejection.get();
      return Optional.empty();
    }

    this.lastAcceptDrawRejection = null;
    final GameResult drawResult = new GameResult(GameResultType.DRAW_AGREEMENT, Side.NONE,
        "The game is drawn by agreement.");
    endGame(drawResult);
    return Optional.of(drawResult);
  }

  private String lastAcceptDrawRejection;

  public synchronized String getLastAcceptDrawRejection() {
    return lastAcceptDrawRejection;
  }

  /**
   * Player rejects a draw offer.
   */
  public synchronized void rejectDraw(Side side) {
    drawOfferManager.rejectDraw(side);
  }

  // ===== Draw claims =====

  /**
   * Player claims a draw (threefold repetition or 50-move rule).
   */
  public synchronized DrawClaimResult claimDraw(Side side, DrawClaimType type, String san) {
    if (state != GameState.IN_PROGRESS) {
      return DrawClaimResult.rejected("You cannot claim a draw now.");
    }
    if (side != board.getHavingMove()) {
      // FIDE 9.2 / 9.3: a draw claim can only be made by the player whose turn it is.
      return DrawClaimResult.rejected("You cannot claim a draw when not having the move.");
    }

    final DrawClaimResult claimResult = drawClaimManager.processClaim(board, type, san);

    if (claimResult.accepted()) {
      final GameResultType resultType = (type == DrawClaimType.THREEFOLD_ON_BOARD
          || type == DrawClaimType.THREEFOLD_WITH_MOVE) ? GameResultType.THREEFOLD_CLAIM
              : GameResultType.FIFTY_MOVE_CLAIM;

      // If claim-with-move was accepted, perform the move first
      if (claimResult.moveToPerform().isPresent()) {
        board.performMove(claimResult.moveToPerform().get());
      }

      endGame(new GameResult(resultType, Side.NONE, claimResult.message()));
    } else if (claimResult.moveToPerform().isPresent()) {
      // Rejected claim with move — player must execute the specified move
      mustExecuteMove = claimResult.moveToPerform().get();
      clock.startClock(side);
    }

    return claimResult;
  }

  // ===== Resignation =====

  /**
   * Player resigns.
   *
   * <p>FIDE-aligned: a resignation is a draw if the opponent has no series of legal moves
   * that could result in checkmate. We use the QUICK winnability check, not the FULL CUA:
   * full CUA is a deep iterative-deepening helpmate search that can take hundreds of ms
   * on midgame positions, while QUICK runs in microseconds and is precise enough for the
   * cases that matter at resignation/flag-fall (lone king, K+B, K+N, etc.).
   * POSSIBLY_WINNABLE is treated as winnable: if we can't prove the opponent is
   * unwinnable, we award them the win.
   */
  public synchronized GameResult resign(Side side) {
    final Side opponent = side.getOppositeSide();

    final UnwinnableQuick winnability = board.isUnwinnableQuick(opponent);
    if (winnability == UnwinnableQuick.UNWINNABLE) {
      final String sideName = sideName(side);
      final String opponentName = sideName(opponent);
      final GameResult drawResult = new GameResult(GameResultType.RESIGNATION, Side.NONE,
          sideName + " resigned, but because " + opponentName
              + " has no possible win, the game is a draw.");
      endGame(drawResult);
      return drawResult;
    }

    final String sideName = sideName(side);
    final String opponentName = sideName(opponent);
    final GameResult lossResult = new GameResult(GameResultType.RESIGNATION, opponent,
        sideName + " resigns. " + opponentName + " wins the game.");
    endGame(lossResult);
    return lossResult;
  }

  // ===== Flag fall =====

  /**
   * Checks for flag fall. Should be called periodically.
   *
   * <p>When a side runs out of time, we use the QUICK winnability check on the OPPONENT
   * (the side that did NOT time out) to decide whether they can possibly checkmate. If
   * they cannot, the game is a draw (FIDE 6.9 / 5.2.2). The QUICK check is a fast
   * static analysis suitable for use on every flag fall; the FULL CUA is intentionally
   * avoided here because it is a deep search and the dumb-board has no other reason to
   * pay that cost.
   */
  public synchronized Optional<GameResult> checkFlagFall() {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }

    clock.tick();

    for (final Side side : new Side[] { Side.WHITE, Side.BLACK }) {
      if (clock.isFlagFall(side)) {
        final Side opponent = side.getOppositeSide();
        final UnwinnableQuick winnability = board.isUnwinnableQuick(opponent);

        final String sideName = sideName(side);
        final String opponentName = sideName(opponent);
        final GameResult flagResult;
        if (winnability == UnwinnableQuick.UNWINNABLE) {
          flagResult = new GameResult(GameResultType.FLAG_FALL, Side.NONE,
              sideName + "'s time has elapsed, but because " + opponentName
                  + " has no possible win, the game is a draw.");
        } else {
          flagResult = new GameResult(GameResultType.FLAG_FALL, opponent,
              sideName + " loses on time. " + opponentName + " wins the game.");
        }

        endGame(flagResult);
        return Optional.of(flagResult);
      }
    }

    return Optional.empty();
  }

  // ===== Automatic game endings =====

  /**
   * Game-ending detection that runs after every accepted move and on every auto-end
   * board event. Only fast checks are allowed here — never the full CUA
   * (isDeadPositionFull / isUnwinnableFull). Insufficient material is detected via
   * the cheap structural test on the board; positions that are dead by exhaustive
   * search but not by insufficient material are not auto-ended (the players will end
   * them via fivefold/75-move/stalemate or claim a draw).
   */
  private Optional<GameResult> checkAutomaticEndings() {
    // 1. Checkmate
    if (board.isCheckmate()) {
      final Side winner = board.getHavingMove().getOppositeSide();
      return Optional.of(new GameResult(GameResultType.CHECKMATE, winner,
          sideName(winner) + " won the game by checkmate."));
    }

    // 2. Stalemate
    if (board.isStalemate()) {
      return Optional.of(new GameResult(GameResultType.STALEMATE, Side.NONE,
          "The game is drawn by stalemate."));
    }

    // 3. Insufficient material (FIDE 9.4 / 5.2.2). Fast structural check; no search.
    if (board.isInsufficientMaterial()) {
      return Optional.of(new GameResult(GameResultType.DEAD_POSITION, Side.NONE,
          "The game is drawn by insufficient material. Neither player can checkmate."));
    }

    // 4. Fivefold repetition
    if (board.isFivefoldRepetition()) {
      return Optional.of(new GameResult(GameResultType.FIVEFOLD_REPETITION, Side.NONE,
          "The game is drawn by fivefold repetition."));
    }

    // 5. 75-move rule
    if (board.isSeventyFiftyMove()) {
      return Optional.of(new GameResult(GameResultType.SEVENTY_FIVE_MOVE, Side.NONE,
          "The game is drawn by the 75-move rule."));
    }

    return Optional.empty();
  }

  // ===== Helpers =====

  private void startNewTurn() {
    this.currentSequence = new ActionSequence(board.getHavingMove());
    this.positionBeforeTurn = board.getStaticPosition();
    this.restorationTargetPosition = positionBeforeTurn;
    this.removedSquaresThisTurn.clear();
    this.mustExecuteMove = null;
  }

  private void endGame(GameResult gameResult) {
    this.state = GameState.ENDED;
    this.result = gameResult;
    this.clock.stopClock();
  }

  private static String sideName(Side side) {
    return side == Side.WHITE ? "White" : "Black";
  }

  /**
   * Enters the "waiting for ready" state after an arbiter intervention.
   * Both players must signal readiness before the game continues.
   *
   * <p>The released-piece rule window is reset here because, after a recovery
   * handshake, prior in-turn events should not retroactively bind the resumed
   * play. In particular, a "legal-in-isolation" release that was actually
   * invalid in context (e.g. a pawn drop that violates an active touch-move
   * obligation on a different piece) must not be treated as a commitment after
   * the recovery — otherwise the player can never satisfy the touch-move and
   * the rule deadlocks. Known trade-off: a player who commits a legal release
   * and then triggers an unrelated arbiter intervention (wrong-time draw,
   * drawAcceptRejected, opponentClockPressed) before the clock press loses
   * the FIDE 4.7 commitment after the handshake. Acceptable in practice.
   */
  public synchronized void enterWaitingForReady() {
    this.waitingForReady = true;
    this.whiteReady = false;
    this.blackReady = false;
    currentSequence.resetReleasedPieceRule();
  }

  /**
   * Enters the restoration state after an invalid move. Board events are then monitored
   * until the physical board matches the position before the turn.
   */
  public synchronized void enterWaitingForRestoration() {
    enterWaitingForRestoration(positionBeforeTurn);
  }

  public synchronized void enterWaitingForRestoration(StaticPosition restorationTargetPosition) {
    this.waitingForRestoration = true;
    this.restorationResumePending = false;
    this.waitingForReady = false;
    this.whiteReady = false;
    this.blackReady = false;
    this.restorationTargetPosition = restorationTargetPosition;
  }

  /**
   * Marks the restore flow complete and either waits for ready confirmation or resumes later.
   */
  public synchronized void completeRestoration() {
    this.waitingForRestoration = false;
    currentSequence.resetReleasedPieceRule();
    if (autoResumeAfterRestore) {
      this.restorationResumePending = true;
      this.waitingForReady = false;
      this.whiteReady = false;
      this.blackReady = false;
    } else {
      this.restorationResumePending = false;
      enterWaitingForReady();
    }
  }

  /**
   * Restarts the clock after an automatic restoration pause.
   */
  public synchronized void resumeAfterRestorationDelay() {
    if (state == GameState.IN_PROGRESS && restorationResumePending && !waitingForReady && !waitingForRestoration) {
      restorationResumePending = false;
      clock.startClock(board.getHavingMove());
    }
  }

  /**
   * A player signals readiness to continue after an arbiter intervention.
   *
   * @return true if both players are now ready and the game should continue
   */
  public synchronized boolean playerReady(Side side) {
    if (!waitingForReady) {
      return false;
    }
    switch (side) {
      case WHITE -> whiteReady = true;
      case BLACK -> blackReady = true;
      default -> { return false; }
    }
    if (whiteReady && blackReady) {
      waitingForReady = false;
      if (state == GameState.IN_PROGRESS) {
        clock.startClock(board.getHavingMove());
      }
      return true;
    }
    return false;
  }

  /**
   * Returns the position before the current turn, for restoring after an illegal move.
   */
  public synchronized StaticPosition getRestorePosition() {
    return restorationTargetPosition;
  }

  public synchronized boolean isWaitingForReady() {
    return waitingForReady;
  }

  public synchronized boolean isWaitingForRestoration() {
    return waitingForRestoration;
  }

  public synchronized boolean isRestorationResumePending() {
    return restorationResumePending;
  }

  public synchronized boolean isRestoredPosition(StaticPosition position) {
    return restorationTargetPosition.equals(position);
  }

  public synchronized boolean isAutoResumeAfterRestore() {
    return autoResumeAfterRestore;
  }

  // ===== PGN export =====

  public synchronized String exportPgn() {
    return PgnCreate.createPgnFileString(board);
  }

  // ===== Getters =====

  public synchronized GameState getState() {
    return state;
  }

  public synchronized GameResult getResult() {
    return result;
  }

  public synchronized Side getHavingMove() {
    return board.getHavingMove();
  }

  public synchronized ApiBoard getBoard() {
    return board;
  }

  public synchronized ClockManager getClock() {
    return clock;
  }

  public synchronized DrawOfferManager getDrawOfferManager() {
    return drawOfferManager;
  }

  public synchronized boolean isCheck() {
    return board.isCheck();
  }

  public synchronized StaticPosition getPositionBeforeTurn() {
    return positionBeforeTurn;
  }

  public synchronized MoveSpecification getMustExecuteMove() {
    return mustExecuteMove;
  }
}
