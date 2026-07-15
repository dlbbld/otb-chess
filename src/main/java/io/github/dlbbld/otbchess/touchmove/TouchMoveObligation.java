// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.touchmove;

import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * Represents a touch-move obligation arising from a player touching a piece.
 *
 * @param type                       the kind of obligation: must move own piece, must capture opponent piece, or must
 *                                   castle on a specific side (FIDE 4.4.a).
 * @param square                     the square of the primary touched piece (at the time of touch, in the position
 *                                   before the move). For {@link TouchMoveType#CASTLING} this is the king's starting
 *                                   square.
 * @param piece                      the primary touched piece. For {@link TouchMoveType#CASTLING} this is the king.
 * @param castlingMove               for {@link TouchMoveType#CASTLING}, the side the player must castle on (KING_SIDE or
 *                                   QUEEN_SIDE). {@link CastlingMove#NONE} for the other types.
 * @param toSquare                   for {@link TouchMoveType#SPECIFIC_CAPTURE}, the square of the opponent piece that
 *                                   must be captured. {@link Square#NONE} for the other types.
 * @param capturedPiece              for {@link TouchMoveType#SPECIFIC_CAPTURE}, the opponent piece that must be
 *                                   captured. {@link Piece#NONE} for the other types.
 * @param opponentTouchedFirst       for {@link TouchMoveType#SPECIFIC_CAPTURE}, whether the opponent piece was touched
 *                                   before the own piece. Used only to render the physical history in the right order.
 * @param precededByUnmovableOwnTouch for {@link TouchMoveType#OWN_PIECE}, whether the player touched one or more of
 *                                   their own pieces that had no legal moves <em>before</em> touching the binding piece.
 *                                   When true, the binding piece is not literally the first piece touched -- it is the
 *                                   first touched piece that can move -- and a more specific violation message is used.
 *                                   Always {@code false} for the other types.
 */
public record TouchMoveObligation(TouchMoveType type, Square square, Piece piece, CastlingMove castlingMove,
    Square toSquare, Piece capturedPiece, boolean opponentTouchedFirst, boolean precededByUnmovableOwnTouch) {

  /** Convenience constructor for own-piece / opponent-piece obligations. */
  public TouchMoveObligation(TouchMoveType type, Square square, Piece piece) {
    this(type, square, piece, CastlingMove.NONE, Square.NONE, Piece.NONE, false, false);
  }

  /** Convenience constructor for castling obligations. */
  public TouchMoveObligation(TouchMoveType type, Square square, Piece piece, CastlingMove castlingMove) {
    this(type, square, piece, castlingMove, Square.NONE, Piece.NONE, false, false);
  }

  /**
   * Creates a {@link TouchMoveType#SPECIFIC_CAPTURE} obligation (FIDE 4.3.3): the player must capture
   * {@code capturedPiece} on {@code toSquare} using {@code ownPiece} from {@code fromSquare}.
   */
  public static TouchMoveObligation specificCapture(Square fromSquare, Piece ownPiece, Square toSquare,
      Piece capturedPiece) {
    return specificCapture(fromSquare, ownPiece, toSquare, capturedPiece, false);
  }

  /**
   * Creates a {@link TouchMoveType#SPECIFIC_CAPTURE} obligation while preserving the order in which the pieces were
   * touched, so the violation message describes the actual physical sequence.
   */
  public static TouchMoveObligation specificCapture(Square fromSquare, Piece ownPiece, Square toSquare,
      Piece capturedPiece, boolean opponentTouchedFirst) {
    return new TouchMoveObligation(TouchMoveType.SPECIFIC_CAPTURE, fromSquare, ownPiece, CastlingMove.NONE, toSquare,
        capturedPiece, opponentTouchedFirst, false);
  }

  /**
   * Returns a copy of this obligation with {@link #precededByUnmovableOwnTouch()} set to {@code true}. Used by the
   * evaluator when the binding own piece was reached only after the player touched own pieces that had no legal moves.
   */
  public TouchMoveObligation asPrecededByUnmovableOwnTouch() {
    return new TouchMoveObligation(type, square, piece, castlingMove, toSquare, capturedPiece, opponentTouchedFirst,
        true);
  }
}
