// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server.model;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.arbiter.BoardMark;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.placement.PlacementBoard;
import io.github.dlbbld.otbchess.placement.PlacementMeasure;

/**
 * Represents a game room connecting two players.
 */
public class GameRoom {

  private final String gameId;
  private GameSession session;
  private BitboardPosition physicalPosition;
  // Presentation text for the current offer, including an explanation from a rejected claim.
  // DrawOfferManager remains authoritative about whether the offer exists and can be accepted.
  private String drawOfferMessage = "Your opponent offers a draw.";
  // Both players have been sent the result of the current session.
  private boolean gameEndAnnounced;
  // The marked squares both boards were last sent; a change of the session's marks is sent once.
  private List<BoardMark> announcedBoardMarks = List.of();
  private final TimeControl timeControl;
  // Kept for rematches: a rematch rebuilds the session with the SAME settings.
  private final int maxIllegalMoves;
  private final boolean autoResumeAfterRestore;
  // Free placement of the pieces (E-005): where every piece lies; null in a game without it. A rematch keeps the
  // option and its limit like the other settings.
  private final PlacementBoard placements;
  private final int maxUnclearPlacements;
  // The piece the player to move was last told to take off or put back (a half-removed opponent's piece), or 0.
  private int halfRemovedPieceId;
  // Whether both pages were last told that the player to move has said J'adoube and not yet adjusted a piece.
  private boolean announcedJadoubeArmed;
  // Wall-clock creation time, used to reap rooms that were created but never joined.
  private final long createdAtMs = System.currentTimeMillis();

  private WebSocket whitePlayer;
  private WebSocket blackPlayer;
  private ScheduledFuture<?> clockTickFuture;

  // Secret reconnect tokens follow the players when rematches swap their seats. A dropped socket
  // can resume even if it never received the rematch start message with its new colour.
  private String whiteToken;
  private String blackToken;

  // Rematch handshake after the game ended: the side that offered, or NONE. When the OTHER side
  // also offers (= accepts), the rematch starts. Reset by startRematch().
  private Side rematchOfferedBy = Side.NONE;

  // Wall-clock time (ms since epoch) since a seat's socket dropped without a resume; null while
  // connected. Lets "claim victory" verify the opponent has really been gone past the grace.
  private Long whiteDisconnectedAtMs;
  private Long blackDisconnectedAtMs;

  public GameRoom(String gameId, TimeControl timeControl) {
    this(gameId, timeControl, io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true);
  }

  public GameRoom(String gameId, TimeControl timeControl, int maxIllegalMoves) {
    this(gameId, timeControl, maxIllegalMoves, true);
  }

  public GameRoom(String gameId, TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore) {
    this(gameId, timeControl, maxIllegalMoves, autoResumeAfterRestore, new Board());
  }

  /**
   * Constructor accepting a custom starting board (e.g. parsed from a FEN supplied on the start screen). FEN parsing
   * and validation happen at the server boundary before the room is built.
   */
  public GameRoom(String gameId, TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore,
      Board startingBoard) {
    this(gameId, timeControl, maxIllegalMoves, autoResumeAfterRestore, startingBoard, false,
        GameSession.DEFAULT_MAX_UNCLEAR_PLACEMENTS);
  }

  /**
   * @param freePlacement        whether the pieces stay where they are let go (E-005) instead of being centred
   * @param maxUnclearPlacements the number of unclear placements at a clock press that loses the game, or
   *                             {@link GameSession#UNLIMITED_UNCLEAR_PLACEMENTS}; only used with free placement
   */
  public GameRoom(String gameId, TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore,
      Board startingBoard, boolean freePlacement, int maxUnclearPlacements) {
    this.gameId = gameId;
    this.session = new GameSession(timeControl, maxIllegalMoves, autoResumeAfterRestore, startingBoard);
    this.session.setMaxUnclearPlacements(maxUnclearPlacements);
    this.physicalPosition = startingBoard.getBitboardPosition();
    this.timeControl = timeControl;
    this.maxIllegalMoves = maxIllegalMoves;
    this.autoResumeAfterRestore = autoResumeAfterRestore;
    this.maxUnclearPlacements = maxUnclearPlacements;
    this.placements = freePlacement
        ? new PlacementBoard(PlacementMeasure.radiusFor(PlacementMeasure.DEFAULT_EXACTNESS_PERCENT),
            startingBoard.getBitboardPosition())
        : null;
  }

