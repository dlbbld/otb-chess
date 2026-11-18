// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  // ----- finding 2: a player who is away when the game starts, or when the opponent reconnects -----

  @Test
  void aCreatorWhoDroppedLongAgoIsTreatedAsHavingLeftWhenTheGameStarts() throws Exception {
    setLong("disconnectGraceMs", 60);
    setLong("abandonMs", 400);
    final var creator = new Client();
    final var joiner = new Client();
    final String id = create(creator, 600000, null);
    close(creator);
    Thread.sleep(600); // both timers of the drop are spent: this game used to run without any deadline

    final long joinedAt = System.nanoTime();
    join(joiner, id);
    rooms().get(id).stopClockTicker();
    assertEquals(GameState.IN_PROGRESS, rooms().get(id).getSession().getState());

    await("the present player is told that the opponent is away", () -> joiner.count("opponentDisconnected") == 1);
    assertEquals(340, joiner.last("opponentDisconnected").get("abandonInMs").getAsLong());
    await("the game is adjudicated", () -> joiner.count("gameEnded") == 1);
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - joinedAt);
    assertTrue(elapsedMs >= 390, "the deadline counts from the start of the game, was " + elapsedMs + " ms");
    assertEquals("ABANDONMENT", joiner.last("gameEnded").get("resultType").getAsString());
    assertEquals("black", joiner.last("gameEnded").get("winner").getAsString());
  }

  @Test
  void theDeadlineOfADropBeforeTheGameDoesNotEndTheGameAndAReturningCreatorPlays() throws Exception {
    setLong("disconnectGraceMs", 60);
    setLong("abandonMs", 1200);
    final var creator = new Client();
    final var joiner = new Client();
    final var creatorBack = new Client();
    final String id = create(creator, 600000, null);
    close(creator);
    Thread.sleep(800);
    join(joiner, id); // 400 ms before the deadline of the drop
    rooms().get(id).stopClockTicker();

    Thread.sleep(700); // past that deadline, well before the one of this game
    assertEquals(GameState.IN_PROGRESS, rooms().get(id).getSession().getState(),
        "the creator used to lose here, 400 ms into the game");
    assertEquals(0, joiner.count("gameEnded"));
    assertEquals(1, joiner.count("opponentDisconnected"), "notice counted from the start of the game");

    resume(creatorBack, id, creator.token());
    assertEquals("IN_PROGRESS", creatorBack.last("resync").get("state").getAsString());
    assertEquals(1, joiner.count("opponentReconnected"));
    Thread.sleep(700); // past the deadline of this game: the resume has defused it
    assertEquals(GameState.IN_PROGRESS, rooms().get(id).getSession().getState());
    assertEquals(0, joiner.count("gameEnded"));
  }

  @Test
  void aCreatorWhoIsConnectedGetsNoAbsenceWhenTheGameStarts() throws Exception {
    setLong("disconnectGraceMs", 0);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    Thread.sleep(80);
    assertNull(rooms().get(id).getDisconnectedAt(Side.WHITE));
    assertEquals(0, black.count("opponentDisconnected"));
  }

  @Test
  void aPlayerWhoReconnectsWhileTheOpponentIsAwayIsToldAgainWithTheTimeLeft() throws Exception {
    setLong("disconnectGraceMs", 40);
    setLong("abandonMs", 60000);
    final var white = new Client();
    final var black = new Client();
    final var whiteBack = new Client();
    final String id = start(white, black);
    close(black);
    await("first notice", () -> white.count("opponentDisconnected") == 1);

    // White's own socket blips: the reconnect clears the countdown and Claim victory on the screen.
    close(white);
    Thread.sleep(100);
    resume(whiteBack, id, white.token());

    assertEquals(List.of("resync", "opponentDisconnected"), whiteBack.types());
    final long left = whiteBack.last("opponentDisconnected").get("abandonInMs").getAsLong();
    assertTrue(left > 59000 && left < 60000, "the time left, not a fresh window: " + left);
    send(whiteBack, "claimVictory");
    assertEquals("ABANDONMENT", whiteBack.last("gameEnded").get("resultType").getAsString());
    assertEquals("white", whiteBack.last("gameEnded").get("winner").getAsString());
  }

  @Test
  void aReconnectInsideTheGraceGetsTheOneNoticeOnTheNewSocket() throws Exception {
    setLong("disconnectGraceMs", 400);
    setLong("abandonMs", 60000);
    final var white = new Client();
    final var black = new Client();
    final var whiteBack = new Client();
    final String id = start(white, black);
    close(black);
    close(white);
    resume(whiteBack, id, white.token());
    assertEquals(0, whiteBack.count("opponentDisconnected"), "inside the grace nothing is said yet");

    await("the pending notice reaches the new socket", () -> whiteBack.count("opponentDisconnected") == 1);
    Thread.sleep(100);
    assertEquals(1, whiteBack.count("opponentDisconnected"));
  }

  @Test
  void aReconnectWithTheOpponentPresentSaysNothingAboutAnAbsence() throws Exception {
    setLong("disconnectGraceMs", 0);
    final var white = new Client();
    final var black = new Client();
    final var whiteBack = new Client();
    final String id = start(white, black);
    close(white);
    resume(whiteBack, id, white.token());
    assertEquals(0, whiteBack.count("opponentDisconnected"));
  }

  // ----- finding 3: the board events of one turn are limited -----

  @Test
  void aTurnRecordsNoMoreBoardEventsThanTheLimitAndTheMoveIsStillRuledOn() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    final int limit = io.github.dlbbld.otbchess.game.GameSession.MAX_BOARD_EVENTS_PER_TURN;
    // Touching the knight binds White, so no pawn release ends the weighing of the turn.
    final JsonObject click = JsonParser.parseString("{\"type\":\"boardEvent\",\"event\":{\"eventType\":\"CLICK\","
        + "\"square\":\"g1\",\"targetSquare\":\"NONE\",\"piece\":\"WHITE_KNIGHT\",\"displacedPiece\":\"NONE\"}}")
        .getAsJsonObject();
    server.onMessage(white.socket, click.toString());
    final long startedAt = System.nanoTime();
    for (int i = 1; i < limit; i++) {
      drag(white, "WHITE_PAWN", i % 2 == 1 ? "e2" : "e4", i % 2 == 1 ? "e4" : "e2");
    }
    final long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    assertTrue(tookMs < 10000, "a turn at the limit must be recorded promptly, took " + tookMs + " ms");
    assertTrue(rooms().get(id).getSession().isTurnEventLimitReached());
    assertEquals(0, white.count("error"));
    final long forwarded = black.count("opponentBoardEvent");

    drag(white, "WHITE_PAWN", "e4", "e2"); // one more: the pawn is back on e2

    assertEquals("Too many piece movements in one move. Further movements are not recorded.", white.text("error"));
    assertEquals(forwarded, black.count("opponentBoardEvent"), "a refused event is not forwarded");

    // The touched knight is moved and the clock pressed: the ruling still stands on what was recorded.
    drag(white, "WHITE_KNIGHT", "g1", "f3");
    sendWithBoard(white, "clockPress");
    assertEquals("Move accepted.", white.text("move_accepted"));
    assertEquals(1, black.count("opponentMoved"));
    assertFalse(rooms().get(id).getSession().isTurnEventLimitReached(), "a new turn starts at zero");
  }

  // ----- finding 4: an offer that answers an offer agrees the draw in a pause as well -----

  @Test
  void anOfferAnsweringAnOfferDuringThePauseAfterAWrongClockPressAgreesTheDraw() throws Exception {
    setLong("wrongClockPauseMs", 60000);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    playOneMoveEach(white, black);
    send(black, "offerDraw");
    send(black, "opponentClockPressed");
    assertEquals(Side.NONE, rooms().get(id).getSession().getClock().getRunningFor(), "the arbiter stopped the clocks");

    send(white, "offerDraw"); // used to be answered "The game is paused...", while Accept agreed the draw

    for (final Client client : new Client[] { white, black }) {
      assertEquals("DRAW_AGREEMENT", client.last("gameEnded").get("resultType").getAsString(), client.types().toString());
      assertEquals("white", client.last("gameEnded").get("actor").getAsString());
    }
    assertEquals(0, white.count("repeatedDrawOffer"));
  }

  @Test
  void anOfferAnsweringAnOfferDuringARestorationPauseAgreesTheDraw() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    playOneMoveEach(white, black);
    send(black, "offerDraw");
    drag(white, "BLACK_PAWN", "a7", "a6"); // White moves a Black piece: the game is paused for the restoration
    assertTrue(rooms().get(id).getSession().isWaitingForRestoration());

    send(white, "offerDraw");

    assertEquals(GameState.ENDED, rooms().get(id).getSession().getState());
    assertEquals("DRAW_AGREEMENT", black.last("gameEnded").get("resultType").getAsString());
  }

  @Test
  void anOfferDuringAPauseWithNoOfferToAnswerIsStillNotConsidered() throws Exception {
    setLong("wrongClockPauseMs", 60000);
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    playOneMoveEach(white, black);
    send(black, "opponentClockPressed");

    send(white, "offerDraw");

    assertEquals("The game is paused. Please wait until the clock restarts.", white.text("repeatedDrawOffer"));
    assertFalse(rooms().get(id).getSession().getDrawOfferManager().isDrawOffered());
    assertEquals(0, black.count("drawOffered"));
  }

  // ----- finding 5: a resignation needs a running game -----

  @Test
  void aResignationBeforeAnOpponentJoinedIsRefusedAndTheRoomStaysJoinable() throws Exception {
    final var creator = new Client();
    final var joiner = new Client();
    final String id = create(creator, 600000, null);

    send(creator, "resign");

    final GameRoom room = rooms().get(id);
    assertEquals("The game has not started.", creator.text("error"));
    assertEquals(0, creator.count("gameEnded"));
    assertEquals(GameState.WAITING_FOR_PLAYERS, room.getSession().getState());

    // The joiner used to be seated in a room whose session had ended: the clock ran, every move was dropped.
    join(joiner, id);
    room.stopClockTicker();
    board = MessageConverter.fromStaticPosition(room.getSession().getPositionBeforeTurn());
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());
    assertNull(room.getSession().getResult());
    drag(creator, "WHITE_PAWN", "e2", "e4");
    sendWithBoard(creator, "clockPress");
    assertEquals(1, joiner.count("opponentMoved"), "the game is played normally");
    send(joiner, "resign");
    assertEquals("RESIGNATION", creator.last("gameEnded").get("resultType").getAsString());
    assertEquals("white", creator.last("gameEnded").get("winner").getAsString());
  }

  // ----- finding 6: a seat that is given back carries no disconnect mark -----

  @Test
  void aSeatGivenBackWhileItsCloseCallbackRanIsCleanForTheNextJoiner() throws Exception {
    final var creator = new Client();
    final var lost = new Client();
    final var joiner = new Client();
    final String id = create(creator, 600000, null);
    final GameRoom room = rooms().get(id);
    // The socket closes while it is being seated, and its close callback runs before the seat is given back.
    lost.closesDuringSend = true;
    lost.duringSend = () -> server.onClose(lost.socket, 1006, "", true);

    join(lost, id);

    assertNull(room.getBlackPlayer());
    assertNull(room.getDisconnectedAt(Side.BLACK), "the mark must go with the seat");

    join(joiner, id);
    room.stopClockTicker();
    assertTrue(room.isConnected(Side.BLACK));
    send(joiner, "resign");
    send(creator, "rematchOffer");
    assertEquals(1, creator.count("rematchOfferSent"), creator.types().toString());
    assertEquals(0, creator.count("rematchUnavailable"));
    send(joiner, "rematchOffer");
    assertEquals(1, creator.count("rematchStarted"));
  }

  // ----- finding 9: a connection has one game -----

  @Test
  void aConnectionThatCreatesAnotherGameGivesUpItsWaitingRoom() throws Exception {
    setInt("maxConcurrentGames", 2);
    final var one = new Client();
    final var other = new Client();
    final var joiner = new Client();
    final String first = create(one, 600000, null);
    final String second = create(one, 600000, null);
    final String third = create(one, 600000, null);

    assertEquals(3, one.count("gameCreated"));
    assertEquals(Set.of(third), rooms().keySet(), "one connection used to hold every room it ever created");
    assertEquals(third, seats().get(one.socket));

    // The room limit is not used up: another player creates a game.
    sendCreate(other, 600000, null);
    assertEquals(1, other.count("gameCreated"), other.types().toString());
    assertEquals(0, other.count("error"));

    join(joiner, first);
    assertEquals("not_found", joiner.last("joinFailed").get("reason").getAsString());
    join(joiner, third);
    rooms().get(third).stopClockTicker();
    assertEquals(1, joiner.count("gameStarted"));
    assertNotEquals(first, second);
  }

  @Test
  void aPlayerInARunningGameCannotCreateAnotherOne() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);

    sendCreate(white, 600000, null);

    assertEquals("You are already playing a game.", white.text("error"));
    assertEquals(1, white.count("gameCreated"));
    assertEquals(Set.of(id), rooms().keySet());
    assertEquals(id, seats().get(white.socket));
    assertEquals(GameState.IN_PROGRESS, rooms().get(id).getSession().getState());

    // After the game the same connection may create a new one.
    send(white, "resign");
    sendCreate(white, 600000, null);
    assertEquals(2, white.count("gameCreated"));
  }

  // ----- findings 7 and 8: no draw before both players have moved; the offered move is in the record -----

  @Test
  void acceptingOnTheFirstMoveIsRefusedAndTheOfferStaysOnTheTable() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    send(white, "offerDraw");
    assertEquals(1, black.count("drawOffered"));
    final String tooEarly = "A draw can be agreed only after both players have made at least one move (FIDE 5.2.3).";

    send(black, "acceptDraw");
    assertEquals(tooEarly, black.text("drawAcceptRejected"));
    send(black, "offerDraw");
    assertEquals(tooEarly, black.text("repeatedDrawOffer"));

    final var session = rooms().get(id).getSession();
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertEquals(Side.WHITE, session.getDrawOfferManager().getOfferingSide());
    assertEquals(0, white.count("gameEnded"));
    assertEquals(0, white.count("drawOffered"), "Black's answer is no offer of its own");
    assertEquals(0, white.count("drawRejected"));
    assertEquals(0, black.count("drawOfferExpired"), "the panel with White's offer stays");
  }

  @Test
  void theMoveMadeWithTheAcceptedOfferAndTheResultAreInThePgn() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    playOneMoveEach(white, black);
    drag(white, "WHITE_KNIGHT", "g1", "f3");
    sendWithBoard(white, "offerDraw");

    send(black, "acceptDraw");
    send(black, "requestPgn");

    assertEquals("DRAW_AGREEMENT", white.last("gameEnded").get("resultType").getAsString());
    assertEquals("1. e4 e5 2. Nf3 1/2-1/2", black.last("pgn").get("pgn").getAsString().strip());
    assertEquals(List.of("e4", "e5", "Nf3"), rooms().get(id).getSession().getBoard().getPerformedMovesAsSan());
  }
}
