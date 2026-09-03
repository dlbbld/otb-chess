// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.java_websocket.WebSocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse.IllegalMoveDetail;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse.ReleasedPieceContext;
import io.github.dlbbld.otbchess.message.MessageKey;
import io.github.dlbbld.otbchess.message.Messages;
import io.github.dlbbld.otbchess.touchmove.TouchMoveObligation;
import io.github.dlbbld.otbchess.touchmove.TouchMoveType;

class TestGameWebSocketServer {

  /** Minimal open socket that captures every text frame sent by the server. */
  private static WebSocket capturingSocket(List<String> sent) {
    return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[] { WebSocket.class },
        (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
            if (args != null && args.length == 1 && args[0] instanceof String text) {
              sent.add(text);
            }
            yield null;
          }
          case "isOpen" -> true;
          case "equals" -> proxy == args[0];
          case "hashCode" -> System.identityHashCode(proxy);
          case "toString" -> "capturingSocket";
          default -> null;
        });
  }

  @Test
  void abortWaitingGameConfirmsAbortAndRemovesJoinCode() {
    final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);
    final List<String> creatorMessages = new ArrayList<>();
    final WebSocket creator = capturingSocket(creatorMessages);

    server.onMessage(creator,
        "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":180000,\"incrementMs\":0}");
    final JsonObject created = JsonParser.parseString(creatorMessages.get(0)).getAsJsonObject();
    assertEquals("gameCreated", created.get("type").getAsString());
    final String gameId = created.get("gameId").getAsString();

    server.onMessage(creator, "{\"type\":\"abort\"}");
    final JsonObject aborted = JsonParser.parseString(creatorMessages.get(1)).getAsJsonObject();
    assertEquals("gameAborted", aborted.get("type").getAsString());
    assertEquals("Game aborted.", aborted.get("message").getAsString());

    // The abort is not merely a client-side navigation: the room is gone and its old code can no
    // longer be joined.
    final List<String> joinerMessages = new ArrayList<>();
    server.onMessage(capturingSocket(joinerMessages),
        "{\"type\":\"joinGame\",\"gameId\":\"" + gameId + "\"}");
    final JsonObject joinFailed = JsonParser.parseString(joinerMessages.get(0)).getAsJsonObject();
    assertEquals("joinFailed", joinFailed.get("type").getAsString());
    assertEquals("not_found", joinFailed.get("reason").getAsString());
  }

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
    final IllegalMoveDetail detail = new IllegalMoveDetail(Optional.of("player-only wording: you must restore"),
        Optional.of("opponent-safe wording: they must restore"), Side.WHITE, 1, 2, false, false);
    final ArbiterResponse response = ArbiterResponse.illegalMove(detail);

    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, response.playerMessageKey());
    assertTrue(response.renderedPlayerMessage().contains("player-only wording"));
    assertTrue(response.renderedOpponentMessage().get().contains("opponent-safe wording"));
    assertFalse(response.renderedOpponentMessage().get().contains("player-only wording"));
  }

  @Test
  void testOpponentReleasedPieceViolationMessageUsesStructuredContext() {
    final ReleasedPieceContext context = new ReleasedPieceContext(Piece.WHITE_PAWN, Square.E3, Square.E2);
    final ArbiterResponse response = ArbiterResponse.releasedPieceViolation(context, false, null);

    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_PLAYER, response.playerMessageKey());
    assertEquals(Piece.WHITE_PAWN, response.releasedPieceContext().get().piece());
    assertEquals(Square.E3, response.releasedPieceContext().get().square());
    assertEquals(Messages.get(MessageKey.ARBITER_RELEASED_PIECE_OPPONENT, "pawn", "e3"),
        response.renderedOpponentMessage().get());
  }
}
