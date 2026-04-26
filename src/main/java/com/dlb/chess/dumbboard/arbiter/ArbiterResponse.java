package com.dlb.chess.dumbboard.arbiter;

import java.util.Optional;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.dumbboard.touchmove.TouchMoveObligation;
import com.dlb.chess.model.LegalMove;

/**
 * Represents the arbiter's response after evaluating a clock press or a mid-play event.
 *
 * @param type            the type of response
 * @param message         human-readable message for the player
 * @param acceptedMove    present only for MOVE_ACCEPTED — the legal move that was played
 * @param obligation      present for TOUCH_MOVE_VIOLATION — the unsatisfied obligation
 */
public record ArbiterResponse(
    ArbiterResponseType type,
    String message,
    Optional<LegalMove> acceptedMove,
    Optional<TouchMoveObligation> obligation,
    Optional<StaticPosition> restorePosition) {

  public static ArbiterResponse moveAccepted(LegalMove move) {
    return new ArbiterResponse(ArbiterResponseType.MOVE_ACCEPTED, "Move accepted.", Optional.of(move),
        Optional.empty(), Optional.empty());
  }

  public static ArbiterResponse touchMoveViolation(String message, TouchMoveObligation obligation) {
    return new ArbiterResponse(ArbiterResponseType.TOUCH_MOVE_VIOLATION, message, Optional.empty(),
        Optional.of(obligation), Optional.empty());
  }

  public static ArbiterResponse releasedPieceViolation(String message, StaticPosition restorePosition) {
    return new ArbiterResponse(ArbiterResponseType.RELEASED_PIECE_VIOLATION, message, Optional.empty(),
        Optional.empty(), Optional.of(restorePosition));
  }

  public static ArbiterResponse illegalMove(String message) {
    return new ArbiterResponse(ArbiterResponseType.ILLEGAL_MOVE, message, Optional.empty(), Optional.empty(),
        Optional.empty());
  }

  public static ArbiterResponse illegalMoveGameLost(String message) {
    return new ArbiterResponse(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, message, Optional.empty(),
        Optional.empty(), Optional.empty());
  }

  public static ArbiterResponse incompleteMove(String message) {
    return new ArbiterResponse(ArbiterResponseType.INCOMPLETE_MOVE, message, Optional.empty(), Optional.empty(),
        Optional.empty());
  }

  public static ArbiterResponse positionChange(String message) {
    return new ArbiterResponse(ArbiterResponseType.POSITION_CHANGE, message, Optional.empty(), Optional.empty(),
        Optional.empty());
  }

  public static ArbiterResponse revertOpponentPiece() {
    return positionChange("Position change: You moved an opponent's piece. That is not allowed. "
        + "Please restore the position.");
  }

  public static ArbiterResponse revertRestoration(String message) {
    return positionChange(message);
  }
}
