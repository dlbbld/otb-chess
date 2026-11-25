// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.arbiter.BoardMark;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * The move that replaces an illegal move has to be made with the touched piece. Reported journey (2026-11-25): 1. e4,
 * then Black plays the knight from g8 to g6 - an illegal move - and reverts; the knight from b8 to b6 with the clock
 * was then ruled a second illegal move, which lost the game. The knight on g8 was touched first and can move: the
 * ruling is a touch-move violation, and the arbiter asks for a move of that knight.
 */
class TestTouchMoveAfterRestoration {

  private long time;

  private static GameSession afterE4() {
    final var session = new GameSession(new TimeControl(600000, 0));
    session.startGame();
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4)).type());
    return session;
  }

  private static BitboardPosition moved(GameSession session, Piece piece, Square from, Square to) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, piece).build();
  }

  private ArbiterResponse blackKnight(GameSession session, Square from, Square to) {
    session.recordEvent(Side.BLACK, BoardEvent.dragMove(from, to, Piece.BLACK_KNIGHT, ++time));
    return session.pressClockButton(Side.BLACK, moved(session, Piece.BLACK_KNIGHT, from, to));
  }

  private static void revertAndResume(GameSession session, ArbiterResponse ruling) {
    session.enterWaitingForRestoration(ruling.restorePosition().orElse(session.getPositionBeforeTurn()));
    assertTrue(session.completeRestoration());
    assertTrue(session.resumeAfterRestorationDelay());
  }

  @Test
  void anIllegalMoveOfAnotherPieceAfterTheRevertIsATouchMoveViolationAndNotASecondIllegalMove() {
    final var session = afterE4();

    final ArbiterResponse first = blackKnight(session, Square.G8, Square.G6);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, first.type());
    assertTrue(first.renderedPlayerMessage().contains("This is your 1st illegal move."), first.renderedPlayerMessage());
    revertAndResume(session, first);

    final ArbiterResponse second = blackKnight(session, Square.B8, Square.B6);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, second.type());
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(second.renderedPlayerMessage().contains("You first touched the knight on g8"),
        second.renderedPlayerMessage());
    assertTrue(second.renderedPlayerMessage().contains("move the knight from g8"), second.renderedPlayerMessage());
    assertEquals(List.of(BoardMark.green(Square.G8)), session.getBoardMarks());
    // No penalty time for a touch-move violation: White has what the first illegal move gave.
    final long whiteTime = session.getClock().getRemainingTimeMs(Side.WHITE);
    assertTrue(whiteTime > 600000 && whiteTime <= 600000 + 120000, "White has " + whiteTime + " ms");

    revertAndResume(session, second);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, blackKnight(session, Square.G8, Square.F6).type());
  }

  @Test
  void aSecondIllegalMoveOfTheTouchedPieceItselfLosesTheGame() {
    final var session = afterE4();
    revertAndResume(session, blackKnight(session, Square.G8, Square.G6));

    final ArbiterResponse second = blackKnight(session, Square.G8, Square.G5);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, second.type());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(Side.WHITE, session.getResult().winner());
  }

  @Test
  void aLegalMoveOfAnotherPieceAfterTheRevertIsATouchMoveViolationAsBefore() {
    final var session = afterE4();
    revertAndResume(session, blackKnight(session, Square.G8, Square.G6));

    final ArbiterResponse second = blackKnight(session, Square.B8, Square.C6);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, second.type());
    assertTrue(second.renderedPlayerMessage().contains("You first touched the knight on g8"));
  }
}
