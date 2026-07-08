// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.PieceType;
import io.github.dlbbld.ashlarchess.board.enums.PromotionPieceType;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.UpdateSquare;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.board.InvalidMoveException;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse.IllegalMoveDetail;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse.ReleasedPieceContext;
import io.github.dlbbld.otbchess.castling.CastlingAttemptDetector;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.core.PositionComparator;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.event.BoardEventType;
import io.github.dlbbld.otbchess.touchmove.TouchMoveEvaluator;
import io.github.dlbbld.otbchess.touchmove.TouchMoveObligation;
import io.github.dlbbld.otbchess.touchmove.TouchMoveType;

/**
 * The arbiter engine combines touch-move evaluation with position comparison.
 *
 * <p>
 * Called when the player presses the clock (or offers a draw, which also triggers evaluation). Performs two-layer
 * evaluation:
 * <ol>
 * <li>Layer 1 (Position Comparison): does the board state correspond to a legal move?</li>
 * <li>Layer 2 (Touch-Move): if a touch-move obligation exists, does the move satisfy it?</li>
 * </ol>
 */
public class ArbiterEngine {

  private final IllegalMoveTracker illegalMoveTracker;

  public ArbiterEngine() {
    this.illegalMoveTracker = new IllegalMoveTracker();
  }

  public ArbiterEngine(int maxIllegalMoves) {
    this.illegalMoveTracker = new IllegalMoveTracker(maxIllegalMoves);
  }

  public ArbiterEngine(IllegalMoveTracker illegalMoveTracker) {
    this.illegalMoveTracker = illegalMoveTracker;
  }

  public IllegalMoveTracker getIllegalMoveTracker() {
    return illegalMoveTracker;
  }

  /**
   * Side-effect-free check used by the auto-end path to find out whether a released-piece commitment (FIDE 4.7)
   * currently binds the player to a final position that the given {@code afterPosition} does not satisfy. If true, no
   * code that bypasses {@link #evaluateClockPress} should accept the position as a move — the committed move must be
   * played via the normal clock-press flow, where the violation is reported and the recovery flow runs.
   */
  public boolean hasReleasedPieceViolation(Board board, BitboardPosition afterPosition, ActionSequence sequence) {
    return findReleasedPieceViolation(board, afterPosition, sequence).isPresent();
  }

  /**
   * Side-effect-free check: does the action sequence contain at least one release event whose resulting position is
   * part of a legal move from {@code positionBeforeTurn}? Equivalent to "has the on-move player committed to a move via
   * FIDE 4.7?" without comparing against any particular {@code afterPosition}.
   */
  public boolean hasReleasedPieceCommitment(Board board, ActionSequence sequence) {
    return findReleasedPieceCommitmentPosition(board, sequence).isPresent();
  }

