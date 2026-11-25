// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.InvalidMoveException;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.board.enums.Square;

class TestIllegalMoveReasons {

  @Test
  void restatesTheLibraryReasons() {
    assertEquals("it leaves the own king in check",
        IllegalMoveReasons.asStatement("it would leave the own king in check"));
    assertEquals("it exposes the own king to check",
        IllegalMoveReasons.asStatement("it would expose the own king to check"));
  }

  @Test
  void passesAnUnknownReasonThrough() {
    assertEquals("the knight cannot move in this way",
        IllegalMoveReasons.asStatement("the knight cannot move in this way"));
  }

  @Test
  void everyRestatementIsAStatementAboutTheMovePlayed() {
    final Map<String, String> statements = IllegalMoveReasons.statements();
    assertFalse(statements.isEmpty());
    for (final Map.Entry<String, String> entry : statements.entrySet()) {
      assertTrue(entry.getKey().contains("would"), "library reason without 'would': " + entry.getKey());
      assertFalse(entry.getValue().contains("would"), "restatement still predicts: " + entry.getValue());
    }
  }

  /**
   * The restatements exist only because the library words these two reasons conditionally. This pins the library's
   * own text for the version in {@code pom.xml}: when a library upgrade rewords them, this test fails and the pairs in
   * {@code illegal-move-reasons.properties} must follow (or go, if the library then states what the move does).
   */
  @Test
  void theLibraryStillWordsTheseReasonsConditionally() {
    // The bishop on e2 is pinned by the rook on e3.
    assertEquals("it would expose the own king to check",
        libraryReason("4k3/8/8/8/8/4r3/4B3/4K3 w - - 0 1", Square.E2, Square.D3));
    // The king is in check from a1, and the bishop move does not answer it.
    assertEquals("it would leave the own king in check",
        libraryReason("4k3/8/8/8/8/8/3B4/r3K3 w - - 0 1", Square.D2, Square.E3));

    assertTrue(IllegalMoveReasons.statements().containsKey("it would expose the own king to check"));
    assertTrue(IllegalMoveReasons.statements().containsKey("it would leave the own king in check"));
    assertEquals(2, IllegalMoveReasons.statements().size());
  }

  private static String libraryReason(String fen, Square from, Square to) {
    final Board board = Board.fromFenStrict(fen);
    return assertThrows(InvalidMoveException.class, () -> board.move(new MoveSpecification(from, to))).getMessage();
  }
}
