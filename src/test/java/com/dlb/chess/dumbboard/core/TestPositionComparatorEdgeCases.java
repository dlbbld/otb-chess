package com.dlb.chess.dumbboard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.model.LegalMove;

/**
 * Edge case tests for PositionComparator, covering scenarios that came up during
 * manual testing and GUI integration.
 */
class TestPositionComparatorEdgeCases {

  @Test
  void testMultiplePiecesMovedIsIllegal() {
    // Player moves two pieces (e.g. pawn e4 AND knight f3) — no single legal move produces this
    final Board board = new Board();
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN)
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertTrue(matches.isEmpty());
  }

  @Test
  void testPieceMissingFromBoardIsIllegal() {
    // Player removes a piece without replacing it (not a valid move)
    final Board board = new Board();
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).build();

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertTrue(matches.isEmpty());
  }

  @Test
  void testExtraPieceOnBoardIsIllegal() {
    // Player adds a piece to an empty square without moving
    final Board board = new Board();
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E4, Piece.WHITE_QUEEN).build();

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertTrue(matches.isEmpty());
  }

  @Test
  void testQueensideCastling() {
    final Board board = new Board();
    // Set up queenside castling: 1.d4 d5 2.Bf4 Bf5 3.Nc3 Nc6 4.Qd2 Qd7
    board.moveStrict("d4");
    board.moveStrict("d5");
    board.moveStrict("Bf4");
    board.moveStrict("Bf5");
    board.moveStrict("Nc3");
    board.moveStrict("Nc6");
    board.moveStrict("Qd2");
    board.moveStrict("Qd7");

    // White queenside castling: king e1->c1, rook a1->d1
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.A1, Piece.NONE)
        .createChangedPosition(Square.C1, Piece.WHITE_KING)
        .createChangedPosition(Square.D1, Piece.WHITE_ROOK).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);
    assertTrue(match.isPresent());
  }

  @Test
  void testPromotionWithCaptureDifferentPieces() {
    final Board board = new Board();
    // Set up: pawn on b7 can capture rook on a8 with promotion
    board.moveStrict("a4");
    board.moveStrict("b5");
    board.moveStrict("axb5");
    board.moveStrict("a6");
    board.moveStrict("bxa6");
    board.moveStrict("Bb7");
    board.moveStrict("axb7");
    board.moveStrict("Nc6");

    // Promote to rook (not queen)
    final BitboardPosition afterRook = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_ROOK).build();

    final Optional<LegalMove> matchRook = PositionComparator.findUniqueMatchingMove(board, afterRook);
    assertTrue(matchRook.isPresent());

    // Promote to bishop
    final BitboardPosition afterBishop = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_BISHOP).build();

    final Optional<LegalMove> matchBishop = PositionComparator.findUniqueMatchingMove(board, afterBishop);
    assertTrue(matchBishop.isPresent());
  }

  @Test
  void testRookMoveFirstIsNotCastling() {
    final Board board = new Board();
    // Set up so castling is possible
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");

    // Player moves rook first then king — this is TWO pieces moved, not castling
    // Rook h1->f1, king e1->g1 — position matches castling result BUT
    // rook move h1->f1 is a valid rook move on its own, not castling
    // The position with both pieces moved should match castling (position is identical)
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    // Position comparison finds castling as a match — the rook-first rule
    // is enforced by touch-move in the ArbiterEngine, not by position comparison
    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertEquals(1, matches.size());
  }

  @Test
  void testBlackMoveAfterWhite() {
    final Board board = new Board();
    board.moveStrict("e4");

    // Black plays e5
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E7, Piece.NONE)
        .createChangedPosition(Square.E5, Piece.BLACK_PAWN).build();

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);
    assertTrue(match.isPresent());
    assertEquals(Square.E7, match.get().moveSpecification().fromSquare());
    assertEquals(Square.E5, match.get().moveSpecification().toSquare());
  }
}
