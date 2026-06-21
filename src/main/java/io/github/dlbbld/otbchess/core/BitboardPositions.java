package io.github.dlbbld.otbchess.core;

import java.util.ArrayList;
import java.util.List;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.model.UpdateSquare;

/**
 * Derives a {@link BitboardPosition} by overlaying explicit square-to-piece updates onto a base position.
 *
 * <p>
 * This replaces the {@code StaticPosition.createChangedPosition(...)} family from the pre-Ashlar API. Ashlar's
 * {@link BitboardPosition} is an immutable twelve-bitboard record with no public "set this square to this piece"
 * primitive, so the board's reconstruction of a physical position from board events rebuilds the bitboards directly.
 * The board uses little-endian rank-file indexing (a1 = bit 0, h8 = bit 63).
 *
 * <p>
 * {@link BitboardPosition}'s constructor only enforces that the bitboards are pairwise disjoint (no square carries two
 * pieces); it does not require a legal position. Each update therefore vacates the square on every bitboard before
 * placing the new occupant, which keeps every intermediate position disjoint even for the partial / transient
 * placements the board derives from physical board events.
 */
public final class BitboardPositions {

  private BitboardPositions() {
  }

  /** Returns {@code base} with the single given square set to {@code piece} ({@code NONE} clears it). */
  public static BitboardPosition withUpdate(BitboardPosition base, Square square, Piece piece) {
    return withUpdates(base, List.of(new UpdateSquare(square, piece)));
  }

  /** Returns {@code base} with the given square updates applied in order. */
  public static BitboardPosition withUpdates(BitboardPosition base, List<UpdateSquare> updateList) {
    long whitePawns = base.whitePawns();
    long whiteRooks = base.whiteRooks();
    long whiteKnights = base.whiteKnights();
    long whiteBishops = base.whiteBishops();
    long whiteQueens = base.whiteQueens();
    long whiteKings = base.whiteKings();
    long blackPawns = base.blackPawns();
    long blackRooks = base.blackRooks();
    long blackKnights = base.blackKnights();
    long blackBishops = base.blackBishops();
    long blackQueens = base.blackQueens();
    long blackKings = base.blackKings();

    for (final UpdateSquare update : updateList) {
      final long bit = 1L << index(update.square());
      final long clear = ~bit;
      whitePawns &= clear;
      whiteRooks &= clear;
      whiteKnights &= clear;
      whiteBishops &= clear;
      whiteQueens &= clear;
      whiteKings &= clear;
      blackPawns &= clear;
      blackRooks &= clear;
      blackKnights &= clear;
      blackBishops &= clear;
      blackQueens &= clear;
      blackKings &= clear;

      switch (update.piece()) {
        case WHITE_PAWN -> whitePawns |= bit;
        case WHITE_ROOK -> whiteRooks |= bit;
        case WHITE_KNIGHT -> whiteKnights |= bit;
        case WHITE_BISHOP -> whiteBishops |= bit;
        case WHITE_QUEEN -> whiteQueens |= bit;
        case WHITE_KING -> whiteKings |= bit;
        case BLACK_PAWN -> blackPawns |= bit;
        case BLACK_ROOK -> blackRooks |= bit;
        case BLACK_KNIGHT -> blackKnights |= bit;
        case BLACK_BISHOP -> blackBishops |= bit;
        case BLACK_QUEEN -> blackQueens |= bit;
        case BLACK_KING -> blackKings |= bit;
        case NONE -> {
          // square left empty
        }
        default -> throw new IllegalArgumentException("Unexpected piece: " + update.piece());
      }
    }

    return new BitboardPosition(whitePawns, whiteRooks, whiteKnights, whiteBishops, whiteQueens, whiteKings, blackPawns,
        blackRooks, blackKnights, blackBishops, blackQueens, blackKings);
  }

  private static int index(Square square) {
    final int file = square.getFile().getLetter() - 'a';
    final int rank = square.getRank().getNumber() - 1;
    return file + 8 * rank;
  }

  /** Starts a fluent overlay onto {@code base}; finish with {@link Builder#build()}. */
  public static Builder from(BitboardPosition base) {
    return new Builder(base);
  }

  /** Accumulates square-to-piece overlays and applies them all in {@link #build()}. */
  public static final class Builder {

    private final BitboardPosition base;
    private final List<UpdateSquare> updates = new ArrayList<>();

    private Builder(BitboardPosition base) {
      this.base = base;
    }

    /** Records a square update ({@code NONE} clears the square) and returns this builder. */
    public Builder createChangedPosition(Square square, Piece piece) {
      updates.add(new UpdateSquare(square, piece));
      return this;
    }

    /** Returns the base position with every recorded update applied in order. */
    public BitboardPosition build() {
      return withUpdates(base, updates);
    }
  }
}
