// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import java.util.Optional;

import io.github.dlbbld.ashlarchess.board.enums.Side;

/**
 * Manages draw offer lifecycle per FIDE rule 9.1.2.1.
 *
 * <p>
 * Rules:
 * <ul>
 * <li>Correct time: after making a move, before pressing the clock</li>
 * <li>Wrong time: still valid, but escalating penalties (info → warning → game loss)</li>
 * <li>Opponent can accept until they touch a piece or press the clock</li>
 * <li>Repeated offers: separate escalation (info → warning → game loss)</li>
 * </ul>
 */
public class DrawOfferManager {

  private static final int PENALTY_INFO = 1;
  private static final int PENALTY_WARNING = 2;
  private static final int PENALTY_GAME_LOST = 3;
  private static final String DEFAULT_OFFERER_REJECTION_MESSAGE = "Your opponent rejected the draw offer.";

  private boolean drawOffered;
  private Side offeringSide;
  private boolean opponentTouchedPiece;
  private String offererRejectionMessage;
  /**
   * True iff the active offer was made at the correct time (offerer on move, after making a move). Used to choose the
   * right "offer-no-longer-acceptable" trigger: correct-time → opponent's TOUCH invalidates the offer (FIDE 9.1.2.1);
   * wrong-time → opponent's LEGAL RELEASE invalidates the offer instead (player was mid-thinking when the offer
   * arrived; merely touching a piece while deciding their move shouldn't penalise them).
   */
  private boolean wasOfferedAtCorrectTime;

  // Repeated offer tracking (per side, cumulative across the game)
  private int whiteRepeatCount;
  private int blackRepeatCount;

  // Wrong-time offer tracking — per side, PER MOVE (reset when a new turn starts, see
  // resetWrongTimeCountsForNewMove). A wrong-time offer is only semi-illegal (FIDE 9.1.2.1: the
  // offer is valid, the timing is admonishable), so unlike the claim ladders the count does NOT
  // carry over to the next move: the first wrong-time offer of every move is a real offer.
  private int whiteWrongTimeCount;
  private int blackWrongTimeCount;

  public DrawOfferManager() {
    this.drawOffered = false;
    this.offeringSide = Side.NONE;
    this.opponentTouchedPiece = false;
    this.wasOfferedAtCorrectTime = false;
    this.offererRejectionMessage = DEFAULT_OFFERER_REJECTION_MESSAGE;
    this.whiteRepeatCount = 0;
    this.blackRepeatCount = 0;
    this.whiteWrongTimeCount = 0;
    this.blackWrongTimeCount = 0;
  }

  /**
   * Result of a draw offer attempt.
   *
   * @param accepted                    true iff a real offer was registered (and must be forwarded to the opponent)
   * @param opponentInfo                passive information for the opponent (info window below the clock), or
   *                                    {@code null}: set when a wrong-time offer was NOT considered (the second on the
   *                                    same move, carrying the warning)
   * @param clearOpponentArbiterMessage true iff the opponent's active arbiter window should be cleared before showing
   *                                    the passive info (the old rejection message is stale once a not-considered repeat
   *                                    happens)
   */
  public record DrawOfferResult(boolean accepted, boolean gameLost, boolean isWrongTime, String arbiterMessage,
      String opponentInfo, boolean clearOpponentArbiterMessage) {

    public static DrawOfferResult ok() {
      return new DrawOfferResult(true, false, false, null, null, false);
    }

    public static DrawOfferResult wrongTime(String message) {
      return new DrawOfferResult(true, false, true, message, null, false);
    }

    /** Wrong-time offer NOT considered (second on the same move): not forwarded; both players informed. */
    public static DrawOfferResult wrongTimeNotConsidered(String message, String opponentInfo) {
      return new DrawOfferResult(false, false, true, message, opponentInfo, true);
    }

    public static DrawOfferResult wrongTimeGameLost(String message) {
      return new DrawOfferResult(false, true, true, message, null, false);
    }

    public static DrawOfferResult repeated(String message) {
      return new DrawOfferResult(false, false, false, message, null, false);
    }

    public static DrawOfferResult repeatedGameLost(String message) {
      return new DrawOfferResult(false, true, false, message, null, false);
    }
  }

  /**
   * Attempts to offer a draw at the correct time (after making a move, before pressing clock).
   */
  public DrawOfferResult offerDrawCorrectTime(Side side) {
    return offerDrawCorrectTime(side, DEFAULT_OFFERER_REJECTION_MESSAGE);
  }

