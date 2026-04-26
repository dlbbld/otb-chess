package com.dlb.chess.dumbboard.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponseType;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.game.GameSession;
import com.dlb.chess.dumbboard.game.model.DrawClaimResult;
import com.dlb.chess.dumbboard.game.model.DrawClaimType;
import com.dlb.chess.dumbboard.game.model.GameResult;
import com.dlb.chess.dumbboard.game.model.GameState;
import com.dlb.chess.dumbboard.game.model.TimeControl;
import com.dlb.chess.dumbboard.server.message.MessageConverter;
import com.dlb.chess.dumbboard.server.model.GameRoom;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * WebSocket server for dumb chessboard game communication.
 */
public class GameWebSocketServer extends WebSocketServer {

  private static final Gson GSON = new Gson();

  private final Map<String, GameRoom> gameRooms = new ConcurrentHashMap<>();
  private final Map<WebSocket, String> playerGameMap = new ConcurrentHashMap<>();
  private final ScheduledExecutorService clockExecutor = Executors.newScheduledThreadPool(2);

  // TESTING-ONLY: most recently created game ID, exposed via /api/lastGameId so a second browser
  // session can pre-fill the join field without manual copy/paste. Remove once development is done.
  private volatile String lastCreatedGameId;

  public GameWebSocketServer(int port) {
    super(new InetSocketAddress(port));
    setTcpNoDelay(true); // Disable Nagle's algorithm for low-latency messaging
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    System.out.println("New connection: " + conn.getRemoteSocketAddress());
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    System.out.println("Connection closed: " + conn.getRemoteSocketAddress());
    final String gameId = playerGameMap.remove(conn);
    if (gameId != null) {
      final GameRoom room = gameRooms.get(gameId);
      if (room != null) {
        final Side side = room.getSide(conn);
        if (side != Side.NONE) {
          // Notify opponent
          final JsonObject msg = new JsonObject();
          msg.addProperty("type", "opponentDisconnected");
          msg.addProperty("message", "Your opponent has disconnected.");
          room.sendToSide(side.getOppositeSide(), GSON.toJson(msg));
        }
      }
    }
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    try {
      final JsonObject json = GSON.fromJson(message, JsonObject.class);
      final String type = json.get("type").getAsString();

      switch (type) {
        case "createGame" -> handleCreateGame(conn, json);
        case "joinGame" -> handleJoinGame(conn, json);
        case "boardEvent" -> handleBoardEvent(conn, json);
        case "clockPress" -> handleClockPress(conn, json);
        case "offerDraw" -> handleOfferDraw(conn, json);
        case "acceptDraw" -> handleAcceptDraw(conn);
        case "rejectDraw" -> handleRejectDraw(conn);
        case "claimDraw" -> handleClaimDraw(conn, json);
        case "resign" -> handleResign(conn);
        case "requestPgn" -> handleRequestPgn(conn);
        case "restorePosition" -> handleRestorePosition(conn);
        case "readyToContinue" -> handleReadyToContinue(conn);
        case "opponentClockPressed" -> handleOpponentClockPressed(conn);
        default -> sendError(conn, "Unknown message type: " + type);
      }
    } catch (final Exception e) {
      sendError(conn, "Error processing message: " + e.getMessage());
    }
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    System.err.println("WebSocket error: " + ex.getMessage());
    ex.printStackTrace();
  }

  @Override
  public void onStart() {
    System.out.println("WebSocket server started on port " + getPort());
  }

  // ===== Message handlers =====

