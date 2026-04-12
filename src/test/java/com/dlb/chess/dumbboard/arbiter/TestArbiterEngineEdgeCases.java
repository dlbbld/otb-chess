package com.dlb.chess.dumbboard.arbiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.Board;
import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.common.interfaces.ApiBoard;
import com.dlb.chess.dumbboard.event.ActionSequence;
import com.dlb.chess.dumbboard.event.BoardEvent;

/**
 * Edge case tests for ArbiterEngine covering scenarios found during testing.
 */
class TestArbiterEngineEdgeCases {

  @Test
  void testPlayerFumblesMultiplePiecesThenRestoresAllButOne() {
    // Player moves knight to b3 (illegal), captures on c3 (moving again),
    // then puts everything back and plays e4.
    // Touch-move: knight was touched first.
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    // Touch knight b1 (has legal moves → touch-move obligation)
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.B3, Piece.WHITE_KNIGHT, 0));
    // But final position is e4 (pawn moved, not knight)
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    // Touch-move violation: knight was touched, but pawn was moved
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    // Does NOT count as illegal move
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testTouchPieceWithNoLegalMovesThenMoveAnother() {
    // Player touches rook a1 (no legal moves in starting position),
    // then moves pawn e4. No touch-move violation because rook has no legal moves.
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.A1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testIllegalMoveCounterNotIncrementedByTouchMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Touch-move violation (not illegal move)
    final ActionSequence seq1 = new ActionSequence(Side.WHITE);
    seq1.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    seq1.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));
    final StaticPosition afterE4 = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);
    engine.evaluateClockPress(board, afterE4, seq1);

    // Then an actual illegal move
    final ActionSequence seq2 = new ActionSequence(Side.WHITE);
    seq2.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition illegal = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);
    engine.evaluateClockPress(board, illegal, seq2);

    // Only the illegal move counts, not the touch-move violation
    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testEmptyActionSequenceNoTouchMove() {
    // Player makes a valid move without any recorded events (e.g. board was manipulated
    // outside of tracked events). No touch-move obligation.
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    // Empty sequence — no events

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testCaptureDetectedByPositionComparison() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    board.performMove("e4");
    board.performMove("d5");

    // White captures: exd5
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragCapture(Square.E4, Square.D5, Piece.WHITE_PAWN, Piece.BLACK_PAWN, 0));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E4, Piece.NONE)
        .createChangedPosition(Square.D5, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertEquals(Piece.BLACK_PAWN, response.acceptedMove().get().pieceCaptured());
  }

  @Test
  void testOpponentTouchCreatesCapureObligation() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    board.performMove("e4");
    board.performMove("d5");

    // White touches the black pawn on d5 (can be captured by exd5)
    // Then plays Nf3 instead
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.D5, Piece.BLACK_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertTrue(response.message().contains("capture"));
  }

  @Test
  void testBlackIllegalMoveCountedSeparately() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    board.performMove("e4"); // White plays

    // Black makes an illegal move
    final ActionSequence seq = new ActionSequence(Side.BLACK);
    seq.addEvent(BoardEvent.dragMove(Square.E7, Square.E4, Piece.BLACK_PAWN, 0));
    final StaticPosition illegal = board.getStaticPosition()
        .createChangedPosition(Square.E7, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.BLACK_PAWN);
    engine.evaluateClockPress(board, illegal, seq);

    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.BLACK));
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }
}