  /**
   * Returns the physical position after the first release that committed the player to a legal move from the turn
   * start. Mid-play interventions use this as their restoration target: if the player already completed a legal move
   * and then disturbed the board, Revert must undo only the later disturbance.
   */
  public Optional<BitboardPosition> findReleasedPieceCommitmentPosition(Board board, ActionSequence sequence) {
    BitboardPosition currentPosition = board.getBitboardPosition();
    for (final BoardEvent event : sequence.getEventsSinceReleasedPieceRuleReset()) {
      final BitboardPosition beforeEvent = currentPosition;
      currentPosition = applyEvent(currentPosition, event);
      if (isReleaseOnBoard(event)) {
        final ReleaseCommitment commitment = findCommitmentForRelease(board, event, beforeEvent);
        if (!commitment.allowedFinalPositions().isEmpty()) {
          return Optional.of(currentPosition);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Evaluates the board state when the player presses the clock.
   *
   * @param board         the board state before this turn's move
   * @param afterPosition the physical board state after the player's manipulations
   * @param sequence      the action sequence recorded during this turn
   * @return the arbiter's response
   */
  public ArbiterResponse evaluateClockPress(Board board, BitboardPosition afterPosition, ActionSequence sequence) {
    final Side sideToMove = board.getSideToMove();

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);
    final Optional<ArbiterResponse> unfinishedCastlingTouch = evaluateUnfinishedCastlingTouch(board, afterPosition,
        sequence, obligation);
    if (unfinishedCastlingTouch.isPresent()) {
      return unfinishedCastlingTouch.get();
    }

    final Optional<ArbiterResponse> opponentCaptureTouchViolation = evaluateOpponentCaptureTouchBeforeRelease(board,
        afterPosition, obligation);
    if (opponentCaptureTouchViolation.isPresent()) {
      return opponentCaptureTouchViolation.get();
    }

    final Optional<ReleasedPieceLock> releasedPieceViolation = findReleasedPieceViolation(board, afterPosition,
        sequence);
    if (releasedPieceViolation.isPresent()) {
      final ReleasedPieceLock lock = releasedPieceViolation.get();
      final Optional<ArbiterResponse.RookFirstCastlingContext> rookFirstCastling = findRookFirstCastlingContext(board,
          afterPosition, lock);
      if (rookFirstCastling.isPresent()) {
        return ArbiterResponse.releasedPieceViolationRookFirstCastling(rookFirstCastling.get(),
            lock.releasePosition());
      }
      // Castling-only commitment ⇒ the player must complete the castling, not restore
      // the king. The message points them at the rook's destination instead of telling
      // them (misleadingly) to "put the king back" — the king is already on the right
      // square.
      final Optional<LegalMove> castlingMove = lock.uniqueCastlingMove();
      if (castlingMove.isPresent()) {
        final MoveSpecification spec = castlingMove.get().moveSpecification();
        final Side castlingSide = castlingMove.get().movingSide();
        final Square rookFrom = CastlingAttemptDetector.calculateRookCastlingFrom(castlingSide, spec.castlingMove());
        final Square rookTo = CastlingAttemptDetector.calculateRookCastlingTo(castlingSide, spec.castlingMove());
        final String castlingDirection = spec.castlingMove() == CastlingMove.KING_SIDE ? "kingside" : "queenside";
        return ArbiterResponse.releasedPieceViolationCastling(new ArbiterResponse.ReleasedPieceCastlingContext(
            lock.piece(), lock.square(), castlingDirection, rookFrom, rookTo), lock.releasePosition());
      }
      return ArbiterResponse.releasedPieceViolation(
          new ReleasedPieceContext(lock.piece(), lock.square(), lock.fromSquare(),
              lock.releasedPieceDisplacedAfterRelease()),
          isReleaseOriginAmbiguous(board, lock), lock.releasePosition());
    }

    // FIDE 7.5.3: pressing the clock without making a move (board unchanged) is considered and
    // penalised as an illegal move — it counts toward the illegal-move limit and carries the
    // standard penalty. There is nothing to restore, so the message asks for a move instead.
    if (board.getBitboardPosition().equals(afterPosition)) {
      illegalMoveTracker.recordIllegalMove(sideToMove);
      final String reason = "the clock was pressed without a move being made (FIDE 7.5.3)";
      final IllegalMoveDetail detail = new IllegalMoveDetail(Optional.of(reason), Optional.of(reason), sideToMove,
          illegalMoveTracker.getIllegalMoveCount(sideToMove), illegalMoveTracker.getMaxIllegalMoves(),
          illegalMoveTracker.isUnlimited(), true);
      if (illegalMoveTracker.isGameLost(sideToMove)) {
        return ArbiterResponse.illegalMoveGameLost(detail);
      }
      return ArbiterResponse.illegalMove(detail);
    }

    // Layer 1: Position Comparison — find matching legal move
    final Set<LegalMove> matchingMoves = PositionComparator.findMatchingMoves(board, afterPosition);

    if (matchingMoves.isEmpty()) {
      // No legal move produces this position
      return handleIllegalMove(board, afterPosition, sequence, sideToMove);
    }

    // We have at least one matching move. Pick the first (should be unique in practice).
    final LegalMove matchedMove = matchingMoves.iterator().next();

    // Layer 2: Touch-Move Check
    if (obligation.isPresent()) {
      if (!TouchMoveEvaluator.satisfiesObligation(obligation.get(), matchedMove)) {
        return handleTouchMoveViolation(obligation.get());
      }
    }

    // Move accepted
    return ArbiterResponse.moveAccepted(matchedMove);
  }

  private Optional<ArbiterResponse> evaluateOpponentCaptureTouchBeforeRelease(Board board,
      BitboardPosition afterPosition, Optional<TouchMoveObligation> obligation) {
    if (obligation.isEmpty()) {
      return Optional.empty();
    }
    if (obligation.get().type() != TouchMoveType.OPPONENT_PIECE
        && obligation.get().type() != TouchMoveType.SPECIFIC_CAPTURE) {
      return Optional.empty();
    }
    final BitboardPosition comparisonPosition = restoreTouchedOpponentPieceIfRemoved(afterPosition, obligation.get());
    final Set<LegalMove> matchingMoves = PositionComparator.findMatchingMoves(board, comparisonPosition);
    if (matchingMoves.isEmpty()) {
      return Optional.empty();
    }
    for (final LegalMove matchedMove : matchingMoves) {
      if (TouchMoveEvaluator.satisfiesObligation(obligation.get(), matchedMove)) {
        return Optional.empty();
      }
    }
    return Optional.of(handleTouchMoveViolation(obligation.get()));
  }

  private static BitboardPosition restoreTouchedOpponentPieceIfRemoved(BitboardPosition afterPosition,
      TouchMoveObligation obligation) {
    final Square square = obligation.type() == TouchMoveType.SPECIFIC_CAPTURE ? obligation.toSquare()
        : obligation.square();
    final Piece piece = obligation.type() == TouchMoveType.SPECIFIC_CAPTURE ? obligation.capturedPiece()
        : obligation.piece();
    if (square == Square.NONE || piece == Piece.NONE || afterPosition.get(square) != Piece.NONE) {
      return afterPosition;
    }
    return BitboardPositions.from(afterPosition).createChangedPosition(square, piece).build();
  }

  private record ReleasedPieceLock(BitboardPosition releasePosition, Set<BitboardPosition> allowedFinalPositions,
      Set<LegalMove> committedMoves, Piece piece, Square square, Square fromSquare,
      boolean releasedPieceDisplacedAfterRelease) {

    ReleasedPieceLock withReleasedPieceDisplacedAfterRelease() {
      if (releasedPieceDisplacedAfterRelease) {
        return this;
      }
      return new ReleasedPieceLock(releasePosition, allowedFinalPositions, committedMoves, piece, square, fromSquare,
          true);
    }

    /**
     * True iff every legal move consistent with the release is a castling move (i.e. the release commits the player
     * exclusively to castling). In that case the player must complete the castling rather than restore the released
     * piece, and the message should reflect that.
     */
    boolean isCastlingOnlyCommitment() {
      if (committedMoves.isEmpty()) {
        return false;
      }
      return committedMoves.stream().allMatch(m -> m.moveSpecification().isCastling());
    }

    /** The unique castling move the player is committed to, when {@link #isCastlingOnlyCommitment()}. */
    Optional<LegalMove> uniqueCastlingMove() {
      if (committedMoves.size() == 1 && isCastlingOnlyCommitment()) {
        return Optional.of(committedMoves.iterator().next());
      }
      return Optional.empty();
    }
  }

  private static Optional<ArbiterResponse> evaluateUnfinishedCastlingTouch(Board board, BitboardPosition afterPosition,
      ActionSequence sequence, Optional<TouchMoveObligation> obligation) {
    if (board.getBitboardPosition().equals(afterPosition)) {
      return Optional.empty();
    }
    if (obligation.isEmpty() || obligation.get().type() != TouchMoveType.CASTLING) {
      return Optional.empty();
    }
    final CastlingMove castlingMove = obligation.get().castlingMove();
    if (!hasExplicitKingThenRookTouch(sequence, board.getSideToMove(), castlingMove)) {
      return Optional.empty();
    }
    final Optional<LegalMove> legalCastlingMove = board.getLegalMoves().stream()
        .filter(move -> move.moveSpecification().isCastling())
        .filter(move -> move.moveSpecification().castlingMove() == castlingMove)
        .findFirst();
    if (legalCastlingMove.isEmpty()) {
      return Optional.empty();
    }
    final BitboardPosition castledPosition = board.getBitboardPosition()
        .afterMove(legalCastlingMove.get().moveSpecification(), board.getSideToMove());
    if (afterPosition.equals(castledPosition)) {
      return Optional.empty();
    }
    return Optional.of(ArbiterResponse.touchMoveViolation(obligation.get(),
        castlingIntermediatePosition(board, castlingMove).filter(afterPosition::equals).orElse(null)));
  }

  private static boolean hasExplicitKingThenRookTouch(ActionSequence sequence, Side sideToMove,
      CastlingMove castlingMove) {
    final Square kingFrom = castlingMove.kingFromSquare(sideToMove);
    final Square rookFrom = castlingMove.rookFromSquare(sideToMove);
    final Piece king = Piece.of(sideToMove, PieceType.KING);
    final Piece rook = Piece.of(sideToMove, PieceType.ROOK);
    boolean kingClicked = false;
    for (final BoardEvent event : sequence.getEvents()) {
      if (event.type() != BoardEventType.CLICK) {
        continue;
      }
      if (!kingClicked) {
        kingClicked = event.piece() == king && event.square() == kingFrom;
      } else if (event.piece() == rook && event.square() == rookFrom) {
        return true;
      }
    }
    return false;
  }

  private static Optional<BitboardPosition> castlingIntermediatePosition(Board board, CastlingMove castlingMove) {
    final Side sideToMove = board.getSideToMove();
    final Square kingFrom = castlingMove.kingFromSquare(sideToMove);
    final Square kingTo = castlingMove.kingToSquare(sideToMove);
    final Piece king = Piece.of(sideToMove, PieceType.KING);
    if (board.getBitboardPosition().get(kingFrom) != king || board.getBitboardPosition().get(kingTo) != Piece.NONE) {
      return Optional.empty();
    }
    return Optional.of(BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(kingFrom, Piece.NONE)
        .createChangedPosition(kingTo, king)
        .build());
  }

  private static Optional<ReleasedPieceLock> findReleasedPieceViolation(Board board, BitboardPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> attemptedCastling = inferPhysicalCastlingAttempt(board, afterPosition,
        sequence.getEventsSinceReleasedPieceRuleReset());
    if (attemptedCastling.isPresent()
        && shouldBypassReleasedPieceForInvalidCastlingAttempt(board, attemptedCastling.get())) {
      return Optional.empty();
    }

    BitboardPosition currentPosition = board.getBitboardPosition();
    ReleasedPieceLock firstReleasedLegalPosition = null;

    for (final BoardEvent event : sequence.getEventsSinceReleasedPieceRuleReset()) {
      if (firstReleasedLegalPosition != null && displacesReleasedPiece(event, firstReleasedLegalPosition)) {
        firstReleasedLegalPosition = firstReleasedLegalPosition.withReleasedPieceDisplacedAfterRelease();
      }

      final BitboardPosition beforeEvent = currentPosition;
      currentPosition = applyEvent(currentPosition, event);

      if (firstReleasedLegalPosition == null && isReleaseOnBoard(event)) {
        final ReleaseCommitment commitment = findCommitmentForRelease(board, event, beforeEvent);
        if (!commitment.allowedFinalPositions().isEmpty()) {
          firstReleasedLegalPosition = new ReleasedPieceLock(currentPosition, commitment.allowedFinalPositions(),
              commitment.committedMoves(), event.piece(), event.targetSquare(), event.square(), false);
        }
      }
    }

    if (firstReleasedLegalPosition != null
        && !firstReleasedLegalPosition.allowedFinalPositions().contains(afterPosition)) {
      return Optional.of(firstReleasedLegalPosition);
    }
    return Optional.empty();
  }

  private static boolean displacesReleasedPiece(BoardEvent event, ReleasedPieceLock lock) {
    if (lock.square() == Square.NONE || lock.piece() == Piece.NONE) {
      return false;
    }
    final boolean pickedUpFromReleaseSquare = switch (event.type()) {
      case DRAG_MOVE, DRAG_CAPTURE, REMOVE -> event.piece() == lock.piece() && event.square() == lock.square();
      case CLICK, RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> false;
    };
    if (pickedUpFromReleaseSquare) {
      return true;
    }
    return event.targetSquare() == lock.square() && event.displacedPiece() == lock.piece();
  }

  private static Optional<ArbiterResponse.RookFirstCastlingContext> findRookFirstCastlingContext(Board board,
      BitboardPosition afterPosition, ReleasedPieceLock lock) {
    final Side sideToMove = board.getSideToMove();
    if (lock.piece() != Piece.of(sideToMove, PieceType.ROOK) || lock.fromSquare() == Square.NONE
        || afterPosition.get(lock.square()) != lock.piece()) {
      return Optional.empty();
    }

    for (final CastlingMove castlingMove : List.of(CastlingMove.KING_SIDE, CastlingMove.QUEEN_SIDE)) {
      if (!isCastlingLegalOnSide(board, castlingMove)) {
        continue;
      }
      final Square rookFrom = castlingMove.rookFromSquare(sideToMove);
      final Square rookTo = castlingMove.rookToSquare(sideToMove);
      if (lock.fromSquare() != rookFrom || lock.square() != rookTo) {
        continue;
      }
      final Square kingFrom = castlingMove.kingFromSquare(sideToMove);
      final Square kingTo = castlingMove.kingToSquare(sideToMove);
      final Piece king = Piece.of(sideToMove, PieceType.KING);
      if (lock.releasePosition().get(kingFrom) == king && lock.releasePosition().get(kingTo) == Piece.NONE
          && afterPosition.get(kingFrom) == Piece.NONE && afterPosition.get(kingTo) == king
          && differsOnlyOn(lock.releasePosition(), afterPosition, kingFrom, kingTo)) {
        return Optional.of(new ArbiterResponse.RookFirstCastlingContext(lock.piece(), rookFrom, rookTo, kingFrom));
      }
    }
    return Optional.empty();
  }

  private static boolean isCastlingLegalOnSide(Board board, CastlingMove castlingMove) {
    return board.getLegalMoves().stream()
        .anyMatch(move -> move.moveSpecification().isCastling()
            && move.moveSpecification().castlingMove() == castlingMove);
  }

  private static boolean differsOnlyOn(BitboardPosition first, BitboardPosition second, Square firstSquare,
      Square secondSquare) {
    for (final Square square : Square.values()) {
      if (square == Square.NONE || square == firstSquare || square == secondSquare) {
        continue;
      }
      if (first.get(square) != second.get(square)) {
        return false;
      }
    }
    return first.get(firstSquare) != second.get(firstSquare) && first.get(secondSquare) != second.get(secondSquare);
  }

  /**
   * Captures both the set of allowed final positions and the set of legal moves that the release commits the player to.
   * The legal-move set lets the message-building code recognise a castling-only commitment and produce the
   * castling-specific arbiter message.
   */
  private record ReleaseCommitment(Set<BitboardPosition> allowedFinalPositions, Set<LegalMove> committedMoves) {
  }

  private static ReleaseCommitment findCommitmentForRelease(Board board, BoardEvent event,
      BitboardPosition positionBeforeEvent) {
    final Set<BitboardPosition> positions = new HashSet<>();
    final Set<LegalMove> moves = new HashSet<>();
    for (final LegalMove legalMove : board.getLegalMoves()) {
      if (isReleasePartOfLegalMove(board.getSideToMove(), event, legalMove, positionBeforeEvent,
          board.getBitboardPosition())) {
        moves.add(legalMove);
        positions.add(board.getBitboardPosition().afterMove(legalMove.moveSpecification(), board.getSideToMove()));
      }
    }
    return new ReleaseCommitment(positions, moves);
  }

  /**
   * True when "the {piece} on {square}" alone would not identify the released piece: more than one piece of the same
   * kind could have legally reached the release square (e.g. knights on c3 and g5 both reaching e4), so the violation
   * message must also name the origin square — SAN-style disambiguation, applied only when needed. Origins are counted
   * over the legal moves of the position before the turn; castling and promotions are irrelevant here (one king; a
   * promotion release has no board origin — its {@code fromSquare} is {@link Square#NONE}).
   */
  private static boolean isReleaseOriginAmbiguous(Board board, ReleasedPieceLock lock) {
    if (lock.fromSquare() == Square.NONE) {
      return false;
    }
    return board.getLegalMoves().stream()
        .filter(move -> !move.moveSpecification().isCastling())
        .filter(move -> move.movingPiece() == lock.piece())
        .filter(move -> move.moveSpecification().toSquare() == lock.square())
        .map(move -> move.moveSpecification().fromSquare())
        .distinct().count() > 1;
  }

  private static boolean isReleasePartOfLegalMove(Side havingMove, BoardEvent event, LegalMove legalMove,
      BitboardPosition positionBeforeEvent, BitboardPosition turnStartPosition) {
    final MoveSpecification spec = legalMove.moveSpecification();
    if (spec.isCastling()) {
      return event.piece() == Piece.of(havingMove, PieceType.KING)
          && event.square() == spec.castlingMove().kingFromSquare(havingMove)
          && event.targetSquare() == spec.castlingMove().kingToSquare(havingMove);
    }
    if (spec.promotionPieceType() != PromotionPieceType.NONE) {
      // A promotion is released only when the PROMOTED piece (e.g. a queen from the side area) is
      // placed on the promotion square. A pawn landing on the last rank is an incomplete move, never
      // a legal release — so it must not start a released-piece commitment. The commitment (and the
      // restore message) then correctly names the promoted piece, not the pawn.
      // The promoted piece must arrive from OFF the board: side-area placements are RESTORE_*
      // events, which carry no source square. Dragging an already-on-board piece of the same type
      // onto the promotion square is NOT a promotion completion — it is position tampering,
      // adjudicated as an illegal move at the clock press.
      final Piece promotedPiece = Piece.of(havingMove, spec.promotionPieceType().getPieceType());
      return event.piece() == promotedPiece && event.targetSquare() == spec.toSquare()
          && event.square() == Square.NONE;
    }
    if (event.piece() != legalMove.movingPiece()) {
      return false;
    }
    if (event.square() != spec.fromSquare() || event.targetSquare() != spec.toSquare()) {
      return false;
    }
    // The release physically IS this move only if the destination square was not tampered with
    // earlier in the turn. Example: after b2xa1 (own pawn parked on a1 mid-promotion), dragging
    // the a8 rook onto a1 used to match the legal Ra8xa1 of the turn-start position — but the
    // physical act captured the player's OWN pawn, which is no move at all (and produced an
    // unsatisfiable commitment: restore target = the tampered position itself). Comparing the
    // destination's content at release time with the turn start rejects that, while normal
    // moves, captures, and en passant (whose destination square is untouched) stay committed.
    return positionBeforeEvent.get(spec.toSquare()) == turnStartPosition.get(spec.toSquare());
  }

  private static BitboardPosition applyEvent(BitboardPosition position, BoardEvent event) {
    final List<UpdateSquare> updates = new ArrayList<>();
    switch (event.type()) {
      case CLICK -> {
        return position;
      }
      case DRAG_MOVE, DRAG_CAPTURE -> {
        addUpdate(updates, position, event.square(), Piece.NONE);
        addUpdate(updates, position, event.targetSquare(), event.piece());
      }
      case REMOVE -> addUpdate(updates, position, event.square(), Piece.NONE);
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> addUpdate(updates, position, event.targetSquare(), event.piece());
    }
    if (updates.isEmpty()) {
      return position;
    }
    return BitboardPositions.withUpdates(position, updates);
  }

  private static void addUpdate(List<UpdateSquare> updates, BitboardPosition position, Square square, Piece piece) {
    if (square != Square.NONE && position.get(square) != piece) {
      updates.add(new UpdateSquare(square, piece));
    }
  }

  private static boolean isReleaseOnBoard(BoardEvent event) {
    return switch (event.type()) {
      case DRAG_MOVE, DRAG_CAPTURE, RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> event.piece() != Piece.NONE
          && event.targetSquare() != Square.NONE;
      case CLICK, REMOVE -> false;
    };
  }

  private ArbiterResponse handleIllegalMove(Board board, BitboardPosition afterPosition, ActionSequence sequence,
      Side sideToMove) {
    illegalMoveTracker.recordIllegalMove(sideToMove);
    final Optional<IllegalMoveReason> reason = explainSimpleIllegalMove(board, afterPosition, sequence);
    final int count = illegalMoveTracker.getIllegalMoveCount(sideToMove);
    final IllegalMoveDetail detail = new IllegalMoveDetail(reason.map(IllegalMoveReason::playerReason),
        reason.map(IllegalMoveReason::opponentReason), sideToMove, count, illegalMoveTracker.getMaxIllegalMoves(),
        illegalMoveTracker.isUnlimited(), false);

    if (illegalMoveTracker.isGameLost(sideToMove)) {
      return ArbiterResponse.illegalMoveGameLost(detail);
    }

    return ArbiterResponse.illegalMove(detail);
  }

  /**
   * Physical move inferred from the player's board manipulations.
   */
  private record AttemptedMove(MoveSpecification moveSpecification, boolean castlingAttempt, Square kingReleaseSquare) {
  }

  private record IllegalMoveReason(String playerReason, String opponentReason) {
  }

  private record NormalizedReason(String playerReason, String opponentReason) {
  }

  private static Optional<IllegalMoveReason> explainSimpleIllegalMove(Board board, BitboardPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> attemptedMove = inferAttemptedMove(board, afterPosition, sequence);
    if (attemptedMove.isEmpty()) {
      return Optional.empty();
    }

    final MoveSpecification moveSpecification = attemptedMove.get().moveSpecification();
    final BitboardPosition beforePosition = board.getBitboardPosition();
    try {
      // move() runs the same legality validation as the library's internal check and throws
      // InvalidMoveException for an illegal move; unmove() restores the board afterwards.
      board.move(moveSpecification);
      board.unmove();
    } catch (final InvalidMoveException e) {
      return Optional.of(formatIllegalMoveExplanation(e.getMessage(), board, sequence, attemptedMove.get()));
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
    final BitboardPosition expectedPosition = beforePosition.afterMove(moveSpecification, board.getSideToMove());
    if (!expectedPosition.equals(afterPosition)) {
      final String reason = "the move itself is legal, but the final board position is not correct";
      return Optional.of(new IllegalMoveReason(reason, reason));
    }
    return Optional.empty();
  }

  private static Optional<AttemptedMove> inferAttemptedMove(Board board, BitboardPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> castlingAttempt = inferPhysicalCastlingAttempt(board, afterPosition,
        sequence.getEvents());
    if (castlingAttempt.isPresent()) {
      return castlingAttempt;
    }
    return inferSimpleAttemptedMove(board, afterPosition, sequence);
  }

  private static Optional<AttemptedMove> inferPhysicalCastlingAttempt(Board board, BitboardPosition afterPosition,
      List<BoardEvent> events) {
    return CastlingAttemptDetector.findPhysicalAttempt(board, afterPosition, events)
        .map(attempt -> new AttemptedMove(attempt.moveSpecification(), true, attempt.kingReleaseSquare()));
  }

  private static boolean shouldBypassReleasedPieceForInvalidCastlingAttempt(Board board, AttemptedMove attempt) {
    if (!attempt.castlingAttempt() || isCastlingLegal(board, attempt.moveSpecification())) {
      return false;
    }
    return !isKingReleaseLegalMove(board, attempt);
  }

  private static boolean isCastlingLegal(Board board, MoveSpecification moveSpecification) {
    try {
      board.move(moveSpecification);
      board.unmove();
      return true;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  private static boolean isKingReleaseLegalMove(Board board, AttemptedMove attempt) {
    if (attempt.kingReleaseSquare() == Square.NONE) {
      return false;
    }
    final Square kingFrom = attempt.moveSpecification().castlingMove().kingFromSquare(board.getSideToMove());
    final Piece kingPiece = Piece.of(board.getSideToMove(), PieceType.KING);
    for (final LegalMove legalMove : board.getLegalMoves()) {
      if (!legalMove.moveSpecification().isCastling() && legalMove.movingPiece() == kingPiece
          && legalMove.moveSpecification().fromSquare() == kingFrom
          && legalMove.moveSpecification().toSquare() == attempt.kingReleaseSquare()) {
        return true;
      }
    }
    return false;
  }

  private static Optional<AttemptedMove> inferSimpleAttemptedMove(Board board, BitboardPosition afterPosition,
      ActionSequence sequence) {
    BoardEvent moveEvent = null;

    for (final BoardEvent event : sequence.getEvents()) {
      if (event.type() == BoardEventType.CLICK) {
        continue;
      }
      if (event.type() != BoardEventType.DRAG_MOVE && event.type() != BoardEventType.DRAG_CAPTURE) {
        return Optional.empty();
      }
      if (moveEvent != null) {
        return Optional.empty();
      }
      moveEvent = event;
    }

    if (moveEvent == null || moveEvent.piece() == Piece.NONE || moveEvent.piece().getSide() != board.getSideToMove()
        || moveEvent.square() == Square.NONE || moveEvent.targetSquare() == Square.NONE
        || moveEvent.square() == moveEvent.targetSquare()) {
      return Optional.empty();
    }
    if (board.getBitboardPosition().get(moveEvent.square()) != moveEvent.piece()) {
      return Optional.empty();
    }
    if (afterPosition.get(moveEvent.square()) != Piece.NONE
        || afterPosition.get(moveEvent.targetSquare()) != moveEvent.piece()) {
      return Optional.empty();
    }

    final MoveSpecification moveSpecification = createMoveSpecification(moveEvent);
    return Optional.of(new AttemptedMove(moveSpecification, moveSpecification.isCastling(),
        moveSpecification.isCastling() ? moveEvent.targetSquare() : Square.NONE));
  }

  private static MoveSpecification createMoveSpecification(BoardEvent event) {
    final Optional<CastlingMove> castlingMove = inferCastlingMove(event);
    if (castlingMove.isPresent()) {
      return new MoveSpecification(castlingMove.get());
    }
    return new MoveSpecification(event.square(), event.targetSquare());
  }

  private static Optional<CastlingMove> inferCastlingMove(BoardEvent event) {
    if (event.piece() != Piece.WHITE_KING && event.piece() != Piece.BLACK_KING) {
      return Optional.empty();
    }
    if (event.square() == Square.E1 && event.targetSquare() == Square.G1
        || event.square() == Square.E8 && event.targetSquare() == Square.G8) {
      return Optional.of(CastlingMove.KING_SIDE);
    }
    if (event.square() == Square.E1 && event.targetSquare() == Square.C1
        || event.square() == Square.E8 && event.targetSquare() == Square.C8) {
      return Optional.of(CastlingMove.QUEEN_SIDE);
    }
    return Optional.empty();
  }

  private static IllegalMoveReason formatIllegalMoveExplanation(String reason, Board board, ActionSequence sequence,
      AttemptedMove attemptedMove) {
    final NormalizedReason normalizedReason = normalizeIllegalMoveReason(reason, attemptedMove.castlingAttempt());
    final String formattedPlayerReason;
    final String formattedOpponentReason;
    if (attemptedMove.castlingAttempt() && !reason.startsWith("castling is not possible")) {
      formattedPlayerReason = "castling is not possible: " + normalizedReason.playerReason();
      formattedOpponentReason = "castling is not possible: " + normalizedReason.opponentReason();
    } else {
      formattedPlayerReason = normalizedReason.playerReason();
      formattedOpponentReason = normalizedReason.opponentReason();
    }
    return new IllegalMoveReason(
        formattedPlayerReason + formatCastlingTouchMoveConsequence(board, sequence, attemptedMove, false),
        formattedOpponentReason + formatCastlingTouchMoveConsequence(board, sequence, attemptedMove, true));
  }

  private static NormalizedReason normalizeIllegalMoveReason(String reason, boolean castlingAttempt) {
    if (!reason.equals("it would leave the own king in check")) {
      return new NormalizedReason(reason, reason);
    }
    final String naturalReason = "it leaves the own king in check";
    if (castlingAttempt) {
      return new NormalizedReason(naturalReason, naturalReason);
    }
    return new NormalizedReason("because " + naturalReason, naturalReason);
  }

  private static String formatCastlingTouchMoveConsequence(Board board, ActionSequence sequence,
      AttemptedMove attemptedMove, boolean opponent) {
    if (!attemptedMove.castlingAttempt()) {
      return "";
    }

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);
    final Square kingFrom = attemptedMove.moveSpecification().castlingMove().kingFromSquare(board.getSideToMove());
    final Piece kingPiece = Piece.of(board.getSideToMove(), PieceType.KING);

    if (obligation.isPresent()) {
      final TouchMoveObligation value = obligation.get();
      if (value.type() == TouchMoveType.OWN_PIECE && value.square() == kingFrom && value.piece() == kingPiece) {
        return opponent
            ? " Castling counts as a king move; because the king has legal moves, after restoring the position"
                + " they must make a legal move with the king."
            : " Castling counts as a king move; because the king has legal moves, after restoring the position"
                + " you must make a legal move with the king.";
      }
      // A different first touch remains governed by the normal touch-move recovery path.
      return "";
    }

    return opponent
        ? " Castling counts as a king move, but the touched king has no legal moves; after restoring the position"
            + " they may make another legal move."
        : " Castling counts as a king move, but the touched king has no legal moves; after restoring the position"
            + " make another legal move.";
  }

  private ArbiterResponse handleTouchMoveViolation(TouchMoveObligation obligation) {
    return switch (obligation.type()) {
      case OWN_PIECE -> ArbiterResponse.touchMoveViolation(obligation);
      case OPPONENT_PIECE -> ArbiterResponse.touchMoveViolation(obligation);
      case CASTLING -> ArbiterResponse.touchMoveViolation(obligation);
      case SPECIFIC_CAPTURE -> ArbiterResponse.touchMoveViolation(obligation);
    };
  }

}