  private void handleCreateGame(WebSocket conn, JsonObject json) {
    final long initialTimeMs = json.get("initialTimeMs").getAsLong();
    final long incrementMs = json.get("incrementMs").getAsLong();
    final String sideStr = json.get("side").getAsString();
    // maxIllegalMoves: 1..10 = limit, -1 = unlimited, missing = FIDE default (2)
    final int maxIllegalMoves = json.has("maxIllegalMoves") ? json.get("maxIllegalMoves").getAsInt()
        : com.dlb.chess.dumbboard.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES;
    final boolean autoResumeAfterRestore = !json.has("autoResumeAfterRestore")
        || json.get("autoResumeAfterRestore").getAsBoolean();

    final String gameId = UUID.randomUUID().toString().substring(0, 8);
    final TimeControl timeControl = new TimeControl(initialTimeMs, incrementMs);
    final GameRoom room = new GameRoom(gameId, timeControl, maxIllegalMoves, autoResumeAfterRestore);

    if ("white".equalsIgnoreCase(sideStr)) {
      room.setWhitePlayer(conn);
    } else {
      room.setBlackPlayer(conn);
    }

    gameRooms.put(gameId, room);
    playerGameMap.put(conn, gameId);
    lastCreatedGameId = gameId; // TESTING-ONLY: see field comment

    final JsonObject response = new JsonObject();
    response.addProperty("type", "gameCreated");
    response.addProperty("gameId", gameId);
    response.addProperty("side", sideStr.toLowerCase());
    response.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(StaticPosition.INITIAL_POSITION)));
    conn.send(GSON.toJson(response));

    System.out.println("Game created: " + gameId + " by " + sideStr);
  }

  private void handleJoinGame(WebSocket conn, JsonObject json) {
    final String gameId = json.get("gameId").getAsString();
    final GameRoom room = gameRooms.get(gameId);

    if (room == null) {
      sendError(conn, "Game not found: " + gameId);
      return;
    }
    if (room.isFull()) {
      sendError(conn, "Game is already full.");
      return;
    }

    final String side;
    if (room.getWhitePlayer() == null) {
      room.setWhitePlayer(conn);
      side = "white";
    } else {
      room.setBlackPlayer(conn);
      side = "black";
    }

    playerGameMap.put(conn, gameId);

    // Send join confirmation to the joining player
    final JsonObject joinResponse = new JsonObject();
    joinResponse.addProperty("type", "gameJoined");
    joinResponse.addProperty("gameId", gameId);
    joinResponse.addProperty("side", side);
    joinResponse.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(StaticPosition.INITIAL_POSITION)));
    conn.send(GSON.toJson(joinResponse));

    // Notify both players that the game is starting
    final JsonObject startMsg = new JsonObject();
    startMsg.addProperty("type", "gameStarted");
    startMsg.addProperty("message", "Both players connected. Game starting!");
    room.sendToBoth(GSON.toJson(startMsg));

    // Start the game
    room.getSession().startGame();

    // Start clock ticker
    room.startClockTicker(clockExecutor, () -> tickClock(room));

    // Send initial clock update
    sendClockUpdate(room);

    System.out.println("Game started: " + gameId);
  }

  private void handleBoardEvent(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final JsonObject eventData = json.getAsJsonObject("event");

    final BoardEvent event = MessageConverter.toBoardEvent(
        eventData.get("eventType").getAsString(),
        eventData.get("square").getAsString(),
        eventData.get("targetSquare").getAsString(),
        eventData.get("piece").getAsString(),
        eventData.get("displacedPiece").getAsString());

    if (room.getSession().isRestorationResumePending()) {
      return;
    }

    if (room.getSession().isWaitingForRestoration()) {
      forwardBoardEventToOpponent(room, side, eventData);

      if (json.has("boardState")) {
        @SuppressWarnings("unchecked")
        final Map<String, String> boardStateMap = GSON.fromJson(json.getAsJsonObject("boardState"), Map.class);
        final StaticPosition afterPosition = MessageConverter.toStaticPosition(boardStateMap);
        if (room.getSession().isRestoredPosition(afterPosition)) {
          completeRestoration(room);
        }
      }
      return;
    }

    final Optional<ArbiterResponse> midPlayResponse = room.getSession().recordEvent(side, event);

    if (midPlayResponse.isPresent()) {
      sendArbiterResponse(room, side, midPlayResponse.get());
    }

    // Forward the event to the opponent for real-time board visibility
    forwardBoardEventToOpponent(room, side, eventData);

    // Auto-end on game-ending moves (checkmate, stalemate, dead position, fivefold, 75-move):
    // accept the move and end the game without waiting for a clock press.
    if (midPlayResponse.isEmpty() && json.has("boardState")) {
      @SuppressWarnings("unchecked")
      final Map<String, String> boardStateMap = GSON.fromJson(json.getAsJsonObject("boardState"), Map.class);
      final StaticPosition afterPosition = MessageConverter.toStaticPosition(boardStateMap);
      final Optional<ArbiterResponse> autoEndResponse = room.getSession().evaluateForAutoEnd(side, afterPosition);
      if (autoEndResponse.isPresent()) {
        sendArbiterResponse(room, side, autoEndResponse.get());
        sendClockUpdate(room);
        checkGameEnded(room);
      }
    }
  }

  private void handleClockPress(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);

    @SuppressWarnings("unchecked")
    final Map<String, String> boardState = GSON.fromJson(json.getAsJsonObject("boardState"), Map.class);
    final StaticPosition afterPosition = MessageConverter.toStaticPosition(boardState);

    final ArbiterResponse response = room.getSession().pressClockButton(side, afterPosition);
    sendArbiterResponse(room, side, response);

    if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
      sendClockUpdate(room);
      // Note: opponentMoved (sent by sendArbiterResponse) already includes the board state.
      // Do NOT also send boardUpdate here, as it can overwrite the opponent's in-progress moves.
    } else if (response.type() == ArbiterResponseType.ILLEGAL_MOVE
        || response.type() == ArbiterResponseType.TOUCH_MOVE_VIOLATION) {
      sendRestoreInstructions(room, side);
    }

    checkGameEnded(room);
  }

  private void handleOfferDraw(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final boolean isCorrectTime = side == room.getSession().getHavingMove();

    if (isCorrectTime) {
      // Correct time: after making a move — triggers move evaluation
      @SuppressWarnings("unchecked")
      final Map<String, String> boardState = GSON.fromJson(json.getAsJsonObject("boardState"), Map.class);
      final StaticPosition afterPosition = MessageConverter.toStaticPosition(boardState);

      final ArbiterResponse response = room.getSession().offerDrawCorrectTime(side, afterPosition);
      sendArbiterResponse(room, side, response);

      if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
        sendDrawOfferToOpponent(room, side);
        sendClockUpdate(room);
      }
    } else {
      // Wrong time: offer is still valid, but penalties apply
      final var result = room.getSession().offerDrawWrongTime(side);

      if (result.arbiterMessage() != null) {
        // Send arbiter message to the player
        final JsonObject arbiterMsg = new JsonObject();
        arbiterMsg.addProperty("type", result.isWrongTime() ? "wrongTimeDrawOffer" : "repeatedDrawOffer");
        arbiterMsg.addProperty("message", result.arbiterMessage());
        conn.send(GSON.toJson(arbiterMsg));

        if (result.isWrongTime()) {
          // Stop clock, ready-to-continue flow
          room.getSession().getClock().stopClock();
          room.getSession().enterWaitingForReady();
          final JsonObject readyMsg = new JsonObject();
          readyMsg.addProperty("type", "waitingForReady");
          readyMsg.addProperty("message", "Are you ready to continue?");
          room.sendToBoth(GSON.toJson(readyMsg));
        }
      }

      // Forward the draw offer to the opponent (it's valid even at wrong time)
      if (result.accepted() && !result.gameLost()) {
        sendDrawOfferToOpponent(room, side);
      }
    }

    checkGameEnded(room);
  }

  private void sendDrawOfferToOpponent(GameRoom room, Side offeringSide) {
    final JsonObject drawMsg = new JsonObject();
    drawMsg.addProperty("type", "drawOffered");
    drawMsg.addProperty("message", "Your opponent offers a draw.");
    room.sendToSide(offeringSide.getOppositeSide(), GSON.toJson(drawMsg));
  }

  private void handleAcceptDraw(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final Optional<GameResult> result = room.getSession().acceptDraw(side);

    if (result.isPresent()) {
      sendGameEnded(room, result.get());
    } else {
      // Acceptance was rejected (e.g. touched a piece)
      final String rejection = room.getSession().getLastAcceptDrawRejection();
      if (rejection != null) {
        // Arbiter intervention: stop clock, explain, ready-to-continue
        room.getSession().getClock().stopClock();
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "drawAcceptRejected");
        msg.addProperty("message", rejection);
        conn.send(GSON.toJson(msg));

        room.getSession().enterWaitingForReady();
        final JsonObject readyMsg = new JsonObject();
        readyMsg.addProperty("type", "waitingForReady");
        readyMsg.addProperty("message", "Are you ready to continue?");
        room.sendToBoth(GSON.toJson(readyMsg));
      }
    }
  }

  private void handleRejectDraw(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    room.getSession().rejectDraw(side);

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "drawRejected");
    msg.addProperty("message", "Draw offer rejected.");
    room.sendToBoth(GSON.toJson(msg));
  }

  private void handleClaimDraw(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final DrawClaimType claimType = DrawClaimType.valueOf(json.get("claimType").getAsString());
    final String san = json.has("san") ? json.get("san").getAsString() : null;

    final DrawClaimResult result = room.getSession().claimDraw(side, claimType, san);

    final JsonObject response = new JsonObject();
    response.addProperty("type", "drawClaimResult");
    response.addProperty("accepted", result.accepted());
    response.addProperty("message", result.message());
    if (result.moveToPerform().isPresent()) {
      response.addProperty("mustExecuteMove", result.moveToPerform().get().toString());
    }
    conn.send(GSON.toJson(response));

    checkGameEnded(room);
  }

  private void handleResign(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final GameResult result = room.getSession().resign(side);
    sendGameEnded(room, result);
  }

  private void handleRequestPgn(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final String pgn = room.getSession().exportPgn();
    final JsonObject response = new JsonObject();
    response.addProperty("type", "pgn");
    response.addProperty("pgn", pgn);
    conn.send(GSON.toJson(response));
  }

  private void handleRestorePosition(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    // Send the position before the turn to the player so the client can restore
    final var restorePosition = room.getSession().getRestorePosition();
    completeRestoration(room, restorePosition);
  }

  private void completeRestoration(GameRoom room) {
    completeRestoration(room, room.getSession().getRestorePosition());
  }

  private void completeRestoration(GameRoom room, StaticPosition restorePosition) {
    room.getSession().completeRestoration();

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "positionRestored");
    msg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(restorePosition)));
    if (room.getSession().isAutoResumeAfterRestore()) {
      msg.addProperty("message", "Position restored. Restarting the clock shortly. Please be ready.");
      msg.addProperty("autoResumePending", true);
    } else {
      msg.addProperty("message", "Position restored. Are you ready to continue?");
      msg.addProperty("autoResumePending", false);
    }
    room.sendToBoth(GSON.toJson(msg));

    if (room.getSession().isAutoResumeAfterRestore()) {
      clockExecutor.schedule(() -> resumeAfterRestorationDelay(room), 1500, TimeUnit.MILLISECONDS);
      return;
    }

    // Send ready prompt to both players
    final JsonObject readyMsg = new JsonObject();
    readyMsg.addProperty("type", "waitingForReady");
    readyMsg.addProperty("message", "Are you ready to continue?");
    room.sendToBoth(GSON.toJson(readyMsg));
  }

  private void resumeAfterRestorationDelay(GameRoom room) {
    room.getSession().resumeAfterRestorationDelay();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "gameResumed");
    msg.addProperty("message", "Clock restarted. Game continues.");
    msg.addProperty("havingMove", room.getSession().getHavingMove().name().toLowerCase());
    room.sendToBoth(GSON.toJson(msg));
    sendClockUpdate(room);
  }

  private void handleReadyToContinue(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final boolean bothReady = room.getSession().playerReady(side);

    if (bothReady) {
      // Both players are ready — game continues
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "gameResumed");
      msg.addProperty("message", "Both players ready. Game continues.");
      msg.addProperty("havingMove", room.getSession().getHavingMove().name().toLowerCase());
      room.sendToBoth(GSON.toJson(msg));
      sendClockUpdate(room);
    } else {
      // Waiting for the other player
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "waitingForOpponentReady");
      msg.addProperty("message", "Waiting for your opponent to be ready...");
      conn.send(GSON.toJson(msg));
    }
  }

  private void handleOpponentClockPressed(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    // Stop the clock
    room.getSession().getClock().stopClock();

    // Notify the player who pressed the wrong clock
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "opponentClockPressed");
    msg.addProperty("message", "Please do not press the opponent's clock.");
    conn.send(GSON.toJson(msg));

    // Enter waiting for ready
    room.getSession().enterWaitingForReady();

    // Send ready prompt to both
    final JsonObject readyMsg = new JsonObject();
    readyMsg.addProperty("type", "waitingForReady");
    readyMsg.addProperty("message", "Are you ready to continue?");
    room.sendToBoth(GSON.toJson(readyMsg));
  }

  private void sendRestoreInstructions(GameRoom room, Side side) {
    room.getSession().enterWaitingForRestoration();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "restoreRequired");
    msg.addProperty("message", "Please restore the position to the beginning of the move.");
    room.sendToSide(side, GSON.toJson(msg));
  }

  private void forwardBoardEventToOpponent(GameRoom room, Side side, JsonObject eventData) {
    final JsonObject forwardMsg = new JsonObject();
    forwardMsg.addProperty("type", "opponentBoardEvent");
    forwardMsg.add("event", eventData);
    room.sendToSide(side.getOppositeSide(), GSON.toJson(forwardMsg));
  }

  // ===== Helper methods =====

  /**
   * TESTING-ONLY: returns the most recently created game ID that is still joinable (room exists
   * and not yet full), or null if no such game exists. Used by the lobby HTTP endpoint to pre-fill
   * the join code in a second browser session. Remove once development is done.
   */
  public String getJoinableLastCreatedGameId() {
    final String id = lastCreatedGameId;
    if (id == null) {
      return null;
    }
    final GameRoom room = gameRooms.get(id);
    if (room == null || room.isFull()) {
      return null;
    }
    return id;
  }

  private GameRoom getRoom(WebSocket conn) {
    final String gameId = playerGameMap.get(conn);
    if (gameId == null) {
      sendError(conn, "You are not in a game.");
      return null;
    }
    return gameRooms.get(gameId);
  }

  private void sendArbiterResponse(GameRoom room, Side side, ArbiterResponse response) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", response.type().name().toLowerCase());
    msg.addProperty("message", response.message());

    if (response.acceptedMove().isPresent()) {
      final var move = response.acceptedMove().get();
      final JsonObject moveData = new JsonObject();
      moveData.addProperty("from", move.moveSpecification().fromSquare().getName());
      moveData.addProperty("to", move.moveSpecification().toSquare().getName());
      moveData.addProperty("piece", move.movingPiece().name());
      msg.add("move", moveData);
    }

    System.out.println("Sending to " + side + ": " + response.type());
    room.sendToSide(side, GSON.toJson(msg));

    // Also notify opponent of accepted moves — include full board state
    if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
      final var position = room.getSession().getBoard().getStaticPosition();
      final var havingMove = room.getSession().getHavingMove();
      final var isCheck = room.getSession().isCheck();

      final JsonObject opponentMsg = new JsonObject();
      opponentMsg.addProperty("type", "opponentMoved");
      opponentMsg.addProperty("message", "Opponent completed a move.");
      opponentMsg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(position)));
      opponentMsg.addProperty("havingMove", havingMove.name().toLowerCase());
      opponentMsg.addProperty("isCheck", isCheck);
      System.out.println("Sending opponentMoved to " + side.getOppositeSide() + " with board state");
      room.sendToSide(side.getOppositeSide(), GSON.toJson(opponentMsg));
    }
  }

  private void sendClockUpdate(GameRoom room) {
    final var clock = room.getSession().getClock();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "clockUpdate");
    msg.addProperty("whiteTimeMs", clock.getRemainingTimeMs(Side.WHITE));
    msg.addProperty("blackTimeMs", clock.getRemainingTimeMs(Side.BLACK));
    msg.addProperty("running", clock.getRunningFor().name().toLowerCase());
    room.sendToBoth(GSON.toJson(msg));
  }

  private void sendBoardUpdate(GameRoom room) {
    final var position = room.getSession().getBoard().getStaticPosition();
    final var havingMove = room.getSession().getHavingMove();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "boardUpdate");
    msg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(position)));
    msg.addProperty("havingMove", havingMove.name().toLowerCase());
    msg.addProperty("isCheck", room.getSession().isCheck());
    System.out.println("Sending boardUpdate to both players. Having move: " + havingMove);
    room.sendToBoth(GSON.toJson(msg));
  }

  private void sendGameEnded(GameRoom room, GameResult result) {
    room.stopClockTicker();

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "gameEnded");
    msg.addProperty("resultType", result.type().name());
    msg.addProperty("winner", result.winner().name().toLowerCase());
    msg.addProperty("description", result.description());
    room.sendToBoth(GSON.toJson(msg));
  }

  private void checkGameEnded(GameRoom room) {
    if (room.getSession().getState() == GameState.ENDED) {
      final GameResult result = room.getSession().getResult();
      if (result != null) {
        sendGameEnded(room, result);
      }
    }
  }

  private void tickClock(GameRoom room) {
    try {
      if (room.getSession().getState() != GameState.IN_PROGRESS) {
        return;
      }

      // Check flag fall
      final Optional<GameResult> flagFall = room.getSession().checkFlagFall();
      if (flagFall.isPresent()) {
        sendGameEnded(room, flagFall.get());
        return;
      }

      // Send clock update
      sendClockUpdate(room);
    } catch (final Exception e) {
      System.err.println("Error in clock tick: " + e.getMessage());
    }
  }

  private void sendError(WebSocket conn, String message) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "error");
    msg.addProperty("message", message);
    if (conn != null && conn.isOpen()) {
      conn.send(GSON.toJson(msg));
    }
  }
}
