package com.dlb.chess.dumbboard.arbiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class TestArbiterEngine {

  @Test
  void testValidSimpleMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player drags pawn e2 to e4 and presses clock
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertTrue(response.acceptedMove().isPresent());
    assertEquals(Square.E2, response.acceptedMove().get().moveSpecification().fromSquare());
    assertEquals(Square.E4, response.acceptedMove().get().moveSpecification().toSquare());
  }

  @Test
  void testIllegalMoveNoMatchingPosition() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player puts knight on an impossible square
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testSecondIllegalMoveGameLost() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    // First illegal move
    engine.evaluateClockPress(board, afterPosition, sequence);

    // Second illegal move
    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, response.type());
    assertTrue(response.message().contains("2nd illegal move"));
  }

  @Test
  void testIncompleteMoveBoardUnchanged() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player presses clock without changing the board
    final ActionSequence sequence = new ActionSequence(Side.WHITE);

    final ArbiterResponse response = engine.evaluateClockPress(board, board.getStaticPosition(), sequence);

    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
  }

  @Test
  void testTouchMoveViolationOwnPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player touches knight g1, but plays pawn e4
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertTrue(response.obligation().isPresent());
    assertEquals(Square.G1, response.obligation().get().square());
    assertTrue(response.message().contains("knight"));
  }

  @Test
  void testTouchMoveViolationOpponentPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    // Play 1.e4 d5 — now exd5 is possible
    board.performMove("e4");
    board.performMove("d5");

    // White touches opponent pawn on d5 (must capture), but plays Nf3 instead
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
  void testTouchMoveViolationDoesNotCountAsIllegalMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player touches knight g1, but plays pawn e4 — touch-move violation
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    engine.evaluateClockPress(board, afterPosition, sequence);

    // Touch-move violation should NOT count as illegal move
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testTouchMoveObligationSatisfied() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player touches knight g1 and plays Nf3 — satisfies touch-move
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testCastlingDetectedViaPositionComparison() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    // Set up kingside castling
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nc6");
    board.performMove("Be2");
    board.performMove("Nf6");

    // Player moves king to g1 and rook to f1 (castling)
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testEnPassantDetectedViaPositionComparison() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    // Set up en passant
    board.performMove("e4");
    board.performMove("d5");
    board.performMove("e5");
    board.performMove("f5");

    // Player executes en passant: pawn e5 to f6, removes pawn from f5
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.remove(Square.F5, Piece.BLACK_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E5, Square.F6, Piece.WHITE_PAWN, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E5, Piece.NONE)
        .createChangedPosition(Square.F5, Piece.NONE)
        .createChangedPosition(Square.F6, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testPlayerFumblesButEndsWithValidPosition() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player drags knight to illegal square, then moves it back, then plays e4
    // All that matters is the final position and the touch-move scan
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    // Touches the pawn first (pawn on e2 has legal moves → touch-move obligation)
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    // Position is valid and touch-move is satisfied (pawn was touched and moved)
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }

  @Test
  void testPlayerFumblesWithKnightButPlaysLegalKnightMove() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player touches knight on b1, fumbles around, ends up with knight on c3
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.B3, Piece.WHITE_KNIGHT, 0)); // illegal intermediate
    sequence.addEvent(BoardEvent.dragMove(Square.B3, Square.C3, Piece.WHITE_KNIGHT, 1)); // correction

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.B1, Piece.NONE)
        .createChangedPosition(Square.C3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    // Position is valid (Nc3) and touch-move satisfied (knight from b1 was touched)
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
  }
}
