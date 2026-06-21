package io.github.dlbbld.otbchess.event;

import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * Represents a single board manipulation event during a player's turn.
 *
 * @param type           the type of event
 * @param square         the square involved (source square for drags, clicked square for click)
 * @param targetSquare   the target square (for DRAG_MOVE, DRAG_CAPTURE, RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED);
 *                       Square.NONE for CLICK and REMOVE
 * @param piece          the piece being acted upon
 * @param displacedPiece the piece displaced from the target square (for DRAG_CAPTURE, RESTORE_TO_OCCUPIED); Piece.NONE
 *                       otherwise
 * @param timestampMs    the timestamp of the event in milliseconds
 */
public record BoardEvent(BoardEventType type, Square square, Square targetSquare, Piece piece, Piece displacedPiece,
    long timestampMs) {

  public static BoardEvent click(Square square, Piece piece, long timestampMs) {
    return new BoardEvent(BoardEventType.CLICK, square, Square.NONE, piece, Piece.NONE, timestampMs);
  }

  public static BoardEvent dragMove(Square fromSquare, Square toSquare, Piece piece, long timestampMs) {
    return new BoardEvent(BoardEventType.DRAG_MOVE, fromSquare, toSquare, piece, Piece.NONE, timestampMs);
  }

  public static BoardEvent dragCapture(Square fromSquare, Square toSquare, Piece piece, Piece displacedPiece,
      long timestampMs) {
    return new BoardEvent(BoardEventType.DRAG_CAPTURE, fromSquare, toSquare, piece, displacedPiece, timestampMs);
  }

  public static BoardEvent remove(Square square, Piece piece, long timestampMs) {
    return new BoardEvent(BoardEventType.REMOVE, square, Square.NONE, piece, Piece.NONE, timestampMs);
  }

  public static BoardEvent restoreToEmpty(Square toSquare, Piece piece, long timestampMs) {
    return new BoardEvent(BoardEventType.RESTORE_TO_EMPTY, Square.NONE, toSquare, piece, Piece.NONE, timestampMs);
  }

  public static BoardEvent restoreToOccupied(Square toSquare, Piece piece, Piece displacedPiece, long timestampMs) {
    return new BoardEvent(BoardEventType.RESTORE_TO_OCCUPIED, Square.NONE, toSquare, piece, displacedPiece,
        timestampMs);
  }
}
