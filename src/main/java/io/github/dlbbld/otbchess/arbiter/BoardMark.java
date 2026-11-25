// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * A square the arbiter points at with a ruling, drawn as a circle on both boards. Green marks what the player has to
 * play: a piece a touch obliges them to move or to capture (FIDE 4.3), and the start and the end square of the move
 * they had already made when they changed the position again (FIDE 4.7). Red marks where an illegal move started and
 * ended.
 */
public record BoardMark(Square square, Color color) {

  public enum Color {
    GREEN, RED
  }

  public static BoardMark green(Square square) {
    return new BoardMark(square, Color.GREEN);
  }

  public static BoardMark red(Square square) {
    return new BoardMark(square, Color.RED);
  }
}
