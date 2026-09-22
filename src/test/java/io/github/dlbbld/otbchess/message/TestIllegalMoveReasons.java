// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
