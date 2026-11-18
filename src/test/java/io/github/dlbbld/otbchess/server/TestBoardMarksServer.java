// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/** The arbiter's circles as both players are sent them: every change once, and again to a page that comes back. */
class TestBoardMarksServer {
  private static final Gson GSON = new Gson();

  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);
  /** The board as the player on move has it in front of them. */
  private Map<String, String> board;

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] { WebSocket.class }, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
            messages.add(JsonParser.parseString((String) args[0]).getAsJsonObject());
            yield null;
          }
          case "isOpen" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });

    long count(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).count();
    }

    JsonObject last(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).reduce((a, b) -> b)
          .orElseThrow(() -> new AssertionError("no " + type + " in " + types()));
    }

    List<String> types() {
      return messages.stream().map(m -> m.get("type").getAsString()).toList();
    }

    /** The marks last sent, as "square:color". */
    List<String> marks() {
      final List<String> marks = new ArrayList<>();
      for (final var mark : last("boardMarks").getAsJsonArray("marks")) {
        marks.add(mark.getAsJsonObject().get("square").getAsString() + ":"
            + mark.getAsJsonObject().get("color").getAsString());
      }
      return marks;
    }

    String token() {
      return messages.stream().filter(m -> m.has("token")).reduce((a, b) -> b).orElseThrow().get("token")
          .getAsString();
    }
  }

  @SuppressWarnings("unchecked")
  private GameRoom room(String id) throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return ((Map<String, GameRoom>) field.get(server)).get(id);
  }

  private String start(Client white, Client black, int maxIllegalMoves) throws Exception {
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", "white");
    create.addProperty("initialTimeMs", 600000);
    create.addProperty("incrementMs", 0);
    create.addProperty("maxIllegalMoves", maxIllegalMoves);
    server.onMessage(white.socket, create.toString());
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    room(id).stopClockTicker();
    board = MessageConverter.fromStaticPosition(room(id).getSession().getPositionBeforeTurn());
    return id;
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
  }

  private void event(Client client, String eventType, String piece, String from, String to) {
    if (!"CLICK".equals(eventType)) {
      board.put(from, "NONE");
      board.put(to, piece);
    }
    final JsonObject event = new JsonObject();
    event.addProperty("eventType", eventType);
    event.addProperty("square", from);
    event.addProperty("targetSquare", to);
    event.addProperty("piece", piece);
    event.addProperty("displacedPiece", "NONE");
    final JsonObject message = new JsonObject();
    message.addProperty("type", "boardEvent");
    message.add("event", event);
    message.add("boardState", GSON.toJsonTree(board));
    server.onMessage(client.socket, message.toString());
  }

  private void click(Client client, String piece, String square) {
    event(client, "CLICK", piece, square, "NONE");
  }

  private void drag(Client client, String piece, String from, String to) {
    event(client, "DRAG_MOVE", piece, from, to);
  }

  private void clock(Client client) {
    final JsonObject message = new JsonObject();
    message.addProperty("type", "clockPress");
    message.add("boardState", GSON.toJsonTree(board));
    server.onMessage(client.socket, message.toString());
  }

  /** Revert, and wait until the clock runs again. */
  private void revert(Client client, String id) throws Exception {
    final long resumed = client.count("gameResumed");
    send(client, "restorePosition");
    board = MessageConverter.fromStaticPosition(room(id).getSession().getPositionBeforeTurn());
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (client.count("gameResumed") == resumed && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(resumed + 1, client.count("gameResumed"), client.types().toString());
  }

  @AfterEach
  void shutdown() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @Test
  void theTouchedPieceIsSentToBothBoardsAndTakenOffWhenTheMoveIsMade() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2);
    click(white, "WHITE_KNIGHT", "g1");
    drag(white, "WHITE_PAWN", "e2", "e4");
    final int before = white.messages.size();

    clock(white);

    assertEquals(List.of("g1:green"), white.marks());
    assertEquals(List.of("g1:green"), black.marks());
    // The circle comes with the ruling, before the request to restore.
    assertEquals(List.of("touch_move_violation", "boardMarks", "restoreRequired"),
        white.types().subList(before, white.messages.size()));

    // The restoration changes nothing about the obligation: no second message.
    revert(white, id);
    assertEquals(1, white.count("boardMarks"));
    assertEquals(1, black.count("boardMarks"));

    drag(white, "WHITE_KNIGHT", "g1", "f3");
    clock(white);
    assertEquals("move_accepted", white.types().get(white.types().lastIndexOf("boardMarks") - 1));
    assertEquals(List.of(), white.marks());
    assertEquals(List.of(), black.marks());
    assertEquals(2, white.count("boardMarks"));
    assertEquals(2, black.count("boardMarks"));
  }

  @Test
  void theIllegalMoveIsSentToBothBoardsAndTakenOffWithTheRestoration() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2);
    drag(white, "WHITE_KNIGHT", "b1", "b3");

    clock(white);

    assertEquals(List.of("b1:red", "b3:red"), white.marks());
    assertEquals(List.of("b1:red", "b3:red"), black.marks());

    revert(white, id);
    for (final Client client : new Client[] { white, black }) {
      assertEquals(List.of(), client.marks());
      assertEquals(2, client.count("boardMarks"));
      assertTrue(client.types().lastIndexOf("boardMarks") > client.types().lastIndexOf("positionRestored"),
          client.types().toString());
    }
  }

  @Test
  void aPlayerWhoComesBackIsSentTheCurrentMarks() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2);
    click(white, "WHITE_KNIGHT", "g1");
    drag(white, "WHITE_PAWN", "e2", "e4");
    clock(white);
    revert(white, id);

    for (final Client away : new Client[] { white, black }) {
      final var back = new Client();
      server.onMessage(back.socket,
          "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + away.token() + "\"}");

      assertEquals(List.of("g1:green"), back.marks());
      assertTrue(back.types().indexOf("boardMarks") > back.types().indexOf("resync"), back.types().toString());
    }
  }

  @Test
  void aPlayerWhoComesBackToABoardWithoutMarksIsSentNone() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2);
    drag(white, "WHITE_PAWN", "e2", "e4");
    clock(white);

    final var back = new Client();
    server.onMessage(back.socket,
        "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + black.token() + "\"}");

    assertEquals(1, back.count("resync"));
    assertEquals(0, back.count("boardMarks"));
    assertEquals(0, white.count("boardMarks"), "an ordinary game sends no marks at all");
    assertEquals(0, black.count("boardMarks"));
  }

  @Test
  void theMarksOfAFinishedGameDoNotReachTheRematch() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 1);
    drag(white, "WHITE_KNIGHT", "b1", "b3");
    clock(white);
    assertEquals("ILLEGAL_MOVE_GAME_LOST", white.last("gameEnded").get("resultType").getAsString());
    assertEquals(List.of("b1:red", "b3:red"), black.marks());

    send(white, "rematchOffer");
    send(black, "rematchOffer");
    assertEquals(1, white.count("rematchStarted"));
    assertEquals(List.of(), room(id).getSession().getBoardMarks());

    // The first move of the rematch (the colours are swapped: the former Black plays White).
    room(id).stopClockTicker();
    board = MessageConverter.fromStaticPosition(room(id).getSession().getPositionBeforeTurn());
    drag(black, "WHITE_PAWN", "e2", "e4");
    clock(black);

    assertEquals(1, black.count("move_accepted"));
    assertEquals(1, white.count("boardMarks"), "nothing changed in the new game, so nothing is sent");
    assertEquals(1, black.count("boardMarks"));
  }
}
