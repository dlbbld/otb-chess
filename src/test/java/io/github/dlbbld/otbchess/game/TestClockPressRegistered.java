// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * Which presses of the own lever register (E-004): those of a lever that was up, which the arbiter rules on, whatever
 * the ruling. Both players hear these, and only these.
 */
class TestClockPressRegistered {

  private static GameSession session(long timeMs, boolean autoResumeAfterRestore) {
    final var session = new GameSession(new TimeControl(timeMs, 0), 2, autoResumeAfterRestore);
    session.startGame();
    return session;
  }

  private static GameSession session() {
    return session(600000, true);
  }

  private static BitboardPosition moved(GameSession session, Square from, Square to, Piece piece) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, piece).build();
  }

  private static BitboardPosition afterE4(GameSession session) {
    return moved(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
  }

  /** The knight from b1 to b3: no knight move. */
  private static BitboardPosition afterAnIllegalMove(GameSession session) {
    return moved(session, Square.B1, Square.B3, Piece.WHITE_KNIGHT);
  }

  // ----- registered: the arbiter rules on the move -----

  @Test
  void anAcceptedMoveRegistersAndIsReportedOnce() {
    final var session = session();

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterE4(session));

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertTrue(session.consumeClockPressRegistered());
    assertFalse(session.consumeClockPressRegistered(), "one press is heard once");
  }

  @Test
  void anIllegalMoveRegisters() {
    final var session = session();

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterAnIllegalMove(session));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertTrue(session.consumeClockPressRegistered());
  }

  @Test
  void aPressWithoutAMoveRegisters() {
    final var session = session();

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn());

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertTrue(session.consumeClockPressRegistered());
  }

  @Test
  void anIncompleteMoveRegisters() {
    final var session = session();
    assertFalse(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4").accepted());
    assertNotNull(session.getMustExecuteMove(), "the rejected claim binds the claimer to the move");

    // Another move than the one owed.
    final ArbiterResponse response = session.pressClockButton(Side.WHITE,
        moved(session, Square.D2, Square.D4, Piece.WHITE_PAWN));

    // The same type of answer as for a press that is not ruled on: only the session can tell them apart.
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertTrue(response.renderedPlayerMessage().contains("was not executed"), response.renderedPlayerMessage());
    assertTrue(session.consumeClockPressRegistered());
  }

  // ----- not registered: the lever was down, or the clock was not running for the player -----

  @Test
  void aPressWhenItIsNotThePlayersTurnDoesNotRegister() {
    final var session = session();

    final ArbiterResponse response = session.pressClockButton(Side.BLACK, session.getPositionBeforeTurn());

    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertFalse(session.consumeClockPressRegistered());
  }

  @Test
  void aPressAfterTheGameDoesNotRegister() {
    final var session = session();
    session.resign(Side.BLACK);
    assertEquals(GameState.ENDED, session.getState());

    session.pressClockButton(Side.WHITE, afterE4(session));

    assertFalse(session.consumeClockPressRegistered());
  }

  @Test
  void aPressThatMeetsAnExpiredClockDoesNotRegister() throws Exception {
    final var session = session(50, true);
    Thread.sleep(75);

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterE4(session));

    assertEquals("Your time has expired.", response.renderedPlayerMessage());
    assertFalse(session.consumeClockPressRegistered());
  }

  @Test
  void aPressWhileThePositionIsToBeRestoredDoesNotRegister() {
    final var session = session();
    session.pressClockButton(Side.WHITE, afterAnIllegalMove(session));
    assertTrue(session.consumeClockPressRegistered());
    session.enterWaitingForRestoration();

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterAnIllegalMove(session));

    assertTrue(response.renderedPlayerMessage().contains("restore the position"), response.renderedPlayerMessage());
    assertFalse(session.consumeClockPressRegistered());
  }

  @Test
  void aPressWhileBothPlayersAreAskedWhetherTheyAreReadyDoesNotRegister() {
    final var session = session(600000, false);
    session.pressClockButton(Side.WHITE, afterAnIllegalMove(session));
    assertTrue(session.consumeClockPressRegistered());
    session.enterWaitingForRestoration();
    session.completeRestoration();
    assertTrue(session.isWaitingForReady());

    session.pressClockButton(Side.WHITE, afterE4(session));

    assertFalse(session.consumeClockPressRegistered());
  }

  @Test
  void aPressDuringThePauseAfterAWrongClockPressDoesNotRegister() {
    final var session = session();
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());
    assertEquals(Side.NONE, session.getClock().getRunningFor());

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterE4(session));

    assertTrue(response.renderedPlayerMessage().contains("wait until the clock restarts"),
        response.renderedPlayerMessage());
    assertFalse(session.consumeClockPressRegistered());
  }

  @Test
  void aPressDuringTheDelayBeforeTheGameResumesRegisters() {
    final var session = session();
    session.pressClockButton(Side.WHITE, afterAnIllegalMove(session));
    session.consumeClockPressRegistered();
    session.enterWaitingForRestoration();
    session.completeRestoration();
    assertTrue(session.isRestorationResumePending(), "the clock restarts by itself after a short delay");

    // The press itself restarts the clock, and the arbiter rules on the move.
    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterE4(session));

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertTrue(session.consumeClockPressRegistered());
  }

  @Test
  void aPressThatDoesNotRegisterClearsAnEarlierOneThatWasNeverReported() {
    final var session = session();
    session.pressClockButton(Side.WHITE, afterE4(session));

    // White again, although Black is to move now.
    session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn());

    assertFalse(session.consumeClockPressRegistered());
  }
}
