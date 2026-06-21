package io.github.dlbbld.otbchess.arbiter;

import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.event.BoardEventType;

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
      BitboardPosition positionBeforeTurn, Set<Square> removedSquares) {

    final Optional<ArbiterResponse> positionChangeCheck = validateOpponentPiecePositionChange(event, sideToMove);
    if (positionChangeCheck.isPresent()) {
      return positionChangeCheck;
    }

    // Check for invalid piece restoration
    return validateRestoration(event, sideToMove, positionBeforeTurn, removedSquares);
  }

  /**
   * Checks if the player is trying to move an opponent's piece <em>on the board</em>
   * (which is never allowed, even as the start of a capture sequence). The arbiter
   * intervenes immediately.
   *
   * <p>What is allowed without intervention:
   * <ul>
   *   <li>{@link BoardEventType#CLICK} on an opponent piece — touch-move only, no
   *       position change.</li>
   *   <li>{@link BoardEventType#REMOVE} of an opponent piece — corresponds to the
   *       physical capture sequence: lift the opponent piece off the board, then move
   *       your own piece onto that square. The position-comparison at clock press
   *       evaluates whether the resulting position matches a legal capture; if not,
   *       the standard illegal-move flow handles it.</li>
   *   <li>{@link BoardEventType#RESTORE_TO_EMPTY} / {@link BoardEventType#RESTORE_TO_OCCUPIED}
   *       — handled by {@link #validateRestoration}; allows putting a piece back from
   *       the side area.</li>
   * </ul>
   *
   * <p>What is blocked: dragging an opponent piece from one square to another
   * ({@link BoardEventType#DRAG_MOVE}, {@link BoardEventType#DRAG_CAPTURE}). That is
   * never part of a legal sequence and the arbiter intervenes with a generic
   * "you cannot move opponent pieces" message.
   */
  private static Optional<ArbiterResponse> validateOpponentPiecePositionChange(BoardEvent event, Side sideToMove) {
    final Piece piece = event.piece();
    if (piece == Piece.NONE) {
      return Optional.empty();
    }

    if (event.type() != BoardEventType.DRAG_MOVE && event.type() != BoardEventType.DRAG_CAPTURE) {
      // CLICK, REMOVE, and the RESTORE_* cases either carry no commitment or are
      // covered by validateRestoration. Allowing REMOVE of an opponent piece
      // implements the physical capture-by-removal flow.
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
      BitboardPosition positionBeforeTurn, Set<Square> removedSquares) {

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
          "Position change: You changed the position. That is not allowed. Please restore the position."));
    }

    // It was removed during this turn — check if it's being restored to its correct square
    final Piece originalPiece = positionBeforeTurn.get(targetSquare);
    if (originalPiece != piece) {
      return Optional.of(ArbiterResponse.revertRestoration(
          "Position change: You changed the position. There was never that piece on this square."
              + " Please restore the position."));
    }

    return Optional.empty();
  }
}
