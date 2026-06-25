// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.touchmove;

public enum TouchMoveType {

  // Touched own piece that has legal moves — must move it
  OWN_PIECE,

  // Touched opponent piece that can be legally captured — must capture it
  OPPONENT_PIECE,

  // Touched own king and then own rook (FIDE 4.4.a), with castling on the touched
  // rook's side being legal — must perform the castling move on that side.
  CASTLING,

  // Touched an own piece and an opponent piece that the own piece can legally capture
  // (FIDE 4.3.3) — must capture that opponent piece with that own piece.
  SPECIFIC_CAPTURE
}
