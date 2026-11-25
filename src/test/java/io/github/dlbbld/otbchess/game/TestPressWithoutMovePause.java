// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * The pause after a clock press without a move (FIDE 7.5.3): the arbiter stops the clock for the ruling, as for every
 * other one. With nothing to restore, the game goes on as it does after a restoration - the clock restarts by itself,
 * or both players say that they are ready. A press in the pause is not ruled on.
 */
class TestPressWithoutMovePause {

  private static GameSession session(int maxIllegalMoves, boolean autoResumeAfterRestore) {
    final var session = new GameSession(new TimeControl(600000, 0), maxIllegalMoves, autoResumeAfterRestore);
    session.startGame();
    return session;
  }

  private static ArbiterResponse pressWithoutMove(GameSession session) {
    return session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn());
  }

  private static BitboardPosition afterE4(GameSession session) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
  }

  @Test
  void aPressInThePauseIsNotRuledOnAndNotCounted() {
    final var session = session(2, true);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, pressWithoutMove(session).type());
    assertTrue(session.consumeClockPressRegistered());

    for (int click = 0; click < 3; click++) {
      final ArbiterResponse response = pressWithoutMove(session);
      assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
      assertTrue(response.renderedPlayerMessage().contains("wait until the clock restarts"),
          response.renderedPlayerMessage());
      assertFalse(session.consumeClockPressRegistered());
    }

    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(session.isPressWithoutMovePause());
  }

  @Test
  void aMoveMadeInThePauseIsAcceptedAfterTheRestart() {
    final var session = session(2, true);
    pressWithoutMove(session);
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, session.pressClockButton(Side.WHITE, afterE4(session)).type());
    assertTrue(session.resumeAfterPressWithoutMove(session.getPressWithoutMovePauseId()));

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4(session)).type());
    assertEquals(Side.BLACK, session.getClock().getRunningFor());
    assertFalse(session.isPressWithoutMovePause());
  }

  @Test
  void onlyThePauseTheRestartWasScheduledForIsEnded() {
    final var session = session(5, true);
    pressWithoutMove(session);
    final long first = session.getPressWithoutMovePauseId();
    assertTrue(session.resumeAfterPressWithoutMove(first));
    pressWithoutMove(session);

    // The callback of the first pause fires late, during the second one.
    assertFalse(session.resumeAfterPressWithoutMove(first));
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertTrue(session.isPressWithoutMovePause());

    assertTrue(session.resumeAfterPressWithoutMove(session.getPressWithoutMovePauseId()));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
    assertFalse(session.resumeAfterPressWithoutMove(session.getPressWithoutMovePauseId()), "a pause ends once");
  }

  @Test
  void withoutAutomaticResumeTheGameWaitsForBothPlayers() {
    final var session = session(2, false);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, pressWithoutMove(session).type());

    assertTrue(session.isWaitingForReady());
    assertFalse(session.isPressWithoutMovePause());
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    final ArbiterResponse inThePause = pressWithoutMove(session);
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, inThePause.type());
    assertTrue(inThePause.renderedPlayerMessage().contains("until both players are ready"));

    assertFalse(session.playerReady(Side.WHITE));
    assertTrue(session.playerReady(Side.BLACK));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());

    // The lever is up again: the next press without a move is the second illegal move.
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, pressWithoutMove(session).type());
  }

  @Test
  void thePauseEndsWithTheGame() {
    final var session = session(2, true);
    pressWithoutMove(session);
    final long pause = session.getPressWithoutMovePauseId();

    session.resign(Side.WHITE);

    assertFalse(session.isPressWithoutMovePause());
    assertFalse(session.resumeAfterPressWithoutMove(pause));
    assertEquals(Side.NONE, session.getClock().getRunningFor());
  }

  @Test
  void aRulingThatAsksForARestorationTakesThePauseOver() {
    final var session = session(5, true);
    pressWithoutMove(session);
    final long pause = session.getPressWithoutMovePauseId();

    // White moves a black pawn during the pause: the arbiter asks for the position to be restored.
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E7, Square.E6, Piece.BLACK_PAWN, 1))
        .isPresent());
    session.enterWaitingForRestoration();

    assertFalse(session.isPressWithoutMovePause());
    assertFalse(session.resumeAfterPressWithoutMove(pause), "the clock restarts after the restoration, not before");
    assertEquals(Side.NONE, session.getClock().getRunningFor());
  }
}
