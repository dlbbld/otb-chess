// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;

class TestReviewRegressions {
  @Test
  void clockSwitchCannotAddIncrementAfterExpiry() throws Exception {
    final var clock = new ClockManager(new TimeControl(20, 2000));
    clock.startClock(Side.BLACK);
    Thread.sleep(30);
    assertFalse(clock.switchClock());
    assertEquals(0, clock.getRemainingTimeMs(Side.BLACK));
    assertEquals(Side.BLACK, clock.getRunningFor());
    assertEquals(20, clock.getRemainingTimeMs(Side.WHITE));
  }

  @Test
  void expiredClockCannotBeRevivedByIncrement() throws Exception {
    final var session = new GameSession(new TimeControl(50, 2000));
    session.startGame();
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final var original = session.getPositionBeforeTurn();
    final var after = BitboardPositions.from(original).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    Thread.sleep(75);
    assertEquals(0, session.getClock().getRemainingTimeMs(Side.WHITE));
    assertNotEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, after).type());
    assertEquals(GameResultType.FLAG_FALL, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
    assertEquals(original, session.getBoard().getBitboardPosition());
    assertEquals(0, session.getClock().getRemainingTimeMs(Side.WHITE));
  }

  @Test
  void liveClockStillReceivesIncrement() {
    final var session = new GameSession(new TimeControl(60000, 2000));
    session.startGame();
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final var after = BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, after).type());
    assertTrue(session.getClock().getRemainingTimeMs(Side.WHITE) > 60000);
    assertEquals(Side.BLACK, session.getClock().getRunningFor());
  }

  @Test
  void lateTerminalActionsPreserveResultAndMetadata() {
    final var session = new GameSession(new TimeControl(60000, 0));
    session.startGame();
    session.offerDrawWrongTime(Side.WHITE);
    final var result = session.resign(Side.WHITE);
    final long endedAt = session.getEndedAtMs();
    assertTrue(session.acceptDraw(Side.BLACK).isEmpty());
    assertEquals("The game is not in progress.", session.getLastAcceptDrawRejection());
    assertSame(result, session.resign(Side.BLACK));
    assertSame(result, session.getResult());
    assertEquals(endedAt, session.getEndedAtMs());
    assertFalse(session.getDrawOfferManager().isDrawOffered());
  }

  @Test
  void checkmateDuringWrongClockPauseEndsAtomically() {
    final var session = new GameSession(new TimeControl(60000, 0), 2, true,
        Board.fromFenStrict("7k/8/5KQ1/8/8/8/8/8 w - - 0 1"));
    session.startGame();
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G6, Square.G7, Piece.WHITE_QUEEN, 0));
    final var after = BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(Square.G6, Piece.NONE)
        .createChangedPosition(Square.G7, Piece.WHITE_QUEEN).build();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.evaluateForAutoEnd(Side.WHITE, after).orElseThrow().type());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.CHECKMATE, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertNull(session.resumeAfterWrongClockPress());
  }

  // Rook each, so the side that flags loses (the opponent can mate); the halfmove clock makes a 50-move claim valid.
  private static final String FIFTY_MOVE_CLAIMABLE = "4k3/4r3/8/8/8/4K3/4R3/8 w - - 100 80";

  @Test
  void validDrawClaimAfterExpiryStillLosesOnTime() throws Exception {
    final var session = new GameSession(new TimeControl(50, 0), 2, true, Board.fromFenStrict(FIFTY_MOVE_CLAIMABLE));
    session.startGame();
    Thread.sleep(75);
    final var claim = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertFalse(claim.accepted());
    assertEquals(GameResultType.FLAG_FALL, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
  }

  @Test
  void validDrawClaimBeforeExpiryIsStillAccepted() {
    final var session = new GameSession(new TimeControl(60000, 0), 2, true,
        Board.fromFenStrict(FIFTY_MOVE_CLAIMABLE));
    session.startGame();
    assertTrue(session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null).accepted());
    assertEquals(GameResultType.FIFTY_MOVE_CLAIM, session.getResult().type());
  }

  @Test
  void resignationAfterTheOpponentsExpiryKeepsTheWinOnTime() throws Exception {
    final var session = new GameSession(new TimeControl(50, 0));
    session.startGame();
    Thread.sleep(75);
    final var result = session.resign(Side.BLACK);
    assertEquals(GameResultType.FLAG_FALL, result.type());
    assertEquals(Side.BLACK, result.winner());
    assertSame(result, session.getResult());
  }

  @Test
  void penaltyActionsAfterTheOpponentsExpiryCannotReplaceTheFlagFall() throws Exception {
    // Each of these would otherwise run its own escalation ladder against Black, whose opponent has already flagged.
    for (int action = 0; action < 3; action++) {
      final var session = new GameSession(new TimeControl(50, 0));
      session.startGame();
      Thread.sleep(75);
      switch (action) {
        case 0 -> assertFalse(session.pressOpponentClock(Side.BLACK).offense());
        case 1 -> assertFalse(session.offerDrawWrongTime(Side.BLACK).accepted());
        default -> assertFalse(session.claimDraw(Side.BLACK, DrawClaimType.FIFTY_MOVE_ON_BOARD, null).accepted());
      }
      assertEquals(GameResultType.FLAG_FALL, session.getResult().type());
      assertEquals(Side.BLACK, session.getResult().winner());
    }
  }

  @Test
  void clockPressDuringTheAutomaticRestorationPauseReportsItsResumeOnce() {
    final var session = new GameSession(new TimeControl(60000, 0));
    session.startGame();
    final var original = session.getPositionBeforeTurn();
    session.getClock().stopClock();
    session.enterWaitingForRestoration();
    session.completeRestoration();
    assertTrue(session.isRestorationResumePending());
    assertFalse(session.consumeResumedByClockPress());

    // A press without a move: the turn goes on, and the press itself restarted the clock.
    assertNotEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, original).type());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
    assertTrue(session.consumeResumedByClockPress());
    assertFalse(session.consumeResumedByClockPress());
    // The delayed resume has nothing left to do.
    assertFalse(session.resumeAfterRestorationDelay());
  }

  @Test
  void delayedResumeRestartsTheClockWhenNoPressCameFirst() {
    final var session = new GameSession(new TimeControl(60000, 0));
    session.startGame();
    session.getClock().stopClock();
    session.enterWaitingForRestoration();
    session.completeRestoration();
    assertTrue(session.resumeAfterRestorationDelay());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
    assertFalse(session.consumeResumedByClockPress());
    assertFalse(session.resumeAfterRestorationDelay());
  }
}
