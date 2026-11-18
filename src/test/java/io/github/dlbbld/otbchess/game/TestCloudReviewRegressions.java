// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/** Regressions for the findings of the cloud review of the review branch. */
class TestCloudReviewRegressions {

  /** Long enough for the test to act, short enough to run out during a short sleep. */
  private static final long SHORT_TIME_MS = 50;

  private static GameSession session(long timeMs) {
    final var session = new GameSession(new TimeControl(timeMs, 0), 2, true);
    session.startGame();
    return session;
  }

  private static void letTheClockRunOut() throws InterruptedException {
    Thread.sleep(SHORT_TIME_MS + 25);
  }

  private static BitboardPosition afterE4(GameSession session) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
  }

  /** A claim with a legal move that is rejected: the claimer then owes that move. */
  private static void oweE4AfterARejectedClaim(GameSession session) {
    final DrawClaimResult claim = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");
    assertFalse(claim.accepted());
    assertNotNull(session.getMustExecuteMove(), "the rejected claim binds the claimer to the move");
  }

  // ----- a move owed after a rejected claim does not outlive the game -----

  @Test
  void aClockPressAfterTheFlagFellEndsTheGameAndLeavesNoMoveOwed() throws Exception {
    final var session = session(SHORT_TIME_MS);
    oweE4AfterARejectedClaim(session);
    letTheClockRunOut();

    // The press arrives before the periodic check has seen the expired clock.
    final ArbiterResponse response = session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn());

    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertEquals("Your time has expired.", response.renderedPlayerMessage());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.FLAG_FALL, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
    assertNull(session.getMustExecuteMove(), "a finished game owes no move");

    // What the server did next on this answer: ask for a restore towards the owed move.
    session.enterWaitingForRestoration();
    assertFalse(session.isWaitingForRestoration(), "a finished game must not be paused again");
    assertFalse(session.completeRestoration());
  }

  @Test
  void everyEndingClearsTheOwedMoveAndRefusesARestoration() {
    final var session = session(60000);
    oweE4AfterARejectedClaim(session);

    session.resign(Side.WHITE);

    assertEquals(GameState.ENDED, session.getState());
    assertNull(session.getMustExecuteMove());
    session.enterWaitingForRestoration(afterE4(session));
    assertFalse(session.isWaitingForRestoration());
    assertFalse(session.isWaitingForReady());
    assertFalse(session.isRestorationResumePending());
  }

  @Test
  void theOwedMoveIsStillOwedAndRestorableWhileTheGameRuns() {
    final var session = session(60000);
    oweE4AfterARejectedClaim(session);

    // Another move than the owed one: the arbiter asks to revert, and the move stays owed.
    final var afterD4 = BitboardPositions.from(session.getPositionBeforeTurn())
        .createChangedPosition(Square.D2, Piece.NONE).createChangedPosition(Square.D4, Piece.WHITE_PAWN).build();
    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterD4);
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertTrue(response.renderedPlayerMessage().contains("was not executed"), response.renderedPlayerMessage());
    assertNotNull(session.getMustExecuteMove());
    session.enterWaitingForRestoration();
    assertTrue(session.isWaitingForRestoration());
  }

  // ----- abandonment does not outrank a fallen flag -----

  @ParameterizedTest
  @EnumSource(value = Side.class, names = { "WHITE", "BLACK" })
  void abandonmentAfterAnUnnoticedFlagFallIsDecidedOnTime(Side leaver) throws Exception {
    // White is to move and White's clock runs out. Whoever is then adjudicated as having left -
    // Black on White's "Claim victory", or White by the abandonment timer - White lost on time first.
    final var session = session(SHORT_TIME_MS);
    letTheClockRunOut();

    assertNull(session.abandon(leaver), "nothing is left to adjudicate as an abandonment");

    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.FLAG_FALL, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
    assertEquals("White loses on time. Black wins the game.", session.getResult().description());
  }

  @Test
  void abandonmentWithTimeLeftIsStillAnAbandonment() {
    final var session = session(60000);

    final var result = session.abandon(Side.BLACK);

    assertEquals(GameResultType.ABANDONMENT, result.type());
    assertEquals(Side.WHITE, result.winner());
    assertEquals(Side.BLACK, session.getTerminationActor());
  }

  // ----- nothing is adjudicated during the pause after a wrong clock press -----

  private static void assertNotConsidered(DrawClaimResult result) {
    assertFalse(result.accepted());
    assertTrue(result.wrongTime(), "the claimer must keep the one claim of this move");
    assertEquals("The game is paused. Please wait until the clock restarts.", result.message());
    assertFalse(result.convertsToDrawOffer());
    assertTrue(result.moveToPerform().isEmpty());
    assertTrue(result.opponentMessage().isEmpty());
    assertTrue(result.opponentInfo().isEmpty());
  }

  @Test
  void drawClaimsDuringTheWrongClockPressPauseAreNotConsidered() {
    final var session = session(60000);
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    final long whiteTime = session.getClock().getRemainingTimeMs(Side.WHITE);
    final long blackTime = session.getClock().getRemainingTimeMs(Side.BLACK);

    for (int i = 0; i < 3; i++) {
      assertNotConsidered(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4"));
      assertNotConsidered(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null));
      assertNotConsidered(session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null));
      assertNotConsidered(session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null));
      assertNotConsidered(session.retractDrawClaim(Side.WHITE));
    }

    assertEquals(GameState.IN_PROGRESS, session.getState(), "no claim ladder may advance during the pause");
    assertEquals(Side.NONE, session.getClock().getRunningFor(), "a claim must not restart the stopped clock");
    assertEquals(blackTime, session.getClock().getRemainingTimeMs(Side.BLACK), "no penalty time");
    assertEquals(whiteTime, session.getClock().getRemainingTimeMs(Side.WHITE));
    assertFalse(session.getDrawOfferManager().isDrawOffered(), "no claim may turn into an offer during the pause");
    assertNull(session.getMustExecuteMove());

    // The arbiter restarts the clock; the claim of this move was not used up by the pause.
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress());
    final DrawClaimResult claim = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(claim.wrongTime());
    assertTrue(claim.convertsToDrawOffer(), "after the pause the claim is examined again");
    assertTrue(session.getClock().getRemainingTimeMs(Side.BLACK) > blackTime, "and an incorrect one costs time");
  }

  @Test
  void drawOffersDuringTheWrongClockPressPauseAreNotConsidered() {
    final var session = session(60000);
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());
    final String paused = "The game is paused. Please wait until the clock restarts.";

    for (final Side side : new Side[] { Side.BLACK, Side.WHITE, Side.BLACK }) {
      final var offer = session.offerDrawWrongTime(side);
      assertFalse(offer.accepted());
      assertFalse(offer.gameLost());
      assertEquals(paused, offer.arbiterMessage());
      assertNull(offer.opponentInfo());
    }
    // The player on move makes a move during the pause and offers with it.
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0))
        .isEmpty());
    final ArbiterResponse withMove = session.offerDrawCorrectTime(Side.WHITE, afterE4(session));
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, withMove.type());
    assertEquals(paused, withMove.renderedPlayerMessage());

    assertFalse(session.getDrawOfferManager().isDrawOffered());
    assertEquals(GameState.IN_PROGRESS, session.getState(), "no offer ladder may advance during the pause");
    assertEquals(Side.NONE, session.getClock().getRunningFor());

    // After the pause offers are taken again, and the refused ones were not counted as repeats.
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress());
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.offerDrawCorrectTime(Side.WHITE, afterE4(session)).type());
    assertTrue(session.getDrawOfferManager().isDrawOffered());
    assertEquals(Side.WHITE, session.getDrawOfferManager().getOfferingSide());
  }

  @Test
  void aStaleWrongClockMarkerDoesNotPauseARunningGame() {
    // The admonishment pause is over as soon as a clock runs again, whatever restarted it.
    final var session = session(60000);
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());
    session.getClock().startClock(Side.WHITE);

    assertTrue(session.offerDrawWrongTime(Side.BLACK).accepted());
  }

  // ----- a long turn without a committing release is ruled on promptly -----

  @Test
  void aLongUncommittedTurnIsRecordedPromptlyAndStillRuledOnCorrectly() {
    // Touching the knight binds White to a knight move, so no pawn release ever commits: the
    // arbiter looks at every release of the turn again on each new event. One replay per ruling
    // instead of one per release: 800 events took over a minute before. The cost of an event still
    // grows with the square of the turn's length, which is why a turn records a limited number of
    // events (GameSession.MAX_BOARD_EVENTS_PER_TURN); this turn stays below it.
    final var session = session(600000);
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0)).isEmpty());

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
      for (int i = 0; i < 400; i++) {
        final BoardEvent event = i % 2 == 0 ? BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, i + 1)
            : BoardEvent.dragMove(Square.E4, Square.E2, Piece.WHITE_PAWN, i + 1);
        assertTrue(session.recordEvent(Side.WHITE, event).isEmpty());
      }
    });

    assertFalse(session.hasReleasedPieceCommitment(), "a pawn release cannot commit after the knight was touched");
    // The pawn is back on e2; the touched knight is moved and the move is accepted.
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 900))
        .isEmpty());
    final var afterNf3 = BitboardPositions.from(session.getPositionBeforeTurn())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterNf3).type());
    assertEquals("Nf3", session.getBoard().getPerformedMovesAsSan().get(0));
  }

  @Test
  void aPawnMoveMadeAfterTheLongFumbleIsStillATouchMoveViolation() {
    final var session = session(600000);
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0)).isEmpty());
    for (int i = 0; i < 41; i++) {
      final BoardEvent event = i % 2 == 0 ? BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, i + 1)
          : BoardEvent.dragMove(Square.E4, Square.E2, Piece.WHITE_PAWN, i + 1);
      assertTrue(session.recordEvent(Side.WHITE, event).isEmpty());
    }

    // The pawn stands on e4 after the odd number of drags.
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION,
        session.pressClockButton(Side.WHITE, afterE4(session)).type());
  }
}
