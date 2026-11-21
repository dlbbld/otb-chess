// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;

/** Which two squares the arbiter circles for an illegal move: only when the board shows exactly one move. */
class TestIllegalMoveSquares {

  private static final String PROMOTION = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1";
  private static final String BLACK_TO_MOVE = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

  private static List<Square> squares(Board board, BitboardPosition after, BoardEvent... events) {
    final var sequence = new ActionSequence(board.getSideToMove());
    for (final BoardEvent event : events) {
      sequence.addEvent(event);
    }
    return ArbiterEngine.illegalMoveSquares(board, after, sequence);
  }

  /** The position of the board with squares set: pairs of square, piece. */
  private static BitboardPosition with(Board board, Object... changes) {
    var builder = BitboardPositions.from(board.getBitboardPosition());
    for (int i = 0; i < changes.length; i += 2) {
      builder = builder.createChangedPosition((Square) changes[i], (Piece) changes[i + 1]);
    }
    return builder.build();
  }

  @Test
  void onePieceMovedToAnEmptySquare() {
    final var board = new Board();
    assertEquals(List.of(Square.B1, Square.B3),
        squares(board, with(board, Square.B1, Piece.NONE, Square.B3, Piece.WHITE_KNIGHT)));
    assertEquals(List.of(Square.E2, Square.E5),
        squares(board, with(board, Square.E2, Piece.NONE, Square.E5, Piece.WHITE_PAWN)));
  }

  @Test
  void onePiecePutOnAnOccupiedSquare() {
    final var board = new Board();
    // onto an own piece
    assertEquals(List.of(Square.A1, Square.A2),
        squares(board, with(board, Square.A1, Piece.NONE, Square.A2, Piece.WHITE_ROOK)));
    // onto an opponent piece, through the own pawn
    assertEquals(List.of(Square.C1, Square.C8),
        squares(board, with(board, Square.C1, Piece.NONE, Square.C8, Piece.WHITE_BISHOP)));
  }

  @Test
  void blackMovesAreSeenFromBlacksSide() {
    final var board = Board.fromFenStrict(BLACK_TO_MOVE);
    assertEquals(List.of(Square.G8, Square.G6),
        squares(board, with(board, Square.G8, Piece.NONE, Square.G6, Piece.BLACK_KNIGHT)));
    assertEquals(Side.BLACK, board.getSideToMove());
  }

  @Test
  void aPawnMayStandOnTheLastRankAsThePieceItWasPromotedTo() {
    final var board = Board.fromFenStrict(PROMOTION);
    // a7 to b8 is no pawn move, whatever stands there afterwards
    assertEquals(List.of(Square.A7, Square.B8),
        squares(board, with(board, Square.A7, Piece.NONE, Square.B8, Piece.WHITE_QUEEN)));
    // left as a pawn on the last rank
    assertEquals(List.of(Square.A7, Square.A8),
        squares(board, with(board, Square.A7, Piece.NONE, Square.A8, Piece.WHITE_PAWN)));
    // a king is no promotion piece
    assertEquals(List.of(), squares(board, with(board, Square.A7, Piece.NONE, Square.B8, Piece.WHITE_KING)));
  }

  @Test
  void aPawnReplacedByAnotherPieceBelowTheLastRankIsNoMove() {
    final var board = new Board();
    assertEquals(List.of(), squares(board, with(board, Square.E2, Piece.NONE, Square.E4, Piece.WHITE_QUEEN)));
  }

  @Test
  void anythingElseIsNotMarked() {
    final var board = new Board();
    // two pieces displaced
    assertEquals(List.of(), squares(board, with(board, Square.B1, Piece.NONE, Square.B3, Piece.WHITE_KNIGHT,
        Square.G1, Piece.NONE, Square.G3, Piece.WHITE_KNIGHT)));
    // a piece taken off the board
    assertEquals(List.of(), squares(board, with(board, Square.B1, Piece.NONE)));
    // an opponent piece moved
    assertEquals(List.of(), squares(board, with(board, Square.A7, Piece.NONE, Square.A6, Piece.BLACK_PAWN)));
    // an own piece moved and an opponent piece removed elsewhere
    assertEquals(List.of(), squares(board, with(board, Square.B1, Piece.NONE, Square.B3, Piece.WHITE_KNIGHT,
        Square.H7, Piece.NONE)));
    // nothing changed
    assertEquals(List.of(), squares(board, board.getBitboardPosition()));
  }

