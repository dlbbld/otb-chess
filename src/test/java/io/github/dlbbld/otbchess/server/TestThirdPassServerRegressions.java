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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/** Server-level regressions for the gaps left by the second review pass. */
class TestThirdPassServerRegressions {
  private static final String REVERT = "{\"type\":\"restorePosition\"}";

  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);

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
          .orElseThrow();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  private String create(Client white, boolean autoResume) {
    server.onMessage(white.socket,
        "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0,"
            + "\"autoResumeAfterRestore\":" + autoResume + "}");
    return white.last("gameCreated").get("gameId").getAsString();
  }

  private void join(Client client, String id) {
    server.onMessage(client.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "checkpoint timed out");
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void awaitBlockedOrFinished(Thread thread) throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(!thread.isAlive() || thread.getState() == Thread.State.BLOCKED,
        "the second action did not reach the checkpoint");
  }

  @AfterEach
  void shutdown() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void revertDuringNormalPlayIsIgnoredFromEitherPlayer(boolean autoResume) throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = create(white, autoResume);
    join(black, id);
    final var session = rooms().get(id).getSession();
    white.messages.clear();
    black.messages.clear();

    server.onMessage(black.socket, REVERT);
    server.onMessage(white.socket, REVERT);

    assertTrue(white.messages.isEmpty(), white.messages.toString());
    assertTrue(black.messages.isEmpty(), black.messages.toString());
    assertFalse(session.isWaitingForReady());
    assertFalse(session.isRestorationResumePending());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  @Test
  void onlyThePlayerWhoMustRestoreCanRevertAndOnlyOnce() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = create(white, false);
    join(black, id);
    final var session = rooms().get(id).getSession();
    session.getClock().stopClock();
    session.enterWaitingForRestoration();

    server.onMessage(black.socket, REVERT);

    assertTrue(session.isWaitingForRestoration(), "the opponent cannot revert for the player");
    assertEquals(0, white.count("positionRestored"));
    assertEquals(0, black.count("positionRestored"));

    server.onMessage(white.socket, REVERT);

    assertTrue(session.isWaitingForReady());
    assertEquals(1, white.count("positionRestored"));
    assertEquals(1, black.count("positionRestored"));

    server.onMessage(black.socket, "{\"type\":\"readyToContinue\"}");
    server.onMessage(white.socket, REVERT);

    assertEquals(1, white.count("positionRestored"), "a second Revert is not a new restoration");
    assertEquals(1, white.count("waitingForReady"));
    assertTrue(session.isReady(Side.BLACK), "a second Revert must not withdraw the opponent's Ready");

    server.onMessage(white.socket, "{\"type\":\"readyToContinue\"}");

    assertEquals(1, white.count("gameResumed"));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  @Test
  void abortAndJoinInTheSameInstantCannotBothSucceed() throws Exception {
    final var creator = new Client();
    final var joiner = new Client();
    final String id = create(creator, true);
    final var removing = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var first = new AtomicBoolean(true);
    // Pause the abort after it decided the room may go, before the room is removed.
    final var room = new GameRoom(id, new TimeControl(60000, 0)) {
      @Override
      public void stopClockTicker() {
        if (first.getAndSet(false)) {
          removing.countDown();
          await(release);
        }
        super.stopClockTicker();
      }
    };
    room.setWhitePlayer(creator.socket);
    rooms().put(id, room);
    final var abort = new Thread(() -> server.onMessage(creator.socket, "{\"type\":\"abort\"}"));
    final var join = new Thread(() -> join(joiner, id));
    try {
      abort.start();
      await(removing);
      join.start();
      awaitBlockedOrFinished(join);
      assertEquals(0, joiner.count("gameJoined"), "the join must wait for the abort to finish");
    } finally {
      release.countDown();
      abort.join(5000);
      join.join(5000);
    }
    assertFalse(abort.isAlive());
    assertFalse(join.isAlive());

    assertEquals(1, creator.count("gameAborted"));
    assertEquals(0, creator.count("gameStarted"));
    assertEquals(0, joiner.count("gameJoined"));
    assertEquals(0, joiner.count("gameStarted"));
    assertEquals("not_found", joiner.last("joinFailed").get("reason").getAsString());
    assertFalse(rooms().containsKey(id));
    assertEquals(GameState.WAITING_FOR_PLAYERS, room.getSession().getState());
    assertEquals(null, room.getBlackPlayer());
  }

  @Test
  void anAbortThatArrivesAfterTheJoinIsRefusedAndTheGameGoesOn() throws Exception {
    final var creator = new Client();
    final var joiner = new Client();
    final String id = create(creator, true);
    join(joiner, id);

    server.onMessage(creator.socket, "{\"type\":\"abort\"}");

    assertEquals(0, creator.count("gameAborted"));
    assertTrue(creator.last("error").get("message").getAsString().contains("cannot be aborted"));
    assertTrue(rooms().containsKey(id));
    assertEquals(GameState.IN_PROGRESS, rooms().get(id).getSession().getState());
  }
}
