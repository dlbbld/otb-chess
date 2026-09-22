// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.touchmove;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.castling.CastlingAttemptDetector;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;

/**
 * Last-line check of the touch-move rule before a move enters the game.
 *
 * <p>
 * {@link TouchMoveEvaluator} picks the binding obligation through a chain of special rules (castling commitment,
 * specific capture, first touch) that return early. If one of them fires when it should not, the result is silent: the
 * move satisfies the wrong obligation and is accepted. This check deliberately does not reuse that chain. It re-derives
 * only the FIDE 4.3 core from the raw action sequence -- the first touched piece that can be moved or captured must be
 * moved or captured -- which every special rule narrows but never overrides. An accepted move that breaks it means the
 * evaluator is wrong, and the game must stop rather than record the move.
 */
public final class FirstTouchInvariant {

  private FirstTouchInvariant() {
  }

  /**
   * Throws if {@code acceptedMove} does not honour the first binding touch of the turn.
   *
   * @throws IllegalStateException when the arbiter accepted a move that breaks the touch-move rule
   */
  public static void verify(ActionSequence sequence, Board board, LegalMove acceptedMove) {
    final Optional<BoardEvent> firstBindingTouch = findFirstBindingTouch(sequence, board);
    if (firstBindingTouch.isPresent() && !honours(firstBindingTouch.get(), sequence.getSideToMove(), acceptedMove)) {
      final BoardEvent touch = firstBindingTouch.get();
      throw new IllegalStateException("Touch-move invariant violated: accepted " + acceptedMove.moveSpecification()
          + " although the first binding touch was " + touch.piece() + " on " + touch.square());
    }
  }

  /** Whether {@code acceptedMove} honours the first binding touch of the turn (true when nothing binds). */
  public static boolean isHonoured(ActionSequence sequence, Board board, LegalMove acceptedMove) {
    return findFirstBindingTouch(sequence, board)
        .map(touch -> honours(touch, sequence.getSideToMove(), acceptedMove)).orElse(true);
  }

  private static Optional<BoardEvent> findFirstBindingTouch(ActionSequence sequence, Board board) {
    final Side sideToMove = sequence.getSideToMove();
    final Set<LegalMove> legalMoves = new HashSet<>(board.getLegalMoves());
    final List<BoardEvent> events = sequence.getEvents();
    for (int i = 0; i < events.size(); i++) {
      // Specified exception: a failed castling attempt while the king cannot move binds neither king nor rook.
      if (CastlingAttemptDetector.isFailedAttemptWithNoLegalKingMove(events, i, sideToMove, legalMoves)) {
        i++;
        continue;
      }
      final BoardEvent event = events.get(i);
      if (!isTouch(event) || event.piece() == Piece.NONE) {
        continue;
      }
      final boolean binds = event.piece().getSide() == sideToMove
          ? legalMoves.stream().anyMatch(move -> origin(move) == event.square())
          : legalMoves.stream().anyMatch(move -> capturedSquare(move) == event.square());
      if (binds) {
        return Optional.of(event);
      }
    }
    return Optional.empty();
  }

  private static boolean honours(BoardEvent touch, Side sideToMove, LegalMove move) {
    return touch.piece().getSide() == sideToMove ? origin(move) == touch.square()
        : capturedSquare(move) == touch.square();
  }

  private static boolean isTouch(BoardEvent event) {
    return switch (event.type()) {
      case CLICK, REMOVE, DRAG_MOVE, DRAG_CAPTURE -> true;
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> false;
    };
  }

  /** The square the moving piece starts on; for castling, the king's square (castling is a king move). */
  private static Square origin(LegalMove move) {
    if (move.moveSpecification().isCastling()) {
      return move.moveSpecification().castlingMove().kingFromSquare(move.movingSide());
    }
    return move.moveSpecification().fromSquare();
  }

  /** The square of the captured piece, or {@link Square#NONE} for a non-capture. */
  private static Square capturedSquare(LegalMove move) {
    if (move.capturedPiece() == Piece.NONE) {
      return Square.NONE;
    }
    return move.isEnPassant() ? move.enPassantCapturedPawnSquare() : move.moveSpecification().toSquare();
  }
}
