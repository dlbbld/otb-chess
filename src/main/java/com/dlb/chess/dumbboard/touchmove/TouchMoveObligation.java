package com.dlb.chess.dumbboard.touchmove;

import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Square;

/**
 * Represents a touch-move obligation arising from a player touching a piece.
 *
 * @param type   whether the obligation is for an own piece (must move it) or opponent piece (must capture it)
 * @param square the square of the touched piece (at the time of touch, in the position before the move)
 * @param piece  the piece that was touched
 */
public record TouchMoveObligation(TouchMoveType type, Square square, Piece piece) {
}
