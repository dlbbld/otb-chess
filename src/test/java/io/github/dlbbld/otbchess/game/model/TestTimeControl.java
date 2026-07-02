// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestTimeControl {

  private static TimeControl of(long minutes, long incrementSeconds) {
    return new TimeControl(minutes * 60 * 1000L, incrementSeconds * 1000L);
  }

  /**
   * FIDE discipline boundaries (Laws of Chess, Appendices A and B): expected duration = initial time
   * plus 60 times the increment. Blitz at 10 minutes or less; rapid above 10 and below 60 minutes;
   * classical at 60 minutes or more.
   */
  @Test
  void testFideCategoryBoundaries() {
    assertEquals("Blitz", of(3, 0).fideCategory());
    assertEquals("Blitz", of(5, 3).fideCategory()); // 5 + 3*60s = 8 min
    assertEquals("Blitz", of(10, 0).fideCategory()); // exactly 10 min is still blitz
    assertEquals("Rapid", of(10, 1).fideCategory()); // 11 min
    assertEquals("Rapid", of(15, 10).fideCategory()); // 25 min
    assertEquals("Rapid", of(30, 0).fideCategory()); // 30+0 is FIDE rapid, not classical
    assertEquals("Classical", of(30, 30).fideCategory()); // exactly 60 min is classical
    assertEquals("Classical", of(60, 0).fideCategory());
    assertEquals("Classical", of(90, 30).fideCategory());
  }

  @Test
  void testDisplayLabel() {
    assertEquals("5+3 • Blitz", of(5, 3).displayLabel());
    assertEquals("15+10 • Rapid", of(15, 10).displayLabel());
    assertEquals("30+0 • Rapid", of(30, 0).displayLabel());
    assertEquals("90+30 • Classical", of(90, 30).displayLabel());
  }

  /** Non-whole minutes are not producible from the lobby but must still render sanely. */
  @Test
  void testFractionalMinutesLabel() {
    assertEquals("1.5+0 • Blitz", new TimeControl(90 * 1000L, 0).displayLabel());
    assertEquals("0.5+1 • Blitz", new TimeControl(30 * 1000L, 1000L).displayLabel());
  }
}
