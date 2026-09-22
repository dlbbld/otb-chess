// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.touchmove;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;

class TestFirstTouchInvariant {

  /** 1. g3 e5 2. Bg2 d5 3. Nf3 Nc6: White can castle short, the b1 knight can move. */
  private static Board userReportedBoard() {
    final Board board = new Board();
    for (final String san : new String[] { "g3", "e5", "Bg2", "d5", "Nf3", "Nc6" }) {
      board.moveStrict(san);
    }
    return board;
  }

  @Test
  void castlingAfterTouchingTheKnightStopsTheGame() {
    // The user-reported bug: the arbiter accepted O-O although the b1 knight was touched first.
    final Board board = userReportedBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.B3, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 1));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 2));

    final LegalMove castling = castling(board, CastlingMove.KING_SIDE);
    assertFalse(FirstTouchInvariant.isHonoured(sequence, board, castling));
    final IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> FirstTouchInvariant.verify(sequence, board, castling));
    assertTrue(e.getMessage().toLowerCase().contains("b1"), e.getMessage());

    assertDoesNotThrow(() -> FirstTouchInvariant.verify(sequence, board, move(board, Square.B1, Square.C3)));
  }

  @Test
  void kingThenRookCastlingHonoursTheKingTouch() {
    final Board board = userReportedBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.E1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.click(Square.H1, Piece.WHITE_ROOK, 1));

    assertTrue(FirstTouchInvariant.isHonoured(sequence, board, castling(board, CastlingMove.KING_SIDE)));
  }

  @Test
  void rookTouchedFirstIsNotHonouredByCastling() {
    // FIDE 4.4.2: castling is a king move, so it does not move the first touched rook.
    final Board board = userReportedBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.H1, Piece.WHITE_ROOK, 0));

    assertFalse(FirstTouchInvariant.isHonoured(sequence, board, castling(board, CastlingMove.KING_SIDE)));
  }

  @Test
  void unmovablePieceTouchedFirstDoesNotBind() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 0));

    assertTrue(FirstTouchInvariant.isHonoured(sequence, board, move(board, Square.E2, Square.E4)));
  }

  @Test
  void opponentPieceTouchedFirstMustBeCapturedByAnyPiece() {
    // Opponent knight b3 first, then an unrelated own pawn, then the c2 pawn that captures it.
    final Board board = Board.fromFenStrict("4k3/8/8/8/8/1n6/PPP1P3/4K3 w - - 0 1");
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.B3, Piece.BLACK_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    sequence.addEvent(BoardEvent.click(Square.C2, Piece.WHITE_PAWN, 2));

    assertTrue(FirstTouchInvariant.isHonoured(sequence, board, move(board, Square.C2, Square.B3)));
    assertTrue(FirstTouchInvariant.isHonoured(sequence, board, move(board, Square.A2, Square.B3)));
    assertFalse(FirstTouchInvariant.isHonoured(sequence, board, move(board, Square.E2, Square.E4)));
  }

  @Test
  void enPassantHonoursTheTouchedPawnOnItsOwnSquare() {
    final Board board = Board.fromFenStrict("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.remove(Square.D5, Piece.BLACK_PAWN, 0));

    assertTrue(FirstTouchInvariant.isHonoured(sequence, board, move(board, Square.E5, Square.D6)));
  }

  @Test
  void failedCastlingAttemptWithNoKingMoveBindsNeitherKingNorRook() {
    // Same position as the evaluator test: the king cannot move, so the attempt leaves any move free.
    final Board board = Board.fromFenStrict("k4r2/8/8/8/8/8/P2PP3/3QK2R w K - 0 1");
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.F1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.G1, Piece.WHITE_ROOK, 1));
    sequence.addEvent(BoardEvent.dragMove(Square.A2, Square.A3, Piece.WHITE_PAWN, 2));

    assertTrue(FirstTouchInvariant.isHonoured(sequence, board, move(board, Square.A2, Square.A3)));
  }

  private static LegalMove move(Board board, Square from, Square to) {
    return board.getLegalMoves().stream().filter(m -> m.moveSpecification().fromSquare() == from)
        .filter(m -> m.moveSpecification().toSquare() == to).findFirst().orElseThrow();
  }

  private static LegalMove castling(Board board, CastlingMove side) {
    return board.getLegalMoves().stream().filter(m -> m.moveSpecification().castlingMove() == side).findFirst()
        .orElseThrow();
  }
}
