package com.dlb.chess.dumbboard.arbiter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.dlb.chess.board.Board;
import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.ValidateNewMove;
import com.dlb.chess.board.model.UpdateSquare;
import com.dlb.chess.board.enums.CastlingMove;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.common.model.MoveSpecification;
import com.dlb.chess.dumbboard.castling.CastlingAttemptDetector;
import com.dlb.chess.dumbboard.core.PositionComparator;
import com.dlb.chess.dumbboard.event.ActionSequence;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.event.BoardEventType;
import com.dlb.chess.dumbboard.touchmove.TouchMoveEvaluator;
import com.dlb.chess.dumbboard.touchmove.TouchMoveObligation;
import com.dlb.chess.dumbboard.touchmove.TouchMoveType;
import com.dlb.chess.exceptions.InvalidMoveException;
import com.dlb.chess.model.LegalMove;
import com.dlb.chess.moves.utility.CastlingUtility;

/**
 * The arbiter engine combines touch-move evaluation with position comparison.
 *
 * <p>Called when the player presses the clock (or offers a draw, which also triggers evaluation).
 * Performs two-layer evaluation:
 * <ol>
 *   <li>Layer 1 (Position Comparison): does the board state correspond to a legal move?</li>
 *   <li>Layer 2 (Touch-Move): if a touch-move obligation exists, does the move satisfy it?</li>
 * </ol>
 */
public class ArbiterEngine {

  private final IllegalMoveTracker illegalMoveTracker;

  public ArbiterEngine() {
    this.illegalMoveTracker = new IllegalMoveTracker();
  }

  public ArbiterEngine(int maxIllegalMoves) {
    this.illegalMoveTracker = new IllegalMoveTracker(maxIllegalMoves);
  }

  public ArbiterEngine(IllegalMoveTracker illegalMoveTracker) {
    this.illegalMoveTracker = illegalMoveTracker;
  }

  public IllegalMoveTracker getIllegalMoveTracker() {
    return illegalMoveTracker;
  }

  /**
   * Side-effect-free check used by the auto-end path to find out whether a
   * released-piece commitment (FIDE 4.7) currently binds the player to a final
   * position that the given {@code afterPosition} does not satisfy. If true, no
   * code that bypasses {@link #evaluateClockPress} should accept the position
   * as a move — the committed move must be played via the normal clock-press
   * flow, where the violation is reported and the recovery flow runs.
   */
  public boolean hasReleasedPieceViolation(ApiBoard board, StaticPosition afterPosition, ActionSequence sequence) {
    return findReleasedPieceViolation(board, afterPosition, sequence).isPresent();
  }

