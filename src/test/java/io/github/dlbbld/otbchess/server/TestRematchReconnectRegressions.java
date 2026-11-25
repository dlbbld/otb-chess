// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.params.provider.EnumSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/** Reconnect identity must follow the player across the rematch, even if its start message is lost. */
class TestRematchReconnectRegressions {
  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    volatile boolean open = true;
    boolean dropRematchStart;
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] { WebSocket.class }, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
            final JsonObject message = JsonParser.parseString((String) args[0]).getAsJsonObject();
            if (dropRematchStart && message.get("type").getAsString().equals("rematchStarted")) {
              open = false;
              throw new WebsocketNotConnectedException();
            }
            messages.add(message);
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
          .orElseThrow(() -> new AssertionError("No " + type + " in " + messages));
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  private String create(Client white) {
    server.onMessage(white.socket, """
        {"type":"createGame","side":"white","initialTimeMs":60000,"incrementMs":0}
        """);
    return white.last("gameCreated").get("gameId").getAsString();
  }

  private GameRoom start(Client white, Client black) throws Exception {
    final String id = create(white);
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    final GameRoom room = rooms().get(id);
    room.stopClockTicker();
    return room;
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
  }

  private void resume(Client client, String id, String token) {
    server.onMessage(client.socket,
        "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
  }

  /** The caller holds the room lock, forcing the reconnect to wait while the game changes. */
  private static void awaitRoomLock(Thread thread) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(Thread.State.BLOCKED, thread.getState(), "resume must reach the room lock");
  }

  @AfterEach
  void shutdown() throws Exception {
    for (String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @Test
  void resumeWaitingForRoomLockUsesThePlayersNewRematchSeat() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final var returning = new Client();
    final GameRoom room = start(white, black);
    final String token = white.last("gameCreated").get("token").getAsString();
    final Thread resume = new Thread(() -> resume(returning, room.getGameId(), token));
    synchronized (room) {
      resume.start();
      awaitRoomLock(resume);
      send(black, "resign");
      send(white, "rematchOffer");
      send(black, "rematchOffer");
    }
    resume.join(5000);
    assertFalse(resume.isAlive());
    assertEquals(0, returning.count("resumeFailed"));
    assertEquals("black", returning.last("resync").get("side").getAsString());
    assertSame(black.socket, room.getWhitePlayer(), "the opponent keeps the newly assigned White seat");
    assertSame(returning.socket, room.getBlackPlayer());
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());
    assertEquals(Side.WHITE, room.getSession().getClock().getRunningFor());
  }

  @Test
  void resumeWaitingForRoomLockCannotAttachAfterTheGameEnds() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final var returning = new Client();
    final GameRoom room = start(white, black);
    final String token = white.last("gameCreated").get("token").getAsString();
    final Thread resume = new Thread(() -> resume(returning, room.getGameId(), token));
    synchronized (room) {
      resume.start();
      awaitRoomLock(resume);
      send(black, "resign");
    }
    resume.join(5000);
    assertFalse(resume.isAlive());
    // No seat in the finished game: the returning connection is only shown how it ended.
    assertEquals(0, returning.count("resumeFailed"));
    assertEquals(1, returning.count("resumeEnded"));
    assertEquals(0, returning.count("resync"));
    assertSame(white.socket, room.getWhitePlayer());
  }

  @Test
  void resumeWaitingForRoomLockCannotAttachToAnAbortedRoom() throws Exception {
    final var white = new Client();
    final var returning = new Client();
    final String id = create(white);
    final GameRoom room = rooms().get(id);
    final String token = white.last("gameCreated").get("token").getAsString();
    final Thread resume = new Thread(() -> resume(returning, id, token));
    synchronized (room) {
      resume.start();
      awaitRoomLock(resume);
      send(white, "abort");
    }
    resume.join(5000);
    assertFalse(resume.isAlive());
    assertEquals(1, returning.count("resumeFailed"));
    assertEquals(0, returning.count("resync"));
    assertTrue(rooms().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(value = Side.class, names = { "WHITE", "BLACK" })
  void lostRematchStartCanResumeAndRefreshWithTheKnownToken(Side disconnectedSide) throws Exception {
    final var white = new Client();
    final var black = new Client();
    final var returning = new Client();
    final GameRoom room = start(white, black);
    final Client disconnected = disconnectedSide == Side.WHITE ? white : black;
    final String token = disconnected.last(disconnectedSide == Side.WHITE ? "gameCreated" : "gameJoined")
        .get("token").getAsString();
    send(black, "resign");
    disconnected.dropRematchStart = true;
    send(white, "rematchOffer");
    send(black, "rematchOffer");
    assertEquals(0, disconnected.count("rematchStarted"));
    server.onClose(disconnected.socket, 1006, "network interruption", true);

    resume(returning, room.getGameId(), token);
    assertEquals(0, returning.count("resumeFailed"));
    final Side newSide = disconnectedSide.getOppositeSide();
    final JsonObject snapshot = returning.last("resync");
    assertEquals(newSide.name().toLowerCase(), snapshot.get("side").getAsString());
    assertEquals("IN_PROGRESS", snapshot.get("state").getAsString());
    assertEquals(token, snapshot.get("token").getAsString());
    assertSame(returning.socket, room.getSocket(newSide));
    assertTrue(room.isConnected(newSide));

    final var refreshed = new Client();
    resume(refreshed, room.getGameId(), snapshot.get("token").getAsString());
    assertEquals(newSide.name().toLowerCase(), refreshed.last("resync").get("side").getAsString());
    assertSame(refreshed.socket, room.getSocket(newSide));
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());
  }
}
