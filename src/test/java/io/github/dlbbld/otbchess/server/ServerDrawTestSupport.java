// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import java.util.Map;

import org.java_websocket.WebSocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/** Shared by server tests in which a draw is agreed: FIDE 5.2.3 asks for a move by each player first. */
final class ServerDrawTestSupport {
  private static final Gson GSON = new Gson();

  private ServerDrawTestSupport() {
  }

  /**
   * Plays a2-a3 and a7-a6 with the clock presses, through the server, in a game at the initial position.
   *
   * @return the board after the two moves, for tests that keep the board of the player on move
   */
  static Map<String, String> bothPlayersMove(GameWebSocketServer server, GameRoom room, WebSocket white,
      WebSocket black) {
    final Map<String, String> board = MessageConverter.fromStaticPosition(room.getSession().getPositionBeforeTurn());
    move(server, white, board, "WHITE_PAWN", "a2", "a3");
    move(server, black, board, "BLACK_PAWN", "a7", "a6");
    if (room.getSession().getBoard().getPerformedMovesAsSan().size() != 2) {
      throw new AssertionError("the two opening moves were not accepted");
    }
    return board;
  }

  private static void move(GameWebSocketServer server, WebSocket socket, Map<String, String> board, String piece,
      String from, String to) {
    board.put(from, "NONE");
    board.put(to, piece);
    final JsonObject event = new JsonObject();
    event.addProperty("eventType", "DRAG_MOVE");
    event.addProperty("square", from);
    event.addProperty("targetSquare", to);
    event.addProperty("piece", piece);
    event.addProperty("displacedPiece", "NONE");
    final JsonObject boardEvent = new JsonObject();
    boardEvent.addProperty("type", "boardEvent");
    boardEvent.add("event", event);
    boardEvent.add("boardState", GSON.toJsonTree(board));
    server.onMessage(socket, boardEvent.toString());
    final JsonObject press = new JsonObject();
    press.addProperty("type", "clockPress");
    press.add("boardState", GSON.toJsonTree(board));
    server.onMessage(socket, press.toString());
  }
}
