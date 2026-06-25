package io.github.dlbbld.otbchess.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.event.BoardEvent;
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

  private final Map<String, GameRoom> gameRooms = new ConcurrentHashMap<>();
  private final Map<WebSocket, String> playerGameMap = new ConcurrentHashMap<>();
  private final ScheduledExecutorService clockExecutor = Executors.newScheduledThreadPool(2);

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
        case "abort" -> handleAbort(conn);
        case "requestPgn" -> handleRequestPgn(conn);
        case "restorePosition" -> handleRestorePosition(conn);
        case "readyToContinue" -> handleReadyToContinue(conn);
        case "opponentClockPressed" -> handleOpponentClockPressed(conn);
        default -> sendError(conn, "Unknown message type: " + type);
      }
    } catch (final Exception e) {
      sendInternalError(conn, e, "onMessage");
    }
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

  // ===== Message handlers =====

  private void handleCreateGame(WebSocket conn, JsonObject json) {
    final long initialTimeMs = json.get("initialTimeMs").getAsLong();
    final long incrementMs = json.get("incrementMs").getAsLong();
    final String requestedSide = json.get("side").getAsString();
    // maxIllegalMoves: 1..10 = limit, -1 = unlimited, missing = FIDE default (2)
    final int maxIllegalMoves = json.has("maxIllegalMoves") ? json.get("maxIllegalMoves").getAsInt()
        : io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES;
    final boolean autoResumeAfterRestore = !json.has("autoResumeAfterRestore")
        || json.get("autoResumeAfterRestore").getAsBoolean();

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
        parsed = Board.fromFenStrict(fenInput);
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

    final String gameId = UUID.randomUUID().toString().substring(0, 8);
    final TimeControl timeControl = new TimeControl(initialTimeMs, incrementMs);
    final GameRoom room = new GameRoom(gameId, timeControl, maxIllegalMoves, autoResumeAfterRestore, startingBoard);

    if ("white".equals(creatorSide)) {
      room.setWhitePlayer(conn);
    } else {
      room.setBlackPlayer(conn);
    }

    gameRooms.put(gameId, room);
    playerGameMap.put(conn, gameId);

    final JsonObject response = new JsonObject();
    response.addProperty("type", "gameCreated");
    response.addProperty("gameId", gameId);
    response.addProperty("side", creatorSide);
    response.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(startingBoard.getBitboardPosition())));
    response.addProperty("havingMove", startingBoard.getSideToMove().name().toLowerCase());
    conn.send(GSON.toJson(response));

    System.out.println("Game created: " + gameId + " by " + creatorSide + (fenInput.isEmpty() ? "" : " (custom FEN)"));
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

    // Send join confirmation to the joining player. Send the actual starting board
    // (not the hard-coded initial position) so a custom-FEN game shows the right
    // pieces in the joiner's first render.
    final var startingPosition = room.getSession().getBoard().getBitboardPosition();
    final var havingMove = room.getSession().getHavingMove();
    final JsonObject joinResponse = new JsonObject();
    joinResponse.addProperty("type", "gameJoined");
    joinResponse.addProperty("gameId", gameId);
    joinResponse.addProperty("side", side);
    joinResponse.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(startingPosition)));
    joinResponse.addProperty("havingMove", havingMove.name().toLowerCase());
    conn.send(GSON.toJson(joinResponse));

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

    System.out.println("Game started: " + gameId);
  }

  private void handleBoardEvent(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);
    final JsonObject eventData = json.getAsJsonObject("event");
    final String eventType = eventData.get("eventType").getAsString();

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

    final BoardEvent event = MessageConverter.toBoardEvent(eventType, eventData.get("square").getAsString(),
        eventData.get("targetSquare").getAsString(), eventData.get("piece").getAsString(),
        eventData.get("displacedPiece").getAsString());

    if (room.getSession().isRestorationResumePending()) {
      return;
    }

    if (room.getSession().isWaitingForRestoration()) {
      forwardBoardEventToOpponent(room, side, eventData);

      if (json.has("boardState")) {
        @SuppressWarnings("unchecked") final Map<String, String> boardStateMap = GSON
            .fromJson(json.getAsJsonObject("boardState"), Map.class);
        final BitboardPosition afterPosition = MessageConverter.toStaticPosition(boardStateMap);
        if (room.getSession().isRestoredPosition(afterPosition)) {
          completeRestoration(room);
        }
      }
      return;
    }

    final Optional<ArbiterResponse> midPlayResponse = room.getSession().recordEvent(side, event);

    if (midPlayResponse.isPresent()) {
      final ArbiterResponse response = midPlayResponse.get();
      if (response.type() == ArbiterResponseType.POSITION_CHANGE) {
        sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
      } else {
        sendArbiterResponse(room, side, response);
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
        conn.send(GSON.toJson(msg));
      } else if (wrongTimeReleaseInvalidation) {
        drawMgr.recordOpponentTouchedPiece();
        final JsonObject msg = new JsonObject();
        msg.addProperty("type", "drawOfferInvalidated");
        msg.addProperty("message", "The draw offer is no longer valid because you made the move.");
        conn.send(GSON.toJson(msg));
      }
    }

    // Forward the event to the opponent for real-time board visibility
    forwardBoardEventToOpponent(room, side, eventData);

    // Auto-end on game-ending moves (checkmate, stalemate, dead position, fivefold, 75-move):
    // accept the move and end the game without waiting for a clock press.
    if (midPlayResponse.isEmpty() && json.has("boardState")) {
      @SuppressWarnings("unchecked") final Map<String, String> boardStateMap = GSON
          .fromJson(json.getAsJsonObject("boardState"), Map.class);
      final BitboardPosition afterPosition = MessageConverter.toStaticPosition(boardStateMap);
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

    @SuppressWarnings("unchecked") final Map<String, String> boardState = GSON
        .fromJson(json.getAsJsonObject("boardState"), Map.class);
    final BitboardPosition afterPosition = MessageConverter.toStaticPosition(boardState);

    final ArbiterResponse response = room.getSession().pressClockButton(side, afterPosition);
    sendArbiterResponse(room, side, response);

    sendOpponentArbiterMessage(room, side, response);

    if (response.type() == ArbiterResponseType.MOVE_ACCEPTED) {
      sendClockUpdate(room);
      // Note: opponentMoved (sent by sendArbiterResponse) already includes the board state.
      // Do NOT also send boardUpdate here, as it can overwrite the opponent's in-progress moves.
    } else if (response.type() == ArbiterResponseType.ILLEGAL_MOVE) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
    } else if (response.type() == ArbiterResponseType.RELEASED_PIECE_VIOLATION) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error",
          response.restorePosition().orElse(room.getSession().getPositionBeforeTurn()));
    } else if (response.type() == ArbiterResponseType.TOUCH_MOVE_VIOLATION) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
    } else if (response.type() == ArbiterResponseType.INCOMPLETE_MOVE && side == room.getSession().getHavingMove()
        && room.getSession().getMustExecuteMove() != null) {
      // A rejected claim's specified move was not carried out: offer a Revert to the start of the
      // turn. The move is still owed afterwards, so the player reverts and then plays it.
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "info");
    }

    checkGameEnded(room);
  }

  private void handleOfferDraw(WebSocket conn, JsonObject json) {
    final GameRoom room = getRoom(conn);
    if (room == null) {
      return;
    }

    final Side side = room.getSide(conn);

    // Parse the boardState (it's required for the correct-time validation path).
    BitboardPosition afterPosition = null;
    if (json.has("boardState")) {
      @SuppressWarnings("unchecked") final Map<String, String> boardState = GSON
          .fromJson(json.getAsJsonObject("boardState"), Map.class);
      afterPosition = MessageConverter.toStaticPosition(boardState);
    }

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

    if (moveAttempted) {
      handleCorrectTimeDrawOffer(room, conn, side, afterPosition);
    } else if (!hasMove && room.getSession().hasReleasedPieceCommitment()) {
      // Scenario 2: opponent has already made a legal release (committed move via FIDE 4.7),
      // they are merely waiting to press their clock. The offer is invalid — do NOT register
      // it, do NOT count it toward the wrong-time penalty, and do NOT forward it to the
      // committed opponent. Just inform the offerer.
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "wrongTimeDrawOffer");
      msg.addProperty("message", "The draw offer is not valid because your opponent has"
          + " already made a move and is about to press the clock.");
      conn.send(GSON.toJson(msg));
    } else {
      handleWrongTimeDrawOffer(room, conn, side);
    }

    checkGameEnded(room);
  }

  private void handleCorrectTimeDrawOffer(GameRoom room, WebSocket conn, Side side, BitboardPosition afterPosition) {
    final ArbiterResponse response = room.getSession().offerDrawCorrectTime(side, afterPosition);

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
      conn.send(GSON.toJson(ack));
      return;
    }
    if (response.type() == ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST) {
      // Repeated-offer game-lost penalty. The session has already ended the game.
      sendArbiterResponse(room, side, response);
      return;
    }

    // Move was invalid (or repeated-offer info/warning). Apply the standard arbiter
    // intervention — for the move-validity violations, this also drives the restoration flow.
    sendArbiterResponse(room, side, response);
    if (response.type() == ArbiterResponseType.ILLEGAL_MOVE) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
    } else if (response.type() == ArbiterResponseType.TOUCH_MOVE_VIOLATION) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error");
    } else if (response.type() == ArbiterResponseType.RELEASED_PIECE_VIOLATION) {
      sendRestoreInstructions(room, side, response.renderedPlayerMessage(), "error",
          response.restorePosition().orElse(room.getSession().getPositionBeforeTurn()));
    }
    // INCOMPLETE_MOVE / repeated-offer warning — message has already been sent, no further action.
  }

  private void handleWrongTimeDrawOffer(GameRoom room, WebSocket conn, Side side) {
    final var result = room.getSession().offerDrawWrongTime(side);

    if (result.arbiterMessage() != null) {
      final JsonObject arbiterMsg = new JsonObject();
      arbiterMsg.addProperty("type", result.isWrongTime() ? "wrongTimeDrawOffer" : "repeatedDrawOffer");
      arbiterMsg.addProperty("message", result.arbiterMessage());
      conn.send(GSON.toJson(arbiterMsg));
      // Note: we no longer stop the clock or enter the ready-to-continue handshake here.
      // Per FIDE the offer is informational and the clock keeps running on whoever has the move.
    }

    // Forward the offer to the opponent (still valid even at the wrong time, unless the
    // offering side just hit the game-loss penalty or this was a duplicate from the same side).
    if (result.accepted() && !result.gameLost()) {
      sendDrawOfferToOpponent(room, side);
    }
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

    // Personalised per player so it is unambiguous who rejected.
    final JsonObject toRejecter = new JsonObject();
    toRejecter.addProperty("type", "drawRejected");
    toRejecter.addProperty("message", "You rejected the draw offer.");
    room.sendToSide(side, GSON.toJson(toRejecter));

    final JsonObject toOfferer = new JsonObject();
    toOfferer.addProperty("type", "drawRejected");
    toOfferer.addProperty("message", "Your opponent rejected the draw offer.");
    room.sendToSide(side.getOppositeSide(), GSON.toJson(toOfferer));
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

    // Per-player feedback for the claim event itself (claimer's arbiter panel).
    final JsonObject response = new JsonObject();
    response.addProperty("type", "drawClaimResult");
    response.addProperty("accepted", result.accepted());
    response.addProperty("message", result.message());
    response.addProperty("invalidMove", result.invalidMove());
    if (result.moveToPerform().isPresent()) {
      response.addProperty("mustExecuteMove", result.moveToPerform().get().toString());
    }
    conn.send(GSON.toJson(response));

    // Opponent gets a separate notification (the claim event happened on their counterpart's
    // side; they need to know it occurred and what its outcome was).
    if (result.opponentMessage().isPresent()) {
      final JsonObject opponentMsg = new JsonObject();
      opponentMsg.addProperty("type", "drawClaimOpponent");
      opponentMsg.addProperty("message", result.opponentMessage().get());
      room.sendToSide(side.getOppositeSide(), GSON.toJson(opponentMsg));
    }

    // FIDE 9.5: a rejected claim is treated as a draw offer to the opponent. The session
    // already registered the offer; broadcast it so the opponent gets the standard
    // Accept/Reject panel and the touch-piece invalidation flow.
    if (result.convertsToDrawOffer()) {
      sendDrawOfferToOpponent(room, side);
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
    sendGameEnded(room, result);
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
    if (room.isFull() || room.getSession().getState() != GameState.WAITING_FOR_PLAYERS) {
      sendError(conn, "The game cannot be aborted after it has started.");
      return;
    }

    room.stopClockTicker();
    gameRooms.remove(room.getGameId());
    playerGameMap.remove(conn);

    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "gameAborted");
    msg.addProperty("message", "Game aborted.");
    conn.send(GSON.toJson(msg));
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

  private void completeRestoration(GameRoom room, BitboardPosition restorePosition) {
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
    sendRestoreInstructions(room, side, "Please restore the position to the beginning of the move.", "info");
  }

  private void sendRestoreInstructions(GameRoom room, Side side, String message, String style) {
    sendRestoreInstructions(room, side, message, style, room.getSession().getPositionBeforeTurn());
  }

  private void sendRestoreInstructions(GameRoom room, Side side, String message, String style,
      BitboardPosition restorePosition) {
    room.getSession().enterWaitingForRestoration(restorePosition);
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "restoreRequired");
    msg.addProperty("message", message);
    msg.addProperty("style", style);
    room.sendToSide(side, GSON.toJson(msg));
  }

  private void sendOpponentArbiterMessage(GameRoom room, Side side, ArbiterResponse response) {
    final Optional<String> opponentMessage = response.renderedOpponentMessage();
    if (opponentMessage.isEmpty()) {
      return;
    }
    final JsonObject opponentMsg = new JsonObject();
    opponentMsg.addProperty("type", response.type().name().toLowerCase());
    opponentMsg.addProperty("message", opponentMessage.get());
    opponentMsg.addProperty("style", response.style());
    room.sendToSide(side.getOppositeSide(), GSON.toJson(opponentMsg));
  }

  private void forwardBoardEventToOpponent(GameRoom room, Side side, JsonObject eventData) {
    final JsonObject forwardMsg = new JsonObject();
    forwardMsg.addProperty("type", "opponentBoardEvent");
    forwardMsg.add("event", eventData);
    room.sendToSide(side.getOppositeSide(), GSON.toJson(forwardMsg));
  }

  // ===== Helper methods =====

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
    msg.addProperty("message", response.renderedPlayerMessage());
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
    final var position = room.getSession().getBoard().getBitboardPosition();
    final var havingMove = room.getSession().getHavingMove();
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "boardUpdate");
    msg.add("board", GSON.toJsonTree(MessageConverter.fromStaticPosition(position)));
    msg.addProperty("havingMove", havingMove.name().toLowerCase());
    room.sendToBoth(GSON.toJson(msg));
  }

  private void sendGameEnded(GameRoom room, GameResult result) {
    room.stopClockTicker();

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
    if (result.winner() == Side.NONE && (result.type() == GameResultType.RESIGNATION
        || result.type() == GameResultType.FLAG_FALL || result.type() == GameResultType.DRAW_AGREEMENT)) {
      msg.addProperty("actor", room.getSession().getTerminationActor().name().toLowerCase());
    }
    if (result.winner() == Side.NONE
        && (result.type() == GameResultType.RESIGNATION || result.type() == GameResultType.FLAG_FALL)) {
      msg.addProperty("drawReason",
          room.getSession().isDrawExceptionByInsufficientMaterial() ? "INSUFFICIENT_MATERIAL" : "NO_MATE");
    }
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

      // Check flag fall. ClockManager.tick() (called inside checkFlagFall via
      // getRemainingTimeMs) clamps the flagged side's time to 0, so emit one
      // final clock update BEFORE the game-ended message — otherwise the client's
      // last cached value is still the previous tick's positive remainder and
      // the LCD shows 0:01 even after the player has lost on time.
      final Optional<GameResult> flagFall = room.getSession().checkFlagFall();
      if (flagFall.isPresent()) {
        sendClockUpdate(room);
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

  private void sendError(WebSocket conn, String message) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "error");
    msg.addProperty("message", message);
    if (conn != null && conn.isOpen()) {
      conn.send(GSON.toJson(msg));
    }
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
    if (conn != null && conn.isOpen()) {
      conn.send(GSON.toJson(msg));
    }
  }
}
