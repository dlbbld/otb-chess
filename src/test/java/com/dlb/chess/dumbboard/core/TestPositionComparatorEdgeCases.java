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

/**
 * Edge case tests for PositionComparator, covering scenarios that came up during
 * manual testing and GUI integration.
 */
class TestPositionComparatorEdgeCases {

  @Test
  void testMultiplePiecesMovedIsIllegal() {
    // Player moves two pieces (e.g. pawn e4 AND knight f3) — no single legal move produces this
    final ApiBoard board = new Board();
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN)
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertTrue(matches.isEmpty());
  }

  @Test
  void testPieceMissingFromBoardIsIllegal() {
    // Player removes a piece without replacing it (not a valid move)
    final ApiBoard board = new Board();
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE);

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertTrue(matches.isEmpty());
  }

  @Test
  void testExtraPieceOnBoardIsIllegal() {
    // Player adds a piece to an empty square without moving
    final ApiBoard board = new Board();
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E4, Piece.WHITE_QUEEN);

    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertTrue(matches.isEmpty());
  }

  @Test
  void testQueensideCastling() {
    final ApiBoard board = new Board();
    // Set up queenside castling: 1.d4 d5 2.Bf4 Bf5 3.Nc3 Nc6 4.Qd2 Qd7
    board.performMove("d4");
    board.performMove("d5");
    board.performMove("Bf4");
    board.performMove("Bf5");
    board.performMove("Nc3");
    board.performMove("Nc6");
    board.performMove("Qd2");
    board.performMove("Qd7");

    // White queenside castling: king e1->c1, rook a1->d1
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.A1, Piece.NONE)
        .createChangedPosition(Square.C1, Piece.WHITE_KING)
        .createChangedPosition(Square.D1, Piece.WHITE_ROOK);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);
    assertTrue(match.isPresent());
  }

  @Test
  void testPromotionWithCaptureDifferentPieces() {
    final ApiBoard board = new Board();
    // Set up: pawn on b7 can capture rook on a8 with promotion
    board.performMove("a4");
    board.performMove("b5");
    board.performMove("axb5");
    board.performMove("a6");
    board.performMove("bxa6");
    board.performMove("Bb7");
    board.performMove("axb7");
    board.performMove("Nc6");

    // Promote to rook (not queen)
    final StaticPosition afterRook = board.getStaticPosition()
        .createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_ROOK);

    final Optional<LegalMove> matchRook = PositionComparator.findUniqueMatchingMove(board, afterRook);
    assertTrue(matchRook.isPresent());

    // Promote to bishop
    final StaticPosition afterBishop = board.getStaticPosition()
        .createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_BISHOP);

    final Optional<LegalMove> matchBishop = PositionComparator.findUniqueMatchingMove(board, afterBishop);
    assertTrue(matchBishop.isPresent());
  }

  @Test
  void testRookMoveFirstIsNotCastling() {
    final ApiBoard board = new Board();
    // Set up so castling is possible
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nc6");
    board.performMove("Be2");
    board.performMove("Nf6");

    // Player moves rook first then king — this is TWO pieces moved, not castling
    // Rook h1->f1, king e1->g1 — position matches castling result BUT
    // rook move h1->f1 is a valid rook move on its own, not castling
    // The position with both pieces moved should match castling (position is identical)
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK);

    // Position comparison finds castling as a match — the rook-first rule
    // is enforced by touch-move in the ArbiterEngine, not by position comparison
    final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, afterPosition);
    assertEquals(1, matches.size());
  }

  @Test
  void testBlackMoveAfterWhite() {
    final ApiBoard board = new Board();
    board.performMove("e4");

    // Black plays e5
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E7, Piece.NONE)
        .createChangedPosition(Square.E5, Piece.BLACK_PAWN);

    final Optional<LegalMove> match = PositionComparator.findUniqueMatchingMove(board, afterPosition);
    assertTrue(match.isPresent());
    assertEquals(Square.E7, match.get().moveSpecification().fromSquare());
    assertEquals(Square.E5, match.get().moveSpecification().toSquare());
  }
}
