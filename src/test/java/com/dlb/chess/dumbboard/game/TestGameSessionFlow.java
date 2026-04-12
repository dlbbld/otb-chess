package com.dlb.chess.dumbboard.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponseType;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.game.model.GameState;
import com.dlb.chess.dumbboard.game.model.TimeControl;

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
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition illegalPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);
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

    final StaticPosition originalPosition = session.getBoard().getStaticPosition();

    // White makes a move (valid)
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final StaticPosition afterE4 = originalPosition
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);
    session.pressClockButton(Side.WHITE, afterE4);

    // Now it's Black's turn — the restore position should be the position after e4
    assertEquals(afterE4, session.getRestorePosition());
  }

  @Test
  void testIllegalMoveThenValidMoveSucceeds() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White makes an illegal move (knight to g3 — impossible)
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition illegalPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);
    final ArbiterResponse illegalResponse = session.pressClockButton(Side.WHITE, illegalPos);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, illegalResponse.type());

    // Ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White now makes a valid move with the SAME piece (knight) — touch-move still applies
    // from the earlier touch, so he must move the knight
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition validPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);
    final ArbiterResponse validResponse = session.pressClockButton(Side.WHITE, validPos);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, validResponse.type());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void testIllegalMoveThenDifferentPieceIsTouchMoveViolation() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White touches knight (illegal move to g3)
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition illegalPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);
    session.pressClockButton(Side.WHITE, illegalPos);

    // Ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White tries to play pawn instead — touch-move violation (knight was touched)
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));
    final StaticPosition pawnPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);
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
    session.recordEvent(Side.WHITE,
        BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    final StaticPosition afterE4 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);
    final ArbiterResponse violation = session.pressClockButton(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, violation.type());

    // Ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // White now moves the knight correctly
    session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition afterNf3 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);
    final ArbiterResponse valid = session.pressClockButton(Side.WHITE, afterNf3);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, valid.type());
  }

  private void makeSimpleMove(GameSession session, Square from, Square to, Piece piece) {
    final Side side = session.getHavingMove();
    session.recordEvent(side, BoardEvent.dragMove(from, to, piece, System.currentTimeMillis()));
    final StaticPosition afterPosition = session.getBoard().getStaticPosition()
        .createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, piece);
    final ArbiterResponse response = session.pressClockButton(side, afterPosition);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }
}
