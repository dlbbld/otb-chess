// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.adjudication.AdjudicationResult;
import io.github.dlbbld.ashlarchess.adjudication.Adjudicator;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.pgn.PgnCreate;
import io.github.dlbbld.otbchess.arbiter.ArbiterEngine;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse.IllegalMoveDetail;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.arbiter.MidPlayValidator;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResult;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.message.MessageKey;

/**
 * Central orchestrator for an OTB Chess game.
 *
 * <p>
 * Holds the game state and coordinates all managers: clock, arbiter, draw offers, draw claims.
 *
 * <p>
 * Thread safety: methods are synchronized because WebSocket messages arrive from different threads.
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

  // For the personalised end-of-game messages: the side that performed the terminating action
  // (resigned, flagged, or accepted a draw); the client phrases the message in the second person
  // for each player. drawExceptionByInsufficientMaterial additionally distinguishes the two
  // resignation / flag-fall draw reasons (material shortage vs. unwinnable despite material).
  private Side terminationActor = Side.NONE;
  private boolean drawExceptionByInsufficientMaterial;

  // Per-turn state
  private ActionSequence currentSequence;
  private BitboardPosition positionBeforeTurn;
  private final Set<Square> removedSquaresThisTurn;

  // State for "must execute specified move" after rejected draw claim. The SAN is kept alongside
  // the move so the "not executed" message can name the move the player still owes.
  private MoveSpecification mustExecuteMove;
  private String mustExecuteMoveSan;

  // FIDE 9.2 / 9.3: a player may make at most one draw claim per move. Set when a claim
  // attempt is processed (accepted or rejected, but not when the SAN was invalid — the
  // player hasn't actually completed an attempt yet). Reset on startNewTurn().
  private boolean claimMadeThisTurn;

  // Claims while NOT having the move (FIDE 9.2/9.3 require the move). Teaching philosophy: the
  // claim buttons stay enabled so the player can repeat the fault and learn — the arbiter
  // escalates instead: plain rejection, then a warning, then loss of the game on the third
  // wrong-time claim. Counted per player across the whole game (a warning, once given, stands).
  private static final int WRONG_TIME_CLAIM_LIMIT = 3;
  private final Map<Side, Integer> wrongTimeClaimCounts = new EnumMap<>(Side.class);

  // Second-or-later claims on the SAME move (FIDE 9.2/9.3 allow one claim per move). Same
  // philosophy: buttons stay enabled; the first violation gets the warning immediately (the
  // player already used their legitimate claim), the second loses the game. Violations are
  // counted per player across the whole game — a legitimate single claim on a later move is
  // never a violation, but a repeated one after the warning loses.
  private static final int REPEAT_CLAIM_VIOLATION_LIMIT = 2;
  private final Map<Side, Integer> repeatClaimViolationCounts = new EnumMap<>(Side.class);

  // Claims AFTER touching/moving a piece on this move, before the clock press (FIDE 9.4: the
  // right to claim is lost once a piece is touched). Same ladder as the wrong-time claims:
  // rejection, warning, loss on the third — counted per player across the whole game, i.e. the
  // count accumulates over different moves.
  private static final int AFTER_TOUCH_CLAIM_LIMIT = 3;
  private final Map<Side, Integer> afterTouchClaimCounts = new EnumMap<>(Side.class);

  // FIDE 9.5.3: an incorrect draw claim adds 2 minutes to the opponent's clock.
  // (Article-9 of the Competitive Rules of Play; rapid/blitz Appendix A.3 reduces this
  // to 1 minute — not differentiated here, see fide-deviations.md.)
  private static final long INCORRECT_CLAIM_PENALTY_MS = 2 * 60 * 1000;

  // Ready-to-continue tracking (both players must click after arbiter intervention)
  private boolean waitingForReady;
  private boolean whiteReady;
  private boolean blackReady;
  private boolean waitingForRestoration;
  private boolean restorationResumePending;
  private BitboardPosition restorationTargetPosition;
  // True when the current restoration was caused by a released-piece (FIDE 4.7) violation. While set,
  // completeRestoration() preserves the released-piece rule window so the committed move stays final
  // across revert cycles until the player actually plays it. Cleared on a new turn / other violations.
  private boolean restorationFromReleasedPiece;

  public GameSession(TimeControl timeControl) {
    this(timeControl, io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES);
  }

  public GameSession(TimeControl timeControl, int maxIllegalMoves) {
    this(timeControl, maxIllegalMoves, true);
  }

  public GameSession(TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore) {
    this(timeControl, maxIllegalMoves, autoResumeAfterRestore, new Board());
  }

  /**
   * Constructor for games that start from a non-initial position (e.g. a custom FEN supplied on the start screen). The
   * board is pre-built by the caller; FEN parsing and validation happen at the server boundary so the validation reason
   * can be returned to the client before the {@link GameSession} is created.
   *
   * <p>
   * The starting side-to-move is taken from the supplied board, so e.g. a FEN with Black to move correctly starts the
   * clock on Black at game start.
   */
  public GameSession(TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore,
      Board startingBoard) {
    this.board = startingBoard;
    this.clock = new ClockManager(timeControl);
    this.arbiter = new ArbiterEngine(maxIllegalMoves);
    this.drawOfferManager = new DrawOfferManager();
    this.drawClaimManager = new DrawClaimManager();
    this.timeControl = timeControl;
    this.autoResumeAfterRestore = autoResumeAfterRestore;

    this.state = GameState.WAITING_FOR_PLAYERS;
    this.result = null;
    this.currentSequence = new ActionSequence(board.getSideToMove());
    this.positionBeforeTurn = board.getBitboardPosition();
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
    // The clock starts on whichever side is to move in the starting position,
    // not blindly on White, so a FEN with Black to move correctly clocks Black.
    this.clock.startClock(board.getSideToMove());
  }

  // ===== Mid-play event recording =====

  /**
   * Records a board event during play. Returns an arbiter response if mid-play intervention is needed.
   */
  public synchronized Optional<ArbiterResponse> recordEvent(Side side, BoardEvent event) {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }
    if (side != board.getSideToMove()) {
      return Optional.empty();
    }

    // Check mid-play validation
    Optional<ArbiterResponse> midPlayResponse = MidPlayValidator.validate(event, side, positionBeforeTurn,
        removedSquaresThisTurn);
    if (midPlayResponse.isPresent()) {
      final Optional<BitboardPosition> committedReleasePosition = arbiter.findReleasedPieceCommitmentPosition(board,
          currentSequence);
      if (committedReleasePosition.isPresent()) {
        midPlayResponse = Optional.of(midPlayResponse.get().withRestorePosition(committedReleasePosition.get()));
        restorationFromReleasedPiece = true;
      } else {
        restorationFromReleasedPiece = false;
      }
      clock.stopClock();
      // Moving an OPPONENT's piece escalates like the other misconducts (A-007): notice,
      // notice + warning, loss of the game on the third time — counted across the whole game.
      if (midPlayResponse.get().playerMessageKey() == MessageKey.ARBITER_POSITION_CHANGE_OPPONENT_PIECE) {
        return Optional.of(escalateMovedOpponentPiece(side, midPlayResponse.get()));
      }
      return midPlayResponse;
    }

    // Track removed opponent pieces. Two paths:
    // • DRAG_CAPTURE: the player drops their own piece on top of an opponent piece;
    // the displaced piece (= the opponent piece) is implicitly removed. The
    // opponent piece is identified by event.displacedPiece().
    // • REMOVE: the player drags an opponent piece off the board explicitly,
    // as the first step of a capture-by-removal sequence. The opponent piece is
    // identified by event.piece(); displacedPiece is NONE for REMOVE events.
    if (event.type() == io.github.dlbbld.otbchess.event.BoardEventType.DRAG_CAPTURE) {
      if (event.displacedPiece() != io.github.dlbbld.ashlarchess.board.enums.Piece.NONE
          && event.displacedPiece().getSide() != side) {
        removedSquaresThisTurn.add(event.targetSquare());
      }
    } else if (event.type() == io.github.dlbbld.otbchess.event.BoardEventType.REMOVE) {
      if (event.piece() != io.github.dlbbld.ashlarchess.board.enums.Piece.NONE && event.piece().getSide() != side) {
        removedSquaresThisTurn.add(event.square());
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
  public synchronized ArbiterResponse pressClockButton(Side side, BitboardPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return ArbiterResponse.incompleteMove("The game is not in progress.");
    }
    if (side != board.getSideToMove()) {
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
   * Triggered after each board event during play. If the current physical position corresponds to a legal move that
   * immediately ends the game (checkmate, stalemate, dead position, fivefold repetition, 75-move rule), the move is
   * accepted and the game is ended without waiting for a clock press. For any non-ending move, this returns empty and
   * the player must still press the clock as usual.
   *
   * <p>
   * This must NOT have side effects on the illegal-move counter — intermediate positions during piece manipulation are
   * not "moves" and must not be recorded as illegal. Only the clock press (or an offered draw, etc.) goes through the
   * full {@link ArbiterEngine} evaluation that records illegal moves.
   */
  public synchronized Optional<ArbiterResponse> evaluateForAutoEnd(Side side, BitboardPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }
    if (side != board.getSideToMove()) {
      return Optional.empty();
    }
    // Skip during the patient-loop recovery from a rejected draw claim — that path requires the
    // mustExecuteMove flow at clock press, not auto-end.
    if (mustExecuteMove != null) {
      return Optional.empty();
    }

    // Position match — pure read, no side effects. Most intermediate positions during piece
    // manipulation will produce no match and we exit immediately.
    final java.util.Set<io.github.dlbbld.ashlarchess.board.LegalMove> matchingMoves = io.github.dlbbld.otbchess.core.PositionComparator
        .findMatchingMoves(board, afterPosition);
    if (matchingMoves.isEmpty()) {
      return Optional.empty();
    }
    final io.github.dlbbld.ashlarchess.board.LegalMove matchedMove = matchingMoves.iterator().next();

    // Released-piece guard (FIDE 4.7): if the player has already committed a release in this
    // turn and the current physical position is NOT one of the committed move's allowed final
    // positions, the player must not auto-finish a different move. The clock-press flow will
    // produce a RELEASED_PIECE_VIOLATION and the standard restoration recovery handles it.
    if (arbiter.hasReleasedPieceViolation(board, afterPosition, currentSequence)) {
      return Optional.empty();
    }

    // Touch-move check (also pure read). If the matched move would violate touch-move, leave
    // detection to the clock press so the existing arbiter feedback flow handles it.
    final java.util.Optional<io.github.dlbbld.otbchess.touchmove.TouchMoveObligation> obligation = io.github.dlbbld.otbchess.touchmove.TouchMoveEvaluator
        .findObligation(currentSequence, board);
    if (obligation.isPresent()
        && !io.github.dlbbld.otbchess.touchmove.TouchMoveEvaluator.satisfiesObligation(obligation.get(), matchedMove)) {
      return Optional.empty();
    }

    // Speculatively perform the matched move and check whether the resulting position ends the
    // game (checkmate, stalemate, dead position, fivefold, 75-move).
    final MoveSpecification spec = matchedMove.moveSpecification();
    board.move(spec);
    final Optional<GameResult> ending = checkAutomaticEndings();
    if (ending.isEmpty()) {
      // Not a game-ending move — leave evaluation to the clock press, undo our speculative move.
      board.unmove();
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
        board.move(response.acceptedMove().get().moveSpecification());

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
        // FIDE 7.5.3 press-without-move: nothing to restore — the player simply still has to
        // move, so their clock keeps running. Every other illegal move pauses for restoration.
        final boolean noMoveMade = response.illegalMoveDetail().map(IllegalMoveDetail::noMoveMade).orElse(false);
        if (!noMoveMade) {
          clock.stopClock();
        }
        restorationFromReleasedPiece = false;
      }
      case ILLEGAL_MOVE_GAME_LOST -> {
        endGame(new GameResult(GameResultType.ILLEGAL_MOVE_GAME_LOST, side.getOppositeSide(),
            response.renderedPlayerMessage()));
      }
      case TOUCH_MOVE_VIOLATION -> {
        clock.stopClock();
        restorationFromReleasedPiece = false;
      }
      case RELEASED_PIECE_VIOLATION -> {
        clock.stopClock();
        // Latch the commitment so the upcoming restoration keeps the released move final (FIDE 4.7).
        restorationFromReleasedPiece = true;
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

  private ArbiterResponse evaluateMustExecuteMove(BitboardPosition afterPosition) {
    // Compute expected position after the specified move
    final BitboardPosition expectedPosition = positionBeforeTurn.afterMove(mustExecuteMove, board.getSideToMove());

    if (expectedPosition.equals(afterPosition)) {
      // Correct — perform the move
      final MoveSpecification executedMove = mustExecuteMove;
      final io.github.dlbbld.ashlarchess.board.LegalMove matchedLegalMove = board.getLegalMoves().stream()
          .filter(lm -> lm.moveSpecification().equals(executedMove)).findFirst()
          .orElseThrow(() -> new IllegalStateException("Specified move is not in the legal move set"));
      board.move(executedMove);
      mustExecuteMove = null;
      mustExecuteMoveSan = null;
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
    return ArbiterResponse.incompleteMove("The specified move, " + mustExecuteMoveSan
        + ", was not executed. Please revert the position and play the specified move.");
  }

  // ===== Draw offers =====

  /**
   * Player offers a draw at the correct time (their turn, after making a move). The move is validated but NOT performed
   * and the clock does NOT switch — the player still has to press the clock themselves to commit the move. This matches
   * FIDE: the draw offer is communicated after the move is made and before the clock is pressed; the clock press is a
   * separate act.
   *
   * <p>
   * Returns {@link ArbiterResponseType#MOVE_ACCEPTED} when the move is legal and the offer has been registered (server
   * should then forward the offer to the opponent and tell the offering player to press the clock). Returns the
   * appropriate intervention type if the move is invalid (in which case the offer is dropped without penalty).
   */
  public synchronized ArbiterResponse offerDrawCorrectTime(Side side, BitboardPosition afterPosition) {
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
   * Player offers a draw at the wrong time (not their turn, or no move made). The offer is still valid per FIDE 9.1.2.1
   * and forwarded to the opponent, but escalating penalties apply.
   *
   * @return the arbiter message (may be null if no penalty), and whether game is lost
   */
  public synchronized DrawOfferManager.DrawOfferResult offerDrawWrongTime(Side side) {
    if (state != GameState.IN_PROGRESS) {
      return DrawOfferManager.DrawOfferResult.repeated("The game is not in progress.");
    }

    final boolean offererHasMove = side == board.getSideToMove();
    final var result = drawOfferManager.offerDrawWrongTime(side, offererHasMove);
    if (result.gameLost()) {
      terminationActor = side;
      endGame(new GameResult(GameResultType.WRONG_TIME_OFFER_GAME_LOST, side.getOppositeSide(),
          sideName(side) + " loses the game by repeatedly offering a draw at the wrong time."));
    }
    return result;
  }

  /**
   * Whether the on-move player has already made a legal release in this turn — i.e. a release that corresponds to (the
   * start of) a legal move from the position before turn. Used by the server to detect Scenario 2 of the draw-offer
   * flow: an offer arriving after the opponent has committed a move via FIDE 4.7 is rejected outright.
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
    terminationActor = side; // the player who accepted the offer (for the personalised message)
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
      return DrawClaimResult.error("You cannot claim a draw now.");
    }
    if (side != board.getSideToMove()) {
      // FIDE 9.2 / 9.3: a draw claim can only be made by the player whose turn it is. (Offering a
      // draw is different — an offer is possible at any time; a CLAIM requires the move.) The
      // buttons stay enabled (see wrongTimeClaimCounts) and the arbiter escalates: rejection,
      // then a warning, then loss of the game on the third wrong-time claim.
      final int count = wrongTimeClaimCounts.merge(side, 1, Integer::sum);
      if (count >= WRONG_TIME_CLAIM_LIMIT) {
        endGame(new GameResult(GameResultType.WRONG_TIME_CLAIM_GAME_LOST, side.getOppositeSide(),
            sideName(side) + " loses the game by repeatedly claiming a draw when not having the move."));
        return DrawClaimResult.rejectedWithoutDrawOffer(
            "You have been warned that you will lose the game when you claim a draw again while not having the"
                + " move. As you have claimed a draw again, you lose the game.",
            "Your opponent has, despite the warnings, repeatedly requested to claim a draw while not having the"
                + " move, and so has lost the game.");
      }
      if (count == WRONG_TIME_CLAIM_LIMIT - 1) {
        return DrawClaimResult.wrongTime(
            "You cannot claim a draw when not having the move. Warning: your next"
                + " draw claim when not having the move loses the game.",
            "Your opponent again claimed a draw while not having the move. The claim was not considered, and"
                + " they have been warned: their next draw claim when not having the move loses them the game.");
      }
      // "Not considered" (not "rejected"): a claim made out of turn never reaches the rule
      // machinery — only a claim that was actually examined on the merits can be rejected.
      return DrawClaimResult.wrongTime("You cannot claim a draw when not having the move.",
          "Your opponent claimed a draw while not having the move. The claim was not considered.");
    }
    if (!currentSequence.isEmpty()) {
      // FIDE 9.4: the player loses the right to claim under 9.2 / 9.3 once any piece has
      // been touched on this move. Any event in the current turn's action sequence
      // (CLICK, DRAG_*, REMOVE, RESTORE_*) counts as a touch — claims must be made
      // before starting to interact with pieces. Same escalation ladder as the wrong-time
      // claims (see afterTouchClaimCounts): rejection, warning, loss on the third.
      final int count = afterTouchClaimCounts.merge(side, 1, Integer::sum);
      if (count >= AFTER_TOUCH_CLAIM_LIMIT) {
        endGame(new GameResult(GameResultType.CLAIM_AFTER_TOUCH_GAME_LOST, side.getOppositeSide(),
            sideName(side) + " loses the game by repeatedly claiming a draw after touching a piece."));
        return DrawClaimResult.rejectedWithoutDrawOffer(
            "You have been warned that you will lose the game when you claim a draw again after touching a piece."
                + " As you have claimed a draw again, you lose the game.",
            "Your opponent has, despite the warnings, repeatedly claimed a draw after touching a piece, and so"
                + " has lost the game.");
      }
      final String rejection = "You cannot claim a draw after touching or moving a piece on this move (FIDE 9.4)."
          + " Claims must be made before any piece interaction.";
      if (count == AFTER_TOUCH_CLAIM_LIMIT - 1) {
        return DrawClaimResult.wrongTime(
            rejection + " Warning: your next draw claim after touching a piece loses the game.",
            "Your opponent again claimed a draw after touching a piece on this move. The claim was not considered,"
                + " and they have been warned: their next draw claim after touching a piece loses them the game.");
      }
      return DrawClaimResult.wrongTime(rejection,
          "Your opponent claimed a draw after touching a piece on this move. The claim was not considered.");
    }
    if (claimMadeThisTurn) {
      // FIDE 9.2/9.3 allow one claim per move. The buttons stay enabled (see
      // repeatClaimViolationCounts) and the arbiter escalates: warning on the first repeat
      // (the legitimate claim was already used), loss of the game on the next.
      final int violations = repeatClaimViolationCounts.merge(side, 1, Integer::sum);
      if (violations >= REPEAT_CLAIM_VIOLATION_LIMIT) {
        endGame(new GameResult(GameResultType.REPEAT_CLAIM_GAME_LOST, side.getOppositeSide(),
            sideName(side) + " loses the game by repeatedly claiming a draw on the same move."));
        return DrawClaimResult.rejectedWithoutDrawOffer(
            "You have been warned that you will lose the game when you claim a draw again on the same move."
                + " As you have claimed a draw again, you lose the game.",
            "Your opponent has, despite the warnings, repeatedly claimed a draw on the same move, and so has"
                + " lost the game.");
      }
      return DrawClaimResult.repeatClaim(
          "You cannot make more than one draw claim on your move. You are warned:"
              + " the next draw claim on a move you have already claimed on loses the game.",
          "Your opponent made a second draw claim on the same move. The claim was not considered, and they have"
              + " been warned: their next draw claim on a move they have already claimed on loses them the game.");
    }

    final DrawClaimResult claimResult = drawClaimManager.processClaim(board, type, san);

    // An invalid SAN doesn't constitute a completed claim attempt — the player can re-prompt.
    // Any other outcome counts and locks claims for the rest of this turn.
    if (!claimResult.invalidMove()) {
      claimMadeThisTurn = true;
    }

    if (claimResult.accepted()) {
      final GameResultType resultType = (type == DrawClaimType.THREEFOLD_ON_BOARD
          || type == DrawClaimType.THREEFOLD_WITH_MOVE) ? GameResultType.THREEFOLD_CLAIM
              : GameResultType.FIFTY_MOVE_CLAIM;

      if (claimResult.moveToPerform().isPresent()) {
        board.move(claimResult.moveToPerform().get());
      }

      // Use the short game-end description for the result panel; the long claim-feedback
      // line goes only to the per-player arbiter messages.
      final String description = claimResult.gameEndDescription().orElse(claimResult.message());
      endGame(new GameResult(resultType, Side.NONE, description));
    } else if (!claimResult.invalidMove()) {
      // FIDE 9.5.3: an incorrect (i.e. completed but rejected) claim adds 2 minutes to the
      // opponent's clock. Both rejected on-board claims and rejectedWithMove claims qualify;
      // invalid-SAN doesn't (the player hasn't actually claimed yet — they can re-prompt).
      clock.addPenaltyTime(side.getOppositeSide(), INCORRECT_CLAIM_PENALTY_MS);
      if (claimResult.moveToPerform().isPresent()) {
        mustExecuteMove = claimResult.moveToPerform().get();
        mustExecuteMoveSan = san;
        clock.startClock(side);
      }
    }

    // FIDE 9.5: a rejected claim is treated as a draw offer to the opponent. We register a
    // correct-time offer (the claimer is on the move) so it follows the standard accept /
    // reject / touch-piece-invalidation flow without going through the wrong-time escalation.
    if (claimResult.convertsToDrawOffer()) {
      drawOfferManager.offerDrawCorrectTime(side);
    }

    return claimResult;
  }

  // ===== Resignation =====

  /**
   * Player resigns.
   *
   * <p>
   * FIDE 5.1.2: a resignation is a loss unless the opponent could not checkmate by any series of legal moves, in which
   * case it is a draw. {@link Adjudicator} applies that exception; we use the QUICK variant (the live-play path -
   * bounded latency, drawing only when it can prove the opponent unwinnable) rather than the FULL analyzer, whose deep
   * helpmate search can cost hundreds of ms and is not warranted for a button press.
   *
   * <p>
   * On a draw the player-facing message splits the two cases the adjudicator folds together: a material shortage versus
   * a position unwinnable despite sufficient material (see {@link #drawReason(Side)}).
   */
  public synchronized GameResult resign(Side side) {
    final Side opponent = side.getOppositeSide();

    if (Adjudicator.adjudicateResignationQuick(board, side) == AdjudicationResult.DRAW) {
      terminationActor = side;
      drawExceptionByInsufficientMaterial = board.isInsufficientMaterial(opponent);
      final GameResult drawResult = new GameResult(GameResultType.RESIGNATION, Side.NONE,
          sideName(side) + " resigned, but because " + drawReason(opponent) + ", the game is a draw.");
      endGame(drawResult);
      return drawResult;
    }

    final GameResult lossResult = new GameResult(GameResultType.RESIGNATION, opponent,
        sideName(side) + " resigns. " + sideName(opponent) + " wins the game.");
    endGame(lossResult);
    return lossResult;
  }

  // ===== Moving an opponent's piece (A-007) =====

  // Dragging an opponent's piece on the board is never allowed; the arbiter escalates like the
  // other misconducts: notice + restore, notice + warning + restore, loss of the game on the
  // third time. Counted per player across the whole game.
  private static final int MOVED_OPPONENT_PIECE_LIMIT = 3;
  private final Map<Side, Integer> movedOpponentPieceCounts = new EnumMap<>(Side.class);

  // Passive information for the opponent produced by the latest escalation step (see the
  // face-to-face principle / info window below the clock); consumed by the server layer.
  private String pendingOpponentInfo;

  /** @return and clears the passive opponent-info text of the latest escalation step, if any. */
  public synchronized String consumePendingOpponentInfo() {
    final String info = pendingOpponentInfo;
    pendingOpponentInfo = null;
    return info;
  }

  private ArbiterResponse escalateMovedOpponentPiece(Side side, ArbiterResponse original) {
    final int count = movedOpponentPieceCounts.merge(side, 1, Integer::sum);
    if (count >= MOVED_OPPONENT_PIECE_LIMIT) {
      terminationActor = side;
      endGame(new GameResult(GameResultType.MOVED_OPPONENT_PIECE_GAME_LOST, side.getOppositeSide(),
          sideName(side) + " loses the game by repeatedly moving the opponent's pieces."));
      // The server sees the ENDED state and broadcasts gameEnded (with the personalised
      // messages) instead of restore instructions.
      return original;
    }
    if (count == MOVED_OPPONENT_PIECE_LIMIT - 1) {
      pendingOpponentInfo = "Your opponent again moved one of your pieces and has been warned: the next time"
          + " they move one of your pieces, they lose the game. The position must be restored.";
      final ArbiterResponse warning = ArbiterResponse.positionChange(
          "Position change: You moved an opponent's piece. That is not allowed. Please restore the position."
              + " Warning: the next time you move an opponent's piece, you lose the game.");
      return original.restorePosition().map(warning::withRestorePosition).orElse(warning);
    }
    pendingOpponentInfo = "Your opponent moved one of your pieces. The game is paused until the position"
        + " has been restored.";
    return original;
  }

  // ===== Wrong clock press (pressing the opponent's clock) =====

  // Real-world modeling: on a physical clock the wrong lever CAN be pressed. Escalation like the
  // other misconducts: the arbiter pauses the game and admonishes (the clock restarts after a
  // short pause), the second time with a warning, the third press loses the game. Counted per
  // player across the whole game.
  private static final int WRONG_CLOCK_PRESS_LIMIT = 3;
  private final Map<Side, Integer> wrongClockPressCounts = new EnumMap<>(Side.class);
  // Whose clock the arbiter must restart after the admonishment pause; null when no pause active.
  private Side wrongClockPressPausedFor;

  /**
   * Outcome of a press of the opponent's clock lever.
   *
   * @param offense      false when the press was a physical no-op (the opponent's lever was already down — their
   *                     clock was not running); nothing is counted or announced then
   * @param gameLost     true on the third offense — the game has been ended inside this call
   * @param message      arbiter message for the offender ({@code null} for a no-op)
   * @param opponentInfo passive info for the opponent ({@code null} for a no-op and for the game-ending press, which
   *                     speaks through {@code gameEnded} instead)
   */
  public record WrongClockPressOutcome(boolean offense, boolean gameLost, String message, String opponentInfo) {
  }

  /**
   * The player pressed their OPPONENT's clock lever. Physically meaningful only while the opponent's clock is
   * running (their lever up) — otherwise the lever is already down and nothing happens, exactly like a real clock.
   */
  public synchronized WrongClockPressOutcome pressOpponentClock(Side side) {
    final Side opponent = side.getOppositeSide();
    if (state != GameState.IN_PROGRESS || clock.getRunningFor() != opponent) {
      return new WrongClockPressOutcome(false, false, null, null);
    }
    final int count = wrongClockPressCounts.merge(side, 1, Integer::sum);
    if (count >= WRONG_CLOCK_PRESS_LIMIT) {
      terminationActor = side;
      endGame(new GameResult(GameResultType.WRONG_CLOCK_PRESS_GAME_LOST, opponent,
          sideName(side) + " loses the game by repeatedly pressing the opponent's clock."));
      return new WrongClockPressOutcome(true, true, null, null);
    }
    clock.stopClock();
    wrongClockPressPausedFor = opponent;
    if (count == WRONG_CLOCK_PRESS_LIMIT - 1) {
      return new WrongClockPressOutcome(true, false,
          "Please do not press your opponent's clock. The game is paused and will continue shortly."
              + " Warning: the next press of your opponent's clock loses the game.",
          "Your opponent pressed your clock and has been warned: the next press loses them the game."
              + " The game is paused; your clock will restart shortly.");
    }
    return new WrongClockPressOutcome(true, false,
        "Please do not press your opponent's clock. The game is paused and will continue shortly.",
        "Your opponent pressed your clock. The game is paused; your clock will restart shortly.");
  }

  /**
   * Ends the admonishment pause after a wrong clock press: restarts the clock of the side that was running before
   * the offense. No-op when the game ended meanwhile or no such pause is active.
   *
   * @return the side whose clock was restarted, or {@code null} when nothing happened
   */
  public synchronized Side resumeAfterWrongClockPress() {
    if (state != GameState.IN_PROGRESS || wrongClockPressPausedFor == null) {
      wrongClockPressPausedFor = null;
      return null;
    }
    final Side side = wrongClockPressPausedFor;
    wrongClockPressPausedFor = null;
    clock.startClock(side);
    return side;
  }

  // ===== Abandonment =====

  /**
   * A player abandoned the game (closed the browser / never reconnected). Adjudicated like a resignation, as chess
   * servers do: the leaver loses — unless the remaining player could not checkmate by any series of legal moves
   * (helpmate test via {@link Adjudicator}, QUICK variant like {@link #resign(Side)}), in which case it is a draw.
   *
   * @return the game result, or {@code null} when there is nothing to adjudicate (game not running)
   */
  public synchronized GameResult abandon(Side side) {
    if (state != GameState.IN_PROGRESS) {
      // Never started (reaper territory) or already decided (e.g. flag fell while they were gone).
      return null;
    }
    final Side opponent = side.getOppositeSide();
    terminationActor = side;

    if (Adjudicator.adjudicateResignationQuick(board, side) == AdjudicationResult.DRAW) {
      drawExceptionByInsufficientMaterial = board.isInsufficientMaterial(opponent);
      final GameResult drawResult = new GameResult(GameResultType.ABANDONMENT, Side.NONE,
          sideName(side) + " left the game, but because " + drawReason(opponent) + ", the game is a draw.");
      endGame(drawResult);
      return drawResult;
    }

    final GameResult lossResult = new GameResult(GameResultType.ABANDONMENT, opponent,
        sideName(side) + " left the game. " + sideName(opponent) + " wins the game.");
    endGame(lossResult);
    return lossResult;
  }

  // ===== Flag fall =====

  /**
   * Checks for flag fall. Should be called periodically.
   *
   * <p>
   * FIDE 6.9: a player who runs out of time loses unless the opponent could not checkmate by any series of legal moves,
   * in which case it is a draw. {@link Adjudicator} applies that exception; we use the QUICK variant for the same
   * live-play / latency reason as {@link #resign(Side)}. On a draw the message splits insufficient material from a
   * position unwinnable despite sufficient material (see {@link #drawReason(Side)}).
   */
  public synchronized Optional<GameResult> checkFlagFall() {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }

    clock.tick();

    for (final Side side : new Side[] { Side.WHITE, Side.BLACK }) {
      if (clock.isFlagFall(side)) {
        final Side opponent = side.getOppositeSide();

        final GameResult flagResult;
        if (Adjudicator.adjudicateFlagfallQuick(board, side) == AdjudicationResult.DRAW) {
          terminationActor = side;
          drawExceptionByInsufficientMaterial = board.isInsufficientMaterial(opponent);
          flagResult = new GameResult(GameResultType.FLAG_FALL, Side.NONE,
              sideName(side) + " flagged, but because " + drawReason(opponent) + ", the game is a draw.");
        } else {
          flagResult = new GameResult(GameResultType.FLAG_FALL, opponent,
              sideName(side) + " loses on time. " + sideName(opponent) + " wins the game.");
        }

        endGame(flagResult);
        return Optional.of(flagResult);
      }
    }

    return Optional.empty();
  }

  // ===== Automatic game endings =====

  /**
   * Game-ending detection that runs after every accepted move and on every auto-end board event. Only fast checks are
   * allowed here — never the full CUA (isDeadPositionFull / isUnwinnableFull). Insufficient material is detected via
   * the cheap structural test on the board; positions that are dead by exhaustive search but not by insufficient
   * material are not auto-ended (the players will end them via fivefold/75-move/stalemate or claim a draw).
   */
  private Optional<GameResult> checkAutomaticEndings() {
    // 1. Checkmate
    if (board.isCheckmate()) {
      final Side winner = board.getSideToMove().getOppositeSide();
      return Optional
          .of(new GameResult(GameResultType.CHECKMATE, winner, sideName(winner) + " won the game by checkmate."));
    }

    // 2. Stalemate
    if (board.isStalemate()) {
      return Optional.of(new GameResult(GameResultType.STALEMATE, Side.NONE, "The game is drawn by stalemate."));
    }

    // 3. Insufficient material (FIDE 9.4 / 5.2.2). Fast structural check; no search.
    if (board.isInsufficientMaterial()) {
      return Optional.of(new GameResult(GameResultType.DEAD_POSITION, Side.NONE,
          "The game is drawn by insufficient material. Neither player can checkmate."));
    }

    // 4. Fivefold repetition
    if (board.isFivefoldRepetition()) {
      return Optional.of(
          new GameResult(GameResultType.FIVEFOLD_REPETITION, Side.NONE, "The game is drawn by fivefold repetition."));
    }

    // 5. 75-move rule
    if (board.isSeventyFiveMove()) {
      return Optional
          .of(new GameResult(GameResultType.SEVENTY_FIVE_MOVE, Side.NONE, "The game is drawn by the 75-move rule."));
    }

    return Optional.empty();
  }

  // ===== Helpers =====

  private void startNewTurn() {
    this.currentSequence = new ActionSequence(board.getSideToMove());
    this.positionBeforeTurn = board.getBitboardPosition();
    this.restorationTargetPosition = positionBeforeTurn;
    this.removedSquaresThisTurn.clear();
    this.mustExecuteMove = null;
    this.mustExecuteMoveSan = null;
    this.claimMadeThisTurn = false;
    this.restorationFromReleasedPiece = false;
    // The wrong-time offer escalation is per move (a draw offer is only semi-illegal) — the
    // count never carries over, unlike the claim ladders.
    drawOfferManager.resetWrongTimeCountsForNewMove();
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
   * Reason clause for a flag-fall / resignation that {@link Adjudicator} ruled a draw: the would-be winner cannot mate.
   * The adjudicator folds two FIDE cases together; we split them for the player-facing message using the cheap
   * structural material test - a material shortage (lone king, K+B, K+N, ...) versus a position that is unwinnable
   * despite sufficient material (a blocked wall / fortress).
   *
   * @param opponent the would-be winner (the side that did not resign / flag)
   */
  private String drawReason(Side opponent) {
    return board.isInsufficientMaterial(opponent) ? sideName(opponent) + " has insufficient material to mate"
        : sideName(opponent) + " has no potential mate";
  }

  /**
   * Enters the "waiting for ready" state after an arbiter intervention. Both players must signal readiness before the
   * game continues.
   *
   * <p>
   * The released-piece rule window is reset here because, after a recovery handshake, prior in-turn events should not
   * retroactively bind the resumed play. In particular, a "legal-in-isolation" release that was actually invalid in
   * context (e.g. a pawn drop that violates an active touch-move obligation on a different piece) must not be treated
   * as a commitment after the recovery — otherwise the player can never satisfy the touch-move and the rule deadlocks.
   * Known trade-off: a player who commits a legal release and then triggers an unrelated arbiter intervention
   * (wrong-time draw, drawAcceptRejected, opponentClockPressed) before the clock press loses the FIDE 4.7 commitment
   * after the handshake. Acceptable in practice.
   */
  public synchronized void enterWaitingForReady() {
    this.waitingForReady = true;
    this.whiteReady = false;
    this.blackReady = false;
    currentSequence.resetReleasedPieceRule();
  }

  /**
   * Enters the restoration state after an invalid move. Board events are then monitored until the physical board
   * matches the position before the turn.
   */
  public synchronized void enterWaitingForRestoration() {
    enterWaitingForRestoration(positionBeforeTurn);
  }

  public synchronized void enterWaitingForRestoration(BitboardPosition restorationTargetPosition) {
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
    // Preserve the FIDE 4.7 released-piece commitment when THIS restoration was caused by a
    // released-piece violation: the committed move must stay final across revert cycles until the
    // player actually plays it and presses the clock. Illegal/touch-move restorations still reset
    // the rule window (see enterWaitingForReady's rationale) to avoid deadlocks.
    if (!restorationFromReleasedPiece) {
      currentSequence.resetReleasedPieceRule();
    }
    this.whiteReady = false;
    this.blackReady = false;
    if (autoResumeAfterRestore) {
      this.restorationResumePending = true;
      this.waitingForReady = false;
    } else {
      this.restorationResumePending = false;
      this.waitingForReady = true;
    }
  }

  /**
   * Restarts the clock after an automatic restoration pause.
   */
  public synchronized void resumeAfterRestorationDelay() {
    if (state == GameState.IN_PROGRESS && restorationResumePending && !waitingForReady && !waitingForRestoration) {
      restorationResumePending = false;
      clock.startClock(board.getSideToMove());
    }
  }

  /**
   * Continues after an intervention where the required reference position already matches the physical board. The
   * action sequence is deliberately preserved: for an incomplete castling move, the king release still binds the player
   * to finish castling by moving the rook.
   */
  public synchronized void continueWithoutRestoration() {
    if (state == GameState.IN_PROGRESS && !waitingForReady && !waitingForRestoration) {
      restorationResumePending = false;
      clock.startClock(board.getSideToMove());
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
      default -> {
        return false;
      }
    }
    if (whiteReady && blackReady) {
      waitingForReady = false;
      if (state == GameState.IN_PROGRESS) {
        clock.startClock(board.getSideToMove());
      }
      return true;
    }
    return false;
  }

  /**
   * Returns the position before the current turn, for restoring after an illegal move.
   */
  public synchronized BitboardPosition getRestorePosition() {
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

  public synchronized boolean isRestoredPosition(BitboardPosition position) {
    return restorationTargetPosition.equals(position);
  }

  public synchronized boolean isAutoResumeAfterRestore() {
    return autoResumeAfterRestore;
  }

  /**
   * @return whether the current (latched) restoration is the result of a released-piece commitment (FIDE 4.7) — the
   *         player's move is final and they only need to press the clock. Lets the server phrase the restore/resume
   *         messages so the player doesn't think they still have to move.
   */
  public synchronized boolean isRestorationFromReleasedPiece() {
    return restorationFromReleasedPiece;
  }

  // ===== PGN export =====

  public synchronized String exportPgn() {
    return PgnCreate.toPgnString(board);
  }

  // ===== Getters =====

  public synchronized GameState getState() {
    return state;
  }

  public synchronized GameResult getResult() {
    return result;
  }

  /** The side that resigned, flagged, or accepted a draw (for the personalised end-of-game message). */
  public synchronized Side getTerminationActor() {
    return terminationActor;
  }

  /** Whether the draw-by-exception is by insufficient material (vs. unwinnable despite material). */
  public synchronized boolean isDrawExceptionByInsufficientMaterial() {
    return drawExceptionByInsufficientMaterial;
  }

  public synchronized Side getHavingMove() {
    return board.getSideToMove();
  }

  public synchronized Board getBoard() {
    return board;
  }

  public synchronized ClockManager getClock() {
    return clock;
  }

  public synchronized DrawOfferManager getDrawOfferManager() {
    return drawOfferManager;
  }

  public synchronized BitboardPosition getPositionBeforeTurn() {
    return positionBeforeTurn;
  }

  public synchronized MoveSpecification getMustExecuteMove() {
    return mustExecuteMove;
  }
}
