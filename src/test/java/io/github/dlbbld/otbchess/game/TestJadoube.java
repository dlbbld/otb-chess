// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.GameSession.JadoubeKind;
import io.github.dlbbld.otbchess.game.GameSession.JadoubeOutcome;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * J'adoube (free placement, E-005; FIDE 4.2.1): the button in the own time and in the opponent's time, when a
 * J'adoube that was not used ends, and the ruling for a piece adjusted without it once the move is made.
 */
class TestJadoube {

  private GameSession session;
  private long time;

  @BeforeEach
  void start() {
    session = new GameSession(new TimeControl(600000, 0), 3, true);
    session.startGame();
  }

  private BitboardPosition afterE4() {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
  }

  private void playE4WithoutPressing() {
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, time++));
    assertTrue(session.isMoveMade());
  }

  private void restartClock() {
    assertTrue(session.isUnclearPlacementPause(), "the board is locked for the call");
    assertTrue(session.resumeAfterPressWithoutMove(session.getPressWithoutMovePauseId()));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  // ===== The button =====

  @Test
  void inTheOwnTimeJadoubeIsSaidOnceAndUsedUpByOnePiece() {
    final JadoubeOutcome said = session.pressJadoube(Side.WHITE);
    assertEquals(JadoubeKind.ARMED, said.kind());
    assertEquals("White: J'adoube.", said.message());
    assertTrue(session.isJadoubeArmed());
    assertEquals(JadoubeKind.IGNORED, session.pressJadoube(Side.WHITE).kind(), "it is said already");

    assertFalse(session.useJadoube(Side.BLACK), "only the player to move adjusts");
    assertTrue(session.useJadoube(Side.WHITE));
    assertFalse(session.isJadoubeArmed(), "one J'adoube, one piece");
    assertFalse(session.useJadoube(Side.WHITE));

    assertEquals(JadoubeKind.ARMED, session.pressJadoube(Side.WHITE).kind(), "every adjustment needs a new one");
  }

  @Test
  void jadoubeCanBeSaidAfterAPieceWasTouchedOrMoved() {
    session.recordEvent(Side.WHITE, BoardEvent.click(Square.E2, Piece.WHITE_PAWN, time++));
    assertEquals(JadoubeKind.ARMED, session.pressJadoube(Side.WHITE).kind());
    assertTrue(session.useJadoube(Side.WHITE));
    playE4WithoutPressing();
    assertEquals(JadoubeKind.ARMED, session.pressJadoube(Side.WHITE).kind());
  }

  @Test
  void anUnusedJadoubeEndsWithTheClockPress() {
    session.pressJadoube(Side.WHITE);
    playE4WithoutPressing();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4()).type());
    assertFalse(session.isJadoubeArmed());
    assertFalse(session.useJadoube(Side.BLACK), "it does not pass to the opponent");
  }

  @Test
  void anUnusedJadoubeEndsWithARulingThatPausesTheGame() {
    session.pressJadoube(Side.WHITE);
    // A clock press without a move: the arbiter stops the clock.
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE,
        session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn()).type());
    assertFalse(session.isJadoubeArmed());

    assertTrue(session.resumeAfterPressWithoutMove(session.getPressWithoutMovePauseId()));
    session.pressJadoube(Side.WHITE);
    // The opponent presses White's clock: the arbiter stops it for the admonishment.
    assertTrue(session.pressOpponentClock(Side.BLACK).offense());
    assertFalse(session.isJadoubeArmed());
  }

  @Test
  void theButtonDoesNothingWhileTheGameIsPaused() {
    session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn()); // press without a move: paused
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertEquals(JadoubeKind.IGNORED, session.pressJadoube(Side.WHITE).kind());
    assertEquals(JadoubeKind.IGNORED, session.pressJadoube(Side.BLACK).kind(), "and costs the opponent nothing");
    assertEquals(0, session.getJadoubeWrongTimeCount(Side.BLACK));
  }

  @Test
  void inTheOpponentsTimeNothingIsArmedAndTheThirdPressLosesTheGame() {
    final JadoubeOutcome first = session.pressJadoube(Side.BLACK);
    assertEquals(JadoubeKind.WRONG_TIME, first.kind());
    assertEquals("You can only adjust pieces in your own time.", first.message());
    assertEquals("Your opponent said J'adoube in your time.", first.opponentInfo());
    assertFalse(session.isJadoubeArmed());
    assertEquals(Side.WHITE, session.getClock().getRunningFor(), "no pause");

    final JadoubeOutcome second = session.pressJadoube(Side.BLACK);
    assertEquals("You can only adjust pieces in your own time. Warning: the next time you lose the game.",
        second.message());
    assertEquals("Your opponent said J'adoube in your time and has been warned: the next time loses them the"
        + " game.", second.opponentInfo());
    assertEquals(GameState.IN_PROGRESS, session.getState());

    final JadoubeOutcome third = session.pressJadoube(Side.BLACK);
    assertEquals(JadoubeKind.WRONG_TIME_GAME_LOST, third.kind());
    assertNull(third.message());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.JADOUBE_WRONG_TIME_GAME_LOST, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
    assertEquals(Side.BLACK, session.getTerminationActor());
    assertEquals(JadoubeKind.IGNORED, session.pressJadoube(Side.WHITE).kind(), "the game is over");
  }

  @Test
  void theLadderForTheOpponentsTimeIsPerPlayer() {
    session.pressJadoube(Side.BLACK);
    session.pressJadoube(Side.BLACK);
    playE4WithoutPressing();
    session.pressClockButton(Side.WHITE, afterE4());
    assertEquals(JadoubeKind.WRONG_TIME, session.pressJadoube(Side.WHITE).kind());
    assertEquals(1, session.getJadoubeWrongTimeCount(Side.WHITE));
    assertEquals(2, session.getJadoubeWrongTimeCount(Side.BLACK));
    assertEquals(JadoubeKind.ARMED, session.pressJadoube(Side.BLACK).kind(), "in the own time it is said");
  }

  // ===== Adjusting without J'adoube =====

  @Test
  void beforeTheMoveIsMadeAShiftedPieceIsNotRuledOn() {
    assertFalse(session.isMoveMade());
    assertTrue(session.ruleOnAdjustingWithoutJadoube(Side.WHITE).isEmpty(), "it is a touch, as in every game");
    assertEquals(0, session.getAdjustedWithoutJadoubeCount(Side.WHITE));
  }

  @Test
  void afterTheMoveIsMadeAdjustingWithoutJadoubeIsCalledAtOnceAndTheThirdTimeLosesTheGame() {
    playE4WithoutPressing();
    final ArbiterResponse first = session.ruleOnAdjustingWithoutJadoube(Side.WHITE).orElseThrow();
    assertEquals(ArbiterResponseType.UNCLEAR_PLACEMENT, first.type());
    assertEquals("You cannot adjust pieces without saying J'adoube.", first.renderedPlayerMessage());
    assertEquals(Optional.of("Your opponent adjusted a piece without saying J'adoube."),
        first.renderedOpponentMessage());
    assertEquals(Side.NONE, session.getClock().getRunningFor(), "the game is paused for the call");
    assertTrue(session.ruleOnAdjustingWithoutJadoube(Side.WHITE).isEmpty(), "nothing is ruled on in the pause");
    restartClock();
    assertTrue(session.isMoveMade(), "the move stands");

    final ArbiterResponse second = session.ruleOnAdjustingWithoutJadoube(Side.WHITE).orElseThrow();
    assertEquals("You cannot adjust pieces without saying J'adoube. Warning: the next time you lose the game.",
        second.renderedPlayerMessage());
    assertEquals(Optional.of("Your opponent adjusted a piece without saying J'adoube and has been warned: the next"
        + " time loses them the game."), second.renderedOpponentMessage());
    restartClock();

    final ArbiterResponse third = session.ruleOnAdjustingWithoutJadoube(Side.WHITE).orElseThrow();
    assertEquals(ArbiterResponseType.UNCLEAR_PLACEMENT_GAME_LOST, third.type());
    assertEquals(GameResultType.ADJUSTED_WITHOUT_JADOUBE_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
    assertEquals(Side.WHITE, session.getTerminationActor());
    assertEquals(0, session.getUnclearPlacementCount(Side.WHITE), "a ladder of its own");
  }

  @Test
  void afterTheCallTheMoveIsAcceptedWithTheClockPress() {
    playE4WithoutPressing();
    session.ruleOnAdjustingWithoutJadoube(Side.WHITE);
    restartClock();
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4()).type());
    assertEquals(1, session.getAdjustedWithoutJadoubeCount(Side.WHITE), "the count stays for the whole game");
  }

  @Test
  void onlyThePlayerToMoveIsRuledOn() {
    playE4WithoutPressing();
    assertTrue(session.ruleOnAdjustingWithoutJadoube(Side.BLACK).isEmpty());
  }
}
