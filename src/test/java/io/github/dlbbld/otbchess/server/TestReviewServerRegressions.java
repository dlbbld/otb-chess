// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.java_websocket.WebSocket;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

class TestReviewServerRegressions {
  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);

  private static class Client {
    final List<JsonObject> messages = new ArrayList<>();
    boolean open = true;
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] {WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> { messages.add(JsonParser.parseString((String) args[0]).getAsJsonObject()); yield null; }
          case "isOpen" -> open;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });
    JsonObject last(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).reduce((a,b) -> b).orElseThrow();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  private String start(Client white, Client black) {
    server.onMessage(white.socket, "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0,\"autoResumeAfterRestore\":false}");
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    return id;
  }

  private void reap() throws Exception {
    reap(0);
  }

  private void reap(long retentionMs) throws Exception {
    final var ttl = GameWebSocketServer.class.getDeclaredField("endedRoomTtlMs");
    ttl.setAccessible(true);
    ttl.setLong(server, retentionMs);
    final var method = GameWebSocketServer.class.getDeclaredMethod("reapAbandonedRooms");
    method.setAccessible(true);
    method.invoke(server);
  }

  @AfterEach
  void stopExecutors() throws Exception {
    for (final String name : new String[] {"clockExecutor", "maintenance"}) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @Test
  void resumeRestoresBothReadyPromptsAndAcknowledgedReadiness() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    final var session = rooms().get(id).getSession();
    session.getClock().stopClock();
    session.enterWaitingForRestoration();
    session.completeRestoration();
    final String token = white.last("gameCreated").get("token").getAsString();
    final var returning = new Client();
    server.onMessage(returning.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
    assertEquals("Are you ready to continue?", returning.last("waitingForReady").get("message").getAsString());
    assertEquals("Are you ready to continue?", black.last("waitingForReady").get("message").getAsString());
    session.playerReady(Side.WHITE);
    server.onMessage(returning.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
    assertTrue(returning.last("waitingForOpponentReady").get("message").getAsString().contains("Waiting"));
    assertTrue(session.playerReady(Side.BLACK));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  @Test
  void resumePreservesAnUnpressedMoveWithoutCommittingIt() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    final var room = rooms().get(id);
    final var original = room.getSession().getPositionBeforeTurn();
    final var after = BitboardPositions.from(original).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    final var message = JsonParser.parseString("""
        {"type":"boardEvent","event":{"eventType":"DRAG_MOVE","square":"e2",
        "targetSquare":"e4","piece":"WHITE_PAWN","displacedPiece":"NONE"}}
        """).getAsJsonObject();
    final var physicalBoard = new Gson().toJsonTree(MessageConverter.fromStaticPosition(after));
    message.add("boardState", physicalBoard);
    server.onMessage(white.socket, message.toString());
    final var returning = new Client();
    final String token = white.last("gameCreated").get("token").getAsString();
    server.onMessage(returning.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
    assertEquals(physicalBoard, returning.last("resync").get("board"));
    assertEquals(original, room.getSession().getBoard().getBitboardPosition());
    assertEquals(Side.WHITE, room.getSession().getHavingMove());
  }

  @Test
  void cleanupRetainsActiveAndConnectedRematchRoomsButReclaimsDisconnectedEndings() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    reap();
    assertTrue(rooms().containsKey(id));
    server.onMessage(white.socket, "{\"type\":\"resign\"}");
    reap();
    assertTrue(rooms().containsKey(id));
    white.open = false;
    reap();
    assertTrue(rooms().containsKey(id));
    black.open = false;
    reap(60000);
    assertTrue(rooms().containsKey(id));
    reap();
    assertFalse(rooms().containsKey(id));
    final var next = new Client();
    server.onMessage(next.socket, "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0}");
    assertNotNull(next.last("gameCreated"));
  }
}
