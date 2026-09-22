// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.PieceType;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.core.BitboardPositions;

/**
 * Last-line check of every position the arbiter asks the player to restore.
 *
 * <p>
 * A restore target is only ever the position at the start of the turn, the position after one legal move (a
 * released-piece commitment), or the king-first intermediate of a legal castling move. Anything else means the arbiter
 * has lost track of the position -- it once "restored" to the board with the king still on b1 after Ke1-b1 Ra1-c1,
 * skipping the illegal-move penalty -- and the game must stop instead of continuing from it.
 */
public final class RestoreTargetInvariant {

  private RestoreTargetInvariant() {
  }

  /**
   * Throws if {@code target} is not a position the arbiter may ask the player to restore.
   *
   * @param board  the board at the start of the turn
   * @param target the restore target
   * @throws IllegalStateException when the target cannot be justified from the turn-start position
   */
  public static void verify(Board board, BitboardPosition target) {
    if (!isValidTarget(board, target)) {
      throw new IllegalStateException("Restore-target invariant violated: the target is neither the turn-start"
          + " position, nor one legal move from it, nor a castling intermediate (" + board.getSideToMove()
          + " to move)");
    }
  }

  /** Whether {@code target} is the turn-start position, one legal move from it, or a legal castling intermediate. */
  public static boolean isValidTarget(Board board, BitboardPosition target) {
    final BitboardPosition turnStart = board.getBitboardPosition();
    if (target.equals(turnStart)) {
      return true;
    }
    final Side side = board.getSideToMove();
    for (final LegalMove legalMove : board.getLegalMoves()) {
      final MoveSpecification spec = legalMove.moveSpecification();
      if (target.equals(turnStart.afterMove(spec, side))) {
        return true;
      }
      if (spec.isCastling() && target.equals(BitboardPositions.from(turnStart)
          .createChangedPosition(spec.castlingMove().kingFromSquare(side), Piece.NONE)
          .createChangedPosition(spec.castlingMove().kingToSquare(side), Piece.of(side, PieceType.KING)).build())) {
        return true;
      }
    }
    return false;
  }
}
