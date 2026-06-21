package io.github.dlbbld.otbchess.castling;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.PieceType;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.enums.SquareUtility;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.model.UpdateSquare;
import io.github.dlbbld.ashlarchess.common.model.MoveSpecification;
import io.github.dlbbld.ashlarchess.model.LegalMove;
import io.github.dlbbld.ashlarchess.moves.CastlingUtility;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.event.BoardEventType;

public final class CastlingAttemptDetector {

  private CastlingAttemptDetector() {
  }

  public record Attempt(MoveSpecification moveSpecification, Square kingReleaseSquare) {
  }

  public static Optional<Attempt> findPhysicalAttempt(Board board, BitboardPosition afterPosition,
      List<BoardEvent> events) {
    final List<BoardEvent> moveEvents = filterDragEventsOnly(events);
    if (moveEvents.size() != 2) {
      return Optional.empty();
    }

    final BoardEvent kingEvent = moveEvents.get(0);
    final BoardEvent rookEvent = moveEvents.get(1);
    for (final CastlingMove castlingMove : List.of(CastlingMove.KING_SIDE, CastlingMove.QUEEN_SIDE)) {
      final MoveSpecification moveSpecification = new MoveSpecification(castlingMove);
      if (isCastlingAttempt(board.getSideToMove(), moveSpecification, castlingMove, kingEvent, rookEvent)
          && isPositionAfterEvents(board.getBitboardPosition(), afterPosition, kingEvent, rookEvent)) {
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
    return isDragEvent(event) && event.piece() == Piece.of(sideToMove, PieceType.KING)
        && event.square() == CastlingUtility.calculateKingCastlingFrom(sideToMove, moveSpecification);
  }

  private static boolean isRookEvent(Side sideToMove, CastlingMove castlingMove, BoardEvent event) {
    return isDragEvent(event) && event.piece() == Piece.of(sideToMove, PieceType.ROOK)
        && event.square() == calculateRookCastlingFrom(sideToMove, castlingMove);
  }

  public static Square calculateRookCastlingFrom(Side sideToMove, CastlingMove castlingMove) {
    return switch (castlingMove) {
      case KING_SIDE -> SquareUtility.calculateKingSideRookOriginalSquare(sideToMove);
      case QUEEN_SIDE -> SquareUtility.calculateQueenSideRookOriginalSquare(sideToMove);
      case NONE -> throw new IllegalArgumentException();
    };
  }

  private static boolean isPositionAfterEvents(BitboardPosition beforePosition, BitboardPosition afterPosition,
      BoardEvent kingEvent, BoardEvent rookEvent) {
    final BitboardPosition expectedPhysicalPosition = BitboardPositions.withUpdates(beforePosition,
        List.of(new UpdateSquare(kingEvent.square(), Piece.NONE), new UpdateSquare(rookEvent.square(), Piece.NONE),
            new UpdateSquare(kingEvent.targetSquare(), kingEvent.piece()),
            new UpdateSquare(rookEvent.targetSquare(), rookEvent.piece())));
    return expectedPhysicalPosition.equals(afterPosition);
  }

  private static boolean hasLegalMovesFromSquare(Set<LegalMove> legalMoves, Square square) {
    for (final LegalMove legalMove : legalMoves) {
      if (legalMove.moveSpecification().fromSquare() == square) {
        return true;
      }
      if (CastlingUtility.isCastlingMove(legalMove.moveSpecification())) {
        final Square kingFrom = CastlingUtility.calculateKingCastlingFrom(legalMove.movingSide(),
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
