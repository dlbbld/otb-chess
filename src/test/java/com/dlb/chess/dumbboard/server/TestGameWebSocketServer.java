package com.dlb.chess.dumbboard.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse.IllegalMoveDetail;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse.ReleasedPieceContext;
import com.dlb.chess.dumbboard.message.MessageKey;
import com.dlb.chess.dumbboard.message.Messages;
import com.dlb.chess.dumbboard.touchmove.TouchMoveObligation;
import com.dlb.chess.dumbboard.touchmove.TouchMoveType;

class TestGameWebSocketServer {

  @Test
  void testTouchMoveMessageCarriesStructuredObligation() {
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OWN_PIECE, Square.B2,
        Piece.WHITE_PAWN);
    final ArbiterResponse response = ArbiterResponse.touchMoveViolation(obligation);

    assertEquals(MessageKey.ARBITER_TOUCH_MOVE_OWN_PLAYER, response.playerMessageKey());
    assertEquals(Piece.WHITE_PAWN, response.obligation().get().piece());
    assertEquals(Square.B2, response.obligation().get().square());
    assertEquals(Messages.get(MessageKey.ARBITER_TOUCH_MOVE_OWN_OPPONENT, "pawn", "b2"),
        response.renderedOpponentMessage().get());
  }

  @Test
  void testOpponentIllegalMoveMessageUsesOpponentReasonNotPlayerMessage() {
    final IllegalMoveDetail detail = new IllegalMoveDetail(
        Optional.of("player-only wording: you must restore"),
        Optional.of("opponent-safe wording: they must restore"),
        Side.WHITE,
        1,
        2,
        false);
    final ArbiterResponse response = ArbiterResponse.illegalMove(detail);

    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, response.playerMessageKey());
    assertTrue(response.renderedPlayerMessage().contains("player-only wording"));
    assertTrue(response.renderedOpponentMessage().get().contains("opponent-safe wording"));
    assertFalse(response.renderedOpponentMessage().get().contains("player-only wording"));
  }

  @Test
  void testOpponentReleasedPieceViolationMessageUsesStructuredContext() {
    final ReleasedPieceContext context = new ReleasedPieceContext(Piece.WHITE_PAWN, Square.E3);
    final ArbiterResponse response = ArbiterResponse.releasedPieceViolation(context, null);

    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_PLAYER, response.playerMessageKey());
    assertEquals(Piece.WHITE_PAWN, response.releasedPieceContext().get().piece());
    assertEquals(Square.E3, response.releasedPieceContext().get().square());
    assertEquals(Messages.get(MessageKey.ARBITER_RELEASED_PIECE_OPPONENT, "pawn", "e3"),
        response.renderedOpponentMessage().get());
  }
}
