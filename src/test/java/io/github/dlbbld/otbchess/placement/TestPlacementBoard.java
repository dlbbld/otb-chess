// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.event.BoardEventType;
import io.github.dlbbld.otbchess.placement.PlacementBoard.PlacedPiece;
import io.github.dlbbld.otbchess.placement.PlacementBoard.Release;

/**
 * The layer of free placement (E-005): what each release is for the rules, and which piece counts on which square
 * while pieces lie across borders. The position is the one after 1.e4 d5, White to move.
 */
class TestPlacementBoard {

  private static final int RADIUS = PlacementMeasure.radiusFor(20);
  private static final String AFTER_E4_D5 = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2";

  private BitboardPosition start;
  private PlacementBoard board;

  @BeforeEach
  void setUp() {
    start = Board.fromFenStrict(AFTER_E4_D5).getBitboardPosition();
    board = new PlacementBoard(RADIUS, start);
  }

  private static int x(String square) {
    return PlacementMeasure.centreOfFile(Square.parse(square));
  }

  private static int y(String square) {
    return PlacementMeasure.centreOfRank(Square.parse(square));
  }

  private int idOn(String square) {
    return board.pieces().stream().filter(piece -> piece.square() == Square.parse(square)).findFirst().orElseThrow()
        .id();
  }

  private PlacedPiece piece(int id) {
    return board.pieces().stream().filter(piece -> piece.id() == id).findFirst().orElseThrow();
  }

  private Release releaseOn(int id, String square) {
    return board.release(Side.WHITE, id, x(square), y(square));
  }

  /** On the line between two squares of a rank: the left border of the second one. */
  private Release releaseOnLeftBorderOf(int id, String square) {
    return board.release(Side.WHITE, id, x(square) - 512, y(square));
  }

  private static void assertEvent(Release release, BoardEventType type, String square, String target, Piece piece,
      Piece displaced) {
    final BoardEvent event = release.event().orElseThrow(() -> new AssertionError("no event"));
    assertEquals(type, event.type());
    assertEquals(square == null ? Square.NONE : Square.parse(square), event.square());
    assertEquals(target == null ? Square.NONE : Square.parse(target), event.targetSquare());
    assertEquals(piece, event.piece());
    assertEquals(displaced, event.displacedPiece());
  }

  @Test
  void aPositionSetUpIsCentredAndClear() {
    assertEquals(32, board.pieces().size());
    assertTrue(board.showsClearly(start));
    assertFalse(board.hasUnclearPiece());
    final PlacedPiece pawn = piece(idOn("e4"));
    assertEquals(4 * 1024 + 512, pawn.x());
    assertEquals(3 * 1024 + 512, pawn.y());
  }

  @Test
  void aPieceShiftedOnItsSquareIsATouch() {
    final int knight = idOn("g1");
    final Release release = board.release(Side.WHITE, knight, x("g1") + 200, y("g1") - 100);
    assertEvent(release, BoardEventType.CLICK, "g1", null, Piece.WHITE_KNIGHT, Piece.NONE);
    assertEquals(x("g1") + 200, piece(knight).x());
    assertTrue(board.showsClearly(start), "the position is unchanged, the piece only lies off the centre");
  }

  @Test
  void aClearReleaseOnAnotherSquareIsTheMoveOfToday() {
    final Release release = releaseOn(idOn("g1"), "f3");
    assertEvent(release, BoardEventType.DRAG_MOVE, "g1", "f3", Piece.WHITE_KNIGHT, Piece.NONE);
    assertEquals(Piece.WHITE_KNIGHT, board.countedPosition().get(Square.F3));
    assertEquals(Piece.NONE, board.countedPosition().get(Square.G1));
  }

  @Test
  void aClearReleaseOnAnOccupiedSquareCaptures() {
    final int victim = idOn("d5");
    final Release release = releaseOn(idOn("e4"), "d5");
    assertEvent(release, BoardEventType.DRAG_CAPTURE, "e4", "d5", Piece.WHITE_PAWN, Piece.BLACK_PAWN);
    assertEquals(victim, release.capturedId());
    assertFalse(board.isOnBoard(victim));
    assertEquals(31, board.pieces().size());
  }

