// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import io.github.dlbbld.otbchess.message.IllegalMoveReasons;
import io.github.dlbbld.otbchess.touchmove.FirstTouchInvariant;
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

  /** A completed legal release, independent of later touches and recovery bookkeeping. */
  public record FinalMoveCommitment(LegalMove move, BitboardPosition position) {
  }

  /**
   * Find the first completed move that satisfied the obligations at the moment it was made.
   * Later touches cannot retroactively replace it with a castling or capture obligation.
   * Incomplete castling, promotion and en passant remain subject to the ordinary arbiter flow.
   */
  public Optional<FinalMoveCommitment> findFinalMoveCommitment(Board board, ActionSequence sequence) {
    // One forward pass: the replay carries the release ruling from event to event, so the ruling for
    // each prefix of the turn is at hand without replaying that prefix. This runs on every board
    // event of an uncommitted turn; a replay per prefix made a long turn cost its length cubed.
    final ReleaseReplay replay = new ReleaseReplay(board, sequence);
    for (final BoardEvent event : sequence.getEvents()) {
      if (!replay.advance(event)) {
        continue;
      }
      if (!isReleaseOnBoard(event) && event.type() != BoardEventType.REMOVE) {
        continue;
      }
      final BitboardPosition position = replay.position();
      final Set<LegalMove> matches = PositionComparator.findMatchingMoves(board, position);
      if (matches.isEmpty()) {
        continue;
      }
      final ReleaseRuling ruling = replay.ruling();
      if (findReleasedPieceViolation(board, position, replay.prefix(), ruling).isPresent()) {
        continue;
      }
      final Optional<TouchMoveObligation> obligation = ruling.obligation();
      for (final LegalMove move : matches) {
        // A move that breaks the binding of an unclear release is not made (E-005).
        if (ruling.binding().isPresent() && !ruling.binding().get().moves().contains(move)) {
          continue;
        }
        if (obligation.isEmpty() || TouchMoveEvaluator.satisfiesObligation(obligation.get(), move)) {
          return Optional.of(new FinalMoveCommitment(move, position));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * What a piece let go in an unclear placement binds the player to (free placement, E-005): the legal moves that
   * end with the piece on one of the squares it was let go across.
   *
   * @param piece      the piece
   * @param fromSquare the square it stood on
   * @param squares    the squares it is bound to: those of the squares it was let go across that give a legal move
   * @param moves      the moves it is bound to
   * @param at         the square the piece counts on now, {@link Square#NONE} once it left the board
   */
  public record UnclearBinding(Piece piece, Square fromSquare, List<Square> squares, Set<LegalMove> moves,
      Square at) {

    /** @return whether the piece stands on one of the squares it is bound to */
    public boolean isPieceOnBoundSquare() {
      return squares.contains(at);
    }
  }

  /** A piece that was released in a clear placement and commits the player (FIDE 4.7), and the square it is on. */
  public record ReleasedPiece(Piece piece, Square square) {
  }

  /**
   * The binding of the turn's first unclear release that binds, while it is in force: until a release in a clear
   * placement honours it, from where on the released-piece rule of today decides.
   * <ul>
   * <li>The binding is what a clear release on each of the squares would have committed the player to at that
   * moment, by the rules of today: so a king let go across f1 and g1 is bound to Kf1 and to castling, and a pawn let
   * go across squares of the last rank or on the square of an en passant capture is bound to nothing.</li>
   * <li>The square the piece came from among the squares: bound to nothing - the arbiter cannot tell a piece put
   * back carelessly from a move.</li>
   * <li>No square gives a legal move that honours the touch-move obligation in force: bound to nothing.</li>
   * <li>The first binding stands: a second unclear release on other squares changes nothing.</li>
   * </ul>
   */
  public Optional<UnclearBinding> findUnclearBinding(Board board, ActionSequence sequence) {
    return findReleaseRuling(board, sequence).binding();
  }

  /** The piece of the first release that commits the player (FIDE 4.7) and the square it was released on. */
  public Optional<ReleasedPiece> findReleasedPiece(Board board, ActionSequence sequence) {
    return findReleaseRuling(board, sequence).lock().filter(lock -> lock.square() != Square.NONE)
        .map(lock -> new ReleasedPiece(lock.piece(), lock.square()));
  }

  /** Evaluate against a latched final move without consulting later touch obligations. */
  public ArbiterResponse evaluateFinalMove(Board board, BitboardPosition afterPosition,
      FinalMoveCommitment commitment, ActionSequence sequence) {
    if (commitment.position().equals(afterPosition)) {
      // Same safety net as the ordinary acceptance below: a latched move is exempt from LATER
      // touches, never from the first touch that bound the player when the move was completed.
      FirstTouchInvariant.verify(sequence, board, commitment.move());
      return ArbiterResponse.moveAccepted(commitment.move());
    }
    final LegalMove move = commitment.move();
    final MoveSpecification spec = move.moveSpecification();
    final Square from = spec.isCastling() ? spec.castlingMove().kingFromSquare(move.movingSide()) : spec.fromSquare();
    final Square to = spec.isCastling() ? spec.castlingMove().kingToSquare(move.movingSide()) : spec.toSquare();
    final Piece piece = commitment.position().get(to);
    final ReleasedPieceLock lock = new ReleasedPieceLock(commitment.position(), Set.of(commitment.position()),
        Set.of(move), piece, to, from, afterPosition.get(to) != piece);
    final Optional<ArbiterResponse.RookFirstCastlingContext> rookFirst = findRookFirstCastlingContext(board,
        afterPosition, lock);
    if (rookFirst.isPresent()) {
      return ArbiterResponse.releasedPieceViolationRookFirstCastling(rookFirst.get(), commitment.position());
    }
    return ArbiterResponse.releasedPieceViolation(
        new ReleasedPieceContext(piece, to, from, lock.releasedPieceDisplacedAfterRelease()
            || sequence.getEvents().stream().anyMatch(event -> displacesReleasedPiece(event, lock))),
        isReleaseOriginAmbiguous(board, lock), commitment.position());
  }

  /**
   * Side-effect-free check used by the auto-end path to find out whether a released-piece commitment (FIDE 4.7)
   * currently binds the player to a final position that the given {@code afterPosition} does not satisfy. If true, no
   * code that bypasses {@link #evaluateClockPress} should accept the position as a move — the committed move must be
   * played via the normal clock-press flow, where the violation is reported and the recovery flow runs.
   */
  public boolean hasReleasedPieceViolation(Board board, BitboardPosition afterPosition, ActionSequence sequence) {
    return findReleasedPieceViolation(board, afterPosition, sequence, findReleaseRuling(board, sequence))
        .isPresent();
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
    return findReleaseRuling(board, sequence).lock().map(ReleasedPieceLock::releasePosition);
  }

  /**
   * The touch-move obligation that binds the turn. A release that commits the player (FIDE 4.7) settles the turn: the
   * obligation is the one in force at that release, and a piece touched afterwards cannot replace it. Without such a
   * release the touches of the whole turn decide.
   */
  public Optional<TouchMoveObligation> findBindingObligation(Board board, ActionSequence sequence) {
    return findReleaseRuling(board, sequence).obligation();
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

    final Optional<FinalMoveCommitment> finalMove = findFinalMoveCommitment(board, sequence);
    if (finalMove.isPresent()) {
      return evaluateFinalMove(board, afterPosition, finalMove.get(), sequence);
    }

    final ReleaseRuling ruling = findReleaseRuling(board, sequence);
    final Optional<TouchMoveObligation> obligation = ruling.obligation();
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
        sequence, ruling);
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
      // The board shows exactly a release that the touch-move rule overruled - in practice the king
      // put on its castling square although an earlier touch forbids that castling (FIDE 4.4.2 for
      // a rook touched first). Like any started castling this is not counted as an illegal move;
      // the ruling names the obligation that actually binds, never "complete the castling".
      if (obligation.isPresent() && ruling.overruledReleasePosition().filter(afterPosition::equals).isPresent()) {
        return handleTouchMoveViolation(obligation.get());
      }
      // The touch came first, so the touch-move rule comes first (FIDE 4.3; 7.5.1 for the move that replaces an
      // illegal one). When the player moved something else - the piece they have to move stands where it was
      // touched, or the piece they have to capture was not taken - that is a touch-move violation, whether or
      // not the other move would have been legal: the arbiter asks for the touched piece and does not count an
      // illegal move. An illegal move of the touched piece itself is an illegal move.
      if (obligation.isPresent()
          && leavesObligationUntouched(obligation.get(), afterPosition, sequence.getEventsSincePositionRestored())) {
        return handleTouchMoveViolation(obligation.get());
      }
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

    // Safety net: an independent re-check of the first-touch rule. Throws, so the move is never
    // recorded, if the obligation chain above accepted a move it should have rejected.
    FirstTouchInvariant.verify(sequence, board, matchedMove);

    // Move accepted
    return ArbiterResponse.moveAccepted(matchedMove);
  }

  /**
   * @param events what was done on the board since it was last set back - all of the turn when it never was
   * @return whether the player did not honour the obligation with what is on the board: the own piece they have to
   *         move stands where it was touched, or the opponent's piece they have to capture is still on its square.
   *         The piece is followed through the events, because another piece of the same kind can stand on the
   *         square of one that was moved away; a piece that was picked up and put back stands where it was. A
   *         castling obligation is ruled on by its own rules.
   */
  private static boolean leavesObligationUntouched(TouchMoveObligation obligation, BitboardPosition afterPosition,
      List<BoardEvent> events) {
    return switch (obligation.type()) {
      case OWN_PIECE, OPPONENT_PIECE -> stillStandsUntouched(obligation.square(), obligation.piece(), afterPosition,
          events);
      case SPECIFIC_CAPTURE -> stillStandsUntouched(obligation.square(), obligation.piece(), afterPosition, events)
          || stillStandsUntouched(obligation.toSquare(), obligation.capturedPiece(), afterPosition, events);
      case CASTLING -> false;
    };
  }

  private static boolean stillStandsUntouched(Square square, Piece piece, BitboardPosition afterPosition,
      List<BoardEvent> events) {
    if (square == Square.NONE || afterPosition.get(square) != piece) {
      return false;
    }
    // Where the piece that stood on the square is now; NONE once it left the board.
    Square at = square;
    for (final BoardEvent event : events) {
      switch (event.type()) {
        case DRAG_MOVE, DRAG_CAPTURE -> {
          if (at != Square.NONE && event.square() == at) {
            at = event.targetSquare();
          } else if (at != Square.NONE && event.targetSquare() == at) {
            at = Square.NONE; // something was put on it: it is off the board
          }
        }
        case REMOVE -> {
          if (at != Square.NONE && event.square() == at) {
            at = Square.NONE;
          }
        }
        case RESTORE_TO_OCCUPIED -> {
          // Pushed off the board by a piece set down from the side.
          if (at != Square.NONE && event.targetSquare() == at) {
            at = Square.NONE;
          }
        }
        case RESTORE_TO_EMPTY -> {
          // Taken off the board and set down again where it stood: it is back.
          if (at == Square.NONE && event.targetSquare() == square && event.piece() == piece) {
            at = square;
          }
        }
        default -> {
          // A click moves nothing.
        }
      }
    }
    return at == square;
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
      ActionSequence sequence, ReleaseRuling ruling) {
    final Optional<AttemptedMove> attemptedCastling = inferPhysicalCastlingAttempt(board, afterPosition,
        sequence.getEventsSinceReleasedPieceRuleReset());
    if (attemptedCastling.isPresent()
        && shouldBypassReleasedPieceForInvalidCastlingAttempt(board, attemptedCastling.get())) {
      return Optional.empty();
    }
    return ruling.lock().filter(lock -> !lock.allowedFinalPositions().contains(afterPosition));
  }

  /**
   * What the releases of the turn settle.
   *
   * @param lock                     the first release that commits the player (FIDE 4.7), if any
   * @param obligation               the touch-move obligation that binds the turn, see {@link #findBindingObligation}
   * @param overruledReleasePosition the board right after the first release that was part of a legal move but broke
   *                                 the touch-move obligation in force - such a release commits to nothing
   * @param binding                  the binding of an unclear release that is in force, see
   *                                 {@link #findUnclearBinding}
   */
  private record ReleaseRuling(Optional<ReleasedPieceLock> lock, Optional<TouchMoveObligation> obligation,
      Optional<BitboardPosition> overruledReleasePosition, Optional<UnclearBinding> binding) {
  }

  /**
   * Finds the first release that commits the player and the obligation that goes with it. The two rules are applied
   * in the order the player acted, so they can never give contradictory orders:
   * <ul>
   * <li>A release counts under FIDE 4.7 only if it honours the touch-move obligation already in force. A king put on
   * c1 after the a1 rook was touched (4.4.2), or a pawn move made after another piece was touched, commits to
   * nothing: the touched piece still has to be moved.</li>
   * <li>Once a release commits, later touches change nothing. A king released on g1 means kingside castling even if
   * the a1 rook is touched afterwards.</li>
   * </ul>
   */
  private static ReleaseRuling findReleaseRuling(Board board, ActionSequence sequence) {
    final ReleaseReplay replay = new ReleaseReplay(board, sequence);
    for (final BoardEvent event : sequence.getEvents()) {
      replay.advance(event);
    }
    return replay.ruling();
  }

  /**
   * Replays the events of a turn one at a time and keeps what the releases so far settle. After each
   * {@link #advance}, {@link #ruling()} is the {@link ReleaseRuling} for the events replayed up to there - the same
   * ruling a fresh replay of that prefix would give.
   */
  private static final class ReleaseReplay {
    private final Board board;
    /** Index of the first event inside the released-piece rule window (see {@link ActionSequence}). */
    private final int resetIndex;
    /** The events replayed so far, with the rule window starting where the turn's own window starts. */
    private final ActionSequence prefix;
    private BitboardPosition position;
    private ReleasedPieceLock lock;
    private Optional<TouchMoveObligation> obligationAtRelease = Optional.empty();
    private BitboardPosition overruledReleasePosition;
    /** The first unclear release that binds (free placement), and where its piece counts now. */
    private UnclearBinding binding;
    private Square boundAt = Square.NONE;

    ReleaseReplay(Board board, ActionSequence sequence) {
      this.board = board;
      this.resetIndex = sequence.getEvents().size() - sequence.getEventsSinceReleasedPieceRuleReset().size();
      this.prefix = new ActionSequence(sequence.getSideToMove());
      this.position = board.getBitboardPosition();
    }

    /**
     * Replays the next event of the turn.
     *
     * @return whether the event lies inside the released-piece rule window; events before it are only recorded as
     *         touches
     */
    boolean advance(BoardEvent event) {
      final int index = prefix.getEvents().size();
      if (index == resetIndex) {
        prefix.resetReleasedPieceRule();
      }
      prefix.addEvent(event);
      if (index < resetIndex) {
        return false;
      }
      if (lock != null && displacesReleasedPiece(event, lock)) {
        lock = lock.withReleasedPieceDisplacedAfterRelease();
      }

      final BitboardPosition beforeEvent = position;
      position = applyEvent(position, event);
      if (binding != null) {
        boundAt = followBoundPiece(boundAt, binding.piece(), event);
      }
      if (lock == null && binding == null && !event.releasedOn().isEmpty()) {
        bind(event, beforeEvent);
        return true;
      }
      if (lock != null || !isReleaseOnBoard(event)) {
        return true;
      }
      final ReleaseCommitment commitment = findCommitmentForRelease(board, event, beforeEvent);
      if (commitment.allowedFinalPositions().isEmpty()) {
        return true;
      }
      final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(prefix, board);
      final Set<LegalMove> honouringMoves = new HashSet<>();
      final Set<BitboardPosition> honouringPositions = new HashSet<>();
      boolean honoursObligation = false;
      for (final LegalMove move : commitment.committedMoves()) {
        if (obligation.isEmpty() || TouchMoveEvaluator.satisfiesObligation(obligation.get(), move)) {
          honoursObligation = true;
          // A clear release that breaks the binding of an unclear release commits to nothing (E-005).
          if (binding == null || binding.moves().contains(move)) {
            honouringMoves.add(move);
            honouringPositions.add(board.getBitboardPosition().afterMove(move.moveSpecification(),
                board.getSideToMove()));
          }
        }
      }
      if (honouringMoves.isEmpty()) {
        if (!honoursObligation && overruledReleasePosition == null) {
          overruledReleasePosition = position;
        }
        return true;
      }
      lock = new ReleasedPieceLock(position, honouringPositions, honouringMoves, event.piece(),
          event.targetSquare(), event.square(), false);
      obligationAtRelease = obligation;
      return true;
    }

    /** The board after the events replayed so far (events before the rule window do not count). */
    BitboardPosition position() {
      return position;
    }

    ActionSequence prefix() {
      return prefix;
    }

    /**
     * The piece of {@code event} was let go in an unclear placement and nothing binds the player yet: it binds to
     * what a clear release on each of the squares it lies on would commit to now.
     */
    private void bind(BoardEvent event, BitboardPosition beforeEvent) {
      final Piece piece = event.piece();
      final Square from = event.square();
      if (piece == Piece.NONE || piece.getSide() != board.getSideToMove() || from == Square.NONE
          || event.releasedOn().contains(from)) {
        return;
      }
      final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(prefix, board);
      final List<Square> squares = new ArrayList<>();
      final Set<LegalMove> moves = new HashSet<>();
      for (final Square square : event.releasedOn()) {
        final Piece occupant = beforeEvent.get(square);
        final BoardEvent release = occupant == Piece.NONE
            ? BoardEvent.dragMove(from, square, piece, event.timestampMs())
            : BoardEvent.dragCapture(from, square, piece, occupant, event.timestampMs());
        // What the release would commit to, and the move it would complete when the piece did not come straight
        // from the square it stood on at the start of the turn.
        final Set<LegalMove> candidates = new HashSet<>(
            findCommitmentForRelease(board, release, beforeEvent).committedMoves());
        candidates.addAll(PositionComparator.findMatchingMoves(board, applyEvent(beforeEvent, release)));
        boolean binds = false;
        for (final LegalMove move : candidates) {
          if (obligation.isEmpty() || TouchMoveEvaluator.satisfiesObligation(obligation.get(), move)) {
            moves.add(move);
            binds = true;
          }
        }
        if (binds) {
          squares.add(square);
        }
      }
      if (!squares.isEmpty()) {
        binding = new UnclearBinding(piece, from, List.copyOf(squares), Set.copyOf(moves), from);
        boundAt = from;
      }
    }

    ReleaseRuling ruling() {
      if (lock != null) {
        return new ReleaseRuling(Optional.of(lock), obligationAtRelease, Optional.empty(), Optional.empty());
      }
      final Optional<UnclearBinding> inForce = binding == null ? Optional.empty()
          : Optional.of(new UnclearBinding(binding.piece(), binding.fromSquare(), binding.squares(),
              binding.moves(), boundAt));
      return new ReleaseRuling(Optional.empty(), TouchMoveEvaluator.findObligation(prefix, board),
          Optional.ofNullable(overruledReleasePosition), inForce);
    }
  }

  /**
   * Where the bound piece counts after {@code event}: followed from square to square, off the board once it is
   * taken off or something is put on it, and back when a piece of its kind is set down from the side.
   */
  private static Square followBoundPiece(Square at, Piece piece, BoardEvent event) {
    return switch (event.type()) {
      case CLICK -> at;
      case DRAG_MOVE, DRAG_CAPTURE -> {
        if (at != Square.NONE && event.square() == at) {
          yield event.targetSquare();
        }
        yield event.targetSquare() == at ? Square.NONE : at;
      }
      case REMOVE -> event.square() == at ? Square.NONE : at;
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> {
        if (at == Square.NONE && event.piece() == piece) {
          yield event.targetSquare();
        }
        yield event.targetSquare() == at ? Square.NONE : at;
      }
    };
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
    if (!releaseMatchesMove(havingMove, event, legalMove)) {
      return false;
    }
    // FIDE 4.7 binds a piece released "as a legal move or part of a legal move". Matching the
    // released piece alone is not enough: the rest of the board must still be the turn-start
    // position. Otherwise Ke1-b1 followed by Ra1-c1 made the rook release count as the legal Rc1
    // although the rook jumped the misplaced king, and b2xa1 (own pawn parked on a1) followed by
    // Ra8-a1 counted as Ra8xa1 although it captured the player's own pawn.
    return applyEvent(positionBeforeEvent, event)
        .equals(positionAfterRelease(havingMove, legalMove, turnStartPosition));
  }

  /**
   * The board right after the release that completes (or, for castling, starts) {@code legalMove}: the position after
   * the move, or for castling the king-first intermediate with only the king moved.
   */
  private static BitboardPosition positionAfterRelease(Side havingMove, LegalMove legalMove,
      BitboardPosition turnStartPosition) {
    final MoveSpecification spec = legalMove.moveSpecification();
    if (spec.isCastling()) {
      return BitboardPositions.from(turnStartPosition)
          .createChangedPosition(spec.castlingMove().kingFromSquare(havingMove), Piece.NONE)
          .createChangedPosition(spec.castlingMove().kingToSquare(havingMove), Piece.of(havingMove, PieceType.KING))
          .build();
    }
    return turnStartPosition.afterMove(spec, havingMove);
  }

  /** Whether the released piece and squares are those of {@code legalMove}, ignoring the rest of the board. */
  private static boolean releaseMatchesMove(Side havingMove, BoardEvent event, LegalMove legalMove) {
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
    // En passant is deliberately excluded: physically, landing on the en-passant square is only
    // half of the capture, and the clock-press position must decide whether the captured pawn was
    // also removed.
    return !legalMove.isEnPassant();
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
    final List<Square> moveSquares = illegalMoveSquares(board, afterPosition, sequence);
    final IllegalMoveDetail detail = new IllegalMoveDetail(reason.map(IllegalMoveReason::playerReason),
        reason.map(IllegalMoveReason::opponentReason), sideToMove, count, illegalMoveTracker.getMaxIllegalMoves(),
        illegalMoveTracker.isUnlimited(), false, moveSquares.isEmpty() ? Square.NONE : moveSquares.get(0),
        moveSquares.isEmpty() ? Square.NONE : moveSquares.get(1));

    if (illegalMoveTracker.isGameLost(sideToMove)) {
      return ArbiterResponse.illegalMoveGameLost(detail);
    }

    return ArbiterResponse.illegalMove(detail);
  }

  /**
   * Where the illegal move started and ended, for the circles on the board - only when the board shows exactly one
   * move: a castling attempt (the king's squares), or one own piece that left its square and stands on another one
   * while nothing else changed. A pawn may stand there as the piece it was promoted to. Several displaced pieces
   * give no squares: the arbiter does not guess which of them was "the move" - also not when two pieces of the same
   * kind moved and the board merely looks like one move. Only what was done since the board was last set back by a
   * restoration counts: an earlier attempt of the same turn is no longer on the board.
   *
   * @return the start and the end square, or an empty list
   */
  static List<Square> illegalMoveSquares(Board board, BitboardPosition afterPosition, ActionSequence sequence) {
    final Side side = board.getSideToMove();
    final List<BoardEvent> events = sequence.getEventsSincePositionRestored();
    final Optional<CastlingAttemptDetector.Attempt> castling = CastlingAttemptDetector.findPhysicalAttempt(board,
        afterPosition, events);
    if (castling.isPresent()) {
      // The square the king was actually put on, which need not be the castling square.
      return List.of(castling.get().moveSpecification().castlingMove().kingFromSquare(side),
          castling.get().kingReleaseSquare());
    }
    final BitboardPosition before = board.getBitboardPosition();
    final List<Square> changed = new ArrayList<>();
    for (final Square square : Square.values()) {
      if (square != Square.NONE && before.get(square) != afterPosition.get(square)) {
        changed.add(square);
      }
    }
    if (changed.size() != 2) {
      return List.of();
    }
    for (final Square from : changed) {
      final Square to = changed.get(0) == from ? changed.get(1) : changed.get(0);
      final Piece moved = before.get(from);
      final Piece arrived = afterPosition.get(to);
      if (moved == Piece.NONE || moved.getSide() != side || afterPosition.get(from) != Piece.NONE
          || arrived == Piece.NONE || arrived.getSide() != side) {
        continue;
      }
      final int lastRank = side == Side.WHITE ? 8 : 1;
      final boolean promoted = moved.getPieceType() == PieceType.PAWN && to.getRank().getNumber() == lastRank
          && arrived.getPieceType() != PieceType.PAWN && arrived.getPieceType() != PieceType.KING;
      if (arrived == moved || promoted) {
        return otherPiecesStayed(before, afterPosition, events, from, to) ? List.of(from, to) : List.of();
      }
    }
    return List.of();
  }

  /** A piece on the board or in the side area, with the square it stood on before the events. */
  private record TrackedPiece(Piece piece, Square origin) {
  }

  /**
   * Whether every piece beside the move still stands on its own square, by following each piece through the events.
   * Two pieces of the same kind that changed places leave the position as it was, and only the events tell. Events
   * that do not lead from the one position to the other say nothing about it, and the positions decide alone.
   */
  private static boolean otherPiecesStayed(BitboardPosition before, BitboardPosition after, List<BoardEvent> events,
      Square from, Square to) {
    final Map<Square, TrackedPiece> onBoard = new EnumMap<>(Square.class);
    for (final Square square : Square.values()) {
      if (square != Square.NONE && before.get(square) != Piece.NONE) {
        onBoard.put(square, new TrackedPiece(before.get(square), square));
      }
    }
    final List<TrackedPiece> sideArea = new ArrayList<>();
    for (final BoardEvent event : events) {
      final TrackedPiece moving;
      switch (event.type()) {
        case CLICK -> {
          continue;
        }
        case DRAG_MOVE, DRAG_CAPTURE, REMOVE -> {
          moving = onBoard.remove(event.square());
          if (moving == null || moving.piece() != event.piece()) {
            return true;
          }
        }
        case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> {
          final List<TrackedPiece> candidates = sideArea.stream().filter(piece -> piece.piece() == event.piece())
              .toList();
          // Which one of several pieces of a kind came back is not known - and neither is where a new piece is from.
          moving = candidates.size() == 1 ? candidates.get(0) : new TrackedPiece(event.piece(), Square.NONE);
          if (!candidates.isEmpty()) {
            sideArea.remove(candidates.get(0));
          }
        }
        default -> {
          return true;
        }
      }
      if (event.type() == BoardEventType.REMOVE) {
        sideArea.add(moving);
        continue;
      }
      if (event.targetSquare() == Square.NONE) {
        return true;
      }
      final TrackedPiece displaced = onBoard.put(event.targetSquare(), moving);
      if (displaced != null) {
        sideArea.add(displaced);
      }
    }
    for (final Square square : Square.values()) {
      if (square == Square.NONE) {
        continue;
      }
      final TrackedPiece piece = onBoard.get(square);
      if ((piece == null ? Piece.NONE : piece.piece()) != after.get(square)) {
        return true;
      }
    }
    return onBoard.entrySet().stream().filter(entry -> entry.getKey() != from && entry.getKey() != to)
        .allMatch(entry -> entry.getValue().origin() == entry.getKey());
  }

  /**
   * Physical move inferred from the player's board manipulations.
   */
  private record AttemptedMove(MoveSpecification moveSpecification, boolean castlingAttempt, Square kingReleaseSquare,
      boolean fullPhysicalCastlingAttempt) {
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
    final Optional<LegalMove> legalAttempt = board.getLegalMoves().stream()
        .filter(move -> move.moveSpecification().equals(moveSpecification)).findFirst();
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
      final Optional<IllegalMoveReason> enPassantReason = explainIncompleteEnPassant(legalAttempt, expectedPosition,
          afterPosition);
      if (enPassantReason.isPresent()) {
        return enPassantReason;
      }
      final String reason = "the move itself is legal, but the final board position is not correct";
      return Optional.of(new IllegalMoveReason(reason, reason));
    }
    return Optional.empty();
  }

  private static Optional<IllegalMoveReason> explainIncompleteEnPassant(Optional<LegalMove> legalAttempt,
      BitboardPosition expectedPosition, BitboardPosition afterPosition) {
    if (legalAttempt.isEmpty() || !legalAttempt.get().isEnPassant()) {
      return Optional.empty();
    }
    final Square capturedSquare = legalAttempt.get().enPassantCapturedPawnSquare();
    final Piece capturedPiece = legalAttempt.get().capturedPiece();
    if (afterPosition.get(capturedSquare) != capturedPiece) {
      return Optional.empty();
    }
    final BitboardPosition withCapturedPawnStillPresent = BitboardPositions.from(expectedPosition)
        .createChangedPosition(capturedSquare, capturedPiece).build();
    if (!withCapturedPawnStillPresent.equals(afterPosition)) {
      return Optional.empty();
    }
    final String reason = "the en passant capture is incomplete: the captured pawn on "
        + capturedSquare.getName() + " is still on the board";
    return Optional.of(new IllegalMoveReason(reason, reason));
  }

  /**
   * The move the player made on the board, read from what was done since the board was last set back by a
   * restoration: an earlier attempt of the same turn is no longer on the board, and counting its events would hide
   * the move when it is repeated.
   */
  private static Optional<AttemptedMove> inferAttemptedMove(Board board, BitboardPosition afterPosition,
      ActionSequence sequence) {
    final List<BoardEvent> events = sequence.getEventsSincePositionRestored();
    final Optional<AttemptedMove> castlingAttempt = inferPhysicalCastlingAttempt(board, afterPosition, events);
    if (castlingAttempt.isPresent()) {
      return castlingAttempt;
    }
    return inferSimpleAttemptedMove(board, afterPosition, events);
  }

  private static Optional<AttemptedMove> inferPhysicalCastlingAttempt(Board board, BitboardPosition afterPosition,
      List<BoardEvent> events) {
    return CastlingAttemptDetector.findPhysicalAttempt(board, afterPosition, events)
        .map(attempt -> new AttemptedMove(attempt.moveSpecification(), true, attempt.kingReleaseSquare(), true));
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
      List<BoardEvent> events) {
    BoardEvent moveEvent = null;

    for (final BoardEvent event : events) {
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
        moveSpecification.isCastling() ? moveEvent.targetSquare() : Square.NONE, false));
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

  /**
   * The library phrases its reasons conditionally ("it would ..."), which fits a move that was only presented to it.
   * The player has made this move on the board, so the arbiter states what it does. The wording comes from
   * {@link IllegalMoveReasons}; a reason without an entry there is passed through unchanged.
   */
  private static NormalizedReason normalizeIllegalMoveReason(String reason, boolean castlingAttempt) {
    final String naturalReason = IllegalMoveReasons.asStatement(reason);
    if (naturalReason.equals(reason)) {
      return new NormalizedReason(reason, reason);
    }
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

    final Square kingFrom = attemptedMove.moveSpecification().castlingMove().kingFromSquare(board.getSideToMove());
    final String actionDescription = attemptedMove.fullPhysicalCastlingAttempt()
        ? "attempted to castle by moving the king and rook"
        : "released the king on " + attemptedMove.kingReleaseSquare().getName() + ", which attempts to castle";
    if (hasLegalMovesFromSquare(board.getLegalMoves(), kingFrom)) {
      return opponent
          ? " Because your opponent " + actionDescription + ", and castling on this side is"
              + " illegal, after restoring the position they must make a legal move with the king."
          : " Because you " + actionDescription + ", and castling on this side is illegal,"
              + " after restoring the position you must make a legal move with the king.";
    }

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);
    if (obligation.isPresent()) {
      // A different first touch remains governed by the normal touch-move recovery path.
      return "";
    }

    return opponent
        ? " Because your opponent " + actionDescription + ", and castling on this side is"
            + " illegal, but the king has no legal move, after restoring the position they may make any legal move."
        : " Because you " + actionDescription + ", and castling on this side is illegal,"
            + " but the king has no legal move, after restoring the position you may make any legal move.";
  }

  private static boolean hasLegalMovesFromSquare(List<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().fromSquare() == square) {
        return true;
      }
      if (legalMove.moveSpecification().isCastling()
          && legalMove.moveSpecification().castlingMove().kingFromSquare(legalMove.movingSide()) == square) {
        return true;
      }
    }
    return false;
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
