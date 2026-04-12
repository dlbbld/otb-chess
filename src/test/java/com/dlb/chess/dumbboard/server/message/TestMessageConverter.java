package com.dlb.chess.dumbboard.server.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Square;

class TestMessageConverter {

  @Test
  void testRoundTripInitialPosition() {
    final StaticPosition initial = StaticPosition.INITIAL_POSITION;
    final Map<String, String> map = MessageConverter.fromStaticPosition(initial);
    final StaticPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(initial, reconstructed);
  }

  @Test
  void testRoundTripAfterMove() {
    // Position after 1.e4: e2 empty, e4 has white pawn
    final StaticPosition afterE4 = StaticPosition.INITIAL_POSITION
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final Map<String, String> map = MessageConverter.fromStaticPosition(afterE4);
    final StaticPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(afterE4, reconstructed);
  }

  @Test
  void testEmptyPosition() {
    final StaticPosition empty = StaticPosition.EMPTY_POSITION;
    final Map<String, String> map = MessageConverter.fromStaticPosition(empty);
    final StaticPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(empty, reconstructed);
  }

  @Test
  void testMapContainsAllSquares() {
    final Map<String, String> map = MessageConverter.fromStaticPosition(StaticPosition.INITIAL_POSITION);
    assertEquals(64, map.size());
    assertEquals("WHITE_ROOK", map.get("a1"));
    assertEquals("BLACK_KING", map.get("e8"));
    assertEquals("NONE", map.get("e4"));
  }

  @Test
  void testRoundTripPositionWithManyEmptySquares() {
    // Regression: the original bug was that NONE-to-NONE updates threw an error.
    // A position with mostly empty squares (endgame) must round-trip correctly.
    final StaticPosition endgame = StaticPosition.EMPTY_POSITION
        .createChangedPosition(Square.E1, Piece.WHITE_KING)
        .createChangedPosition(Square.E8, Piece.BLACK_KING)
        .createChangedPosition(Square.A1, Piece.WHITE_ROOK);

    final Map<String, String> map = MessageConverter.fromStaticPosition(endgame);
    final StaticPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(endgame, reconstructed);
    assertEquals(Piece.WHITE_KING, reconstructed.get(Square.E1));
    assertEquals(Piece.BLACK_KING, reconstructed.get(Square.E8));
    assertEquals(Piece.WHITE_ROOK, reconstructed.get(Square.A1));
    assertEquals(Piece.NONE, reconstructed.get(Square.D4));
  }

  @Test
  void testRoundTripAfterCastling() {
    // Position after kingside castling: king g1, rook f1
    final StaticPosition afterCastling = StaticPosition.INITIAL_POSITION
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK)
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final Map<String, String> map = MessageConverter.fromStaticPosition(afterCastling);
    final StaticPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(afterCastling, reconstructed);
  }
}
