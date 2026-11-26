// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.placement;

import java.util.ArrayList;
import java.util.List;

import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * The measure of free placement (E-005): where a piece lies is the centre of its image, in whole units with
 * {@value #UNITS_PER_SQUARE} to a square, {@code x} along the files from a to h and {@code y} along the ranks from 1
 * to 8. The inner circle around that centre decides: inside one square the placement is clear, across a border it is
 * unclear, and with the centre outside the board the piece is off the board.
 *
 * <p>
 * The page draws a border around the 64 squares that is as wide as the radius of the inner circle. So a piece can
 * hang over the squares by half its circle, lying unclear on the squares at the rim, and it is off the board exactly
 * when its circle goes over the outer edge of the border: that is when its centre leaves the squares. The border
 * therefore needs no measure of its own.
 *
 * <p>
 * Everything is whole-number arithmetic, so that the page ({@code static/js/placement.js}) computes exactly the same.
 * The two are held together by {@code tests/placement-samples.json}, which both read in their tests.
 */
public final class PlacementMeasure {

  public static final int UNITS_PER_SQUARE = 1024;
  public static final int BOARD_UNITS = 8 * UNITS_PER_SQUARE;
  /** The placement exactness: the area of the inner circle as a percentage of the area of the piece image. */
  public static final int DEFAULT_EXACTNESS_PERCENT = 20;
  // The piece image is 56 of the 64 pixels of a square in each direction (static/css/board.css).
  private static final double PIECE_IMAGE_SHARE = 56.0 / 64.0;

  public enum Kind {
    CLEAR, UNCLEAR, OFF_BOARD
  }

  /**
   * @param kind    clear, unclear or off the board
   * @param square  the square the centre is on; {@link Square#NONE} off the board
   * @param squares the squares of the board the inner circle lies on, by file and then by rank; exactly the one
   *                square of a clear placement, none off the board
   */
  public record Measured(Kind kind, Square square, List<Square> squares) {

    public boolean liesOn(Square other) {
      return squares.contains(other);
    }
  }

  private PlacementMeasure() {
  }

  /** @return the radius of the inner circle in units, for an exactness in percent of the piece's area */
  public static int radiusFor(int exactnessPercent) {
    if (exactnessPercent < 1 || exactnessPercent > 100) {
      throw new IllegalArgumentException("exactness must be 1 to 100 percent");
    }
    return (int) Math.round(PIECE_IMAGE_SHARE * Math.sqrt(exactnessPercent / 100.0 / Math.PI) * UNITS_PER_SQUARE);
  }

  public static int centreOfFile(Square square) {
    return fileIndex(square) * UNITS_PER_SQUARE + UNITS_PER_SQUARE / 2;
  }

  public static int centreOfRank(Square square) {
    return rankIndex(square) * UNITS_PER_SQUARE + UNITS_PER_SQUARE / 2;
  }

  public static Measured measure(int x, int y, int radius) {
    if (x < 0 || x >= BOARD_UNITS || y < 0 || y >= BOARD_UNITS) {
      return new Measured(Kind.OFF_BOARD, Square.NONE, List.of());
    }
    final int file = x / UNITS_PER_SQUARE;
    final int rank = y / UNITS_PER_SQUARE;
    final int left = x % UNITS_PER_SQUARE;
    final int right = UNITS_PER_SQUARE - left;
    final int below = y % UNITS_PER_SQUARE;
    final int above = UNITS_PER_SQUARE - below;
    final Square square = squareAt(file, rank);
    // A centre exactly the radius away from a border is clear: the circle touches the border, it does not cross it.
    if (left >= radius && right >= radius && below >= radius && above >= radius) {
      return new Measured(Kind.CLEAR, square, List.of(square));
    }
    final List<Square> squares = new ArrayList<>();
    for (int f = file - 1; f <= file + 1; f++) {
      for (int r = rank - 1; r <= rank + 1; r++) {
        if (f < 0 || f > 7 || r < 0 || r > 7) {
          continue;
        }
        // The distance from the centre to the neighbouring square, per direction: none to the own file or rank.
        final long dx = f == file ? 0 : f < file ? left : right;
        final long dy = r == rank ? 0 : r < rank ? below : above;
        if (dx * dx + dy * dy < (long) radius * radius) {
          squares.add(squareAt(f, r));
        }
      }
    }
    return new Measured(Kind.UNCLEAR, square, List.copyOf(squares));
  }

  static Square squareAt(int file, int rank) {
    return Square.parse("" + (char) ('a' + file) + (char) ('1' + rank));
  }

  static int fileIndex(Square square) {
    return square.getName().charAt(0) - 'a';
  }

  static int rankIndex(Square square) {
    return square.getName().charAt(1) - '1';
  }
}
