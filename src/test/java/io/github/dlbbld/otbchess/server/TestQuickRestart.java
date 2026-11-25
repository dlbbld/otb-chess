// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

/**
 * Found at the deployment of 0.5.0 (2026-11-25): the restarted server could not bind its WebSocket port for about 50
 * seconds ("Address already in use"), because connections of the server before it were still expiring. A server
 * must be able to take its port over at once.
 */
class TestQuickRestart {

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void theServerAsksToReuseItsAddressExceptOnWindows() {
    // On Windows the option means something else: two servers on one port. It stays off there.
    assertEquals(!GameWebSocketServer.isWindows(), new GameWebSocketServer("127.0.0.1", 0).isReuseAddr());
  }

  @Test
  void aServerStartedRightAfterOneWithOpenConnectionsWasStoppedBindsThePort() throws Exception {
    final int port = freePort();
    final var first = new GameWebSocketServer("127.0.0.1", port);
    first.start();
    assertTrue(first.awaitStarted(10, TimeUnit.SECONDS));
    // A player is connected when the server goes down. (Stopped in an orderly way, as here, the old server leaves
    // nothing behind on the port; only a killed process does. That journey is replayed with a real process in
    // tests/e2e/server-restart.spec.ts - this test guards the restart in one JVM.)
    final WebSocketClient player = new WebSocketClient(URI.create("ws://127.0.0.1:" + port)) {
      @Override
      public void onOpen(ServerHandshake handshake) {
      }

      @Override
      public void onMessage(String message) {
      }

      @Override
      public void onClose(int code, String reason, boolean remote) {
      }

      @Override
      public void onError(Exception ex) {
      }
    };
    assertTrue(player.connectBlocking(10, TimeUnit.SECONDS));
    first.stop(1000);

    final var second = new GameWebSocketServer("127.0.0.1", port);
    second.start();
    try {
      assertTrue(second.awaitStarted(5, TimeUnit.SECONDS), "the restarted server must bind its port at once");
    } finally {
      second.stop(1000);
      player.closeBlocking();
    }
  }
}
