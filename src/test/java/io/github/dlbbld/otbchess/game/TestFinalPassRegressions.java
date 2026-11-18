// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.enums.Side;

/** Regressions for the findings of the final review pass before the release. */
class TestFinalPassRegressions {

  // ----- finding 1: an offer is answered by the other player, never by "no side" -----

  @Test
  void noSideCanNeitherAcceptNorRejectAnOffer() {
    final var offers = new DrawOfferManager();
    offers.offerDrawCorrectTime(Side.BLACK);

    assertFalse(offers.canAccept(Side.NONE));
    assertEquals("Only the player the offer was made to can answer it.", offers.acceptDraw(Side.NONE).orElseThrow());
    assertEquals("Only the player the offer was made to can answer it.", offers.rejectDraw(Side.NONE).orElseThrow());
    assertTrue(offers.isDrawOffered(), "the offer must still be pending");
    assertEquals(Side.BLACK, offers.getOfferingSide());

    assertTrue(offers.canAccept(Side.WHITE));
    assertTrue(offers.acceptDraw(Side.WHITE).isEmpty());
  }
}
