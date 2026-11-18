// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/** What the two players are sent in the cases found by the final review pass before the release. */
class TestFinalPassServerRegressions {
  private static final Gson GSON = new Gson();

  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);
  /** The board as the player on move has it in front of them. */
  private Map<String, String> board;

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    volatile boolean open = true;
    /** The socket still reports open, but the send fails. */
    volatile boolean closesDuringSend;
    /** Runs once inside the next send, before it fails - a close callback arriving at that moment. */
    volatile Runnable duringSend;
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] { WebSocket.class }, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
            final Runnable hook = duringSend;
            if (hook != null) {
              duringSend = null;
              hook.run();
            }
            if (closesDuringSend || !open) {
              throw new WebsocketNotConnectedException();
            }
            messages.add(JsonParser.parseString((String) args[0]).getAsJsonObject());
            yield null;
          }
          case "isOpen" -> open;
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

    String text(String type) {
      return last(type).get("message").getAsString();
    }

    String token() {
      return messages.stream().filter(m -> m.has("token")).reduce((a, b) -> b).orElseThrow().get("token")
          .getAsString();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  @SuppressWarnings("unchecked")
  private Map<WebSocket, String> seats() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("playerGameMap");
    field.setAccessible(true);
    return (Map<WebSocket, String>) field.get(server);
  }

  private void setLong(String name, long value) throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.setLong(server, value);
  }

  private void setInt(String name, int value) throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(server, value);
  }

  private void sendCreate(Client creator, long timeMs, String fen) {
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", "white");
    create.addProperty("initialTimeMs", timeMs);
    create.addProperty("incrementMs", 0);
    if (fen != null) {
      create.addProperty("fen", fen);
    }
    server.onMessage(creator.socket, create.toString());
  }

  private String create(Client creator, long timeMs, String fen) {
    sendCreate(creator, timeMs, fen);
    return creator.last("gameCreated").get("gameId").getAsString();
  }

  private void join(Client joiner, String id) {
    server.onMessage(joiner.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
  }

  /** Starts a game. The periodic clock check is stopped, so that a test decides when the clocks are looked at. */
  private String start(Client white, Client black, long timeMs, String fen) throws Exception {
    final String id = create(white, timeMs, fen);
    join(black, id);
    rooms().get(id).stopClockTicker();
    board = MessageConverter.fromStaticPosition(rooms().get(id).getSession().getPositionBeforeTurn());
    return id;
  }

  private String start(Client white, Client black) throws Exception {
    return start(white, black, 600000, null);
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
  }

  private void sendWithBoard(Client client, String type) {
    final JsonObject message = new JsonObject();
    message.addProperty("type", type);
    message.add("boardState", GSON.toJsonTree(board));
    server.onMessage(client.socket, message.toString());
  }

  private void resume(Client client, String id, String token) {
    server.onMessage(client.socket,
        "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
  }

  private void close(Client client) {
    client.open = false;
    server.onClose(client.socket, 1006, "", true);
  }

  private void claim(Client client, String claimType) {
    server.onMessage(client.socket, "{\"type\":\"claimDraw\",\"claimType\":\"" + claimType + "\"}");
  }

  private void drag(Client client, String piece, String from, String to) {
    board.put(from, "NONE");
    board.put(to, piece);
    final JsonObject event = new JsonObject();
    event.addProperty("eventType", "DRAG_MOVE");
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

  /** One move by each player, with the clock presses: after that both players have made a move. */
  private void playOneMoveEach(Client white, Client black) {
    drag(white, "WHITE_PAWN", "e2", "e4");
    sendWithBoard(white, "clockPress");
    drag(black, "BLACK_PAWN", "e7", "e5");
    sendWithBoard(black, "clockPress");
  }

  private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  @AfterEach
  void shutdown() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  // ----- finding 1: a connection that no longer holds a seat -----

  @Test
  void aSecondWindowTakesTheSeatAndTheFirstIsTakenOutOfTheGameAndTold() throws Exception {
    final var white = new Client();
    final var blackOld = new Client();
    final var blackNew = new Client();
    final String id = start(white, blackOld);

    resume(blackNew, id, blackOld.token());

    assertSame(blackNew.socket, rooms().get(id).getBlackPlayer());
    assertEquals("This game is now open in another window. This window no longer takes part.",
        blackOld.text("sessionReplaced"));
    assertFalse(seats().containsKey(blackOld.socket), "the old connection must leave the game");
    assertEquals(id, seats().get(blackNew.socket));
    assertEquals(0, blackNew.count("sessionReplaced"));
    assertEquals(0, white.count("sessionReplaced"));
  }

  @Test
  void theOldWindowCannotAgreeADrawOnTheOwnOffer() throws Exception {
    final var white = new Client();
    final var blackOld = new Client();
    final var blackNew = new Client();
    final String id = start(white, blackOld);
    playOneMoveEach(white, blackOld);
    resume(blackNew, id, blackOld.token());
    send(blackNew, "offerDraw");
    assertEquals(1, white.count("drawOffered"));

    // The journey of the finding: Offer draw in the old window agreed the draw without White.
    send(blackOld, "offerDraw");
    send(blackOld, "acceptDraw");

    final var session = rooms().get(id).getSession();
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(session.getDrawOfferManager().isDrawOffered(), "Black's offer is still White's to answer");
    assertEquals(0, white.count("gameEnded"));
    assertEquals(0, blackNew.count("gameEnded"));
    assertEquals("You are not in a game.", blackOld.text("error"));
    assertEquals(0, blackOld.messages.stream().filter(m -> m.has("devDetail")).count(), blackOld.types().toString());

    // White, the player the offer was made to, can still accept it.
    send(white, "acceptDraw");
    assertEquals("DRAW_AGREEMENT", white.last("gameEnded").get("resultType").getAsString());
    assertEquals("white", white.last("gameEnded").get("actor").getAsString());
  }

  @ParameterizedTest
  @ValueSource(strings = { "rejectDraw", "resign", "offerDraw", "claimDraw", "clockPress", "cancelDrawClaim",
      "opponentClockPressed", "claimVictory", "restorePosition", "readyToContinue" })
  void theOldWindowChangesNothingAndCausesNoInternalError(String action) throws Exception {
    final var white = new Client();
    final var blackOld = new Client();
    final var blackNew = new Client();
    final String id = start(white, blackOld);
    resume(blackNew, id, blackOld.token());
    send(blackNew, "offerDraw");
    final int whiteMessages = white.messages.size();
    final int blackMessages = blackNew.messages.size();

    switch (action) {
      case "claimDraw" -> claim(blackOld, "THREEFOLD_ON_BOARD");
      case "clockPress" -> sendWithBoard(blackOld, "clockPress");
      default -> send(blackOld, action);
    }

    final var session = rooms().get(id).getSession();
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(session.getDrawOfferManager().isDrawOffered(), "the pending offer must not be removed");
    assertEquals(Side.BLACK, session.getDrawOfferManager().getOfferingSide());
    assertEquals(whiteMessages, white.messages.size(), white.types().toString());
    assertEquals(blackMessages, blackNew.messages.size(), blackNew.types().toString());
    assertEquals("You are not in a game.", blackOld.text("error"));
    assertEquals(0, blackOld.messages.stream().filter(m -> m.has("devDetail")).count());
  }

  @Test
  void aConnectionKnownToTheRoomButWithoutASeatIsRefused() throws Exception {
    // Belt and braces: should a connection ever stay mapped to a room without holding a seat, no
    // handler may act on it for "no side".
    final var white = new Client();
    final var black = new Client();
    final var stray = new Client();
    final String id = start(white, black);
    playOneMoveEach(white, black);
    send(black, "offerDraw");
    seats().put(stray.socket, id);

    send(stray, "acceptDraw");
    send(stray, "rejectDraw");
    send(stray, "offerDraw");
    send(stray, "resign");

    final var session = rooms().get(id).getSession();
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(session.getDrawOfferManager().isDrawOffered());
    assertEquals(4, stray.count("error"));
    assertEquals("You are not a player in this game.", stray.text("error"));
    assertEquals(0, white.count("gameEnded"));
  }

  @Test
  void theOldWindowClosingLaterIsNoDisconnectOfThePlayer() throws Exception {
    final var white = new Client();
    final var blackOld = new Client();
    final var blackNew = new Client();
    final String id = start(white, blackOld);
    setLong("disconnectGraceMs", 0);
    resume(blackNew, id, blackOld.token());

    close(blackOld);
    Thread.sleep(60);

    assertNull(rooms().get(id).getDisconnectedAt(Side.BLACK));
    assertEquals(0, white.count("opponentDisconnected"));
    assertTrue(rooms().get(id).isConnected(Side.BLACK));
  }
}
