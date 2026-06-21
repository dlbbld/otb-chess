package io.github.dlbbld.otbchess.game;

import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * Manages chess clocks for both players.
 *
 * <p>Uses {@code System.nanoTime()} for precise elapsed time measurement.
 * The clock runs on the server side. Updates are sent to clients periodically.
 */
public class ClockManager {

  private long whiteTimeRemainingMs;
  private long blackTimeRemainingMs;
  private final long incrementMs;
  private Side runningFor;
  private long lastTickNanos;

  public ClockManager(TimeControl timeControl) {
    this.whiteTimeRemainingMs = timeControl.initialTimeMs();
    this.blackTimeRemainingMs = timeControl.initialTimeMs();
    this.incrementMs = timeControl.incrementMs();
    this.runningFor = Side.NONE;
    this.lastTickNanos = 0;
  }

  /**
   * Starts the clock for the given side.
   */
  public void startClock(Side side) {
    if (side == Side.NONE) {
      throw new IllegalArgumentException("Cannot start clock for NONE");
    }
    tick(); // Update elapsed time before switching
    this.runningFor = side;
    this.lastTickNanos = System.nanoTime();
  }

  /**
   * Stops both clocks (pause).
   */
  public void stopClock() {
    tick();
    this.runningFor = Side.NONE;
  }

  /**
   * Switches the clock: stops the current side's clock (adding increment), starts the opponent's.
   * Called after a valid move is accepted.
   */
  public void switchClock() {
    tick();

    if (runningFor == Side.NONE) {
      throw new IllegalStateException("Cannot switch clock when no clock is running");
    }

    // Add increment to the side that just moved
    addTime(runningFor, incrementMs);

    // Switch to opponent
    final Side opponent = runningFor.getOppositeSide();
    this.runningFor = opponent;
    this.lastTickNanos = System.nanoTime();
  }

  /**
   * Adds penalty time to the given side's clock.
   */
  public void addPenaltyTime(Side side, long ms) {
    addTime(side, ms);
  }

  /**
   * Returns the remaining time for the given side in milliseconds.
   * Accounts for currently elapsed time if the clock is running.
   */
  public long getRemainingTimeMs(Side side) {
    tick();
    return switch (side) {
      case WHITE -> whiteTimeRemainingMs;
      case BLACK -> blackTimeRemainingMs;
      default -> throw new IllegalArgumentException("Side must be WHITE or BLACK");
    };
  }

  /**
   * Checks if the given side's time has expired (flag fall).
   */
  public boolean isFlagFall(Side side) {
    return getRemainingTimeMs(side) <= 0;
  }

  /**
   * Returns which side's clock is currently running.
   */
  public Side getRunningFor() {
    return runningFor;
  }

  /**
   * Updates elapsed time for the running clock.
   * Must be called before reading time values.
   */
  public void tick() {
    if (runningFor == Side.NONE) {
      return;
    }

    final long now = System.nanoTime();
    final long elapsedMs = (now - lastTickNanos) / 1_000_000;

    if (elapsedMs > 0) {
      subtractTime(runningFor, elapsedMs);
      lastTickNanos = now;
    }
  }

  private void addTime(Side side, long ms) {
    switch (side) {
      case WHITE -> whiteTimeRemainingMs += ms;
      case BLACK -> blackTimeRemainingMs += ms;
      default -> throw new IllegalArgumentException("Side must be WHITE or BLACK");
    }
  }

  private void subtractTime(Side side, long ms) {
    switch (side) {
      case WHITE -> whiteTimeRemainingMs = Math.max(0, whiteTimeRemainingMs - ms);
      case BLACK -> blackTimeRemainingMs = Math.max(0, blackTimeRemainingMs - ms);
      default -> throw new IllegalArgumentException("Side must be WHITE or BLACK");
    }
  }
}
