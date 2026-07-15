// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.touchmove;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;

class TestTouchMoveEvaluator {

  @Test
  void testTouchOwnPieceWithLegalMoves() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player clicks the knight on g1 — it has legal moves (Nf3, Nh3)
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.G1, obligation.get().square());
    assertEquals(Piece.WHITE_KNIGHT, obligation.get().piece());
  }

  @Test
  void testTouchOwnPieceWithoutLegalMoves() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player clicks the rook on a1 — in starting position it has no legal moves
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertFalse(obligation.isPresent());
  }

  @Test
  void testTouchOpponentCapturablePiece() {
    final Board board = new Board();
    // Play 1.e4 d5 so the black pawn on d5 can be captured by exd5
    board.moveStrict("e4");
    board.moveStrict("d5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player clicks the opponent pawn on d5 — it can be captured
    sequence.addEvent(BoardEvent.click(Square.D5, Piece.BLACK_PAWN, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OPPONENT_PIECE, obligation.get().type());
    assertEquals(Square.D5, obligation.get().square());
  }

  @Test
  void testTouchOpponentNonCapturablePiece() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player clicks the opponent pawn on a7 — no white piece can capture it in starting position
    sequence.addEvent(BoardEvent.click(Square.A7, Piece.BLACK_PAWN, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertFalse(obligation.isPresent());
  }

  @Test
  void testFirstObligationWins() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player touches the knight on g1 first (has legal moves), then the knight on b1
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.click(Square.B1, Piece.WHITE_KNIGHT, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(Square.G1, obligation.get().square());
  }

  @Test
  void testSkipNonObligatoryThenFindObligation() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player touches the rook on a1 (no legal moves), then the knight on g1 (has legal moves)
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(Square.G1, obligation.get().square());
    assertEquals(Piece.WHITE_KNIGHT, obligation.get().piece());
    // The binding piece was reached only after touching the a1 rook (which has no legal moves), so
    // it is flagged as not literally the first piece touched.
    assertTrue(obligation.get().precededByUnmovableOwnTouch());
  }

  @Test
  void testFirstTouchHasLegalMovesIsNotFlaggedAsPreceded() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Knight g1 (has legal moves) is the very first piece touched, then knight b1 (also has moves).
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.click(Square.B1, Piece.WHITE_KNIGHT, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(Square.G1, obligation.get().square());
    assertFalse(obligation.get().precededByUnmovableOwnTouch());
  }

  @Test
  void testDragMoveCreatesObligation() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player drags the pawn from e2 to e4
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.E2, obligation.get().square());
  }

  @Test
  void testRestoreEventDoesNotCreateObligation() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // Player restores a piece from side area — no touch-move
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.E4, Piece.BLACK_PAWN, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertFalse(obligation.isPresent());
  }

  @Test
  void testEmptySequence() {
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertFalse(obligation.isPresent());
  }

  @Test
  void testSatisfiesObligationOwnPiece() {
    final Board board = new Board();

    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OWN_PIECE, Square.G1,
        Piece.WHITE_KNIGHT);

    // Find the Nf3 legal move
    final var nf3 = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.G1 && m.moveSpecification().toSquare() == Square.F3)
        .findFirst().orElseThrow();

    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation, nf3));
  }

  @Test
  void testDoesNotSatisfyObligationOwnPieceDifferentPiece() {
    final Board board = new Board();

    // Obligation: must move knight on g1
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OWN_PIECE, Square.G1,
        Piece.WHITE_KNIGHT);

    // Find the e4 pawn move (different piece)
    final var e4 = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.E2 && m.moveSpecification().toSquare() == Square.E4)
        .findFirst().orElseThrow();

    assertFalse(TouchMoveEvaluator.satisfiesObligation(obligation, e4));
  }

  @Test
  void testSatisfiesObligationOpponentPiece() {
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("d5");

    // Obligation: must capture the pawn on d5
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OPPONENT_PIECE, Square.D5,
        Piece.BLACK_PAWN);

    // Find exd5
    final var exd5 = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.E4 && m.moveSpecification().toSquare() == Square.D5)
        .findFirst().orElseThrow();

    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation, exd5));
  }

  @Test
  void testEnPassantSatisfiesOpponentPieceObligationOnCapturedPawnSquare() {
    final Board board = Board.fromFenStrict("4k3/8/8/1pP5/3N4/8/8/4K3 w - b6 0 1");
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OPPONENT_PIECE, Square.B5,
        Piece.BLACK_PAWN);

    final var cxb6ep = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.C5 && m.moveSpecification().toSquare() == Square.B6)
        .findFirst().orElseThrow();

    assertTrue(cxb6ep.isEnPassant());
    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation, cxb6ep));
  }

  @Test
  void testTouchOwnThenCapturableOpponentBindsSpecificCapture() {
    // FIDE 4.3.3: after 1.e4 d5, touching the white pawn on e4 and then the black pawn on d5 (which
    // the e4 pawn can capture) binds the specific capture exd5.
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("d5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.E4, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.click(Square.D5, Piece.BLACK_PAWN, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.SPECIFIC_CAPTURE, obligation.get().type());
    assertEquals(Square.E4, obligation.get().square());
    assertEquals(Square.D5, obligation.get().toSquare());

    final var exd5 = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.E4 && m.moveSpecification().toSquare() == Square.D5)
        .findFirst().orElseThrow();
    final var e5 = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.E4 && m.moveSpecification().toSquare() == Square.E5)
        .findFirst().orElseThrow();

    // Only the specific capture satisfies it; another legal move of the touched pawn does not.
    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation.get(), exd5));
    assertFalse(TouchMoveEvaluator.satisfiesObligation(obligation.get(), e5));
  }

  @Test
  void testTouchOpponentThenOwnFallsBackToOpponentPieceObligation() {
    final Board board = Board.fromFenStrict("4k3/8/1r6/8/8/1R6/8/4K3 w - - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.remove(Square.B6, Piece.BLACK_ROOK, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.B3, Square.B5, Piece.WHITE_ROOK, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.SPECIFIC_CAPTURE, obligation.get().type());
    assertEquals(Square.B3, obligation.get().square());
    assertEquals(Piece.WHITE_ROOK, obligation.get().piece());
    assertEquals(Square.B6, obligation.get().toSquare());
    assertEquals(Piece.BLACK_ROOK, obligation.get().capturedPiece());
    assertTrue(obligation.get().opponentTouchedFirst());
  }

  @Test
  void testOpponentFirstIgnoresEarlierNonCapturingOwnTouchWhenLaterOwnPieceCanCapture() {
    final Board board = Board.fromFenStrict("4k3/8/8/8/8/1n6/PPP1P3/4K3 w - - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.B3, Piece.BLACK_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    sequence.addEvent(BoardEvent.click(Square.C2, Piece.WHITE_PAWN, 2));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.SPECIFIC_CAPTURE, obligation.get().type());
    assertEquals(Square.C2, obligation.get().square());
    assertEquals(Piece.WHITE_PAWN, obligation.get().piece());
    assertEquals(Square.B3, obligation.get().toSquare());
    assertEquals(Piece.BLACK_KNIGHT, obligation.get().capturedPiece());
    assertTrue(obligation.get().opponentTouchedFirst());
  }

  @Test
  void testTouchOwnThenOpponentBindsSpecificEnPassantCapture() {
    final Board board = Board.fromFenStrict("4k3/8/8/1pP5/3N4/8/8/4K3 w - b6 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.C5, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.click(Square.B5, Piece.BLACK_PAWN, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.SPECIFIC_CAPTURE, obligation.get().type());
    assertEquals(Square.C5, obligation.get().square());
    assertEquals(Square.B5, obligation.get().toSquare());

    final var cxb6ep = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.C5 && m.moveSpecification().toSquare() == Square.B6)
        .findFirst().orElseThrow();

    assertTrue(cxb6ep.isEnPassant());
    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation.get(), cxb6ep));
  }

  @Test
  void testTouchOwnThenOpponentItCannotCaptureFallsBackToOwnPiece() {
    // FIDE 4.3.3 fallback: if the touched own piece cannot capture the touched opponent piece, the
    // obligation is just to move the first touched piece. The g1 knight cannot capture the d5 pawn.
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("d5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.click(Square.D5, Piece.BLACK_PAWN, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.G1, obligation.get().square());
  }

  @Test
  void testDoesNotSatisfyObligationOpponentPieceDifferentMove() {
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("d5");

    // Obligation: must capture the pawn on d5
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OPPONENT_PIECE, Square.D5,
        Piece.BLACK_PAWN);

    // Find Nf3 (does not capture on d5)
    final var nf3 = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().fromSquare() == Square.G1 && m.moveSpecification().toSquare() == Square.F3)
        .findFirst().orElseThrow();

    assertFalse(TouchMoveEvaluator.satisfiesObligation(obligation, nf3));
  }

  @Test
  void testCastlingSatisfiesKingTouchObligation() {
    final Board board = new Board();
    // Set up kingside castling for white
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");

    // Obligation: must move the king (touched on e1)
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.OWN_PIECE, Square.E1,
        Piece.WHITE_KING);

    // Find the castling move
    final var castling = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().castlingMove() != io.github.dlbbld.ashlarchess.board.enums.CastlingMove.NONE)
        .findFirst().orElseThrow();

    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation, castling));
  }

  @Test
  void testTouchKingCreatesObligationWhenCastlingAvailable() {
    final Board board = new Board();
    // Set up kingside castling for white
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.E1, Piece.WHITE_KING, 0));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.E1, obligation.get().square());
  }

  @Test
  void testFailedCastlingAttemptWithNoKingMovesDoesNotBindRook() {
    final Board board = Board.fromFenStrict("k4r2/8/8/8/8/8/P2PP3/3QK2R w K - 0 1");
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // The king has no legal move. Under FIDE 4.4.3/4.7.2, the failed castling attempt does
    // not bind the rook; the player is free to make any legal move.
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.F1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.G1, Piece.WHITE_ROOK, 1));

    Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertFalse(obligation.isPresent());

    sequence.addEvent(BoardEvent.dragMove(Square.A2, Square.A3, Piece.WHITE_PAWN, 2));
    obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.A2, obligation.get().square());
  }

  // ---- King-then-rook combined touch (FIDE 4.4.a) ----

  /**
   * Sets up a position where White has both castling rights and the path is clear for kingside castling. Used by the
   * CASTLING-obligation tests below.
   */
  private static Board whiteKingsideClearBoard() {
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");
    return board;
  }

  @Test
  void testKingThenRookClickEstablishesCastlingObligation() {
    final Board board = whiteKingsideClearBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // White touches the king on e1, then the kingside rook on h1; kingside castling is legal.
    sequence.addEvent(BoardEvent.click(Square.E1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.click(Square.H1, Piece.WHITE_ROOK, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.CASTLING, obligation.get().type());
    assertEquals(io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE, obligation.get().castlingMove());
    assertEquals(Square.E1, obligation.get().square());
    assertEquals(Piece.WHITE_KING, obligation.get().piece());
  }

  @Test
  void testKingThenRookDragsEstablishCastlingObligation() {
    final Board board = whiteKingsideClearBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // The drag-pickup of the king on e1 is a touch on e1; same for the rook on h1.
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.CASTLING, obligation.get().type());
    assertEquals(io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE, obligation.get().castlingMove());
  }

  @Test
  void testRookThenKingDoesNotEstablishCastlingObligation() {
    final Board board = whiteKingsideClearBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // R first, then K — the new rule requires K-then-R order. Falls back to the existing
    // first-obligation rule. In this position the h1 rook has legal moves (g1 and f1 are
    // empty after Nf3 / Be2), so the rook-touch carries the OWN_PIECE obligation on h1.
    // The point of this test is to verify the CASTLING obligation did NOT fire.
    sequence.addEvent(BoardEvent.click(Square.H1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.click(Square.E1, Piece.WHITE_KING, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.H1, obligation.get().square());
  }

  @Test
  void testRookClickThenCastlingMotionIsRookObligationNotCastling() {
    // FIDE 4.4.2: the player clicks the h1 rook first, then performs the castling motion
    // (king e1->g1, rook h1->f1). Touching the rook first forbids castling; the rook has legal
    // moves, so the obligation is OWN_PIECE on h1 and the castling move must NOT satisfy it.
    final Board board = whiteKingsideClearBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.H1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 1));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 2));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.H1, obligation.get().square());

    final var castling = board.getLegalMoves().stream().filter(
        m -> m.moveSpecification().castlingMove() == io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE)
        .findFirst().orElseThrow();
    assertFalse(TouchMoveEvaluator.satisfiesObligation(obligation.get(), castling));
  }

  @Test
  void testCastlingMoveSatisfiesCastlingObligation() {
    final Board board = whiteKingsideClearBoard();
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.CASTLING, Square.E1, Piece.WHITE_KING,
        io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE);

    final var castling = board.getLegalMoves().stream().filter(
        m -> m.moveSpecification().castlingMove() == io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE)
        .findFirst().orElseThrow();

    assertTrue(TouchMoveEvaluator.satisfiesObligation(obligation, castling));
  }

  @Test
  void testNonCastlingKingMoveDoesNotSatisfyCastlingObligation() {
    final Board board = whiteKingsideClearBoard();
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.CASTLING, Square.E1, Piece.WHITE_KING,
        io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE);

    // A plain king move (e1->f1 if legal — but with bishop gone it should be) doesn't satisfy
    // the castling obligation. Fall back to a search to find any non-castling king move.
    final var plainKingMove = board.getLegalMoves().stream()
        .filter(m -> m.moveSpecification().castlingMove() == io.github.dlbbld.ashlarchess.board.enums.CastlingMove.NONE)
        .filter(m -> m.moveSpecification().fromSquare() == Square.E1).findFirst().orElseThrow();

    assertFalse(TouchMoveEvaluator.satisfiesObligation(obligation, plainKingMove));
  }

  @Test
  void testCastlingObligationOtherSideMoveRejected() {
    // White: king on e1, both rooks on starting squares, no pieces in the way for either side.
    final Board board = Board.fromFenStrict("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
    final TouchMoveObligation obligation = new TouchMoveObligation(TouchMoveType.CASTLING, Square.E1, Piece.WHITE_KING,
        io.github.dlbbld.ashlarchess.board.enums.CastlingMove.KING_SIDE);

    // Queenside castling is legal in this position but does NOT satisfy a kingside obligation.
    final var queensideCastling = board.getLegalMoves().stream().filter(
        m -> m.moveSpecification().castlingMove() == io.github.dlbbld.ashlarchess.board.enums.CastlingMove.QUEEN_SIDE)
        .findFirst().orElseThrow();

    assertFalse(TouchMoveEvaluator.satisfiesObligation(obligation, queensideCastling));
  }

  @Test
  void testKingThenRookOnIllegalSideDoesNotEstablishCastlingObligation() {
    // White can castle kingside only — queenside path is blocked (queen on d1 in the standard
    // opening setup). Touching K then queenside rook should NOT establish the CASTLING
    // obligation; it falls back to existing rules.
    final Board board = whiteKingsideClearBoard();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    sequence.addEvent(BoardEvent.click(Square.E1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    // Falls back: king touch establishes OWN_PIECE on e1 (king has legal moves including O-O).
    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.OWN_PIECE, obligation.get().type());
    assertEquals(Square.E1, obligation.get().square());
  }

  @Test
  void testTouchedRookDeterminesSideWhenBothLegal() {
    // Both castling sides legal for White. Touching K then queenside rook a1 → must castle queenside.
    final Board board = Board.fromFenStrict("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    sequence.addEvent(BoardEvent.click(Square.E1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 1));

    final Optional<TouchMoveObligation> obligation = TouchMoveEvaluator.findObligation(sequence, board);

    assertTrue(obligation.isPresent());
    assertEquals(TouchMoveType.CASTLING, obligation.get().type());
    assertEquals(io.github.dlbbld.ashlarchess.board.enums.CastlingMove.QUEEN_SIDE, obligation.get().castlingMove());
  }
}
