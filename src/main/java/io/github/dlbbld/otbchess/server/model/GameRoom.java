// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server.model;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * Represents a game room connecting two players.
 */
public class GameRoom {

  private final String gameId;
  private GameSession session;
  private final TimeControl timeControl;
  // Kept for rematches: a rematch rebuilds the session with the SAME settings.
  private final int maxIllegalMoves;
  private final boolean autoResumeAfterRestore;
  // Wall-clock creation time, used to reap rooms that were created but never joined.
  private final long createdAtMs = System.currentTimeMillis();

  private WebSocket whitePlayer;
  private WebSocket blackPlayer;
  private ScheduledFuture<?> clockTickFuture;

  // Per-side secret reconnect tokens. A player whose socket drops re-attaches by presenting its
  // token (not the guessable join code), so a third party who knows the join code can't hijack a seat.
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
    this.gameId = gameId;
    this.session = new GameSession(timeControl, maxIllegalMoves, autoResumeAfterRestore, startingBoard);
    this.timeControl = timeControl;
    this.maxIllegalMoves = maxIllegalMoves;
    this.autoResumeAfterRestore = autoResumeAfterRestore;
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
    if (whitePlayer != null && whitePlayer.isOpen()) {
      whitePlayer.send(message);
    }
    if (blackPlayer != null && blackPlayer.isOpen()) {
      blackPlayer.send(message);
    }
  }

  public void sendToSide(Side side, String message) {
    final WebSocket socket = getSocket(side);
    if (socket != null && socket.isOpen()) {
      socket.send(message);
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
   * (the original FEN for custom games) with the SAME time control and settings. The reconnect tokens are invalidated
   * — the caller must issue fresh per-seat tokens and start the game/clock, mirroring the join flow.
   */
  public void startRematch() {
    final WebSocket previousWhite = whitePlayer;
    whitePlayer = blackPlayer;
    blackPlayer = previousWhite;
    whiteToken = null;
    blackToken = null;
    rematchOfferedBy = Side.NONE;
    session = new GameSession(timeControl, maxIllegalMoves, autoResumeAfterRestore,
        new Board(session.getBoard().getInitialFen()));
  }
}
