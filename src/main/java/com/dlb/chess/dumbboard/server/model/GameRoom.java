package com.dlb.chess.dumbboard.server.model;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;

import com.dlb.chess.board.enums.Side;
import com.dlb.chess.dumbboard.game.GameSession;
import com.dlb.chess.dumbboard.game.model.TimeControl;

/**
 * Represents a game room connecting two players.
 */
public class GameRoom {

  private final String gameId;
  private final GameSession session;
  private final TimeControl timeControl;

  private WebSocket whitePlayer;
  private WebSocket blackPlayer;
  private ScheduledFuture<?> clockTickFuture;

  public GameRoom(String gameId, TimeControl timeControl) {
    this(gameId, timeControl,
        com.dlb.chess.dumbboard.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true);
  }

  public GameRoom(String gameId, TimeControl timeControl, int maxIllegalMoves) {
    this(gameId, timeControl, maxIllegalMoves, true);
  }

  public GameRoom(String gameId, TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore) {
    this.gameId = gameId;
    this.session = new GameSession(timeControl, maxIllegalMoves, autoResumeAfterRestore);
    this.timeControl = timeControl;
  }

  public String getGameId() {
    return gameId;
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
