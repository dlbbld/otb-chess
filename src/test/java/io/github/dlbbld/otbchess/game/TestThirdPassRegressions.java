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
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/** Regressions for the gaps left by the second review pass. */
class TestThirdPassRegressions {

  private static GameSession session(boolean autoResume) {
    final var session = new GameSession(new TimeControl(60000, 0), 2, autoResume);
    session.startGame();
    return session;
  }

  /**
   * White's first action of the move is dragging a Black pawn. That intervention is not part of White's action
   * sequence, so no "claim after touching" rule shields the claim machinery during the pause that follows.
   */
  private static void interveneForMovedOpponentPiece(GameSession session) {
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E7, Square.E6, Piece.BLACK_PAWN, 0))
        .isPresent());
    session.enterWaitingForRestoration();
  }

  private static void assertNotConsidered(DrawClaimResult result) {
    assertFalse(result.accepted());
    assertTrue(result.wrongTime(), "the claimer must keep the one claim of this move");
    assertTrue(result.message().contains("paused"), result.message());
    assertFalse(result.convertsToDrawOffer());
    assertTrue(result.moveToPerform().isEmpty());
    assertTrue(result.opponentMessage().isEmpty());
    assertTrue(result.opponentInfo().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = { "restoring", "ready", "delayed" })
  void drawClaimsDuringARestorationPauseAreNotConsidered(String phase) {
    final var session = session(phase.equals("delayed"));
    interveneForMovedOpponentPiece(session);
    if (!phase.equals("restoring")) {
      assertTrue(session.completeRestoration());
    }
    final long blackTime = session.getClock().getRemainingTimeMs(Side.BLACK);
    final long whiteTime = session.getClock().getRemainingTimeMs(Side.WHITE);

    for (int i = 0; i < 3; i++) {
      assertNotConsidered(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4"));
      assertNotConsidered(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null));
      assertNotConsidered(session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null));
      assertNotConsidered(session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null));
      assertNotConsidered(session.retractDrawClaim(Side.WHITE));
    }

    assertEquals(GameState.IN_PROGRESS, session.getState(), "no claim ladder may advance during the pause");
    assertEquals(Side.NONE, session.getClock().getRunningFor(), "a claim must not restart a stopped clock");
    assertEquals(blackTime, session.getClock().getRemainingTimeMs(Side.BLACK), "no penalty time");
    assertEquals(whiteTime, session.getClock().getRemainingTimeMs(Side.WHITE));
    assertFalse(session.getDrawOfferManager().isDrawOffered(), "no claim may turn into an offer during the pause");
    assertNull(session.getMustExecuteMove());

    if (phase.equals("restoring")) {
      assertTrue(session.completeRestoration());
    }
    if (phase.equals("delayed")) {
      assertTrue(session.resumeAfterRestorationDelay());
    } else {
      session.playerReady(Side.WHITE);
      assertTrue(session.playerReady(Side.BLACK));
    }

    // Play is on again: the claim of this move is still available and is examined on its merits.
    final var claim = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");
    assertFalse(claim.accepted());
    assertFalse(claim.wrongTime());
    assertTrue(claim.moveToPerform().isPresent());
    assertEquals(blackTime + 120_000, session.getClock().getRemainingTimeMs(Side.BLACK));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  @Test
  void completingARestorationThatIsNotPendingLeavesARunningGameAlone() {
    for (final boolean autoResume : new boolean[] { true, false }) {
      final var session = session(autoResume);

      assertFalse(session.completeRestoration());

      assertFalse(session.isWaitingForReady());
      assertFalse(session.isRestorationResumePending());
      assertEquals(Side.WHITE, session.getClock().getRunningFor());
    }
  }

  @Test
  void aRepeatedRevertCannotWithdrawAReadyConfirmation() {
    final var session = session(false);
    interveneForMovedOpponentPiece(session);
    assertTrue(session.completeRestoration());
    assertFalse(session.playerReady(Side.BLACK));

    assertFalse(session.completeRestoration(), "the restoration is over; a second Revert is a no-op");

    assertTrue(session.isReady(Side.BLACK));
    assertTrue(session.playerReady(Side.WHITE));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  @Test
  void aRestorationCannotBeCompletedAfterTheGameEnded() {
    final var session = session(true);
    interveneForMovedOpponentPiece(session);
    session.resign(Side.BLACK);

    assertFalse(session.completeRestoration());
    assertFalse(session.isRestorationResumePending());
  }
}
