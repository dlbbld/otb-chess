package com.dlb.chess.dumbboard.server.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.board.model.UpdateSquare;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.event.BoardEventType;

/**
 * Converts between client JSON messages and domain types.
 */
public class MessageConverter {

  /**
   * Converts a client board state map to a StaticPosition.
   * The map is keyed by square name (e.g. "e2") with piece name values (e.g. "WHITE_PAWN" or "NONE").
   *
   * <p>Only non-NONE pieces are applied as updates to the empty position, because
   * {@code StaticPosition.createChangedPosition} does not allow setting a square
   * to the same piece it already contains (including NONE to NONE).
   */
  public static StaticPosition toStaticPosition(Map<String, String> boardState) {
    final List<UpdateSquare> updates = new ArrayList<>();

    for (final Map.Entry<String, String> entry : boardState.entrySet()) {
      final Piece piece = Piece.valueOf(entry.getValue());
      if (piece != Piece.NONE) {
        final Square square = Square.calculate(entry.getKey());
        updates.add(new UpdateSquare(square, piece));
      }
    }

    if (updates.isEmpty()) {
      return StaticPosition.EMPTY_POSITION;
    }
    return StaticPosition.EMPTY_POSITION.createChangedPosition(updates);
  }

  /**
   * Converts a StaticPosition to a map for the client.
   */
  public static Map<String, String> fromStaticPosition(StaticPosition position) {
    final Map<String, String> result = new java.util.LinkedHashMap<>();
    for (final Square square : Square.REAL) {
      result.put(square.getName(), position.get(square).name());
    }
    return result;
  }

  /**
   * Converts client event data to a BoardEvent.
   */
  public static BoardEvent toBoardEvent(String eventType, String square, String targetSquare, String piece,
      String displacedPiece) {
    final BoardEventType type = BoardEventType.valueOf(eventType);
    final Square sq = "NONE".equals(square) ? Square.NONE : Square.calculate(square);
    final Square targetSq = "NONE".equals(targetSquare) ? Square.NONE : Square.calculate(targetSquare);
    final Piece p = Piece.valueOf(piece);
    final Piece dp = Piece.valueOf(displacedPiece);
    final long timestamp = System.currentTimeMillis();

    return new BoardEvent(type, sq, targetSq, p, dp, timestamp);
  }
}
