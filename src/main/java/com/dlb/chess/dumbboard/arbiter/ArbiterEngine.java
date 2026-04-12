package com.dlb.chess.dumbboard.arbiter;

import java.util.Optional;
import java.util.Set;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.dumbboard.core.PositionComparator;
import com.dlb.chess.dumbboard.event.ActionSequence;
import com.dlb.chess.dumbboard.touchmove.TouchMoveEvaluator;
import com.dlb.chess.dumbboard.touchmove.TouchMoveObligation;
import com.dlb.chess.dumbboard.touchmove.TouchMoveType;
import com.dlb.chess.model.LegalMove;

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

  public ArbiterEngine(IllegalMoveTracker illegalMoveTracker) {
    this.illegalMoveTracker = illegalMoveTracker;
  }

  public IllegalMoveTracker getIllegalMoveTracker() {
    return illegalMoveTracker;
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

    // Check if the board position even changed
    if (board.getStaticPosition().equals(afterPosition)) {
      return ArbiterResponse.incompleteMove("Please complete your move.");
    }

    // Layer 1: Position Comparison — find matching legal move
    final Set<LegalMove> matchingMoves = PositionComparator.findMatchingMoves(board, afterPosition);

    if (matchingMoves.isEmpty()) {
      // No legal move produces this position
      return handleIllegalMove(sideToMove);
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

  private ArbiterResponse handleIllegalMove(Side sideToMove) {
    illegalMoveTracker.recordIllegalMove(sideToMove);

    if (illegalMoveTracker.isGameLost(sideToMove)) {
      final String sideName = sideToMove == Side.WHITE ? "White" : "Black";
      return ArbiterResponse.illegalMoveGameLost(
          sideName + " loses the game. This was the second illegal move by " + sideName + ".");
    }

    return ArbiterResponse.illegalMove("Illegal move. Please revert the position.");
  }

  private ArbiterResponse handleTouchMoveViolation(TouchMoveObligation obligation, Side sideToMove) {
    final String pieceName = formatPieceName(obligation.piece());
    final String squareName = obligation.square().getName();

    return switch (obligation.type()) {
      case OWN_PIECE -> ArbiterResponse.touchMoveViolation(
          "Touch-move violation: You have touched the " + pieceName + " on " + squareName
              + ". Because the " + pieceName + " has legal moves, the " + pieceName
              + " must be moved. Please revert the position.",
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
