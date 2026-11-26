// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.placement.PlacementBoard.PlacedPiece;
import io.github.dlbbld.otbchess.placement.PlacementMeasure;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/**
 * Free placement of the pieces (E-005), first playable case: the capture that starts with the opponent's piece.
 * After 1.e4 d5 White lifts the pawn on d5 to play exd5 and lets go of it on the line between d5 and e5. The
 * arbiter says at once that it is still on the board; nothing is counted until the clock is pressed with the pawn
 * still lying there. From there the capture can be finished in every order a hand can do it.
 */
class TestFreePlacementServer {

  private static final String NOTICE = "placementNotice";
  private static final String CALL = "unclear_placement";
  private static final String STILL_ON_THE_BOARD = "Black's pawn is still on the board. To capture it, take it off"
      + " the board. Otherwise put it back on d5.";
  private static final String CALL_FOR_THE_PAWN = "It is not clear on which square your opponent's pawn is. Put it"
      + " back on d5 or take it off the board. Place your pieces more carefully.";

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
          .orElseThrow(() -> new AssertionError("no " + type + " in " + types()));
    }

    String lastMessage(String type) {
      return last(type).get("message").getAsString();
    }

    List<String> types() {
      return messages.stream().map(m -> m.get("type").getAsString()).toList();
    }

    String token() {
      return messages.stream().filter(m -> m.has("token")).reduce((a, b) -> b).orElseThrow().get("token")
          .getAsString();
    }
  }

  private final Client white = new Client();
  private final Client black = new Client();
  private GameRoom room;

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

  private JsonObject createMessage() {
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", "white");
    create.addProperty("initialTimeMs", 600000);
    create.addProperty("incrementMs", 0);
    create.addProperty("maxIllegalMoves", 5);
    create.addProperty("autoResumeAfterRestore", true);
    create.addProperty("freePlacement", true);
    return create;
  }

  private void start(JsonObject create) throws Exception {
    server.onMessage(white.socket, create.toString());
    final String id = white.last("gameCreated").get("gameId").getAsString();
    server.onMessage(black.socket, "{\"type\":\"joinGame\",\"gameId\":\"" + id + "\"}");
    room = room(id);
    room.stopClockTicker();
  }

  /** A game with free placement after 1.e4 d5: White to move, exd5 possible. */
  private void startAfterE4D5() throws Exception {
    start(createMessage());
    releaseOn(white, "e2", "e4");
    press(white);
    releaseOn(black, "d7", "d5");
    press(black);
    assertEquals(1, white.count("move_accepted"));
    assertEquals(1, black.count("move_accepted"));
    assertEquals(Side.WHITE, room.getSession().getHavingMove());
  }

  private void send(Client client, String type) {
    server.onMessage(client.socket, "{\"type\":\"" + type + "\"}");
  }

  private void press(Client client) {
    send(client, "clockPress");
  }

  private static int x(String square) {
    return PlacementMeasure.centreOfFile(Square.parse(square));
  }

  private static int y(String square) {
    return PlacementMeasure.centreOfRank(Square.parse(square));
  }

  private int idOn(String square) {
    return room.getPlacements().pieces().stream().filter(piece -> piece.square() == Square.parse(square))
        .findFirst().orElseThrow(() -> new AssertionError("no piece counts on " + square)).id();
  }

  private PlacedPiece piece(int id) {
    return room.getPlacements().pieces().stream().filter(piece -> piece.id() == id).findFirst().orElseThrow();
  }

  private void release(Client client, int id, int x, int y) {
    final JsonObject event = new JsonObject();
    event.addProperty("eventType", "RELEASE");
    event.addProperty("id", id);
    event.addProperty("x", x);
    event.addProperty("y", y);
    final JsonObject message = new JsonObject();
    message.addProperty("type", "boardEvent");
    message.add("event", event);
    server.onMessage(client.socket, message.toString());
  }

  private void releaseOn(Client client, String from, String to) {
    release(client, idOn(from), x(to), y(to));
  }

  /** White lets go of the pawn from d5 on the line between d5 and e5. */
  private int leaveVictimOnTheLine() {
    final int victim = idOn("d5");
    release(white, victim, x("e5") - 512, y("d5"));
    return victim;
  }

  private void takeOff(int id) {
    release(white, id, -400, y("d5"));
  }

  private int unclearCount(Side side) {
    return room.getSession().getUnclearPlacementCount(side);
  }

  private Side clockRunsFor() {
    return room.getSession().getClock().getRunningFor();
  }

  private void assertCaptureAccepted() {
    assertEquals(2, white.count("move_accepted"), white.types().toString());
    assertEquals(Piece.WHITE_PAWN, room.getSession().getBoard().getBitboardPosition().get(Square.D5));
    assertEquals(Piece.NONE, room.getSession().getBoard().getBitboardPosition().get(Square.E4));
    assertEquals(Side.BLACK, room.getSession().getHavingMove());
    assertTrue(room.getSession().exportPgn().contains("exd5"), room.getSession().exportPgn());
    assertEquals(31, room.getPlacements().pieces().size(), "one black pawn has left the board");
    assertEquals(31, black.last("opponentMoved").getAsJsonArray("placements").size());
  }

  @AfterEach
  void shutdown() throws Exception {
    for (final String name : new String[] { "clockExecutor", "maintenance" }) {
      final var field = GameWebSocketServer.class.getDeclaredField(name);
      field.setAccessible(true);
      ((ExecutorService) field.get(server)).shutdownNow();
    }
  }

  // ===== The option =====

  @Test
  void aGameCreatedWithoutTheOptionIsAGameOfToday() throws Exception {
    final JsonObject create = createMessage();
    create.remove("freePlacement");
    start(create);
    assertFalse(room.isFreePlacement());
    assertNull(room.getPlacements());
    assertFalse(white.last("gameCreated").has("placements"));
    assertFalse(black.last("gameJoined").has("placements"));
  }

  @Test
  void aGameWithFreePlacementIsSentWhereEveryPieceLies() throws Exception {
    start(createMessage());
    assertTrue(room.isFreePlacement());
    for (final JsonObject message : List.of(white.last("gameCreated"), black.last("gameJoined"))) {
      assertEquals(32, message.getAsJsonArray("placements").size());
      assertEquals(226, message.get("placementRadius").getAsInt());
    }
  }

  @Test
  void theLimitOfUnclearPlacementsMustBeInRange() throws Exception {
    for (final int invalid : new int[] { 0, 11, -2 }) {
      final Client creator = new Client();
      final JsonObject create = createMessage();
      create.addProperty("maxUnclearPlacements", invalid);
      server.onMessage(creator.socket, create.toString());
      assertEquals(0, creator.count("gameCreated"), "limit " + invalid);
      assertEquals(1, creator.count("error"));
    }
  }

  // ===== A piece stays where it is let go =====

  @Test
  void aMoveLetGoOffTheCentreIsAcceptedAndThePieceStaysWhereItLies() throws Exception {
    start(createMessage());
    final int pawn = idOn("e2");
    release(white, pawn, x("e4") + 180, y("e4") - 150);
    final JsonObject mirrored = black.last("opponentBoardEvent").getAsJsonObject("event");
    assertEquals("RELEASE", mirrored.get("eventType").getAsString());
    assertEquals(x("e4") + 180, mirrored.get("x").getAsInt());
    press(white);
    assertEquals(1, white.count("move_accepted"));
    assertEquals(x("e4") + 180, piece(pawn).x());
    assertEquals(y("e4") - 150, piece(pawn).y());
    boolean found = false;
    for (final JsonElement element : black.last("opponentMoved").getAsJsonArray("placements")) {
      final JsonObject placed = element.getAsJsonObject();
      if (placed.get("id").getAsInt() == pawn) {
        assertEquals(x("e4") + 180, placed.get("x").getAsInt());
        assertEquals(y("e4") - 150, placed.get("y").getAsInt());
        found = true;
      }
    }
    assertTrue(found, "the opponent is told where the pawn lies");
  }

  // ===== The capture that starts with the opponent's piece =====

  @Test
  void theVictimLetGoOnTheLineGetsAnInstructionAtOnceAndCostsNothing() throws Exception {
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();

    assertEquals(STILL_ON_THE_BOARD, white.lastMessage(NOTICE));
    assertEquals("Your opponent lifted your pawn on d5 and set it down between squares. It is still on the board.",
        black.lastMessage(NOTICE));
    assertEquals(Side.WHITE, clockRunsFor(), "White's clock keeps running");
    assertEquals(0, unclearCount(Side.WHITE));
    assertEquals(0, white.count(CALL));
    assertEquals(0, white.count("restoreRequired"));
    assertEquals(32, room.getPlacements().pieces().size(), "both pawns are still in play");
    assertEquals(Piece.BLACK_PAWN, room.getPlacements().countedPosition().get(Square.D5));
    assertEquals(x("e5") - 512, piece(victim).x(), "the pawn stays where it was let go");
    assertEquals(victim, black.last("opponentBoardEvent").getAsJsonObject("event").get("id").getAsInt());
  }

  @Test
  void takenOffTheBoardAfterwardsTheCaptureIsFinishedAndAccepted() throws Exception {
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    takeOff(victim);
    assertEquals("", white.lastMessage(NOTICE), "the instruction has ended");
    assertEquals("", black.lastMessage(NOTICE));
    releaseOn(white, "e4", "d5");
    press(white);
    assertCaptureAccepted();
    assertEquals(0, unclearCount(Side.WHITE));
  }

  @Test
  void putBackTheInstructionEndsAndTheDirectCaptureIsAccepted() throws Exception {
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    release(white, victim, x("d5") + 30, y("d5") - 20);
    assertEquals("", white.lastMessage(NOTICE));
    assertTrue(room.getPlacements().halfRemovedPiece(Side.WHITE).isEmpty());

    final int capturer = idOn("e4");
    release(white, capturer, x("d5"), y("d5"));
    assertEquals(victim, black.last("opponentBoardEvent").getAsJsonObject("event").get("capturedId").getAsInt());
    press(white);
    assertCaptureAccepted();
  }

  @Test
  void theTouchOfTheVictimStandsAfterItIsPutBack() throws Exception {
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    releaseOn(white, "d5", "d5"); // unclear -> clear on its square; idOn finds it by the square it counts on
    assertTrue(room.getPlacements().showsClearly(room.getSession().getPositionBeforeTurn()));
    assertTrue(room.getPlacements().isOnBoard(victim));

    // The pawn was touched and can be captured: another move breaks touch-move.
    releaseOn(white, "g1", "f3");
    press(white);
    assertEquals(1, white.count("touch_move_violation"), white.types().toString());
    assertEquals(1, white.count("move_accepted"));
  }

  @Test
  void aTouchedPieceThatCannotBeCapturedBindsToNothing() throws Exception {
    startAfterE4D5();
    final int pawn = idOn("a7");
    release(white, pawn, x("b7") - 512, y("a7"));
    assertTrue(white.lastMessage(NOTICE).endsWith("put it back on a7."), white.lastMessage(NOTICE));
    release(white, pawn, x("a7"), y("a7"));
    releaseOn(white, "g1", "f3");
    press(white);
    assertEquals(2, white.count("move_accepted"), white.types().toString());
  }

  @Test
  void pressingTheClockWithTheVictimOnTheLineIsCountedOnceAndPausesTheGame() throws Exception {
    setLong("wrongClockPauseMs", 150);
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    final long heard = white.count("clockPressHeard");
    press(white);

    assertEquals(CALL_FOR_THE_PAWN + " The game is paused and will continue shortly.", white.lastMessage(CALL));
    assertEquals("Your opponent pressed the clock while a piece was not clearly on a square."
        + " The game is paused; the clock will restart shortly.", black.lastMessage("opponentInfo"));
    assertEquals(heard + 1, white.count("clockPressHeard"), "the lever went down");
    assertEquals(1, unclearCount(Side.WHITE));
    assertEquals(Side.NONE, clockRunsFor(), "the arbiter has stopped the clock");
    assertTrue(room.getSession().isUnclearPlacementPause());
    assertEquals(0, white.count("restoreRequired"), "nothing is to be restored");
    assertEquals(1, white.count("move_accepted"));

    // A press in the pause is not ruled on and not counted.
    press(white);
    assertEquals(1, white.count(CALL));
    assertEquals(1, unclearCount(Side.WHITE));

    // The board cannot be used until the clock restarts: the pawn stays where it lies.
    takeOff(victim);
    assertTrue(room.getPlacements().isOnBoard(victim));
    assertEquals(32, white.last("placementsSync").getAsJsonArray("placements").size());

    await("the arbiter restarts the clock", () -> white.count("gameResumed") == 1);
    assertEquals(CALL_FOR_THE_PAWN + " The clock has restarted.", white.lastMessage("gameResumed"));
    assertEquals(Side.WHITE, clockRunsFor(), "White corrects on their own running clock");
    assertFalse(room.getSession().isUnclearPlacementPause());

    takeOff(victim);
    releaseOn(white, "e4", "d5");
    press(white);
    assertCaptureAccepted();
    assertEquals(1, unclearCount(Side.WHITE));
  }

  @Test
  void theVictimCarriedOffItsSquareIsAMovedOpponentPieceAndNothingElse() throws Exception {
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    // Wholly clear of d5: on the line between e5 and f5.
    release(white, victim, x("f5") - 512, y("d5"));

    assertTrue(white.lastMessage("restoreRequired").contains("You moved an opponent's piece."),
        white.types().toString());
    assertTrue(black.lastMessage("opponentInfo").startsWith("Your opponent moved one of your pieces."));
    assertTrue(room.getSession().isWaitingForRestoration());
    assertEquals(0, unclearCount(Side.WHITE));
    assertEquals(0, white.count(CALL));

    // Revert: the arbiter sets the position up, centred.
    send(white, "restorePosition");
    final JsonObject restored = white.last("positionRestored");
    assertEquals(32, restored.getAsJsonArray("placements").size());
    assertTrue(room.getPlacements().showsClearly(room.getSession().getPositionBeforeTurn()));
    final PlacedPiece pawn = piece(idOn("d5"));
    assertEquals(x("d5"), pawn.x());
    assertEquals(y("d5"), pawn.y());
  }

  @Test
  void theCapturerSetOnTheSquareFirstKeepsBothPiecesUntilTheVictimIsTakenOff() throws Exception {
    setLong("wrongClockPauseMs", 150);
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    final long notices = white.count(NOTICE);
    releaseOn(white, "e4", "d5");

    assertEquals(0, black.last("opponentBoardEvent").getAsJsonObject("event").get("capturedId").getAsInt());
    assertEquals(32, room.getPlacements().pieces().size(), "both pieces lie on the board");
    assertEquals(notices, white.count(NOTICE), "the instruction stands");
    assertEquals(STILL_ON_THE_BOARD, white.lastMessage(NOTICE));
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());

    // Pressed now, the pawn that still lies there is called and counted - the move is not ruled on.
    press(white);
    assertEquals(1, white.count(CALL));
    assertEquals(1, unclearCount(Side.WHITE));
    assertEquals(1, white.count("move_accepted"));
    await("the arbiter restarts the clock", () -> white.count("gameResumed") == 1);

    takeOff(victim);
    assertEquals("", white.lastMessage(NOTICE));
    press(white);
    assertCaptureAccepted();
  }

  @Test
  void aPageThatComesBackSeesThePawnOnTheLineAndTheInstruction() throws Exception {
    startAfterE4D5();
    final int victim = leaveVictimOnTheLine();
    for (final Client returning : List.of(black, white)) {
      final Side side = returning == white ? Side.WHITE : Side.BLACK;
      final Client page = new Client();
      final JsonObject resume = new JsonObject();
      resume.addProperty("type", "resume");
      resume.addProperty("gameId", room.getGameId());
      resume.addProperty("token", room.getToken(side));
      server.onMessage(page.socket, resume.toString());
      boolean onTheLine = false;
      for (final JsonElement element : page.last("resync").getAsJsonArray("placements")) {
        final JsonObject placed = element.getAsJsonObject();
        onTheLine |= placed.get("id").getAsInt() == victim && placed.get("x").getAsInt() == x("e5") - 512;
      }
      assertTrue(onTheLine, side + " sees the pawn where it lies");
      assertTrue(page.lastMessage(NOTICE).contains("still on the board"), side + " is told again");
    }
  }

  // ===== The ladder =====

  @Test
  void theThirdUnclearPlacementAtAClockPressLosesTheGame() throws Exception {
    setLong("wrongClockPauseMs", 100);
    startAfterE4D5();
    leaveVictimOnTheLine();
    press(white);
    assertFalse(white.lastMessage(CALL).contains("Warning"));
    await("first restart", () -> white.count("gameResumed") == 1);
    press(white);
    assertTrue(white.lastMessage(CALL).contains("Warning: the next time you press the clock with a piece placed"
        + " unclearly, you lose the game."), white.lastMessage(CALL));
    assertTrue(black.lastMessage("opponentInfo").contains("has been warned"));
    await("second restart", () -> white.count("gameResumed") == 2);
    press(white);

    assertEquals(GameState.ENDED, room.getSession().getState());
    assertEquals(GameResultType.UNCLEAR_PLACEMENT_GAME_LOST, room.getSession().getResult().type());
    assertEquals(Side.BLACK, room.getSession().getResult().winner());
    assertEquals(1, white.count("unclear_placement_game_lost"));
    final JsonObject ended = black.last("gameEnded");
    assertEquals("UNCLEAR_PLACEMENT_GAME_LOST", ended.get("resultType").getAsString());
    assertEquals("white", ended.get("actor").getAsString());
  }

  @Test
  void withALimitOfOneTheFirstUnclearPlacementLosesAndWithoutALimitNoneDoes() throws Exception {
    final JsonObject create = createMessage();
    create.addProperty("maxUnclearPlacements", 1);
    start(create);
    release(white, idOn("g1"), x("g3") - 512, y("g3"));
    press(white);
    assertEquals(GameResultType.UNCLEAR_PLACEMENT_GAME_LOST, room.getSession().getResult().type());

    final GameSession unlimited = new GameSession(new io.github.dlbbld.otbchess.game.model.TimeControl(600000, 0));
    unlimited.setMaxUnclearPlacements(GameSession.UNLIMITED_UNCLEAR_PLACEMENTS);
    unlimited.startGame();
    final List<PlacedPiece> knight = List.of(
        new PlacedPiece(1, Piece.WHITE_KNIGHT, x("g3") - 512, y("g3"), Square.G1, Square.G1, false, List.of(Square.F3, Square.G3)));
    for (int press = 1; press <= 12; press++) {
      assertEquals("It is not clear on which square your knight is. Place your pieces more carefully.",
          unlimited.pressClockButton(Side.WHITE, unlimited.getPositionBeforeTurn(), knight).renderedPlayerMessage());
      assertTrue(unlimited.resumeAfterPressWithoutMove(unlimited.getPressWithoutMovePauseId()));
    }
    assertEquals(GameState.IN_PROGRESS, unlimited.getState());
    assertEquals(12, unlimited.getUnclearPlacementCount(Side.WHITE));
  }

  @Test
  void withoutAutomaticResumeBothPlayersAreAskedWhetherTheyAreReady() throws Exception {
    final JsonObject create = createMessage();
    create.addProperty("autoResumeAfterRestore", false);
    start(create);
    release(white, idOn("g1"), x("g3") - 512, y("g3"));
    press(white);
    assertEquals(1, white.count(CALL));
    assertTrue(white.lastMessage("waitingForReady").endsWith("Are you ready to continue?"));
    assertEquals("Are you ready to continue?", black.lastMessage("waitingForReady"));
    assertTrue(room.getSession().isUnclearPlacementPause());
    send(white, "readyToContinue");
    send(black, "readyToContinue");
    assertEquals(Side.WHITE, clockRunsFor());
    assertFalse(room.getSession().isUnclearPlacementPause());
  }

  // ===== Other moments that read the board =====

  @Test
  void noGameEndsWhileAPieceLiesUnclear() throws Exception {
    final JsonObject create = createMessage();
    create.addProperty("fen", "6k1/5ppp/8/8/8/8/8/R6K w - - 0 1"); // Ra8 is mate
    start(create);
    final int rook = idOn("a1");
    final int pawn = idOn("h7");
    // The mating move itself, let go across a8 and b8: no move yet.
    release(white, rook, x("b8") - 512, y("a8"));
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState());
    // A black pawn that cannot be captured, lifted and left across g7 and h7; then the rook clearly on a8.
    release(white, pawn, x("h7") - 512, y("h7"));
    release(white, rook, x("a8"), y("a8"));
    assertEquals(GameState.IN_PROGRESS, room.getSession().getState(), "mate is on the board, a piece lies unclear");
    release(white, pawn, x("h7"), y("h7"));
    assertEquals(GameState.ENDED, room.getSession().getState());
    assertEquals(GameResultType.CHECKMATE, room.getSession().getResult().type());
    assertEquals(0, unclearCount(Side.WHITE));
  }

  @Test
  void aDrawOfferWithAnUnclearPieceIsRuledOnLikeAClockPress() throws Exception {
    startAfterE4D5();
    release(white, idOn("g1"), x("g3") - 512, y("g3"));
    final long heard = white.count("clockPressHeard");
    send(white, "offerDraw");
    assertEquals(1, white.count(CALL));
    assertEquals(1, unclearCount(Side.WHITE));
    assertFalse(room.getSession().getDrawOfferManager().isDrawOffered(), "the offer is not made");
    assertEquals(0, black.count("drawOffered"));
    assertEquals(heard, white.count("clockPressHeard"), "no lever went down for the offer");
  }

  // ===== The binding of an own piece let go unclearly =====

  /** King e2, rook a1: the rook reaches every square of the first rank. */
  private static final String ROOK = "4k3/8/8/8/8/8/4K3/R7 w - - 0 1";

  private void startWithRook() throws Exception {
    final JsonObject create = createMessage();
    create.addProperty("fen", ROOK);
    start(create);
    setLong("wrongClockPauseMs", 30);
  }

  private static String marks(Client client) {
    final StringBuilder marks = new StringBuilder();
    client.last("boardMarks").getAsJsonArray("marks").forEach(mark -> marks
        .append(mark.getAsJsonObject().get("color").getAsString()).append(' ')
        .append(mark.getAsJsonObject().get("square").getAsString()).append(' '));
    return marks.toString().trim();
  }

  private void awaitRestart(long resumedBefore) throws InterruptedException {
    await("the arbiter restarts the clock", () -> white.count("gameResumed") > resumedBefore);
    assertEquals(Side.WHITE, clockRunsFor());
  }

  @Test
  void theRookLetGoAcrossTwoSquaresIsBoundToThemAndSentThereUntilItStandsOnOne() throws Exception {
    startWithRook();
    final int rook = idOn("a1");

    // Let go on the line between d1 and e1: no ruling yet, the clock runs, nothing is circled.
    release(white, rook, x("e1") - 512, y("e1"));
    assertEquals(0, white.count(CALL));
    assertEquals(0, white.count("boardMarks"));
    assertEquals(Side.WHITE, clockRunsFor());
    assertEquals(Square.A1, piece(rook).square(), "it still counts on a1");

    // The clock press: the call names the two squares, counts once, and circles them on both boards.
    long resumed = white.count("gameResumed");
    press(white);
    assertTrue(white.lastMessage(CALL).startsWith("It is not clear on which square your rook is. Place it on d1 or"
        + " on e1, and place your pieces more carefully."), white.lastMessage(CALL));
    assertEquals(1, unclearCount(Side.WHITE));
    assertEquals("green d1 green e1", marks(white));
    assertEquals("green d1 green e1", marks(black));
    awaitRestart(resumed);

    // Placed clearly on b1, a square it is not bound to: Rb1 is not made, the arbiter names the squares again,
    // and nothing is counted.
    release(white, rook, x("b1"), y("b1"));
    resumed = white.count("gameResumed");
    press(white);
    assertTrue(white.lastMessage(CALL).startsWith("Your rook was released on d1 and e1. Place it on d1 or on e1."),
        white.lastMessage(CALL));
    assertTrue(black.lastMessage("opponentInfo").startsWith("Your opponent's rook was released on d1 and e1. It has"
        + " to be placed on one of them."), black.lastMessage("opponentInfo"));
    assertEquals(1, unclearCount(Side.WHITE), "the second ruling does not count");
    assertEquals(0, white.count("move_accepted"));
    assertEquals(Side.WHITE, room.getSession().getHavingMove());
    assertEquals(Square.B1, piece(rook).square(), "the rook stays where the player put it");
    awaitRestart(resumed);

    // Taken to d1: the circles go on both boards, and the move is accepted.
    release(white, rook, x("d1"), y("d1"));
    assertEquals("", marks(white));
    assertEquals("", marks(black));
    press(white);
    assertEquals(1, white.count("move_accepted"));
    assertTrue(room.getSession().exportPgn().contains("Rd1"), room.getSession().exportPgn());
    assertEquals(Side.BLACK, room.getSession().getHavingMove());
  }

  @Test
  void theBoundRookPlacedClearlyOnOneOfItsSquaresBeforeTheClockPressIsAnOrdinaryMove() throws Exception {
    startWithRook();
    final int rook = idOn("a1");
    release(white, rook, x("e1") - 512, y("e1"));
    release(white, rook, x("e1"), y("e1"));
    press(white);
    assertEquals(0, white.count(CALL));
    assertEquals(0, unclearCount(Side.WHITE));
    assertEquals(1, white.count("move_accepted"));
    assertTrue(room.getSession().exportPgn().contains("Re1"), room.getSession().exportPgn());
  }

  @Test
  void aPieceLetGoAcrossTheBorderOfItsOwnSquareIsBoundToNothing() throws Exception {
    start(createMessage());
    setLong("wrongClockPauseMs", 30);
    final int pawn = idOn("e2");
    release(white, pawn, x("e2"), y("e3") - 512); // across e2 and e3
    final long resumed = white.count("gameResumed");
    press(white);
    assertTrue(white.lastMessage(CALL).startsWith("It is not clear on which square your pawn is. Place your pieces"
        + " more carefully."), white.lastMessage(CALL));
    assertEquals(0, white.count("boardMarks"));
    awaitRestart(resumed);
    release(white, pawn, x("e4"), y("e4"));
    press(white);
    assertEquals(1, white.count("move_accepted"));
  }

  @Test
  void aDrawOfferWithTheBoundRookOnAnotherSquareGetsTheCallAndIsNotMade() throws Exception {
    startWithRook();
    final int rook = idOn("a1");
    release(white, rook, x("e1") - 512, y("e1"));
    release(white, rook, x("b1"), y("b1"));
    send(white, "offerDraw");
    assertTrue(white.lastMessage(CALL).startsWith("Your rook was released on d1 and e1."), white.lastMessage(CALL));
    assertEquals("green d1 green e1", marks(white));
    assertFalse(room.getSession().getDrawOfferManager().isDrawOffered(), "the offer is not made");
    assertEquals(0, black.count("drawOffered"));
    assertEquals(0, unclearCount(Side.WHITE));
  }

  @Test
  void aPageThatComesBackIsShownTheCirclesOfTheBoundSquares() throws Exception {
    startWithRook();
    release(white, idOn("a1"), x("e1") - 512, y("e1"));
    final long resumed = white.count("gameResumed");
    press(white);
    awaitRestart(resumed);

    final Client back = new Client();
    final JsonObject resume = new JsonObject();
    resume.addProperty("type", "resume");
    resume.addProperty("gameId", room.getGameId());
    resume.addProperty("token", room.getToken(Side.BLACK));
    server.onMessage(back.socket, resume.toString());
    assertEquals("green d1 green e1", marks(back));
  }

  // ===== The frames =====

  @Test
  void aReleaseThatIsNoneIsRefusedAndThePageIsSentThePieces() throws Exception {
    start(createMessage());
    release(white, 999, x("e4"), y("e4"));
    assertEquals(1, white.count("error"));
    assertEquals(1, white.count("placementsSync"));

    final JsonObject event = new JsonObject();
    event.addProperty("eventType", "RELEASE");
    event.addProperty("id", idOn("e2"));
    event.addProperty("x", 4608.5);
    event.addProperty("y", 3584);
    final JsonObject message = new JsonObject();
    message.addProperty("type", "boardEvent");
    message.add("event", event);
    server.onMessage(white.socket, message.toString());
    assertEquals(2, white.count("error"));
    assertTrue(room.getPlacements().showsClearly(room.getSession().getPositionBeforeTurn()));
    assertEquals(0, black.count("opponentBoardEvent"));
  }

  @Test
  void aRematchKeepsFreePlacementAndStartsCentred() throws Exception {
    startAfterE4D5();
    leaveVictimOnTheLine();
    send(white, "resign");
    send(white, "rematchOffer");
    send(black, "rematchOffer");
    room.stopClockTicker();
    assertNotNull(room.getPlacements());
    assertEquals(32, white.last("rematchStarted").getAsJsonArray("placements").size());
    assertTrue(room.getPlacements().showsClearly(room.getSession().getPositionBeforeTurn()));
    assertEquals(0, room.getHalfRemovedPieceId());
  }
}
