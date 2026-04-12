package com.dlb.chess.dumbboard.arbiter;

import com.dlb.chess.board.enums.Side;

/**
 * Tracks illegal move count per player.
 *
 * <p>Per FIDE rules:
 * <ul>
 *   <li>First illegal move: +2 minutes to opponent's clock</li>
 *   <li>Second illegal move: game lost for the offending player</li>
 * </ul>
 *
 * <p>Touch-move violations do not count as illegal moves.
 */
public class IllegalMoveTracker {

  private static final int MAX_ILLEGAL_MOVES = 2;
  private static final long PENALTY_TIME_MS = 2 * 60 * 1000; // 2 minutes

  private int whiteIllegalMoveCount;
  private int blackIllegalMoveCount;

  public IllegalMoveTracker() {
    this.whiteIllegalMoveCount = 0;
    this.blackIllegalMoveCount = 0;
  }

  public void recordIllegalMove(Side side) {
    switch (side) {
      case WHITE -> whiteIllegalMoveCount++;
      case BLACK -> blackIllegalMoveCount++;
      default -> throw new IllegalArgumentException("Side must be WHITE or BLACK");
    }
  }

  public int getIllegalMoveCount(Side side) {
    return switch (side) {
      case WHITE -> whiteIllegalMoveCount;
      case BLACK -> blackIllegalMoveCount;
      default -> throw new IllegalArgumentException("Side must be WHITE or BLACK");
    };
  }

  public boolean isGameLost(Side side) {
    return getIllegalMoveCount(side) >= MAX_ILLEGAL_MOVES;
  }

  public long getPenaltyTimeMs() {
    return PENALTY_TIME_MS;
  }
}
