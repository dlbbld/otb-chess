package com.dlb.chess.dumbboard.game;

import java.util.Optional;

import com.dlb.chess.board.enums.Side;

/**
 * Manages draw offer lifecycle per FIDE rule 9.1.2.1.
 *
 * <p>Rules:
 * <ul>
 *   <li>Correct time: after making a move, before pressing the clock</li>
 *   <li>Wrong time: still valid, but escalating penalties (info → warning → game loss)</li>
 *   <li>Opponent can accept until they touch a piece or press the clock</li>
 *   <li>Repeated offers: separate escalation (info → warning → game loss)</li>
 * </ul>
 */
public class DrawOfferManager {

  private static final int PENALTY_INFO = 1;
  private static final int PENALTY_WARNING = 2;
  private static final int PENALTY_GAME_LOST = 3;

  private boolean drawOffered;
  private Side offeringSide;
  private boolean opponentTouchedPiece;

  // Repeated offer tracking (per side, cumulative across the game)
  private int whiteRepeatCount;
  private int blackRepeatCount;

  // Wrong-time offer tracking (per side, cumulative across the game)
  private int whiteWrongTimeCount;
  private int blackWrongTimeCount;

  public DrawOfferManager() {
    this.drawOffered = false;
    this.offeringSide = Side.NONE;
    this.opponentTouchedPiece = false;
    this.whiteRepeatCount = 0;
    this.blackRepeatCount = 0;
    this.whiteWrongTimeCount = 0;
    this.blackWrongTimeCount = 0;
  }

  /**
   * Result of a draw offer attempt.
   */
  public record DrawOfferResult(
      boolean accepted,
      boolean gameLost,
      boolean isWrongTime,
      String arbiterMessage) {

    public static DrawOfferResult ok() {
      return new DrawOfferResult(true, false, false, null);
    }

    public static DrawOfferResult wrongTime(String message) {
      return new DrawOfferResult(true, false, true, message);
    }

    public static DrawOfferResult wrongTimeGameLost(String message) {
      return new DrawOfferResult(false, true, true, message);
    }

    public static DrawOfferResult repeated(String message) {
      return new DrawOfferResult(false, false, false, message);
    }

    public static DrawOfferResult repeatedGameLost(String message) {
      return new DrawOfferResult(false, true, false, message);
    }
  }

  /**
   * Attempts to offer a draw at the correct time (after making a move, before pressing clock).
   */
  public DrawOfferResult offerDrawCorrectTime(Side side) {
    // Check for repeated offer
    if (drawOffered && offeringSide == side) {
      return handleRepeatedOffer(side);
    }

    // Valid offer at correct time
    this.drawOffered = true;
    this.offeringSide = side;
    this.opponentTouchedPiece = false;
    return DrawOfferResult.ok();
  }

  /**
   * Attempts to offer a draw at the wrong time (not the player's turn, or no move made).
   * The offer is still valid per FIDE 9.1.2.1, but escalating penalties apply per 11.5.
   */
  public DrawOfferResult offerDrawWrongTime(Side side) {
    // Check for repeated offer first
    if (drawOffered && offeringSide == side) {
      return handleRepeatedOffer(side);
    }

    final int count = incrementWrongTimeCount(side);

    // Set up the offer (it IS valid, just penalized)
    this.drawOffered = true;
    this.offeringSide = side;
    this.opponentTouchedPiece = false;

    if (count >= PENALTY_GAME_LOST) {
      return DrawOfferResult.wrongTimeGameLost(
          "You have repeatedly offered a draw at the wrong time. You lose the game.");
    }
    if (count == PENALTY_WARNING) {
      return DrawOfferResult.wrongTime(
          "You are offering a draw at the wrong time. "
              + "The next wrong-time draw offer will lose the game.");
    }
    // count == PENALTY_INFO
    return DrawOfferResult.wrongTime(
        "A draw offer should be made after completing your move and before pressing the clock. "
            + "The offer is still valid, but please follow the correct procedure.");
  }

  private DrawOfferResult handleRepeatedOffer(Side side) {
    final int count = incrementRepeatCount(side);

    if (count >= PENALTY_GAME_LOST) {
      return DrawOfferResult.repeatedGameLost(
          "You have repeatedly offered a draw. You lose the game.");
    }
    if (count == PENALTY_WARNING) {
      return DrawOfferResult.repeated(
          "You cannot repeat the draw offer. The next repeated draw offer will lose the game.");
    }
    // count == PENALTY_INFO
    return DrawOfferResult.repeated(
        "You cannot repeat the draw offer on the same move.");
  }

  /**
   * Records that the opponent touched a piece (loses right to accept the draw).
   */
  public void recordOpponentTouchedPiece() {
    this.opponentTouchedPiece = true;
  }

  /**
   * Attempts to accept the draw offer. Returns a message if acceptance is blocked.
   */
  public Optional<String> acceptDraw(Side side) {
    if (!drawOffered) {
      return Optional.of("No draw offer is active.");
    }
    if (side == offeringSide) {
      return Optional.of("You cannot accept your own draw offer.");
    }
    if (opponentTouchedPiece) {
      return Optional.of("Accepting the draw offer after touching a piece is no longer valid.");
    }
    return Optional.empty(); // Acceptance is valid
  }

  /**
   * Attempts to reject the draw offer.
   */
  public Optional<String> rejectDraw(Side side) {
    if (!drawOffered) {
      return Optional.of("No draw offer is active.");
    }
    if (opponentTouchedPiece) {
      return Optional.of("The draw offer is no longer valid because you touched a piece.");
    }
    clearOffer();
    return Optional.empty();
  }

  /**
   * Clears the current draw offer. Called after rejection, clock press by opponent, or invalid move.
   */
  public void clearOffer() {
    this.drawOffered = false;
    this.offeringSide = Side.NONE;
    this.opponentTouchedPiece = false;
  }

  /**
   * Drops the draw offer without counting it as repeated. Called when the move was invalid.
   */
  public void dropOfferDueToInvalidMove(Side side) {
    clearOffer();
  }

  public boolean isDrawOffered() {
    return drawOffered;
  }

  public Side getOfferingSide() {
    return offeringSide;
  }

  public boolean hasOpponentTouchedPiece() {
    return opponentTouchedPiece;
  }

  private int incrementRepeatCount(Side side) {
    return switch (side) {
      case WHITE -> ++whiteRepeatCount;
      case BLACK -> ++blackRepeatCount;
      default -> throw new IllegalArgumentException();
    };
  }

  private int incrementWrongTimeCount(Side side) {
    return switch (side) {
      case WHITE -> ++whiteWrongTimeCount;
      case BLACK -> ++blackWrongTimeCount;
      default -> throw new IllegalArgumentException();
    };
  }
}
