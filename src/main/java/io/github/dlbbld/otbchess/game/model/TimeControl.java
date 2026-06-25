// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game.model;

/**
 * Represents a chess time control.
 *
 * @param initialTimeMs initial time per player in milliseconds
 * @param incrementMs   increment per move in milliseconds
 */
public record TimeControl(long initialTimeMs, long incrementMs) {

  // Standard presets
  public static final TimeControl BLITZ_3_0 = new TimeControl(3 * 60 * 1000L, 0);
  public static final TimeControl BLITZ_3_2 = new TimeControl(3 * 60 * 1000L, 2 * 1000L);
  public static final TimeControl BLITZ_5_0 = new TimeControl(5 * 60 * 1000L, 0);
  public static final TimeControl BLITZ_5_3 = new TimeControl(5 * 60 * 1000L, 3 * 1000L);
  public static final TimeControl RAPID_15_0 = new TimeControl(15 * 60 * 1000L, 0);
  public static final TimeControl RAPID_15_10 = new TimeControl(15 * 60 * 1000L, 10 * 1000L);
  public static final TimeControl CLASSICAL_30_0 = new TimeControl(30 * 60 * 1000L, 0);
}
