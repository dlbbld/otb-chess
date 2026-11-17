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

/** What the two players are sent in the cases of the third review pass. */
class TestFourthPassServerRegressions {
  private static final Gson GSON = new Gson();
  private static final String BOTH_CASTLINGS = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1";

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

    String text(String type) {
      return last(type).get("message").getAsString();
    }
  }

  @SuppressWarnings("unchecked")
  private GameRoom room(String id) throws Exception {
    final var field = GameWebSocketServer.class.getDeclaredField("gameRooms");
    field.setAccessible(true);
    return ((Map<String, GameRoom>) field.get(server)).get(id);
  }

  private String start(Client white, Client black, boolean autoResume, String fen) throws Exception {
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", "white");
    create.addProperty("initialTimeMs", 60000);
    create.addProperty("incrementMs", 0);
    create.addProperty("autoResumeAfterRestore", autoResume);
    if (fen != null) {
      create.addProperty("fen", fen);
    }
    server.onMessage(white.socket, create.toString());
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    board = MessageConverter.fromStaticPosition(room(id).getSession().getPositionBeforeTurn());
    return id;
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

  /** The Revert button: the server puts the board back and says so. */
  private void revert(Client client) {
    send(client, "restorePosition");
    board = GSON.fromJson(client.last("positionRestored").get("board"),
        new com.google.gson.reflect.TypeToken<Map<String, String>>() {
        }.getType());
  }

  private void fireDelayedResume(String id) throws Exception {
    final var method = GameWebSocketServer.class.getDeclaredMethod("resumeAfterRestorationDelay", GameRoom.class);
    method.setAccessible(true);
    method.invoke(server, room(id));
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
  void aCounterOfferEndsTheGameAsADrawForBothPlayers() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, true, null);

    send(white, "offerDraw");
    assertEquals(1, black.count("drawOffered"));
    send(black, "offerDraw");

    assertEquals(GameState.ENDED, room(id).getSession().getState());
    for (final Client client : new Client[] { white, black }) {
      assertEquals(1, client.count("gameEnded"));
      final JsonObject ended = client.last("gameEnded");
      assertEquals("DRAW_AGREEMENT", ended.get("resultType").getAsString());
      assertEquals("none", ended.get("winner").getAsString());
      assertEquals("black", ended.get("actor").getAsString());
    }
    // Black's press is an acceptance: no offer of Black's is announced and Black is not admonished.
    assertEquals(0, white.count("drawOffered"));
    assertEquals(0, black.count("wrongTimeDrawOffer"));
    assertEquals(0, black.count("drawOfferSent"));
  }

  @Test
  void anOwnOfferClosesTheOutdatedPanelAndItsRejectButtonCannotWithdrawIt() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, true, null);
    final var offers = room(id).getSession().getDrawOfferManager();

    send(black, "offerDraw");
    assertEquals(1, white.count("drawOffered"));
    drag(white, "WHITE_PAWN", "e2", "e4");
    assertEquals("The draw offer is no longer valid because you made the move.", white.text("drawOfferInvalidated"));

    sendWithBoard(white, "offerDraw");
    assertEquals(GameState.IN_PROGRESS, room(id).getSession().getState());
    assertEquals(Side.WHITE, offers.getOfferingSide());
    assertEquals(1, white.count("drawOfferSent"));
    assertEquals(1, white.count("drawOfferExpired"), "the panel with Black's old offer must close");
    assertEquals(0, black.count("drawOfferExpired"));
    assertEquals(1, black.count("drawOffered"));

    // A Reject that still reaches the server (pressed at the same moment, or sent by a modified client).
    send(white, "rejectDraw");
    assertEquals("You cannot reject your own draw offer.", white.text("drawAcceptRejected"));
    assertEquals(0, white.count("drawRejected"));
    assertEquals(0, black.count("drawRejected"), "Black must not be told that their opponent rejected");
    assertTrue(offers.isDrawOffered());
    assertEquals(Side.WHITE, offers.getOfferingSide());

    send(black, "acceptDraw");
    assertEquals("DRAW_AGREEMENT", black.last("gameEnded").get("resultType").getAsString());
  }

  @Test
  void aRejectedClaimThatReplacesAnOutdatedOfferClosesItsPanelToo() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, true, null);
    final var offers = room(id).getSession().getDrawOfferManager();

    drag(white, "WHITE_PAWN", "e2", "e4");
    sendWithBoard(white, "offerDraw");
    sendWithBoard(white, "clockPress");
    assertEquals(1, black.count("drawOffered"));
    // Black handles a White pawn: the offer can no longer be accepted, the panel stays by design.
    drag(black, "WHITE_PAWN", "a2", "a3");
    assertEquals(1, black.count("drawOfferInvalidated"));
    revert(black);
    fireDelayedResume(id);

    // FIDE 9.5: the incorrect claim is an offer by Black, which takes the place of White's outdated one.
    server.onMessage(black.socket, "{\"type\":\"claimDraw\",\"claimType\":\"THREEFOLD_ON_BOARD\"}");

    assertEquals(Side.BLACK, offers.getOfferingSide());
    assertEquals(1, black.count("drawOfferExpired"));
    assertEquals(1, white.count("drawOffered"));
    assertEquals(0, white.count("drawOfferExpired"));
  }

  @Test
  void aFirstOfferDoesNotCloseAnyPanel() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black, true, null);

    send(white, "offerDraw");

    assertEquals(0, white.count("drawOfferExpired"));
    assertEquals(0, black.count("drawOfferExpired"));
  }

  @Test
  void aStartedCastlingIsRestoredWithoutTheWordFinalAndIsThenCompleted() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, true, BOTH_CASTLINGS);

    drag(white, "WHITE_KING", "e1", "g1");
    drag(white, "WHITE_KING", "g1", "f1");
    sendWithBoard(white, "clockPress");
    assertTrue(white.text("released_piece_violation").startsWith("Castling has been started"));
    assertEquals(1, white.count("restoreRequired"));

    revert(white);
    assertEquals("WHITE_KING", board.get("g1"));
    for (final Client client : new Client[] { white, black }) {
      assertEquals("Position restored. Restarting the clock shortly. Please be ready.",
          client.text("positionRestored"));
    }
    fireDelayedResume(id);
    for (final Client client : new Client[] { white, black }) {
      assertEquals("Clock restarted. Game continues.", client.text("gameResumed"));
    }

    drag(white, "WHITE_ROOK", "h1", "f1");
    sendWithBoard(white, "clockPress");
    assertEquals(1, white.count("move_accepted"));
    assertEquals("O-O", room(id).getSession().getBoard().getPerformedMovesAsSan().get(0));
  }

  @Test
  void aCompletedMoveIsRestoredAsFinalForItsPlayerOnlyAndTheClockPressIsAccepted() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, true, null);

    drag(white, "WHITE_PAWN", "e2", "e4");
    drag(white, "BLACK_PAWN", "a7", "a6");
    assertEquals(1, white.count("restoreRequired"));

    revert(white);
    assertEquals("WHITE_PAWN", board.get("e4"));
    assertEquals("BLACK_PAWN", board.get("a7"));
    assertEquals("Position restored — your move is final. Press the clock to continue.",
        white.text("positionRestored"));
    assertEquals("Position restored.", black.text("positionRestored"));
    fireDelayedResume(id);
    assertEquals("Clock restarted. Your move is final — press the clock to continue.", white.text("gameResumed"));
    assertEquals("Clock restarted. Game continues.", black.text("gameResumed"));

    sendWithBoard(white, "clockPress");
    assertEquals(1, white.count("move_accepted"));
  }

  @Test
  void aRefreshInsideTheAutomaticResumeDelayKeepsTheBoardLockedUntilTheClockRestarts() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, true, null);
    final var session = room(id).getSession();

    drag(white, "BLACK_PAWN", "a7", "a6");
    revert(white);
    assertTrue(session.isRestorationResumePending());
    final long blackNotices = black.count("positionRestored");

    final var returning = new Client();
    final String token = white.last("gameCreated").get("token").getAsString();
    server.onMessage(returning.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");

    // resync alone enables the board of the player on move; the pause notice after it locks it again.
    final List<String> types = returning.types();
    assertTrue(types.indexOf("resync") >= 0 && types.indexOf("resync") < types.indexOf("positionRestored"),
        types.toString());
    final JsonObject notice = returning.last("positionRestored");
    assertTrue(notice.get("autoResumePending").getAsBoolean());
    assertFalse(notice.has("board"), "resync carries the board");
    assertEquals("Position restored. Restarting the clock shortly. Please be ready.",
        notice.get("message").getAsString());
    assertEquals(blackNotices, black.count("positionRestored"), "the opponent did not refresh");
    assertEquals(0, returning.count("gameResumed"));

    fireDelayedResume(id);
    assertEquals("white", returning.last("gameResumed").get("havingMove").getAsString());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());

    // Outside the delay a refresh sends no such notice.
    final var again = new Client();
    server.onMessage(again.socket, "{\"type\":\"resume\",\"gameId\":\"" + id + "\",\"token\":\"" + token + "\"}");
    assertEquals(1, again.count("resync"));
    assertEquals(0, again.count("positionRestored"));
  }
}
