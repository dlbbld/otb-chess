// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import java.util.List;
import java.util.Optional;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.otbchess.message.MessageKey;
import io.github.dlbbld.otbchess.message.MessageSeverity;
import io.github.dlbbld.otbchess.message.Messages;
import io.github.dlbbld.otbchess.touchmove.TouchMoveObligation;

/**
 * Represents the arbiter's response after evaluating a clock press or a mid-play event.
 *
 * @param type                 the type of response
 * @param playerMessageKey     message key rendered for the player
 * @param playerMessageArgs    positional arguments for the player message
 * @param opponentMessageKey   message key rendered for the opponent, if any
 * @param opponentMessageArgs  positional arguments for the opponent message
 * @param acceptedMove         present only for MOVE_ACCEPTED - the legal move that was played
 * @param obligation           present for TOUCH_MOVE_VIOLATION - the unsatisfied obligation
 * @param restorePosition      present when restoration must target a non-turn-start position
 * @param illegalMoveDetail    structured illegal-move context
 * @param releasedPieceContext structured released-piece context
 */
public record ArbiterResponse(ArbiterResponseType type, MessageKey playerMessageKey, List<Object> playerMessageArgs,
    Optional<MessageKey> opponentMessageKey, List<Object> opponentMessageArgs, Optional<LegalMove> acceptedMove,
    Optional<TouchMoveObligation> obligation, Optional<BitboardPosition> restorePosition,
    Optional<IllegalMoveDetail> illegalMoveDetail, Optional<ReleasedPieceContext> releasedPieceContext) {

  public ArbiterResponse {
    playerMessageArgs = List.copyOf(playerMessageArgs);
    opponentMessageArgs = List.copyOf(opponentMessageArgs);
  }

  /**
   * @param noMoveMade true for the FIDE 7.5.3 case — the clock was pressed with the board unchanged. Counted and
   *                   penalised like any illegal move, but there is nothing to restore: the messages say "make a
   *                   move" instead of "restore the position", and the server skips the restoration flow.
   */
  public record IllegalMoveDetail(Optional<String> playerReason, Optional<String> opponentReason, Side side, int count,
      int maxIllegalMoves, boolean unlimited, boolean noMoveMade) {

    String playerReasonPrefix() {
      if (playerReason.isPresent() && playerReason.get().startsWith("because ")) {
        return "Illegal move " + ensureSentence(playerReason.get()) + " ";
      }
      return playerReason.map(reason -> "Illegal move: " + ensureSentence(reason) + " ").orElse("Illegal move. ");
    }

    Optional<String> opponentReasonDetail() {
      return opponentReason.map(ArbiterResponse::ensureSentence);
    }

    String sideName() {
      return side == Side.WHITE ? "White" : "Black";
    }

    String countOrdinal() {
      return ordinalSuffix(count);
    }

    String maxOrdinal() {
      return ordinalSuffix(maxIllegalMoves);
    }
  }

  /**
   * The binding release: {@code piece} was released on {@code square}, having been picked up from {@code fromSquare}.
   * {@code fromSquare} is {@link Square#NONE} when the origin is not a board square (e.g. a promotion piece placed
   * from the side area, or the castling variant where the message names its own squares).
   */
  public record ReleasedPieceContext(Piece piece, Square square, Square fromSquare) {
  }

  /**
   * Released-piece context for the case where the release commits the player exclusively to a castling move. Carries
   * the castling direction and the rook from/to so the message can tell the player to complete the castling — rather
   * than misleadingly asking them to "put the king back", since the king is already on the right square.
   */
  public record ReleasedPieceCastlingContext(Piece piece, Square square, String castlingDirection, Square rookFrom,
      Square rookTo) {
  }

  /**
   * FIDE 4.4.2: a player who moves the rook before the king may not castle on that side. If the rook release is itself
   * a legal rook move, the move is fixed as that rook move and the later king displacement must be restored.
   */
  public record RookFirstCastlingContext(Piece piece, Square rookFrom, Square rookTo, Square kingFrom) {
  }

  public static ArbiterResponse moveAccepted(LegalMove move) {
    return new ArbiterResponse(ArbiterResponseType.MOVE_ACCEPTED, MessageKey.ARBITER_MOVE_ACCEPTED, List.of(),
        Optional.empty(), List.of(), Optional.of(move), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty());
  }

  public static ArbiterResponse touchMoveViolation(TouchMoveObligation obligation) {
    final MessageKey playerKey;
    final MessageKey opponentKey;
    final List<Object> args;
    switch (obligation.type()) {
      case OWN_PIECE -> {
        // When the binding piece was reached only after touching own pieces with no legal moves, the
        // word "first" would be misleading, so a variant message names it as the first touched piece
        // that can move.
        if (obligation.precededByUnmovableOwnTouch()) {
          playerKey = MessageKey.ARBITER_TOUCH_MOVE_OWN_PRECEDED_PLAYER;
          opponentKey = MessageKey.ARBITER_TOUCH_MOVE_OWN_PRECEDED_OPPONENT;
        } else {
          playerKey = MessageKey.ARBITER_TOUCH_MOVE_OWN_PLAYER;
          opponentKey = MessageKey.ARBITER_TOUCH_MOVE_OWN_OPPONENT;
        }
        args = List.of(formatPieceName(obligation.piece()), obligation.square().getName());
      }
      case OPPONENT_PIECE -> {
        playerKey = MessageKey.ARBITER_TOUCH_MOVE_OPPONENT_PLAYER;
        opponentKey = MessageKey.ARBITER_TOUCH_MOVE_OPPONENT_OPPONENT;
        args = List.of(formatPieceName(obligation.piece()), obligation.square().getName());
      }
      case CASTLING -> {
        playerKey = MessageKey.ARBITER_TOUCH_MOVE_CASTLING_PLAYER;
        opponentKey = MessageKey.ARBITER_TOUCH_MOVE_CASTLING_OPPONENT;
        args = List.of();
      }
      case SPECIFIC_CAPTURE -> {
        playerKey = MessageKey.ARBITER_TOUCH_MOVE_SPECIFIC_CAPTURE_PLAYER;
        opponentKey = MessageKey.ARBITER_TOUCH_MOVE_SPECIFIC_CAPTURE_OPPONENT;
        args = List.of(formatPieceName(obligation.piece()), obligation.square().getName(),
            formatPieceName(obligation.capturedPiece()), obligation.toSquare().getName());
      }
      default -> throw new IllegalStateException("Unhandled obligation type: " + obligation.type());
    }
    return new ArbiterResponse(ArbiterResponseType.TOUCH_MOVE_VIOLATION, playerKey, args, Optional.of(opponentKey),
        args, Optional.empty(), Optional.of(obligation), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /**
   * @param ambiguousOrigin true when another piece of the same kind could also have legally reached the release
   *                        square, so "the {piece} on {square}" alone would not tell the player WHICH piece was
   *                        released (e.g. knights on c3 and g5 both reaching e4). The message then names the origin:
   *                        "released the {piece} from {from} on {square}" — SAN-style disambiguation, only when
   *                        needed. Requires a known {@code fromSquare}.
   */
  public static ArbiterResponse releasedPieceViolation(ReleasedPieceContext context, boolean ambiguousOrigin,
      BitboardPosition restorePosition) {
    final boolean nameOrigin = ambiguousOrigin && context.fromSquare() != Square.NONE;
    final MessageKey playerKey = nameOrigin ? MessageKey.ARBITER_RELEASED_PIECE_FROM_PLAYER
        : MessageKey.ARBITER_RELEASED_PIECE_PLAYER;
    final MessageKey opponentKey = nameOrigin ? MessageKey.ARBITER_RELEASED_PIECE_FROM_OPPONENT
        : MessageKey.ARBITER_RELEASED_PIECE_OPPONENT;
    final List<Object> args = nameOrigin
        ? List.of(formatPieceName(context.piece()), context.fromSquare().getName(), context.square().getName())
        : List.of(formatPieceName(context.piece()), context.square().getName());
    return new ArbiterResponse(ArbiterResponseType.RELEASED_PIECE_VIOLATION, playerKey,
        args, Optional.of(opponentKey), args, Optional.empty(), Optional.empty(),
        Optional.ofNullable(restorePosition), Optional.empty(), Optional.of(context));
  }

  /**
   * Released-piece violation where the release commits the player to castling. The message tells the player to complete
   * the castling by placing the rook on its target square; the king is already correctly placed.
   */
  public static ArbiterResponse releasedPieceViolationCastling(ReleasedPieceCastlingContext context,
      BitboardPosition restorePosition) {
    final List<Object> args = List.of(context.square().getName(), context.castlingDirection(),
        context.rookFrom().getName(), context.rookTo().getName());
    // The standard ReleasedPieceContext (piece + square) is also carried, so callers
    // that read structured fields without distinguishing castling still see the king
    // and the release square. No origin: the castling message names its own squares,
    // and a castling king release is never ambiguous (there is only one king).
    final ReleasedPieceContext releasedContext = new ReleasedPieceContext(context.piece(), context.square(),
        Square.NONE);
    return new ArbiterResponse(ArbiterResponseType.RELEASED_PIECE_VIOLATION,
        MessageKey.ARBITER_RELEASED_PIECE_CASTLING_PLAYER, args,
        Optional.of(MessageKey.ARBITER_RELEASED_PIECE_CASTLING_OPPONENT), args, Optional.empty(), Optional.empty(),
        Optional.ofNullable(restorePosition), Optional.empty(), Optional.of(releasedContext));
  }

  public static ArbiterResponse releasedPieceViolationRookFirstCastling(RookFirstCastlingContext context,
      BitboardPosition restorePosition) {
    final List<Object> args = List.of(context.rookFrom().getName(), context.rookTo().getName(),
        context.kingFrom().getName());
    final ReleasedPieceContext releasedContext = new ReleasedPieceContext(context.piece(), context.rookTo(),
        context.rookFrom());
    return new ArbiterResponse(ArbiterResponseType.RELEASED_PIECE_VIOLATION,
        MessageKey.ARBITER_RELEASED_PIECE_ROOK_FIRST_CASTLING_PLAYER, args,
        Optional.of(MessageKey.ARBITER_RELEASED_PIECE_ROOK_FIRST_CASTLING_OPPONENT), args, Optional.empty(),
        Optional.empty(), Optional.ofNullable(restorePosition), Optional.empty(), Optional.of(releasedContext));
  }

  public static ArbiterResponse illegalMove(IllegalMoveDetail detail) {
    final MessageKey playerKey;
    final List<Object> playerArgs;
    // The no-move variants (FIDE 7.5.3) end with "Please make a move." — there is nothing to restore.
    if (detail.unlimited()) {
      playerKey = detail.noMoveMade() ? MessageKey.ARBITER_ILLEGAL_MOVE_NO_MOVE_PLAYER_UNLIMITED
          : MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_UNLIMITED;
      playerArgs = List.of(detail.playerReasonPrefix(), detail.count(), detail.countOrdinal());
    } else {
      final int remaining = detail.maxIllegalMoves() - detail.count();
      if (remaining == 1) {
        playerKey = detail.noMoveMade() ? MessageKey.ARBITER_ILLEGAL_MOVE_NO_MOVE_PLAYER_NEXT
            : MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT;
        playerArgs = List.of(detail.playerReasonPrefix(), detail.count(), detail.countOrdinal());
      } else {
        playerKey = detail.noMoveMade() ? MessageKey.ARBITER_ILLEGAL_MOVE_NO_MOVE_PLAYER_LIMIT
            : MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_LIMIT;
        playerArgs = List.of(detail.playerReasonPrefix(), detail.count(), detail.countOrdinal(),
            detail.maxIllegalMoves(), detail.maxOrdinal());
      }
    }

    final Optional<String> opponentReason = detail.opponentReasonDetail();
    final Optional<MessageKey> opponentKey = Optional
        .of(opponentReason.isPresent()
            ? (detail.noMoveMade() ? MessageKey.ARBITER_ILLEGAL_MOVE_NO_MOVE_OPPONENT
                : MessageKey.ARBITER_ILLEGAL_MOVE_OPPONENT)
            : MessageKey.ARBITER_ILLEGAL_MOVE_OPPONENT_GENERIC);
    final List<Object> opponentArgs = opponentReason.<List<Object>>map(List::of).orElseGet(List::of);

    return new ArbiterResponse(ArbiterResponseType.ILLEGAL_MOVE, playerKey, playerArgs, opponentKey, opponentArgs,
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(detail), Optional.empty());
  }

  public static ArbiterResponse illegalMoveGameLost(IllegalMoveDetail detail) {
    return new ArbiterResponse(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST,
        MessageKey.ARBITER_ILLEGAL_MOVE_GAME_LOST_PLAYER,
        List.of(detail.playerReasonPrefix(), detail.sideName(), detail.count(), detail.countOrdinal()),
        Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(detail),
        Optional.empty());
  }

  public static ArbiterResponse illegalMoveGameLost(String message) {
    return custom(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, MessageKey.CUSTOM_ERROR, message);
  }

  public static ArbiterResponse incompleteMove(String message) {
    return custom(ArbiterResponseType.INCOMPLETE_MOVE, MessageKey.CUSTOM_INFO, message);
  }

  public static ArbiterResponse positionChange(String message) {
    return custom(ArbiterResponseType.POSITION_CHANGE, MessageKey.CUSTOM_ERROR, message);
  }

  public static ArbiterResponse revertOpponentPiece() {
    return new ArbiterResponse(ArbiterResponseType.POSITION_CHANGE, MessageKey.ARBITER_POSITION_CHANGE_OPPONENT_PIECE,
        List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty());
  }

  public static ArbiterResponse revertRestoration(String message) {
    return positionChange(message);
  }

  @Deprecated(forRemoval = true)
  public String message() {
    return renderedPlayerMessage();
  }

  public String renderedPlayerMessage() {
    return Messages.get(playerMessageKey, playerMessageArgs.toArray());
  }

  public Optional<String> renderedOpponentMessage() {
    return opponentMessageKey.map(key -> Messages.get(key, opponentMessageArgs.toArray()));
  }

  public MessageSeverity severity() {
    return playerMessageKey.severity();
  }

  public String style() {
    return severity().style();
  }

  private static ArbiterResponse custom(ArbiterResponseType type, MessageKey key, String message) {
    return new ArbiterResponse(type, key, List.of(message), Optional.empty(), List.of(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static String formatPieceName(Piece piece) {
    return switch (piece.getPieceType()) {
      case KING -> "king";
      case QUEEN -> "queen";
      case ROOK -> "rook";
      case BISHOP -> "bishop";
      case KNIGHT -> "knight";
      case PAWN -> "pawn";
      default -> "piece";
    };
  }

  private static String ensureSentence(String text) {
    final String trimmed = text.trim();
    if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
      return trimmed;
    }
    return trimmed + ".";
  }

  private static String ordinalSuffix(int n) {
    final int mod100 = n % 100;
    if (mod100 >= 11 && mod100 <= 13) {
      return "th";
    }
    return switch (n % 10) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }
}
