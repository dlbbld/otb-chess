// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/**
 * The clock pressed twice: a press without a move is followed by a pause in which a further press is not ruled on,
 * and a press that reaches the server when the player's lever is down is not answered - so that the second click of
 * a double click neither costs a second illegal move nor takes the first ruling out of the arbiter's window.
 */
class TestClockPressTwiceServer {
  private static final Gson GSON = new Gson();
  private static final String HEARD = "clockPressHeard";

  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);
  /** The board as the player on move has it in front of them. */
  private Map<String, String> board;

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    volatile boolean open = true;
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] { WebSocket.class }, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
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

    long heard() {
      return count(HEARD);
    }

    JsonObject last(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).reduce((a, b) -> b)
          .orElseThrow(() -> new AssertionError("no " + type + " in " + types()));
    }

    List<String> types() {
      return messages.stream().map(m -> m.get("type").getAsString()).toList();
    }

    /** The types of the messages that arrived after the first {@code from} messages. */
    List<String> typesFrom(int from) {
      return types().subList(from, messages.size());
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

  private void setLong(String name, long value) throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.setLong(server, value);
  }

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  private String start(Client white, Client black, int maxIllegalMoves, boolean autoResumeAfterRestore)
      throws Exception {
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", "white");
    create.addProperty("initialTimeMs", 600000);
    create.addProperty("incrementMs", 0);
    create.addProperty("maxIllegalMoves", maxIllegalMoves);
    create.addProperty("autoResumeAfterRestore", autoResumeAfterRestore);
    server.onMessage(white.socket, create.toString());
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    room(id).stopClockTicker();
    takeBoardFromTheServer(id);
    return id;
  }

  private String start(Client white, Client black) throws Exception {
    return start(white, black, 5, true);
  }

  private void takeBoardFromTheServer(String id) throws Exception {
    board = MessageConverter.fromStaticPosition(room(id).getSession().getPositionBeforeTurn());
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
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

  private void clock(Client client) {
    final JsonObject message = new JsonObject();
    message.addProperty("type", "clockPress");
    message.add("boardState", GSON.toJsonTree(board));
    server.onMessage(client.socket, message.toString());
  }

  private void move(Client client, String piece, String from, String to) {
    drag(client, piece, from, to);
    clock(client);
  }

  private static void assertHeard(long expected, Client... clients) {
    for (final Client client : clients) {
      assertEquals(expected, client.heard(), client.types().toString());
    }
  }

  @AfterEach
  void shutdown() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  private static final String RULING = "Illegal move: the clock was pressed without a move being made (FIDE 7.5.3).";

  private void awaitRunning(String id, Side side) throws Exception {
    await("the clock of " + side + " runs", () -> {
      try {
        return room(id).getSession().getClock().getRunningFor() == side;
      } catch (final Exception e) {
        throw new IllegalStateException(e);
      }
    });
  }

  // ----- the pause after a press without a move -----

  @Test
  void aPressWithoutAMoveStopsTheClockAndTheSecondClickIsNotAnswered() throws Exception {
    setLong("wrongClockPauseMs", 60000);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2, true);

    clock(white);

    final String ruling = white.last("illegal_move").get("message").getAsString();
    assertTrue(ruling.startsWith(RULING), ruling);
    assertTrue(ruling.contains("This is your 1st illegal move."), ruling);
    assertTrue(ruling.endsWith("Please make a move. The game is paused and will continue shortly."), ruling);
    final String info = black.last("opponentInfo").get("message").getAsString();
    assertTrue(info.endsWith("They are requested to make a move. The game is paused; the clock will restart shortly."),
        info);
    assertEquals(Side.NONE, room(id).getSession().getClock().getRunningFor());
    assertEquals("none", white.last("clockUpdate").get("running").getAsString());
    final int whiteBefore = white.messages.size();
    final int blackBefore = black.messages.size();

    // The second click of a double click, and a third one.
    clock(white);
    clock(white);

    assertEquals(List.of(), white.typesFrom(whiteBefore));
    assertEquals(List.of(), black.typesFrom(blackBefore));
    assertEquals(1, white.heard());
    assertEquals(GameState.IN_PROGRESS, room(id).getSession().getState());
  }

  @Test
  void theArbiterRestartsTheClockAndTheRulingStaysInView() throws Exception {
    setLong("wrongClockPauseMs", 40);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2, true);

    clock(white);
    awaitRunning(id, Side.WHITE);

    final JsonObject restarted = white.last("clockRestarted");
    final String ruling = restarted.get("message").getAsString();
    assertTrue(ruling.startsWith(RULING), ruling);
    assertTrue(ruling.contains("Your next illegal move will lose the game."), ruling);
    assertTrue(ruling.endsWith("Please make a move. The clock has restarted."), ruling);
    assertEquals("error", restarted.get("style").getAsString());
    assertEquals(0, black.count("clockRestarted"), "the opponent's arbiter window is left alone");
    assertEquals(0, white.count("gameResumed") + black.count("gameResumed"));
    final String info = black.last("opponentInfo").get("message").getAsString();
    assertTrue(info.endsWith("They are requested to make a move. The clock has restarted."), info);
    assertEquals("white", white.last("clockUpdate").get("running").getAsString());
    // The restart by the arbiter is no press.
    assertEquals(1, white.heard());

    // Now the lever is up again: a further press without a move is the second illegal move.
    clock(white);

    for (final Client client : new Client[] { white, black }) {
      assertEquals("ILLEGAL_MOVE_GAME_LOST", client.last("gameEnded").get("resultType").getAsString());
      assertEquals(2, client.heard());
    }
  }

  @Test
  void aMoveMadeInThePauseIsAcceptedOnceTheClockRunsAgain() throws Exception {
    setLong("wrongClockPauseMs", 150);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2, true);
    clock(white);

    move(white, "WHITE_PAWN", "e2", "e4");
    assertEquals(0, white.count("move_accepted"), "the press in the pause is not ruled on");

    awaitRunning(id, Side.WHITE);
    clock(white);

    assertEquals(1, white.count("move_accepted"));
    assertEquals(Side.BLACK, room(id).getSession().getClock().getRunningFor());
  }

  @Test
  void withoutAutomaticResumeBothPlayersAreAskedWhetherTheyAreReady() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2, false);

    clock(white);

    final String ruling = white.last("illegal_move").get("message").getAsString();
    assertTrue(ruling.endsWith("Please make a move."), ruling);
    final String asked = white.last("waitingForReady").get("message").getAsString();
    assertEquals(ruling + " Are you ready to continue?", asked);
    assertEquals("Are you ready to continue?", black.last("waitingForReady").get("message").getAsString());
    assertTrue(white.types().indexOf("illegal_move") < white.types().indexOf("waitingForReady"));
    assertTrue(room(id).getSession().isWaitingForReady());
    assertFalse(room(id).getSession().isPressWithoutMovePause());
    assertEquals(Side.NONE, room(id).getSession().getClock().getRunningFor());

    send(white, "readyToContinue");
    assertEquals(Side.NONE, room(id).getSession().getClock().getRunningFor());
    send(black, "readyToContinue");

    assertEquals(Side.WHITE, room(id).getSession().getClock().getRunningFor());
    assertEquals("Both players ready. Game continues.", white.last("gameResumed").get("message").getAsString());
    assertEquals(1, white.heard(), "no restart is a press");
  }

  @Test
  void thePauseEndsWithTheGame() throws Exception {
    setLong("wrongClockPauseMs", 60);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 2, true);
    clock(white);

    send(white, "resign");
    Thread.sleep(200);

    assertEquals(GameState.ENDED, room(id).getSession().getState());
    assertEquals(Side.NONE, room(id).getSession().getClock().getRunningFor());
    assertEquals(0, white.count("clockRestarted"));
  }

  // ----- a press that reaches the server when the player's lever is down -----

  @Test
  void aSecondPressAfterAnAcceptedMoveIsNotAnswered() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    move(white, "WHITE_PAWN", "e2", "e4");
    assertEquals(1, white.count("move_accepted"));
    final int whiteBefore = white.messages.size();
    final int blackBefore = black.messages.size();

    // Sent before the answer to the first press arrived: for the server it is Black's turn by now.
    clock(white);

    assertEquals(List.of(), white.typesFrom(whiteBefore));
    assertEquals(List.of(), black.typesFrom(blackBefore));
    assertEquals(Side.BLACK, room(id).getSession().getClock().getRunningFor());
  }

  @Test
  void aPressAfterTheGameIsNotAnswered() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black, 1, true);
    clock(white);
    assertEquals("ILLEGAL_MOVE_GAME_LOST", white.last("gameEnded").get("resultType").getAsString());
    final int whiteBefore = white.messages.size();
    final int blackBefore = black.messages.size();

    clock(white);

    assertEquals(List.of(), white.typesFrom(whiteBefore));
    assertEquals(List.of(), black.typesFrom(blackBefore));
  }
}
