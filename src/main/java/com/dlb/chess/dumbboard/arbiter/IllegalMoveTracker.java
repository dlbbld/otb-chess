package com.dlb.chess.dumbboard.arbiter;

import com.dlb.chess.board.enums.Side;

/**
 * Tracks illegal move count per player.
 *
 * <p>Per FIDE rules the default limit is 2 (first illegal move costs 2 minutes; the
 * second one ends the game). The limit is configurable per game so players can set
 * it to anything in 1..10 or to unlimited (no game-loss on illegal moves) at the
 * lobby. Each illegal move always adds {@link #getPenaltyTimeMs()} to the opponent's
 * clock regardless of the limit.
 *
 * <p>Touch-move violations do not count as illegal moves.
 */
public class IllegalMoveTracker {

  /** FIDE default — second illegal move loses. */
  public static final int DEFAULT_MAX_ILLEGAL_MOVES = 2;

  /** Sentinel meaning "unlimited illegal moves — never lose by illegal-move count". */
  public static final int UNLIMITED = -1;

  private static final long PENALTY_TIME_MS = 2 * 60 * 1000; // 2 minutes

  private final int maxIllegalMoves;

  private int whiteIllegalMoveCount;
  private int blackIllegalMoveCount;

  public IllegalMoveTracker() {
    this(DEFAULT_MAX_ILLEGAL_MOVES);
  }

  public IllegalMoveTracker(int maxIllegalMoves) {
    if (maxIllegalMoves != UNLIMITED && maxIllegalMoves < 1) {
      throw new IllegalArgumentException("maxIllegalMoves must be >= 1 or UNLIMITED");
    }
    this.maxIllegalMoves = maxIllegalMoves;
    this.whiteIllegalMoveCount = 0;
    this.blackIllegalMoveCount = 0;
  }

  public int getMaxIllegalMoves() {
    return maxIllegalMoves;
  }

  public boolean isUnlimited() {
    return maxIllegalMoves == UNLIMITED;
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
    if (isUnlimited()) {
      return false;
    }
    return getIllegalMoveCount(side) >= maxIllegalMoves;
  }

  public long getPenaltyTimeMs() {
    return PENALTY_TIME_MS;
  }
}
