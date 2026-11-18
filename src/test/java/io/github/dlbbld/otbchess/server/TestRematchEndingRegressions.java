// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.GameResult;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

class TestRematchEndingRegressions {
  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] {WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> { messages.add(JsonParser.parseString((String) args[0]).getAsJsonObject()); yield null; }
          case "isOpen" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });

    long count(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).count();
    }

    JsonObject last(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).reduce((a, b) -> b).orElseThrow();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  private GameRoom create(Client white) throws Exception {
    server.onMessage(white.socket,
        "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0}");
    return rooms().get(white.last("gameCreated").get("gameId").getAsString());
  }

  private void join(Client black, GameRoom room) {
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + room.getGameId() + "\"}");
    room.stopClockTicker(); // Tests control the old game's tick; rematches run their normal ticker.
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(5, TimeUnit.SECONDS), "checkpoint timed out"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }

  @AfterEach
  void shutdown() throws Exception {
    for (String name : List.of("clockExecutor", "maintenance")) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  @Test
  void oldFlagFallCallbackCannotEndOrStopTheRematch() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final var room = create(white);
    final var adjudicated = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var failure = new AtomicReference<Throwable>();
    // Pause after adjudication has released the session monitor, before its result is announced.
    final var oldSession = new GameSession(new TimeControl(50, 0)) {
      @Override public Optional<GameResult> checkFlagFall() {
        final var result = super.checkFlagFall();
        if (Thread.currentThread().getName().equals("old-tick") && result.isPresent()) {
          adjudicated.countDown();
          await(release);
        }
        return result;
      }
    };
    final var sessionField = GameRoom.class.getDeclaredField("session");
    sessionField.setAccessible(true);
    sessionField.set(room, oldSession);
    join(black, room);
    Thread.sleep(80);
    final var tick = GameWebSocketServer.class.getDeclaredMethod("tickClock", GameRoom.class);
    tick.setAccessible(true);
    final var ticker = new Thread(() -> {
      try { tick.invoke(server, room); }
      catch (Throwable e) { failure.set(e); }
    }, "old-tick");
    try {
      ticker.start();
      await(adjudicated);
      // The competing request announces the already-decided flag fall; both players then rematch.
      send(black, "resign");
      assertEquals(1, white.count("gameEnded"));
      assertEquals(1, black.count("gameEnded"));
      send(white, "rematchOffer");
      send(black, "rematchOffer");
      assertNotSame(oldSession, room.getSession());
      assertEquals(1, white.count("rematchStarted"));
      assertEquals(1, black.count("rematchStarted"));
    } finally {
      release.countDown();
      ticker.join(5000);
    }
    assertFalse(ticker.isAlive());
    assertNull(failure.get());
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());
    assertNull(room.getSession().getResult());
    assertFalse(room.isGameEndAnnounced(), "the old result must not suppress the rematch's gameplay");
    final var futureField = GameRoom.class.getDeclaredField("clockTickFuture");
    futureField.setAccessible(true);
    final var future = (ScheduledFuture<?>) futureField.get(room);
    assertNotNull(future, "the rematch still needs its clock ticker");
    assertFalse(future.isCancelled());
    assertEquals(1, white.count("gameEnded"));
    assertEquals(1, black.count("gameEnded"));

    // Original Black now plays White. A real gameplay request must still be dispatched.
    final var afterE4 = BitboardPositions.from(room.getSession().getPositionBeforeTurn())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    final var press = new JsonObject();
    press.addProperty("type", "clockPress");
    press.add("boardState", new Gson().toJsonTree(MessageConverter.fromStaticPosition(afterE4)));
    server.onMessage(black.socket, press.toString());
    assertEquals(1, black.count("move_accepted"));
    assertEquals(1, white.count("opponentMoved"));
    assertEquals(Side.BLACK, room.getSession().getHavingMove());
  }

  @Test
  void identicalResultsFromDifferentGamesAreNotTheSameAnnouncement() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final var room = create(white);
    join(black, room);
    send(white, "resign");
    final var oldResult = room.getSession().getResult();
    send(white, "rematchOffer");
    send(black, "rematchOffer");
    final var newResult = room.getSession().resign(Side.WHITE);
    assertEquals(oldResult, newResult);
    assertNotSame(oldResult, newResult);
    final var announce = GameWebSocketServer.class.getDeclaredMethod("sendGameEnded", GameRoom.class, GameResult.class);
    announce.setAccessible(true);
    announce.invoke(server, room, oldResult);
    assertFalse(room.isGameEndAnnounced());
    assertEquals(1, white.count("gameEnded"));
    assertEquals(1, black.count("gameEnded"));
    announce.invoke(server, room, newResult);
    assertTrue(room.isGameEndAnnounced());
    assertEquals(2, white.count("gameEnded"));
    assertEquals(2, black.count("gameEnded"));
  }
}
