// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
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

  public DrawClaimResult processClaim(Board board, DrawClaimType type, String san) {
    return switch (type) {
      case THREEFOLD_ON_BOARD -> claimThreefoldOnBoard(board);
      case THREEFOLD_WITH_MOVE -> claimThreefoldWithMove(board, san);
      case FIFTY_MOVE_ON_BOARD -> claimFiftyMoveOnBoard(board);
      case FIFTY_MOVE_WITH_MOVE -> claimFiftyMoveWithMove(board, san);
    };
  }

  private DrawClaimResult claimThreefoldOnBoard(Board board) {
    if (board.canClaimThreefoldRepetitionRule()) {
      return DrawClaimResult.accepted("Your claim was accepted.",
          "Your opponent claimed a draw by threefold repetition. The claim was accepted.", THREEFOLD_ENDED);
    }
    return DrawClaimResult.rejected("Threefold repetition claim rejected. The position has not occurred three times.",
        "Your opponent claimed a draw by threefold repetition of the current position, but the claim is not valid."
            + " It still counts as a draw offer. Do you accept the draw?");
  }

  private DrawClaimResult claimThreefoldWithMove(Board board, String san) {
    if (san == null || san.isBlank()) {
      return DrawClaimResult.invalidMove("Please enter a move in SAN notation for the claim.");
    }
    final MoveSpecification moveSpec;
    try {
      moveSpec = LenientSanParser.parse(san, board).moveSpecification();
    } catch (final LenientSanParserValidationException e) {
      return DrawClaimResult.invalidMove(invalidClaimMoveMessage(san, e));
    }

    if (!board.canClaimThreefoldRepetitionRuleWithOwnMove()) {
      return DrawClaimResult.rejected(
          "Claim rejected, because no move from the current position can lead to a threefold"
              + " repetition. Please play.",
          "Your opponent claimed a draw by threefold repetition with the move " + san + ", but the claim is not"
              + " valid. It still counts as a draw offer. Do you accept the draw?");
    }

    board.move(moveSpec);
    final boolean isThreefold = board.isThreefoldRepetition();
    board.unmove();

    if (isThreefold) {
      return DrawClaimResult.accepted("Your claim was accepted after your move " + san + ".",
          "Your opponent requested a draw for threefold repetition after the move " + san + ".", THREEFOLD_ENDED);
    }

    return DrawClaimResult.rejectedWithMove(
        "Claim rejected, because there is no threefold repetition after the mentioned move " + san + ". Please play.",
        "Your opponent claimed a draw by threefold repetition with the move " + san + ", but the claim is not valid."
            + " It still counts as a draw offer. Do you accept the draw?",
        moveSpec);
  }

  private DrawClaimResult claimFiftyMoveOnBoard(Board board) {
    if (board.canClaimFiftyMoveRule()) {
      return DrawClaimResult.accepted("Your claim was accepted.",
          "Your opponent claimed a draw by the 50-move rule. The claim was accepted.", FIFTY_MOVE_ENDED);
    }
    return DrawClaimResult.rejected(
        "50-move rule claim rejected. 50 moves have not been played without a pawn move or capture.",
        "Your opponent claimed a draw by the 50-move rule on the current position, but the claim is not valid."
            + " It still counts as a draw offer. Do you accept the draw?");
  }

  private DrawClaimResult claimFiftyMoveWithMove(Board board, String san) {
    if (san == null || san.isBlank()) {
      return DrawClaimResult.invalidMove("Please enter a move in SAN notation for the claim.");
    }
    final MoveSpecification moveSpec;
    try {
      moveSpec = LenientSanParser.parse(san, board).moveSpecification();
    } catch (final LenientSanParserValidationException e) {
      return DrawClaimResult.invalidMove(invalidClaimMoveMessage(san, e));
    }

    if (!board.canClaimFiftyMoveRuleWithOwnMove()) {
      return DrawClaimResult.rejected(
          "Claim rejected, because no move from the current position can satisfy the 50-move" + " rule. Please play.",
          "Your opponent claimed a draw by the 50-move rule with the move " + san + ", but the claim is not valid."
              + " It still counts as a draw offer. Do you accept the draw?");
    }

    board.move(moveSpec);
    final boolean isFiftyMove = board.isFiftyMove();
    board.unmove();

    if (isFiftyMove) {
      return DrawClaimResult.accepted("Your claim was accepted after your move " + san + ".",
          "Your opponent requested a draw by the 50-move rule after the move " + san + ".", FIFTY_MOVE_ENDED);
    }

    return DrawClaimResult.rejectedWithMove(
        "Claim rejected, because the 50-move rule does not apply after the mentioned move " + san + ". Please play.",
        "Your opponent claimed a draw by the 50-move rule with the move " + san + ", but the claim is not valid."
            + " It still counts as a draw offer. Do you accept the draw?",
        moveSpec);
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
