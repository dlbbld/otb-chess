package com.dlb.chess.dumbboard.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.dumbboard.touchmove.TouchMoveObligation;
import com.dlb.chess.dumbboard.touchmove.TouchMoveType;

class TestGameWebSocketServer {

  @Test
  void testOpponentTouchMoveViolationMessageForOwnTouchedPiece() {
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OWN_PIECE, Square.B2,
        Piece.WHITE_PAWN);

    assertEquals("Your opponent has made a touch-move violation. They first touched the pawn on b2,"
        + " which has legal moves, but moved another piece. They are requested to restore the position"
        + " and move the touched piece.", GameWebSocketServer.formatOpponentTouchMoveViolation(obligation));
  }

  @Test
  void testOpponentIllegalMoveMessageIncludesReason() {
    assertEquals("Your opponent made an illegal move: the knight cannot move in this way."
        + " They are requested to restore the position.",
        GameWebSocketServer.formatOpponentIllegalMove(
            "Illegal move: the knight cannot move in this way. Please restore the position."));
  }

  @Test
  void testOpponentReleasedPieceViolationMessage() {
    assertEquals("Your opponent violated the released-piece rule: they already released the pawn on e3,"
        + " and that was a legal move. Under the released-piece rule, they cannot change this position anymore."
        + " They are requested to put the pawn back on e3 and press the clock.",
        GameWebSocketServer.formatOpponentReleasedPieceViolation(
            "Released-piece violation: You already released the pawn on e3, and that was a legal move."
                + " Under the released-piece rule, you cannot change this position anymore."
                + " Please put the pawn back on e3 and press the clock."));
  }
}
