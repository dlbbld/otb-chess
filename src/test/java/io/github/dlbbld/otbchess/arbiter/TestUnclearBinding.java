// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.board.enums.CastlingMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterEngine.UnclearBinding;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;

/**
 * The binding of a piece let go in an unclear placement (free placement, E-005): from then on, already before the
 * clock press, the player is bound to the legal moves that end with the piece on one of the squares it was let go
 * across. The owner's rulings Q1 to Q3 of the implementation plan are pinned here one by one.
 */
class TestUnclearBinding {

  /** King e2, rook a1: the rook reaches every square of the first rank. */
  private static final String ROOK = "4k3/8/8/8/8/8/4K3/R7 w - - 0 1";

  private final ArbiterEngine engine = new ArbiterEngine();
  private Board board;
  private ActionSequence sequence;

  private void position(String fen) {
    board = Board.fromFenStrict(fen);
    sequence = new ActionSequence(board.getSideToMove());
  }

  private static Square sq(String name) {
    return Square.parse(name);
  }

  private static List<Square> squares(String... names) {
    return List.of(names).stream().map(Square::parse).toList();
  }

  /** The piece on {@code from} is let go across the border of {@code across}. */
  private void letGoAcross(String from, Piece piece, String... across) {
    sequence.addEvent(BoardEvent.releaseUnclear(sq(from), piece, squares(across), 0));
  }

  private void move(String from, String to, Piece piece) {
    sequence.addEvent(BoardEvent.dragMove(sq(from), sq(to), piece, 0));
  }

  private Optional<UnclearBinding> binding() {
    return engine.findUnclearBinding(board, sequence);
  }

  private Set<String> boundMoves() {
    return binding().orElseThrow().moves().stream().map(TestUnclearBinding::name).collect(Collectors.toSet());
  }

  private Optional<String> finalMove() {
    return engine.findFinalMoveCommitment(board, sequence).map(commitment -> name(commitment.move()));
  }

  /** "a1-d1", or "O-O" for castling. */
  private static String name(LegalMove move) {
    final MoveSpecification spec = move.moveSpecification();
    if (spec.isCastling()) {
      return spec.castlingMove() == CastlingMove.KING_SIDE ? "O-O" : "O-O-O";
    }
    return spec.fromSquare().getName() + "-" + spec.toSquare().getName();
  }

  // ===== What binds =====

  @Test
  void aRookLetGoAcrossTwoSquaresIsBoundToBoth() {
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    final UnclearBinding binding = binding().orElseThrow();
    assertEquals(Piece.WHITE_ROOK, binding.piece());
    assertEquals(sq("a1"), binding.fromSquare());
    assertEquals(squares("d1", "e1"), binding.squares());
    assertEquals(Set.of("a1-d1", "a1-e1"), boundMoves());
    assertEquals(sq("a1"), binding.at(), "it still counts on the square it stood on");
    assertFalse(binding.isPieceOnBoundSquare());
    assertTrue(finalMove().isEmpty(), "an unclear release is no move");
    assertFalse(engine.hasReleasedPieceCommitment(board, sequence));
  }

  @Test
  void onlyTheSquaresThatGiveALegalMoveBind() {
    // Q1: with the king on e1 the rook cannot go to e1. The arbiter names d1 only.
    position("4k3/8/8/8/8/8/8/R3K3 w - - 0 1");
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    assertEquals(squares("d1"), binding().orElseThrow().squares());
    assertEquals(Set.of("a1-d1"), boundMoves());
  }

  @Test
  void aPieceThatCanReachNoneOfTheSquaresIsBoundToNothing() {
    // The bishop from c1 across c4 and d4: it can reach neither. Any legal bishop move remains.
    position("4k3/8/8/8/8/8/4K3/2B5 w - - 0 1");
    letGoAcross("c1", Piece.WHITE_BISHOP, "c4", "d4");
    assertTrue(binding().isEmpty());
    move("c1", "e3", Piece.WHITE_BISHOP);
    assertEquals(Optional.of("c1-e3"), finalMove());
  }

