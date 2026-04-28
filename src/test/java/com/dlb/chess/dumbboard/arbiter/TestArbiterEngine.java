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
import com.dlb.chess.dumbboard.message.MessageKey;
import com.dlb.chess.dumbboard.message.Messages;

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
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, response.playerMessageKey());
    assertEquals(1, response.illegalMoveDetail().get().count());
    assertEquals(Side.WHITE, response.illegalMoveDetail().get().side());
    assertEquals("the knight cannot move in this way", response.illegalMoveDetail().get().playerReason().get());
    assertEquals(1, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  @Test
  void testIllegalMoveWithoutSimpleAttemptKeepsGenericMessage() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_NEXT, response.playerMessageKey());
    assertEquals(1, response.illegalMoveDetail().get().count());
    assertTrue(response.illegalMoveDetail().get().playerReason().isEmpty());
  }

  @Test
  void testReleasedPieceViolationAfterLegalPawnRelease() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E3, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E3, Square.E4, Piece.WHITE_PAWN, 1));

    final StaticPosition afterE4 = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterE4, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_PLAYER, response.playerMessageKey());
    assertEquals(Piece.WHITE_PAWN, response.releasedPieceContext().get().piece());
    assertEquals(Square.E3, response.releasedPieceContext().get().square());
    assertTrue(response.restorePosition().isPresent());
    assertEquals(board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_PAWN), response.restorePosition().get());
  }

  /** FIDE 4.7: putting the piece back to the original square does not undo a committed release. */
  @Test
  void testReleasedPieceViolationWhenDraggedBackToOrigin() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // White releases pawn on e3 (legal commit), then drags the pawn back to e2.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E3, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E3, Square.E2, Piece.WHITE_PAWN, 1));

    // Final physical position equals the position before the turn — no move on the board.
    final StaticPosition afterOriginal = board.getStaticPosition();

    final ArbiterResponse response = engine.evaluateClockPress(board, afterOriginal, sequence);

    // The committed e3 release still binds; the player must restore to the e3 state.
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.restorePosition().isPresent());
    assertEquals(board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_PAWN), response.restorePosition().get());
    // No illegal-move counter increment for the procedural violation.
    assertEquals(0, engine.getIllegalMoveTracker().getIllegalMoveCount(Side.WHITE));
  }

  /** FIDE 4.7: the FIRST legal release in the turn is the one that binds — even if a later
      drop is also a legal move. */
  @Test
  void testReleasedPieceViolationFirstReleaseWinsAcrossTwoLegalMoves() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // White releases knight on c3 (legal: Nc3), then drags the same knight to d2 (Nd2 is also
    // a legal move from b1, but the c3 release was the binding one).
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.B1, Square.C3, Piece.WHITE_KNIGHT, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.C3, Square.D2, Piece.WHITE_KNIGHT, 1));

    final StaticPosition afterD2 = board.getStaticPosition()
        .createChangedPosition(Square.B1, Piece.NONE)
        .createChangedPosition(Square.D2, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterD2, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.message().contains("knight on c3"));
    assertTrue(response.restorePosition().isPresent());
    assertEquals(board.getStaticPosition()
        .createChangedPosition(Square.B1, Piece.NONE)
        .createChangedPosition(Square.C3, Piece.WHITE_KNIGHT), response.restorePosition().get());
  }

  /** FIDE 4.7: the king's release on g1 starts a kingside castling sequence. The only legal
      final position is the full castled state (king on g1 AND rook on f1). Stopping after the
      king move, with the rook still on h1, must trigger a violation. */
  @Test
  void testReleasedPieceViolationCastlingKingReleasedButRookNotMoved() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    // Set up a position where O-O is legal for white.
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nf6");
    board.performMove("Bc4");
    board.performMove("Bc5");

    // White releases the king on g1 but never moves the rook.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));

    final StaticPosition afterKingOnly = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterKingOnly, sequence);

    // The release on g1 is part of the legal castling move, so it commits — but the only
    // allowed final position is the fully castled one with the rook on f1.
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    // Castling-specific message: the king's home is correct, the player must complete
    // the castling by moving the rook from h1 to f1. The message must NOT mislead the
    // player into "putting the king back" since the king is already where it belongs.
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_CASTLING_PLAYER, response.playerMessageKey());
    assertTrue(response.message().contains("king on g1"));
    assertTrue(response.message().contains("kingside castling"));
    assertTrue(response.message().contains("from h1 to f1"));
  }

  /** User-reported scenario: kingside castling is legal, the player releases the king on g1,
      then drags the rook from h1 to E1 (wrong destination) and presses the clock. The arbiter
      must report a castling-specific message asking the player to complete the castling by
      placing the rook on f1 — not the misleading "put the king back on g1" message, since
      the king is already correctly placed. */
  @Test
  void testReleasedPieceViolationCastlingRookMovedToWrongSquare() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nf6");
    board.performMove("Bc4");
    board.performMove("Bc5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.E1, Piece.WHITE_ROOK, 1));

    final StaticPosition wrongAfter = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.WHITE_ROOK)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.H1, Piece.NONE);

    final ArbiterResponse response = engine.evaluateClockPress(board, wrongAfter, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertEquals(MessageKey.ARBITER_RELEASED_PIECE_CASTLING_PLAYER, response.playerMessageKey());
    assertTrue(response.message().contains("king on g1"));
    assertTrue(response.message().contains("kingside castling"));
    assertTrue(response.message().contains("from h1 to f1"));
    // The misleading "put the king back" wording from the generic released-piece message
    // must NOT appear — the king is already correctly placed.
    assertFalse(response.message().contains("Please put the king back"));
  }

  /** Castling completed normally: king released on g1, then rook released on f1, board now in
      the fully castled state. The released-piece rule must NOT fire — the player completed
      the legal move that the king's release was part of. */
  @Test
  void testReleasedPieceRuleAllowsCompletedCastling() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nf6");
    board.performMove("Bc4");
    board.performMove("Bc5");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final StaticPosition afterCastled = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterCastled, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
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
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_GAME_LOST_PLAYER, response.playerMessageKey());
    assertEquals(2, response.illegalMoveDetail().get().count());
  }

  /** With max=4 the early illegal moves should name the 4th (not "next") as the loss-trigger,
      and the count should advance correctly across attempts. */
  @Test
  void testIllegalMoveMessageNamesMaxOrdinalWhenManyRemain() {
    final ArbiterEngine engine = new ArbiterEngine(4);
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

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
    final ApiBoard board = new Board();

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertEquals(MessageKey.ARBITER_ILLEGAL_MOVE_PLAYER_UNLIMITED, response.playerMessageKey());
    assertEquals(1, response.illegalMoveDetail().get().count());
    assertTrue(response.illegalMoveDetail().get().unlimited());
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
  void testTouchMoveViolationOwnPawnMessageNamesFirstTouchedPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();

    // Player touches pawn b2, but plays e4.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.click(Square.B2, Piece.WHITE_PAWN, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

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
  void testRookFirstCannotBeAcceptedAsCastling() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board();
    board.performMove("e4");
    board.performMove("e5");
    board.performMove("Nf3");
    board.performMove("Nc6");
    board.performMove("Be2");
    board.performMove("Nf6");

    // Rook first produces the same final position as castling, but the move may not be
    // accepted as O-O because castling is a king move.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.message().contains("rook on f1"));
  }

  @Test
  void testIllegalCastlingAttemptReportsCastlingReasonAndKingObligation() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board("k4r2/8/8/8/8/8/8/4K2R w K - 0 1");

    // White tries to castle through f1, which is attacked by the black rook on f8.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.G1, Piece.WHITE_KING)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK);

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
    final ApiBoard board = new Board("k4r2/8/8/8/8/8/P2PP3/3QK2R w K - 0 1");

    // White attempts a malformed kingside castling motion. Castling is impossible because f1 is
    // attacked, and the king has no legal move at all from e1.
    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.F1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.G1, Piece.WHITE_ROOK, 1));

    final StaticPosition afterFailedCastling = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.F1, Piece.WHITE_KING)
        .createChangedPosition(Square.G1, Piece.WHITE_ROOK);

    final ArbiterResponse failedCastling = engine.evaluateClockPress(board, afterFailedCastling, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, failedCastling.type());
    assertTrue(failedCastling.message().contains("castling is not possible"));
    assertTrue(failedCastling.message().contains("the touched king has no legal moves"));
    assertTrue(failedCastling.message().contains("make another legal move"));

    // After restoring the original position, the failed castling rook touch must not bind the
    // player to a rook move. A different legal move is acceptable.
    sequence.resetReleasedPieceRule();
    sequence.addEvent(BoardEvent.dragMove(Square.A2, Square.A3, Piece.WHITE_PAWN, 2));
    final StaticPosition afterPawnMove = board.getStaticPosition()
        .createChangedPosition(Square.A2, Piece.NONE)
        .createChangedPosition(Square.A3, Piece.WHITE_PAWN);

    final ArbiterResponse laterMove = engine.evaluateClockPress(board, afterPawnMove, sequence);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, laterMove.type());
    assertTrue(laterMove.acceptedMove().isPresent());
    assertEquals(Square.A2, laterMove.acceptedMove().get().moveSpecification().fromSquare());
    assertEquals(Square.A3, laterMove.acceptedMove().get().moveSpecification().toSquare());
  }

  @Test
  void testNonCastlingKingAndRookMovesDoNotReportCastlingFailure() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board("k4r2/8/8/8/8/8/P2PP3/3QK2R w K - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.E3, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.F2, Piece.WHITE_ROOK, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.E3, Piece.WHITE_KING)
        .createChangedPosition(Square.F2, Piece.WHITE_ROOK);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());
    assertFalse(response.message().contains("castling is not possible"));
  }

  @Test
  void testFailedAdjacentCastlingAttemptWithLegalKingReleaseStillBindsReleasedPiece() {
    final ArbiterEngine engine = new ArbiterEngine();
    final ApiBoard board = new Board("k7/8/8/8/8/8/P2PPP2/3QK2R w - - 0 1");

    final ActionSequence sequence = new ActionSequence(Side.WHITE);
    sequence.addEvent(BoardEvent.dragMove(Square.E1, Square.F1, Piece.WHITE_KING, 0));
    sequence.addEvent(BoardEvent.dragMove(Square.H1, Square.G1, Piece.WHITE_ROOK, 1));

    final StaticPosition afterPosition = board.getStaticPosition()
        .createChangedPosition(Square.E1, Piece.NONE)
        .createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.F1, Piece.WHITE_KING)
        .createChangedPosition(Square.G1, Piece.WHITE_ROOK);

    final ArbiterResponse response = engine.evaluateClockPress(board, afterPosition, sequence);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, response.type());
    assertTrue(response.message().contains("king on f1"));
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
