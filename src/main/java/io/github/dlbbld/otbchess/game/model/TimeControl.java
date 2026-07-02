// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game.model;

import java.util.Locale;

/**
 * Represents a chess time control.
 *
 * @param initialTimeMs initial time per player in milliseconds
 * @param incrementMs   increment per move in milliseconds
 */
public record TimeControl(long initialTimeMs, long incrementMs) {

  /**
   * The FIDE discipline for this time control. Per the FIDE Laws of Chess (Appendices A and B), the
   * expected game duration is the initial time plus 60 times the increment: <b>blitz</b> when that
   * is 10 minutes or less, <b>rapid</b> when more than 10 but less than 60 minutes, and
   * <b>classical</b> (FIDE: "standard") when 60 minutes or more. So 5+3 (8 min) is blitz, 30+0 is
   * rapid, and 30+30 (60 min) is classical.
   */
  public String fideCategory() {
    final long expectedDurationMs = initialTimeMs + 60 * incrementMs;
    if (expectedDurationMs <= 10 * 60 * 1000L) {
      return "Blitz";
    }
    if (expectedDurationMs < 60 * 60 * 1000L) {
      return "Rapid";
    }
    return "Classical";
  }

  /** Compact "minutes+increment" form, e.g. "5+3" or "15+10" (Lichess-style). */
  public String label() {
    return formatMinutes(initialTimeMs) + "+" + incrementMs / 1000;
  }

  /** Display label with the FIDE discipline, e.g. "5+3 • Blitz" (Lichess-style). */
  public String displayLabel() {
    return label() + " • " + fideCategory();
  }

  private static String formatMinutes(long ms) {
    final long seconds = ms / 1000;
    if (seconds % 60 == 0) {
      return String.valueOf(seconds / 60);
    }
    // Not producible from the lobby (whole minutes only), but the API allows arbitrary times:
    // fall back to fractional minutes with up to two decimals, e.g. 90s -> "1.5".
    final String text = String.format(Locale.ROOT, "%.2f", seconds / 60.0);
    return text.replaceAll("0+$", "").replaceAll("\\.$", "");
  }
}
