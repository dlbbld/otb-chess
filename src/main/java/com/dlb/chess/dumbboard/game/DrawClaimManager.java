package com.dlb.chess.dumbboard.game;

import com.dlb.chess.board.Board;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.common.model.MoveSpecification;
import com.dlb.chess.dumbboard.game.model.DrawClaimResult;
import com.dlb.chess.dumbboard.game.model.DrawClaimType;
import com.dlb.chess.san.exceptions.SanValidationException;
import com.dlb.chess.san.validate.SanValidation;

/**
 * Handles draw claims: threefold repetition and 50-move rule.
 *
 * <p>Two variants each:
 * <ul>
 *   <li>"On the board": the current position already qualifies</li>
 *   <li>"With move": a specified move will create the qualifying position.
 *       If the claim is rejected, the specified move must still be played.</li>
 * </ul>
 */
public class DrawClaimManager {

  /**
   * Processes a draw claim.
   *
   * @param board the current board state
   * @param type  the type of draw claim
   * @param san   the SAN move (only for "with move" claims, null otherwise)
   * @return the result of the claim
   */
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
      return DrawClaimResult.accepted("The game is drawn by threefold repetition.");
    }
    return DrawClaimResult.rejected("Threefold repetition claim rejected. The position has not occurred three times.");
  }

  private DrawClaimResult claimThreefoldWithMove(ApiBoard board, String san) {
    // Validate the SAN first. The player's input always gets feedback before any
    // claim-feasibility short-circuit fires: an invalid SAN is reported as invalidMove so the
    // player can correct it.
    final MoveSpecification moveSpec;
    try {
      moveSpec = SanValidation.validateSan(san, board);
    } catch (final SanValidationException e) {
      return DrawClaimResult.invalidMove("Invalid move: " + e.getMessage()
          + " Please enter a legal move for the claim.");
    }

    // SAN is legal. Short-circuit: if no legal move from this position can possibly lead to
    // a threefold repetition, reject the claim immediately without performing the move.
    if (!board.canClaimThreefoldRepetitionRuleWithOwnMove()) {
      return DrawClaimResult.rejected(
          "Claim rejected, because no move from the current position can lead to a threefold"
              + " repetition. Please play.");
    }

    // Temporarily perform the move and check
    board.performMove(moveSpec);
    final boolean isThreefold = board.isThreefoldRepetition();
    board.unperformMove();

    if (isThreefold) {
      // Claim accepted — the move will be performed by the game session.
      // Echo the SAN the player entered so the message clearly references their input.
      return DrawClaimResult.accepted(
          "Claim accepted. There would be a threefold repetition after the entered move "
              + san + ". The game is drawn.");
    }

    // Claim rejected — the player must still execute this move.
    return DrawClaimResult.rejectedWithMove(
        "Claim rejected, because there is no threefold repetition after the mentioned move "
            + san + ". Please play.",
        moveSpec);
  }

  private DrawClaimResult claimFiftyMoveOnBoard(ApiBoard board) {
    if (board.canClaimFiftyMoveRule()) {
      return DrawClaimResult.accepted("The game is drawn by the 50-move rule.");
    }
    return DrawClaimResult.rejected(
        "50-move rule claim rejected. 50 moves have not been played without a pawn move or capture.");
  }

  private DrawClaimResult claimFiftyMoveWithMove(ApiBoard board, String san) {
    // Validate the SAN first; bad input is reported as invalidMove before any
    // claim-feasibility short-circuit.
    final MoveSpecification moveSpec;
    try {
      moveSpec = SanValidation.validateSan(san, board);
    } catch (final SanValidationException e) {
      return DrawClaimResult.invalidMove("Invalid move: " + e.getMessage()
          + " Please enter a legal move for the claim.");
    }

    // SAN is legal. Short-circuit: clean-chess can determine in O(1) whether any legal move
    // from this position could satisfy the 50-move rule.
    if (!board.canClaimFiftyMoveRuleWithOwnMove()) {
      return DrawClaimResult.rejected(
          "Claim rejected, because no move from the current position can satisfy the 50-move"
              + " rule. Please play.");
    }

    // Temporarily perform the move and check
    board.performMove(moveSpec);
    final boolean isFiftyMove = board.isFiftyMove();
    board.unperformMove();

    if (isFiftyMove) {
      // Echo the SAN the player entered.
      return DrawClaimResult.accepted(
          "Claim accepted. The 50-move rule would apply after the entered move "
              + san + ". The game is drawn.");
    }

    return DrawClaimResult.rejectedWithMove(
        "Claim rejected, because the 50-move rule does not apply after the mentioned move "
            + san + ". Please play.",
        moveSpec);
  }
}