  @Test
  void aCastlingAttemptIsMarkedOnTheSquaresOfTheKing() {
    final var board = Board.fromFenStrict("r3k2r/pppppppp/8/8/8/5n2/PPPPP1PP/R3K2R w KQkq - 0 1");
    final BitboardPosition castled = with(board, Square.E1, Piece.NONE, Square.G1, Piece.WHITE_KING, Square.H1,
        Piece.NONE, Square.F1, Piece.WHITE_ROOK);
    assertEquals(List.of(Square.E1, Square.G1),
        squares(board, castled, BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0),
            BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1)));
  }

  // ----- what the events tell that the position does not -----

  @Test
  void twoPiecesOfTheSameKindThatChangedPlacesAreNotOneMove() {
    final var board = new Board();
    final BitboardPosition looksLikeB1ToG3 = with(board, Square.B1, Piece.NONE, Square.G3, Piece.WHITE_KNIGHT);
    // The knight from g1 went to g3, the one from b1 took its place.
    assertEquals(List.of(),
        squares(board, looksLikeB1ToG3, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0),
            BoardEvent.dragMove(Square.B1, Square.G1, Piece.WHITE_KNIGHT, 1)));
    // The same through the side area: the knight from g1 is taken off and comes back on g3.
    assertEquals(List.of(),
        squares(board, looksLikeB1ToG3, BoardEvent.remove(Square.G1, Piece.WHITE_KNIGHT, 0),
            BoardEvent.dragMove(Square.B1, Square.G1, Piece.WHITE_KNIGHT, 1),
            BoardEvent.restoreToEmpty(Square.G3, Piece.WHITE_KNIGHT, 2)));
  }

  @Test
  void onePieceMovedInSeveralStepsIsOneMove() {
    final var board = new Board();
    final BitboardPosition knightOnB3 = with(board, Square.B1, Piece.NONE, Square.B3, Piece.WHITE_KNIGHT);
    assertEquals(List.of(Square.B1, Square.B3),
        squares(board, knightOnB3, BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0),
            BoardEvent.dragMove(Square.B1, Square.B4, Piece.WHITE_KNIGHT, 1),
            BoardEvent.dragMove(Square.B4, Square.B3, Piece.WHITE_KNIGHT, 2)));
    // taken off the board and put back on another square
    assertEquals(List.of(Square.B1, Square.B3),
        squares(board, knightOnB3, BoardEvent.remove(Square.B1, Piece.WHITE_KNIGHT, 0),
            BoardEvent.restoreToEmpty(Square.B3, Piece.WHITE_KNIGHT, 1)));
    // another piece that was lifted and put back on its own square has not moved
    assertEquals(List.of(Square.B1, Square.B3),
        squares(board, knightOnB3, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0),
            BoardEvent.dragMove(Square.G3, Square.G1, Piece.WHITE_KNIGHT, 1),
            BoardEvent.dragMove(Square.B1, Square.B3, Piece.WHITE_KNIGHT, 2)));
  }

  @Test
  void aPromotionPieceFromTheSideAreaIsStillOneMove() {
    final var board = Board.fromFenStrict(PROMOTION);
    assertEquals(List.of(Square.A7, Square.B8),
        squares(board, with(board, Square.A7, Piece.NONE, Square.B8, Piece.WHITE_QUEEN),
            BoardEvent.dragMove(Square.A7, Square.B8, Piece.WHITE_PAWN, 0),
            BoardEvent.restoreToOccupied(Square.B8, Piece.WHITE_QUEEN, Piece.WHITE_PAWN, 1)));
  }

  @Test
  void whatWasDoneBeforeARestorationIsNoLongerOnTheBoard() {
    final var board = new Board();
    final var sequence = new ActionSequence(board.getSideToMove());
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.G1, Piece.WHITE_KNIGHT, 1));
    sequence.markPositionRestored();
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.B3, Piece.WHITE_KNIGHT, 2));

    assertEquals(List.of(Square.B1, Square.B3), ArbiterEngine.illegalMoveSquares(board,
        with(board, Square.B1, Piece.NONE, Square.B3, Piece.WHITE_KNIGHT), sequence));
  }

  @Test
  void aCastlingAttemptRepeatedAfterARestorationIsMarkedAgain() {
    final var board = Board.fromFenStrict("r3k2r/pppppppp/8/8/8/5n2/PPPPP1PP/R3K2R w KQkq - 0 1");
    final BitboardPosition castled = with(board, Square.E1, Piece.NONE, Square.G1, Piece.WHITE_KING, Square.H1,
        Piece.NONE, Square.F1, Piece.WHITE_ROOK);
    final var sequence = new ActionSequence(board.getSideToMove());
    for (int attempt = 0; attempt < 2; attempt++) {
      sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
      sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));
      assertEquals(List.of(Square.E1, Square.G1), ArbiterEngine.illegalMoveSquares(board, castled, sequence));
      sequence.markPositionRestored();
    }
  }

  @Test
  void eventsThatDoNotLeadToThePositionLeaveItToThePosition() {
    final var board = new Board();
    // The events are those of another position (a restoration to another one than the start of the turn).
    assertEquals(List.of(Square.B1, Square.B3),
        squares(board, with(board, Square.B1, Piece.NONE, Square.B3, Piece.WHITE_KNIGHT),
            BoardEvent.dragMove(Square.E4, Square.E5, Piece.WHITE_PAWN, 0)));
  }
}
