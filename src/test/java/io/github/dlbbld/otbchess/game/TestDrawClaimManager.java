// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;

class TestDrawClaimManager {

  private final DrawClaimManager manager = new DrawClaimManager();

  /**
   * A rejected claim converts to a draw offer (FIDE 9.5). The opponent-side message must say what actually happened —
   * which claim, why it stands as an offer, and the accept question — for ALL four claim types, so the client can show
   * it verbatim with the Accept/Reject panel instead of a bare "your opponent offers a draw".
   */
  @Test
  void testRejectedClaimOpponentMessagesAnnounceClaimAndOffer() {
    final Board board = new Board(); // initial position: no repetition, no 50-move progress

    final DrawClaimResult threefoldOnBoard = manager.processClaim(board, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(threefoldOnBoard.accepted());
    assertTrue(threefoldOnBoard.opponentMessage().get()
        .startsWith("Your opponent claimed a draw by threefold repetition of the current position"));

    final DrawClaimResult fiftyOnBoard = manager.processClaim(board, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertFalse(fiftyOnBoard.accepted());
    assertTrue(fiftyOnBoard.opponentMessage().get()
        .startsWith("Your opponent claimed a draw by the 50-move rule on the current position"));

    final DrawClaimResult threefoldWithMove = manager.processClaim(board, DrawClaimType.THREEFOLD_WITH_MOVE, "Nf3");
    assertFalse(threefoldWithMove.accepted());
    assertTrue(threefoldWithMove.opponentMessage().get()
        .startsWith("Your opponent claimed a draw by threefold repetition with the move Nf3"));

    final DrawClaimResult fiftyWithMove = manager.processClaim(board, DrawClaimType.FIFTY_MOVE_WITH_MOVE, "Nf3");
    assertFalse(fiftyWithMove.accepted());
    assertTrue(fiftyWithMove.opponentMessage().get()
        .startsWith("Your opponent claimed a draw by the 50-move rule with the move Nf3"));

    // Every rejected variant carries the not-valid + still-an-offer + accept question tail.
    for (final DrawClaimResult result : new DrawClaimResult[] { threefoldOnBoard, fiftyOnBoard, threefoldWithMove,
        fiftyWithMove }) {
      final String message = result.opponentMessage().get();
      assertTrue(message.contains("but the claim is not valid"), message);
      assertTrue(message.contains("It still counts as a draw offer."), message);
      assertTrue(message.endsWith("Do you accept the draw?"), message);
      assertTrue(result.convertsToDrawOffer());
      // Merit rejections speak through the offer message, not the passive info window.
      assertTrue(result.opponentInfo().isEmpty());
    }
  }
}
