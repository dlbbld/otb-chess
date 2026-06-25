package io.github.dlbbld.otbchess.core;

import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Square;

/**
 * A single square-to-piece overlay used when deriving a {@link io.github.dlbbld.ashlarchess.bitboard.BitboardPosition}
 * from a base position (see {@link BitboardPositions}).
 *
 * <p>
 * Previously imported from ashlar-chess ({@code board.model.UpdateSquare}); that package is internal in the JPMS
 * (module) build of ashlar-chess and no longer exported. otb only ever used it as a local (square, piece) pair and
 * never handed it across the ashlar-chess API, so it is owned here instead.
 */
public record UpdateSquare(Square square, Piece piece) {
}
