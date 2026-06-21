package io.github.dlbbld.otbchess.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.model.LegalMove;

class TestPositionComparator {

  @Test
  void testSimplePawnAdvanceOneSquare() {
    final Board board = new Board();

    // Simulate: white pawn from e2 to e3
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E3, Piece.WHITE_PAWN).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E2, match.get().moveSpecification().fromSquare());
    assertEquals(Square.E3, match.get().moveSpecification().toSquare());
  }

  @Test
  void testSimplePawnAdvanceTwoSquares() {
    final Board board = new Board();

    // Simulate: white pawn from e2 to e4
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E2, match.get().moveSpecification().fromSquare());
    assertEquals(Square.E4, match.get().moveSpecification().toSquare());
  }

  @Test
  void testKnightMove() {
    final Board board = new Board();

    // Simulate: knight from g1 to f3
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.G1, match.get().moveSpecification().fromSquare());
    assertEquals(Square.F3, match.get().moveSpecification().toSquare());
    assertEquals(Piece.WHITE_KNIGHT, match.get().movingPiece());
  }

  @Test
  void testCapture() {
    final Board board = new Board();
    // Play 1.e4 e5 2.d4 to set up exd4 capture
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("d4");

    // Black's turn: exd4
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E5, Piece.NONE).createChangedPosition(Square.D4, Piece.BLACK_PAWN).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E5, match.get().moveSpecification().fromSquare());
    assertEquals(Square.D4, match.get().moveSpecification().toSquare());
    assertEquals(Piece.WHITE_PAWN, match.get().capturedPiece());
  }

  @Test
  void testCastlingKingside() {
    final Board board = new Board();
    // Play moves to enable white kingside castling
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");

    // White's turn: O-O (kingside castling)
    // King e1 -> g1, Rook h1 -> f1
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING).createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
  }

  @Test
  void testEnPassant() {
    final Board board = new Board();
    // Set up en passant: 1.e4 d5 2.e5 f5 (black pawn on f5 can be captured en passant)
    board.moveStrict("e4");
    board.moveStrict("d5");
    board.moveStrict("e5");
    board.moveStrict("f5");

    // White's turn: exf6 (en passant)
    // White pawn e5 -> f6, black pawn on f5 disappears
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E5, Piece.NONE).createChangedPosition(Square.F5, Piece.NONE)
        .createChangedPosition(Square.F6, Piece.WHITE_PAWN).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.E5, match.get().moveSpecification().fromSquare());
    assertEquals(Square.F6, match.get().moveSpecification().toSquare());
  }

  @Test
  void testPromotion() {
    final Board board = new Board();
    // Set up promotion: pawn reaches 7th rank, promotes by capturing on a8
    // 1.a4 b5 2.axb5 a6 3.bxa6 Bb7 4.axb7 Nc6 5.bxa8=Q
    board.moveStrict("a4");
    board.moveStrict("b5");
    board.moveStrict("axb5");
    board.moveStrict("a6");
    board.moveStrict("bxa6");
    board.moveStrict("Bb7");
    board.moveStrict("axb7");
    board.moveStrict("Nc6");

    // White's turn: bxa8=Q (promotion to queen, capturing the rook)
    // Pawn b7 disappears, queen appears on a8 (replacing black rook)
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE).createChangedPosition(Square.A8, Piece.WHITE_QUEEN).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);

    assertTrue(match.isPresent());
    assertEquals(Square.B7, match.get().moveSpecification().fromSquare());
    assertEquals(Square.A8, match.get().moveSpecification().toSquare());
  }

  @Test
  void testPromotionToKnightDistinctFromQueen() {
    final Board board = new Board();
    board.moveStrict("a4");
    board.moveStrict("b5");
    board.moveStrict("axb5");
    board.moveStrict("a6");
    board.moveStrict("bxa6");
    board.moveStrict("Bb7");
    board.moveStrict("axb7");
    board.moveStrict("Nc6");

    // White's turn: bxa8=N (promotion to knight, capturing the rook)
    final BitboardPosition afterPositionKnight = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE).createChangedPosition(Square.A8, Piece.WHITE_KNIGHT).build();

    final Optional<LegalMove> matchKnight = PositionComparator.findUniqueMatchingMove(board, afterPositionKnight);

    assertTrue(matchKnight.isPresent());
    // Verify it's the knight promotion, not queen
    assertEquals(Square.A8, matchKnight.get().moveSpecification().toSquare());
  }

  @Test
  void testNoMatchIllegalPosition() {
    final Board board = new Board();

    // Create a position that cannot arise from any legal move
    // Move knight to an impossible square
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);

    assertTrue(matches.isEmpty());
  }

  @Test
  void testNoMatchSamePosition() {
    final Board board = new Board();

    // Same position as before: no move was made
    final BitboardPosition afterPosition = board.getBitboardPosition();

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);

    assertTrue(matches.isEmpty());
  }
}
