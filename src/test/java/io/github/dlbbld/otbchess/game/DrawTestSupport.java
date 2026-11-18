// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;

/** Shared by tests in which a draw is agreed: FIDE 5.2.3 asks for a move by each player first. */
public final class DrawTestSupport {

  private DrawTestSupport() {
  }

  /**
   * Plays a2-a3 and a7-a6 with the clock presses in a game that stands at the initial position. The a-pawns are
   * out of the way of everything the draw tests do afterwards.
   */
  public static void bothPlayersMove(GameSession session) {
    move(session, Side.WHITE, Square.A2, Square.A3, Piece.WHITE_PAWN);
    move(session, Side.BLACK, Square.A7, Square.A6, Piece.BLACK_PAWN);
  }

  private static void move(GameSession session, Side side, Square from, Square to, Piece piece) {
    session.recordEvent(side, BoardEvent.dragMove(from, to, piece, 0));
    final var after = BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, piece).build();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(side, after).type());
  }
}
