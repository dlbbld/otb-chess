package io.github.dlbbld.otbchess.core;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.model.LegalMove;

public class PositionComparator {

  // Returns all legal moves that produce the given after-position.
  // Typically returns 0 (no legal move matches) or 1 (exactly one match).
  public static Set<LegalMove> findMatchingMoves(Board board, BitboardPosition afterPosition) {
    final Set<LegalMove> matchingMoves = new TreeSet<>();
    final BitboardPosition beforePosition = board.getBitboardPosition();

    for (final LegalMove legalMove : board.getLegalMoves()) {
      final BitboardPosition positionAfterMove = beforePosition.afterMove(legalMove.moveSpecification(),
          legalMove.movingSide());
      if (positionAfterMove.equals(afterPosition)) {
        matchingMoves.add(legalMove);
      }
    }

    return matchingMoves;
  }

  // Returns the unique matching legal move, or empty if no match or multiple matches.
  public static Optional<LegalMove> findUniqueMatchingMove(Board board, BitboardPosition afterPosition) {
    final Set<LegalMove> matches = findMatchingMoves(board, afterPosition);
    if (matches.size() == 1) {
      return Optional.of(matches.iterator().next());
    }
    return Optional.empty();
  }
}