  /** @return whether the pieces of this game stay where they are let go (E-005) */
  public boolean isFreePlacement() {
    return placements != null;
  }

  /** @return where every piece lies, or {@code null} in a game without free placement */
  public PlacementBoard getPlacements() {
    return placements;
  }

  public synchronized int getHalfRemovedPieceId() {
    return halfRemovedPieceId;
  }

  public synchronized void setHalfRemovedPieceId(int id) {
    halfRemovedPieceId = id;
  }

  public String getGameId() {
    return gameId;
  }

  /** @return wall-clock time (ms since epoch) when this room was created. */
  public long getCreatedAtMs() {
    return createdAtMs;
  }

  public GameSession getSession() {
    return session;
  }

  public synchronized BitboardPosition getPhysicalPosition() {
    return physicalPosition;
  }

  public synchronized void setPhysicalPosition(BitboardPosition position) {
    physicalPosition = position;
  }

  public synchronized String getDrawOfferMessage() {
    return drawOfferMessage;
  }

  public synchronized void setDrawOfferMessage(String message) {
    drawOfferMessage = message;
  }

  /** @return true for the first call per game, i.e. when the caller is the one to announce the result. */
  public synchronized boolean markGameEndAnnounced() {
    if (gameEndAnnounced) {
      return false;
    }
    gameEndAnnounced = true;
    return true;
  }

  public synchronized boolean isGameEndAnnounced() {
    return gameEndAnnounced;
  }

  public TimeControl getTimeControl() {
    return timeControl;
  }

  public WebSocket getWhitePlayer() {
    return whitePlayer;
  }

  public void setWhitePlayer(WebSocket whitePlayer) {
    this.whitePlayer = whitePlayer;
  }

  public WebSocket getBlackPlayer() {
    return blackPlayer;
  }

  public void setBlackPlayer(WebSocket blackPlayer) {
    this.blackPlayer = blackPlayer;
  }

  public boolean isFull() {
    return whitePlayer != null && blackPlayer != null;
  }

  public WebSocket getSocket(Side side) {
    return switch (side) {
      case WHITE -> whitePlayer;
      case BLACK -> blackPlayer;
      default -> null;
    };
  }

  public boolean isConnected(Side side) {
    final WebSocket socket = getSocket(side);
    return socket != null && socket.isOpen() && getDisconnectedAt(side) == null;
  }

  public Side getSide(WebSocket conn) {
    if (conn == whitePlayer) {
      return Side.WHITE;
    }
    if (conn == blackPlayer) {
      return Side.BLACK;
    }
    return Side.NONE;
  }

  public void setToken(Side side, String token) {
    if (side == Side.WHITE) {
      this.whiteToken = token;
    } else if (side == Side.BLACK) {
      this.blackToken = token;
    }
  }

  public String getToken(Side side) {
    return switch (side) {
      case WHITE -> whiteToken;
      case BLACK -> blackToken;
      default -> null;
    };
  }

  /** @return the side that owns this reconnect token, or {@link Side#NONE} if it matches neither. */
  public Side sideForToken(String token) {
    if (token == null) {
      return Side.NONE;
    }
    if (token.equals(whiteToken)) {
      return Side.WHITE;
    }
    if (token.equals(blackToken)) {
      return Side.BLACK;
    }
    return Side.NONE;
  }

  /** Re-attaches a (reconnected) socket to a side, replacing the dropped one. */
  public void setSocket(Side side, WebSocket conn) {
    if (side == Side.WHITE) {
      this.whitePlayer = conn;
    } else if (side == Side.BLACK) {
      this.blackPlayer = conn;
    }
  }

  public void sendToBoth(String message) {
    sendToSide(Side.WHITE, message);
    sendToSide(Side.BLACK, message);
  }

  public void sendToSide(Side side, String message) {
    send(getSocket(side), message);
  }

