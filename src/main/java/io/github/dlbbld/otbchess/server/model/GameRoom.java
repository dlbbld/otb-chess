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
  private final GameSession session;
  private final TimeControl timeControl;
  // Wall-clock creation time, used to reap rooms that were created but never joined.
  private final long createdAtMs = System.currentTimeMillis();

  private WebSocket whitePlayer;
  private WebSocket blackPlayer;
  private ScheduledFuture<?> clockTickFuture;

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
}
