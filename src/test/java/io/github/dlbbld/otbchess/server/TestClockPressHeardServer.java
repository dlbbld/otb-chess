// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/**
 * The message behind the clock sound (E-004): {@code clockPressHeard} goes to both players, once, for every press of a
 * lever that was up - before anything the arbiter says about it - and for nothing else.
 */
class TestClockPressHeardServer {
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

  // ----- presses of the own lever that the arbiter rules on -----

  @Test
  void anAcceptedMoveIsHeardOnceOnEachBoardBeforeTheRuling() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);
    drag(white, "WHITE_PAWN", "e2", "e4");
    assertHeard(0, white, black);
    final int whiteBefore = white.messages.size();
    final int blackBefore = black.messages.size();

    clock(white);

    assertHeard(1, white, black);
    assertEquals(List.of(HEARD, "move_accepted"), white.typesFrom(whiteBefore).subList(0, 2));
    assertEquals(List.of(HEARD, "opponentMoved"), black.typesFrom(blackBefore).subList(0, 2));
    assertEquals(1, white.last(HEARD).size(), "the message carries nothing but its type");

    move(black, "BLACK_PAWN", "e7", "e5");
    assertHeard(2, white, black);
  }

  @Test
  void anIllegalMoveIsHeardOnceOnEachBoardBeforeTheRuling() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);
    drag(white, "WHITE_KNIGHT", "b1", "b3");
    final int whiteBefore = white.messages.size();
    final int blackBefore = black.messages.size();

    clock(white);

    assertHeard(1, white, black);
    assertEquals(List.of(HEARD, "illegal_move"), white.typesFrom(whiteBefore).subList(0, 2));
    assertEquals(HEARD, black.typesFrom(blackBefore).get(0));
  }

  @Test
  void aPressWithoutAMoveIsHeardOnceOnEachBoard() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);

    clock(white);

    assertEquals(1, white.count("illegal_move"));
    assertHeard(1, white, black);
  }

  @Test
  void aPressThatLosesTheGameIsHeardBeforeTheResult() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black, 1, true);
    drag(white, "WHITE_KNIGHT", "b1", "b3");

    clock(white);

    for (final Client client : new Client[] { white, black }) {
      assertEquals("ILLEGAL_MOVE_GAME_LOST", client.last("gameEnded").get("resultType").getAsString());
      assertEquals(1, client.heard());
      assertTrue(client.types().indexOf(HEARD) < client.types().indexOf("gameEnded"), client.types().toString());
    }
  }

  // ----- presses that are not ruled on: the lever was down, or the clock was not running for the player -----

  @Test
  void aPressWhenItIsNotThePlayersTurnIsSilent() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);

    clock(black);

    assertHeard(0, white, black);
  }

  @Test
  void aPressOfTheOpponentsLeverThatIsAlreadyDownIsSilent() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);

    // White is to move: Black's lever is down.
    send(white, "opponentClockPressed");

    assertHeard(0, white, black);
    assertEquals(0, white.count("opponentClockPressed"));
  }

  @Test
  void aPressWhileThePositionIsToBeRestoredIsSilent() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    move(white, "WHITE_KNIGHT", "b1", "b3");
    assertTrue(room(id).getSession().isWaitingForRestoration());
    assertHeard(1, white, black);

    clock(white);
    clock(white);

    assertHeard(1, white, black);
  }

  @Test
  void aPressWhileBothPlayersAreAskedWhetherTheyAreReadyIsSilent() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 5, false);
    move(white, "WHITE_KNIGHT", "b1", "b3");
    send(white, "restorePosition");
    takeBoardFromTheServer(id);
    assertTrue(room(id).getSession().isWaitingForReady());
    assertHeard(1, white, black);

    clock(white);

    assertHeard(1, white, black);

    // Both are ready: the clock runs again, and no message of the restoration made a sound.
    send(white, "readyToContinue");
    send(black, "readyToContinue");
    assertEquals(Side.WHITE, room(id).getSession().getClock().getRunningFor());
    assertHeard(1, white, black);
    move(white, "WHITE_PAWN", "e2", "e4");
    assertHeard(2, white, black);
  }

  @Test
  void aPressAfterTheGameIsSilent() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);
    send(black, "resign");
    assertEquals(1, white.count("gameEnded"));

    clock(white);
    send(black, "opponentClockPressed");

    assertHeard(0, white, black);
  }

  // ----- the opponent's lever -----

  @Test
  void everyWrongClockPressIsHeardOnBothBoardsAlsoTheOneThatLosesTheGame() throws Exception {
    setLong("wrongClockPauseMs", 40);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);

    // White is to move and White's clock runs: Black presses White's lever.
    for (int press = 1; press <= 2; press++) {
      final int blackBefore = black.messages.size();
      final int whiteBefore = white.messages.size();
      send(black, "opponentClockPressed");
      assertHeard(press, white, black);
      assertEquals(List.of(HEARD, "opponentClockPressed"), black.typesFrom(blackBefore).subList(0, 2));
      assertEquals(List.of(HEARD, "opponentInfo"), white.typesFrom(whiteBefore).subList(0, 2));
      await("the clock restarts", () -> {
        try {
          return room(id).getSession().getClock().getRunningFor() == Side.WHITE;
        } catch (final Exception e) {
          throw new IllegalStateException(e);
        }
      });
      // The restart by the arbiter is no press.
      assertHeard(press, white, black);
    }

    send(black, "opponentClockPressed");

    for (final Client client : new Client[] { white, black }) {
      assertEquals("WRONG_CLOCK_PRESS_GAME_LOST", client.last("gameEnded").get("resultType").getAsString());
      assertEquals(3, client.heard());
      assertTrue(client.types().lastIndexOf(HEARD) < client.types().indexOf("gameEnded"), client.types().toString());
    }
  }

  @Test
  void pressesDuringThePauseAfterAWrongClockPressAreSilent() throws Exception {
    setLong("wrongClockPauseMs", 60000);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    send(black, "opponentClockPressed");
    assertEquals(Side.NONE, room(id).getSession().getClock().getRunningFor(), "the arbiter stopped the clocks");
    assertHeard(1, white, black);

    // Neither lever is up while the clock is stopped.
    drag(white, "WHITE_PAWN", "e2", "e4");
    clock(white);
    send(black, "opponentClockPressed");

    assertHeard(1, white, black);
  }

  // ----- what follows a press, or looks like one, makes no sound -----

  @Test
  void aPressDuringTheDelayBeforeTheGameResumesIsHeardOnceAndTheRestartsAreSilent() throws Exception {
    setLong("wrongClockPauseMs", 40);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    move(white, "WHITE_KNIGHT", "b1", "b3");
    send(white, "restorePosition");
    takeBoardFromTheServer(id);
    assertTrue(room(id).getSession().isRestorationResumePending());
    assertHeard(1, white, black);
    final int whiteBefore = white.messages.size();

    // The clock shows a pause, but this press counts: it ends the pause and is ruled on - as a press without
    // a move, for which the arbiter stops the clock once more.
    clock(white);

    assertHeard(2, white, black);
    // The lever first, then the arbiter.
    assertEquals(List.of(HEARD, "illegal_move"), white.typesFrom(whiteBefore).subList(0, 2));

    // The delayed resume finds the restoration pause over, and the arbiter restarts the clock: no press.
    final var method = GameWebSocketServer.class.getDeclaredMethod("resumeAfterRestorationDelay", GameRoom.class);
    method.setAccessible(true);
    method.invoke(server, room(id));
    await("the clock restarts", () -> {
      try {
        return room(id).getSession().getClock().getRunningFor() == Side.WHITE;
      } catch (final Exception e) {
        throw new IllegalStateException(e);
      }
    });
    assertEquals(1, white.typesFrom(whiteBefore).stream().filter("gameResumed"::equals).count());
    assertHeard(2, white, black);
  }

  @Test
  void aPlayerWhoWasAwayDoesNotHearThePressLater() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    black.open = false;

    move(white, "WHITE_PAWN", "e2", "e4");

    assertEquals(1, white.heard());
    assertEquals(0, black.heard());

    final var back = new Client();
    server.onMessage(back.socket,
        "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + black.token() + "\"}");
    assertEquals(1, back.count("resync"));
    assertEquals(0, back.heard(), "what happened while the player was away is not replayed");
    assertEquals(1, white.heard());
  }

  @Test
  void aMoveThatEndsTheGameWithoutAClockPressIsSilent() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);
    move(white, "WHITE_PAWN", "f2", "f3");
    move(black, "BLACK_PAWN", "e7", "e5");
    move(white, "WHITE_PAWN", "g2", "g4");
    assertHeard(3, white, black);

    // Checkmate: the game ends with the move, nobody presses a clock.
    drag(black, "BLACK_QUEEN", "d8", "h4");

    for (final Client client : new Client[] { white, black }) {
      assertEquals("CHECKMATE", client.last("gameEnded").get("resultType").getAsString());
      assertEquals(3, client.heard());
    }
  }

  @Test
  void theRematchStartsSilentlyAndItsFirstPressIsHeardOnceOnEachBoard() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    move(white, "WHITE_PAWN", "e2", "e4");
    send(black, "resign");
    send(white, "rematchOffer");
    send(black, "rematchOffer");
    assertEquals(1, white.count("rematchStarted"));
    assertHeard(1, white, black);

    // The colours are swapped: the former Black plays White.
    room(id).stopClockTicker();
    takeBoardFromTheServer(id);
    move(black, "WHITE_PAWN", "e2", "e4");

    assertEquals(1, black.count("move_accepted"));
    assertHeard(2, white, black);
  }
}
