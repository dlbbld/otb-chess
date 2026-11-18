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
    long count(String type) {
      return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).count();
    }
    int indexOf(String type) {
      for (int i = 0; i < messages.size(); i++) {
        if (messages.get(i).get("type").getAsString().equals(type)) {
          return i;
        }
      }
      return -1;
    }
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

  private void delayedResume(GameRoom room) throws Exception {
    final var method = GameWebSocketServer.class.getDeclaredMethod("resumeAfterRestorationDelay", GameRoom.class);
    method.setAccessible(true);
    method.invoke(server, room);
  }

  private Client reconnect(Client player, String id, String joinedMessage) {
    final var returning = new Client();
    final String token = player.last(joinedMessage).get("token").getAsString();
    server.onMessage(returning.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
    return returning;
  }

  @Test
  void resumeReplaysTheOfferOnlyToItsRecipientWithoutRegisteringAnotherOffer() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    ServerDrawTestSupport.bothPlayersMove(server, rooms().get(id), white.socket, black.socket);
    server.onMessage(white.socket, "{\"type\":\"offerDraw\"}");
    final var expected = black.last("drawOffered");
    final var returningWhite = reconnect(white, id, "gameCreated");
    assertEquals(0, returningWhite.count("drawOffered"));
    final var returningBlack = reconnect(black, id, "gameJoined");
    assertEquals(expected, returningBlack.last("drawOffered"));
    assertTrue(returningBlack.indexOf("resync") < returningBlack.indexOf("drawOffered"));
    server.onMessage(returningBlack.socket, "{\"type\":\"acceptDraw\"}");
    for (final Client client : new Client[] {returningWhite, returningBlack}) {
      assertEquals("DRAW_AGREEMENT", client.last("gameEnded").get("resultType").getAsString());
    }
  }

  @Test
  void resumePreservesClaimOfferTextAndDoesNotReplayAClearedOffer() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    server.onMessage(white.socket, "{\"type\":\"claimDraw\",\"claimType\":\"THREEFOLD_ON_BOARD\"}");
    final var expected = black.last("drawOffered");
    assertTrue(expected.get("message").getAsString().contains("still counts as a draw offer"));
    final var returning = reconnect(black, id, "gameJoined");
    assertEquals(expected, returning.last("drawOffered"));
    server.onMessage(returning.socket, "{\"type\":\"rejectDraw\"}");
    final var afterRejection = reconnect(black, id, "gameJoined");
    assertEquals(0, afterRejection.count("drawOffered"));
    server.onMessage(white.socket, "{\"type\":\"offerDraw\"}");
    final var nextOffer = reconnect(black, id, "gameJoined");
    assertEquals("Your opponent offers a draw.", nextOffer.last("drawOffered").get("message").getAsString());
  }

  @Test
  void resumeDoesNotRevalidateAnOfferInvalidatedByTouch() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    server.onMessage(white.socket, "{\"type\":\"offerDraw\"}");
    final var session = rooms().get(id).getSession();
    session.getDrawOfferManager().recordOpponentTouchedPiece();
    final var returning = reconnect(black, id, "gameJoined");
    assertEquals(1, returning.count("drawOffered"));
    server.onMessage(returning.socket, "{\"type\":\"acceptDraw\"}");
    assertTrue(returning.last("drawAcceptRejected").get("message").getAsString().contains("touched a piece"));
    assertEquals(0, returning.count("gameEnded"));
  }

  @Test
  void clockPressDuringTheAutoResumeDelayAnnouncesTheResumeExactlyOnce() throws Exception {
    final var white = new Client();
    final var black = new Client();
    server.onMessage(white.socket, "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":60000,\"incrementMs\":0}");
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    final var room = rooms().get(id);
    final var original = room.getSession().getPositionBeforeTurn();
    final var gson = new Gson();

    // First offence, so the restoration costs nothing: White moves Black's e-pawn, then reverts.
    final var moved = BitboardPositions.from(original).createChangedPosition(Square.E7, Piece.NONE)
        .createChangedPosition(Square.E6, Piece.BLACK_PAWN).build();
    final var event = JsonParser.parseString("""
        {"type":"boardEvent","event":{"eventType":"DRAG_MOVE","square":"e7",
        "targetSquare":"e6","piece":"BLACK_PAWN","displacedPiece":"NONE"}}
        """).getAsJsonObject();
    event.add("boardState", gson.toJsonTree(MessageConverter.fromStaticPosition(moved)));
    server.onMessage(white.socket, event.toString());
    server.onMessage(white.socket, "{\"type\":\"restorePosition\"}");
    assertTrue(room.getSession().isRestorationResumePending());

    // White presses the clock inside the delay without having moved: the clock runs again and the
    // turn goes on, so both boards must hear about the resume - before the arbiter's answer.
    final var press = new JsonObject();
    press.addProperty("type", "clockPress");
    press.add("boardState", gson.toJsonTree(MessageConverter.fromStaticPosition(original)));
    server.onMessage(white.socket, press.toString());
    assertEquals(Side.WHITE, room.getSession().getClock().getRunningFor());
    assertEquals(1, white.count("gameResumed"));
    assertEquals(1, black.count("gameResumed"));
    assertEquals("white", white.last("gameResumed").get("havingMove").getAsString());
    assertTrue(white.indexOf("gameResumed") < white.indexOf("illegal_move"));

    // The delayed resume finds the game resumed and stays silent.
    delayedResume(room);
    assertEquals(1, white.count("gameResumed"));
    assertEquals(1, black.count("gameResumed"));
  }

  @Test
  void delayedResumeStaysSilentAfterTheGameEnded() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    final var room = rooms().get(id);
    final var session = room.getSession();
    server.onMessage(white.socket, "{\"type\":\"resign\"}");
    delayedResume(room);
    assertEquals(0, white.count("gameResumed"));
    assertEquals(0, black.count("gameResumed"));
    assertEquals(Side.NONE, session.getClock().getRunningFor());
  }

  @Test
  void theResultIsAnnouncedOnceAndLateActionsAreDropped() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    ServerDrawTestSupport.bothPlayersMove(server, rooms().get(id), white.socket, black.socket);
    server.onMessage(white.socket, "{\"type\":\"offerDraw\"}");
    server.onMessage(black.socket, "{\"type\":\"acceptDraw\"}");
    assertEquals(1, white.count("gameEnded"));
    assertEquals(1, black.count("gameEnded"));

    server.onMessage(black.socket, "{\"type\":\"rematchOffer\"}");
    assertEquals("rematchOffered", white.messages.get(white.messages.size() - 1).get("type").getAsString());
    final int whiteMessages = white.messages.size();
    final int blackMessages = black.messages.size();

    // Actions that were in flight when the game ended: no reply to anyone, and the result stands.
    final var result = rooms().get(id).getSession().getResult();
    for (final String late : new String[] {"resign", "acceptDraw", "rejectDraw", "offerDraw", "cancelDrawClaim",
        "restorePosition", "readyToContinue", "opponentClockPressed", "claimVictory"}) {
      server.onMessage(white.socket, "{\"type\":\"" + late + "\"}");
      server.onMessage(black.socket, "{\"type\":\"" + late + "\"}");
    }
    server.onMessage(white.socket, "{\"type\":\"clockPress\",\"boardState\":{}}");
    assertEquals(whiteMessages, white.messages.size());
    assertEquals(blackMessages, black.messages.size());
    assertEquals(result, rooms().get(id).getSession().getResult());

    // What stays meaningful after the result still works, and a rematch is announced afresh.
    server.onMessage(white.socket, "{\"type\":\"requestPgn\"}");
    assertNotNull(white.last("pgn"));
    server.onMessage(white.socket, "{\"type\":\"rematchOffer\"}");
    assertNotNull(white.last("rematchStarted"));
    server.onMessage(white.socket, "{\"type\":\"resign\"}");
    assertEquals(2, white.count("gameEnded"));
    assertEquals(2, black.count("gameEnded"));
  }

  @Test
  void wrongClockPressOnAnExpiredClockAnnouncesTheFlagFall() throws Exception {
    final var white = new Client();
    final var black = new Client();
    server.onMessage(white.socket, "{\"type\":\"createGame\",\"side\":\"white\",\"initialTimeMs\":40,\"incrementMs\":0}");
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    Thread.sleep(70);
    // Black presses White's lever after White's time ran out but before the periodic check noticed.
    server.onMessage(black.socket, "{\"type\":\"opponentClockPressed\"}");
    for (final Client client : new Client[] {white, black}) {
      assertEquals(1, client.count("gameEnded"));
      assertEquals("FLAG_FALL", client.last("gameEnded").get("resultType").getAsString());
      assertEquals("black", client.last("gameEnded").get("winner").getAsString());
    }
  }
}
