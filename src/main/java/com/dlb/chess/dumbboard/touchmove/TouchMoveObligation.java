package com.dlb.chess.dumbboard.touchmove;

import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * Represents a touch-move obligation arising from a player touching a piece.
 *
 * @param type         the kind of obligation: must move own piece, must capture opponent piece,
 *                     or must castle on a specific side (FIDE 4.4.a).
 * @param square       the square of the primary touched piece (at the time of touch, in the
 *                     position before the move). For {@link TouchMoveType#CASTLING} this is the
 *                     king's starting square.
 * @param piece        the primary touched piece. For {@link TouchMoveType#CASTLING} this is the king.
 * @param castlingMove for {@link TouchMoveType#CASTLING}, the side the player must castle on
 *                     (KING_SIDE or QUEEN_SIDE). {@link CastlingMove#NONE} for the other types.
 */
public record TouchMoveObligation(TouchMoveType type, Square square, Piece piece, CastlingMove castlingMove) {

  /** Convenience constructor for non-castling obligations. */
  public TouchMoveObligation(TouchMoveType type, Square square, Piece piece) {
    this(type, square, piece, CastlingMove.NONE);
  }
}
