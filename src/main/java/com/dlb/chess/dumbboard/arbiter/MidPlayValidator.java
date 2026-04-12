package com.dlb.chess.dumbboard.arbiter;

import java.util.Optional;
import java.util.Set;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.event.BoardEventType;

/**
 * Validates board events during play (before clock press).
 *
 * <p>Two mid-play interventions exist:
 * <ol>
 *   <li>Moving an opponent piece → immediate arbiter intervention</li>
 *   <li>Invalid piece restoration from side area → immediate arbiter intervention</li>
 * </ol>
 */
public class MidPlayValidator {

  /**
   * Validates a board event during play. Returns an arbiter response if intervention is needed.
   *
   * @param event              the board event to validate
   * @param sideToMove         whose turn it is
   * @param positionBeforeTurn the board position at the start of this turn
   * @param removedSquares     squares from which opponent pieces were removed during this turn
   * @return an arbiter response if intervention is needed, empty otherwise
   */
  public static Optional<ArbiterResponse> validate(BoardEvent event, Side sideToMove,
      StaticPosition positionBeforeTurn, Set<Square> removedSquares) {

    // Check for moving opponent pieces
    final Optional<ArbiterResponse> opponentCheck = validateNotMovingOpponentPiece(event, sideToMove);
    if (opponentCheck.isPresent()) {
      return opponentCheck;
    }

    // Check for invalid piece restoration
    return validateRestoration(event, sideToMove, positionBeforeTurn, removedSquares);
  }

  /**
   * Checks if the player is trying to move an opponent's piece.
   * Only applies to CLICK, DRAG_MOVE, DRAG_CAPTURE, and REMOVE events.
   */
  private static Optional<ArbiterResponse> validateNotMovingOpponentPiece(BoardEvent event, Side sideToMove) {
    final Piece piece = event.piece();
    if (piece == Piece.NONE) {
      return Optional.empty();
    }

    // Only check events that involve picking up a piece from the board
    if (event.type() == BoardEventType.RESTORE_TO_EMPTY || event.type() == BoardEventType.RESTORE_TO_OCCUPIED) {
      return Optional.empty();
    }

    if (piece.getSide() != sideToMove) {
      return Optional.of(ArbiterResponse.revertOpponentPiece());
    }

    return Optional.empty();
  }

  /**
   * Validates piece restoration from the side area.
   *
   * <p>Rules:
   * <ol>
   *   <li>Own pieces cannot be restored to the board (exception: promotion piece placement)</li>
   *   <li>Opponent pieces not removed during this turn cannot be restored</li>
   *   <li>Opponent pieces removed during this turn can only be restored to their correct square</li>
   * </ol>
   */
  private static Optional<ArbiterResponse> validateRestoration(BoardEvent event, Side sideToMove,
      StaticPosition positionBeforeTurn, Set<Square> removedSquares) {

    if (event.type() != BoardEventType.RESTORE_TO_EMPTY && event.type() != BoardEventType.RESTORE_TO_OCCUPIED) {
      return Optional.empty();
    }

    final Piece piece = event.piece();
    final Square targetSquare = event.targetSquare();

    // Own piece restored to board
    if (piece.getSide() == sideToMove) {
      // Promotion is an exception — handled separately in the game session
      // For now, we allow it and let the position check at clock press handle validity
      // TODO: revisit if we need to distinguish promotion piece placement here
      return Optional.empty();
    }

    // Opponent piece: was it removed during this turn?
    if (!removedSquares.contains(targetSquare)) {
      // Check if this piece belongs on this square in the original position
      final Piece originalPiece = positionBeforeTurn.get(targetSquare);
      if (originalPiece == piece) {
        // Restoring to the correct square — valid
        return Optional.empty();
      }
      return Optional.of(ArbiterResponse.revertRestoration(
          "You cannot alter the position. Please revert."));
    }

    // It was removed during this turn — check if it's being restored to its correct square
    final Piece originalPiece = positionBeforeTurn.get(targetSquare);
    if (originalPiece != piece) {
      return Optional.of(ArbiterResponse.revertRestoration(
          "You cannot alter the position. There was never a piece on this square."));
    }

    return Optional.empty();
  }
}
