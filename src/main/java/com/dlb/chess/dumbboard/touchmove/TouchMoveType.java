package com.dlb.chess.dumbboard.touchmove;

public enum TouchMoveType {

  // Touched own piece that has legal moves — must move it
  OWN_PIECE,

  // Touched opponent piece that can be legally captured — must capture it
  OPPONENT_PIECE
}
