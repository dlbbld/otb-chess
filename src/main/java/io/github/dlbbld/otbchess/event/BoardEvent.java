// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.event;

import java.util.List;

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
 * @param releasedOn     free placement (E-005) only: the squares the piece was let go across, when it was let go in
 *                       an unclear placement. Such a release is a {@code CLICK} - the piece was touched and still
 *                       counts on its square - that also says where it lies. Empty for every other event.
 */
public record BoardEvent(BoardEventType type, Square square, Square targetSquare, Piece piece, Piece displacedPiece,
    long timestampMs, List<Square> releasedOn) {

  public BoardEvent {
    releasedOn = List.copyOf(releasedOn);
  }

  public BoardEvent(BoardEventType type, Square square, Square targetSquare, Piece piece, Piece displacedPiece,
      long timestampMs) {
    this(type, square, targetSquare, piece, displacedPiece, timestampMs, List.of());
  }

  /**
   * A piece let go across the border of squares (free placement, E-005): it is touched, it stays on the square it
   * counted on, and the squares it lies on are what it may be bound to.
   */
  public static BoardEvent releaseUnclear(Square square, Piece piece, List<Square> releasedOn, long timestampMs) {
    return new BoardEvent(BoardEventType.CLICK, square, Square.NONE, piece, Piece.NONE, timestampMs, releasedOn);
  }

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
