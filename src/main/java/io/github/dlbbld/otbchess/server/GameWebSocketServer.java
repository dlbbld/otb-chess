// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.arbiter.BoardMark;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResult;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/**
 * WebSocket server for OTB Chess game communication.
 */
public class GameWebSocketServer extends WebSocketServer {

  private static final Gson GSON = new Gson();

  // Join codes: 12 base32 chars (~60 bits) from a CSPRNG. Wide enough that the codes can't be
  // guessed/enumerated, replacing the old 32-bit UUID prefix.
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final int JOIN_CODE_LENGTH = 12;
  // Reconnect tokens are longer (~100 bits) since they authorise re-attaching to a seat.
  private static final int SESSION_TOKEN_LENGTH = 20;

  private final Map<String, GameRoom> gameRooms = new ConcurrentHashMap<>();
  private final Map<WebSocket, String> playerGameMap = new ConcurrentHashMap<>();
  private final ScheduledExecutorService clockExecutor = Executors.newScheduledThreadPool(2);

  // --- Phase 2 hardening config (12-factor; overridable via environment) ---
  private final int maxConcurrentGames = OtbChessServer.envInt("OTB_MAX_GAMES", 1000);
  private final long roomTtlMs = OtbChessServer.envLong("OTB_ROOM_TTL_MS", TimeUnit.MINUTES.toMillis(30));
  private final long endedRoomTtlMs = OtbChessServer.envLong("OTB_ENDED_ROOM_TTL_MS", TimeUnit.MINUTES.toMillis(30));
  private final int maxMessageChars = OtbChessServer.envInt("OTB_MAX_MSG_CHARS", 65536);
  private final int maxViolationsPerConn = OtbChessServer.envInt("OTB_MAX_VIOLATIONS", 30);
  // How long to wait after a socket drops before telling the opponent "disconnected" — gives the
  // player a window to reconnect (idle drop / network blip) without flapping the game.
  private final long disconnectGraceMs = OtbChessServer.envLong("OTB_DISCONNECT_GRACE_MS",
      TimeUnit.SECONDS.toMillis(12));
  // How long after a socket drops (without a resume) a RUNNING game is adjudicated as abandoned:
  // the leaver loses unless the remaining player has no possible mate (then it is a draw). Longer
  // than the disconnect grace so a network blip never forfeits a game.
  private final long abandonMs = OtbChessServer.envLong("OTB_ABANDON_MS", TimeUnit.SECONDS.toMillis(60));
  // Length of the arbiter's admonishment pause after a wrong clock press before the interrupted
  // clock restarts.
  private final long wrongClockPauseMs = OtbChessServer.envLong("OTB_WRONG_CLOCK_PAUSE_MS",
      TimeUnit.SECONDS.toMillis(5));
  private final Set<String> allowedOrigins = parseAllowedOrigins();
  private final UsageLog usageLog = UsageLog.fromConfig(OtbChessServer.envStr("OTB_USAGE_LOG", "logs/usage.log"),
      OtbChessServer.envInt("OTB_USAGE_RETENTION_DAYS", 30));
  // Daemon so it never keeps the JVM alive (e.g. on a failed startup that stops the server).
  private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(daemon("otb-maint"));

  public GameWebSocketServer(String host, int port) {
    super(new InetSocketAddress(host, port));
    setTcpNoDelay(true); // Disable Nagle's algorithm for low-latency messaging
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    // Anti-CSWSH: reject WebSocket handshakes whose Origin isn't allowlisted. A missing Origin is
    // allowed (non-browser clients / smoke tests don't send one); browsers always do, so a mismatch
    // means a cross-site page is trying to drive this socket.
    final String origin = handshake.hasFieldValue("Origin") ? handshake.getFieldValue("Origin") : null;
    if (!isOriginAllowed(origin, allowedOrigins)) {
      System.out.println("Rejected WebSocket: disallowed Origin '" + origin + "' from " + conn.getRemoteSocketAddress());
      conn.close(CloseFrame.POLICY_VALIDATION, "Origin not allowed");
      return;
    }
    conn.setAttachment(new ConnectionState());
    System.out.println("New connection: " + conn.getRemoteSocketAddress());
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    System.out.println("Connection closed: " + conn.getRemoteSocketAddress());
    final String gameId = playerGameMap.remove(conn);
    if (gameId == null) {
      return;
    }
    final GameRoom room = gameRooms.get(gameId);
    if (room == null) {
      return;
    }
    final Side side = room.getSide(conn);
    if (side == Side.NONE) {
      return;
    }
    markAbsent(room, side, conn, gameId);
  }

  /**
   * Starts the absence of a player whose socket is gone: the opponent is told after the disconnect grace, and a
   * running game is adjudicated as abandoned after the abandonment window - both counted from now. Called when a
   * socket drops, and when a game starts while one seat is not connected: that player is treated as having left at
   * the start of the game, because a deadline that began before the game existed could end it seconds after its
   * first move, or - once spent - never.
   *
   * <p>
   * The timers belong to this absence only: a resume (another socket on the seat), a rematch (the seats swap) or a
   * later call for the same seat (another time stamp) makes them no-ops.
   */
  private void markAbsent(GameRoom room, Side side, WebSocket conn, String gameId) {
    final long since = System.currentTimeMillis();
    room.setDisconnectedAt(side, since);

    // The player may reconnect (idle drop / blip). Defer the "opponent disconnected" notice; if a
    // resume swaps in a new socket for this side within the grace window, the seat no longer points
    // at this (closed) conn and we stay quiet. The notice carries the time remaining until the
    // abandonment adjudication so the client can show a countdown and the Claim-victory button.
    maintenance.schedule(() -> {
      if (isSameAbsence(room, side, conn, since)) {
        if (room.getSession().getState() == GameState.ENDED) {
          synchronized (room) {
            room.setRematchOfferedBy(Side.NONE);
          }
          sendRematchUnavailable(room, side.getOppositeSide(),
              "Your opponent has disconnected. A rematch is no longer available.");
          return;
        }
        sendOpponentDisconnected(room, side.getOppositeSide(), Math.max(0, abandonMs - disconnectGraceMs));
      }
    }, disconnectGraceMs, TimeUnit.MILLISECONDS);

    // Stage 2: if the player is STILL gone after the (longer) abandonment window, a running game
    // is adjudicated as abandoned, like chess servers do: the leaver loses — unless the remaining
    // player has no possible mate by any series of legal moves, in which case it is a draw
    // (GameSession.abandon). A resume swaps in a new socket and defuses this; a game that ended
    // meanwhile (e.g. the leaver's flag fell) makes abandon() a no-op.
    maintenance.schedule(() -> {
      try {
        if (!isSameAbsence(room, side, conn, since)) {
          return; // reconnected, or a later absence has its own deadline
        }
        final GameResult result = room.getSession().abandon(side);
        if (result == null) {
          // The game wasn't running (never started, or already decided). If it was decided right
          // here, by a clock that had run out before the periodic check saw it, announce that.
          checkGameEnded(room);
          return;
        }
        sendGameEnded(room, result);
        System.out.println("Game " + gameId + " adjudicated after abandonment by " + side.name().toLowerCase());
      } catch (final RuntimeException e) {
        System.err.println("[abandonment] " + gameId + ": " + e);
      }
    }, abandonMs, TimeUnit.MILLISECONDS);
  }

  private static boolean isSameAbsence(GameRoom room, Side side, WebSocket conn, long since) {
    final Long current = room.getDisconnectedAt(side);
    return room.getSocket(side) == conn && current != null && current == since;
  }

