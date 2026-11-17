// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

class TestMidplayExpiryServerRegressions {
  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] { WebSocket.class }, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> { messages.add(JsonParser.parseString((String) args[0]).getAsJsonObject()); yield null; }
          case "isOpen" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });
    JsonObject last(String type) {
      return messages.stream().filter(m -> type.equals(m.get("type").getAsString())).reduce((a, b) -> b).orElseThrow();
    }
    long count(String type) {
      return messages.stream().filter(m -> type.equals(m.get("type").getAsString())).count();
    }
  }

  @SuppressWarnings("unchecked")
  private GameRoom room(String id) throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return ((Map<String, GameRoom>) field.get(server)).get(id);
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
  }

  @AfterEach
  void stopExecutors() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void expiredThirdViolationAnnouncesTheFlagDrawOnBothBoardsEvenWithoutBoardState(boolean includeBoardState)
      throws Exception {
    final var white = new Client();
    final var black = new Client();
    server.onMessage(white.socket, """
        {"type":"createGame","side":"white","initialTimeMs":500,"incrementMs":0,
        "autoResumeAfterRestore":false,"fen":"4k3/8/8/8/8/8/8/R3K3 w - - 0 1"}
        """);
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    final var room = room(id);
    // This test controls the action-versus-ticker ordering; the real clock still expires naturally.
    room.stopClockTicker();
    final var event = JsonParser.parseString("""
        {"type":"boardEvent","event":{"eventType":"DRAG_MOVE","square":"e8",
        "targetSquare":"e7","piece":"BLACK_KING","displacedPiece":"NONE"}}
        """).getAsJsonObject();
    final var changed = BitboardPositions.from(room.getSession().getPositionBeforeTurn())
        .createChangedPosition(Square.E8, Piece.NONE).createChangedPosition(Square.E7, Piece.BLACK_KING).build();
    event.add("boardState", new Gson().toJsonTree(MessageConverter.fromStaticPosition(changed)));
    for (int offense = 0; offense < 2; offense++) {
      server.onMessage(white.socket, event.toString());
      assertEquals(offense + 1, white.count("restoreRequired"));
      send(white, "restorePosition");
      send(white, "readyToContinue");
      send(black, "readyToContinue");
    }
    Thread.sleep(room.getSession().getClock().getRemainingTimeMs(Side.WHITE) + 25);
    if (!includeBoardState) event.remove("boardState");

    server.onMessage(white.socket, event.toString());

    for (final var client : new Client[] { white, black }) {
      assertEquals(1, client.count("gameEnded"));
      assertEquals("FLAG_FALL", client.last("gameEnded").get("resultType").getAsString());
      assertEquals("none", client.last("gameEnded").get("winner").getAsString());
      assertEquals("INSUFFICIENT_MATERIAL", client.last("gameEnded").get("drawReason").getAsString());
      assertEquals(0, client.last("clockUpdate").get("whiteTimeMs").getAsLong());
    }
    assertEquals(2, white.count("restoreRequired"), "no restoration is requested after the result");
    assertFalse(room.getSession().isWaitingForRestoration());
  }
}
