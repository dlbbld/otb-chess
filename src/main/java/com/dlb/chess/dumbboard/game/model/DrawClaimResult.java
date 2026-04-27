package com.dlb.chess.dumbboard.game.model;

import java.util.Optional;

import com.dlb.chess.common.model.MoveSpecification;

/**
 * Result of a draw claim attempt.
 *
 * @param accepted        whether the draw claim was accepted
 * @param message         human-readable message
 * @param moveToPerform   if a claim-with-move is rejected, the move that must still be played
 * @param invalidMove     true iff the player supplied a SAN that did not validate as a legal
 *                        move on the current position (claim-with-move only). The frontend uses
 *                        this to keep the SAN-input panel visible and re-prompt for a legal move
 *                        instead of treating the claim attempt as fully consumed.
 */
public record DrawClaimResult(
    boolean accepted,
    String message,
    Optional<MoveSpecification> moveToPerform,
    boolean invalidMove) {

  public static DrawClaimResult accepted(String message) {
    return new DrawClaimResult(true, message, Optional.empty(), false);
  }

  public static DrawClaimResult rejected(String message) {
    return new DrawClaimResult(false, message, Optional.empty(), false);
  }

  public static DrawClaimResult rejectedWithMove(String message, MoveSpecification moveToPerform) {
    return new DrawClaimResult(false, message, Optional.of(moveToPerform), false);
  }

  /** Player's SAN failed validation against clean-chess on the current position. */
  public static DrawClaimResult invalidMove(String message) {
    return new DrawClaimResult(false, message, Optional.empty(), true);
  }
}
