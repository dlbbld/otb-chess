package com.dlb.chess.dumbboard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.Board;
import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.model.LegalMove;

class TestPositionComparator {

  @Test
  void testSimplePawnAdvanceOneSquare() {
    final ApiBoard board = new Board();

    // Simulate: white pawn from e2 to e3
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_PAWN);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E2, match.get().moveSpecification().fromSquare());
    assertEquals(Square.E3, match.get().moveSpecification().toSquare());
  }

  @Test
  void testSimplePawnAdvanceTwoSquares() {
    final ApiBoard board = new Board();

    // Simulate: white pawn from e2 to e4
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E2, match.get().moveSpecification().fromSquare());
    assertEquals(Square.E4, match.get().moveSpecification().toSquare());
  }

  @Test
  void testKnightMove() {
    final ApiBoard board = new Board();

    // Simulate: knight from g1 to f3
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.G1, match.get().moveSpecification().fromSquare());
    assertEquals(Square.F3, match.get().moveSpecification().toSquare());
    assertEquals(Piece.WHITE_KNIGHT, match.get().movingPiece());
  }

  @Test
  void testCapture() {
    final ApiBoard board = new Board();
    // Play 1.e4 e5 2.d4 to set up exd4 capture
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("d4");

    // Black's turn: exd4
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E5, Piece.NONE)
        .createChangedPosition(Square.D4, Piece.BLACK_PAWN);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E5, match.get().moveSpecification().fromSquare());
    assertEquals(Square.D4, match.get().moveSpecification().toSquare());
    assertEquals(Piece.WHITE_PAWN, match.get().pieceCaptured());
  }

  @Test
  void testCastlingKingside() {
    final ApiBoard board = new Board();
    // Play moves to enable white kingside castling
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nc6");
    board.performMove("Be2");
    board.performMove("Nf6");

    // White's turn: O-O (kingside castling)
    // King e1 -> g1, Rook h1 -> f1
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
  }

  @Test
  void testEnPassant() {
    final ApiBoard board = new Board();
    // Set up en passant: 1.e4 d5 2.e5 f5 (black pawn on f5 can be captured en passant)
    board.performMove("e4");
    board.performMove("d5");
    board.performMove("e5");
    board.performMove("f5");

    // White's turn: exf6 (en passant)
    // White pawn e5 -> f6, black pawn on f5 disappears
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E5, Piece.NONE)
        .createChangedPosition(Square.F5, Piece.NONE)
        .createChangedPosition(Square.F6, Piece.WHITE_PAWN);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E5, match.get().moveSpecification().fromSquare());
    assertEquals(Square.F6, match.get().moveSpecification().toSquare());
  }

  @Test
  void testPromotion() {
    final ApiBoard board = new Board();
    // Set up promotion: pawn reaches 7th rank, promotes by capturing on a8
    // 1.a4 b5 2.axb5 a6 3.bxa6 Bb7 4.axb7 Nc6 5.bxa8=Q
    board.performMove("a4");
    board.performMove("b5");
    board.performMove("axb5");
    board.performMove("a6");
    board.performMove("bxa6");
    board.performMove("Bb7");
    board.performMove("axb7");
    board.performMove("Nc6");

    // White's turn: bxa8=Q (promotion to queen, capturing the rook)
    // Pawn b7 disappears, queen appears on a8 (replacing black rook)
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_QUEEN);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.B7, match.get().moveSpecification().fromSquare());
    assertEquals(Square.A8, match.get().moveSpecification().toSquare());
  }

  @Test
  void testPromotionToKnightDistinctFromQueen() {
    final ApiBoard board = new Board();
    board.performMove("a4");
    board.performMove("b5");
    board.performMove("axb5");
    board.performMove("a6");
    board.performMove("bxa6");
    board.performMove("Bb7");
    board.performMove("axb7");
    board.performMove("Nc6");

    // White's turn: bxa8=N (promotion to knight, capturing the rook)
    final StaticPosition afterPositionKnight = board.getStaticPosition()
        .createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_KNIGHT);

    final Optional<LegalMove> matchKnight = PositionComparator.findUniqueMatchingMove(board, afterPositionKnight);

    assertTrue(matchKnight.isPresent());
    // Verify it's the knight promotion, not queen
    assertEquals(Square.A8, matchKnight.get().moveSpecification().toSquare());
  }

  @Test
  void testNoMatchIllegalPosition() {
    final ApiBoard board = new Board();

    // Create a position that cannot arise from any legal move
    // Move knight to an impossible square
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);

    assertTrue(matches.isEmpty());
  }

  @Test
  void testNoMatchSamePosition() {
    final ApiBoard board = new Board();

    // Same position as before: no move was made
    final StaticPosition afterPosition = board.getStaticPosition();

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);

    assertTrue(matches.isEmpty());
  }
}
