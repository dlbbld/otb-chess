package com.dlb.chess.dumbboard.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dlb.chess.board.Board;
import com.dlb.chess.board.StaticPosition;
import com.dlb.chess.board.enums.Piece;
import com.dlb.chess.board.enums.Side;
import com.dlb.chess.board.enums.Square;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponse;
import com.dlb.chess.dumbboard.arbiter.ArbiterResponseType;
import com.dlb.chess.dumbboard.event.ActionSequence;
import com.dlb.chess.dumbboard.event.BoardEvent;
import com.dlb.chess.dumbboard.game.model.DrawClaimResult;
import com.dlb.chess.dumbboard.game.model.DrawClaimType;
import com.dlb.chess.dumbboard.game.model.GameResult;
import com.dlb.chess.dumbboard.game.model.GameResultType;
import com.dlb.chess.dumbboard.game.model.GameState;
import com.dlb.chess.dumbboard.game.model.TimeControl;

class TestGameSession {

  private static final TimeControl TEST_TIME = new TimeControl(5 * 60 * 1000L, 0);

  /**
   * Helper: makes a move on the session by constructing the after-position and pressing the clock.
   */
  private ArbiterResponse makeMove(GameSession session, Square from, Square to, Piece movingPiece) {
    final Side side = session.getHavingMove();
    final StaticPosition before = session.getBoard().getStaticPosition();

    // Record the drag event
    session.recordEvent(side,
        BoardEvent.dragMove(from, to, movingPiece, System.currentTimeMillis()));

    // Construct after-position
    final StaticPosition afterPosition = before
        .createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, movingPiece);

