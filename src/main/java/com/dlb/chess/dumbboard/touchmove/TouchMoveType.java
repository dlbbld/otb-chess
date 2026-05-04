package com.dlb.chess.dumbboard.touchmove;

public enum TouchMoveType {

  // Touched own piece that has legal moves — must move it
  OWN_PIECE,

  // Touched opponent piece that can be legally captured — must capture it
  OPPONENT_PIECE,

  // Touched own king and then own rook (FIDE 4.4.a), with castling on the touched
  // rook's side being legal — must perform the castling move on that side.
  CASTLING
}
