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
}