  /**
   * Sends to a socket if it is open. A socket can close between the open check and the send; the send then throws.
   * That must not fail the caller: the message was for a player who is gone, and whatever the caller does next - above
   * all the other player's copy of a result - still has to happen. The close itself is handled by the server's close
   * callback like any other disconnect.
   *
   * @return whether the message was handed to the socket
   */
  public static boolean send(WebSocket socket, String message) {
    if (socket == null || !socket.isOpen()) {
      return false;
    }
    try {
      socket.send(message);
      return true;
    } catch (final WebsocketNotConnectedException e) {
      return false;
    }
  }

  /**
   * Starts periodic clock tick and flag fall check.
   */
  public void startClockTicker(ScheduledExecutorService executor, Runnable tickAction) {
    if (clockTickFuture != null) {
      clockTickFuture.cancel(false);
    }
    clockTickFuture = executor.scheduleAtFixedRate(tickAction, 1000, 1000, TimeUnit.MILLISECONDS);
  }

  public void stopClockTicker() {
    if (clockTickFuture != null) {
      clockTickFuture.cancel(false);
      clockTickFuture = null;
    }
  }

  /** Marks the side's socket as dropped (or reconnected with {@code null}) for the claim-victory guard. */
  public void setDisconnectedAt(Side side, Long timestampMs) {
    if (side == Side.WHITE) {
      this.whiteDisconnectedAtMs = timestampMs;
    } else if (side == Side.BLACK) {
      this.blackDisconnectedAtMs = timestampMs;
    }
  }

  /** @return when this side's socket dropped (ms since epoch), or {@code null} while connected. */
  public Long getDisconnectedAt(Side side) {
    return switch (side) {
      case WHITE -> whiteDisconnectedAtMs;
      case BLACK -> blackDisconnectedAtMs;
      default -> null;
    };
  }

  // ===== Rematch =====

  /** @return the side that has offered a rematch since the game ended, or {@link Side#NONE}. */
  public Side getRematchOfferedBy() {
    return rematchOfferedBy;
  }

  public void setRematchOfferedBy(Side side) {
    this.rematchOfferedBy = side;
  }

  /**
   * Starts a rematch: the players swap colours (seats), and a fresh session begins from the SAME starting position
   * (the original FEN for custom games) with the SAME time control and settings. Reconnect tokens swap with their
   * owners, so a lost rematch-start message cannot strand a player without a valid token. The caller starts the clock.
   */
  public void startRematch() {
    final WebSocket previousWhite = whitePlayer;
    whitePlayer = blackPlayer;
    blackPlayer = previousWhite;
    final String previousWhiteToken = whiteToken;
    whiteToken = blackToken;
    blackToken = previousWhiteToken;
    whiteDisconnectedAtMs = null;
    blackDisconnectedAtMs = null;
    rematchOfferedBy = Side.NONE;
    session = new GameSession(timeControl, maxIllegalMoves, autoResumeAfterRestore,
        new Board(session.getBoard().getInitialFen()));
    session.setMaxUnclearPlacements(maxUnclearPlacements);
    physicalPosition = session.getBoard().getBitboardPosition();
    if (placements != null) {
      placements.reset(physicalPosition);
    }
    halfRemovedPieceId = 0;
    announcedJadoubeArmed = false;
    gameEndAnnounced = false;
    announcedBoardMarks = List.of();
    drawOfferMessage = "Your opponent offers a draw.";
  }

  /**
   * @return whether J'adoube is armed, when that differs from what both pages were last told - it is then
   *         remembered as told; empty when nothing changed
   */
  public synchronized Optional<Boolean> takeChangedJadoubeArmed() {
    final boolean armed = session.isJadoubeArmed();
    if (armed == announcedJadoubeArmed) {
      return Optional.empty();
    }
    announcedJadoubeArmed = armed;
    return Optional.of(armed);
  }

  /**
   * @return the marks to send when they differ from what both boards were last sent - they are then remembered as
   *         sent; empty when nothing changed
   */
  public synchronized Optional<List<BoardMark>> takeChangedBoardMarks() {
    final List<BoardMark> marks = session.getBoardMarks();
    if (marks.equals(announcedBoardMarks)) {
      return Optional.empty();
    }
    announcedBoardMarks = marks;
    return Optional.of(marks);
  }
}