  private void sendOpponentDisconnected(GameRoom room, Side to, long abandonInMs) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "opponentDisconnected");
    msg.addProperty("message", "Your opponent has disconnected.");
    msg.addProperty("abandonInMs", abandonInMs);
    room.sendToSide(to, GSON.toJson(msg));
  }

  /**
   * Tells a player who has just re-attached that the opponent is away, with the time that is left until the
   * abandonment adjudication. The notice of the opponent's disconnect went to the socket this player has lost, and
   * the reconnect clears the countdown on their screen. Inside the grace nothing is sent: the pending notice will
   * reach the new socket. During an arbiter pause the player's own prompt (Revert, Ready) keeps the window.
   */
  private void sendOpponentAbsence(GameRoom room, Side side) {
    final var session = room.getSession();
    final Long since = room.getDisconnectedAt(side.getOppositeSide());
    if (since == null || session.getState() != GameState.IN_PROGRESS) {
      return;
    }
    final long gone = System.currentTimeMillis() - since;
    if (gone < disconnectGraceMs || session.isWaitingForRestoration() || session.isWaitingForReady()
        || session.isRestorationResumePending()) {
      return;
    }
    sendOpponentDisconnected(room, side, Math.max(0, abandonMs - gone));
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    // Bound the payload first so an oversized frame can't drive allocation before we even parse it.
    if (message != null && message.length() > maxMessageChars) {
      sendError(conn, "Message too large.");
      recordViolation(conn);
      return;
    }

    final JsonObject json;
    try {
      json = GSON.fromJson(message, JsonObject.class);
    } catch (final RuntimeException e) {
      sendError(conn, "Malformed message: invalid JSON.");
      recordViolation(conn);
      return;
    }
    if (json == null || !json.has("type") || !json.get("type").isJsonPrimitive()) {
      sendError(conn, "Malformed message: missing 'type'.");
      recordViolation(conn);
      return;
    }
    final String type = json.get("type").getAsString();

    try {
      if (isLateGameplayMessage(conn, type)) {
        return;
      }
      if (isSeatlessGameplayMessage(conn, type)) {
        sendError(conn, "You are not a player in this game.");
        return;
      }
      switch (type) {
        case "createGame" -> handleCreateGame(conn, json);
        case "joinGame" -> handleJoinGame(conn, json);
        case "boardEvent" -> handleBoardEvent(conn, json);
        case "clockPress" -> handleClockPress(conn, json);
        case "offerDraw" -> handleOfferDraw(conn, json);
        case "acceptDraw" -> handleAcceptDraw(conn);
        case "rejectDraw" -> handleRejectDraw(conn);
        case "claimDraw" -> handleClaimDraw(conn, json);
        case "cancelDrawClaim" -> handleCancelDrawClaim(conn);
        case "resign" -> handleResign(conn);
        case "claimVictory" -> handleClaimVictory(conn);
        case "rematchOffer" -> handleRematchOffer(conn);
        case "abort" -> handleAbort(conn);
        case "requestPgn" -> handleRequestPgn(conn);
        case "restorePosition" -> handleRestorePosition(conn);
        case "readyToContinue" -> handleReadyToContinue(conn);
        case "opponentClockPressed" -> handleOpponentClockPressed(conn);
        case "keepalive" -> handleKeepalive(conn);
        case "resume" -> handleResume(conn, json);
        default -> {
          sendError(conn, "Unknown message type: " + type);
          recordViolation(conn);
        }
      }
    } catch (final Exception e) {
      sendInternalError(conn, e, "onMessage");
    }
  }

  // Everything a player does to the running game. Rematch, PGN export, reconnect and lobby messages are not listed:
  // they stay meaningful after the result.
  private static final Set<String> GAMEPLAY_MESSAGE_TYPES = Set.of("boardEvent", "clockPress", "offerDraw",
      "acceptDraw", "rejectDraw", "claimDraw", "cancelDrawClaim", "resign", "claimVictory", "restorePosition",
      "readyToContinue", "opponentClockPressed");

  /**
   * A gameplay action that was still in flight when the game ended. Both players have the result by then, so the
   * action is dropped without an answer: any reply would overwrite the result message, or a rematch offer made since,
   * on the sender's board.
   */
  private boolean isLateGameplayMessage(WebSocket conn, String type) {
    if (!GAMEPLAY_MESSAGE_TYPES.contains(type)) {
      return false;
    }
    final String gameId = playerGameMap.get(conn);
    final GameRoom room = gameId == null ? null : gameRooms.get(gameId);
    return room != null && room.isGameEndAnnounced();
  }

  /**
   * A game action from a connection that is known to a room but holds no seat in it. Only the two seated
   * connections play: such an action is refused before any handler can act on it for "no side".
   */
  private boolean isSeatlessGameplayMessage(WebSocket conn, String type) {
    if (!GAMEPLAY_MESSAGE_TYPES.contains(type) && !"rematchOffer".equals(type)) {
      return false;
    }
    final String gameId = playerGameMap.get(conn);
    final GameRoom room = gameId == null ? null : gameRooms.get(gameId);
    return room != null && room.getSide(conn) == Side.NONE;
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    System.err.println("WebSocket error: " + ex.getMessage());
    ex.printStackTrace();
  }

  // Counted down by onStart() once the server socket is bound and listening.
  private final CountDownLatch startedLatch = new CountDownLatch(1);

  @Override
  public void onStart() {
    startedLatch.countDown();
    System.out.println("WebSocket server started on port " + getPort());
    // Maintenance: reap rooms created but never joined, and purge usage-log entries past retention.
    maintenance.scheduleAtFixedRate(this::reapAbandonedRooms, 5, 5, TimeUnit.MINUTES);
    maintenance.scheduleAtFixedRate(usageLog::purgeExpired, 0, 6, TimeUnit.HOURS);
  }

  /**
   * Blocks until {@link #onStart()} has fired (the server socket is bound and listening) or the timeout elapses. Lets
   * the launcher expose the HTTP server only after the WebSocket endpoint is ready, so a client that loads the page can
   * always open its WebSocket.
   *
   * @return {@code true} if the server started within the timeout, {@code false} otherwise
   */
  public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
    return startedLatch.await(timeout, unit);
  }

  /**
   * Non-blocking liveness check used by the HTTP {@code /api/health} probe: reports whether {@link #onStart()} has fired,
   * i.e. the server socket is bound and accepting WebSocket connections.
   *
   * @return {@code true} once the WebSocket server is listening
   */
  public boolean isListening() {
    return startedLatch.getCount() == 0;
  }

  // ===== Message handlers =====

  private void handleCreateGame(WebSocket conn, JsonObject json) {
    reapAbandonedRooms();
    if (!releaseWaitingRoomOf(conn)) {
      sendError(conn, "You are already playing a game.");
      return;
    }
    // Resource bound: cap the number of live rooms so a home box can't be exhausted.
    if (gameRooms.size() >= maxConcurrentGames) {
      sendError(conn, "The server is at capacity. Please try again shortly.");
      return;
    }

    // Validate required fields rather than NPE'ing on missing/wrong-typed input.
    final Long initialTimeBoxed = optLong(json, "initialTimeMs");
    final Long incrementBoxed = optLong(json, "incrementMs");
    final String requestedSide = optString(json, "side");
    if (initialTimeBoxed == null || incrementBoxed == null || requestedSide == null) {
      sendError(conn, "createGame requires initialTimeMs, incrementMs, and side.");
      recordViolation(conn);
      return;
    }
    final long initialTimeMs = initialTimeBoxed;
    final long incrementMs = incrementBoxed;
    // Sane bounds: positive base time up to 24h, non-negative increment up to 1h.
    if (initialTimeMs <= 0 || initialTimeMs > TimeUnit.HOURS.toMillis(24) || incrementMs < 0
        || incrementMs > TimeUnit.HOURS.toMillis(1)) {
      sendError(conn, "createGame time control is out of range.");
      recordViolation(conn);
      return;
    }
    // maxIllegalMoves: 1..10 = limit, -1 = unlimited, missing = FIDE default (2)
    final int maxIllegalMoves = (json.has("maxIllegalMoves") && json.get("maxIllegalMoves").isJsonPrimitive())
        ? json.get("maxIllegalMoves").getAsInt()
        : io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES;
    final boolean autoResumeAfterRestore = !(json.has("autoResumeAfterRestore")
        && json.get("autoResumeAfterRestore").isJsonPrimitive()) || json.get("autoResumeAfterRestore").getAsBoolean();

    // Optional FEN — when supplied, the game starts from that position. Validation goes
    // through Ashlar Chess so the player gets the chess library's specific reason. The
    // creator's side is overridden to the side-to-move from the FEN, so the creator can
    // play first regardless of which colour they originally selected on the start screen.
    final String fenInput = (json.has("fen") && !json.get("fen").isJsonNull()) ? json.get("fen").getAsString().trim()
        : "";

    final Board startingBoard;
    final String creatorSide;
    if (fenInput.isEmpty()) {
      startingBoard = new Board();
      creatorSide = "white".equalsIgnoreCase(requestedSide) ? "white" : "black";
    } else {
      final Board parsed;
      try {
        parsed = OtbChessServer.parseStartingBoard(fenInput);
      } catch (final RuntimeException e) {
        // Any parse failure is treated as a user FEN error (the FEN string is user input),
        // surfacing the chess library's specific validation reason rather than an internal error.
        sendError(conn, "Invalid FEN: " + e.getMessage());
        return;
      }
      startingBoard = parsed;
      // Side-to-move from the FEN wins. If the FEN has Black to move, the creator
      // (who joins first) plays Black; the second player gets White.
      creatorSide = parsed.getSideToMove() == io.github.dlbbld.ashlarchess.board.enums.Side.WHITE ? "white" : "black";
    }

    final TimeControl timeControl = new TimeControl(initialTimeMs, incrementMs);
    // Allocate a unique join code. The creator's side is set on the room *before* it becomes
    // visible in the map, so a racing joiner can never claim the creator's colour. putIfAbsent
    // guards against the (astronomically unlikely) code collision.
    String gameId;
    GameRoom room;
    do {
      gameId = generateJoinCode();
      room = new GameRoom(gameId, timeControl, maxIllegalMoves, autoResumeAfterRestore, startingBoard);
      if ("white".equals(creatorSide)) {
        room.setWhitePlayer(conn);
      } else {
        room.setBlackPlayer(conn);
      }
    } while (gameRooms.putIfAbsent(gameId, room) != null);
    playerGameMap.put(conn, gameId);
    usageLog.record(UsageLog.EVENT_CREATE, gameId);

    // Reconnect token for this seat, so a dropped socket can re-attach (see handleResume).
    final Side creatorSideEnum = "white".equals(creatorSide) ? Side.WHITE : Side.BLACK;
    final String token = generateSessionToken();
    room.setToken(creatorSideEnum, token);

    final JsonObject response = new JsonObject();
    response.addProperty("type", "gameCreated");
    response.addProperty("gameId", gameId);
    response.addProperty("side", creatorSide);
    response.addProperty("token", token);
    response.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(startingBoard.getBitboardPosition())));
    response.addProperty("havingMove", startingBoard.getSideToMove().name().toLowerCase());
    // Display label like "5+3 • Blitz" — time control plus FIDE discipline (see TimeControl).
    response.addProperty("timeControlLabel", timeControl.displayLabel());
    send(conn, response);

    System.out.println("Game created: " + gameId + " by " + creatorSide + (fenInput.isEmpty() ? "" : " (custom FEN)"));
  }

  /**
   * A connection has one game. Before it creates another, the room in which it still waits alone is given up -
   * otherwise one connection could take every room the server has, each parked until it expires.
   *
   * @return false when the connection sits in a running game, which a new game must not replace
   */
  private boolean releaseWaitingRoomOf(WebSocket conn) {
    final String gameId = playerGameMap.get(conn);
    final GameRoom room = gameId == null ? null : gameRooms.get(gameId);
    if (room == null) {
      return true;
    }
    synchronized (room) {
      if (gameRooms.get(gameId) != room || room.getSide(conn) == Side.NONE) {
        return true;
      }
      final GameState state = room.getSession().getState();
      if (state == GameState.IN_PROGRESS) {
        return false;
      }
      if (state == GameState.WAITING_FOR_PLAYERS && !room.isFull()) {
        room.stopClockTicker();
        gameRooms.remove(gameId, room);
        playerGameMap.remove(conn, gameId);
        System.out.println("Waiting game replaced by its creator: " + gameId);
      }
      return true;
    }
  }

  private void handleJoinGame(WebSocket conn, JsonObject json) {
    final String gameId = optString(json, "gameId");
    if (gameId == null || gameId.isBlank()) {
      sendError(conn, "joinGame requires a gameId.");
      recordViolation(conn);
      return;
    }
    final GameRoom room = gameRooms.get(gameId);

    if (room == null) {
      // No active game for this code (typo / expired / reaped / server restarted). Send a friendly
      // `joinFailed` so the client can offer a path back to the lobby instead of a raw error. Still
      // count the miss toward the violation budget so join-code scanning gets throttled.
      sendJoinFailed(conn, "not_found",
          "This game code wasn't found. It may have expired, ended, or been entered incorrectly.");
      recordViolation(conn);
      return;
    }
    // Reserve the seat, issue its token and announce the start as one room transaction.
    synchronized (room) {
      joinRoom(conn, gameId, room);
    }
  }

  private void joinRoom(WebSocket conn, String gameId, GameRoom room) {
    if (gameRooms.get(gameId) != room) {
      // Aborted or expired between the lookup and the seat reservation.
      sendJoinFailed(conn, "not_found",
          "This game code wasn't found. It may have expired, ended, or been entered incorrectly.");
      return;
    }
    if (room.isFull()) {
      // The room is still in memory but has no free seat. Distinguish an already-finished game from
      // one with two live players so the message is accurate.
      final boolean ended = room.getSession().getState() == GameState.ENDED;
      sendJoinFailed(conn, ended ? "ended" : "full",
          ended ? "This game has already ended." : "This game already has two players.");
      return;
    }

    if (room.getSession().getState() != GameState.WAITING_FOR_PLAYERS) {
      // A free seat alone is not enough: only a room that still waits for its game can be joined.
      sendJoinFailed(conn, "ended", "This game has already ended.");
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

    // Reconnect token for the joiner's seat (see handleResume).
    final Side seat = "white".equals(side) ? Side.WHITE : Side.BLACK;
    final String token = generateSessionToken();
    room.setToken(seat, token);

    // Send join confirmation to the joining player. Send the actual starting board
    // (not the hard-coded initial position) so a custom-FEN game shows the right
    // pieces in the joiner's first render.
    final var startingPosition = room.getSession().getBoard().getBitboardPosition();
    final var havingMove = room.getSession().getHavingMove();
    final JsonObject joinResponse = new JsonObject();
    joinResponse.addProperty("type", "gameJoined");
    joinResponse.addProperty("gameId", gameId);
    joinResponse.addProperty("side", side);
    joinResponse.addProperty("token", token);
    joinResponse.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(startingPosition)));
    joinResponse.addProperty("havingMove", havingMove.name().toLowerCase());
    joinResponse.addProperty("timeControlLabel", room.getTimeControl().displayLabel());
    if (!send(conn, joinResponse)) {
      // The joiner's socket closed while it was being seated. Give the seat back: the room is then
      // waiting again - for another joiner or for the reaper - instead of full without a game.
      room.setSocket(seat, null);
      room.setToken(seat, null);
      // The close callback may have marked the seat as disconnected meanwhile; the mark goes with
      // the seat, or the next joiner would count as absent.
      room.setDisconnectedAt(seat, null);
      playerGameMap.remove(conn, gameId);
      return;
    }
    usageLog.record(UsageLog.EVENT_JOIN, gameId);

    // Notify both players that the game is starting. `havingMove` lets the client
    // correctly assign the first turn — this matters when the FEN starts with Black
    // to move.
    final JsonObject startMsg = new JsonObject();
    startMsg.addProperty("type", "gameStarted");
    startMsg.addProperty("message", "Both players connected. Game starting!");
    startMsg.addProperty("havingMove", havingMove.name().toLowerCase());
    room.sendToBoth(GSON.toJson(startMsg));

    // Start the game
    room.getSession().startGame();

    // Start clock ticker
    room.startClockTicker(clockExecutor, () -> tickClock(room));

    // Send initial clock update
    sendClockUpdate(room);

    // The creator may have dropped while waiting. The game starts all the same - they can still
    // resume - but their absence counts from now, not from a drop that preceded the game.
    final Side creatorSeat = seat.getOppositeSide();
    final WebSocket creator = room.getSocket(creatorSeat);
    if (creator != null && (!creator.isOpen() || room.getDisconnectedAt(creatorSeat) != null)) {
      markAbsent(room, creatorSeat, creator, gameId);
    }

    System.out.println("Game started: " + gameId);
  }

  private void handleBoardEvent(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    if (side != room.getSession().getHavingMove() || room.getSession().getState() != GameState.IN_PROGRESS) {
      return;
    }
    final JsonObject eventData = optObject(json, "event");
    final String eventType = (eventData == null) ? null : optString(eventData, "eventType");
    if (eventType == null) {
      sendError(conn, "Malformed boardEvent: missing 'event'/'eventType'.");
      recordViolation(conn);
      return;
    }

    // Cosmetic drag-in-progress events (DRAG_START, DRAG_HOVER) are display-only.
    // Forward them to the opponent so they can mirror the dragging player's hand,
    // but do NOT run them through the arbiter, the move recorder, or the auto-end
    // check — they aren't moves and don't represent any change to the position.
    if ("DRAG_START".equals(eventType) || "DRAG_HOVER".equals(eventType)) {
      if (!room.getSession().isRestorationResumePending()) {
        forwardBoardEventToOpponent(room, side, eventData);
      }
      return;
    }

    final String sq = optString(eventData, "square");
    final String targetSq = optString(eventData, "targetSquare");
    final String piece = optString(eventData, "piece");
    final String displaced = optString(eventData, "displacedPiece");
    if (sq == null || targetSq == null || piece == null || displaced == null) {
      sendError(conn, "Malformed boardEvent: missing square/piece fields.");
      recordViolation(conn);
      return;
    }
    final BoardEvent event;
    try {
      event = MessageConverter.toBoardEvent(eventType, sq, targetSq, piece, displaced);
    } catch (final RuntimeException e) {
      // Unknown event type or invalid square/piece value — treat as a malformed frame, not a crash.
      sendError(conn, "Malformed boardEvent.");
      recordViolation(conn);
      return;
    }

    if (room.getSession().isRestorationResumePending()) {
      return;
    }

    final BitboardPosition physicalPosition = optionalBoardState(json);
    if (physicalPosition != null) {
      room.setPhysicalPosition(physicalPosition);
    }

    if (room.getSession().isWaitingForRestoration()) {
      forwardBoardEventToOpponent(room, side, eventData);

      final BitboardPosition afterPosition = optionalBoardState(json);
      if (afterPosition != null && room.getSession().isRestoredPosition(afterPosition)) {
        completeRestoration(room);
      }
      return;
    }

    if (room.getSession().isTurnEventLimitReached()) {
      // Far beyond anything a hand does in one turn (see GameSession.MAX_BOARD_EVENTS_PER_TURN):
      // not recorded, not forwarded, and counted against the connection.
      sendError(conn, "Too many piece movements in one move. Further movements are not recorded.");
      recordViolation(conn);
      return;
    }

    final Optional<ArbiterResponse> midPlayResponse = room.getSession().recordEvent(side, event);

    // recordEvent may find an expired clock before processing the event. Announce that result
    // even when this frame omitted boardState (and therefore would skip the auto-end check).
    if (room.getSession().getState() == GameState.ENDED) {
      checkGameEnded(room);
      return;
    }

    if (midPlayResponse.isPresent()) {
      final ArbiterResponse response = midPlayResponse.get();
      if (response.type() == ArbiterResponseType.POSITION_CHANGE) {
        sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error",
            response.restorePosition().orElse(room.getSession().getPositionBeforeTurn()));
      } else {
        sendArbiterResponse(room, side, response);
      }
      // Passive info for the opponent (face-to-face principle): produced by escalating
      // violations such as a moved opponent piece; rendered below the clock, no action needed.
      final String info = room.getSession().consumePendingOpponentInfo();
      if (info != null) {
        final JsonObject infoMsg = new JsonObject();
        infoMsg.addProperty("type", "opponentInfo");
        infoMsg.addProperty("message", info);
        room.sendToSide(side.getOppositeSide(), GSON.toJson(infoMsg));
      }
    }

    // The on-move player just performed a board event while a draw offer is pending against
    // them. Whether that invalidates the offer depends on how the offer was made:
    // - Correct-time offer (FIDE 9.1.2.1): even a TOUCH loses the right to accept, because
    // the recipient was waiting and any piece interaction commits them to a move.
    // - Wrong-time offer: the recipient was already mid-thinking when the offer arrived;
    // touching pieces while deciding is normal play. The offer is invalidated only when
    // the recipient has actually MADE THE MOVE — i.e. completed a legal release per
    // FIDE 4.7 (released-piece rule).
    final var drawMgr = room.getSession().getDrawOfferManager();
    if (drawMgr.isDrawOffered() && drawMgr.getOfferingSide() != side && !drawMgr.hasOpponentTouchedPiece()) {

      final boolean correctTimeTouchInvalidation = drawMgr.wasOfferedAtCorrectTime() && isTouchPieceEvent(event);
      final boolean wrongTimeReleaseInvalidation = !drawMgr.wasOfferedAtCorrectTime()
          && room.getSession().hasReleasedPieceCommitment();

      if (correctTimeTouchInvalidation) {
        drawMgr.recordOpponentTouchedPiece();
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "drawOfferInvalidated");
        msg.addProperty("message", "The draw offer is no longer valid because you touched a piece.");
        send(conn, msg);
      } else if (wrongTimeReleaseInvalidation) {
        drawMgr.recordOpponentTouchedPiece();
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "drawOfferInvalidated");
        msg.addProperty("message", "The draw offer is no longer valid because you made the move.");
        send(conn, msg);
      }
    }

    // Forward the event to the opponent for real-time board visibility
    forwardBoardEventToOpponent(room, side, eventData);

    // Auto-end on game-ending moves (checkmate, stalemate, dead position, fivefold, 75-move):
    // accept the move and end the game without waiting for a clock press.
    final BitboardPosition autoEndPosition = midPlayResponse.isEmpty() ? optionalBoardState(json) : null;
    if (autoEndPosition != null) {
      final Optional<ArbiterResponse> autoEndResponse = room.getSession().evaluateForAutoEnd(side, autoEndPosition);
      if (autoEndResponse.isPresent()) {
        sendArbiterResponse(room, side, autoEndResponse.get());
        sendBoardMarksIfChanged(room);
        sendClockUpdate(room);
      }
      checkGameEnded(room);
    }
  }

  private void handleClockPress(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final BitboardPosition afterPosition = parseBoardState(conn, json);
    if (afterPosition == null) {
      return; // malformed boardState — parseBoardState already sent a clean error + counted it
    }

    // One room transaction: a press can end the automatic restoration pause, and a reconnecting
    // player must not be sent that pause after the messages that ended it.
    synchronized (room) {
      pressClock(room, room.getSide(conn), afterPosition);
    }
  }

  private void pressClock(GameRoom room, Side side, BitboardPosition afterPosition) {
    // A press that the arbiter does not rule on and has nothing to say to: the player's lever is down (it is not
    // their turn, or the game is over), or the arbiter has just ruled on a press without a move and stopped the
    // clock. Such a press is typically the second click of a double click, sent before the answer to the first
    // arrived - an answer here would take that ruling out of the arbiter's window.
    final GameSession pressedSession = room.getSession();
    if (pressedSession.getState() != GameState.IN_PROGRESS || side != pressedSession.getHavingMove()
        || pressedSession.isPressWithoutMovePause()) {
      checkGameEnded(room);
      return;
    }
    final ArbiterResponse response = room.getSession().pressClockButton(side, afterPosition);
    // First of all, so that the lever is heard before the arbiter speaks.
    if (room.getSession().consumeClockPressRegistered()) {
      sendClockPressHeard(room);
    }
    // The press ended the automatic restoration pause while the turn goes on: announce the resume
    // here, because the delayed resume will find nothing left to do. Sent before the arbiter's
    // answer so that answer stays on top. An accepted move needs no announcement - it hands the
    // turn over.
    final boolean pressWithoutMove = response.type() == ArbiterResponseType.ILLEGAL_MOVE
        && response.illegalMoveDetail().map(d -> d.noMoveMade()).orElse(false);
    final boolean restartsByItself = pressWithoutMove && pressedSession.isPressWithoutMovePause();
    final boolean waitsForReady = pressWithoutMove && pressedSession.isWaitingForReady();
    // A press without a move stops the clock again at once: the resume is then announced when the pause after
    // that ruling is over, not here.
    final boolean endedRestorationDelay = room.getSession().consumeResumedByClockPress();
    if (endedRestorationDelay && !restartsByItself && response.type() != ArbiterResponseType.MOVE_ACCEPTED
        && room.getSession().getState() == GameState.IN_PROGRESS) {
      sendGameResumed(room, room.getSession().isRestoredMoveFinal());
    }
    sendArbiterResponse(room, side, response, restartsByItself ? PAUSED_SHORTLY : "");

    sendOpponentArbiterNotification(room, side, response, restartsByItself ? OPPONENT_PAUSED_SHORTLY : "");
    sendBoardMarksIfChanged(room);

    if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
      sendClockUpdate(room);
      // Note: opponentMoved (sent by sendArbiterResponse) already includes the board state.
      // Do NOT also send boardUpdate here, as it can overwrite the opponent's in-progress moves.
    } else if (response.type() == ArbiterResponseType.ILLEGAL_MOVE) {
      if (pressWithoutMove) {
        // FIDE 7.5.3 press-without-move: the board is still at the turn start - nothing to restore. The arbiter
        // has stopped the clock all the same, as for the other rulings: the clock update shows the pause and the
        // opponent's penalty time, and the game goes on as it does after a restoration.
        sendClockUpdate(room);
        if (restartsByItself) {
          scheduleRestartAfterPressWithoutMove(room, side, response, endedRestorationDelay);
        } else if (waitsForReady) {
          sendWaitingForReady(room, side, response.renderedPlayerMessage() + " " + READY_QUESTION);
        }
      } else {
        sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
      }
    } else if (response.type() == ArbiterResponseType.RELEASED_PIECE_VIOLATION) {
      final BitboardPosition restorePosition = response.restorePosition()
          .orElse(room.getSession().getPositionBeforeTurn());
      if (restorePosition.equals(afterPosition)) {
        room.getSession().continueWithoutRestoration();
        sendClockUpdate(room);
      } else {
        sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error", restorePosition);
      }
    } else if (response.type() == ArbiterResponseType.TOUCH_MOVE_VIOLATION) {
      final BitboardPosition restorePosition = response.restorePosition()
          .orElse(room.getSession().getPositionBeforeTurn());
      if (restorePosition.equals(afterPosition)) {
        room.getSession().continueWithoutRestoration();
        sendClockUpdate(room);
      } else {
        sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error", restorePosition);
      }
    } else if (response.type() == ArbiterResponseType.INCOMPLETE_MOVE && side == room.getSession().getHavingMove()
        && room.getSession().getMustExecuteMove() != null) {
      // A rejected claim's specified move was not carried out: offer a Revert to the start of the
      // turn. The move is still owed afterwards, so the player reverts and then plays it.
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "info");
    }

    checkGameEnded(room);
  }

  private static final String PAUSED_SHORTLY = " The game is paused and will continue shortly.";
  private static final String OPPONENT_PAUSED_SHORTLY = " The game is paused; the clock will restart shortly.";
  private static final String CLOCK_HAS_RESTARTED = " The clock has restarted.";
  private static final String READY_QUESTION = "Are you ready to continue?";

  /**
   * The arbiter restarts the clock after the pause that follows a clock press without a move. Both players keep
   * what they were told about the ruling; only its last sentence changes from the pause to the restart.
   */
  private void scheduleRestartAfterPressWithoutMove(GameRoom room, Side side, ArbiterResponse response,
      boolean boardsAreLocked) {
    final GameSession pausedSession = room.getSession();
    final long pauseId = pausedSession.getPressWithoutMovePauseId();
    maintenance.schedule(() -> {
      try {
        synchronized (room) {
          if (room.getSession() != pausedSession || !pausedSession.resumeAfterPressWithoutMove(pauseId)) {
            return;
          }
          final JsonObject msg = new JsonObject();
          // The press came in the delay after a restoration, where both boards are locked and wait for the
          // resume: this restart is that resume. Otherwise only the last sentence of the ruling changes.
          msg.addProperty("type", boardsAreLocked ? "gameResumed" : "clockRestarted");
          msg.addProperty("message", response.renderedPlayerMessage() + CLOCK_HAS_RESTARTED);
          msg.addProperty("style", "error");
          if (boardsAreLocked) {
            msg.addProperty("havingMove", pausedSession.getHavingMove().name().toLowerCase());
          }
          room.sendToSide(side, GSON.toJson(msg));
          if (boardsAreLocked) {
            msg.addProperty("message", GameSession.CLOCK_RESTARTED);
            msg.remove("style");
            room.sendToSide(side.getOppositeSide(), GSON.toJson(msg));
          }
          response.renderedOpponentMessage().ifPresent(
              message -> sendOpponentInfo(room, side.getOppositeSide(), message + CLOCK_HAS_RESTARTED, false));
          sendClockUpdate(room);
        }
      } catch (final RuntimeException e) {
        System.err.println("[press-without-move-resume] " + room.getGameId() + ": " + e);
      }
    }, wrongClockPauseMs, TimeUnit.MILLISECONDS);
  }

  /** Asks both players whether they are ready; the player the arbiter ruled on keeps the ruling in view. */
  private void sendWaitingForReady(GameRoom room, Side ruledOn, String ruledOnMessage) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "waitingForReady");
    msg.addProperty("message", ruledOnMessage);
    room.sendToSide(ruledOn, GSON.toJson(msg));
    msg.addProperty("message", READY_QUESTION);
    room.sendToSide(ruledOn.getOppositeSide(), GSON.toJson(msg));
  }

  private void handleOfferDraw(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    // One room transaction: whether this offer meets a pending offer of the opponent, and what
    // becomes of that one, must not change between the check and the answer.
    synchronized (room) {
      offerDraw(room, conn, json);
    }
  }

  private void offerDraw(GameRoom room, WebSocket conn, JsonObject json) {
    final Side side = room.getSide(conn);
    final boolean opponentOfferPending = isOpponentOfferPending(room, side);

    // Parse the boardState (used by the correct-time validation path; optional/tolerant here).
    final BitboardPosition afterPosition = optionalBoardState(json);

    // Correct-time vs. wrong-time per FIDE 9.1.2.1:
    // Correct = the offering player has the move AND has actually made a move on the board
    // (the physical position differs from positionBeforeTurn). The offer is then
    // communicated, but the move is only committed when the player presses the clock.
    // Wrong = anything else (not on move, or on move but no move attempted yet). The offer
    // is still valid and forwarded to the opponent, but with an escalating
    // procedural warning. The clock keeps running on whoever's turn it is.
    final boolean hasMove = side == room.getSession().getHavingMove();
    final boolean moveAttempted = hasMove && afterPosition != null
        && !room.getSession().getPositionBeforeTurn().equals(afterPosition);

    // An offer made while the opponent's offer can still be accepted is the acceptance of that
    // offer, whatever else is true of the moment - the session agrees the draw.
    final boolean acceptsOpponentOffer = room.getSession().getDrawOfferManager().canAccept(side);

    if (moveAttempted) {
      handleCorrectTimeDrawOffer(room, conn, side, afterPosition);
    } else if (!hasMove && !acceptsOpponentOffer && room.getSession().hasReleasedPieceCommitment()) {
      // Scenario 2: opponent has already made a legal release (committed move via FIDE 4.7),
      // they are merely waiting to press their clock. The offer is invalid — do NOT register
      // it, do NOT count it toward the wrong-time penalty, and do NOT forward it to the
      // committed opponent. Just inform the offerer.
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "wrongTimeDrawOffer");
      msg.addProperty("message", "The draw offer is not valid because your opponent has"
          + " already made a move and is about to press the clock.");
      send(conn, msg);
    } else {
      handleWrongTimeDrawOffer(room, conn, side);
    }

    closeOutdatedOfferPanel(room, conn, side, opponentOfferPending);

    checkGameEnded(room);
  }

  private static boolean isOpponentOfferPending(GameRoom room, Side side) {
    final var drawMgr = room.getSession().getDrawOfferManager();
    return drawMgr.isDrawOffered() && drawMgr.getOfferingSide() != side;
  }

  /**
   * The opponent's offer was still on this player's screen although it could no longer be accepted, and an offer of
   * their own - spoken, or implied by a rejected draw claim - has now taken its place: close the panel, so that its
   * buttons cannot be pressed against the player's own offer.
   */
  private void closeOutdatedOfferPanel(GameRoom room, WebSocket conn, Side side, boolean opponentOfferWasPending) {
    final var drawMgr = room.getSession().getDrawOfferManager();
    if (opponentOfferWasPending && drawMgr.isDrawOffered() && drawMgr.getOfferingSide() == side) {
      final JsonObject expired = new JsonObject();
      expired.addProperty("type", "drawOfferExpired");
      send(conn, expired);
    }
  }

  private void handleCorrectTimeDrawOffer(GameRoom room, WebSocket conn, Side side, BitboardPosition afterPosition) {
    final ArbiterResponse response = room.getSession().offerDrawCorrectTime(side, afterPosition);

    if (response.type() == ArbiterResponseType.INCOMPLETE_MOVE
        && room.getSession().getState() == GameState.ENDED) {
      // The offer ended the game: it accepted the opponent's pending offer, or it met a fallen
      // flag. The game-end message says everything.
      return;
    }
    if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
      // Move is valid and the offer has been registered. Acknowledge to the offering player
      // and forward the offer to the opponent. The acknowledgment is bare — no reminder to
      // press the clock — per design-principles P-003 (board never gives procedural
      // instructions). If the offerer forgets to press the clock, their own time keeps
      // running while the opponent considers the offer; that consequence is part of the rules.
      // Crucially: NO sendClockUpdate — the clock stays on the offering player.
      sendDrawOfferToOpponent(room, side);
      final JsonObject ack = new JsonObject();
      ack.addProperty("type", "drawOfferSent");
      ack.addProperty("message", "Draw offer sent.");
      send(conn, ack);
      return;
    }
    if (response.type() == ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST) {
      // The illegal move that reached the limit, or the repeated-offer game-lost penalty. The
      // session has already ended the game.
      sendArbiterResponse(room, side, response);
      sendBoardMarksIfChanged(room);
      return;
    }

    // Move was invalid (or repeated-offer info/warning). Apply the standard arbiter
    // intervention — for the move-validity violations, this also drives the restoration flow.
    // The move shown with the offer is circled like one shown with a clock press.
    sendArbiterResponse(room, side, response);
    sendBoardMarksIfChanged(room);
    if (response.type() == ArbiterResponseType.ILLEGAL_MOVE) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
    } else if (response.type() == ArbiterResponseType.TOUCH_MOVE_VIOLATION) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error",
          response.restorePosition().orElse(room.getSession().getPositionBeforeTurn()));
    } else if (response.type() == ArbiterResponseType.RELEASED_PIECE_VIOLATION) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error",
          response.restorePosition().orElse(room.getSession().getPositionBeforeTurn()));
    }
    // INCOMPLETE_MOVE / repeated-offer warning — message has already been sent. An offer that is
    // not valid because a draw cannot be agreed yet is also made known to the opponent.
    room.getSession().consumeEarlyOfferOpponentInfo()
        .ifPresent(info -> sendOpponentInfo(room, side.getOppositeSide(), info, false));
  }

  /** Passive information about what the other player did, for the info window below the clock. */
  private void sendOpponentInfo(GameRoom room, Side recipient, String message, boolean clearArbiterMessage) {
    final JsonObject info = new JsonObject();
    info.addProperty("type", "opponentInfo");
    info.addProperty("message", message);
    if (clearArbiterMessage) {
      info.addProperty("clearArbiterMessage", true);
    }
    room.sendToSide(recipient, GSON.toJson(info));
  }

  private void handleWrongTimeDrawOffer(GameRoom room, WebSocket conn, Side side) {
    final var result = room.getSession().offerDrawWrongTime(side);

    if (result.gameLost()) {
      // Third wrong-time offer on this move: the session ended the game — the personalised
      // messages travel via gameEnded (actor).
      checkGameEnded(room);
      return;
    }

    if (result.arbiterMessage() != null) {
      final JsonObject arbiterMsg = new JsonObject();
      arbiterMsg.addProperty("type", result.isWrongTime() ? "wrongTimeDrawOffer" : "repeatedDrawOffer");
      arbiterMsg.addProperty("message", result.arbiterMessage());
      send(conn, arbiterMsg);
      // Note: we no longer stop the clock or enter the ready-to-continue handshake here.
      // Per FIDE the offer is informational and the clock keeps running on whoever has the move.
    }

    // An offer that was not considered or is not valid: the opponent sees what happened passively
    // (info window).
    if (result.opponentInfo() != null) {
      sendOpponentInfo(room, side.getOppositeSide(), result.opponentInfo(), result.clearOpponentArbiterMessage());
    }

    // Forward the offer to the opponent — only a REAL registered offer (the first wrong-time
    // offer of the move); a not-considered repeat or a duplicate from the same side is not.
    if (result.accepted()) {
      sendDrawOfferToOpponent(room, side);
    }
  }

  private void sendDrawOfferToOpponent(GameRoom room, Side offeringSide) {
    sendDrawOfferToOpponent(room, offeringSide, "Your opponent offers a draw.");
  }

  /**
   * Variant with a custom message — used when a rejected draw claim converts into a draw offer (FIDE 9.5), so the
   * opponent sees what actually happened ("your opponent claimed … not valid … still counts as a draw offer") instead
   * of a bare "your opponent offers a draw".
   */
  private void sendDrawOfferToOpponent(GameRoom room, Side offeringSide, String message) {
    room.setDrawOfferMessage(message);
    final JsonObject drawMsg = new JsonObject();
    drawMsg.addProperty("type", "drawOffered");
    drawMsg.addProperty("message", message);
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
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "drawAcceptRejected");
        msg.addProperty("message", rejection);
        send(conn, msg);
      }
    }
    checkGameEnded(room);
  }

  private void handleRejectDraw(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final String offererRejectionMessage = room.getSession().rejectDraw(side);
    final String rejection = room.getSession().getLastRejectDrawRejection();
    if (rejection != null) {
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "drawAcceptRejected");
      msg.addProperty("message", rejection);
      send(conn, msg);
      return;
    }

    // Personalised per player so it is unambiguous who rejected.
    final JsonObject toRejecter = new JsonObject();
    toRejecter.addProperty("type", "drawRejected");
    toRejecter.addProperty("message", "You rejected the draw offer.");
    room.sendToSide(side, GSON.toJson(toRejecter));

    final JsonObject toOfferer = new JsonObject();
    toOfferer.addProperty("type", "drawRejected");
    toOfferer.addProperty("message", offererRejectionMessage);
    room.sendToSide(side.getOppositeSide(), GSON.toJson(toOfferer));
  }

  private void handleClaimDraw(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final String claimTypeStr = optString(json, "claimType");
    DrawClaimType claimType = null;
    if (claimTypeStr != null) {
      try {
        claimType = DrawClaimType.valueOf(claimTypeStr);
      } catch (final IllegalArgumentException ignored) {
        claimType = null;
      }
    }
    if (claimType == null) {
      sendError(conn, "Malformed claimDraw: invalid 'claimType'.");
      recordViolation(conn);
      return;
    }
    final String san = optString(json, "san");

    // One room transaction, like a spoken offer: a rejected claim is an offer too (FIDE 9.5).
    synchronized (room) {
      final boolean opponentOfferPending = isOpponentOfferPending(room, side);
      final DrawClaimResult result = room.getSession().claimDraw(side, claimType, san);
      closeOutdatedOfferPanel(room, conn, side, opponentOfferPending);
      sendDrawClaimResult(room, conn, side, result);
    }
  }

  private void handleCancelDrawClaim(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final DrawClaimResult result = room.getSession().retractDrawClaim(side);
    sendDrawClaimResult(room, conn, side, result);
  }

  private void sendDrawClaimResult(GameRoom room, WebSocket conn, Side side, DrawClaimResult result) {
    // Per-player feedback for the claim event itself (claimer's arbiter panel).
    final JsonObject response = new JsonObject();
    response.addProperty("type", "drawClaimResult");
    response.addProperty("accepted", result.accepted());
    response.addProperty("message", result.message());
    response.addProperty("invalidMove", result.invalidMove());
    response.addProperty("wrongTime", result.wrongTime());
    response.addProperty("repeatClaim", result.repeatClaim());
    if (result.moveToPerform().isPresent()) {
      response.addProperty("mustExecuteMove", result.moveToPerform().get().toString());
    }
    send(conn, response);

    // FIDE 9.5: a rejected claim is treated as a draw offer to the opponent. The session already
    // registered the offer; broadcast it with the claim-specific text ("claimed … not valid …
    // still counts as a draw offer. Do you accept?") so the opponent gets ONE accurate message
    // together with the Accept/Reject panel — not a claim notification overwritten by a bare
    // "your opponent offers a draw".
    if (result.convertsToDrawOffer() && room.getSession().getState() == GameState.ENDED) {
      // The implied offer met the opponent's own pending offer: drawn by agreement, nothing to forward.
    } else if (result.convertsToDrawOffer()) {
      sendDrawOfferToOpponent(room, side,
          result.opponentMessage().orElse("Your opponent offers a draw."));
    } else if (result.opponentMessage().isPresent()) {
      // Non-converting outcomes (accepted claim, game-ending violation, …): plain notification.
      final JsonObject opponentMsg = new JsonObject();
      opponentMsg.addProperty("type", "drawClaimOpponent");
      opponentMsg.addProperty("message", result.opponentMessage().get());
      room.sendToSide(side.getOppositeSide(), GSON.toJson(opponentMsg));
    }

    // Passive information for the opponent (face-to-face principle: at a real board they would
    // see the claim happen). Rendered in the info window below the clock — visible, but
    // requiring NO action — never in the standard arbiter window.
    if (result.opponentInfo().isPresent()) {
      final JsonObject info = new JsonObject();
      info.addProperty("type", "opponentInfo");
      info.addProperty("message", result.opponentInfo().get());
      room.sendToSide(side.getOppositeSide(), GSON.toJson(info));
    }

    checkGameEnded(room);
  }

  private void handleResign(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final GameResult result = room.getSession().resign(side);
    if (result == null) {
      sendError(conn, "The game has not started.");
      return;
    }
    sendGameEnded(room, result);
  }

  /**
   * "Claim victory" while the opponent is disconnected: ends the game immediately with the SAME adjudication the
   * automatic abandonment would apply at the deadline — the leaver loses, unless the claimer has no possible mate
   * (then it is a draw). Guarded so it works only once the opponent has been gone past the disconnect grace (the
   * moment the client shows the button); a reconnected opponent makes the claim fail.
   */
  private void handleClaimVictory(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }
    final Side side = room.getSide(conn);
    if (side == Side.NONE) {
      return;
    }
    final Side opponent = side.getOppositeSide();
    final WebSocket opponentSocket = room.getSocket(opponent);
    final Long disconnectedAt = room.getDisconnectedAt(opponent);
    if (opponentSocket != null && opponentSocket.isOpen() || disconnectedAt == null
        || System.currentTimeMillis() - disconnectedAt < disconnectGraceMs) {
      sendError(conn, "Victory cannot be claimed - your opponent is not gone.");
      return;
    }
    final GameResult result = room.getSession().abandon(opponent);
    if (result == null) {
      if (room.getSession().getState() == GameState.ENDED) {
        // The claim met a clock that had run out before the periodic check saw it: the game ended
        // on time, and that result is the answer to the claim.
        checkGameEnded(room);
        return;
      }
      sendError(conn, "There is nothing to claim - the game is not running.");
      return;
    }
    sendGameEnded(room, result);
    System.out.println("Game " + room.getGameId() + " adjudicated after victory claim by "
        + side.name().toLowerCase());
  }

  /**
   * Rematch handshake (Lichess-style): after the game has ended, either player may offer a rematch. The first offer
   * makes the opponent's Rematch button blink; when the opponent presses THEIR button too (= accepting), a new game
   * starts in the same room — same time control and settings, same starting position, colours swapped.
   */
  private void handleRematchOffer(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }
    // The whole handshake is guarded per room: two simultaneous offers must resolve to
    // offer-then-accept, never to two dangling offers.
    synchronized (room) {
      if (room.getSession().getState() != GameState.ENDED) {
        sendError(conn, "A rematch can only be offered after the game has ended.");
        return;
      }
      if (room.getSession().getResult() != null
          && room.getSession().getResult().type() == GameResultType.ABANDONMENT) {
        // The opponent left the game — there is nobody to accept. The client hides the Rematch
        // button for this ending; this guard covers hand-crafted messages.
        sendError(conn, "A rematch is not available - your opponent left the game.");
        return;
      }
      final Side side = room.getSide(conn);
      if (side == Side.NONE) {
        return;
      }
      if (!room.isConnected(side.getOppositeSide())) {
        room.setRematchOfferedBy(Side.NONE);
        sendRematchUnavailable(conn, "A rematch is no longer available - your opponent left the game.");
        return;
      }
      final Side offeredBy = room.getRematchOfferedBy();
      if (offeredBy == side) {
        return; // repeated click on an already-sent offer — idempotent
      }
      if (offeredBy == Side.NONE) {
        room.setRematchOfferedBy(side);
        final JsonObject ack = new JsonObject();
        ack.addProperty("type", "rematchOfferSent");
        ack.addProperty("message", "Rematch offer sent.");
        send(conn, ack);
        final JsonObject offer = new JsonObject();
        offer.addProperty("type", "rematchOffered");
        offer.addProperty("message", "Your opponent offers a rematch.");
        room.sendToSide(side.getOppositeSide(), GSON.toJson(offer));
        return;
      }
      if (!room.isConnected(offeredBy)) {
        room.setRematchOfferedBy(Side.NONE);
        sendRematchUnavailable(conn, "A rematch is no longer available - your opponent left the game.");
        return;
      }
      // The other side had already offered — this press accepts: start the rematch.
      startRematch(room);
    }
  }

  /** Starts the accepted rematch: colours swapped, fresh session, reconnect tokens following their players. */
  private void startRematch(GameRoom room) {
    room.stopClockTicker();
    room.startRematch(); // swaps seats, fresh session from the original starting position

    final var session = room.getSession();
    final var havingMove = session.getHavingMove();
    final var board = GSON.toJsonTree(MessageConverter.fromStaticPosition(session.getBoard().getBitboardPosition()));

    // Tokens follow the players across the seat swap, including when this message cannot be delivered.
    for (final Side seat : new Side[] { Side.WHITE, Side.BLACK }) {
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "rematchStarted");
      msg.addProperty("gameId", room.getGameId());
      msg.addProperty("side", seat.name().toLowerCase());
      msg.addProperty("token", room.getToken(seat));
      msg.add("board", board);
      msg.addProperty("havingMove", havingMove.name().toLowerCase());
      msg.addProperty("timeControlLabel", room.getTimeControl().displayLabel());
      // Short, like the game-start message: no "your turn" coaching — a running clock says it all.
      final String clockLine = havingMove == seat ? "Your clock has been started."
          : "Opponent's clock has been started.";
      msg.addProperty("message",
          "Rematch started - you now play " + (seat == Side.WHITE ? "White" : "Black") + ". " + clockLine);
      room.sendToSide(seat, GSON.toJson(msg));
    }

    // A rematch is a new game — same two events as create + join.
    usageLog.record(UsageLog.EVENT_CREATE, room.getGameId());
    usageLog.record(UsageLog.EVENT_JOIN, room.getGameId());

    session.startGame();
    room.startClockTicker(clockExecutor, () -> tickClock(room));
    sendClockUpdate(room);
    System.out.println("Rematch started: " + room.getGameId());
  }

  /**
   * Aborts a game that has not started yet (no opponent has joined). Like cancelling a Lichess challenge: the creator
   * can throw the game away and start a new one while still alone in the room. Once the opponent has joined the game
   * can only be ended by resignation / play, so an abort attempt then is rejected. Colour-agnostic, so it works for a
   * Black creator too.
   */
  private void handleAbort(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }
    // One room transaction with joinRoom: either the abort removes the room before a joiner can
    // take the seat, or the join starts the game and the abort is refused - never both.
    synchronized (room) {
      if (room.isFull() || room.getSession().getState() != GameState.WAITING_FOR_PLAYERS) {
        sendError(conn, "The game cannot be aborted after it has started.");
        return;
      }

      room.stopClockTicker();
      gameRooms.remove(room.getGameId(), room);
      playerGameMap.remove(conn);
    }

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "gameAborted");
    msg.addProperty("message", "Game aborted.");
    send(conn, msg);
    System.out.println("Game aborted: " + room.getGameId());
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
    send(conn, response);
  }

  private void handleRestorePosition(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    // Revert belongs to the player who has to restore, and only while the arbiter asks for it.
    // Anything else - the opponent, a repeated click, a modified client - is dropped silently.
    if (room.getSide(conn) != room.getSession().getHavingMove()) {
      return;
    }
    completeRestoration(room, room.getSession().getRestorePosition());
  }

  private void completeRestoration(GameRoom room) {
    completeRestoration(room, room.getSession().getRestorePosition());
  }

  private void completeRestoration(GameRoom room, BitboardPosition restorePosition) {
    // Capture before completeRestoration() in case the latch is ever cleared there in future.
    final boolean releasedMoveIsFinal = room.getSession().isRestoredMoveFinal();
    if (!room.getSession().completeRestoration()) {
      return; // no restoration is pending (any more)
    }
    room.setPhysicalPosition(restorePosition);

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "positionRestored");
    msg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(restorePosition)));
    msg.addProperty("autoResumePending", room.getSession().isAutoResumeAfterRestore());
    if (releasedMoveIsFinal) {
      // The released piece completed the move (FIDE 4.7): nothing more to play, just press the clock.
      // This guidance is ONLY for the player on move (the committer); the opponent just sees the restore.
      final Side mover = room.getSession().getHavingMove();
      msg.addProperty("message", "Position restored.");
      room.sendToSide(mover.getOppositeSide(), GSON.toJson(msg));
      msg.addProperty("message", "Position restored — your move is final. Press the clock to continue.");
      room.sendToSide(mover, GSON.toJson(msg));
    } else {
      msg.addProperty("message", room.getSession().isAutoResumeAfterRestore()
          ? "Position restored. Restarting the clock shortly. Please be ready."
          : "Position restored. Are you ready to continue?");
      room.sendToBoth(GSON.toJson(msg));
    }
    sendBoardMarksIfChanged(room);

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
    synchronized (room) {
      final boolean releasedMoveIsFinal = room.getSession().isRestoredMoveFinal();
      if (!room.getSession().resumeAfterRestorationDelay()) {
        return;
      }
      sendGameResumed(room, releasedMoveIsFinal);
    }
  }

  private void sendGameResumed(GameRoom room, boolean releasedMoveIsFinal) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "gameResumed");
    msg.addProperty("havingMove", room.getSession().getHavingMove().name().toLowerCase());
    if (releasedMoveIsFinal) {
      // "Your move is final" is only for the committer (the side to move); the opponent just waits.
      final Side mover = room.getSession().getHavingMove();
      msg.addProperty("message", GameSession.CLOCK_RESTARTED);
      room.sendToSide(mover.getOppositeSide(), GSON.toJson(msg));
      msg.addProperty("message", "Clock restarted. Your move is final — press the clock to continue.");
      room.sendToSide(mover, GSON.toJson(msg));
    } else {
      msg.addProperty("message", GameSession.CLOCK_RESTARTED);
      room.sendToBoth(GSON.toJson(msg));
    }
    sendClockUpdate(room);
  }

  private void handleReadyToContinue(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    // The waiting reply must be sent before another confirmation can announce the resume.
    synchronized (room) {
      final Side side = room.getSide(conn);
      if (side == Side.NONE || !room.getSession().isWaitingForReady()) {
        return; // Ignore duplicate/late confirmations rather than disabling an already-live board.
      }
      sendReadyResponse(room, conn, side);
    }
  }

  private void sendReadyResponse(GameRoom room, WebSocket conn, Side side) {
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
      send(conn, msg);
    }
  }

  /**
   * The player pressed their OPPONENT's clock lever — possible on a physical clock, so it is modeled. The arbiter
   * escalates (see GameSession.pressOpponentClock): pause + admonishment, pause + warning, then loss of the game on
   * the third press. After the admonishment pause ({@code OTB_WRONG_CLOCK_PAUSE_MS}) the interrupted clock restarts.
   * Presses while the opponent's lever is already down are physical no-ops — silence, like the real thing.
   */
  /**
   * A clock lever that was up was pressed down. Both players hear it, each on their own board; the message says
   * nothing about the rules and is not repeated to a player who was away when it happened. It is the only source of
   * the clock sound: the other messages that can follow a press - a ruling, a resumed game, a clock update - also
   * arrive without one.
   */
  private void sendClockPressHeard(GameRoom room) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "clockPressHeard");
    room.sendToBoth(GSON.toJson(msg));
  }

  private void handleOpponentClockPressed(WebSocket conn) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }
    final Side side = room.getSide(conn);
    if (side == Side.NONE) {
      return;
    }

    final var pausedSession = room.getSession();
    final var outcome = pausedSession.pressOpponentClock(side);
    if (!outcome.offense()) {
      // Lever was already down — nothing happened, nothing to say. Unless the press met an expired clock.
      checkGameEnded(room);
      return;
    }
    // The lever was up and went down: heard on both boards, also when this press ends the game.
    sendClockPressHeard(room);

    if (outcome.gameLost()) {
      // Third press: the game is over. The personalised messages travel via gameEnded (actor).
      sendGameEnded(room, room.getSession().getResult());
      return;
    }

    // Admonishment (first press) or warning (second): offender hears the arbiter, the opponent
    // sees what happened passively; the PAUSE shows on both clocks via the clock update.
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "opponentClockPressed");
    msg.addProperty("message", outcome.message());
    send(conn, msg);

    final JsonObject info = new JsonObject();
    info.addProperty("type", "opponentInfo");
    info.addProperty("message", outcome.opponentInfo());
    room.sendToSide(side.getOppositeSide(), GSON.toJson(info));

    sendClockUpdate(room);

    // The arbiter restarts the interrupted clock after the pause (no-op if the game ended or
    // another intervention took over meanwhile).
    maintenance.schedule(() -> {
      try {
        synchronized (room) {
          if (room.getSession() == pausedSession) {
            final Side restarted = pausedSession.resumeAfterWrongClockPress(outcome.pauseId());
            if (restarted != null) {
              sendClockRestarted(room, side, outcome, pausedSession.consumeToldToWaitForClock(restarted));
              sendClockUpdate(room);
            }
          }
        }
      } catch (final RuntimeException e) {
        System.err.println("[wrong-clock-resume] " + room.getGameId() + ": " + e);
      }
    }, wrongClockPauseMs, TimeUnit.MILLISECONDS);
  }

  /**
   * The admonishment pause is over: both players were told that the clock would restart, so both are told that it
   * has. The offender's arbiter window held the admonishment; a warning that still applies is repeated there. The
   * opponent's passive info is brought up to date, and the arbiter speaks to the opponent only when it had told them
   * to wait during the pause - an instruction standing in that window otherwise (a touch-move ruling) stays.
   */
  private void sendClockRestarted(GameRoom room, Side offender, GameSession.WrongClockPressOutcome outcome,
      boolean opponentWasToldToWait) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "clockRestarted");
    msg.addProperty("message", outcome.restartMessage());
    if (outcome.warned()) {
      msg.addProperty("style", "error");
    }
    room.sendToSide(offender, GSON.toJson(msg));

    final Side opponent = offender.getOppositeSide();
    final JsonObject info = new JsonObject();
    info.addProperty("type", "opponentInfo");
    info.addProperty("message", outcome.opponentRestartInfo());
    room.sendToSide(opponent, GSON.toJson(info));

    if (opponentWasToldToWait) {
      final JsonObject restarted = new JsonObject();
      restarted.addProperty("type", "clockRestarted");
      restarted.addProperty("message", GameSession.CLOCK_RESTARTED);
      room.sendToSide(opponent, GSON.toJson(restarted));
    }
  }

  /**
   * Sends both boards the squares the arbiter points at, when they changed: after a ruling on a clock press, after
   * the restoration that takes an illegal move off the board, and when an accepted move ends the turn.
   */
  private void sendBoardMarksIfChanged(GameRoom room) {
    room.takeChangedBoardMarks().ifPresent(marks -> room.sendToBoth(GSON.toJson(boardMarksMessage(marks))));
  }

  private static JsonObject boardMarksMessage(List<BoardMark> marks) {
    final JsonArray squares = new JsonArray();
    for (final BoardMark mark : marks) {
      final JsonObject square = new JsonObject();
      square.addProperty("square", mark.square().getName());
      square.addProperty("color", mark.color().name().toLowerCase());
      squares.add(square);
    }
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "boardMarks");
    msg.add("marks", squares);
    return msg;
  }

  private void sendRestoreInstructions(GameRoom room, Side side) {
    sendRestoreInstructions(room, side, "Please restore the position to the beginning of the move.", "info");
  }

  private void sendRestoreInstructions(GameRoom room, Side side, String message, String style) {
    sendRestoreInstructions(room, side, message, style, room.getSession().getPositionBeforeTurn());
  }

  private void sendRestoreInstructions(GameRoom room, Side side, String message, String style,
      BitboardPosition restorePosition) {
    if (room.getSession().getState() == GameState.ENDED) {
      return; // the action that asked for the restore also ended the game: the result is the answer
    }
    room.getSession().enterWaitingForRestoration(restorePosition);
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "restoreRequired");
    msg.addProperty("message", message);
    msg.addProperty("style", style);
    room.sendToSide(side, GSON.toJson(msg));
  }

  private void sendOpponentArbiterNotification(GameRoom room, Side side, ArbiterResponse response) {
    sendOpponentArbiterNotification(room, side, response, "");
  }

  private void sendOpponentArbiterNotification(GameRoom room, Side side, ArbiterResponse response, String suffix) {
    final Optional<String> opponentMessage = response.renderedOpponentMessage().map(message -> message + suffix);
    if (opponentMessage.isEmpty()) {
      return;
    }
    if (isPassiveOpponentNotification(response.type())) {
      final JsonObject info = new JsonObject();
      info.addProperty("type", "opponentInfo");
      info.addProperty("message", opponentMessage.get());
      room.sendToSide(side.getOppositeSide(), GSON.toJson(info));
      return;
    }
    final JsonObject opponentMsg = new JsonObject();
    opponentMsg.addProperty("type", response.type().name().toLowerCase());
    opponentMsg.addProperty("message", opponentMessage.get());
    opponentMsg.addProperty("style", response.style());
    room.sendToSide(side.getOppositeSide(), GSON.toJson(opponentMsg));
  }

  private static boolean isPassiveOpponentNotification(ArbiterResponseType type) {
    return switch (type) {
      case ILLEGAL_MOVE, TOUCH_MOVE_VIOLATION, RELEASED_PIECE_VIOLATION, INCOMPLETE_MOVE -> true;
      default -> false;
    };
  }

  private void forwardBoardEventToOpponent(GameRoom room, Side side, JsonObject eventData) {
    final JsonObject forwardMsg = new JsonObject();
    forwardMsg.addProperty("type", "opponentBoardEvent");
    forwardMsg.add("event", eventData);
    room.sendToSide(side.getOppositeSide(), GSON.toJson(forwardMsg));
  }

  private void handleKeepalive(WebSocket conn) {
    // App-level heartbeat: keeps the WebSocket from being idled out by Cloudflare while a creator
    // waits for an opponent. Reply so traffic flows both ways; never counts as a violation.
    final JsonObject pong = new JsonObject();
    pong.addProperty("type", "pong");
    send(conn, pong);
  }

  /**
   * Re-attaches a reconnecting player to its seat and resends the authoritative game state. The client presents the
   * secret token it received on create/join (not the guessable join code), so a third party can't seize a seat.
   */
  private void handleResume(WebSocket conn, JsonObject json) {
    final String gameId = optString(json, "gameId");
    final String token = optString(json, "token");
    if (gameId == null || token == null) {
      sendError(conn, "resume requires gameId and token.");
      recordViolation(conn);
      return;
    }
    final GameRoom room = gameRooms.get(gameId);
    if (room == null) {
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "resumeFailed");
      msg.addProperty("message", "This game is no longer available — it may have ended or expired.");
      send(conn, msg);
      return;
    }
    // Validate and attach under the same lock as rematches and room removal. A reconnect waiting
    // for that lock may belong to a player whose colour changed, or to a game that has since ended.
    synchronized (room) {
      final Side side = room.sideForToken(token);
      if (gameRooms.get(gameId) != room || side == Side.NONE) {
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "resumeFailed");
        msg.addProperty("message", "This game is no longer available — it may have ended or expired.");
        send(conn, msg);
        return;
      }
      if (room.getSession().getState() == GameState.ENDED) {
        // A finished game cannot be resumed; clear the returning client's stale saved session.
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "resumeFailed");
        msg.addProperty("message", "This game has already ended.");
        send(conn, msg);
        return;
      }
      resumeSeat(conn, gameId, room, side);
    }
  }

  private void resumeSeat(WebSocket conn, String gameId, GameRoom room, Side side) {
    // A seat has one connection. If the previous socket is still open - the same player opened the
    // game in another window - it leaves the game here and is told so; otherwise it would keep
    // acting in a game in which it holds no seat.
    final WebSocket previous = room.getSocket(side);
    if (previous != null && previous != conn) {
      playerGameMap.remove(previous, gameId);
      final JsonObject replaced = new JsonObject();
      replaced.addProperty("type", "sessionReplaced");
      replaced.addProperty("message", "This game is now open in another window. This window no longer takes part.");
      send(previous, replaced);
    }
    // Swap the dropped socket for the new one and resend current state.
    room.setSocket(side, conn);
    room.setDisconnectedAt(side, null);
    playerGameMap.put(conn, gameId);

    // Tell the opponent the player is back — clears their disconnect countdown / Claim-victory
    // button (the pending disconnect timers defuse themselves via the socket-identity check).
    final JsonObject back = new JsonObject();
    back.addProperty("type", "opponentReconnected");
    back.addProperty("message", "Your opponent has reconnected.");
    room.sendToSide(side.getOppositeSide(), GSON.toJson(back));

    final var session = room.getSession();
    final var clock = session.getClock();
    final JsonObject clockData = new JsonObject();
    clockData.addProperty("whiteTimeMs", clock.getRemainingTimeMs(Side.WHITE));
    clockData.addProperty("blackTimeMs", clock.getRemainingTimeMs(Side.BLACK));
    clockData.addProperty("running", clock.getRunningFor().name().toLowerCase());

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "resync");
    msg.addProperty("gameId", gameId);
    msg.addProperty("side", side.name().toLowerCase());
    msg.addProperty("token", room.getToken(side));
    msg.addProperty("state", session.getState().name());
    msg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(room.getPhysicalPosition())));
    msg.addProperty("havingMove", session.getHavingMove().name().toLowerCase());
    msg.add("clock", clockData);
    msg.addProperty("timeControlLabel", room.getTimeControl().displayLabel());
    send(conn, msg);

    // The circles of the arbiter's last ruling are part of what the board shows: a page that comes
    // back has none, so it is sent the current ones.
    if (!session.getBoardMarks().isEmpty()) {
      room.sendToSide(side, GSON.toJson(boardMarksMessage(session.getBoardMarks())));
    }
    sendRecoveryState(room, side, true);
    sendRecoveryState(room, side.getOppositeSide(), false);

    // Replay the existing offer, without registering it again or resetting its touch/release
    // invalidation. Like the live UI, keep an invalidated offer visible until acted on.
    final var drawOffer = session.getDrawOfferManager();
    if (drawOffer.isDrawOffered() && side == drawOffer.getOfferingSide().getOppositeSide()) {
      sendDrawOfferToOpponent(room, drawOffer.getOfferingSide(), room.getDrawOfferMessage());
    }

    sendOpponentAbsence(room, side);

    System.out.println("Resumed " + side.name().toLowerCase() + " in game " + gameId);
  }

  // ===== Helper methods =====

  private void sendRecoveryState(GameRoom room, Side side, boolean reconnecting) {
    final var session = room.getSession();
    final JsonObject msg = new JsonObject();
    if (session.isWaitingForRestoration() && side == session.getHavingMove()) {
      msg.addProperty("type", "restoreRequired");
      msg.addProperty("message", "Please restore the position before continuing.");
    } else if (session.isWaitingForReady()) {
      final boolean ready = session.isReady(side);
      msg.addProperty("type", ready ? "waitingForOpponentReady" : "waitingForReady");
      msg.addProperty("message", ready ? "Waiting for your opponent to be ready..." : "Are you ready to continue?");
    } else if (reconnecting && session.isRestorationResumePending()) {
      // Inside the short pause before the clock restarts by itself. The server takes no board
      // events until then, so the board must stay locked; the pending gameResumed unlocks it.
      msg.addProperty("type", "positionRestored");
      msg.addProperty("autoResumePending", true);
      msg.addProperty("message", "Position restored. Restarting the clock shortly. Please be ready.");
    } else {
      return;
    }
    room.sendToSide(side, GSON.toJson(msg));
  }

  /** Per-connection state for app-level abuse throttling (carried via {@link WebSocket#getAttachment()}). */
  private static final class ConnectionState {
    private int violations;
  }

  /**
   * Records one invalid/malformed request against the connection and closes it once it exceeds the per-connection
   * violation budget — app-level rate limiting that Cloudflare cannot do (it can't inspect post-upgrade frames).
   *
   * @return {@code true} if the connection was closed
   */
  private boolean recordViolation(WebSocket conn) {
    if (!(conn.getAttachment() instanceof ConnectionState state)) {
      return false;
    }
    if (++state.violations > maxViolationsPerConn) {
      System.out.println(
          "Closing connection " + conn.getRemoteSocketAddress() + " after " + state.violations + " invalid requests");
      conn.close(CloseFrame.POLICY_VALIDATION, "Too many invalid requests");
      return true;
    }
    return false;
  }

  /**
   * Whether a WebSocket {@code Origin} is permitted. A missing/blank Origin is allowed (non-browser clients omit it);
   * loopback hosts are always allowed for local dev; otherwise the lowercased origin must be in the allowlist.
   */
  static boolean isOriginAllowed(String origin, Set<String> allowedExact) {
    if (origin == null || origin.isBlank()) {
      return true;
    }
    final String normalized = origin.trim().toLowerCase(Locale.ROOT);
    try {
      final String host = URI.create(normalized).getHost();
      if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
        return true;
      }
    } catch (final RuntimeException ignored) {
      // Unparseable Origin: fall through to exact match (fails closed unless explicitly allowlisted).
    }
    return allowedExact.contains(normalized);
  }

  private static Set<String> parseAllowedOrigins() {
    final Set<String> set = new HashSet<>();
    set.add("https://play.otb-chess.app"); // production beta host (loopback handled separately)
    for (final String o : OtbChessServer.envStr("OTB_WS_ALLOWED_ORIGINS", "").split(",")) {
      final String trimmed = o.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.isEmpty()) {
        set.add(trimmed);
      }
    }
    return set;
  }

  private static String optString(JsonObject json, String key) {
    return (json.has(key) && json.get(key).isJsonPrimitive()) ? json.get(key).getAsString() : null;
  }

  private static JsonObject optObject(JsonObject json, String key) {
    return (json.has(key) && json.get(key).isJsonObject()) ? json.getAsJsonObject(key) : null;
  }

  /**
   * Parses the required {@code boardState} object into a position, or returns {@code null} after sending a clean
   * validation error and counting a violation — so a malformed frame becomes a tracked validation failure rather than
   * an internal error / crash.
   */
  private BitboardPosition parseBoardState(WebSocket conn, JsonObject json) {
    final JsonObject boardState = optObject(json, "boardState");
    if (boardState == null) {
      sendError(conn, "Malformed message: missing 'boardState'.");
      recordViolation(conn);
      return null;
    }
    try {
      @SuppressWarnings("unchecked") final Map<String, String> map = GSON.fromJson(boardState, Map.class);
      return MessageConverter.toStaticPosition(map);
    } catch (final RuntimeException e) {
      sendError(conn, "Malformed boardState.");
      recordViolation(conn);
      return null;
    }
  }

  /** Parses an OPTIONAL {@code boardState}; returns {@code null} if absent or malformed (the field is optional). */
  private BitboardPosition optionalBoardState(JsonObject json) {
    final JsonObject boardState = optObject(json, "boardState");
    if (boardState == null) {
      return null;
    }
    try {
      @SuppressWarnings("unchecked") final Map<String, String> map = GSON.fromJson(boardState, Map.class);
      return MessageConverter.toStaticPosition(map);
    } catch (final RuntimeException e) {
      return null;
    }
  }

  private static Long optLong(JsonObject json, String key) {
    if (!json.has(key) || !json.get(key).isJsonPrimitive()) {
      return null;
    }
    try {
      return json.get(key).getAsLong();
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  /** Generates a fresh {@value #JOIN_CODE_LENGTH}-character base32 join code from the CSPRNG. */
  static String generateJoinCode() {
    final char[] chars = new char[JOIN_CODE_LENGTH];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = BASE32[RANDOM.nextInt(BASE32.length)];
    }
    return new String(chars);
  }

  /** Generates a fresh {@value #SESSION_TOKEN_LENGTH}-character base32 reconnect token from the CSPRNG. */
  private static String generateSessionToken() {
    final char[] chars = new char[SESSION_TOKEN_LENGTH];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = BASE32[RANDOM.nextInt(BASE32.length)];
    }
    return new String(chars);
  }

  private static ThreadFactory daemon(String name) {
    return runnable -> {
      final Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Removes rooms whose game never started and that have outlived {@link #roomTtlMs} — otherwise a creator who walks
   * away leaves a room (and its session/clock state) parked in memory forever on a home box — and finished games
   * nobody is connected to any more. Safe to run from several threads at once.
   */
  private void reapAbandonedRooms() {
    try {
      final long now = System.currentTimeMillis();
      for (final var entry : gameRooms.entrySet()) {
        final GameRoom room = entry.getValue();
        final boolean neverJoined;
        final WebSocket[] seated;
        synchronized (room) {
          final var session = room.getSession();
          if (session.getState() == GameState.ENDED && now - session.getEndedAtMs() >= endedRoomTtlMs
              && !room.isConnected(Side.WHITE) && !room.isConnected(Side.BLACK)) {
            gameRooms.remove(entry.getKey(), room);
            room.stopClockTicker();
            for (final Side side : new Side[] {Side.WHITE, Side.BLACK}) {
              final WebSocket socket = room.getSocket(side);
              if (socket != null) {
                playerGameMap.remove(socket, entry.getKey());
              }
            }
            continue;
          }
          // Same lock as joinRoom and startRematch, which hold it from seating a player until the
          // game has started. A room seen here with both seats taken and no game is therefore not a
          // join in progress but one that failed half-way; it expires like a room nobody joined.
          final boolean waiting = session.getState() == GameState.WAITING_FOR_PLAYERS;
          if (!waiting || now - room.getCreatedAtMs() <= roomTtlMs) {
            continue;
          }
          // Reaps can overlap (the maintenance sweep and every createGame): only the one that
          // actually removes the room cleans up after it and tells the players.
          if (!gameRooms.remove(entry.getKey(), room)) {
            continue;
          }
          room.stopClockTicker();
          neverJoined = !room.isFull();
          seated = new WebSocket[] { room.getWhitePlayer(), room.getBlackPlayer() };
        }
        for (final WebSocket socket : seated) {
          if (socket == null) {
            continue;
          }
          // Only this room's mapping: the player may have created another game meanwhile.
          playerGameMap.remove(socket, entry.getKey());
          final JsonObject msg = new JsonObject();
          msg.addProperty("type", "gameAborted");
          msg.addProperty("message",
              neverJoined ? "Game expired (no opponent joined)." : "Game expired (the game did not start).");
          send(socket, msg);
        }
        System.out.println("Reaped abandoned game: " + entry.getKey());
      }
    } catch (final Exception e) {
      System.err.println("[maintenance] reapAbandonedRooms: " + e);
    }
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
    sendArbiterResponse(room, side, response, "");
  }

  /** @param suffix what the arbiter adds to the ruling for the player it is given to, or an empty text */
  private void sendArbiterResponse(GameRoom room, Side side, ArbiterResponse response, String suffix) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", response.type().name().toLowerCase());
    msg.addProperty("message", response.renderedPlayerMessage() + suffix);
    msg.addProperty("style", response.style());

    if (response.acceptedMove().isPresent()) {
      final var move = response.acceptedMove().get();
      final MoveSpecification spec = move.moveSpecification();
      final JsonObject moveData = new JsonObject();
      // For castling, MoveSpecification's fromSquare/toSquare are Square.NONE
      // (calling getName() on them throws NonePointerException). Resolve the
      // king's actual from/to squares via the CastlingMove geometry accessors instead, and tag the
      // move so the client can recognise it.
      if (spec.isCastling()) {
        final Side moveSide = move.movingSide();
        final Square kingFrom = spec.castlingMove().kingFromSquare(moveSide);
        final Square kingTo = spec.castlingMove().kingToSquare(moveSide);
        moveData.addProperty("from", kingFrom.getName());
        moveData.addProperty("to", kingTo.getName());
        moveData.addProperty("castling", spec.castlingMove().name());
      } else {
        moveData.addProperty("from", spec.fromSquare().getName());
        moveData.addProperty("to", spec.toSquare().getName());
      }
      moveData.addProperty("piece", move.movingPiece().name());
      msg.add("move", moveData);
    }

    room.sendToSide(side, GSON.toJson(msg));

    // Also notify opponent of accepted moves — include full board state
    if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
      final var position = room.getSession().getBoard().getBitboardPosition();
      room.setPhysicalPosition(position);
      final var havingMove = room.getSession().getHavingMove();

      final JsonObject opponentMsg = new JsonObject();
      opponentMsg.addProperty("type", "opponentMoved");
      opponentMsg.addProperty("message", "Opponent completed a move.");
      opponentMsg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(position)));
      opponentMsg.addProperty("havingMove", havingMove.name().toLowerCase());
      room.sendToSide(side.getOppositeSide(), GSON.toJson(opponentMsg));
    }
  }

  /**
   * Whether this event represents the on-move player touching a piece on the board. Side-area RESTORE events are
   * excluded — they place a piece TO the board, not "touch" a piece on it. Used to invalidate a pending draw offer per
   * FIDE 9.1.2.1.
   */
  private static boolean isTouchPieceEvent(BoardEvent event) {
    return switch (event.type()) {
      case CLICK, DRAG_MOVE, DRAG_CAPTURE, REMOVE -> true;
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> false;
    };
  }

  /**
   * The clock values of a game in play. A game that has ended gets its final values exactly once, from the
   * announcement of the result ({@link #announceGameEnded}): a caller that reaches this after the deciding action
   * sends nothing, so that the same final values do not go out twice.
   */
  private void sendClockUpdate(GameRoom room) {
    if (room.getSession().getState() == GameState.ENDED) {
      return;
    }
    sendClockValues(room);
  }

  private void sendClockValues(GameRoom room) {
    final var clock = room.getSession().getClock();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "clockUpdate");
    msg.addProperty("whiteTimeMs", clock.getRemainingTimeMs(Side.WHITE));
    msg.addProperty("blackTimeMs", clock.getRemainingTimeMs(Side.BLACK));
    msg.addProperty("running", clock.getRunningFor().name().toLowerCase());
    room.sendToBoth(GSON.toJson(msg));
  }

  private void sendBoardUpdate(GameRoom room) {
    final var position = room.getSession().getBoard().getBitboardPosition();
    final var havingMove = room.getSession().getHavingMove();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "boardUpdate");
    msg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(position)));
    msg.addProperty("havingMove", havingMove.name().toLowerCase());
    room.sendToBoth(GSON.toJson(msg));
  }

  private void sendGameEnded(GameRoom room, GameResult result) {
    // A cancelled ticker (or another terminal action) may still finish after a rematch starts.
    // The immutable result belongs to exactly one session. Check its identity before touching
    // the current ticker/announcement flag, and hold the rematch lock through both deliveries.
    synchronized (room) {
      final var session = room.getSession();
      if (result == null || session.getState() != GameState.ENDED || session.getResult() != result) {
        return;
      }
      announceGameEnded(room, result);
    }
  }

  /** Called under the room lock after checking that the result belongs to its current game. */
  private void announceGameEnded(GameRoom room, GameResult result) {
    room.stopClockTicker();
    // Announce-once: claimed before sending, so that two callers racing here cannot both announce.
    // Nothing below can fail on a socket that closes meanwhile (GameRoom.send), so the claim
    // cannot be left standing without the announcement behind it.
    if (!room.markGameEndAnnounced()) {
      return;
    }
    // The final clock values go out before the result, for every ending: after a flag fall the
    // flagged side's time is clamped to 0, and without this update the client's last cached value
    // is the previous tick's positive remainder - the LCD would show 0:01 on a game lost on time.
    sendClockValues(room);

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "gameEnded");
    msg.addProperty("resultType", result.type().name());
    msg.addProperty("winner", result.winner().name().toLowerCase());
    // The side that just moved (the opposite of who is now to move). The client uses this only
    // for the personalised checkmate / stalemate arbiter message; other endings ignore it.
    msg.addProperty("mover", room.getSession().getHavingMove().getOppositeSide().name().toLowerCase());
    msg.addProperty("description", result.description());
    // For draws by an explicit player action (resignation, flag-fall under the FIDE "opponent
    // cannot win" exception, or accepting a draw offer), tag who acted so the client can phrase
    // the message in the second person. Resignation / flag-fall additionally carry the draw reason.
    if (result.type() == GameResultType.ABANDONMENT || result.type() == GameResultType.WRONG_CLOCK_PRESS_GAME_LOST
        || result.type() == GameResultType.MOVED_OPPONENT_PIECE_GAME_LOST
        || result.type() == GameResultType.WRONG_TIME_OFFER_GAME_LOST
        || result.winner() == Side.NONE && (result.type() == GameResultType.RESIGNATION
            || result.type() == GameResultType.FLAG_FALL || result.type() == GameResultType.DRAW_AGREEMENT)) {
      msg.addProperty("actor", room.getSession().getTerminationActor().name().toLowerCase());
    }
    if (result.winner() == Side.NONE
        && (result.type() == GameResultType.RESIGNATION || result.type() == GameResultType.FLAG_FALL
            || result.type() == GameResultType.ABANDONMENT)) {
      msg.addProperty("drawReason",
          room.getSession().isDrawExceptionByInsufficientMaterial() ? "INSUFFICIENT_MATERIAL" : "NO_MATE");
    }
    room.sendToBoth(GSON.toJson(msg));
    // The end of the game took the circles of an open ruling off the board.
    sendBoardMarksIfChanged(room);
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

      // Check flag fall. sendGameEnded sends the final clock update itself, ahead of the result.
      final Optional<GameResult> flagFall = room.getSession().checkFlagFall();
      if (flagFall.isPresent()) {
        sendGameEnded(room, flagFall.get());
        return;
      }

      // Send clock update
      sendClockUpdate(room);
    } catch (final Exception e) {
      System.err.println("[internal] tickClock: " + e);
      e.printStackTrace();
    }
  }

  /** Sends to one connection; a socket that is closed, or closes meanwhile, is skipped (see {@link GameRoom#send}). */
  private static boolean send(WebSocket conn, JsonObject message) {
    return GameRoom.send(conn, GSON.toJson(message));
  }

  private void sendError(WebSocket conn, String message) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "error");
    msg.addProperty("message", message);
    send(conn, msg);
  }

  /**
   * Tells the joining client that there is no active game to join for the given code (not found,
   * already full, or already ended). Distinct from {@link #sendError} so the client can render a
   * calm, friendly message with a path back to the lobby rather than a red technical error.
   */
  private void sendJoinFailed(WebSocket conn, String reason, String message) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "joinFailed");
    msg.addProperty("reason", reason);
    msg.addProperty("message", message);
    send(conn, msg);
  }

  private void sendRematchUnavailable(GameRoom room, Side side, String message) {
    final WebSocket socket = room.getSocket(side);
    if (socket != null && socket.isOpen()) {
      sendRematchUnavailable(socket, message);
    }
  }

  private void sendRematchUnavailable(WebSocket conn, String message) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "rematchUnavailable");
    msg.addProperty("message", message);
    send(conn, msg);
  }

  /**
   * Reports an unexpected server-side exception to the client without leaking the raw technical detail into the
   * user-visible message area. The user sees a single friendly generic message; the technical detail (exception class +
   * message + the calling context) goes into a `devDetail` field that the client renders in a collapsible developer
   * pane at the bottom of the page. The full stack trace is also written to stderr so the developer can correlate.
   */
  private void sendInternalError(WebSocket conn, Throwable e, String context) {
    System.err.println("[internal] " + context + ": " + e);
    e.printStackTrace();

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "error");
    msg.addProperty("message", "We are sorry — the arbiter lost his concentration for a moment"
        + " and could not handle the situation. Please try again.");
    msg.addProperty("devDetail", context + ": " + e);
    send(conn, msg);
  }
}
