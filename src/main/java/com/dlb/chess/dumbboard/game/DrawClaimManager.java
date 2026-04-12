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
    // Validate the SAN move
    final MoveSpecification moveSpec;
    try {
      moveSpec = SanValidation.validateSan(san, board);
    } catch (final SanValidationException e) {
      return DrawClaimResult.rejected("Invalid move: " + e.getMessage());
    }

    // Temporarily perform the move and check
    board.performMove(moveSpec);
    final boolean isThreefold = board.isThreefoldRepetition();
    board.unperformMove();

    if (isThreefold) {
      // Claim accepted — the move will be performed by the game session
      return DrawClaimResult.accepted("The game is drawn by threefold repetition.");
    }

    // Claim rejected — the player must still execute this move
    return DrawClaimResult.rejectedWithMove(
        "Threefold claim rejected. Please play the specified move.", moveSpec);
  }

  private DrawClaimResult claimFiftyMoveOnBoard(ApiBoard board) {
    if (board.canClaimFiftyMoveRule()) {
      return DrawClaimResult.accepted("The game is drawn by the 50-move rule.");
    }
    return DrawClaimResult.rejected(
        "50-move rule claim rejected. 50 moves have not been played without a pawn move or capture.");
  }

  private DrawClaimResult claimFiftyMoveWithMove(ApiBoard board, String san) {
    // Validate the SAN move
    final MoveSpecification moveSpec;
    try {
      moveSpec = SanValidation.validateSan(san, board);
    } catch (final SanValidationException e) {
      return DrawClaimResult.rejected("Invalid move: " + e.getMessage());
    }

    // Temporarily perform the move and check
    board.performMove(moveSpec);
    final boolean isFiftyMove = board.isFiftyMove();
    board.unperformMove();

    if (isFiftyMove) {
      return DrawClaimResult.accepted("The game is drawn by the 50-move rule.");
    }

    return DrawClaimResult.rejectedWithMove(
        "50-move rule claim rejected. Please play the specified move.", moveSpec);
  }
}
