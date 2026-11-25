// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/**
 * A player who comes back to their finished game - a page reload after the end - is shown how it ended: the board, the
 * final times and the result. The seat is not given back.
 */
class TestReturnToEndedGame {
  private static final String FEN = "4k3/8/8/8/8/8/8/4K2R w K - 0 1";

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
          .orElseThrow(() -> new AssertionError("No " + type + " in " + messages));
    }
  }

  private final Client white = new Client();
  private final Client black = new Client();
  private final Client returning = new Client();

  @SuppressWarnings("unchecked")
  private Map<String, GameRoom> rooms() throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return (Map<String, GameRoom>) field.get(server);
  }

  /** White creates a game from {@link #FEN}, Black joins and resigns at once. */
  private GameRoom finishedGame() throws Exception {
    final JsonObject create = JsonParser
        .parseString("{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0}")
        .getAsJsonObject();
    create.addProperty("fen", FEN);
    server.onMessage(white.socket, create.toString());
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    final GameRoom room = rooms().get(id);
    room.stopClockTicker();
    server.onMessage(black.socket, "{\"type\":\"resign\"}");
    assertEquals(GameState.ENDED, room.getSession().getState());
    return room;
  }

  private void resume(Client client, String id, String token) {
    server.onMessage(client.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
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
  void theReturningPlayerGetsTheBoardTheTimesAndTheResult() throws Exception {
    final GameRoom room = finishedGame();

    resume(returning, room.getGameId(), black.last("gameJoined").get("token").getAsString());

    assertEquals(0, returning.count("resumeFailed"));
    assertEquals(0, returning.count("resync"));
    final JsonObject ended = returning.last("resumeEnded");
    assertEquals(room.getGameId(), ended.get("gameId").getAsString());
    assertEquals("black", ended.get("side").getAsString());
    assertEquals("This game has already ended.", ended.get("message").getAsString());
    // The board as it stood: the position the game was created with, nothing was moved.
    assertEquals(white.last("gameCreated").get("board"), ended.get("board"));
    assertEquals(white.last("gameCreated").get("timeControlLabel"), ended.get("timeControlLabel"));
    // The final times, and no clock running.
    final JsonObject finalTimes = white.last("clockUpdate");
    final JsonObject clock = ended.getAsJsonObject("clock");
    assertEquals(finalTimes.get("whiteTimeMs"), clock.get("whiteTimeMs"));
    assertEquals(finalTimes.get("blackTimeMs"), clock.get("blackTimeMs"));
    assertEquals("none", clock.get("running").getAsString());
    // The result, exactly as it was announced to both players when the game ended.
    assertEquals(white.last("gameEnded"), ended.getAsJsonObject("result"));
    assertEquals("RESIGNATION", ended.getAsJsonObject("result").get("resultType").getAsString());
    assertEquals("white", ended.getAsJsonObject("result").get("winner").getAsString());
  }

  @Test
  void theReturningConnectionGetsNoSeat() throws Exception {
    final GameRoom room = finishedGame();
    final long opponentMessages = white.messages.size();

    resume(returning, room.getGameId(), black.last("gameJoined").get("token").getAsString());

    assertSame(black.socket, room.getBlackPlayer(), "the seat stays with the connection that played");
    assertNotSame(returning.socket, room.getBlackPlayer());
    assertEquals(opponentMessages, white.messages.size(), "the opponent is told nothing");

    // Nothing the returning connection sends reaches the room: no rematch can be offered from it.
    server.onMessage(returning.socket, "{\"type\":\"rematchOffer\"}");
    assertEquals("You are not in a game.", returning.last("error").get("message").getAsString());
    assertEquals(0, white.count("rematchOffered"));
    assertEquals(GameState.ENDED, room.getSession().getState());
  }

  @Test
  void aWrongTokenLearnsNothingAboutTheFinishedGame() throws Exception {
    final GameRoom room = finishedGame();

    resume(returning, room.getGameId(), "not-the-token");

    assertEquals(0, returning.count("resumeEnded"));
    assertEquals("This game is no longer available — it may have ended or expired.",
        returning.last("resumeFailed").get("message").getAsString());
  }

  @Test
  void bothPlayersCanReturnEachSeeingTheirOwnSide() throws Exception {
    final GameRoom room = finishedGame();
    final var returningWhite = new Client();

    resume(returningWhite, room.getGameId(), white.last("gameCreated").get("token").getAsString());
    resume(returning, room.getGameId(), black.last("gameJoined").get("token").getAsString());

    assertEquals("white", returningWhite.last("resumeEnded").get("side").getAsString());
    assertEquals("black", returning.last("resumeEnded").get("side").getAsString());
    assertEquals(returningWhite.last("resumeEnded").get("result"), returning.last("resumeEnded").get("result"));
  }
}