  @Test
  void aPieceLetGoOutsideTheBoardIsRemoved() {
    final int victim = idOn("d5");
    final Release release = board.release(Side.WHITE, victim, -300, y("d5"));
    assertEvent(release, BoardEventType.REMOVE, "d5", null, Piece.BLACK_PAWN, Piece.NONE);
    assertTrue(release.offBoard());
    assertFalse(board.isOnBoard(victim));
  }

  @Test
  void aPieceFromTheSideIsPlacedLikeToday() {
    board.release(Side.WHITE, idOn("d5"), -300, 0);
    final Release onEmpty = board.placeFromSide(Side.WHITE, 40, Piece.BLACK_PAWN, x("d5"), y("d5"));
    assertEvent(onEmpty, BoardEventType.RESTORE_TO_EMPTY, null, "d5", Piece.BLACK_PAWN, Piece.NONE);
    final Release onOccupied = board.placeFromSide(Side.WHITE, 41, Piece.WHITE_QUEEN, x("d5"), y("d5"));
    assertEvent(onOccupied, BoardEventType.RESTORE_TO_OCCUPIED, null, "d5", Piece.WHITE_QUEEN, Piece.BLACK_PAWN);
    assertEquals(40, onOccupied.capturedId());
    assertThrows(IllegalArgumentException.class,
        () -> board.placeFromSide(Side.WHITE, 41, Piece.WHITE_QUEEN, x("a3"), y("a3")), "a number in use");
    assertThrows(IllegalArgumentException.class, () -> board.release(Side.WHITE, 999, 0, 0), "an unknown number");
  }

  @Test
  void anOwnPieceLetGoAcrossABorderIsTouchedAndStillCountsOnItsSquare() {
    final int knight = idOn("g1");
    final Release release = releaseOnLeftBorderOf(knight, "g3"); // across f3 and g3
    assertEvent(release, BoardEventType.CLICK, "g1", null, Piece.WHITE_KNIGHT, Piece.NONE);
    assertTrue(board.hasUnclearPiece());
    assertEquals(start, board.countedPosition(), "the rules still see the knight on g1");
    assertFalse(board.showsClearly(start));

    // Let go unclearly again: nothing new for the rules.
    assertTrue(board.release(Side.WHITE, knight, x("f3") + 400, y("f3")).event().isEmpty());

    // Placed clearly: the move from the square it counted on.
    assertEvent(releaseOn(knight, "f3"), BoardEventType.DRAG_MOVE, "g1", "f3", Piece.WHITE_KNIGHT, Piece.NONE);
    assertFalse(board.hasUnclearPiece());
  }

  @Test
  void anUnclearPiecePutBackClearlyOnItsSquareIsNoSecondTouch() {
    final int knight = idOn("g1");
    board.release(Side.WHITE, knight, x("g1") - 500, y("g1"));
    assertTrue(releaseOn(knight, "g1").event().isEmpty());
    assertTrue(board.showsClearly(start));
  }

  @Test
  void aHalfRemovedOpponentPieceIsTouchedAndStillOnItsSquare() {
    final int victim = idOn("d5");
    final Release release = releaseOnLeftBorderOf(victim, "e5"); // on the line between d5 and e5
    assertEvent(release, BoardEventType.CLICK, "d5", null, Piece.BLACK_PAWN, Piece.NONE);
    assertEquals(0, release.capturedId());
    assertEquals(32, board.pieces().size(), "both pawns are still in play");
    assertEquals(Piece.BLACK_PAWN, board.countedPosition().get(Square.D5));
    final PlacedPiece halfRemoved = board.halfRemovedPiece(Side.WHITE).orElseThrow();
    assertEquals(victim, halfRemoved.id());
    assertEquals(Square.D5, halfRemoved.origin());
    assertTrue(board.halfRemovedPiece(Side.BLACK).isEmpty(), "it is Black's own piece");
  }

