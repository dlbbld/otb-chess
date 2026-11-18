// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.server.model.GameRoom;

class TestSecondPassServerRegressions {
  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    Consumer<JsonObject> beforeSend = message -> {};
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] {WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
            final var message = JsonParser.parseString((String) args[0]).getAsJsonObject();
            beforeSend.accept(message); messages.add(message); yield null;
          }
          case "isOpen" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });
    long count(String type) { return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).count(); }
    JsonObject last(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).reduce((a,b)->b).orElseThrow();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms"); field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  private String create(Client white) {
    server.onMessage(white.socket, """
        {"type":"createGame","side":"white","initialTimeMs":60000,"incrementMs":0,"autoResumeAfterRestore":false}
        """);
    return white.last("gameCreated").get("gameId").getAsString();
  }

  private void join(Client client, String id) {
    server.onMessage(client.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(5, TimeUnit.SECONDS), "checkpoint timed out"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }

  private static void awaitBlockedOrFinished(Thread thread) throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(!thread.isAlive() || thread.getState() == Thread.State.BLOCKED, "second action did not reach the checkpoint");
  }

  @AfterEach
  void shutdown() throws Exception {
    for (String name : new String[] {"clockExecutor", "maintenance"}) {
      final var field = GameWebSocketServer.class.getDeclaredField(name); field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @Test
  void concurrentReadyRepliesCannotSendWaitingAfterResume() throws Exception {
    final var white = new Client(); final var black = new Client();
    final String id = create(white); join(black, id);
    final var session = rooms().get(id).getSession();
    session.getClock().stopClock(); session.enterWaitingForReady();
    final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1);
    white.beforeSend = message -> {
      if (message.get("type").getAsString().equals("waitingForOpponentReady")) { entered.countDown(); await(release); }
    };
    final var first = new Thread(() -> server.onMessage(white.socket, "{\"type\":\"readyToContinue\"}"));
    final var second = new Thread(() -> server.onMessage(black.socket, "{\"type\":\"readyToContinue\"}"));
    try {
      first.start(); await(entered); second.start(); awaitBlockedOrFinished(second);
      assertEquals(0, white.count("gameResumed"), "resume must not overtake the waiting reply");
    } finally { release.countDown(); first.join(5000); second.join(5000); }
    assertFalse(first.isAlive()); assertFalse(second.isAlive());
    final var types = white.messages.stream().map(m -> m.get("type").getAsString()).toList();
    assertTrue(types.indexOf("waitingForOpponentReady") < types.indexOf("gameResumed"));
    assertEquals(1, white.count("gameResumed")); assertEquals(1, black.count("gameResumed"));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
    server.onMessage(white.socket, "{\"type\":\"readyToContinue\"}");
    assertEquals(1, white.count("waitingForOpponentReady"), "a late Ready must not re-disable the board");
  }

  @Test
  void simultaneousJoinersCannotBothReserveTheRemainingSeat() throws Exception {
    final var creator = new Client(); final var a = new Client(); final var b = new Client();
    final String id = create(creator);
    final var checked = new CountDownLatch(1); final var release = new CountDownLatch(1);
    final var firstCheck = new AtomicBoolean(true);
    // Pause the first admission after it reads availability, before it writes the seat.
    final var room = new GameRoom(id, new TimeControl(60000, 0)) {
      @Override public boolean isFull() {
        final boolean full = super.isFull();
        if (firstCheck.getAndSet(false)) { checked.countDown(); await(release); }
        return full;
      }
    };
    room.setWhitePlayer(creator.socket); rooms().put(id, room);
    final var first = new Thread(() -> join(a, id)); final var second = new Thread(() -> join(b, id));
    try {
      first.start(); await(checked); second.start(); awaitBlockedOrFinished(second);
      assertEquals(0, b.count("gameJoined"), "the second join must wait for the seat reservation");
    } finally { release.countDown(); first.join(5000); second.join(5000); }
    assertFalse(first.isAlive()); assertFalse(second.isAlive());
    assertEquals(1, a.count("gameJoined")); assertEquals(0, b.count("gameJoined"));
    assertEquals("full", b.last("joinFailed").get("reason").getAsString());
    assertEquals(a.socket, room.getBlackPlayer());
    assertEquals(Side.BLACK, room.sideForToken(a.last("gameJoined").get("token").getAsString()));
    assertEquals(1, creator.count("gameStarted")); assertEquals(1, a.count("gameStarted"));
  }

  static Stream<Arguments> terminalPositions() {
    return Stream.of(
        Arguments.of("7k/6Q1/5K2/8/8/8/8/8 b - - 0 1", "checkmate"),
        Arguments.of("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", "stalemate"),
        Arguments.of("4k3/8/8/8/8/8/8/4K3 w - - 0 1", "insufficient material"),
        Arguments.of("4k3/8/8/8/3R4/8/8/4K3 w - - 150 80", "75-move rule"));
  }

  @ParameterizedTest
  @MethodSource("terminalPositions")
  void terminalStartingPositionsAreRejectedByBothEntryPoints(String fen, String reason) throws Exception {
    final var error = assertThrows(IllegalArgumentException.class, () -> OtbChessServer.parseStartingBoard(fen));
    assertTrue(error.getMessage().contains(reason));
    final var validate = OtbChessServer.class.getDeclaredMethod("validateFenResponse", String.class);
    validate.setAccessible(true);
    final var json = JsonParser.parseString(new String((byte[])validate.invoke(null,
        "fen=" + URLEncoder.encode(fen, StandardCharsets.UTF_8)), StandardCharsets.UTF_8)).getAsJsonObject();
    assertFalse(json.get("valid").getAsBoolean()); assertTrue(json.get("message").getAsString().contains(reason));
    final var client = new Client();
    final var request = JsonParser.parseString("{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0}").getAsJsonObject();
    request.addProperty("fen", fen); server.onMessage(client.socket, request.toString());
    assertEquals(0, client.count("gameCreated")); assertTrue(rooms().isEmpty());
    assertTrue(client.last("error").get("message").getAsString().contains(reason));
  }

  @Test
  void nonTerminalPositionRemainsPlayable() {
    assertFalse(OtbChessServer.parseStartingBoard("7k/8/5KQ1/8/8/8/8/8 w - - 0 1").isCheckmate());
  }
}
