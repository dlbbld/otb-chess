// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game.model;

import java.util.Optional;

import io.github.dlbbld.ashlarchess.board.MoveSpecification;

/**
 * @param accepted            whether the draw claim was accepted
 * @param message             message shown to the claiming player in the arbiter area
 * @param opponentMessage     message broadcast to the opponent ({@code null} when the opponent should not be notified —
 *                            e.g. invalid-SAN re-prompt)
 * @param gameEndDescription  short description used for the game-result panel when the claim ends the game
 *                            ({@code null} when the game continues)
 * @param moveToPerform       if a claim-with-move is rejected, the move that must still be played
 * @param invalidMove         true iff the player's SAN failed Ashlar Chess validation
 * @param convertsToDrawOffer true iff this rejection should be treated as a draw offer to the opponent (rejected
 *                            with-move / on-board claims, but not invalid-SAN or duplicate-claim rejections)
 * @param wrongTime           true iff the claim was made while not having the move. The client keeps the claim buttons
 *                            ENABLED in this case (teaching philosophy: the player may repeat the fault and learn from
 *                            the arbiter's escalation — rejection, warning, then loss of the game)
 */
public record DrawClaimResult(boolean accepted, String message, Optional<String> opponentMessage,
    Optional<String> gameEndDescription, Optional<MoveSpecification> moveToPerform, boolean invalidMove,
    boolean convertsToDrawOffer, boolean wrongTime) {

  public static DrawClaimResult accepted(String message, String opponentMessage, String gameEndDescription) {
    return new DrawClaimResult(true, message, Optional.of(opponentMessage), Optional.of(gameEndDescription),
        Optional.empty(), false, false, false);
  }

  public static DrawClaimResult rejected(String message, String opponentMessage) {
    return new DrawClaimResult(false, message, Optional.of(opponentMessage), Optional.empty(), Optional.empty(), false,
        true, false);
  }

  public static DrawClaimResult rejectedWithMove(String message, String opponentMessage,
      MoveSpecification moveToPerform) {
    return new DrawClaimResult(false, message, Optional.of(opponentMessage), Optional.empty(),
        Optional.of(moveToPerform), false, true, false);
  }

  /** Player's SAN failed validation. No opponent notification, no draw-offer conversion. */
  public static DrawClaimResult invalidMove(String message) {
    return new DrawClaimResult(false, message, Optional.empty(), Optional.empty(), Optional.empty(), true, false,
        false);
  }

  /**
   * Used for non-FIDE-claim error paths (e.g. game not in progress, second claim on same move): rejection without
   * converting to a draw offer.
   */
  public static DrawClaimResult rejectedWithoutDrawOffer(String message, String opponentMessage) {
    return new DrawClaimResult(false, message, Optional.of(opponentMessage), Optional.empty(), Optional.empty(), false,
        false, false);
  }

  /**
   * Claim made while not having the move (FIDE 9.2/9.3 require the move). No opponent notification, no draw-offer
   * conversion, and — via the {@code wrongTime} flag — no client-side lock of the claim buttons, so the player can
   * repeat the fault and the arbiter escalation (warning, then game loss) can play out.
   */
  public static DrawClaimResult wrongTime(String message) {
    return new DrawClaimResult(false, message, Optional.empty(), Optional.empty(), Optional.empty(), false, false,
        true);
  }

  /** Internal pre-claim error (no opponent notification). */
  public static DrawClaimResult error(String message) {
    return new DrawClaimResult(false, message, Optional.empty(), Optional.empty(), Optional.empty(), false, false,
        false);
  }
}
