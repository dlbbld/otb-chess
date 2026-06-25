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
    BitboardPosition currentPosition = board.getBitboardPosition();
    for (final BoardEvent event : sequence.getEventsSinceReleasedPieceRuleReset()) {
      currentPosition = applyEvent(currentPosition, event);
      if (isReleaseOnBoard(event)) {
        final ReleaseCommitment commitment = findCommitmentForRelease(board, event);
        if (!commitment.allowedFinalPositions().isEmpty()) {
          return true;
        }
      }
    }
    return false;
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

    final Optional<ReleasedPieceLock> releasedPieceViolation = findReleasedPieceViolation(board, afterPosition,
        sequence);
    if (releasedPieceViolation.isPresent()) {
      final ReleasedPieceLock lock = releasedPieceViolation.get();
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
      return ArbiterResponse.releasedPieceViolation(new ReleasedPieceContext(lock.piece(), lock.square()),
          lock.releasePosition());
    }

    // Check if the board position even changed
    if (board.getBitboardPosition().equals(afterPosition)) {
      return ArbiterResponse.incompleteMove("Please complete your move.");
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
    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    if (obligation.isPresent()) {
      if (!TouchMoveEvaluator.satisfiesObligation(obligation.get(), matchedMove)) {
        return handleTouchMoveViolation(obligation.get());
      }
    }

    // Move accepted
    return ArbiterResponse.moveAccepted(matchedMove);
  }

  private record ReleasedPieceLock(BitboardPosition releasePosition, Set<BitboardPosition> allowedFinalPositions,
      Set<LegalMove> committedMoves, Piece piece, Square square) {

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

  private static Optional<ReleasedPieceLock> findReleasedPieceViolation(Board board, BitboardPosition afterPosition,
      ActionSequence sequence) {
    final Optional<AttemptedMove> attemptedCastling = inferPhysicalCastlingAttempt(board, afterPosition,
        sequence.getEventsSinceReleasedPieceRuleReset());
    if (attemptedCastling.isPresent()
        && shouldBypassReleasedPieceForInvalidCastlingAttempt(board, attemptedCastling.get())) {
      return Optional.empty();
    }

    BitboardPosition currentPosition = board.getBitboardPosition();
    Optional<ReleasedPieceLock> firstReleasedLegalPosition = Optional.empty();

    for (final BoardEvent event : sequence.getEventsSinceReleasedPieceRuleReset()) {
      currentPosition = applyEvent(currentPosition, event);

      if (firstReleasedLegalPosition.isEmpty() && isReleaseOnBoard(event)) {
        final ReleaseCommitment commitment = findCommitmentForRelease(board, event);
        if (!commitment.allowedFinalPositions().isEmpty()) {
          firstReleasedLegalPosition = Optional.of(new ReleasedPieceLock(currentPosition,
              commitment.allowedFinalPositions(), commitment.committedMoves(), event.piece(), event.targetSquare()));
        }
      }
    }

    if (firstReleasedLegalPosition.isPresent()
        && !firstReleasedLegalPosition.get().allowedFinalPositions().contains(afterPosition)) {
      return firstReleasedLegalPosition;
    }
    return Optional.empty();
  }

  /**
   * Captures both the set of allowed final positions and the set of legal moves that the release commits the player to.
   * The legal-move set lets the message-building code recognise a castling-only commitment and produce the
   * castling-specific arbiter message.
   */
  private record ReleaseCommitment(Set<BitboardPosition> allowedFinalPositions, Set<LegalMove> committedMoves) {
  }

  private static ReleaseCommitment findCommitmentForRelease(Board board, BoardEvent event) {
    final Set<BitboardPosition> positions = new HashSet<>();
    final Set<LegalMove> moves = new HashSet<>();
    for (final LegalMove legalMove : board.getLegalMoves()) {
      if (isReleasePartOfLegalMove(board.getSideToMove(), event, legalMove)) {
        moves.add(legalMove);
        positions.add(board.getBitboardPosition().afterMove(legalMove.moveSpecification(), board.getSideToMove()));
      }
    }
    return new ReleaseCommitment(positions, moves);
  }

  private static boolean isReleasePartOfLegalMove(Side havingMove, BoardEvent event, LegalMove legalMove) {
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
      final Piece promotedPiece = Piece.of(havingMove, spec.promotionPieceType().getPieceType());
      return event.piece() == promotedPiece && event.targetSquare() == spec.toSquare();
    }
    if (event.piece() != legalMove.movingPiece()) {
      return false;
    }
    return event.square() == spec.fromSquare() && event.targetSquare() == spec.toSquare();
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
        illegalMoveTracker.isUnlimited());

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
    final String formattedReason;
    if (attemptedMove.castlingAttempt() && !reason.startsWith("castling is not possible")) {
      formattedReason = "castling is not possible: " + reason;
    } else {
      formattedReason = reason;
    }
    return new IllegalMoveReason(
        formattedReason + formatCastlingTouchMoveConsequence(board, sequence, attemptedMove, false),
        formattedReason + formatCastlingTouchMoveConsequence(board, sequence, attemptedMove, true));
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
