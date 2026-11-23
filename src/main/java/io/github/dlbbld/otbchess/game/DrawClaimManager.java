// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import java.util.List;
import java.util.Optional;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.san.LenientSanParseResult;
import io.github.dlbbld.ashlarchess.san.LenientSanParser;
import io.github.dlbbld.ashlarchess.san.LenientSanParserValidationException;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;

/**
 * Handles draw claims: threefold repetition and 50-move rule.
 *
 * <p>
 * Each result carries three messages: one for the claiming player, one broadcast to the opponent, and (on acceptance) a
 * short game-end description for the result panel.
 */
public class DrawClaimManager {

  private static final String THREEFOLD_ENDED = "The game is drawn by threefold repetition.";
  private static final String FIFTY_MOVE_ENDED = "The game is drawn by the 50-move rule.";
  private static final String REJECTED_CLAIM_COUNTS_AS_OFFER =
      " The claim also counts as a draw offer for your opponent, which he can accept or reject.";
  private static final String REJECTED_CLAIM_IS_OFFER_TO_OPPONENT =
      " It still counts as a draw offer. Do you accept the draw?";
  private static final String REJECTED_CLAIM_IS_NO_OFFER = " It does not count as a draw offer: "
      + DrawOfferManager.DRAW_TOO_EARLY_REASON;

  /** A claim in a game in which a draw can be agreed: a rejected claim is a draw offer (FIDE 9.5). */
  public DrawClaimResult processClaim(Board board, DrawClaimType type, String san) {
    return processClaim(board, type, san, true);
  }

  /**
   * @param drawCanBeAgreed whether both players have made a move (FIDE 5.2.3). Before that a rejected claim is not
   *                        a draw offer: the claimer is told so, and the opponent is only informed
   */
  public DrawClaimResult processClaim(Board board, DrawClaimType type, String san, boolean drawCanBeAgreed) {
    return switch (type) {
      case THREEFOLD_ON_BOARD -> claimThreefoldOnBoard(board, drawCanBeAgreed);
      case THREEFOLD_WITH_MOVE -> claimThreefoldWithMove(board, san, drawCanBeAgreed);
      case FIFTY_MOVE_ON_BOARD -> claimFiftyMoveOnBoard(board, drawCanBeAgreed);
      case FIFTY_MOVE_WITH_MOVE -> claimFiftyMoveWithMove(board, san, drawCanBeAgreed);
    };
  }

  /**
   * A claim that was examined and is not valid.
   *
   * @param message         what the claimer is told about the claim itself
   * @param opponentMessage what the opponent is told about the claim itself
   * @param moveToPerform   the move of a claim with a move, which must still be played; else {@code null}
   */
  private static DrawClaimResult rejected(String message, String opponentMessage, MoveSpecification moveToPerform,
      boolean drawCanBeAgreed) {
    if (!drawCanBeAgreed) {
      return DrawClaimResult.rejectedBeforeDrawCanBeAgreed(message + REJECTED_CLAIM_IS_NO_OFFER,
          opponentMessage + REJECTED_CLAIM_IS_NO_OFFER, Optional.ofNullable(moveToPerform));
    }
    final String toClaimer = message + REJECTED_CLAIM_COUNTS_AS_OFFER;
    final String toOpponent = opponentMessage + REJECTED_CLAIM_IS_OFFER_TO_OPPONENT;
    return moveToPerform == null ? DrawClaimResult.rejected(toClaimer, toOpponent)
        : DrawClaimResult.rejectedWithMove(toClaimer, toOpponent, moveToPerform);
  }

  private DrawClaimResult claimThreefoldOnBoard(Board board, boolean drawCanBeAgreed) {
    if (board.canClaimThreefoldRepetitionRule()) {
      return DrawClaimResult.accepted("Your claim was accepted.",
          "Your opponent claimed a draw by threefold repetition. The claim was accepted.", THREEFOLD_ENDED);
    }
    return rejected("Threefold repetition claim rejected. The position has not occurred three times.",
        "Your opponent claimed a draw by threefold repetition of the current position, but the claim is not valid.",
        null, drawCanBeAgreed);
  }

