// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * A square the arbiter points at with a ruling, drawn as a circle on both boards. Green marks a piece a touch
 * obliges the player to move or to capture (FIDE 4.3); red marks where an illegal move started and ended; blue marks
 * where the move started and ended that the player had already made when they changed the position again (FIDE 4.7).
 */
public record BoardMark(Square square, Color color) {

  public enum Color {
    GREEN, RED, BLUE
  }

  public static BoardMark green(Square square) {
    return new BoardMark(square, Color.GREEN);
  }

  public static BoardMark red(Square square) {
    return new BoardMark(square, Color.RED);
  }

  public static BoardMark blue(Square square) {
    return new BoardMark(square, Color.BLUE);
  }
}
