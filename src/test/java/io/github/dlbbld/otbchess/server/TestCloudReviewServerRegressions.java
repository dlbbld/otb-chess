// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/** What the two players are sent in the cases found by the cloud review of the review branch. */
class TestCloudReviewServerRegressions {
  private static final Gson GSON = new Gson();
  /** Long enough for the test to act, short enough to run out during a short sleep. */
  private static final long SHORT_TIME_MS = 50;
  /** A rook each and 50 moves without progress: White's 50-move claim on the board is correct. */
  private static final String FIFTY_MOVE_CLAIMABLE = "4k3/4r3/8/8/8/4K3/4R3/8 w - - 100 80";

  private final GameWebSocketServer server = new GameWebSocketServer("127.0.0.1", 0);
  /** The board as the player on move has it in front of them. */
  private Map<String, String> board;

  private static class Client {
    final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    volatile boolean open = true;
    /**
     * The socket still reports open but the send fails: it closed between the open check and the send, which is what
     * Java-WebSocket answers with this exception.
     */
    volatile boolean closesDuringSend;
    final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
        new Class<?>[] { WebSocket.class }, (proxy, method, args) -> switch (method.getName()) {
          case "send" -> {
            if (closesDuringSend) {
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

  private void invoke(String method, GameRoom room) throws Exception {
    final var m = GameWebSocketServer.class.getDeclaredMethod(method, GameRoom.class);
    m.setAccessible(true);
    m.invoke(server, room);
  }

  private void reap() throws Exception {
    final var method = GameWebSocketServer.class.getDeclaredMethod("reapAbandonedRooms");
    method.setAccessible(true);
    method.invoke(server);
  }

  private String create(Client creator, long timeMs, String fen) {
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", "white");
    create.addProperty("initialTimeMs", timeMs);
    create.addProperty("incrementMs", 0);
    if (fen != null) {
      create.addProperty("fen", fen);
    }
    server.onMessage(creator.socket, create.toString());
    return creator.last("gameCreated").get("gameId").getAsString();
  }

  private void join(Client joiner, String id) {
    server.onMessage(joiner.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
  }

  /** Starts a game. The periodic clock check is stopped, so that a test decides when an expired clock is seen. */
  private String start(Client white, Client black, long timeMs, String fen) throws Exception {
    final String id = create(white, timeMs, fen);
    join(black, id);
    rooms().get(id).stopClockTicker();
    board = MessageConverter.fromStaticPosition(rooms().get(id).getSession().getPositionBeforeTurn());
    return id;
  }

  private String start(Client white, Client black) throws Exception {
    return start(white, black, 60000, null);
  }

  private static void letTheClockRunOut() throws InterruptedException {
    Thread.sleep(SHORT_TIME_MS + 25);
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

  private void claim(Client client, String claimType, String san) {
    final JsonObject message = new JsonObject();
    message.addProperty("type", "claimDraw");
    message.addProperty("claimType", claimType);
    if (san != null) {
      message.addProperty("san", san);
    }
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

  private static void assertLostOnTimeByWhite(Client client) {
    assertEquals(1, client.count("gameEnded"), client.types().toString());
    final JsonObject ended = client.last("gameEnded");
    assertEquals("FLAG_FALL", ended.get("resultType").getAsString());
    assertEquals("black", ended.get("winner").getAsString());
    assertEquals("White loses on time. Black wins the game.", ended.get("description").getAsString());
  }

  private static void awaitBlockedOrFinished(Thread thread) throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(!thread.isAlive() || thread.getState() == Thread.State.BLOCKED, "the second reap did not reach the room");
  }

  @AfterEach
  void shutdown() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  // ----- a clock press on an expired clock, with a move owed after a rejected claim -----

  @Test
  void aClockPressAfterTheFlagFellWithAMoveOwedAsksForNoRestoration() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, SHORT_TIME_MS, null);
    claim(white, "THREEFOLD_WITH_MOVE", "e4");
    assertTrue(white.last("drawClaimResult").has("mustExecuteMove"), "the rejected claim binds White to e4");
    letTheClockRunOut();

    sendWithBoard(white, "clockPress");

    final var session = rooms().get(id).getSession();
    assertEquals("Your time has expired.", white.text("incomplete_move"));
    assertEquals(0, white.count("restoreRequired"), "no Revert on a finished game: " + white.types());
    assertEquals(0, black.count("restoreRequired"));
    assertFalse(session.isWaitingForRestoration());
    assertNull(session.getMustExecuteMove());
    for (final Client client : new Client[] { white, black }) {
      assertLostOnTimeByWhite(client);
    }
    // The result is the last word on White's board.
    assertEquals("gameEnded", white.types().get(white.types().size() - 1));
  }

  @Test
  void aWrongMoveInsteadOfTheOwedOneStillGetsItsRevertWhileTheGameRuns() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    claim(white, "THREEFOLD_WITH_MOVE", "e4");
    drag(white, "WHITE_PAWN", "d2", "d4");

    sendWithBoard(white, "clockPress");

    assertTrue(white.text("restoreRequired").contains("was not executed"), white.text("restoreRequired"));
    assertTrue(rooms().get(id).getSession().isWaitingForRestoration());
    assertEquals(0, white.count("gameEnded"));
  }

  // ----- a socket that closes while a message is being sent -----

  @ParameterizedTest
  @ValueSource(strings = { "white", "black" })
  void aSocketClosingDuringTheResultDoesNotCostTheOtherPlayerTheResult(String closing) throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    final Client gone = closing.equals("white") ? white : black;
    final Client remaining = closing.equals("white") ? black : white;
    final long clockUpdates = remaining.count("clockUpdate");

    gone.closesDuringSend = true;
    send(remaining, "resign");

    assertEquals(GameState.ENDED, rooms().get(id).getSession().getState());
    assertEquals(1, remaining.count("gameEnded"), remaining.types().toString());
    assertEquals("RESIGNATION", remaining.last("gameEnded").get("resultType").getAsString());
    assertEquals(clockUpdates + 1, remaining.count("clockUpdate"));
    assertEquals(0, remaining.count("error"), "the resigning player must not be told of an internal error");
    assertEquals(0, gone.count("gameEnded"));
  }

  @Test
  void aClaimersSocketClosingDuringTheAnswerDoesNotCostTheOpponentTheResult() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, 60000, FIFTY_MOVE_CLAIMABLE);

