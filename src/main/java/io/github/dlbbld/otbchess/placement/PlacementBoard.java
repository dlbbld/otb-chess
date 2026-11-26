// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.core.UpdateSquare;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.placement.PlacementMeasure.Kind;
import io.github.dlbbld.otbchess.placement.PlacementMeasure.Measured;

/**
 * The pieces of a game with free placement (E-005), exactly where they lie, and the layer that turns each release
 * into an event the rules know.
 *
 * <p>
 * Every piece on the board has a number, a placement and the square it <em>counts on</em> for the rules:
 * <ul>
 * <li>A piece in a clear placement counts on the square it lies on.</li>
 * <li>A piece let go in an unclear placement keeps counting on the square it stood on before, as a piece that was
 * touched. It is "still on the board" there until it is placed clearly or taken off. So the rules never see a
 * square as empty only because its piece lies across a border. The event of an own piece also carries the squares
 * it was let go across; what they bind the player to is decided by the rules.</li>
 * <li>A piece counts on no square when it came from the side and was let go unclearly, or when another piece was
 * placed clearly on the square it counted on - for the rules that was its capture, although it still lies there.</li>
 * </ul>
 * The position the rules look at is {@link #countedPosition()}. It must not be ruled on while a piece lies unclear
 * ({@link #hasUnclearPiece()}): the caller checks that first.
 */
public final class PlacementBoard {

  /** The largest number a piece from the side may bring; far above what a game uses. */
  public static final int MAX_PIECE_ID = 100_000;
  /** The width of the board in units; a release far outside it is no release of a hand. */
  public static final int MAX_COORDINATE = PlacementMeasure.BOARD_UNITS;

  /**
   * A piece on the board.
   *
   * @param id     its number
   * @param square the square it counts on for the rules, {@link Square#NONE} for none
   * @param origin the square it last stood on in a clear placement, {@link Square#NONE} for a piece that came from
   *               the side and has not stood clearly since. A piece that lies unclear keeps it until it is placed
   *               clearly or taken off, also when it no longer counts on that square.
   * @param clear  whether it lies in a clear placement
   * @param liesOn the squares its inner circle lies on: its square for a clear placement, up to four otherwise
   */
  public record PlacedPiece(int id, Piece piece, int x, int y, Square square, Square origin, boolean clear,
      List<Square> liesOn) {
  }

  /**
   * What a release did.
   *
   * @param event      the event for the rules; empty when the release is none for them
   * @param id         the number of the piece
   * @param offBoard   the piece left the board
   * @param capturedId the number of the piece that this release put to the side, or 0
   */
  public record Release(Optional<BoardEvent> event, int id, Piece piece, int x, int y, boolean offBoard,
      int capturedId) {
  }

  private static final class Entry {
    private final int id;
    private final Piece piece;
    private int x;
    private int y;
    private Square square;
    private Square origin;
    private boolean clear;

    private Entry(int id, Piece piece, int x, int y, Square square, boolean clear) {
      this.id = id;
      this.piece = piece;
      this.x = x;
      this.y = y;
      this.square = square;
      this.origin = square;
      this.clear = clear;
    }

    private PlacedPiece view(int radius) {
      return new PlacedPiece(id, piece, x, y, square, origin, clear,
          PlacementMeasure.measure(x, y, radius).squares());
    }
  }

  private final int radius;
  // In the order the pieces were set down: a later piece is drawn over an earlier one.
  private final List<Entry> entries = new ArrayList<>();

  public PlacementBoard(int radius, BitboardPosition position) {
    this.radius = radius;
    reset(position);
  }

  public int radius() {
    return radius;
  }

  /** Sets up a position the arbiter gives: every piece centred on its square, numbered anew. */
  public synchronized void reset(BitboardPosition position) {
    entries.clear();
    int id = 1;
    for (final Square square : Square.REAL) {
      final Piece piece = position.get(square);
      if (piece != Piece.NONE) {
        entries.add(new Entry(id++, piece, PlacementMeasure.centreOfFile(square),
            PlacementMeasure.centreOfRank(square), square, true));
      }
    }
  }

  /** @return the pieces on the board, the one drawn on top last */
  public synchronized List<PlacedPiece> pieces() {
    return entries.stream().map(entry -> entry.view(radius)).toList();
  }

  /** @return the position the rules look at: every piece on the square it counts on */
  public synchronized BitboardPosition countedPosition() {
    final List<UpdateSquare> updates = new ArrayList<>();
    for (final Entry entry : entries) {
      if (entry.square != Square.NONE) {
        updates.add(new UpdateSquare(entry.square, entry.piece));
      }
    }
    return updates.isEmpty() ? BitboardPosition.EMPTY_POSITION
        : BitboardPositions.withUpdates(BitboardPosition.EMPTY_POSITION, updates);
  }

  public synchronized boolean hasUnclearPiece() {
    return entries.stream().anyMatch(entry -> !entry.clear);
  }

  public synchronized List<PlacedPiece> unclearPieces() {
    return entries.stream().filter(entry -> !entry.clear).map(entry -> entry.view(radius)).toList();
  }

  /** @return whether the pieces stand, all in clear placements, exactly as in this position */
  public synchronized boolean showsClearly(BitboardPosition position) {
    return !hasUnclearPiece() && countedPosition().equals(position);
  }

