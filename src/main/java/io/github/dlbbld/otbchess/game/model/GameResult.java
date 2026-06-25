// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game.model;

import io.github.dlbbld.ashlarchess.board.enums.Side;

/**
 * Represents the result of a completed game.
 *
 * @param type        the reason the game ended
 * @param winner      the winning side, or Side.NONE for a draw
 * @param description human-readable description of the result
 */
public record GameResult(GameResultType type, Side winner, String description) {

  public boolean isDraw() {
    return winner == Side.NONE;
  }
}