    return session.pressClockButton(side, afterPosition);
  }

  /**
   * Helper: makes a capture move.
   */
  private ArbiterResponse makeCapture(GameSession session, Square from, Square to, Piece movingPiece,
      Piece capturedPiece) {
    final Side side = session.getHavingMove();
    final StaticPosition before = session.getBoard().getStaticPosition();

    session.recordEvent(side,
        BoardEvent.dragCapture(from, to, movingPiece, capturedPiece, System.currentTimeMillis()));

    final StaticPosition afterPosition = before
        .createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, movingPiece);

    return session.pressClockButton(side, afterPosition);
  }

  @Test
  void testBasicGameFlow() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertEquals(Side.WHITE, session.getHavingMove());

    // White plays e4
    final ArbiterResponse response = makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertEquals(Side.BLACK, session.getHavingMove());

    // Black plays e5
    final ArbiterResponse response2 = makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response2.type());
    assertEquals(Side.WHITE, session.getHavingMove());
  }

  @Test
  void testIllegalMoveAndPenalty() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final Side side = session.getHavingMove();

    // Illegal move: knight to g3 (impossible)
    session.recordEvent(side,
        BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));

    final StaticPosition illegalPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = session.pressClockButton(side, illegalPos);
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, response.type());

    // Still White's turn
    assertEquals(Side.WHITE, session.getHavingMove());
  }

  @Test
  void testTouchNonCapturableOpponentPieceDoesNotBlockLegalMove() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final Optional<ArbiterResponse> touchResponse = session.recordEvent(Side.WHITE,
        BoardEvent.click(Square.A7, Piece.BLACK_PAWN, 0));

    assertTrue(touchResponse.isEmpty());

    final ArbiterResponse response = makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void testTouchCapturableOpponentPieceCreatesCaptureObligation() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    makeMove(session, Square.D7, Square.D5, Piece.BLACK_PAWN);

    final Optional<ArbiterResponse> touchResponse = session.recordEvent(Side.WHITE,
        BoardEvent.click(Square.D5, Piece.BLACK_PAWN, 0));
    assertTrue(touchResponse.isEmpty());

    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT, 1));
    final StaticPosition afterPosition = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.F3, Piece.WHITE_KNIGHT);

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterPosition);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertTrue(response.message().contains("must be captured"));
  }

  @Test
  void testMovingOpponentPieceIsPositionChange() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final Optional<ArbiterResponse> response = session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.A7, Square.A6, Piece.BLACK_PAWN, 0));

    assertTrue(response.isPresent());
    assertEquals(ArbiterResponseType.POSITION_CHANGE, response.get().type());
    assertEquals("Position change: You moved an opponent's piece. That is not allowed. "
        + "Please restore the position.", response.get().message());
  }

  @Test
  void testSecondIllegalMoveEndsGame() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final Side side = session.getHavingMove();
    final StaticPosition illegalPos = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.G1, Piece.NONE)
        .createChangedPosition(Square.G3, Piece.WHITE_KNIGHT);

    // First illegal move
    session.recordEvent(side, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));
    session.pressClockButton(side, illegalPos);
    // Both players ready to continue
    session.enterWaitingForReady();
    session.playerReady(Side.WHITE);
    session.playerReady(Side.BLACK);

    // Second illegal move
    session.recordEvent(side, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 1));
    final ArbiterResponse response = session.pressClockButton(side, illegalPos);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, response.type());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(Side.BLACK, session.getResult().winner());
  }

  @Test
  void testResignation() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final GameResult result = session.resign(Side.WHITE);

    assertEquals(GameResultType.RESIGNATION, result.type());
    assertEquals(Side.BLACK, result.winner());
    assertEquals(GameState.ENDED, session.getState());
  }

  @Test
  void testDrawOfferAccepted() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White plays e4 and offers draw
    final Side white = session.getHavingMove();
    session.recordEvent(white, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final StaticPosition afterE4 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    session.offerDrawCorrectTime(white, afterE4);
    assertTrue(session.getDrawOfferManager().isDrawOffered());

    // Black accepts
    final Optional<GameResult> result = session.acceptDraw(Side.BLACK);
    assertTrue(result.isPresent());
    assertEquals(GameResultType.DRAW_AGREEMENT, result.get().type());
    assertTrue(result.get().isDraw());
  }

  @Test
  void testDrawOfferRejected() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White plays e4 and offers draw
    final Side white = session.getHavingMove();
    session.recordEvent(white, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final StaticPosition afterE4 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    session.offerDrawCorrectTime(white, afterE4);

    // Black rejects
    session.rejectDraw(Side.BLACK);
    assertFalse(session.getDrawOfferManager().isDrawOffered());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  @Test
  void testDrawOfferTouchedPiecePreventsAcceptance() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White plays e4 and offers draw
    final Side white = session.getHavingMove();
    session.recordEvent(white, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final StaticPosition afterE4 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    session.offerDrawCorrectTime(white, afterE4);

    // Black touches a piece
    session.getDrawOfferManager().recordOpponentTouchedPiece();

    // Black tries to accept — rejected because touched a piece
    final Optional<GameResult> result = session.acceptDraw(Side.BLACK);
    assertFalse(result.isPresent());
    assertNotNull(session.getLastAcceptDrawRejection());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  @Test
  void testRepeatedDrawOfferEscalation() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final DrawOfferManager dom = session.getDrawOfferManager();

    // First offer — normal
    var result1 = dom.offerDrawCorrectTime(Side.WHITE);
    assertTrue(result1.accepted());

    // Second offer (repeated, same side, offer still active) — info
    var result2 = dom.offerDrawCorrectTime(Side.WHITE);
    assertFalse(result2.accepted());
    assertTrue(result2.arbiterMessage().contains("cannot repeat"));

    // Third offer — warning
    var result3 = dom.offerDrawCorrectTime(Side.WHITE);
    assertFalse(result3.accepted());
    assertTrue(result3.arbiterMessage().contains("will lose"));

    // Fourth offer — game lost
    var result4 = dom.offerDrawCorrectTime(Side.WHITE);
    assertTrue(result4.gameLost());
  }

  @Test
  void testPgnExport() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Play a few moves
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN);

    final String pgn = session.exportPgn();
    assertNotNull(pgn);
    assertTrue(pgn.contains("e4"));
    assertTrue(pgn.contains("e5"));
  }

  @Test
  void testScholarsMateCheckmate() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // 1. e4
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    // 1... e5
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN);
    // 2. Bc4 (bishop from f1 to c4)
    makeMove(session, Square.F1, Square.C4, Piece.WHITE_BISHOP);
    // 2... Nc6
    makeMove(session, Square.B8, Square.C6, Piece.BLACK_KNIGHT);
    // 3. Qh5 (queen from d1 to h5)
    makeMove(session, Square.D1, Square.H5, Piece.WHITE_QUEEN);
    // 3... Nf6 (blunder)
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT);

    // 4. Qxf7# (queen captures f7 pawn — checkmate)
    final ArbiterResponse response = makeCapture(session, Square.H5, Square.F7, Piece.WHITE_QUEEN, Piece.BLACK_PAWN);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertEquals(GameState.ENDED, session.getState());
    assertNotNull(session.getResult());
    assertEquals(GameResultType.CHECKMATE, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
  }

  @Test
  void testThreefoldClaimOnBoard() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Play moves to create threefold repetition: Nf3 Nf6 Ng1 Ng8 Nf3 Nf6 Ng1 Ng8
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT); // 1. Nf3
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT); // 1... Nf6
    makeMove(session, Square.F3, Square.G1, Piece.WHITE_KNIGHT); // 2. Ng1
    makeMove(session, Square.F6, Square.G8, Piece.BLACK_KNIGHT); // 2... Ng8
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT); // 3. Nf3
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT); // 3... Nf6
    makeMove(session, Square.F3, Square.G1, Piece.WHITE_KNIGHT); // 4. Ng1
    makeMove(session, Square.F6, Square.G8, Piece.BLACK_KNIGHT); // 4... Ng8

    // Now the starting position has occurred 3 times — white can claim
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(result.accepted());
    assertEquals(GameState.ENDED, session.getState());
    assertTrue(session.getResult().isDraw());
  }

  @Test
  void testMustExecuteMoveAfterRejectedClaim() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White claims threefold with move "e4" — but no threefold exists
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");
    assertFalse(result.accepted());
    assertTrue(result.moveToPerform().isPresent());

    // Now white must play e4
    assertNotNull(session.getMustExecuteMove());

    // White plays e4 correctly
    final StaticPosition afterE4 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.E2, Piece.NONE)
        .createChangedPosition(Square.E4, Piece.WHITE_PAWN);

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertNull(session.getMustExecuteMove());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void testMustExecuteMoveWrongPosition() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White claims threefold with move "e4" — rejected
    session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");

    // White plays d4 instead of e4
    final StaticPosition afterD4 = session.getBoard().getStaticPosition()
        .createChangedPosition(Square.D2, Piece.NONE)
        .createChangedPosition(Square.D4, Piece.WHITE_PAWN);

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterD4);
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertTrue(response.message().contains("specified move was not executed"));
  }
}
