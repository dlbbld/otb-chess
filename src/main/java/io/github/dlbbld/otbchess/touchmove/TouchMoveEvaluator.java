package io.github.dlbbld.otbchess.touchmove;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.moves.CastlingUtility;
import io.github.dlbbld.otbchess.castling.CastlingAttemptDetector;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.ashlarchess.model.LegalMove;

/**
 * Evaluates touch-move obligations from the action sequence.
 *
 * <p>Scans the action sequence from the beginning. For each touch/move event:
 * <ul>
 *   <li>If an own piece is touched and has legal moves from that square → OWN_PIECE obligation</li>
 *   <li>If an opponent piece is touched and can be legally captured → OPPONENT_PIECE obligation</li>
 * </ul>
 * The first obligation found in the sequence is the binding one.
 */
public class TouchMoveEvaluator {

  /**
   * Finds the first touch-move obligation in the action sequence.
   *
   * <p>Detection order:
   * <ol>
   *   <li><b>King-then-rook combined touch (FIDE 4.4.a)</b> — if the player has touched their
   *       own king (on the king's starting square) and then their own rook (on a rook starting
   *       square), and castling on the touched rook's side is legal, return a CASTLING
   *       obligation. This takes precedence over the plain own-piece obligation that the king
   *       touch would otherwise create, because castling is the strictly more specific commitment.</li>
   *   <li>Otherwise, scan for the first own-piece or opponent-piece obligation (existing rules).</li>
   * </ol>
   *
   * @param sequence the action sequence recorded during the player's turn
   * @param board    the board state before the player's turn (used to check legal moves)
   * @return the binding touch-move obligation, or empty if none
   */
  public static Optional<TouchMoveObligation> findObligation(ActionSequence sequence, Board board) {
    final Side sideToMove = sequence.getSideToMove();
    final Set<LegalMove> legalMoves = new HashSet<>(board.getLegalMoves());
    final List<BoardEvent> events = sequence.getEvents();

    final Optional<TouchMoveObligation> castlingObligation = findCastlingObligation(events, sideToMove, legalMoves);
    if (castlingObligation.isPresent()) {
      return castlingObligation;
    }

    // FIDE 4.3.3: touching an own piece and an opponent piece it can legally capture binds that
    // specific capture. This is more specific than the plain own-piece / opponent-piece rules, so
    // it takes precedence over the first-obligation scan below.
    final Optional<TouchMoveObligation> specificCapture = findSpecificCaptureObligation(events, sideToMove,
        legalMoves);
    if (specificCapture.isPresent()) {
      return specificCapture;
    }

    for (int i = 0; i < events.size(); i++) {
      if (CastlingAttemptDetector.isFailedAttemptWithNoLegalKingMove(events, i, sideToMove, legalMoves)) {
        i++;
        continue;
      }
      final BoardEvent event = events.get(i);
      final Optional<TouchMoveObligation> obligation = evaluateEvent(event, sideToMove, legalMoves);
      if (obligation.isPresent()) {
        return obligation;
      }
    }

    return Optional.empty();
  }

