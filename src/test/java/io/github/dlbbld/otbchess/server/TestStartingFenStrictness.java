// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Starting positions the chess library refuses since ashlar-chess 21 and 22: positions no game can reach and FEN text
 * that is not written canonically. The reason shown to the player is the library's own.
 */
class TestStartingFenStrictness {

  static Stream<Arguments> refusedStartingPositions() {
    return Stream.of(
        Arguments.of("R3k2R/8/8/8/8/8/8/4K3 b - - 0 1",
            "the black king is in a double check that no single move could create"),
        Arguments.of("4k3/8/8/8/8/8/8/4K2R w - - 00 1", "the halfmove clock of \"00\" cannot have leading zeros"),
        Arguments.of("4k3/8/8/8/8/8/8/4K2R w - - 0 01", "the fullmove number of \"01\" cannot have leading zeros"),
        Arguments.of("4k3/8/8/8/8/8/44/4K2R w - - 0 1",
            "the rank description \"44\" uses consecutive digits for a single empty-square run"));
  }

  @ParameterizedTest
  @MethodSource("refusedStartingPositions")
  void aPositionNoGameCanReachOrANonCanonicalFenIsRefusedWithTheLibraryReason(String fen, String reason)
      throws Exception {
    final var error = assertThrows(RuntimeException.class, () -> OtbChessServer.parseStartingBoard(fen));
    assertEquals(reason, error.getMessage());

    final JsonObject json = validate(fen);
    assertFalse(json.get("valid").getAsBoolean());
    assertEquals("Invalid FEN: " + reason, json.get("message").getAsString());
  }

  @Test
  void aDoubleCheckThatAMoveCanCreateIsStillAccepted() throws Exception {
    // Discovered check: the knight left e4 for f6, opening the rook's file and checking itself.
    final String fen = "4k3/8/5N2/8/8/8/8/4RK2 b - - 0 1";
    assertTrue(OtbChessServer.parseStartingBoard(fen).isCheck());
    assertTrue(validate(fen).get("valid").getAsBoolean());
  }

  private static JsonObject validate(String fen) throws Exception {
    final var validate = OtbChessServer.class.getDeclaredMethod("validateFenResponse", String.class);
    validate.setAccessible(true);
    final byte[] body = (byte[]) validate.invoke(null, "fen=" + URLEncoder.encode(fen, StandardCharsets.UTF_8));
    return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
  }
}
