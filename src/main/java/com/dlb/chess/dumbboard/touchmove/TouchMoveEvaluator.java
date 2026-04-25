package com.dlb.chess.dumbboard.touchmove;

import java.util.Optional;
import java.util.Set;

import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.dumbboard.event.ActionSequence;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.moves.utility.CastlingUtility;
import com.dlb.chess.model.LegalMove;

/**
 * Evaluates touch-move obligations from the action sequence.
 *
 * <p>Scans the action sequence from the beginning. For each touch/move event:
 * <ul>
 *   <li>If an own piece is touched and has legal moves from that square → OWN_PIECE obligation</li>
 *   <li>If an opponent piece is touched and can be legally captured → OPPONENT_PIECE obligation</li>
 * </ul>
 * The first obligation found in the sequence is the binding one.
 */
public class TouchMoveEvaluator {

  /**
   * Finds the first touch-move obligation in the action sequence.
   *
   * @param sequence the action sequence recorded during the player's turn
   * @param board    the board state before the player's turn (used to check legal moves)
   * @return the first binding touch-move obligation, or empty if none
   */
  public static Optional<TouchMoveObligation> findObligation(ActionSequence sequence, ApiBoard board) {
    final Side sideToMove = sequence.getSideToMove();
    final Set<LegalMove> legalMoves = board.getLegalMoveSet();

    for (final BoardEvent event : sequence.getEvents()) {
      final Optional<TouchMoveObligation> obligation = evaluateEvent(event, sideToMove, legalMoves);
      if (obligation.isPresent()) {
        return obligation;
      }
    }

    return Optional.empty();
  }

  private static Optional<TouchMoveObligation> evaluateEvent(BoardEvent event, Side sideToMove,
      Set<LegalMove> legalMoves) {

    final Piece piece = event.piece();
    if (piece == Piece.NONE) {
      return Optional.empty();
    }

    // Determine the square where the piece was touched
    final Square touchedSquare = determineTouchedSquare(event);
    if (touchedSquare == Square.NONE) {
      return Optional.empty();
    }

    final Side pieceSide = piece.getSide();

    if (pieceSide == sideToMove) {
      // Own piece touched: check if it has any legal moves from that square
      if (hasLegalMovesFromSquare(legalMoves, touchedSquare)) {
        return Optional.of(new TouchMoveObligation(TouchMoveType.OWN_PIECE, touchedSquare, piece));
      }
    } else {
      // Opponent piece touched: check if it can be legally captured
      if (canBeCapturedOnSquare(legalMoves, touchedSquare)) {
        return Optional.of(new TouchMoveObligation(TouchMoveType.OPPONENT_PIECE, touchedSquare, piece));
      }
    }

    return Optional.empty();
  }

  /**
   * Determines the square where the piece was touched, based on the event type.
   * For CLICK and REMOVE: the square of the piece.
   * For DRAG_MOVE and DRAG_CAPTURE: the source square (where the piece was picked up).
   * For RESTORE events: no touch-move applies (piece is from side area).
   */
  private static Square determineTouchedSquare(BoardEvent event) {
    return switch (event.type()) {
      case CLICK, REMOVE -> event.square();
      case DRAG_MOVE, DRAG_CAPTURE -> event.square();
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> Square.NONE;
    };
  }

  /**
   * Checks whether there are any legal moves originating from the given square.
   * For castling moves, the fromSquare is NONE in the MoveSpecification, so we
   * additionally check the king's castling origin square.
   */
  private static boolean hasLegalMovesFromSquare(Set<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().fromSquare() == square) {
        return true;
      }
      // Castling: MoveSpecification has fromSquare = NONE, but the king originates from a specific square
      if (CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())) {
        final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(legalMove.havingMove(),
            legalMove.moveSpecification());
        if (kingFrom == square) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Checks whether the piece on the given square can be legally captured.
   */
  private static boolean canBeCapturedOnSquare(Set<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().toSquare() == square && legalMove.pieceCaptured() != Piece.NONE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether a given legal move satisfies a touch-move obligation.
   *
   * @param obligation the touch-move obligation to satisfy
   * @param legalMove  the legal move to check
   * @return true if the move satisfies the obligation
   */
  public static boolean satisfiesObligation(TouchMoveObligation obligation, LegalMove legalMove) {
    return switch (obligation.type()) {
      case OWN_PIECE -> {
        // Must move the touched piece: the move must originate from the obligation square
        if (legalMove.moveSpecification().fromSquare() == obligation.square()) {
          yield true;
        }
        // Castling: king originates from the obligation square
        if (CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())) {
          final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(legalMove.havingMove(),
              legalMove.moveSpecification());
          yield kingFrom == obligation.square();
        }
        yield false;
      }
      case OPPONENT_PIECE ->
        // Must capture the touched opponent piece: the move must land on the obligation square and be a capture
        legalMove.moveSpecification().toSquare() == obligation.square() && legalMove.pieceCaptured() != Piece.NONE;
    };
  }
}