  /**
   * An opponent's piece that the player to move set down halfway through taking it: it lies unclear with its inner
   * circle still on the square it stood on. It stays one when the capturing piece is placed on that square before
   * it is taken off.
   */
  public synchronized Optional<PlacedPiece> halfRemovedPiece(Side mover) {
    return entries.stream().filter(entry -> !entry.clear && entry.origin != Square.NONE
        && entry.piece.getSide() != mover
        && PlacementMeasure.measure(entry.x, entry.y, radius).liesOn(entry.origin)).map(entry -> entry.view(radius))
        .findFirst();
  }

  public synchronized boolean isOnBoard(int id) {
    return find(id) != null;
  }

  /**
   * The player to move let go of a piece of the board.
   *
   * @throws IllegalArgumentException when no piece on the board has this number
   */
  public synchronized Release release(Side mover, int id, int x, int y) {
    final Entry entry = find(id);
    if (entry == null) {
      throw new IllegalArgumentException("no piece " + id + " on the board");
    }
    return settle(mover, entry, x, y);
  }

  /**
   * The player to move let go of a piece from the side over the board. The page gives it the next free number.
   *
   * @throws IllegalArgumentException when the number is in use or out of range
   */
  public synchronized Release placeFromSide(Side mover, int id, Piece piece, int x, int y) {
    if (id < 1 || id > MAX_PIECE_ID || find(id) != null || piece == Piece.NONE) {
      throw new IllegalArgumentException("piece number " + id + " cannot be used");
    }
    final Entry entry = new Entry(id, piece, x, y, Square.NONE, false);
    entries.add(entry);
    return settle(mover, entry, x, y);
  }

  private Release settle(Side mover, Entry entry, int x, int y) {
    final long now = System.currentTimeMillis();
    final Square from = entry.square;
    final boolean wasClear = entry.clear;
    final Piece piece = entry.piece;
    final Measured measured = PlacementMeasure.measure(x, y, radius);

    if (measured.kind() == Kind.OFF_BOARD) {
      entries.remove(entry);
      // A piece that counted on no square was off the board for the rules already.
      final Optional<BoardEvent> event = from == Square.NONE ? Optional.empty()
          : Optional.of(BoardEvent.remove(from, piece, now));
      return new Release(event, entry.id, piece, x, y, true, 0);
    }

    // The piece set down last lies on top.
    entries.remove(entry);
    entries.add(entry);
    entry.x = x;
    entry.y = y;

    if (measured.kind() == Kind.UNCLEAR) {
      entry.clear = false;
      if (from == Square.NONE) {
        return new Release(Optional.empty(), entry.id, piece, x, y, false, 0);
      }
      if (piece.getSide() != mover && !measured.liesOn(from)) {
        // An opponent's piece carried off its square is a moved piece, wherever it was let go.
        entry.square = Square.NONE;
        return new Release(Optional.of(BoardEvent.dragMove(from, measured.square(), piece, now)), entry.id, piece, x,
            y, false, 0);
      }
      // It keeps counting on its square. An own piece is touched and says where it was let go, every time: the
      // first such release that can bind the player does (E-005). Lifting an opponent's piece was a touch, once.
      final Optional<BoardEvent> event;
      if (piece.getSide() == mover) {
        event = Optional.of(BoardEvent.releaseUnclear(from, piece, measured.squares(), now));
      } else {
        event = wasClear ? Optional.of(BoardEvent.click(from, piece, now)) : Optional.empty();
      }
      return new Release(event, entry.id, piece, x, y, false, 0);
    }

    final Square target = measured.square();
    entry.clear = true;
    entry.origin = target;
    if (from == target) {
      // Back on its own square: a touch if it stood there clearly, nothing new if it lay unclear.
      final Optional<BoardEvent> event = wasClear ? Optional.of(BoardEvent.click(from, piece, now))
          : Optional.empty();
      return new Release(event, entry.id, piece, x, y, false, 0);
    }

    // The piece that counts on the target square, if any. A clear one leaves the board as today; one that lies
    // unclear stays where it lies and counts on no square from now on.
    final Entry occupant = entries.stream().filter(other -> other != entry && other.square == target).findFirst()
        .orElse(null);
    int capturedId = 0;
    if (occupant != null) {
      if (occupant.clear) {
        entries.remove(occupant);
        capturedId = occupant.id;
      } else {
        occupant.square = Square.NONE;
      }
    }
    entry.square = target;
    final Piece displaced = occupant == null ? Piece.NONE : occupant.piece;
    final BoardEvent event;
    if (from == Square.NONE) {
      event = occupant == null ? BoardEvent.restoreToEmpty(target, piece, now)
          : BoardEvent.restoreToOccupied(target, piece, displaced, now);
    } else {
      event = occupant == null ? BoardEvent.dragMove(from, target, piece, now)
          : BoardEvent.dragCapture(from, target, piece, displaced, now);
    }
    return new Release(Optional.of(event), entry.id, piece, x, y, false, capturedId);
  }

  private Entry find(int id) {
    return entries.stream().filter(entry -> entry.id == id).findFirst().orElse(null);
  }
}
