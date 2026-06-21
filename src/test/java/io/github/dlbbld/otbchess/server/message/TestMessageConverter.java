package io.github.dlbbld.otbchess.server.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.server.message.MessageConverter;

class TestMessageConverter {

  @Test
  void testRoundTripInitialPosition() {
    final BitboardPosition initial = BitboardPosition.INITIAL_POSITION;
    final Map<String, String> map = MessageConverter.fromStaticPosition(initial);
    final BitboardPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(initial, reconstructed);
  }

  @Test
  void testRoundTripAfterMove() {
    // Position after 1.e4: e2 empty, e4 has white pawn
    final BitboardPosition afterE4 = BitboardPositions.from(BitboardPosition.INITIAL_POSITION)
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final Map<String, String> map = MessageConverter.fromStaticPosition(afterE4);
    final BitboardPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(afterE4, reconstructed);
  }

  @Test
  void testEmptyPosition() {
    final BitboardPosition empty = BitboardPosition.EMPTY_POSITION;
    final Map<String, String> map = MessageConverter.fromStaticPosition(empty);
    final BitboardPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(empty, reconstructed);
  }

  @Test
  void testMapContainsAllSquares() {
    final Map<String, String> map = MessageConverter.fromStaticPosition(BitboardPosition.INITIAL_POSITION);
    assertEquals(64, map.size());
    assertEquals("WHITE_ROOK", map.get("a1"));
    assertEquals("BLACK_KING", map.get("e8"));
    assertEquals("NONE", map.get("e4"));
  }

  @Test
  void testRoundTripPositionWithManyEmptySquares() {
    // Regression: the original bug was that NONE-to-NONE updates threw an error.
    // A position with mostly empty squares (endgame) must round-trip correctly.
    final BitboardPosition endgame = BitboardPositions.from(BitboardPosition.EMPTY_POSITION)
        .createChangedPosition(Square.E1, Piece.WHITE_KING).createChangedPosition(Square.E8, Piece.BLACK_KING)
        .createChangedPosition(Square.A1, Piece.WHITE_ROOK).build();

    final Map<String, String> map = MessageConverter.fromStaticPosition(endgame);
    final BitboardPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(endgame, reconstructed);
    assertEquals(Piece.WHITE_KING, reconstructed.get(Square.E1));
    assertEquals(Piece.BLACK_KING, reconstructed.get(Square.E8));
    assertEquals(Piece.WHITE_ROOK, reconstructed.get(Square.A1));
    assertEquals(Piece.NONE, reconstructed.get(Square.D4));
  }

  @Test
  void testRoundTripAfterCastling() {
    // Position after kingside castling: king g1, rook f1
    final BitboardPosition afterCastling = BitboardPositions.from(BitboardPosition.INITIAL_POSITION)
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING).createChangedPosition(Square.F1, Piece.WHITE_ROOK)
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final Map<String, String> map = MessageConverter.fromStaticPosition(afterCastling);
    final BitboardPosition reconstructed = MessageConverter.toStaticPosition(map);

    assertEquals(afterCastling, reconstructed);
  }
}
