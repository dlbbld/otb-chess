// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TestServerHardening {

  private static final Set<String> ALLOWED = Set.of("https://play.otb-chess.app");

  @Test
  void originCheckAllowsLoopbackAndAllowlistedHosts() {
    // No Origin header (non-browser clients / smoke tests) is allowed.
    assertTrue(GameWebSocketServer.isOriginAllowed(null, ALLOWED));
    assertTrue(GameWebSocketServer.isOriginAllowed("", ALLOWED));
    // Loopback on any scheme/port for local dev.
    assertTrue(GameWebSocketServer.isOriginAllowed("http://localhost:9000", ALLOWED));
    assertTrue(GameWebSocketServer.isOriginAllowed("http://127.0.0.1:8080", ALLOWED));
    // Allowlisted production host, case-insensitive.
    assertTrue(GameWebSocketServer.isOriginAllowed("https://play.otb-chess.app", ALLOWED));
    assertTrue(GameWebSocketServer.isOriginAllowed("https://PLAY.OTB-CHESS.APP", ALLOWED));
  }

  @Test
  void originCheckRejectsForeignOrigins() {
    assertFalse(GameWebSocketServer.isOriginAllowed("https://evil.example.com", ALLOWED));
    // Suffix/lookalike must not slip through.
    assertFalse(GameWebSocketServer.isOriginAllowed("https://play.otb-chess.app.evil.com", ALLOWED));
    assertFalse(GameWebSocketServer.isOriginAllowed("http://play.otb-chess.app", ALLOWED)); // wrong scheme
  }

  @Test
  void joinCodesAre12CharBase32AndUnique() {
    final Set<String> seen = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      final String code = GameWebSocketServer.generateJoinCode();
      assertEquals(12, code.length());
      assertTrue(code.matches("[A-Z2-7]{12}"), "not base32: " + code);
      assertTrue(seen.add(code), "duplicate join code: " + code);
    }
  }
}
