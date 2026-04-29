package com.dlb.chess.dumbboard.game;

import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.common.model.MoveSpecification;
import com.dlb.chess.dumbboard.game.model.DrawClaimResult;
import com.dlb.chess.dumbboard.game.model.DrawClaimType;
import com.dlb.chess.san.exceptions.SanValidationException;
import com.dlb.chess.san.validate.SanValidation;

/**
 * Handles draw claims: threefold repetition and 50-move rule.
 *
 * <p>Each result carries three messages: one for the claiming player, one broadcast to the
 * opponent, and (on acceptance) a short game-end description for the result panel.
 */
public class DrawClaimManager {

  private static final String THREEFOLD_ENDED = "The game is drawn by threefold repetition.";
  private static final String FIFTY_MOVE_ENDED = "The game is drawn by the 50-move rule.";

  public DrawClaimResult processClaim(ApiBoard board, DrawClaimType type, String san) {
    return switch (type) {
      case THREEFOLD_ON_BOARD -> claimThreefoldOnBoard(board);
      case THREEFOLD_WITH_MOVE -> claimThreefoldWithMove(board, san);
      case FIFTY_MOVE_ON_BOARD -> claimFiftyMoveOnBoard(board);
      case FIFTY_MOVE_WITH_MOVE -> claimFiftyMoveWithMove(board, san);
    };
  }

  private DrawClaimResult claimThreefoldOnBoard(ApiBoard board) {
    if (board.canClaimThreefoldRepetitionRule()) {
      return DrawClaimResult.accepted(
          "Your claim was accepted.",
          "Your opponent claimed a draw by threefold repetition. The claim was accepted.",
          THREEFOLD_ENDED);
    }
    return DrawClaimResult.rejected(
        "Threefold repetition claim rejected. The position has not occurred three times.",
        "Your opponent claimed a draw by threefold repetition. The claim was rejected.");
  }

  private DrawClaimResult claimThreefoldWithMove(ApiBoard board, String san) {
    final MoveSpecification moveSpec;
    try {
      moveSpec = SanValidation.validateSan(san, board);
    } catch (final SanValidationException e) {
      return DrawClaimResult.invalidMove("Invalid move: " + e.getMessage()
          + " Please enter a legal move for the claim.");
    }

    if (!board.canClaimThreefoldRepetitionRuleWithOwnMove()) {
      return DrawClaimResult.rejected(
          "Claim rejected, because no move from the current position can lead to a threefold"
              + " repetition. Please play.",
          "Your opponent claimed a draw by threefold repetition after the move " + san
              + ". The claim was rejected.");
    }

    board.performMove(moveSpec);
    final boolean isThreefold = board.isThreefoldRepetition();
    board.unperformMove();

    if (isThreefold) {
      return DrawClaimResult.accepted(
          "Your claim was accepted.",
          "Your opponent requested a draw for threefold repetition after the move " + san + ".",
          THREEFOLD_ENDED);
    }

    return DrawClaimResult.rejectedWithMove(
        "Claim rejected, because there is no threefold repetition after the mentioned move "
            + san + ". Please play.",
        "Your opponent claimed a draw by threefold repetition after the move " + san
            + ". The claim was rejected.",
        moveSpec);
  }

  private DrawClaimResult claimFiftyMoveOnBoard(ApiBoard board) {
    if (board.canClaimFiftyMoveRule()) {
      return DrawClaimResult.accepted(
          "Your claim was accepted.",
          "Your opponent claimed a draw by the 50-move rule. The claim was accepted.",
          FIFTY_MOVE_ENDED);
    }
    return DrawClaimResult.rejected(
        "50-move rule claim rejected. 50 moves have not been played without a pawn move or capture.",
        "Your opponent claimed a draw by the 50-move rule. The claim was rejected.");
  }

  private DrawClaimResult claimFiftyMoveWithMove(ApiBoard board, String san) {
    final MoveSpecification moveSpec;
    try {
      moveSpec = SanValidation.validateSan(san, board);
    } catch (final SanValidationException e) {
      return DrawClaimResult.invalidMove("Invalid move: " + e.getMessage()
          + " Please enter a legal move for the claim.");
    }

    if (!board.canClaimFiftyMoveRuleWithOwnMove()) {
      return DrawClaimResult.rejected(
          "Claim rejected, because no move from the current position can satisfy the 50-move"
              + " rule. Please play.",
          "Your opponent claimed a draw by the 50-move rule after the move " + san
              + ". The claim was rejected.");
    }

    board.performMove(moveSpec);
    final boolean isFiftyMove = board.isFiftyMove();
    board.unperformMove();

    if (isFiftyMove) {
      return DrawClaimResult.accepted(
          "Your claim was accepted.",
          "Your opponent requested a draw by the 50-move rule after the move " + san + ".",
          FIFTY_MOVE_ENDED);
    }

    return DrawClaimResult.rejectedWithMove(
        "Claim rejected, because the 50-move rule does not apply after the mentioned move "
            + san + ". Please play.",
        "Your opponent claimed a draw by the 50-move rule after the move " + san
            + ". The claim was rejected.",
        moveSpec);
  }
}