  /**
   * Side-effect-free check: does the action sequence contain at least one release event whose
   * resulting position is part of a legal move from {@code positionBeforeTurn}? Equivalent to
   * "has the on-move player committed to a move via FIDE 4.7?" without comparing against any
   * particular {@code afterPosition}.
   */
  public boolean hasReleasedPieceCommitment(ApiBoard board, ActionSequence sequence) {
    StaticPosition currentPosition = board.getStaticPosition();
    for (final BoardEvent event : sequence.getEventsSinceReleasedPieceRuleReset()) {
      currentPosition = applyEvent(currentPosition, event);
      if (isReleaseOnBoard(event)) {
        final Set<StaticPosition> allowedFinalPositions = findAllowedFinalPositionsForRelease(board, event);
        if (!allowedFinalPositions.isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Evaluates the board state when the player presses the clock.
   *
   * @param board          the board state before this turn's move
   * @param afterPosition  the physical board state after the player's manipulations
   * @param sequence       the action sequence recorded during this turn
   * @return the arbiter's response
   */
  public ArbiterResponse evaluateClockPress(ApiBoard board, StaticPosition afterPosition, ActionSequence sequence) {
    final Side sideToMove = board.getHavingMove();

    final Optional<ReleasedPieceLock> releasedPieceViolation = findReleasedPieceViolation(board, afterPosition,
        sequence);
    if (releasedPieceViolation.isPresent()) {
      final ReleasedPieceLock lock = releasedPieceViolation.get();
      return ArbiterResponse.releasedPieceViolation(formatReleasedPieceViolation(lock), lock.releasePosition());
    }

    // Check if the board position even changed
    if (board.getStaticPosition().equals(afterPosition)) {
      return ArbiterResponse.incompleteMove("Please complete your move.");
    }

    // Layer 1: Position Comparison — find matching legal move
    final Set<LegalMove> matchingMoves = PositionComparator.findMatchingMoves(board, afterPosition);

    if (matchingMoves.isEmpty()) {
      // No legal move produces this position
      return handleIllegalMove(board, afterPosition, sequence, sideToMove);
    }

    // We have at least one matching move. Pick the first (should be unique in practice).
    final LegalMove matchedMove = matchingMoves.iterator().next();

    // Layer 2: Touch-Move Check
    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    if (obligation.isPresent()) {
      if (!TouchMoveEvaluator.satisfiesObligation(obligation.get(), matchedMove)) {
        return handleTouchMoveViolation(obligation.get(), sideToMove);
      }
    }

    // Move accepted
    return ArbiterResponse.moveAccepted(matchedMove);
  }

  private record ReleasedPieceLock(
      StaticPosition releasePosition,
      Set<StaticPosition> allowedFinalPositions,
      Piece piece,
      Square square) {
  }

  private static Optional<ReleasedPieceLock> findReleasedPieceViolation(ApiBoard board, StaticPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> attemptedCastling = inferPhysicalCastlingAttempt(board, afterPosition,
        sequence.getEventsSinceReleasedPieceRuleReset());
    if (attemptedCastling.isPresent() && shouldBypassReleasedPieceForInvalidCastlingAttempt(board,
        attemptedCastling.get())) {
      return Optional.empty();
    }

    StaticPosition currentPosition = board.getStaticPosition();
    Optional<ReleasedPieceLock> firstReleasedLegalPosition = Optional.empty();

    for (final BoardEvent event : sequence.getEventsSinceReleasedPieceRuleReset()) {
      currentPosition = applyEvent(currentPosition, event);

      if (firstReleasedLegalPosition.isEmpty() && isReleaseOnBoard(event)) {
        final Set<StaticPosition> allowedFinalPositions = findAllowedFinalPositionsForRelease(board, event);
        if (!allowedFinalPositions.isEmpty()) {
          firstReleasedLegalPosition = Optional.of(new ReleasedPieceLock(currentPosition, allowedFinalPositions,
              event.piece(), event.targetSquare()));
        }
      }
    }

    if (firstReleasedLegalPosition.isPresent()
        && !firstReleasedLegalPosition.get().allowedFinalPositions().contains(afterPosition)) {
      return firstReleasedLegalPosition;
    }
    return Optional.empty();
  }

  private static Set<StaticPosition> findAllowedFinalPositionsForRelease(ApiBoard board, BoardEvent event) {
    final Set<StaticPosition> result = new HashSet<>();
    for (final LegalMove legalMove : board.getLegalMoveSet()) {
      if (isReleasePartOfLegalMove(board.getHavingMove(), event, legalMove)) {
        result.add(Board.createPositionAfterMove(board.getStaticPosition(), board.getHavingMove(),
            legalMove.moveSpecification()));
      }
    }
    return result;
  }

  private static boolean isReleasePartOfLegalMove(Side havingMove, BoardEvent event, LegalMove legalMove) {
    if (event.piece() != legalMove.movingPiece()) {
      return false;
    }
    if (CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())) {
      return event.piece() == Piece.calculateKingPiece(havingMove)
          && event.square() == CastlingUtility.calculateKingCastlingFrom(havingMove, legalMove.moveSpecification())
          && event.targetSquare() == CastlingUtility.calculateKingCastlingTo(havingMove,
              legalMove.moveSpecification());
    }
    return event.square() == legalMove.moveSpecification().fromSquare()
        && event.targetSquare() == legalMove.moveSpecification().toSquare();
  }

  private static StaticPosition applyEvent(StaticPosition position, BoardEvent event) {
    final List<UpdateSquare> updates = new ArrayList<>();
    switch (event.type()) {
      case CLICK -> {
        return position;
      }
      case DRAG_MOVE, DRAG_CAPTURE -> {
        addUpdate(updates, position, event.square(), Piece.NONE);
        addUpdate(updates, position, event.targetSquare(), event.piece());
      }
      case REMOVE -> addUpdate(updates, position, event.square(), Piece.NONE);
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> addUpdate(updates, position, event.targetSquare(), event.piece());
    }
    if (updates.isEmpty()) {
      return position;
    }
    return position.createChangedPosition(updates);
  }

  private static void addUpdate(List<UpdateSquare> updates, StaticPosition position, Square square, Piece piece) {
    if (square != Square.NONE && position.get(square) != piece) {
      updates.add(new UpdateSquare(square, piece));
    }
  }

  private static boolean isReleaseOnBoard(BoardEvent event) {
    return switch (event.type()) {
      case DRAG_MOVE, DRAG_CAPTURE, RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED ->
          event.piece() != Piece.NONE && event.targetSquare() != Square.NONE;
      case CLICK, REMOVE -> false;
    };
  }

  private static String formatReleasedPieceViolation(ReleasedPieceLock lock) {
    final String pieceName = formatPieceName(lock.piece());
    final String squareName = lock.square().getName();
    return "Released-piece violation: You already released the " + pieceName + " on " + squareName
        + ", and that was a legal move. Under the released-piece rule, you cannot change this position anymore."
        + " Please put the " + pieceName + " back on " + squareName + " and press the clock.";
  }

  private ArbiterResponse handleIllegalMove(ApiBoard board, StaticPosition afterPosition, ActionSequence sequence,
      Side sideToMove) {
    illegalMoveTracker.recordIllegalMove(sideToMove);
    final Optional<String> reason = explainSimpleIllegalMove(board, afterPosition, sequence);

    if (illegalMoveTracker.isGameLost(sideToMove)) {
      final String sideName = sideToMove == Side.WHITE ? "White" : "Black";
      final int count = illegalMoveTracker.getIllegalMoveCount(sideToMove);
      final String ordinal = ordinalSuffix(count);
      return ArbiterResponse.illegalMoveGameLost(
          reason.map(ArbiterEngine::formatIllegalMoveReason).orElse("")
              + sideName + " loses the game. This was the " + count + ordinal + " illegal move by "
              + sideName + ".");
    }

    return ArbiterResponse.illegalMove(buildOngoingIllegalMoveMessage(sideToMove, reason));
  }

  /**
   * Builds the arbiter message for an illegal move that does NOT yet end the game. The
   * player is told which-numbered illegal move this was and how many remain — phrased as
   * "your next illegal move will lose the game" when there is exactly one remaining, and
   * "the Nth illegal move will lose the game" otherwise. With unlimited illegal moves
   * configured, only the count is reported.
   */
  private String buildOngoingIllegalMoveMessage(Side sideToMove, Optional<String> reason) {
    final int count = illegalMoveTracker.getIllegalMoveCount(sideToMove);
    final int max = illegalMoveTracker.getMaxIllegalMoves();
    final String countOrdinal = ordinalSuffix(count);
    final StringBuilder msg = new StringBuilder();
    msg.append(reason.map(ArbiterEngine::formatIllegalMoveReason).orElse("Illegal move. "));
    msg.append("This is your ").append(count).append(countOrdinal).append(" illegal move. ");
    if (!illegalMoveTracker.isUnlimited()) {
      final int remaining = max - count;
      if (remaining == 1) {
        msg.append("Your next illegal move will lose the game. ");
      } else {
        final String maxOrdinal = ordinalSuffix(max);
        msg.append("Your ").append(max).append(maxOrdinal).append(" illegal move will lose the game. ");
      }
    }
    msg.append("Please restore the position.");
    return msg.toString();
  }

  private record AttemptedMove(
      MoveSpecification moveSpecification,
      boolean castlingAttempt,
      Square kingReleaseSquare) {
  }

  private static Optional<String> explainSimpleIllegalMove(ApiBoard board, StaticPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> attemptedMove = inferAttemptedMove(board, afterPosition, sequence);
    if (attemptedMove.isEmpty()) {
      return Optional.empty();
    }

    final MoveSpecification moveSpecification = attemptedMove.get().moveSpecification();
    try {
      ValidateNewMove.validateNewMove(board, moveSpecification);
      final StaticPosition expectedPosition = Board.createPositionAfterMove(board.getStaticPosition(),
          board.getHavingMove(), moveSpecification);
      if (!expectedPosition.equals(afterPosition)) {
        return Optional.of("the move itself is legal, but the final board position is not correct");
      }
    } catch (final InvalidMoveException e) {
      return Optional.of(formatIllegalMoveExplanation(e.getMessage(), board, sequence, attemptedMove.get()));
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<AttemptedMove> inferAttemptedMove(ApiBoard board, StaticPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> castlingAttempt = inferPhysicalCastlingAttempt(board, afterPosition,
        sequence.getEvents());
    if (castlingAttempt.isPresent()) {
      return castlingAttempt;
    }
    return inferSimpleAttemptedMove(board, afterPosition, sequence);
  }

  private static Optional<AttemptedMove> inferPhysicalCastlingAttempt(ApiBoard board, StaticPosition afterPosition,
      List<BoardEvent> events) {
    return CastlingAttemptDetector.findPhysicalAttempt(board, afterPosition, events)
        .map(attempt -> new AttemptedMove(attempt.moveSpecification(), true, attempt.kingReleaseSquare()));
  }

  private static boolean shouldBypassReleasedPieceForInvalidCastlingAttempt(ApiBoard board, AttemptedMove attempt) {
    if (!attempt.castlingAttempt() || isCastlingLegal(board, attempt.moveSpecification())) {
      return false;
    }
    return !isKingReleaseLegalMove(board, attempt);
  }

  private static boolean isCastlingLegal(ApiBoard board, MoveSpecification moveSpecification) {
    try {
      ValidateNewMove.validateNewMove(board, moveSpecification);
      return true;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  private static boolean isKingReleaseLegalMove(ApiBoard board, AttemptedMove attempt) {
    if (attempt.kingReleaseSquare() == Square.NONE) {
      return false;
    }
    final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(board.getHavingMove(),
        attempt.moveSpecification());
    final Piece kingPiece = Piece.calculateKingPiece(board.getHavingMove());
    for (final LegalMove legalMove : board.getLegalMoveSet()) {
      if (!CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())
          && legalMove.movingPiece() == kingPiece
          && legalMove.moveSpecification().fromSquare() == kingFrom
          && legalMove.moveSpecification().toSquare() == attempt.kingReleaseSquare()) {
        return true;
      }
    }
    return false;
  }

  private static Optional<AttemptedMove> inferSimpleAttemptedMove(ApiBoard board, StaticPosition afterPosition,
      ActionSequence sequence) {
    BoardEvent moveEvent = null;

    for (final BoardEvent event : sequence.getEvents()) {
      if (event.type() == BoardEventType.CLICK) {
        continue;
      }
      if (event.type() != BoardEventType.DRAG_MOVE && event.type() != BoardEventType.DRAG_CAPTURE) {
        return Optional.empty();
      }
      if (moveEvent != null) {
        return Optional.empty();
      }
      moveEvent = event;
    }

    if (moveEvent == null || moveEvent.piece() == Piece.NONE || moveEvent.piece().getSide() != board.getHavingMove()
        || moveEvent.square() == Square.NONE || moveEvent.targetSquare() == Square.NONE
        || moveEvent.square() == moveEvent.targetSquare()) {
      return Optional.empty();
    }
    if (board.getStaticPosition().get(moveEvent.square()) != moveEvent.piece()) {
      return Optional.empty();
    }
    if (afterPosition.get(moveEvent.square()) != Piece.NONE
        || afterPosition.get(moveEvent.targetSquare()) != moveEvent.piece()) {
      return Optional.empty();
    }

    final MoveSpecification moveSpecification = createMoveSpecification(moveEvent);
    return Optional.of(new AttemptedMove(moveSpecification,
        CastlingUtility.calculateIsCastlingMove(moveSpecification),
        CastlingUtility.calculateIsCastlingMove(moveSpecification) ? moveEvent.targetSquare() : Square.NONE));
  }

  private static MoveSpecification createMoveSpecification(BoardEvent event) {
    final Optional<CastlingMove> castlingMove = inferCastlingMove(event);
    if (castlingMove.isPresent()) {
      return new MoveSpecification(castlingMove.get());
    }
    return new MoveSpecification(event.square(), event.targetSquare());
  }

  private static Optional<CastlingMove> inferCastlingMove(BoardEvent event) {
    if (event.piece() != Piece.WHITE_KING && event.piece() != Piece.BLACK_KING) {
      return Optional.empty();
    }
    if (event.square() == Square.E1 && event.targetSquare() == Square.G1
        || event.square() == Square.E8 && event.targetSquare() == Square.G8) {
      return Optional.of(CastlingMove.KING_SIDE);
    }
    if (event.square() == Square.E1 && event.targetSquare() == Square.C1
        || event.square() == Square.E8 && event.targetSquare() == Square.C8) {
      return Optional.of(CastlingMove.QUEEN_SIDE);
    }
    return Optional.empty();
  }

  private static String formatIllegalMoveExplanation(String reason, ApiBoard board, ActionSequence sequence,
      AttemptedMove attemptedMove) {
    final String formattedReason;
    if (attemptedMove.castlingAttempt() && !reason.startsWith("castling is not possible")) {
      formattedReason = "castling is not possible: " + reason;
    } else {
      formattedReason = reason;
    }
    return formattedReason + formatCastlingTouchMoveConsequence(board, sequence, attemptedMove);
  }

  private static String formatCastlingTouchMoveConsequence(ApiBoard board, ActionSequence sequence,
      AttemptedMove attemptedMove) {
    if (!attemptedMove.castlingAttempt()) {
      return "";
    }

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);
    final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(board.getHavingMove(),
        attemptedMove.moveSpecification());
    final Piece kingPiece = Piece.calculateKingPiece(board.getHavingMove());

    if (obligation.isPresent()) {
      final TouchMoveObligation value = obligation.get();
      if (value.type() == TouchMoveType.OWN_PIECE && value.square() == kingFrom && value.piece() == kingPiece) {
        return " Castling counts as a king move; because the king has legal moves, after restoring the position"
            + " you must make a legal move with the king.";
      }
      // A different first touch remains governed by the normal touch-move recovery path.
      return "";
    }

    return " Castling counts as a king move, but the touched king has no legal moves; after restoring the position"
        + " make another legal move.";
  }

  private static String formatIllegalMoveReason(String reason) {
    return "Illegal move: " + ensureSentence(reason) + " ";
  }

  private static String ensureSentence(String text) {
    final String trimmed = text.trim();
    if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
      return trimmed;
    }
    return trimmed + ".";
  }

  private static String ordinalSuffix(int n) {
    final int mod100 = n % 100;
    if (mod100 >= 11 && mod100 <= 13) {
      return "th";
    }
    return switch (n % 10) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }

  private ArbiterResponse handleTouchMoveViolation(TouchMoveObligation obligation, Side sideToMove) {
    final String pieceName = formatPieceName(obligation.piece());
    final String squareName = obligation.square().getName();

    return switch (obligation.type()) {
      case OWN_PIECE -> ArbiterResponse.touchMoveViolation(
          "Touch-move violation: You first touched the " + pieceName + " on " + squareName
              + ", which has legal moves, but moved another piece. Under the touch-move rule, you must move"
              + " the first touched piece. Please restore the position and move the " + pieceName
              + " from " + squareName + ".",
          obligation);
      case OPPONENT_PIECE -> ArbiterResponse.touchMoveViolation(
          "Touch-move violation: You have touched the opponent's " + pieceName + " on " + squareName
              + ". Because the " + pieceName + " can be captured, it must be captured."
              + " Please revert the position.",
          obligation);
    };
  }

  private static String formatPieceName(Piece piece) {
    return switch (piece.getPieceType()) {
      case KING -> "king";
      case QUEEN -> "queen";
      case ROOK -> "rook";
      case BISHOP -> "bishop";
      case KNIGHT -> "knight";
      case PAWN -> "pawn";
      default -> "piece";
    };
  }
}
