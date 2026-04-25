package com.dlb.chess.dumbboard.core;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.dlb.chess.board.Board;
import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.model.LegalMove;

public class PositionComparator {

  // Returns all legal moves that produce the given after-position.
  // Typically returns 0 (no legal move matches) or 1 (exactly one match).
  public static Set<LegalMove> findMatchingMoves(ApiBoard board, StaticPosition afterPosition) {
    final Set<LegalMove> matchingMoves = new TreeSet<>();
    final StaticPosition beforePosition = board.getStaticPosition();

    for (final LegalMove legalMove : board.getLegalMoveSet()) {
      final StaticPosition positionAfterMove = Board.createPositionAfterMove(beforePosition,
          legalMove.havingMove(), legalMove.moveSpecification());
      if (positionAfterMove.equals(afterPosition)) {
        matchingMoves.add(legalMove);
      }
    }

    return matchingMoves;
  }

  // Returns the unique matching legal move, or empty if no match or multiple matches.
  public static Optional<LegalMove> findUniqueMatchingMove(ApiBoard board, StaticPosition afterPosition) {
    final Set<LegalMove> matches = findMatchingMoves(board, afterPosition);
    if (matches.size() == 1) {
      return Optional.of(matches.iterator().next());
    }
    return Optional.empty();
  }
}
