// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

public enum ArbiterResponseType {

  // Valid move, accepted
  MOVE_ACCEPTED,

  // Move doesn't satisfy touch-move obligation
  TOUCH_MOVE_VIOLATION,

  // Player changed a position after releasing a piece on a legal move square
  RELEASED_PIECE_VIOLATION,

  // No legal move matches the board position
  ILLEGAL_MOVE,

  // Second illegal move — game lost
  ILLEGAL_MOVE_GAME_LOST,

  // Player did not complete a move (board unchanged or incomplete)
  INCOMPLETE_MOVE,

  // Mid-play intervention: player changed the position without making a legal move
  POSITION_CHANGE
}
