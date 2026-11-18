// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/** Regressions for the findings of the final review pass before the release. */
class TestFinalPassRegressions {

  // ----- finding 1: an offer is answered by the other player, never by "no side" -----

  @Test
  void noSideCanNeitherAcceptNorRejectAnOffer() {
    final var offers = new DrawOfferManager();
    offers.offerDrawCorrectTime(Side.BLACK);

    assertFalse(offers.canAccept(Side.NONE));
    assertEquals("Only the player the offer was made to can answer it.", offers.acceptDraw(Side.NONE).orElseThrow());
    assertEquals("Only the player the offer was made to can answer it.", offers.rejectDraw(Side.NONE).orElseThrow());
    assertTrue(offers.isDrawOffered(), "the offer must still be pending");
    assertEquals(Side.BLACK, offers.getOfferingSide());

    assertTrue(offers.canAccept(Side.WHITE));
    assertTrue(offers.acceptDraw(Side.WHITE).isEmpty());
  }

  private static GameSession startedSession() {
    final var session = new GameSession(new TimeControl(600000, 0), 2, true);
    session.startGame();
    return session;
  }

  // ----- finding 3: the board events of one turn are limited -----

  @Test
  void eventsBeyondTheLimitOfATurnAreNotRecorded() {
    final var session = startedSession();
    session.recordEvent(Side.WHITE, BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    for (int i = 1; i < GameSession.MAX_BOARD_EVENTS_PER_TURN; i++) {
      assertFalse(session.isTurnEventLimitReached());
      session.recordEvent(Side.WHITE, i % 2 == 1 ? BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, i)
          : BoardEvent.dragMove(Square.E4, Square.E2, Piece.WHITE_PAWN, i));
    }
    assertTrue(session.isTurnEventLimitReached());

    // A release that would complete a legal move, and a moved opponent piece: neither is looked at any more.
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 0))
        .isEmpty());
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.A7, Square.A6, Piece.BLACK_PAWN, 0))
        .isEmpty());
    assertFalse(session.hasReleasedPieceCommitment());
    assertFalse(session.isWaitingForRestoration());
  }

  // ----- finding 5: a resignation needs a running game, and a game starts once -----

  @Test
  void aSessionThatHasNotStartedCannotBeResigned() {
    final var session = new GameSession(new TimeControl(600000, 0), 2, true);

    assertNull(session.resign(Side.WHITE));
    assertEquals(GameState.WAITING_FOR_PLAYERS, session.getState());
    assertNull(session.getResult());

    session.startGame();
    assertEquals(GameResultType.RESIGNATION, session.resign(Side.WHITE).type());
  }

  @Test
  void aGameIsStartedOnceOnly() {
    final var session = startedSession();
    session.resign(Side.WHITE);

    assertThrows(IllegalStateException.class, session::startGame);
    assertEquals(GameState.ENDED, session.getState());
  }

  // ----- finding 4: an offer that answers an offer agrees the draw in a pause as well -----

  @Test
  void aCounterOfferInThePauseAfterAWrongClockPressAgreesTheDraw() {
    final var session = startedSession();
    playOneMoveEach(session);
    assertTrue(session.offerDrawWrongTime(Side.BLACK).accepted());
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());

    session.offerDrawWrongTime(Side.WHITE);

    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.DRAW_AGREEMENT, session.getResult().type());
    assertEquals(Side.WHITE, session.getTerminationActor());
  }

  /** e2-e4 and e7-e5 with the clock presses: after that both players have made a move. */
  static void playOneMoveEach(GameSession session) {
    move(session, Side.WHITE, Square.E2, Square.E4, Piece.WHITE_PAWN);
    move(session, Side.BLACK, Square.E7, Square.E5, Piece.BLACK_PAWN);
  }

  private static void move(GameSession session, Side side, Square from, Square to, Piece piece) {
    session.recordEvent(side, BoardEvent.dragMove(from, to, piece, 0));
    final var after = BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, piece).build();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(side, after).type());
  }

  // ----- finding 7: a draw is agreed only after both players have made a move (FIDE 5.2.3) -----

  private static final String TOO_EARLY = "A draw can be agreed only after both players have made at least one move"
      + " (FIDE 5.2.3).";

  private static BitboardPosition after(GameSession session, Square from, Square to, Piece piece) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, piece).build();
  }

  @Test
  void anOfferOnTheFirstMoveCanNeitherBeAcceptedNorAnsweredWithAnOffer() {
    final var session = startedSession();
    assertTrue(session.offerDrawWrongTime(Side.WHITE).accepted(), "the offer itself is made");

    assertTrue(session.acceptDraw(Side.BLACK).isEmpty());
    assertEquals(TOO_EARLY, session.getLastAcceptDrawRejection());
    final var counter = session.offerDrawWrongTime(Side.BLACK);
    assertFalse(counter.accepted());
    assertEquals(TOO_EARLY, counter.arbiterMessage());

    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(session.getDrawOfferManager().isDrawOffered(), "the offer stays on the table");
    assertEquals(Side.WHITE, session.getDrawOfferManager().getOfferingSide());
  }

  @Test
  void whitesFirstMoveWithAnOfferIsNotEnoughButBlacksFirstMoveWithAnOfferIs() {
    final var session = startedSession();
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final var afterE4 = after(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.offerDrawCorrectTime(Side.WHITE, afterE4).type());
    assertTrue(session.acceptDraw(Side.BLACK).isEmpty(), "only White has made a move");
    assertEquals(TOO_EARLY, session.getLastAcceptDrawRejection());
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4).type());

    // Black makes the first move and offers with it: now both have made a move, although Black's
    // clock press is still outstanding.
    session.recordEvent(Side.BLACK, BoardEvent.dragMove(Square.E7, Square.E5, Piece.BLACK_PAWN, 0));
    // The server's bookkeeping for "the recipient touched a piece": White's offer can no longer be accepted.
    session.getDrawOfferManager().recordOpponentTouchedPiece();
    final var afterE5 = after(session, Square.E7, Square.E5, Piece.BLACK_PAWN);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.offerDrawCorrectTime(Side.BLACK, afterE5).type());
    assertTrue(session.acceptDraw(Side.WHITE).isPresent());

    assertEquals(GameResultType.DRAW_AGREEMENT, session.getResult().type());
    assertEquals(List.of("e4", "e5"), session.getBoard().getPerformedMovesAsSan());
    assertEquals("1. e4 e5 1/2-1/2", session.exportPgn().strip());
  }

  // ----- finding 8: the move made with a draw offer belongs to the game record -----

  @Test
  void theMoveMadeWithAnAcceptedOfferIsInTheRecord() {
    final var session = startedSession();
    DrawTestSupport.bothPlayersMove(session);
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        session.offerDrawCorrectTime(Side.WHITE, after(session, Square.E2, Square.E4, Piece.WHITE_PAWN)).type());

    assertTrue(session.acceptDraw(Side.BLACK).isPresent());

    assertEquals(List.of("a3", "a6", "e4"), session.getBoard().getPerformedMovesAsSan());
    assertEquals("1. a3 a6 2. e4 1/2-1/2", session.exportPgn().strip());
  }

  @Test
  void theMoveMadeWithAnOfferThatIsAnsweredByAnOfferIsInTheRecord() {
    final var session = startedSession();
    DrawTestSupport.bothPlayersMove(session);
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    session.offerDrawCorrectTime(Side.WHITE, after(session, Square.E2, Square.E4, Piece.WHITE_PAWN));

    session.offerDrawWrongTime(Side.BLACK);

    assertEquals(GameState.ENDED, session.getState());
    assertEquals(List.of("a3", "a6", "e4"), session.getBoard().getPerformedMovesAsSan());
  }

  @Test
  void aMoveCompletedWithTheClockIsNotPerformedASecondTime() {
    final var session = startedSession();
    DrawTestSupport.bothPlayersMove(session);
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final var afterE4 = after(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    session.offerDrawCorrectTime(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4).type());

    assertTrue(session.acceptDraw(Side.BLACK).isPresent());

    assertEquals(List.of("a3", "a6", "e4"), session.getBoard().getPerformedMovesAsSan());
  }

  @Test
  void anOfferWithoutAMoveAddsNothingToTheRecord() {
    final var session = startedSession();
    DrawTestSupport.bothPlayersMove(session);
    session.offerDrawWrongTime(Side.BLACK);

    assertTrue(session.acceptDraw(Side.WHITE).isPresent());

    assertEquals(List.of("a3", "a6"), session.getBoard().getPerformedMovesAsSan());
    assertEquals("1. a3 a6 1/2-1/2", session.exportPgn().strip());
  }

  @Test
  void theOwedMoveOfARejectedClaimIsInTheRecordButAnotherMoveIsNot() {
    for (final boolean playsTheOwedMove : new boolean[] { true, false }) {
      final var session = startedSession();
      DrawTestSupport.bothPlayersMove(session);
      // The rejected claim binds White to e4 and counts as a draw offer to Black (FIDE 9.5).
      assertFalse(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4").accepted());
      if (playsTheOwedMove) {
        session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
      } else {
        session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.D2, Square.D4, Piece.WHITE_PAWN, 0));
      }

      assertTrue(session.acceptDraw(Side.BLACK).isPresent());

      assertEquals(playsTheOwedMove ? List.of("a3", "a6", "e4") : List.of("a3", "a6"),
          session.getBoard().getPerformedMovesAsSan());
    }
  }

  @Test
  void thePgnCarriesTheResultOnceTheGameIsDecided() {
    final var session = startedSession();
    DrawTestSupport.bothPlayersMove(session);
    assertEquals("1. a3 a6 *", session.exportPgn().strip());

    session.resign(Side.WHITE);

    assertEquals("1. a3 a6 0-1", session.exportPgn().strip());
  }

  // ----- note 2: what the players read when the clock restarts after a wrong clock press -----

  @Test
  void aWrongClockPressCarriesWhatIsSaidWhenTheClockRestarts() {
    final var session = startedSession();

    final var first = session.pressOpponentClock(Side.BLACK);
    assertFalse(first.warned());
    assertEquals("Clock restarted. Game continues.", first.restartMessage());
    assertEquals("Your opponent pressed your clock. Your clock has restarted.", first.opponentRestartInfo());
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress(first.pauseId()));

    final var second = session.pressOpponentClock(Side.BLACK);
    assertTrue(second.warned());
    assertEquals("Clock restarted. Game continues. Warning: the next press of your opponent's clock loses the game.",
        second.restartMessage());
    assertEquals("Your opponent pressed your clock and has been warned: the next press loses them the game."
        + " Your clock has restarted.", second.opponentRestartInfo());
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress(second.pauseId()));

    final var third = session.pressOpponentClock(Side.BLACK);
    assertTrue(third.gameLost());
    assertNull(third.restartMessage());
    assertNull(third.opponentRestartInfo());

    final var noOffense = startedSession().pressOpponentClock(Side.WHITE); // Black's lever is down
    assertFalse(noOffense.offense());
    assertNull(noOffense.restartMessage());
  }

  @Test
  void onlyAPlayerAnsweredWaitDuringThePauseIsRememberedAsToldToWait() {
    final var session = startedSession();
    assertFalse(session.consumeToldToWaitForClock(Side.WHITE), "no pause, nobody was told");

    session.pressOpponentClock(Side.BLACK);
    assertFalse(session.consumeToldToWaitForClock(Side.WHITE), "White did nothing during the pause");

    assertEquals("The game is paused. Please wait until the clock restarts.",
        session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn()).renderedPlayerMessage());
    assertTrue(session.consumeToldToWaitForClock(Side.WHITE));
    assertFalse(session.consumeToldToWaitForClock(Side.WHITE), "asked once");
    assertFalse(session.consumeToldToWaitForClock(Side.BLACK));

    // An offer and a claim are answered the same way, for the player on move and for the offender.
    session.offerDrawWrongTime(Side.WHITE);
    session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(session.consumeToldToWaitForClock(Side.WHITE));
    assertTrue(session.consumeToldToWaitForClock(Side.BLACK));
  }

  @Test
  void aNewPauseForgetsWhoWasToldToWaitInTheOldOne() {
    final var session = startedSession();
    final var first = session.pressOpponentClock(Side.BLACK);
    session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn());
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress(first.pauseId()));

    session.pressOpponentClock(Side.BLACK);

    assertFalse(session.consumeToldToWaitForClock(Side.WHITE));
  }

  @Test
  void aRestorationPauseIsNotRememberedAsAWaitForTheWrongClockPress() {
    final var session = startedSession();
    session.pressOpponentClock(Side.BLACK);
    // White moves a Black piece: the restoration takes over, and its end is announced by the restoration itself.
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.A7, Square.A6, Piece.BLACK_PAWN, 0))
        .isPresent());
    session.enterWaitingForRestoration(); // what the server does with that ruling

    session.offerDrawWrongTime(Side.WHITE);

    assertFalse(session.consumeToldToWaitForClock(Side.WHITE));
  }
}
