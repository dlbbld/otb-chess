// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.TimeControl;

class TestMidplayExpiryRegressions {
  @Test
  void expiredThirdOpponentPieceMoveIsAFlagFallDrawInsteadOfAMisconductLoss() throws Exception {
    final var session = new GameSession(new TimeControl(500, 0), 2, false,
        Board.fromFenStrict("4k3/8/8/8/8/8/8/R3K3 w - - 0 1"));
    session.startGame();
    final var position = session.getBoard().getBitboardPosition();
    for (int offense = 0; offense < 2; offense++) {
      assertTrue(session.recordEvent(Side.WHITE,
          BoardEvent.dragMove(Square.E8, Square.E7, Piece.BLACK_KING, offense)).isPresent());
      session.enterWaitingForRestoration();
      assertTrue(session.completeRestoration());
      assertFalse(session.playerReady(Side.WHITE));
      assertTrue(session.playerReady(Side.BLACK));
    }
    Thread.sleep(session.getClock().getRemainingTimeMs(Side.WHITE) + 25);

    assertTrue(session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E8, Square.E7, Piece.BLACK_KING, 2)).isEmpty());

    assertEquals(GameResultType.FLAG_FALL, session.getResult().type());
    assertEquals(Side.NONE, session.getResult().winner(), "Black's lone king cannot win on White's flag fall");
    assertEquals(Side.WHITE, session.getTerminationActor());
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertEquals(position, session.getBoard().getBitboardPosition());
    assertFalse(session.isWaitingForRestoration());
  }
}