  @Test
  void theSquareThePieceCameFromAmongTheSquaresBindsToNothing() {
    // Q2: the arbiter cannot tell a piece put back carelessly from a move.
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "a1", "b1");
    assertTrue(binding().isEmpty());
    move("a1", "h1", Piece.WHITE_ROOK);
    assertEquals(Optional.of("a1-h1"), finalMove());
  }

  @Test
  void theFirstBindingStandsWhenThePieceIsLetGoUnclearlyAgain() {
    // Q3: bound to d1 and e1, then let go across f1 and g1.
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    letGoAcross("a1", Piece.WHITE_ROOK, "f1", "g1");
    assertEquals(squares("d1", "e1"), binding().orElseThrow().squares());
  }

  @Test
  void theFirstReleaseThatCanBindDoes() {
    // Across its own square first (bound to nothing), then across d1 and e1.
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "a1", "b1");
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    assertEquals(squares("d1", "e1"), binding().orElseThrow().squares());
  }

  @Test
  void aReleaseThatBreaksTheTouchMoveObligationInForceBindsToNothing() {
    // The king was touched first and can move: the rook let go afterwards is bound to nothing.
    position(ROOK);
    sequence.addEvent(BoardEvent.click(sq("e2"), Piece.WHITE_KING, 0));
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    assertTrue(binding().isEmpty());
  }

  @Test
  void aKingLetGoAcrossF1AndG1IsBoundToTheKingMoveAndToCastling() {
    position("4k3/8/8/8/8/8/8/4K2R w K - 0 1");
    letGoAcross("e1", Piece.WHITE_KING, "f1", "g1");
    assertEquals(squares("f1", "g1"), binding().orElseThrow().squares());
    assertEquals(Set.of("e1-f1", "O-O"), boundMoves());

    // The king placed clearly on g1 is the released king of today: castling has to be completed.
    move("e1", "g1", Piece.WHITE_KING);
    assertTrue(binding().isEmpty(), "the released-piece rule has taken over");
    assertTrue(engine.hasReleasedPieceCommitment(board, sequence));
  }

  @Test
  void aPawnLetGoAcrossSquaresOfTheLastRankIsBoundToNothing() {
    // A pawn on the last rank is half a promotion, never a release.
    position("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
    letGoAcross("a7", Piece.WHITE_PAWN, "a8", "b8");
    assertTrue(binding().isEmpty());
  }

  @Test
  void theSquareOfAnEnPassantCaptureDoesNotBind() {
    // Landing on d6 is half of the capture; e6 is an ordinary move.
    position("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
    letGoAcross("e5", Piece.WHITE_PAWN, "d6", "e6");
    assertEquals(squares("e6"), binding().orElseThrow().squares());
    assertEquals(Set.of("e5-e6"), boundMoves());
  }

  @Test
  void aCaptureOnOneOfTheSquaresBinds() {
    // The rook across d1 (empty) and e1 (a black knight): choosing e1 is the capture.
    position("4k3/8/8/8/8/8/4K3/R3n3 w - - 0 1");
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    assertEquals(squares("d1", "e1"), binding().orElseThrow().squares());
    assertEquals(Set.of("a1-d1", "a1-e1"), boundMoves());
  }

  @Test
  void anOpponentsPieceIsNeverBound() {
    position("4k3/8/8/8/8/8/4K3/R3n3 w - - 0 1");
    letGoAcross("e1", Piece.BLACK_KNIGHT, "e1", "f1");
    assertTrue(binding().isEmpty());
  }

  // ===== What the binding does =====

  @Test
  void aClearReleaseOnASquareThePieceIsNotBoundToCommitsToNothing() {
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("a1", "b1", Piece.WHITE_ROOK);
    assertTrue(finalMove().isEmpty(), "Rb1 is not made");
    assertFalse(engine.hasReleasedPieceCommitment(board, sequence), "and the rook is not released on b1");
    final UnclearBinding binding = binding().orElseThrow();
    assertEquals(sq("b1"), binding.at());
    assertFalse(binding.isPieceOnBoundSquare());

    // Taken from there to a square it is bound to: the move is made.
    move("b1", "d1", Piece.WHITE_ROOK);
    assertEquals(Optional.of("a1-d1"), finalMove());
    assertTrue(binding().orElseThrow().isPieceOnBoundSquare());
  }

  @Test
  void aMoveOfAnotherPieceCommitsToNothingWhileThePieceIsBound() {
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("e2", "e3", Piece.WHITE_KING);
    assertTrue(finalMove().isEmpty());
    assertFalse(engine.hasReleasedPieceCommitment(board, sequence));
    assertEquals(sq("a1"), binding().orElseThrow().at());
  }

  @Test
  void aClearReleaseOnABoundSquareIsTheReleasedPieceOfToday() {
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("a1", "e1", Piece.WHITE_ROOK);
    assertEquals(Optional.of("a1-e1"), finalMove());
    assertTrue(engine.hasReleasedPieceCommitment(board, sequence));
    assertTrue(binding().isEmpty());
    assertEquals(new ArbiterEngine.ReleasedPiece(Piece.WHITE_ROOK, sq("e1")),
        engine.findReleasedPiece(board, sequence).orElseThrow());
  }

  @Test
  void theBoundPieceIsFollowedOffTheBoardAndBack() {
    position(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    sequence.addEvent(BoardEvent.remove(sq("a1"), Piece.WHITE_ROOK, 0));
    assertEquals(Square.NONE, binding().orElseThrow().at());
    sequence.addEvent(BoardEvent.restoreToEmpty(sq("d1"), Piece.WHITE_ROOK, 0));
    assertEquals(sq("d1"), binding().orElseThrow().at());
    assertEquals(Optional.of("a1-d1"), finalMove());
  }

  @Test
  void withoutAnUnclearReleaseNothingChanges() {
    position(ROOK);
    sequence.addEvent(BoardEvent.click(sq("a1"), Piece.WHITE_ROOK, 0));
    assertTrue(binding().isEmpty());
    move("a1", "b1", Piece.WHITE_ROOK);
    assertEquals(Optional.of("a1-b1"), finalMove());
    assertEquals(Side.WHITE, board.getSideToMove());
  }
}
