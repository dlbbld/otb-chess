// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
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
}
