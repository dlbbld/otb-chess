package com.dlb.chess.dumbboard.castling;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.CastlingMove;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.common.model.MoveSpecification;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.event.BoardEventType;
import com.dlb.chess.model.LegalMove;
import com.dlb.chess.moves.utility.CastlingUtility;

public final class CastlingAttemptDetector {

  private CastlingAttemptDetector() {
  }

  public record Attempt(
      MoveSpecification moveSpecification,
      Square kingReleaseSquare) {
  }

  public static Optional<Attempt> findPhysicalAttempt(ApiBoard board, StaticPosition afterPosition,
      List<BoardEvent> events) {
    final List<BoardEvent> moveEvents = filterDragEventsOnly(events);
    if (moveEvents.size() != 2) {
      return Optional.empty();
    }

    final BoardEvent kingEvent = moveEvents.get(0);
    final BoardEvent rookEvent = moveEvents.get(1);
    for (final CastlingMove castlingMove : List.of(CastlingMove.KING_SIDE, CastlingMove.QUEEN_SIDE)) {
      final MoveSpecification moveSpecification = new MoveSpecification(castlingMove);
      if (isCastlingAttempt(board.getHavingMove(), moveSpecification, castlingMove, kingEvent, rookEvent)
          && isPositionAfterEvents(board.getStaticPosition(), afterPosition, kingEvent, rookEvent)) {
        return Optional.of(new Attempt(moveSpecification, kingEvent.targetSquare()));
      }
    }
    return Optional.empty();
  }

  public static boolean isFailedAttemptWithNoLegalKingMove(List<BoardEvent> events, int index, Side sideToMove,
      Set<LegalMove> legalMoves) {
    if (index + 1 >= events.size()) {
      return false;
    }
    final BoardEvent kingEvent = events.get(index);
    final BoardEvent rookEvent = events.get(index + 1);
    for (final CastlingMove castlingMove : List.of(CastlingMove.KING_SIDE, CastlingMove.QUEEN_SIDE)) {
      final MoveSpecification moveSpecification = new MoveSpecification(castlingMove);
      final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(sideToMove, moveSpecification);
      if (!hasLegalMovesFromSquare(legalMoves, kingFrom)
          && isCastlingAttempt(sideToMove, moveSpecification, castlingMove, kingEvent, rookEvent)) {
        return true;
      }
    }
    return false;
  }

  public static Square calculateRookCastlingTo(Side sideToMove, CastlingMove castlingMove) {
    return switch (castlingMove) {
      case KING_SIDE -> switch (sideToMove) {
        case WHITE -> Square.F1;
        case BLACK -> Square.F8;
        case NONE -> throw new IllegalArgumentException();
      };
      case QUEEN_SIDE -> switch (sideToMove) {
        case WHITE -> Square.D1;
        case BLACK -> Square.D8;
        case NONE -> throw new IllegalArgumentException();
      };
      case NONE -> throw new IllegalArgumentException();
    };
  }

  private static List<BoardEvent> filterDragEventsOnly(List<BoardEvent> events) {
    final List<BoardEvent> result = new ArrayList<>();
    for (final BoardEvent event : events) {
      if (event.type() == BoardEventType.CLICK) {
        continue;
      }
      if (!isDragEvent(event)) {
        return List.of();
      }
      result.add(event);
    }
    return result;
  }

  private static boolean isCastlingAttempt(Side sideToMove, MoveSpecification moveSpecification,
      CastlingMove castlingMove, BoardEvent kingEvent, BoardEvent rookEvent) {
    return isStandardCastlingAttempt(sideToMove, moveSpecification, castlingMove, kingEvent, rookEvent)
        || isAdjacentSwappedCastlingAttempt(sideToMove, moveSpecification, castlingMove, kingEvent, rookEvent);
  }

  private static boolean isStandardCastlingAttempt(Side sideToMove, MoveSpecification moveSpecification,
      CastlingMove castlingMove, BoardEvent kingEvent, BoardEvent rookEvent) {
    return isKingEvent(sideToMove, moveSpecification, kingEvent)
        && kingEvent.targetSquare() == CastlingUtility.calculateKingCastlingTo(sideToMove, moveSpecification)
        && isRookEvent(sideToMove, castlingMove, rookEvent)
        && rookEvent.targetSquare() == calculateRookCastlingTo(sideToMove, castlingMove);
  }

  private static boolean isAdjacentSwappedCastlingAttempt(Side sideToMove, MoveSpecification moveSpecification,
      CastlingMove castlingMove, BoardEvent kingEvent, BoardEvent rookEvent) {
    return isKingEvent(sideToMove, moveSpecification, kingEvent)
        && kingEvent.targetSquare() == calculateRookCastlingTo(sideToMove, castlingMove)
        && isRookEvent(sideToMove, castlingMove, rookEvent)
        && rookEvent.targetSquare() == CastlingUtility.calculateKingCastlingTo(sideToMove, moveSpecification);
  }

  private static boolean isKingEvent(Side sideToMove, MoveSpecification moveSpecification, BoardEvent event) {
    return isDragEvent(event)
        && event.piece() == Piece.calculateKingPiece(sideToMove)
        && event.square() == CastlingUtility.calculateKingCastlingFrom(sideToMove, moveSpecification);
  }

  private static boolean isRookEvent(Side sideToMove, CastlingMove castlingMove, BoardEvent event) {
    return isDragEvent(event)
        && event.piece() == Piece.calculateRookPiece(sideToMove)
        && event.square() == calculateRookCastlingFrom(sideToMove, castlingMove);
  }

  public static Square calculateRookCastlingFrom(Side sideToMove, CastlingMove castlingMove) {
    return switch (castlingMove) {
      case KING_SIDE -> Square.calculateKingSideRookOriginalSquare(sideToMove);
      case QUEEN_SIDE -> Square.calculateQueenSideRookOriginalSquare(sideToMove);
      case NONE -> throw new IllegalArgumentException();
    };
  }

  private static boolean isPositionAfterEvents(StaticPosition beforePosition, StaticPosition afterPosition,
      BoardEvent kingEvent, BoardEvent rookEvent) {
    final StaticPosition expectedPhysicalPosition = beforePosition
        .createChangedPosition(kingEvent.square(), Piece.NONE)
        .createChangedPosition(rookEvent.square(), Piece.NONE)
        .createChangedPosition(kingEvent.targetSquare(), kingEvent.piece())
        .createChangedPosition(rookEvent.targetSquare(), rookEvent.piece());
    return expectedPhysicalPosition.equals(afterPosition);
  }

  private static boolean hasLegalMovesFromSquare(Set<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().fromSquare() == square) {
        return true;
      }
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

  private static boolean isDragEvent(BoardEvent event) {
    return event.type() == BoardEventType.DRAG_MOVE || event.type() == BoardEventType.DRAG_CAPTURE;
  }
}