  @Test
  void theHalfRemovedPieceTakenOffIsRemovedFromItsOriginalSquareOnce() {
    final int victim = idOn("d5");
    releaseOnLeftBorderOf(victim, "e5");
    final Release off = board.release(Side.WHITE, victim, x("d5"), 9000);
    assertEvent(off, BoardEventType.REMOVE, "d5", null, Piece.BLACK_PAWN, Piece.NONE);
    assertTrue(board.halfRemovedPiece(Side.WHITE).isEmpty());
    assertEvent(releaseOn(idOn("e4"), "d5"), BoardEventType.DRAG_MOVE, "e4", "d5", Piece.WHITE_PAWN, Piece.NONE);
    assertFalse(board.hasUnclearPiece());
  }

  @Test
  void theHalfRemovedPiecePutBackKeepsOnlyItsTouch() {
    final int victim = idOn("d5");
    releaseOnLeftBorderOf(victim, "e5");
    assertTrue(releaseOn(victim, "d5").event().isEmpty());
    assertTrue(board.halfRemovedPiece(Side.WHITE).isEmpty());
    assertTrue(board.showsClearly(start));
  }

  @Test
  void anOpponentPieceCarriedOffItsSquareIsAMovedPieceAlsoWhenLetGoUnclearly() {
    final int victim = idOn("d5");
    // Clear of d5: across e5 and f5.
    final Release unclear = releaseOnLeftBorderOf(victim, "f5");
    assertEvent(unclear, BoardEventType.DRAG_MOVE, "d5", "f5", Piece.BLACK_PAWN, Piece.NONE);
    assertTrue(board.halfRemovedPiece(Side.WHITE).isEmpty());
  }

  @Test
  void anOpponentPieceCarriedClearlyToAnotherSquareIsAMovedPiece() {
    assertEvent(releaseOn(idOn("d5"), "d4"), BoardEventType.DRAG_MOVE, "d5", "d4", Piece.BLACK_PAWN, Piece.NONE);
  }

  @Test
  void theCapturerPlacedBeforeTheVictimIsTakenOffKeepsBothPiecesOnTheBoard() {
    final int victim = idOn("d5");
    releaseOnLeftBorderOf(victim, "e5");
    final Release capture = releaseOn(idOn("e4"), "d5");
    assertEvent(capture, BoardEventType.DRAG_CAPTURE, "e4", "d5", Piece.WHITE_PAWN, Piece.BLACK_PAWN);
    assertEquals(0, capture.capturedId(), "the victim lies where it was let go");
    assertTrue(board.isOnBoard(victim));
    assertEquals(Square.NONE, piece(victim).square(), "for the rules it is captured");
    assertEquals(Piece.WHITE_PAWN, board.countedPosition().get(Square.D5));
    assertTrue(board.hasUnclearPiece());
    assertEquals(victim, board.halfRemovedPiece(Side.WHITE).orElseThrow().id(), "it is still to be taken off");

    // Taking it off is nothing new for the rules: its removal was the capture.
    final Release off = board.release(Side.WHITE, victim, -200, -200);
    assertTrue(off.event().isEmpty());
    assertTrue(off.offBoard());
    assertFalse(board.hasUnclearPiece());
    assertEquals(31, board.pieces().size());
  }

  @Test
  void anUnclearReleaseOverAnOccupiedSquareRemovesNothing() {
    final int pawn = idOn("e4");
    final Release release = board.release(Side.WHITE, pawn, x("d5") + 400, y("d5"));
    assertEquals(0, release.capturedId());
    assertEquals(32, board.pieces().size());
    assertEquals(pawn, board.pieces().get(31).id(), "the piece set down last lies on top");
  }

  @Test
  void aPositionSetUpAgainIsCentredAndNumberedAnew() {
    releaseOnLeftBorderOf(idOn("d5"), "e5");
    board.reset(start);
    assertTrue(board.showsClearly(start));
    assertTrue(board.halfRemovedPiece(Side.WHITE).isEmpty());
  }
}