  /**
   * Registers a correct-time draw offer with a custom message for the offerer if the opponent rejects it.
   */
  public DrawOfferResult offerDrawCorrectTime(Side side, String offererRejectionMessage) {
    // Check for repeated offer
    if (drawOffered && offeringSide == side) {
      return handleRepeatedOffer(side);
    }

    // Valid offer at correct time
    this.drawOffered = true;
    this.offeringSide = side;
    this.opponentTouchedPiece = false;
    this.wasOfferedAtCorrectTime = true;
    this.offererRejectionMessage = (offererRejectionMessage == null || offererRejectionMessage.isBlank())
        ? DEFAULT_OFFERER_REJECTION_MESSAGE
        : offererRejectionMessage;
    return DrawOfferResult.ok();
  }

  /**
   * Attempts to offer a draw at the wrong time (not the player's turn, or no move made). The offer is still valid per
   * FIDE 9.1.2.1, but escalating penalties apply per 11.5.
   */
  /**
   * @param offererHasMove whether the offering side has the move (case A: on move but no move attempted yet) vs. is not
   *                       on move (case B: opponent's turn). Used only to choose the wording of the first-info message.
   */
  public DrawOfferResult offerDrawWrongTime(Side side, boolean offererHasMove) {
    // Check for repeated offer first (the previous offer is still pending)
    if (drawOffered && offeringSide == side) {
      return handleRepeatedOffer(side);
    }

    final int count = incrementWrongTimeCount(side);

    if (count >= PENALTY_GAME_LOST) {
      return DrawOfferResult.wrongTimeGameLost(
          "You have been warned that you will lose the game when you offer a draw again on this move."
              + " As you have offered again, you lose the game.");
    }
    if (count == PENALTY_WARNING) {
      // The second wrong-time offer on the same move is NOT considered (not forwarded): the
      // opponent already answered the first one. Both players are informed; the offerer is
      // warned that the next one loses the game.
      return DrawOfferResult.wrongTimeNotConsidered(
          "You are again offering a draw at the wrong time. This offer was not considered."
              + " Warning: your next draw offer on this move loses the game.",
          "Your opponent again offered a draw at the wrong time. This offer was not considered, and they"
              + " have been warned: their next draw offer on this move loses them the game.");
    }

    // count == PENALTY_INFO: the FIRST wrong-time offer of the move is a real offer (FIDE
    // 9.1.2.1 — valid, only the timing is admonishable). Set it up; wrong-time →
    // release-piece-based invalidation, see wasOfferedAtCorrectTime field doc.
    this.drawOffered = true;
    this.offeringSide = side;
    this.opponentTouchedPiece = false;
    this.wasOfferedAtCorrectTime = false;
    this.offererRejectionMessage = DEFAULT_OFFERER_REJECTION_MESSAGE;

    // Wording depends on whether the offerer has the move.
    if (offererHasMove) {
      return DrawOfferResult.wrongTime("""
          Please note that when having the move, the draw offer should be made after making\
           your move and before pressing the clock. Not following this procedure could lead\
           to a warning. The offer still counts as a draw offer.""");
    }
    return DrawOfferResult
        .wrongTime("Please note that the draw offer should be made on your own turn. Not following this"
            + " procedure could lead to a warning. The offer still counts as a draw offer.");
  }

  /**
   * Resets the per-move wrong-time offer counts — called when a new turn starts. A wrong-time offer is semi-legal
   * (the offer itself is valid), so the escalation never carries over to the next move.
   */
  public void resetWrongTimeCountsForNewMove() {
    this.whiteWrongTimeCount = 0;
    this.blackWrongTimeCount = 0;
  }

  private DrawOfferResult handleRepeatedOffer(Side side) {
    final int count = incrementRepeatCount(side);

    if (count >= PENALTY_GAME_LOST) {
      return DrawOfferResult.repeatedGameLost("You have repeatedly offered a draw. You lose the game.");
    }
    if (count == PENALTY_WARNING) {
      return DrawOfferResult
          .repeated("You cannot repeat the draw offer. The next repeated draw offer will lose the game.");
    }
    // count == PENALTY_INFO
    return DrawOfferResult.repeated("You cannot repeat the draw offer on the same move.");
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
      return Optional.of("The draw offer is no longer valid because you touched a piece.");
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

  public String offererRejectionMessage() {
    return offererRejectionMessage;
  }

  /**
   * Clears the current draw offer. Called after rejection, clock press by opponent, or invalid move.
   */
  public void clearOffer() {
    this.drawOffered = false;
    this.offeringSide = Side.NONE;
    this.opponentTouchedPiece = false;
    this.wasOfferedAtCorrectTime = false;
    this.offererRejectionMessage = DEFAULT_OFFERER_REJECTION_MESSAGE;
  }

  public boolean wasOfferedAtCorrectTime() {
    return wasOfferedAtCorrectTime;
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
