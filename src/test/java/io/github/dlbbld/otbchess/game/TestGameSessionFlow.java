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
 * Integration tests for the full game flow including ready-to-continue and position restoration.
 */
class TestGameSessionFlow {

  private static final TimeControl TEST_TIME = new TimeControl(5 * 60 * 1000L, 0);

  @Test
  void testReadyToContinueRequiresBothPlayers() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White makes an illegal move
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition illegalPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();
    session.pressClockButton(Side.WHITE, illegalPos);

    // Enter waiting for ready
    session.enterWaitingForReady();
    assertTrue(session.isWaitingForReady());

    // Only White clicks ready — not enough
    assertFalse(session.playerReady(Side.WHITE));
    assertTrue(session.isWaitingForReady());

    // Black clicks ready — now both ready
    assertTrue(session.playerReady(Side.BLACK));
    assertFalse(session.isWaitingForReady());
  }

  @Test
  void testRestorePositionReturnsOriginal() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final BitboardPosition originalPosition = session.getBoard().getBitboardPosition();

    // White makes a move (valid)
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final BitboardPosition afterE4 = BitboardPositions.from(originalPosition)
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    session.pressClockButton(Side.WHITE, afterE4);

    // Now it's Black's turn — the restore position should be the position after e4
    assertEquals(afterE4, session.getRestorePosition());
  }

  @Test
  void testIllegalMoveThenValidMoveSucceeds() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White makes an illegal move (knight to g3 — impossible)
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition illegalPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();
    final ArbiterResponse illegalResponse = session.pressClockButton(Side.WHITE, illegalPos);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, illegalResponse.type());

    // Ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White now makes a valid move with the SAME piece (knight) — touch-move still applies
    // from the earlier touch, so he must move the knight
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition validPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();
    final ArbiterResponse validResponse = session.pressClockButton(Side.WHITE, validPos);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, validResponse.type());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void testIllegalMoveThenDifferentPieceIsTouchMoveViolation() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White touches knight (illegal move to g3)
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition illegalPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();
    session.pressClockButton(Side.WHITE, illegalPos);

    // Ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White tries to play pawn instead — touch-move violation (knight was touched)
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final BitboardPosition pawnPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    final ArbiterResponse response = session.pressClockButton(Side.WHITE, pawnPos);
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
  }

  @Test
  void testFullGameFlowWhiteBlackAlternating() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // 1. e4
    makeSimpleMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    assertEquals(Side.BLACK, session.getHavingMove());

    // 1... e5
    makeSimpleMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN);
    assertEquals(Side.WHITE, session.getHavingMove());

    // 2. Nf3
    makeSimpleMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT);
    assertEquals(Side.BLACK, session.getHavingMove());

    // 2... Nc6
    makeSimpleMove(session, Square.B8, Square.C6, Piece.BLACK_KNIGHT);
    assertEquals(Side.WHITE, session.getHavingMove());

    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  @Test
  void testTouchMoveViolationThenCorrectMove() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White touches knight, but plays pawn — touch-move violation
    session.recordEvent(Side.WHITE, BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    final ArbiterResponse violation = session.pressClockButton(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, violation.type());

    // Ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White now moves the knight correctly
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition afterNf3 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();
    final ArbiterResponse valid = session.pressClockButton(Side.WHITE, afterNf3);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, valid.type());
  }

  @Test
  void testTouchMovePawnObligationPersistsAfterRestoration() {
    final GameSession session = new GameSession(TEST_TIME, 2, false);
    session.startGame();

    // White touches pawn b2, but plays e4.
    session.recordEvent(Side.WHITE, BoardEvent.click(Square.B2, Piece.WHITE_PAWN, 0));
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse violation = session.pressClockButton(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, violation.type());
    assertTrue(violation.message().contains("pawn on b2"));

    // The restore flow should not clear the touch-move obligation.
    session.enterWaitingForRestoration();
    assertTrue(session.isWaitingForRestoration());
    assertTrue(session.isRestoredPosition(session.getRestorePosition()));
    session.completeRestoration();
    assertFalse(session.isWaitingForRestoration());
    assertTrue(session.isWaitingForReady());
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White now performs a legal move with the touched b2 pawn.
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.B2, Square.B4, Piece.WHITE_PAWN, 2));
    final BitboardPosition afterB4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.B2, Piece.NONE).createChangedPosition(Square.B4, Piece.WHITE_PAWN).build();

    final ArbiterResponse valid = session.pressClockButton(Side.WHITE, afterB4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, valid.type());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  /**
   * End-to-end released-piece flow: legal release commits, second drop violates, restoration target is the release
   * position, and after the player puts the piece back on the release square and both Ready, the committed move is
   * accepted.
   */
  @Test
  void testReleasedPieceViolationRestoresToReleasePosition() {
    final GameSession session = new GameSession(TEST_TIME, 2, false);
    session.startGame();

    // White releases the e2 pawn on e3 (legal commit), then drags it on to e4, then presses clock.
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E3, Piece.WHITE_PAWN, 0));
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E3, Square.E4, Piece.WHITE_PAWN, 1));
    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse violation = session.pressClockButton(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, violation.type());
    assertTrue(violation.message().contains("pawn on e3"));

    // Restoration target is the release position (e2 empty, e3 occupied), NOT positionBeforeTurn.
    final BitboardPosition releasePosition = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E3, Piece.WHITE_PAWN).build();
    assertTrue(violation.restorePosition().isPresent());
    assertEquals(releasePosition, violation.restorePosition().get());

    // Server would call this; simulate the same path here.
    session.enterWaitingForRestoration(releasePosition);
    assertTrue(session.isWaitingForRestoration());
    // The session reports "restored" once the physical board matches the release position.
    assertTrue(session.isRestoredPosition(releasePosition));

    // Complete the restoration; with autoResume=false the session enters waitingForReady.
    session.completeRestoration();
    assertFalse(session.isWaitingForRestoration());
    assertTrue(session.isWaitingForReady());

    // Both players Ready.
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);
    assertFalse(session.isWaitingForReady());

    // White presses the clock with the committed release position — the e2-e3 move is accepted.
    final ArbiterResponse accepted = session.pressClockButton(Side.WHITE, releasePosition);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, accepted.type());
    assertEquals(Side.BLACK, session.getHavingMove());
    // (The illegal-move counter is asserted at the engine level in TestArbiterEngine —
    // released-piece violation does not contribute to it.)
  }

  private void makeSimpleMove(GameSession session, Square from, Square to, Piece piece) {
    final Side side = session.getHavingMove();
    session.recordEvent(side, BoardEvent.dragMove(from, to, piece, System.currentTimeMillis()));
    final BitboardPosition afterPosition = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(from, Piece.NONE).createChangedPosition(to, piece).build();
    final ArbiterResponse response = session.pressClockButton(side, afterPosition);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }
}