  private DrawClaimResult claimThreefoldWithMove(Board board, String san, boolean drawCanBeAgreed) {
    if (san == null || san.isBlank()) {
      return DrawClaimResult.invalidMove("Please enter a move in SAN notation for the claim.");
    }
    final LenientSanParseResult parseResult;
    try {
      parseResult = LenientSanParser.parse(san, board);
    } catch (final LenientSanParserValidationException e) {
      return DrawClaimResult.invalidMove(invalidClaimMoveMessage(san, e));
    }
    final MoveSpecification moveSpec = parseResult.moveSpecification();
    final String canonicalSan = canonicalSan(board, moveSpec);

    if (!board.canClaimThreefoldRepetitionRuleWithOwnMove()) {
      return rejected(
          "Threefold claim for move " + canonicalSan
              + " was rejected because this does not result in a threefold repetition. Please play.",
          "Your opponent claimed a draw by threefold repetition with the move " + canonicalSan + ", but the claim is not"
              + " valid.",
          moveSpec, drawCanBeAgreed);
    }

    board.move(moveSpec);
    final boolean isThreefold = board.isThreefoldRepetition();
    board.unmove();

    if (isThreefold) {
      return DrawClaimResult.accepted("Your claim for threefold repetition for move " + canonicalSan
          + " was accepted.",
          "Your opponent requested a draw for threefold repetition after the move " + canonicalSan + ".",
          THREEFOLD_ENDED);
    }

    return rejected(
        "Threefold claim for move " + canonicalSan
            + " was rejected because this does not result in a threefold repetition. Please play.",
        "Your opponent claimed a draw by threefold repetition with the move " + canonicalSan
            + ", but the claim is not valid.",
        moveSpec, drawCanBeAgreed);
  }

  private DrawClaimResult claimFiftyMoveOnBoard(Board board, boolean drawCanBeAgreed) {
    if (board.canClaimFiftyMoveRule()) {
      return DrawClaimResult.accepted("Your claim was accepted.",
          "Your opponent claimed a draw by the 50-move rule. The claim was accepted.", FIFTY_MOVE_ENDED);
    }
    return rejected("50-move rule claim rejected. 50 moves have not been played without a pawn move or capture.",
        "Your opponent claimed a draw by the 50-move rule on the current position, but the claim is not valid.",
        null, drawCanBeAgreed);
  }

  private DrawClaimResult claimFiftyMoveWithMove(Board board, String san, boolean drawCanBeAgreed) {
    if (san == null || san.isBlank()) {
      return DrawClaimResult.invalidMove("Please enter a move in SAN notation for the claim.");
    }
    final LenientSanParseResult parseResult;
    try {
      parseResult = LenientSanParser.parse(san, board);
    } catch (final LenientSanParserValidationException e) {
      return DrawClaimResult.invalidMove(invalidClaimMoveMessage(san, e));
    }
    final MoveSpecification moveSpec = parseResult.moveSpecification();
    final String canonicalSan = canonicalSan(board, moveSpec);

    if (!board.canClaimFiftyMoveRuleWithOwnMove()) {
      return rejected(
          "50-move rule claim for move " + canonicalSan
              + " was rejected because the 50-move rule does not apply after this move. Please play.",
          "Your opponent claimed a draw by the 50-move rule with the move " + canonicalSan
              + ", but the claim is not valid.",
          moveSpec, drawCanBeAgreed);
    }

    board.move(moveSpec);
    final boolean isFiftyMove = board.isFiftyMove();
    board.unmove();

    if (isFiftyMove) {
      return DrawClaimResult.accepted("Your claim under the 50-move rule for move " + canonicalSan
          + " was accepted.",
          "Your opponent requested a draw by the 50-move rule after the move " + canonicalSan + ".",
          FIFTY_MOVE_ENDED);
    }

    return rejected(
        "50-move rule claim for move " + canonicalSan
            + " was rejected because the 50-move rule does not apply after this move. Please play.",
        "Your opponent claimed a draw by the 50-move rule with the move " + canonicalSan
            + ", but the claim is not valid.",
        moveSpec, drawCanBeAgreed);
  }

  private static String canonicalSan(Board board, MoveSpecification moveSpec) {
    final List<LegalMove> legalMoves = board.getLegalMoves();
    final List<String> legalMovesAsSan = board.getLegalMovesAsSan();
    for (int i = 0; i < legalMoves.size(); i++) {
      if (legalMoves.get(i).moveSpecification().equals(moveSpec)) {
        return legalMovesAsSan.get(i);
      }
    }
    throw new IllegalStateException("Resolved claim move is not legal in the current position.");
  }

  private static String invalidClaimMoveMessage(String san, LenientSanParserValidationException exception) {
    final String reason = userFacingInvalidMoveReason(exception.getMessage());
    final String prefix = "The claim was not considered because the presented move '" + san.trim()
        + "' is not legal";
    if (reason.isBlank()) {
      return prefix + ". You may make any legal move.";
    }
    return prefix + ": " + ensureSentence(reason) + " You may make any legal move.";
  }

  private static String userFacingInvalidMoveReason(String message) {
    if (message == null || message.isBlank()) {
      return "";
    }
    final String trimmed = message.trim();
    final String parserPrefix = "The lenient SAN parser could not parse";
    if (trimmed.startsWith(parserPrefix)) {
      final int colon = trimmed.indexOf(':');
      if (colon >= 0 && colon + 1 < trimmed.length()) {
        return trimmed.substring(colon + 1).trim();
      }
      return "";
    }
    return trimmed;
  }

  private static String ensureSentence(String text) {
    final String trimmed = text.trim();
    if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
      return trimmed;
    }
    return trimmed + ".";
  }
}
