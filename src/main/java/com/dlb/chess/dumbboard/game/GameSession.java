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
import com.dlb.chess.unwinnability.full.enums.DeadPositionFull;
import com.dlb.chess.unwinnability.full.enums.UnwinnableFull;

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

  public GameSession(TimeControl timeControl) {
    this.board = new Board();
    this.clock = new ClockManager(timeControl);
    this.arbiter = new ArbiterEngine();
    this.drawOfferManager = new DrawOfferManager();
    this.drawClaimManager = new DrawClaimManager();
    this.timeControl = timeControl;

    this.state = GameState.WAITING_FOR_PLAYERS;
    this.result = null;
    this.currentSequence = new ActionSequence(Side.WHITE);
    this.positionBeforeTurn = board.getStaticPosition();
    this.removedSquaresThisTurn = new HashSet<>();
    this.mustExecuteMove = null;
    this.waitingForReady = false;
    this.whiteReady = false;
    this.blackReady = false;
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

    final ArbiterResponse response = arbiter.evaluateClockPress(board, afterPosition, currentSequence);
    if (response.type() != ArbiterResponseType.MOVE_ACCEPTED) {
      return Optional.empty();
    }

    // Speculatively perform the matched move and check whether the resulting position ends the game.
    final MoveSpecification spec = response.acceptedMove().get().moveSpecification();
    board.performMove(spec);
    final Optional<GameResult> ending = checkAutomaticEndings();
    if (ending.isEmpty()) {
      // Not a game-ending move — leave evaluation to the clock press, undo our speculative move.
      board.unperformMove();
      return Optional.empty();
    }

    // Game-ending move: keep the move performed and finalize state (mirrors MOVE_ACCEPTED in
    // handleArbiterResponse, but without starting a new turn since the game is over).
    clock.switchClock();
    drawOfferManager.clearOffer();
    endGame(ending.get());
    startNewTurn();
    return Optional.of(response);
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
            response.message()));
      }
      case TOUCH_MOVE_VIOLATION -> {
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
   * Player offers a draw at the correct time (their turn, after making a move).
   * This also triggers move evaluation (same as clock press).
   */
  public synchronized ArbiterResponse offerDrawCorrectTime(Side side, StaticPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return ArbiterResponse.incompleteMove("The game is not in progress.");
    }

    final var result = drawOfferManager.offerDrawCorrectTime(side);
    if (result.gameLost()) {
      endGame(new GameResult(GameResultType.DRAW_AGREEMENT, side.getOppositeSide(), result.arbiterMessage()));
      return ArbiterResponse.illegalMoveGameLost(result.arbiterMessage());
    }
    if (!result.accepted()) {
      return ArbiterResponse.incompleteMove(result.arbiterMessage());
    }

    // Draw offer triggers move evaluation
    final ArbiterResponse moveResponse = arbiter.evaluateClockPress(board, afterPosition, currentSequence);

    if (moveResponse.type() != ArbiterResponseType.MOVE_ACCEPTED) {
      drawOfferManager.dropOfferDueToInvalidMove(side);
      return handleArbiterResponse(moveResponse, side, false);
    }

    return handleArbiterResponse(moveResponse, side, true);
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

    final var result = drawOfferManager.offerDrawWrongTime(side);
    if (result.gameLost()) {
      endGame(new GameResult(GameResultType.DRAW_AGREEMENT, side.getOppositeSide(), result.arbiterMessage()));
    }
    return result;
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
    if (state != GameState.IN_PROGRESS || side != board.getHavingMove()) {
      return DrawClaimResult.rejected("You cannot claim a draw now.");
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
   */
  public synchronized GameResult resign(Side side) {
    final Side opponent = side.getOppositeSide();

    // Check if the opponent can even win
    final UnwinnableFull winnability = board.isUnwinnableFull(opponent);
    if (winnability == UnwinnableFull.UNWINNABLE) {
      final GameResult drawResult = new GameResult(GameResultType.RESIGNATION, Side.NONE,
          "The game is drawn. The opponent cannot checkmate by any series of legal moves.");
      endGame(drawResult);
      return drawResult;
    }

    final String sideName = side == Side.WHITE ? "White" : "Black";
    final String opponentName = opponent == Side.WHITE ? "White" : "Black";
    final GameResult lossResult = new GameResult(GameResultType.RESIGNATION, opponent,
        sideName + " resigns. " + opponentName + " wins the game.");
    endGame(lossResult);
    return lossResult;
  }

  // ===== Flag fall =====

  /**
   * Checks for flag fall. Should be called periodically.
   */
  public synchronized Optional<GameResult> checkFlagFall() {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }

    clock.tick();

    for (final Side side : new Side[] { Side.WHITE, Side.BLACK }) {
      if (clock.isFlagFall(side)) {
        final Side opponent = side.getOppositeSide();
        final UnwinnableFull winnability = board.isUnwinnableFull(opponent);

        final GameResult flagResult;
        if (winnability == UnwinnableFull.UNWINNABLE) {
          flagResult = new GameResult(GameResultType.FLAG_FALL, Side.NONE,
              "The game is drawn. The opponent cannot checkmate by any series of legal moves.");
        } else {
          final String sideName = side == Side.WHITE ? "White" : "Black";
          final String opponentName = opponent == Side.WHITE ? "White" : "Black";
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

  private Optional<GameResult> checkAutomaticEndings() {
    // 1. Checkmate
    if (board.isCheckmate()) {
      final Side winner = board.getHavingMove().getOppositeSide();
      final String winnerName = winner == Side.WHITE ? "White" : "Black";
      return Optional.of(new GameResult(GameResultType.CHECKMATE, winner,
          winnerName + " won the game by checkmate."));
    }

    // 2. Stalemate
    if (board.isStalemate()) {
      return Optional.of(new GameResult(GameResultType.STALEMATE, Side.NONE,
          "The game is drawn by stalemate."));
    }

    // 3. Dead position (CUA)
    if (board.isDeadPositionFull() == DeadPositionFull.DEAD_POSITION) {
      return Optional.of(new GameResult(GameResultType.DEAD_POSITION, Side.NONE,
          "The game is drawn. Neither player can checkmate the opponent."));
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
    this.removedSquaresThisTurn.clear();
    this.mustExecuteMove = null;
  }

  private void endGame(GameResult gameResult) {
    this.state = GameState.ENDED;
    this.result = gameResult;
    this.clock.stopClock();
  }

  /**
   * Enters the "waiting for ready" state after an arbiter intervention.
   * Both players must signal readiness before the game continues.
   */
  public synchronized void enterWaitingForReady() {
    this.waitingForReady = true;
    this.whiteReady = false;
    this.blackReady = false;
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
    return positionBeforeTurn;
  }

  public synchronized boolean isWaitingForReady() {
    return waitingForReady;
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
