// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.arbiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.message.MessageKey;
import io.github.dlbbld.otbchess.message.Messages;

class TestArbiterEngine {

  @Test
  void testValidSimpleMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player drags pawn e2 to e4 and presses clock
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertTrue(response.acceptedMove().isPresent());
    assertEquals(Square.E2, response.acceptedMove().get().moveSpecification().fromSquare());
    assertEquals(Square.E4, response.acceptedMove().get().moveSpecification().toSquare());
  }

  @Test
  void testIllegalMoveNoMatchingPosition() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player puts knight on an impossible square
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, response.playerMessageKey());
    assertEquals(1, response.illegalMoveDetail().get().count());
    assertEquals(Side.WHITE, response.illegalMoveDetail().get().side());
    assertEquals("the knight cannot move in this way", response.illegalMoveDetail().get().playerReason().get());
    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testIllegalMoveWithoutSimpleAttemptKeepsGenericMessage() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, response.playerMessageKey());
    assertEquals(1, response.illegalMoveDetail().get().count());
    assertTrue(response.illegalMoveDetail().get().playerReason().isEmpty());
  }

  @Test
  void testReleasedPieceViolationAfterLegalPawnRelease() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E3, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E3, Square.E4, Piece.WHITE_PAWN, 1));

    final BitboardPosition afterE4 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterE4, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_PLAYER, response.playerMessageKey());
    assertEquals(Piece.WHITE_PAWN, response.releasedPieceContext().get().piece());
    assertEquals(Square.E3, response.releasedPieceContext().get().square());
    assertTrue(response.restorePosition().isPresent());
    assertEquals(BitboardPositions.from(board.getBitboardPosition()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_PAWN).build(), response.restorePosition().get());
  }

  /** FIDE 4.7: putting the piece back to the original square does not undo a committed release. */
  @Test
  void testReleasedPieceViolationWhenDraggedBackToOrigin() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // White releases pawn on e3 (legal commit), then drags the pawn back to e2.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E3, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E3, Square.E2, Piece.WHITE_PAWN, 1));

    // Final physical position equals the position before the turn — no move on the board.
    final BitboardPosition afterOriginal = board.getBitboardPosition();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterOriginal, sequence);

    // The committed e3 release still binds; the player must restore to the e3 state.
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.restorePosition().isPresent());
    assertEquals(BitboardPositions.from(board.getBitboardPosition()).createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_PAWN).build(), response.restorePosition().get());
    // No illegal-move counter increment for the procedural violation.
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  /**
   * When two pieces of the same kind could have legally reached the release square, "the knight on e4" would not say
   * WHICH knight was released — the message must name the origin ("the knight from c3 on e4"). SAN-style
   * disambiguation, applied only when ambiguous.
   */
  @Test
  void testReleasedPieceViolationNamesOriginWhenAmbiguous() {
    final ArbiterEngine engine = new ArbiterEngine();
    // White knights on c3 and g5 — BOTH can reach e4.
    final Board board = Board.fromFenStrict("4k3/8/8/6N1/8/2N5/8/4K3 w - - 0 1");

    // White releases the c3 knight on e4 (legal commit), takes it back, and puts the OTHER knight
    // (from g5) on e4 instead.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.C3, Square.E4, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E4, Square.C3, Piece.WHITE_KNIGHT, 1));
    sequence.addEvent(BoardEvent.dragMove(Square.G5, Square.E4, Piece.WHITE_KNIGHT, 2));

    final BitboardPosition wrongKnightOnE4 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G5, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, wrongKnightOnE4, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_FROM_PLAYER, response.playerMessageKey());
    assertEquals(Square.C3, response.releasedPieceContext().get().fromSquare());
    assertTrue(response.renderedPlayerMessage().contains("released the knight from c3 on e4"));
    // The restore instruction keeps naming only the release square.
    assertTrue(response.renderedPlayerMessage().contains("put the knight back on e4"));
    assertEquals(Messages.get(MessageKey.ARBITER_RELEASED_PIECE_FROM_OPPONENT, "knight", "c3", "e4"),
        response.renderedOpponentMessage().get());
    // Restore target: the position with the c3 knight on e4.
    assertEquals(BitboardPositions.from(board.getBitboardPosition()).createChangedPosition(Square.C3, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_KNIGHT).build(), response.restorePosition().get());
  }

  /** An unambiguous release (only one such piece can reach the square) keeps the plain message without the origin. */
  @Test
  void testReleasedPieceViolationOmitsOriginWhenUnambiguous() {
    final ArbiterEngine engine = new ArbiterEngine();
    // Only ONE white knight can reach e4.
    final Board board = Board.fromFenStrict("4k3/8/8/8/8/2N5/8/4K3 w - - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.C3, Square.E4, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E4, Square.D5, Piece.WHITE_KNIGHT, 1));

    final BitboardPosition knightOnD5 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.C3, Piece.NONE).createChangedPosition(Square.D5, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, knightOnD5, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_PLAYER, response.playerMessageKey());
    assertTrue(response.renderedPlayerMessage().contains("released the knight on e4"));
  }

  private static final String PROMOTION_CAPTURE_FEN = "r3k3/1P6/8/8/8/8/8/4K3 w - - 0 1"; // bxa8 promotes

  @Test
  void testPromotionCompletedByPlacingQueenIsAccepted() {
    // bxa8: the pawn lands on a8 (incomplete), is lifted, and a queen is placed on a8 — promotion
    // complete. The pawn-on-a8 step must not be treated as a released-piece commitment.
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict(PROMOTION_CAPTURE_FEN);

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragCapture(Square.B7, Square.A8, Piece.WHITE_PAWN, Piece.BLACK_ROOK, 0));
    sequence.addEvent(BoardEvent.remove(Square.A8, Piece.WHITE_PAWN, 1));
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.A8, Piece.WHITE_QUEEN, 2));

    final BitboardPosition queenOnA8 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE).createChangedPosition(Square.A8, Piece.WHITE_QUEEN).build();

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, engine.evaluateClockPress(board, queenOnA8, sequence).type());
  }

  @Test
  void testPromotionReleasedPieceLocksOnPromotedPieceNotPawn() {
    // After the queen is released on a8 (bxa8=Q completed), swapping it back for the pawn is a
    // released-piece violation naming the QUEEN (not the pawn) — and not an illegal move.
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict(PROMOTION_CAPTURE_FEN);

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragCapture(Square.B7, Square.A8, Piece.WHITE_PAWN, Piece.BLACK_ROOK, 0));
    sequence.addEvent(BoardEvent.remove(Square.A8, Piece.WHITE_PAWN, 1));
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.A8, Piece.WHITE_QUEEN, 2)); // completes bxa8=Q
    sequence.addEvent(BoardEvent.remove(Square.A8, Piece.WHITE_QUEEN, 3));
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.A8, Piece.WHITE_PAWN, 4)); // pawn back on a8

    final BitboardPosition pawnOnA8 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE).createChangedPosition(Square.A8, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, pawnOnA8, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(Piece.WHITE_QUEEN, response.releasedPieceContext().get().piece());
    assertEquals(Square.A8, response.releasedPieceContext().get().square());
    assertEquals(BitboardPositions.from(board.getBitboardPosition()).createChangedPosition(Square.B7, Piece.NONE)
        .createChangedPosition(Square.A8, Piece.WHITE_QUEEN).build(), response.restorePosition().get());
  }

  @Test
  void testPromotionReleasedPieceCannotBeChangedToAnotherPiece() {
    // After bxa8=Q is completed, switching the queen for a knight is NOT a new promotion (bxa8=N)
    // and NOT an illegal move — it is a released-piece violation: the queen is committed.
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict(PROMOTION_CAPTURE_FEN);

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragCapture(Square.B7, Square.A8, Piece.WHITE_PAWN, Piece.BLACK_ROOK, 0));
    sequence.addEvent(BoardEvent.remove(Square.A8, Piece.WHITE_PAWN, 1));
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.A8, Piece.WHITE_QUEEN, 2)); // completes bxa8=Q
    sequence.addEvent(BoardEvent.remove(Square.A8, Piece.WHITE_QUEEN, 3));
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.A8, Piece.WHITE_KNIGHT, 4)); // try to switch to a knight

    final BitboardPosition knightOnA8 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B7, Piece.NONE).createChangedPosition(Square.A8, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, knightOnA8, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(Piece.WHITE_QUEEN, response.releasedPieceContext().get().piece());
    assertEquals(Square.A8, response.releasedPieceContext().get().square());
  }

  private static final String BLACK_PROMOTION_FEN = "r3k3/8/8/8/8/8/1p6/R3K3 b - - 0 1"; // b2xa1 promotes

  /**
   * Reported bug: Black plays b2xa1 (pawn parked on a1, promotion pending), then drags the a8 rook onto a1 and
   * presses the clock. That is neither a promotion completion (the promoted piece must come from the side area) nor
   * Ra8xa1 (the "capture" took Black's OWN pawn) — it is a plain illegal move. It must NOT create a released-piece
   * commitment: that demanded a "restore" to the tampered position itself, and Revert + clock-press looped forever.
   */
  @Test
  void testDraggedBoardPieceOntoPromotionSquareIsIllegalMoveNotReleasedPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict(BLACK_PROMOTION_FEN);

    final ActionSequence sequence = new ActionSequence(Side.BLACK);
    sequence.addEvent(BoardEvent.dragCapture(Square.B2, Square.A1, Piece.BLACK_PAWN, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.dragCapture(Square.A8, Square.A1, Piece.BLACK_ROOK, Piece.BLACK_PAWN, 1));

    final BitboardPosition after = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B2, Piece.NONE).createChangedPosition(Square.A8, Piece.NONE)
        .createChangedPosition(Square.A1, Piece.BLACK_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, after, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertTrue(response.releasedPieceContext().isEmpty());
  }

  /**
   * The legitimate completion of the same promotion stays accepted: pawn captures a1, the pawn is removed, and the
   * ROOK arrives from the side area (a RESTORE event — no source square). b2xa1=R.
   */
  @Test
  void testPromotionCompletedFromSideAreaOnA1IsAccepted() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict(BLACK_PROMOTION_FEN);

    final ActionSequence sequence = new ActionSequence(Side.BLACK);
    sequence.addEvent(BoardEvent.dragCapture(Square.B2, Square.A1, Piece.BLACK_PAWN, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.remove(Square.A1, Piece.BLACK_PAWN, 1));
    sequence.addEvent(BoardEvent.restoreToEmpty(Square.A1, Piece.BLACK_ROOK, 2));

    final BitboardPosition after = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B2, Piece.NONE).createChangedPosition(Square.A1, Piece.BLACK_ROOK).build();

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, engine.evaluateClockPress(board, after, sequence).type());
  }

  /**
   * Guard: without any tampering, a plain capture release on the same square still binds — the destination check
   * must not weaken normal released-piece commitments.
   */
  @Test
  void testUntamperedRookCaptureReleaseStillBinds() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict(BLACK_PROMOTION_FEN);

    // Black releases Ra8xa1 (legal), then drags the rook onward to a4.
    final ActionSequence sequence = new ActionSequence(Side.BLACK);
    sequence.addEvent(BoardEvent.dragCapture(Square.A8, Square.A1, Piece.BLACK_ROOK, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.A1, Square.A4, Piece.BLACK_ROOK, 1));

    final BitboardPosition after = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.A8, Piece.NONE).createChangedPosition(Square.A1, Piece.NONE)
        .createChangedPosition(Square.A4, Piece.BLACK_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, after, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(Square.A1, response.releasedPieceContext().get().square());
  }

  /**
   * FIDE 4.7: the FIRST legal release in the turn is the one that binds — even if a later drop is also a legal move.
   */
  @Test
  void testReleasedPieceViolationFirstReleaseWinsAcrossTwoLegalMoves() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // White releases knight on c3 (legal: Nc3), then drags the same knight to d2 (Nd2 is also
    // a legal move from b1, but the c3 release was the binding one).
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.C3, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.C3, Square.D2, Piece.WHITE_KNIGHT, 1));

    final BitboardPosition afterD2 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B1, Piece.NONE).createChangedPosition(Square.D2, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterD2, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.message().contains("knight on c3"));
    assertTrue(response.restorePosition().isPresent());
    assertEquals(BitboardPositions.from(board.getBitboardPosition()).createChangedPosition(Square.B1, Piece.NONE)
        .createChangedPosition(Square.C3, Piece.WHITE_KNIGHT).build(), response.restorePosition().get());
  }

  /**
   * FIDE 4.7: the king's release on g1 starts a kingside castling sequence. The only legal final position is the full
   * castled state (king on g1 AND rook on f1). Stopping after the king move, with the rook still on h1, must trigger a
   * violation.
   */
  @Test
  void testReleasedPieceViolationCastlingKingReleasedButRookNotMoved() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    // Set up a position where O-O is legal for white.
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nf6");
    board.moveStrict("Bc4");
    board.moveStrict("Bc5");

    // White releases the king on g1 but never moves the rook.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));

    final BitboardPosition afterKingOnly = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.G1, Piece.WHITE_KING).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterKingOnly, sequence);

    // The release on g1 is part of the legal castling move, so it commits — but the only
    // allowed final position is the fully castled one with the rook on f1.
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    // Castling-specific message: the king's home is correct, the player must complete
    // the castling by moving the rook from h1 to f1. The message must NOT mislead the
    // player into "putting the king back" since the king is already where it belongs.
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_CASTLING_PLAYER, response.playerMessageKey());
    assertEquals("Castling has been started. Because the king was released on g1 and kingside castling is legal,"
        + " you must complete the castling move by moving the rook from h1 to f1.", response.message());
  }

  @Test
  void testReleasedPieceViolationCastlingKingReleasedThenMovedToF1() {
    // User scenario: O-O is legal; the king is released on g1 (which commits to castling), then
    // moved on to f1. Pressing the clock must be a released-piece violation (the king is committed
    // to completing the castle), not an accepted move.
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict("4k3/8/8/8/8/8/8/4K2R w K - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.F1, Piece.WHITE_KING, 1));

    final BitboardPosition afterKingOnF1 = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.F1, Piece.WHITE_KING).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterKingOnF1, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_CASTLING_PLAYER, response.playerMessageKey());
  }

  @Test
  void testTwoKnightShuffleAroundPinIsIllegalMove() {
    // Black Nc6 is pinned (it blocks Qb5 -> Ke8). Black moves Nc6->d4 (illegally exposing the king)
    // and then Ne5->c6 to re-block, in one turn. Two moves; no single legal move produces the
    // result, so it is an illegal move.
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict("2bqkb1r/pQp1ppp1/2np4/1Q2n3/8/7p/PP1PPPPP/RNB1KBNR b KQk - 9 12");

    final ActionSequence sequence = new ActionSequence(Side.BLACK);
    sequence.addEvent(BoardEvent.dragMove(Square.C6, Square.D4, Piece.BLACK_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E5, Square.C6, Piece.BLACK_KNIGHT, 1));

    // c6 stays a black knight (the e5 knight refilled it); the net change is e5 -> d4.
    final BitboardPosition after = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.D4, Piece.BLACK_KNIGHT).createChangedPosition(Square.E5, Piece.NONE).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, after, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
  }

  /**
   * User-reported scenario: kingside castling is legal, the player releases the king on g1, then drags the rook from h1
   * to E1 (wrong destination) and presses the clock. The arbiter must report a castling-specific message asking the
   * player to complete the castling by placing the rook on f1 — not the misleading "put the king back on g1" message,
   * since the king is already correctly placed.
   */
  @Test
  void testReleasedPieceViolationCastlingRookMovedToWrongSquare() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nf6");
    board.moveStrict("Bc4");
    board.moveStrict("Bc5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.E1, Piece.WHITE_ROOK, 1));

    final BitboardPosition wrongAfter = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.WHITE_ROOK).createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.H1, Piece.NONE).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, wrongAfter, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_CASTLING_PLAYER, response.playerMessageKey());
    assertEquals("Castling has been started. Because the king was released on g1 and kingside castling is legal,"
        + " you must complete the castling move by moving the rook from h1 to f1.", response.message());
    // The misleading "put the king back" wording from the generic released-piece message
    // must NOT appear — the king is already correctly placed.
    assertFalse(response.message().contains("Please put the king back"));
    assertFalse(response.message().contains("Released-piece violation"));
  }

  /**
   * Castling completed normally: king released on g1, then rook released on f1, board now in the fully castled state.
   * The released-piece rule must NOT fire — the player completed the legal move that the king's release was part of.
   */
  @Test
  void testReleasedPieceRuleAllowsCompletedCastling() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nf6");
    board.moveStrict("Bc4");
    board.moveStrict("Bc5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final BitboardPosition afterCastled = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.H1, Piece.NONE).createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterCastled, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testSecondIllegalMoveGameLost() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

    // First illegal move
    engine.evaluateClockPress(board, afterPosition, sequence);

    // Second illegal move
    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, response.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_GAME_LOST_PLAYER, response.playerMessageKey());
    assertEquals(2, response.illegalMoveDetail().get().count());
  }

  /**
   * With max=4 the early illegal moves should name the 4th (not "next") as the loss-trigger, and the count should
   * advance correctly across attempts.
   */
  @Test
  void testIllegalMoveMessageNamesMaxOrdinalWhenManyRemain() {
    final ArbiterEngine engine = new ArbiterEngine(4);
    final Board board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse first = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, first.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_LIMIT, first.playerMessageKey());
    assertEquals(1, first.illegalMoveDetail().get().count());
    assertEquals(4, first.illegalMoveDetail().get().maxIllegalMoves());

    final ArbiterResponse second = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, second.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_LIMIT, second.playerMessageKey());
    assertEquals(2, second.illegalMoveDetail().get().count());
    assertEquals(4, second.illegalMoveDetail().get().maxIllegalMoves());

    final ArbiterResponse third = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, third.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, third.playerMessageKey());
    assertEquals(3, third.illegalMoveDetail().get().count());
    // Now exactly one remaining → message switches to "next" instead of "4th".

    final ArbiterResponse fourth = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, fourth.type());
  }

  /** With unlimited illegal moves, the message reports the count but never threatens loss. */
  @Test
  void testIllegalMoveMessageOmitsLossThreatWhenUnlimited() {
    final ArbiterEngine engine = new ArbiterEngine(IllegalMoveTracker.UNLIMITED);
    final Board board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_UNLIMITED, response.playerMessageKey());
    assertEquals(1, response.illegalMoveDetail().get().count());
    assertTrue(response.illegalMoveDetail().get().unlimited());
  }

  /**
   * FIDE 7.5.3: pressing the clock without making a move is considered and penalised as an illegal move. With the
   * default limit of two illegal moves, the second such press loses the game.
   */
  @Test
  void testClockPressWithoutMoveIsPenalisedAsIllegalMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    // First press without a move: an illegal move — counted, with the "make a move" instruction
    // (nothing to restore, the board is unchanged).
    final ArbiterResponse first = engine.evaluateClockPress(board, board.getBitboardPosition(), sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, first.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_NO_MOVE_PLAYER_NEXT, first.playerMessageKey());
    assertTrue(first.illegalMoveDetail().get().noMoveMade());
    assertTrue(first.renderedPlayerMessage().contains("the clock was pressed without a move being made (FIDE 7.5.3)"));
    assertTrue(first.renderedPlayerMessage().contains("Please make a move."));
    assertFalse(first.renderedPlayerMessage().contains("restore"));
    assertTrue(first.renderedOpponentMessage().get().contains("They are requested to make a move."));
    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));

    // Second press without a move: the game is lost (default limit: 2 illegal moves).
    final ArbiterResponse second = engine.evaluateClockPress(board, board.getBitboardPosition(), sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, second.type());
    assertTrue(second.renderedPlayerMessage().contains("White loses the game"));
    assertTrue(second.renderedPlayerMessage().contains("2nd illegal move"));
  }

  @Test
  void testTouchMoveViolationOwnPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player touches knight g1, but plays pawn e4
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertTrue(response.obligation().isPresent());
    assertEquals(Square.G1, response.obligation().get().square());
    assertTrue(response.message().contains("knight"));
  }

  @Test
  void testTouchMoveViolationOwnPawnMessageNamesFirstTouchedPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player touches pawn b2, but plays e4.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.B2, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
    assertTrue(response.obligation().isPresent());
    assertEquals(Square.B2, response.obligation().get().square());
    assertEquals(Piece.WHITE_PAWN, response.obligation().get().piece());
    assertEquals(MessageKey.ARBITER_TOUCH_MOVE_OWN_PLAYER, response.playerMessageKey());
    assertEquals(Messages.get(MessageKey.ARBITER_TOUCH_MOVE_OWN_PLAYER, "pawn", "b2"),
        response.renderedPlayerMessage());
  }

  @Test
  void testTouchMoveViolationUsesPrecededMessageWhenEarlierTouchHadNoLegalMoves() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player touches the a1 rook (no legal moves in the start position), then the b1 knight (which
    // does have legal moves), but plays e4. The binding piece is the knight on b1, reached only
    // after an unmovable own-piece touch, so the "first touched piece that can move" message is used.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.click(Square.B1, Piece.WHITE_KNIGHT, 1));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 2));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertTrue(response.obligation().isPresent());
    assertEquals(Square.B1, response.obligation().get().square());
    assertTrue(response.obligation().get().precededByUnmovableOwnTouch());
    assertEquals(MessageKey.ARBITER_TOUCH_MOVE_OWN_PRECEDED_PLAYER, response.playerMessageKey());
    assertEquals(Messages.get(MessageKey.ARBITER_TOUCH_MOVE_OWN_PRECEDED_PLAYER, "knight", "b1"),
        response.renderedPlayerMessage());
  }

  @Test
  void testTouchMoveViolationOpponentPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    // Play 1.e4 d5 — now exd5 is possible
    board.moveStrict("e4");
    board.moveStrict("d5");

    // White touches opponent pawn on d5 (must capture), but plays Nf3 instead
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.D5, Piece.BLACK_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertTrue(response.message().contains("capture"));
  }

  @Test
  void testTouchMoveViolationDoesNotCountAsIllegalMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player touches knight g1, but plays pawn e4 — touch-move violation
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    engine.evaluateClockPress(board, afterPosition, sequence);

    // Touch-move violation should NOT count as illegal move
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testTouchMoveObligationSatisfied() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player touches knight g1 and plays Nf3 — satisfies touch-move
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testCastlingDetectedViaPositionComparison() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    // Set up kingside castling
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");

    // Player moves king to g1 and rook to f1 (castling)
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING).createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testRookFirstCannotBeAcceptedAsCastling() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    board.moveStrict("e4");
    board.moveStrict("e5");
    board.moveStrict("Nf3");
    board.moveStrict("Nc6");
    board.moveStrict("Be2");
    board.moveStrict("Nf6");

    // Rook first produces the same final position as castling, but the move may not be
    // accepted as O-O because castling is a king move.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING).createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.message().contains("rook on f1"));
  }

  @Test
  void testIllegalCastlingAttemptReportsCastlingReasonAndKingObligation() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict("k4r2/8/8/8/8/8/8/4K2R w K - 0 1");

    // White tries to castle through f1, which is attacked by the black rook on f8.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING).createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertTrue(response.message().contains("castling is not possible"));
    assertTrue(response.message().contains("the king would travel over a field that is in check"));
    assertTrue(response.message().contains("Castling counts as a king move"));
    assertTrue(response.message().contains("you must make a legal move with the king"));
    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testFailedAdjacentCastlingAttemptWithNoKingMovesDoesNotBindRook() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict("k4r2/8/8/8/8/8/P2PP3/3QK2R w K - 0 1");

    // White attempts a malformed kingside castling motion. Castling is impossible because f1 is
    // attacked, and the king has no legal move at all from e1.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.F1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.G1, Piece.WHITE_ROOK, 1));

    final BitboardPosition afterFailedCastling = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.F1, Piece.WHITE_KING).createChangedPosition(Square.G1, Piece.WHITE_ROOK).build();

    final ArbiterResponse failedCastling = engine.evaluateClockPress(board, afterFailedCastling, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, failedCastling.type());
    assertTrue(failedCastling.message().contains("castling is not possible"));
    assertTrue(failedCastling.message().contains("the touched king has no legal moves"));
    assertTrue(failedCastling.message().contains("make another legal move"));

    // After restoring the original position, the failed castling rook touch must not bind the
    // player to a rook move. A different legal move is acceptable.
    sequence.resetReleasedPieceRule();
    sequence.addEvent(BoardEvent.dragMove(Square.A2, Square.A3, Piece.WHITE_PAWN, 2));
    final BitboardPosition afterPawnMove = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.A2, Piece.NONE).createChangedPosition(Square.A3, Piece.WHITE_PAWN).build();

    final ArbiterResponse laterMove = engine.evaluateClockPress(board, afterPawnMove, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, laterMove.type());
    assertTrue(laterMove.acceptedMove().isPresent());
    assertEquals(Square.A2, laterMove.acceptedMove().get().moveSpecification().fromSquare());
    assertEquals(Square.A3, laterMove.acceptedMove().get().moveSpecification().toSquare());
  }

  @Test
  void testNonCastlingKingAndRookMovesDoNotReportCastlingFailure() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict("k4r2/8/8/8/8/8/P2PP3/3QK2R w K - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.E3, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F2, Piece.WHITE_ROOK, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_KING).createChangedPosition(Square.F2, Piece.WHITE_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertFalse(response.message().contains("castling is not possible"));
  }

  @Test
  void testFailedAdjacentCastlingAttemptWithLegalKingReleaseStillBindsReleasedPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = Board.fromFenStrict("k7/8/8/8/8/8/P2PPP2/3QK2R w - - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.F1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.G1, Piece.WHITE_ROOK, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.F1, Piece.WHITE_KING).createChangedPosition(Square.G1, Piece.WHITE_ROOK).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.message().contains("king on f1"));
  }

  @Test
  void testEnPassantDetectedViaPositionComparison() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();
    // Set up en passant
    board.moveStrict("e4");
    board.moveStrict("d5");
    board.moveStrict("e5");
    board.moveStrict("f5");

    // Player executes en passant: pawn e5 to f6, removes pawn from f5
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.remove(Square.F5, Piece.BLACK_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E5, Square.F6, Piece.WHITE_PAWN, 1));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E5, Piece.NONE).createChangedPosition(Square.F5, Piece.NONE)
        .createChangedPosition(Square.F6, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testPlayerFumblesButEndsWithValidPosition() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player drags knight to illegal square, then moves it back, then plays e4
    // All that matters is the final position and the touch-move scan
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    // Touches the pawn first (pawn on e2 has legal moves → touch-move obligation)
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    // Position is valid and touch-move is satisfied (pawn was touched and moved)
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testPlayerFumblesWithKnightButPlaysLegalKnightMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final Board board = new Board();

    // Player touches knight on b1, fumbles around, ends up with knight on c3
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.B3, Piece.WHITE_KNIGHT, 0)); // illegal intermediate
    sequence.addEvent(BoardEvent.dragMove(Square.B3, Square.C3, Piece.WHITE_KNIGHT, 1)); // correction

    final BitboardPosition afterPosition = BitboardPositions.from(board.getBitboardPosition())
        .createChangedPosition(Square.B1, Piece.NONE).createChangedPosition(Square.C3, Piece.WHITE_KNIGHT).build();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    // Position is valid (Nc3) and touch-move satisfied (knight from b1 was touched)
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }
}
