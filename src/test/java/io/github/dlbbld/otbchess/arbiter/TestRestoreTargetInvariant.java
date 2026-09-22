// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;

class TestRestoreTargetInvariant {

  /** 1. b3 b6 2. Bb2 Bb7 3. Nc3 Nc6 4. e4 e5 5. Qh5 Qh4: O-O-O is legal for White. */
  private static Board queensideCastlingReadyBoard() {
    final Board board = new Board();
    for (final String san : new String[] { "b3", "b6", "Bb2", "Bb7", "Nc3", "Nc6", "e4", "e5", "Qh5", "Qh4" }) {
      board.moveStrict(san);
    }
    return board;
  }

  @Test
  void turnStartPositionIsValid() {
    final Board board = new Board();
    assertTrue(RestoreTargetInvariant.isValidTarget(board, board.getBitboardPosition()));
  }

  @Test
  void positionAfterOneLegalMoveIsValid() {
    final Board board = queensideCastlingReadyBoard();
    final BitboardPosition afterRc1 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.A1, Piece.NONE).createChangedPosition(Square.C1, Piece.WHITE_ROOK).build();
    assertTrue(RestoreTargetInvariant.isValidTarget(board, afterRc1));
  }

  @Test
  void castlingIntermediateWithOnlyTheKingMovedIsValid() {
    final Board board = queensideCastlingReadyBoard();
    final BitboardPosition kingOnC1 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.C1, Piece.WHITE_KING).build();
    assertDoesNotThrow(() -> RestoreTargetInvariant.verify(board, kingOnC1));
  }

  @Test
  void userReportedKingOnB1TargetStopsTheGame() {
    // The target the arbiter once handed out after Ke1-b1 Ra1-c1: no legal move leads there.
    final Board board = queensideCastlingReadyBoard();
    final BitboardPosition kingB1RookC1 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.B1, Piece.WHITE_KING)
        .createChangedPosition(Square.A1, Piece.NONE).createChangedPosition(Square.C1, Piece.WHITE_ROOK).build();
    assertFalse(RestoreTargetInvariant.isValidTarget(board, kingB1RookC1));
    assertThrows(IllegalStateException.class, () -> RestoreTargetInvariant.verify(board, kingB1RookC1));
  }

  @Test
  void twoMovesFromTurnStartAreInvalid() {
    final Board board = new Board();
    final BitboardPosition e4AndNf3 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN)
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();
    assertFalse(RestoreTargetInvariant.isValidTarget(board, e4AndNf3));
  }
}