    // The claim is correct and ends the game; the answer to the claimer is the first message sent.
    white.closesDuringSend = true;
    claim(white, "FIFTY_MOVE_ON_BOARD", null);

    assertEquals(GameState.ENDED, rooms().get(id).getSession().getState());
    assertEquals(1, black.count("gameEnded"), black.types().toString());
    assertEquals("FIFTY_MOVE_CLAIM", black.last("gameEnded").get("resultType").getAsString());
    assertEquals("none", black.last("gameEnded").get("winner").getAsString());
  }

  @Test
  void aJoinerWhoseSocketClosesWhileBeingSeatedLeavesTheSeatForTheNextJoiner() throws Exception {
    final var creator = new Client();
    final var lost = new Client();
    final var joiner = new Client();
    final String id = create(creator, 60000, null);
    final GameRoom room = rooms().get(id);

    lost.closesDuringSend = true;
    join(lost, id);

    assertFalse(room.isFull(), "the seat must be given back");
    assertNull(room.getBlackPlayer());
    assertEquals(GameState.WAITING_FOR_PLAYERS, room.getSession().getState());
    assertFalse(seats().containsKey(lost.socket));
    assertEquals(0, creator.count("gameStarted"));

    join(joiner, id);
    room.stopClockTicker();

    assertEquals("black", joiner.last("gameJoined").get("side").getAsString());
    assertSame(joiner.socket, room.getBlackPlayer());
    assertEquals(Side.BLACK, room.sideForToken(joiner.last("gameJoined").get("token").getAsString()));
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());
    assertEquals(1, creator.count("gameStarted"));
    assertEquals(1, joiner.count("gameStarted"));
  }

  // ----- the reaper -----

  @Test
  void aRoomLeftFullWithoutAGameExpiresLikeOneNobodyJoined() throws Exception {
    final var creator = new Client();
    final var other = new Client();
    final String id = create(creator, 60000, null);
    final GameRoom room = rooms().get(id);
    // A join or rematch that failed between seating the second player and starting the game.
    room.setBlackPlayer(other.socket);
    seats().put(other.socket, id);
    assertTrue(room.isFull());
    assertEquals(GameState.WAITING_FOR_PLAYERS, room.getSession().getState());

    reap();
    assertTrue(rooms().containsKey(id), "not before the waiting-room lifetime is over");

    setLong("roomTtlMs", -1);
    reap();

    assertFalse(rooms().containsKey(id), "the room must not hold a slot of the game limit forever");
    assertTrue(seats().isEmpty());
    for (final Client client : new Client[] { creator, other }) {
      assertEquals(1, client.count("gameAborted"));
      assertEquals("Game expired (the game did not start).", client.text("gameAborted"));
    }
  }

  @Test
  void aStartedGameIsNeverReapedAsAWaitingRoom() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);

    setLong("roomTtlMs", -1);
    reap();

    assertTrue(rooms().containsKey(id));
    assertEquals(0, white.count("gameAborted"));
    assertEquals(0, black.count("gameAborted"));
  }

  @Test
  void overlappingReapsTellTheCreatorOnceAndSpareTheGameCreatedMeanwhile() throws Exception {
    final var creator = new Client();
    final String id = create(creator, 60000, null);
    final var secondReap = new AtomicReference<Thread>();
    final var newGameCreated = new CountDownLatch(1);
    final var lifetimeChecks = new AtomicInteger();
    // The reaper reads the creation time under the room lock, before it removes the room. The first
    // reap is held there until a second one has found the same room and waits for the lock; the
    // second is held there until the creator has a new game.
    final var room = new GameRoom(id, new TimeControl(60000, 0)) {
      @Override
      public long getCreatedAtMs() {
        try {
          if (lifetimeChecks.incrementAndGet() == 1) {
            final var second = new Thread(() -> {
              try {
                reap();
              } catch (final Exception e) {
                throw new IllegalStateException(e);
              }
            });
            secondReap.set(second);
            second.start();
            awaitBlockedOrFinished(second);
          } else {
            assertTrue(newGameCreated.await(5, TimeUnit.SECONDS));
          }
        } catch (final Exception e) {
          throw new IllegalStateException(e);
        }
        return 0; // long past the waiting-room lifetime
      }
    };
    room.setWhitePlayer(creator.socket);
    rooms().put(id, room);

    reap();
    assertEquals(1, creator.count("gameAborted"));
    assertFalse(rooms().containsKey(id));

    // Back in the lobby the creator starts a new game - while the second reap is still at work.
    final String newId = create(creator, 60000, null);
    newGameCreated.countDown();
    secondReap.get().join(5000);
    assertFalse(secondReap.get().isAlive());

    assertEquals(1, creator.count("gameAborted"), "the expiry must be announced once");
    assertEquals(newId, seats().get(creator.socket), "the creator must stay seated in the new game");
    final var joiner = new Client();
    join(joiner, newId);
    rooms().get(newId).stopClockTicker();
    send(creator, "resign");
    assertEquals(1, creator.count("gameEnded"), "the new game must react to its creator");
    assertEquals(1, joiner.count("gameEnded"));
  }

  // ----- the final clock update -----

  @Test
  void aFlagFallSeenByThePeriodicCheckSendsOneFinalClockUpdateBeforeTheResult() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, SHORT_TIME_MS, null);
    letTheClockRunOut();
    final long whiteUpdates = white.count("clockUpdate");
    final long blackUpdates = black.count("clockUpdate");

    invoke("tickClock", rooms().get(id));

    assertEquals(whiteUpdates + 1, white.count("clockUpdate"), "exactly one final clock update");
    assertEquals(blackUpdates + 1, black.count("clockUpdate"));
    for (final Client client : new Client[] { white, black }) {
      assertLostOnTimeByWhite(client);
      final List<String> types = client.types();
      assertEquals("clockUpdate", types.get(types.size() - 2), types.toString());
      assertEquals("gameEnded", types.get(types.size() - 1));
      final JsonObject finalClock = client.last("clockUpdate");
      assertEquals(0, finalClock.get("whiteTimeMs").getAsLong(), "the flagged clock shows zero");
      assertEquals("none", finalClock.get("running").getAsString());
    }
  }

  // ----- abandonment and a fallen flag -----

  @Test
  void claimingVictoryAfterTheOwnFlagFellLosesOnTime() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, SHORT_TIME_MS, null);
    setLong("disconnectGraceMs", 0);
    black.open = false;
    server.onClose(black.socket, 1006, "", true);
    letTheClockRunOut();

    // White's own clock has run out, the periodic check has not seen it yet, and White claims.
    send(white, "claimVictory");

    assertEquals(GameState.ENDED, rooms().get(id).getSession().getState());
    assertLostOnTimeByWhite(white);
    assertEquals(0, white.count("error"), white.types().toString());
  }

  @Test
  void theAbandonmentDeadlineAfterAFlagFallAnnouncesTheLossOnTime() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black, SHORT_TIME_MS, null);
    // Black leaves; the abandonment deadline passes after White's clock has run out unnoticed.
    setLong("abandonMs", SHORT_TIME_MS + 100);
    black.open = false;
    server.onClose(black.socket, 1006, "", true);

    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (white.count("gameEnded") == 0 && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }

    assertEquals(GameState.ENDED, rooms().get(id).getSession().getState());
    assertLostOnTimeByWhite(white);
  }

  @Test
  void claimingVictoryWithTimeLeftIsStillAWinByAbandonment() throws Exception {
    final var white = new Client();
    final var black = new Client();
    start(white, black);
    setLong("disconnectGraceMs", 0);
    black.open = false;
    server.onClose(black.socket, 1006, "", true);

    send(white, "claimVictory");

    assertEquals("ABANDONMENT", white.last("gameEnded").get("resultType").getAsString());
    assertEquals("white", white.last("gameEnded").get("winner").getAsString());
  }

  // ----- the pause after a wrong clock press -----

  @Test
  void aClaimAndAnOfferDuringTheWrongClockPressPauseAreRefusedOnBothBoards() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    final var session = rooms().get(id).getSession();
    send(black, "opponentClockPressed");
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    final long blackTime = session.getClock().getRemainingTimeMs(Side.BLACK);
    final String paused = "The game is paused. Please wait until the clock restarts.";

    claim(white, "THREEFOLD_ON_BOARD", null);
    assertEquals(paused, white.text("drawClaimResult"));
    send(white, "offerDraw");
    assertEquals(paused, white.text("repeatedDrawOffer"));
    send(black, "offerDraw");
    assertEquals(paused, black.text("repeatedDrawOffer"));

    assertEquals(0, black.count("drawOffered"), "no claim or offer reaches the opponent during the pause");
    assertEquals(0, white.count("drawOffered"));
    assertEquals(0, black.count("drawClaimOpponent"));
    assertFalse(session.getDrawOfferManager().isDrawOffered());
    assertEquals(blackTime, session.getClock().getRemainingTimeMs(Side.BLACK), "no penalty time");
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  // ----- an offer that meets the opponent's offer made with a move -----

  @Test
  void anOfferAnsweringAnOfferMadeWithAReleasedMoveAgreesTheDraw() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);
    board = ServerDrawTestSupport.bothPlayersMove(server, rooms().get(id), white.socket, black.socket);
    white.messages.clear();
    black.messages.clear();

    // White makes the move, offers the draw with it and has not pressed the clock yet.
    drag(white, "WHITE_PAWN", "e2", "e4");
    sendWithBoard(white, "offerDraw");
    assertEquals(1, black.count("drawOffered"));
    send(black, "offerDraw");

    assertEquals(GameState.ENDED, rooms().get(id).getSession().getState());
    for (final Client client : new Client[] { white, black }) {
      assertEquals(1, client.count("gameEnded"), client.types().toString());
      final JsonObject ended = client.last("gameEnded");
      assertEquals("DRAW_AGREEMENT", ended.get("resultType").getAsString());
      assertEquals("none", ended.get("winner").getAsString());
      assertEquals("black", ended.get("actor").getAsString());
    }
    assertEquals(0, black.count("wrongTimeDrawOffer"), "Black's press is an acceptance, not an invalid offer");
    assertEquals(0, white.count("drawOffered"));
  }

  @Test
  void anOfferAfterTheOpponentsReleasedMoveWithoutAPendingOfferIsStillInvalid() throws Exception {
    final var white = new Client();
    final var black = new Client();
    final String id = start(white, black);

    drag(white, "WHITE_PAWN", "e2", "e4");
    send(black, "offerDraw");

    assertEquals("The draw offer is not valid because your opponent has already made a move and is about to press"
        + " the clock.", black.text("wrongTimeDrawOffer"));
    assertEquals(0, white.count("drawOffered"));
    assertFalse(rooms().get(id).getSession().getDrawOfferManager().isDrawOffered());
    assertEquals(GameState.IN_PROGRESS, rooms().get(id).getSession().getState());
  }
}
