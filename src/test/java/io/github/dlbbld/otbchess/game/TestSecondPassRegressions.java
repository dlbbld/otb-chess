// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

class TestSecondPassRegressions {
  private static GameSession session(boolean autoResume) {
    final var session = new GameSession(new TimeControl(60000, 0), 2, autoResume);
    session.startGame();
    return session;
  }

  private static BitboardPosition pawnTo(GameSession session, Square to) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(to, Piece.WHITE_PAWN).build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"restoring", "ready", "delayed"})
  void offersDuringRecoveryDoNotCountThePendingIllegalMoveAgain(String phase) {
    final var session = session(phase.equals("delayed"));
    final var illegal = pawnTo(session, Square.E5);
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E5, Piece.WHITE_PAWN, 0));
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, session.pressClockButton(Side.WHITE, illegal).type());
    session.enterWaitingForRestoration();
    if (!phase.equals("restoring")) session.completeRestoration();
    final long blackTime = session.getClock().getRemainingTimeMs(Side.BLACK);
    for (int i = 0; i < 3; i++) {
      final var response = session.offerDrawCorrectTime(Side.WHITE, illegal);
      assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
      assertTrue(response.renderedPlayerMessage().contains("paused"));
      assertFalse(session.offerDrawWrongTime(Side.WHITE).accepted());
      assertFalse(session.offerDrawWrongTime(Side.BLACK).accepted());
    }
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertNull(session.getResult());
    assertEquals(blackTime, session.getClock().getRemainingTimeMs(Side.BLACK));
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertFalse(session.getDrawOfferManager().isDrawOffered());
    if (phase.equals("restoring")) session.completeRestoration();
    if (phase.equals("delayed")) session.resumeAfterRestorationDelay();
    else { session.playerReady(Side.WHITE); session.playerReady(Side.BLACK); }
    assertTrue(session.offerDrawWrongTime(Side.BLACK).accepted(), "blocked offers must not consume escalation counts");
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, pawnTo(session, Square.E4)).type());
  }

  @Test
  void oldWrongClockCallbackCannotOverrideRestorationReadinessOrTheNextTurn() {
    final var session = session(false);
    final long pause = session.pressOpponentClock(Side.BLACK).pauseId();
    assertTrue(session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E7, Square.E6, Piece.BLACK_PAWN, 0)).isPresent());
    assertNull(session.resumeAfterWrongClockPress(pause), "the new intervention immediately cancels the old pause");
    session.enterWaitingForRestoration();
    assertNull(session.resumeAfterWrongClockPress(pause));
    session.completeRestoration();
    assertNull(session.resumeAfterWrongClockPress(pause));
    assertTrue(session.isWaitingForReady());
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    session.playerReady(Side.WHITE); session.playerReady(Side.BLACK);
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, pawnTo(session, Square.E4)).type());
    assertNull(session.resumeAfterWrongClockPress(pause));
    assertEquals(Side.BLACK, session.getClock().getRunningFor());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void obsoleteCallbackCannotConsumeANewerWrongClockPause() {
    final var session = session(false);
    final long oldPause = session.pressOpponentClock(Side.BLACK).pauseId();
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE); session.playerReady(Side.BLACK);
    final long newPause = session.pressOpponentClock(Side.BLACK).pauseId();
    assertTrue(newPause > oldPause);
    assertNull(session.resumeAfterWrongClockPress(oldPause));
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress(newPause));
    assertNull(session.resumeAfterWrongClockPress(newPause), "a callback only resumes once");
  }
}
