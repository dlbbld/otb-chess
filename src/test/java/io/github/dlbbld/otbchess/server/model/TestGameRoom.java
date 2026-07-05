// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

class TestGameRoom {

  private static final TimeControl TEST_TIME = new TimeControl(5 * 60 * 1000L, 3 * 1000L);

  /** Minimal stand-in socket: identity-distinct, never open (so sendToBoth/sendToSide no-op). */
  private static WebSocket fakeSocket() {
    return fakeSocket(false);
  }

  private static WebSocket fakeSocket(boolean open) {
    return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[] { WebSocket.class },
        (proxy, method, args) -> switch (method.getName()) {
          case "isOpen" -> open;
          case "equals" -> proxy == args[0];
          case "hashCode" -> System.identityHashCode(proxy);
          case "toString" -> "fakeSocket";
          default -> null;
        });
  }

  @Test
  void testStartRematchSwapsSeatsAndResetsSessionAndTokens() {
    final GameRoom room = new GameRoom("TESTGAME", TEST_TIME);
    final WebSocket originalWhite = fakeSocket();
    final WebSocket originalBlack = fakeSocket();
    room.setWhitePlayer(originalWhite);
    room.setBlackPlayer(originalBlack);
    room.setToken(Side.WHITE, "white-token");
    room.setToken(Side.BLACK, "black-token");
    room.getSession().startGame();
    room.getSession().resign(Side.WHITE);
    assertEquals(GameState.ENDED, room.getSession().getState());
    room.setRematchOfferedBy(Side.BLACK);

    final GameSession endedSession = room.getSession();
    room.startRematch();

    // Colours swapped: the former Black now sits on the White seat and vice versa.
    assertSame(originalBlack, room.getWhitePlayer());
    assertSame(originalWhite, room.getBlackPlayer());
    // The old reconnect tokens died with the seat swap (the caller issues fresh ones).
    assertEquals(Side.NONE, room.sideForToken("white-token"));
    assertEquals(Side.NONE, room.sideForToken("black-token"));
    // Handshake state reset; a brand-new session with the SAME time control awaits startGame().
    assertEquals(Side.NONE, room.getRematchOfferedBy());
    assertNotSame(endedSession, room.getSession());
    assertEquals(GameState.WAITING_FOR_PLAYERS, room.getSession().getState());
    assertEquals(TEST_TIME, room.getTimeControl());
    assertEquals(TEST_TIME.initialTimeMs(), room.getSession().getClock().getRemainingTimeMs(Side.WHITE));
  }

  @Test
  void testConnectedSeatRequiresOpenSocketAndNoDisconnectTimestamp() {
    final GameRoom room = new GameRoom("TESTGAME", TEST_TIME);
    room.setWhitePlayer(fakeSocket(true));
    room.setBlackPlayer(fakeSocket(false));

    assertTrue(room.isConnected(Side.WHITE));
    assertFalse(room.isConnected(Side.BLACK));

    room.setDisconnectedAt(Side.WHITE, 123L);
    assertFalse(room.isConnected(Side.WHITE));
  }

  @Test
  void testStartRematchClearsStaleDisconnectTimestamps() {
    final GameRoom room = new GameRoom("TESTGAME", TEST_TIME);
    room.setWhitePlayer(fakeSocket(true));
    room.setBlackPlayer(fakeSocket(true));
    room.setDisconnectedAt(Side.WHITE, 123L);
    room.setDisconnectedAt(Side.BLACK, 456L);
    room.getSession().startGame();
    room.getSession().resign(Side.WHITE);

    room.startRematch();

    assertEquals(null, room.getDisconnectedAt(Side.WHITE));
    assertEquals(null, room.getDisconnectedAt(Side.BLACK));
  }

  /** A standard game's rematch restarts from the NORMAL starting position, not from where the game ended. */
  @Test
  void testStartRematchRestoresStandardStartingPosition() {
    final String standardStartFen = new Board().getFen();
    final GameRoom room = new GameRoom("TESTGAME", TEST_TIME);
    room.getSession().startGame();
    // Mutate the game: 1. e4 e5.
    room.getSession().getBoard().move(new MoveSpecification(Square.E2, Square.E4));
    room.getSession().getBoard().move(new MoveSpecification(Square.E7, Square.E5));
    assertEquals(false, standardStartFen.equals(room.getSession().getBoard().getFen()));

    room.startRematch();

    assertEquals(standardStartFen, room.getSession().getBoard().getFen());
  }

  /** The rematch restarts from the ORIGINAL starting position — also for custom-FEN games with moves played. */
  @Test
  void testStartRematchRestoresCustomFenStartingPosition() {
    final String startFen = "4k3/8/8/8/8/8/8/4K2R w K - 0 1";
    final GameRoom room = new GameRoom("TESTGAME", TEST_TIME, 2, true, Board.fromFenStrict(startFen));
    room.getSession().startGame();
    // Mutate the game: White plays a rook move.
    room.getSession().getBoard().move(new MoveSpecification(Square.H1, Square.H8));
    assertEquals(false, room.getSession().getBoard().getFen().startsWith("4k3/8/8/8/8/8/8/4K2R"));

    room.startRematch();

    assertEquals(startFen, room.getSession().getBoard().getFen());
  }
}
