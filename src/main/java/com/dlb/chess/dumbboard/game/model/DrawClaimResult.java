package com.dlb.chess.dumbboard.game.model;

import java.util.Optional;

import com.dlb.chess.common.model.MoveSpecification;

/**
 * Result of a draw claim attempt.
 *
 * @param accepted        whether the draw claim was accepted
 * @param message         human-readable message
 * @param moveToPerform   if a claim-with-move is rejected, the move that must still be played
 */
public record DrawClaimResult(
    boolean accepted,
    String message,
    Optional<MoveSpecification> moveToPerform) {

  public static DrawClaimResult accepted(String message) {
    return new DrawClaimResult(true, message, Optional.empty());
  }

  public static DrawClaimResult rejected(String message) {
    return new DrawClaimResult(false, message, Optional.empty());
  }

  public static DrawClaimResult rejectedWithMove(String message, MoveSpecification moveToPerform) {
    return new DrawClaimResult(false, message, Optional.of(moveToPerform));
  }
}