  /**
   * Detects the king-then-rook touch pattern (FIDE 4.4.a) and returns a CASTLING obligation
   * if castling on the touched rook's side is legal. Returns empty otherwise.
   *
   * <p>Order matters: the king must be touched before the rook. Rook-first creates a regular
   * own-piece obligation under the existing rule (and our spec disallows castling that begins
   * with a rook move regardless).
   */
  private static Optional<TouchMoveObligation> findCastlingObligation(List<BoardEvent> events, Side sideToMove,
      Set<LegalMove> legalMoves) {
    final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(sideToMove,
        new io.github.dlbbld.ashlarchess.common.model.MoveSpecification(CastlingMove.KING_SIDE));
    final Piece kingPiece = Piece.calculateKingPiece(sideToMove);
    final Piece rookPiece = Piece.calculateRookPiece(sideToMove);
    final Square kingSideRook = Square.calculateKingSideRookOriginalSquare(sideToMove);
    final Square queenSideRook = Square.calculateQueenSideRookOriginalSquare(sideToMove);

    boolean kingTouched = false;
    for (final BoardEvent event : events) {
      final Piece piece = event.piece();
      final Square touchedSquare = determineTouchedSquare(event);
      if (touchedSquare == Square.NONE) {
        continue;
      }

      if (!kingTouched) {
        if (piece == kingPiece && touchedSquare == kingFrom) {
          kingTouched = true;
        } else if (piece == rookPiece && (touchedSquare == kingSideRook || touchedSquare == queenSideRook)) {
          // FIDE 4.4.2: deliberately touching a (castling) rook before the king forbids castling
          // with it this move. Abandon the castling obligation and fall through to the first-
          // obligation scan, which binds the touched rook as a normal own-piece obligation. A
          // later king/rook castling motion must then NOT be accepted as castling.
          return Optional.empty();
        }
        continue;
      }

      if (piece != rookPiece) {
        continue;
      }
      final CastlingMove side;
      if (touchedSquare == kingSideRook) {
        side = CastlingMove.KING_SIDE;
      } else if (touchedSquare == queenSideRook) {
        side = CastlingMove.QUEEN_SIDE;
      } else {
        continue;
      }

      if (isCastlingLegalOnSide(legalMoves, sideToMove, side)) {
        return Optional.of(new TouchMoveObligation(TouchMoveType.CASTLING, kingFrom, kingPiece, side));
      }
      // Rook touched on a side where castling is not legal — fall through to the existing
      // first-obligation scan, which will treat the king touch as a normal own-piece obligation
      // and may add a separate own-piece obligation for the rook (subject to the existing
      // single-obligation limit, see SPECIFICATION.md "Touch-move accumulation" follow-up).
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static boolean isCastlingLegalOnSide(Set<LegalMove> legalMoves, Side sideToMove, CastlingMove side) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.havingMove() != sideToMove) {
        continue;
      }
      if (CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())
          && legalMove.moveSpecification().castlingMove() == side) {
        return true;
      }
    }
    return false;
  }

  /**
   * Detects FIDE 4.3.3: the player touched one of their own pieces (that has legal moves) and one of
   * the opponent's pieces, and the own piece can legally capture the opponent piece. The player must
   * then make that specific capture. Returns empty when the pattern does not apply, in which case the
   * caller falls back to the first-touched obligation (which is exactly the 4.3.3 fallback: move or
   * capture the first piece touched that can be moved or captured).
   */
  private static Optional<TouchMoveObligation> findSpecificCaptureObligation(List<BoardEvent> events,
      Side sideToMove, Set<LegalMove> legalMoves) {
    Square ownSquare = Square.NONE;
    Piece ownPiece = Piece.NONE;
    Square opponentSquare = Square.NONE;
    Piece opponentPiece = Piece.NONE;

    for (final BoardEvent event : events) {
      final Piece piece = event.piece();
      if (piece == Piece.NONE) {
        continue;
      }
      final Square touchedSquare = determineTouchedSquare(event);
      if (touchedSquare == Square.NONE) {
        continue;
      }
      if (piece.getSide() == sideToMove) {
        if (ownSquare == Square.NONE && hasLegalMovesFromSquare(legalMoves, touchedSquare)) {
          ownSquare = touchedSquare;
          ownPiece = piece;
        }
      } else if (opponentSquare == Square.NONE && canBeCapturedOnSquare(legalMoves, touchedSquare)) {
        opponentSquare = touchedSquare;
        opponentPiece = piece;
      }
    }

    if (ownSquare == Square.NONE || opponentSquare == Square.NONE) {
      return Optional.empty();
    }
    if (!canCapture(legalMoves, ownSquare, opponentSquare)) {
      return Optional.empty();
    }
    return Optional.of(TouchMoveObligation.specificCapture(ownSquare, ownPiece, opponentSquare, opponentPiece));
  }

  /** Whether there is a legal move from {@code fromSquare} to {@code toSquare} that captures a piece. */
  private static boolean canCapture(Set<LegalMove> legalMoves, Square fromSquare, Square toSquare) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().fromSquare() == fromSquare
          && legalMove.moveSpecification().toSquare() == toSquare
          && legalMove.pieceCaptured() != Piece.NONE) {
        return true;
      }
    }
    return false;
  }

  private static Optional<TouchMoveObligation> evaluateEvent(BoardEvent event, Side sideToMove,
      Set<LegalMove> legalMoves) {

    final Piece piece = event.piece();
    if (piece == Piece.NONE) {
      return Optional.empty();
    }

    // Determine the square where the piece was touched
    final Square touchedSquare = determineTouchedSquare(event);
    if (touchedSquare == Square.NONE) {
      return Optional.empty();
    }

    final Side pieceSide = piece.getSide();

    if (pieceSide == sideToMove) {
      // Own piece touched: check if it has any legal moves from that square
      if (hasLegalMovesFromSquare(legalMoves, touchedSquare)) {
        return Optional.of(new TouchMoveObligation(TouchMoveType.OWN_PIECE, touchedSquare, piece));
      }
    } else {
      // Opponent piece touched: check if it can be legally captured
      if (canBeCapturedOnSquare(legalMoves, touchedSquare)) {
        return Optional.of(new TouchMoveObligation(TouchMoveType.OPPONENT_PIECE, touchedSquare, piece));
      }
    }

    return Optional.empty();
  }

  /**
   * Determines the square where the piece was touched, based on the event type.
   * For CLICK and REMOVE: the square of the piece.
   * For DRAG_MOVE and DRAG_CAPTURE: the source square (where the piece was picked up).
   * For RESTORE events: no touch-move applies (piece is from side area).
   */
  private static Square determineTouchedSquare(BoardEvent event) {
    return switch (event.type()) {
      case CLICK, REMOVE -> event.square();
      case DRAG_MOVE, DRAG_CAPTURE -> event.square();
      case RESTORE_TO_EMPTY, RESTORE_TO_OCCUPIED -> Square.NONE;
    };
  }

  /**
   * Checks whether there are any legal moves originating from the given square.
   * For castling moves, the fromSquare is NONE in the MoveSpecification, so we
   * additionally check the king's castling origin square.
   */
  private static boolean hasLegalMovesFromSquare(Set<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().fromSquare() == square) {
        return true;
      }
      // Castling: MoveSpecification has fromSquare = NONE, but the king originates from a specific square
      if (CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())) {
        final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(legalMove.havingMove(),
            legalMove.moveSpecification());
        if (kingFrom == square) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Checks whether the piece on the given square can be legally captured.
   */
  private static boolean canBeCapturedOnSquare(Set<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().toSquare() == square && legalMove.pieceCaptured() != Piece.NONE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether a given legal move satisfies a touch-move obligation.
   *
   * @param obligation the touch-move obligation to satisfy
   * @param legalMove  the legal move to check
   * @return true if the move satisfies the obligation
   */
  public static boolean satisfiesObligation(TouchMoveObligation obligation, LegalMove legalMove) {
    return switch (obligation.type()) {
      case OWN_PIECE -> {
        // Must move the touched piece: the move must originate from the obligation square
        if (legalMove.moveSpecification().fromSquare() == obligation.square()) {
          yield true;
        }
        // Castling: king originates from the obligation square
        if (CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())) {
          final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(legalMove.havingMove(),
              legalMove.moveSpecification());
          yield kingFrom == obligation.square();
        }
        yield false;
      }
      case OPPONENT_PIECE ->
        // Must capture the touched opponent piece: the move must land on the obligation square and be a capture
        legalMove.moveSpecification().toSquare() == obligation.square() && legalMove.pieceCaptured() != Piece.NONE;
      case SPECIFIC_CAPTURE ->
        // FIDE 4.3.3: must capture the touched opponent piece with the touched own piece — the move
        // must originate from the own square, land on the opponent square, and be a capture.
        legalMove.moveSpecification().fromSquare() == obligation.square()
            && legalMove.moveSpecification().toSquare() == obligation.toSquare()
            && legalMove.pieceCaptured() != Piece.NONE;
      case CASTLING ->
        // Must castle on the touched rook's side. Only the matching castling move satisfies it.
        CastlingUtility.calculateIsCastlingMove(legalMove.moveSpecification())
            && legalMove.moveSpecification().castlingMove() == obligation.castlingMove();
    };
  }
}
