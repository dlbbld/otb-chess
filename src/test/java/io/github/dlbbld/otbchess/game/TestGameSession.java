// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResult;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

class TestGameSession {

  private static final TimeControl TEST_TIME = new TimeControl(5 * 60 * 1000L, 0);

  /**
   * Helper: makes a move on the session by constructing the after-position and pressing the clock.
   */
  private ArbiterResponse makeMove(GameSession session, Square from, Square to, Piece movingPiece) {
    final Side side = session.getHavingMove();
    final BitboardPosition before = session.getBoard().getBitboardPosition();

    // Record the drag event
    session.recordEvent(side, BoardEvent.dragMove(from, to, movingPiece, System.currentTimeMillis()));

    // Construct after-position
    final BitboardPosition afterPosition = BitboardPositions.from(before).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, movingPiece).build();

    return session.pressClockButton(side, afterPosition);
  }

  /**
   * Helper: makes a capture move.
   */
  private ArbiterResponse makeCapture(GameSession session, Square from, Square to, Piece movingPiece,
      Piece capturedPiece) {
    final Side side = session.getHavingMove();
    final BitboardPosition before = session.getBoard().getBitboardPosition();

    session.recordEvent(side, BoardEvent.dragCapture(from, to, movingPiece, capturedPiece, System.currentTimeMillis()));

    final BitboardPosition afterPosition = BitboardPositions.from(before).createChangedPosition(from, Piece.NONE)
        .createChangedPosition(to, movingPiece).build();

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
    session.recordEvent(side, BoardEvent.dragMove(Square.G1, Square.G3, Piece.WHITE_KNIGHT, 0));

    final BitboardPosition illegalPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

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
    final BitboardPosition afterPosition = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();

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
    assertEquals(
        "Position change: You moved an opponent's piece. That is not allowed. " + "Please restore the position.",
        response.get().message());
  }

  /**
   * Capture-by-removal: the player lifts the opponent piece off the board (REMOVE) and then moves their own piece onto
   * the now-empty square. Both events must be allowed mid-play (no immediate intervention) and the resulting position
   * must be accepted at clock press as a normal capture, since it equals the position after the legal capture move.
   */
  @Test
  void testCaptureByRemovingOpponentPieceFirstThenMoving() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Set up: 1.e4 e5 2.Nf3, then Black plays Nc6, then White plays Nxe5? No — let's set
    // up so White can capture on e5 with the knight on f3. After 1.e4 e5 2.Nf3, white can
    // play Nxe5 (it's pseudo-legal; e5 is undefended after Nc6 isn't played, but the
    // king-safety rule is fine).
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN);
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT);
    makeMove(session, Square.B8, Square.C6, Piece.BLACK_KNIGHT);

    // Step 1: White REMOVES the black pawn from e5 — must NOT trigger an intervention.
    final Optional<ArbiterResponse> removeResponse = session.recordEvent(Side.WHITE,
        BoardEvent.remove(Square.E5, Piece.BLACK_PAWN, 0));
    assertTrue(removeResponse.isEmpty(), "REMOVE of opponent piece must be allowed (no intervention)");

    // Step 2: White moves the knight Nf3 -> e5. The DRAG_MOVE event itself is allowed.
    final Optional<ArbiterResponse> moveResponse = session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.F3, Square.E5, Piece.WHITE_KNIGHT, 1));
    assertTrue(moveResponse.isEmpty(), "DRAG_MOVE of own piece onto empty square must be allowed");

    // Step 3: Press the clock. The resulting position equals the position after the legal
    // capture Nxe5, so the arbiter accepts it as a normal capture move.
    final BitboardPosition afterCapture = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E5, Piece.WHITE_KNIGHT).createChangedPosition(Square.F3, Piece.NONE).build();
    final ArbiterResponse pressResponse = session.pressClockButton(Side.WHITE, afterCapture);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, pressResponse.type());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  /**
   * Stand-alone unit check on the validator: the REMOVE of an opponent piece does not trigger any mid-play
   * intervention, while DRAG_MOVE of the same opponent piece does.
   */
  @Test
  void testRemoveOfOpponentPieceIsAllowedDragOfOpponentPieceIsNot() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final Optional<ArbiterResponse> removeResp = session.recordEvent(Side.WHITE,
        BoardEvent.remove(Square.A7, Piece.BLACK_PAWN, 0));
    assertTrue(removeResp.isEmpty());

    final Optional<ArbiterResponse> dragResp = session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E7, Square.E5, Piece.BLACK_PAWN, 1));
    assertTrue(dragResp.isPresent());
    assertEquals(ArbiterResponseType.POSITION_CHANGE, dragResp.get().type());
  }

  @Test
  void testSecondIllegalMoveEndsGame() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final Side side = session.getHavingMove();
    final BitboardPosition illegalPos = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.G3, Piece.WHITE_KNIGHT).build();

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
  void testCustomFenStartingPositionWithBlackToMove() {
    // FEN with Black to move: White just played e4. Standard openings notation.
    final Board board = Board.fromFenStrict("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1");
    final GameSession session = new GameSession(TEST_TIME,
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true, board);
    session.startGame();

    assertEquals(GameState.IN_PROGRESS, session.getState());
    // The side-to-move from the FEN is Black, not the hardcoded White.
    assertEquals(Side.BLACK, session.getHavingMove());
    // Black's clock is the one running — verify by attempting a White move first
    // and confirming it's rejected as "not your turn".
    final ArbiterResponse rejected = session.pressClockButton(Side.WHITE, session.getBoard().getBitboardPosition());
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, rejected.type());

    // Black plays e5 (the natural reply) and the move is accepted.
    session.recordEvent(Side.BLACK, BoardEvent.dragMove(Square.E7, Square.E5, Piece.BLACK_PAWN, 0));
    final BitboardPosition afterE5 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E7, Piece.NONE).createChangedPosition(Square.E5, Piece.BLACK_PAWN).build();
    final ArbiterResponse accepted = session.pressClockButton(Side.BLACK, afterE5);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, accepted.type());
    assertEquals(Side.WHITE, session.getHavingMove());
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
  void testResignationDuringOpponentTurn() {
    // White moves, so it becomes black's turn; white then resigns. Resignation is unconditional:
    // it ends the game regardless of whose turn it is.
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    assertEquals(Side.BLACK, session.getHavingMove());

    final GameResult result = session.resign(Side.WHITE);

    assertEquals(GameResultType.RESIGNATION, result.type());
    assertEquals(Side.BLACK, result.winner());
    assertEquals(GameState.ENDED, session.getState());
  }

  @Test
  void testResignationByPlayerNotOnMove() {
    // At the start it is white's turn; the player not on move (black) resigns -> white wins.
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    assertEquals(Side.WHITE, session.getHavingMove());

    final GameResult result = session.resign(Side.BLACK);

    assertEquals(GameResultType.RESIGNATION, result.type());
    assertEquals(Side.WHITE, result.winner());
    assertEquals(GameState.ENDED, session.getState());
  }

  @Test
  void testResignationDrawWhenOpponentHasInsufficientMaterial() {
    // White resigns, but Black has only a lone king and can never mate (FIDE 5.1.2 exception):
    // the game is a draw, and the message names the material shortage.
    final GameSession session = new GameSession(TEST_TIME,
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true,
        Board.fromFenStrict("8/8/4k3/3R4/2K5/8/8/8 w - - 0 50"));
    session.startGame();

    final GameResult result = session.resign(Side.WHITE);

    assertEquals(GameResultType.RESIGNATION, result.type());
    assertEquals(Side.NONE, result.winner());
    assertTrue(result.isDraw());
    assertEquals("White resigned, but because Black has insufficient material to mate, the game is a draw.",
        result.description());
  }

  @Test
  void testResignationDrawWhenOpponentHasNoMatePotentialDespiteMaterial() {
    // White resigns in a fully blocked pawn wall: Black has pawns (sufficient material) but can
    // never break through, so the position is unwinnable -> draw, reported as "no potential mate".
    final GameSession session = new GameSession(TEST_TIME,
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true,
        Board.fromFenStrict("8/8/3k4/1p2p1p1/pP1pP1P1/P2P4/1K6/8 w - - 32 62"));
    session.startGame();

    final GameResult result = session.resign(Side.WHITE);

    assertEquals(GameResultType.RESIGNATION, result.type());
    assertEquals(Side.NONE, result.winner());
    assertEquals("White resigned, but because Black has no potential mate, the game is a draw.", result.description());
  }

  @Test
  void testFlagFallDrawWhenOpponentHasInsufficientMaterial() {
    // White's flag falls (zero time), but Black has a lone king: the FIDE 6.9 exception draws it,
    // and the message names the material shortage. TimeControl(0,0) flags the side to move at once.
    final GameSession session = new GameSession(new TimeControl(0, 0),
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true,
        Board.fromFenStrict("8/8/4k3/3R4/2K5/8/8/8 w - - 0 50"));
    session.startGame();

    final Optional<GameResult> result = session.checkFlagFall();

    assertTrue(result.isPresent());
    assertEquals(GameResultType.FLAG_FALL, result.get().type());
    assertEquals(Side.NONE, result.get().winner());
    assertEquals("White flagged, but because Black has insufficient material to mate, the game is a draw.",
        result.get().description());
  }

  @Test
  void testFlagFallDrawWhenOpponentHasNoMatePotentialDespiteMaterial() {
    // White flags in a blocked pawn wall: Black has material but no way to mate -> draw.
    final GameSession session = new GameSession(new TimeControl(0, 0),
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true,
        Board.fromFenStrict("8/8/3k4/1p2p1p1/pP1pP1P1/P2P4/1K6/8 w - - 32 62"));
    session.startGame();

    final Optional<GameResult> result = session.checkFlagFall();

    assertTrue(result.isPresent());
    assertEquals(GameResultType.FLAG_FALL, result.get().type());
    assertEquals(Side.NONE, result.get().winner());
    assertEquals("White flagged, but because Black has no potential mate, the game is a draw.",
        result.get().description());
  }

  @Test
  void testDrawOfferAccepted() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White plays e4 and offers draw
    final Side white = session.getHavingMove();
    session.recordEvent(white, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN, 0));

    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

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

    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

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

    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

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
    final var result1 = dom.offerDrawCorrectTime(Side.WHITE);
    assertTrue(result1.accepted());

    // Second offer (repeated, same side, offer still active) — info
    final var result2 = dom.offerDrawCorrectTime(Side.WHITE);
    assertFalse(result2.accepted());
    assertTrue(result2.arbiterMessage().contains("cannot repeat"));

    // Third offer — warning
    final var result3 = dom.offerDrawCorrectTime(Side.WHITE);
    assertFalse(result3.accepted());
    assertTrue(result3.arbiterMessage().contains("will lose"));

    // Fourth offer — game lost
    final var result4 = dom.offerDrawCorrectTime(Side.WHITE);
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

  /**
   * Claims while not having the move escalate instead of locking the buttons (teaching philosophy: the player may
   * repeat the fault): plain rejection, then a warning, then loss of the game on the third wrong-time claim.
   */
  @Test
  void testWrongTimeClaimEscalatesToGameLoss() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White has the move; BLACK claims. First: plain rejection, marked wrongTime (no button lock).
    // The opponent's arbiter window stays untouched; they see what happened as PASSIVE info.
    final DrawClaimResult first = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(first.accepted());
    assertTrue(first.wrongTime());
    assertEquals("You cannot claim a draw when not having the move.", first.message());
    assertTrue(first.opponentMessage().isEmpty()); // nothing action-relevant for the opponent
    // "Not considered", NOT "rejected": the claim never reached the rule machinery — only a
    // claim examined on the merits can be rejected.
    assertEquals("Your opponent claimed a draw while not having the move. The claim was not considered.",
        first.opponentInfo().get());
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // Second: same rejection plus the warning — the opponent's passive info mentions the warning.
    final DrawClaimResult second = session.claimDraw(Side.BLACK, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(second.wrongTime());
    assertTrue(second.message().contains("Warning: your next draw claim when not having the move loses the game"));
    assertTrue(second.opponentInfo().get().contains("been warned"));
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // Third: the game is lost — action-relevant, so it travels as the standard opponent message.
    final DrawClaimResult third = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(third.accepted());
    assertTrue(third.message().contains("you lose the game"));
    assertTrue(third.opponentMessage().get().contains("repeatedly requested to claim a draw"));
    assertTrue(third.opponentInfo().isEmpty());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.WRONG_TIME_CLAIM_GAME_LOST, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());

    // The game is over — any further claim is rejected on the state check, not counted again.
    final DrawClaimResult afterEnd = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertEquals("You cannot claim a draw now.", afterEnd.message());
  }

  /** The wrong-time claim count survives turn changes — a warning, once given, stands for the whole game. */
  @Test
  void testWrongTimeClaimCountPersistsAcrossTurns() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    final DrawClaimResult second = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(second.message().contains("Warning"));

    // Play a full move pair; Black's wrong-time count must not reset.
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN); // 1. e4
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN); // 1... e5

    // White has the move again; Black's third wrong-time claim loses the game.
    final DrawClaimResult third = session.claimDraw(Side.BLACK, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertFalse(third.accepted());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.WRONG_TIME_CLAIM_GAME_LOST, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
  }

  /**
   * The user-facing reference scenario for the cross-move accumulation (A-003): ONE wrong-time claim per (different)
   * White move — rejection on the first, warning on the second, loss on the third. The count never resets.
   */
  @Test
  void testWrongTimeClaimEscalationSpansSeparateMoves() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // While White is on move 1: Black's first wrong-time claim — plain rejection.
    final DrawClaimResult first = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(first.wrongTime());
    assertFalse(first.message().contains("Warning"));

    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN); // 1. e4
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN); // 1... e5

    // While White is on move 2: the second wrong-time claim — the warning.
    final DrawClaimResult second = session.claimDraw(Side.BLACK, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(second.wrongTime());
    assertTrue(second.message().contains("Warning"));
    assertEquals(GameState.IN_PROGRESS, session.getState());

    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT); // 2. Nf3
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT); // 2... Nf6

    // While White is on move 3: the third wrong-time claim — Black loses.
    final DrawClaimResult third = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(third.accepted());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.WRONG_TIME_CLAIM_GAME_LOST, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
  }

  /**
   * FIDE 9.4 / A-005: claiming after touching a piece on this move (here: the move is already made on the board but
   * the clock not yet pressed) escalates exactly like the wrong-time claims — rejection, warning, loss on the third.
   */
  @Test
  void testClaimAfterTouchEscalatesToGameLoss() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White drags e2-e4 on the board but does NOT press the clock — the touch forfeits the claim right.
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN,
        System.currentTimeMillis()));

    final DrawClaimResult first = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(first.accepted());
    assertTrue(first.wrongTime());
    assertTrue(first.message().contains("FIDE 9.4"));
    assertTrue(first.opponentMessage().isEmpty());
    assertEquals("Your opponent claimed a draw after touching a piece on this move. The claim was not considered.",
        first.opponentInfo().get());

    final DrawClaimResult second = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(second.wrongTime());
    assertTrue(second.message().contains("Warning: your next draw claim after touching a piece loses the game"));
    assertTrue(second.opponentInfo().get().contains("been warned"));
    assertEquals(GameState.IN_PROGRESS, session.getState());

    final DrawClaimResult third = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(third.message().contains("you lose the game"));
    assertTrue(third.opponentMessage().get().contains("repeatedly claimed a draw after touching a piece"));
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.CLAIM_AFTER_TOUCH_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
  }

  /** The after-touch count (A-005) accumulates across DIFFERENT moves, exactly like the wrong-time count. */
  @Test
  void testClaimAfterTouchCountSpansSeparateMoves() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Move 1: White drags e2-e4 (touch), claims -> plain rejection; then completes the move.
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E2, Square.E4, Piece.WHITE_PAWN,
        System.currentTimeMillis()));
    assertTrue(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null).wrongTime());
    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();
    session.pressClockButton(Side.WHITE, afterE4); // 1. e4
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN); // 1... e5

    // Move 2: same fault on a NEW move -> the warning (count persisted).
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.G1, Square.F3, Piece.WHITE_KNIGHT,
        System.currentTimeMillis()));
    final DrawClaimResult second = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(second.message().contains("Warning"));
    final BitboardPosition afterNf3 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.G1, Piece.NONE).createChangedPosition(Square.F3, Piece.WHITE_KNIGHT).build();
    session.pressClockButton(Side.WHITE, afterNf3); // 2. Nf3
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT); // 2... Nf6

    // Move 3: the third after-touch claim -> White loses.
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.B1, Square.C3, Piece.WHITE_KNIGHT,
        System.currentTimeMillis()));
    final DrawClaimResult third = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(third.accepted());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.CLAIM_AFTER_TOUCH_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
  }

  /** All four claim buttons escalate the same way; for the with-move types any SAN is irrelevant. */
  @Test
  void testWrongTimeClaimAppliesToWithMoveTypesRegardlessOfSan() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // A with-move claim while not having the move is rejected before any SAN handling — with a
    // valid SAN, an invalid SAN, or none at all.
    final DrawClaimResult withSan = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_WITH_MOVE, "Nf6");
    assertTrue(withSan.wrongTime());
    assertFalse(withSan.invalidMove()); // the SAN was never looked at

    final DrawClaimResult withInvalidSan = session.claimDraw(Side.BLACK, DrawClaimType.FIFTY_MOVE_WITH_MOVE, "Zz9");
    assertTrue(withInvalidSan.wrongTime());
    assertFalse(withInvalidSan.invalidMove());
    assertTrue(withInvalidSan.message().contains("Warning")); // and both presses counted

    final DrawClaimResult withoutSan = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_WITH_MOVE, null);
    assertEquals(GameState.ENDED, session.getState()); // third press — game lost
    assertEquals(GameResultType.WRONG_TIME_CLAIM_GAME_LOST, session.getResult().type());
    assertFalse(withoutSan.wrongTime()); // the game-ending response carries the loss messages instead
  }

  /**
   * A wrong-time claim is a private procedural mistake: no FIDE 9.5.3 two-minute penalty for the opponent and no
   * conversion into a draw offer (both apply only to completed on-move claims).
   */
  @Test
  void testWrongTimeClaimGivesNoPenaltyAndNoDrawOffer() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final DrawClaimResult result = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);

    assertTrue(result.wrongTime());
    assertFalse(result.convertsToDrawOffer());
    // No 2-minute penalty credited to White: the remaining time can only have ticked DOWN from the
    // initial allotment (a penalty would have pushed it above it).
    assertTrue(session.getClock().getRemainingTimeMs(Side.WHITE) <= TEST_TIME.initialTimeMs());
  }

  /** A wrong-time claim must not burn the once-per-move claim right for when the player IS on move. */
  @Test
  void testWrongTimeClaimDoesNotConsumeOnMoveClaimRight() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Black claims while White has the move — rejected as wrong-time.
    assertTrue(session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null).wrongTime());

    // White plays 1. e4; Black now HAS the move and claims — processed as a normal (on-move)
    // claim: rejected on the merits (no repetition), converted into a draw offer per FIDE 9.5.
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    final DrawClaimResult onMove = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(onMove.wrongTime());
    assertFalse(onMove.accepted());
    assertTrue(onMove.convertsToDrawOffer());
  }

  /** Wrong-time claims are counted per player — one player's warning does not carry over to the other. */
  @Test
  void testWrongTimeClaimCountsArePerPlayer() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Black reaches the warning (two wrong-time claims while White is on move).
    session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null).message().contains("Warning"));

    // After 1. e4 it is Black's move; White's first wrong-time claim gets the PLAIN rejection —
    // Black's count is Black's alone.
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    final DrawClaimResult whiteFirst = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(whiteFirst.wrongTime());
    assertEquals("You cannot claim a draw when not having the move.", whiteFirst.message());
  }

  // ===== Wrong-time draw offers (A-001): per-move escalation =====

  /**
   * A-001 ladder within ONE move: the first wrong-time offer is a real offer; the second is not considered (not
   * forwarded) and carries the warning; the third loses the game.
   */
  @Test
  void testWrongTimeOfferEscalatesWithinTheMove() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame(); // White to move — Black's offers are wrong-time

    final var first = session.offerDrawWrongTime(Side.BLACK);
    assertTrue(first.accepted()); // a REAL offer — forwarded, the opponent can accept it
    assertTrue(first.arbiterMessage().contains("The offer still counts as a draw offer"));
    assertTrue(session.getDrawOfferManager().isDrawOffered());

    // The opponent rejects; Black offers again on the same move: NOT considered, warned.
    session.rejectDraw(Side.WHITE);
    final var second = session.offerDrawWrongTime(Side.BLACK);
    assertFalse(second.accepted());
    assertFalse(second.gameLost());
    assertTrue(second.arbiterMessage().contains("This offer was not considered"));
    assertTrue(second.arbiterMessage().contains("Warning: your next draw offer on this move loses the game"));
    assertTrue(second.opponentInfo().contains("not considered"));
    assertTrue(second.opponentInfo().contains("been warned"));
    assertFalse(session.getDrawOfferManager().isDrawOffered()); // nothing was forwarded
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // Third on the same move: the game is lost.
    final var third = session.offerDrawWrongTime(Side.BLACK);
    assertTrue(third.gameLost());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.WRONG_TIME_OFFER_GAME_LOST, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
    assertEquals(Side.BLACK, session.getTerminationActor());
    assertEquals("Black loses the game by repeatedly offering a draw at the wrong time.",
        session.getResult().description());
  }

  /**
   * Unlike the claim ladders, the wrong-time OFFER count is per move (an offer is only semi-illegal): after a move
   * pair the first wrong-time offer of the new move is a real offer again — no carried-over warning.
   */
  @Test
  void testWrongTimeOfferCountResetsEveryMove() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    session.offerDrawWrongTime(Side.BLACK);
    session.rejectDraw(Side.WHITE);
    assertTrue(session.offerDrawWrongTime(Side.BLACK).arbiterMessage().contains("Warning")); // warned on this move

    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN); // 1. e4
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN); // 1... e5

    // White is on move again; Black's wrong-time offer is the FIRST of this move — real again.
    final var fresh = session.offerDrawWrongTime(Side.BLACK);
    assertTrue(fresh.accepted());
    assertTrue(fresh.arbiterMessage().contains("The offer still counts as a draw offer"));
    assertTrue(session.getDrawOfferManager().isDrawOffered());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  // ===== Clock press without a move (FIDE 7.5.3) =====

  /**
   * FIDE 7.5.3: pressing the clock without making a move is penalised as an illegal move — the opponent gets the
   * standard penalty time, the count escalates, and with the default limit of two the second press loses the game.
   * Nothing needs restoring, so the mover's clock keeps running.
   */
  @Test
  void testTwoClockPressesWithoutMoveLoseTheGame() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame(); // White's clock runs

    final ArbiterResponse first = session.pressClockButton(Side.WHITE, session.getBoard().getBitboardPosition());
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, first.type());
    assertTrue(first.renderedPlayerMessage().contains("FIDE 7.5.3"));
    // Standard illegal-move penalty credited to Black; White's clock keeps running (no restore).
    assertTrue(session.getClock().getRemainingTimeMs(Side.BLACK) > TEST_TIME.initialTimeMs());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
    assertEquals(GameState.IN_PROGRESS, session.getState());

    final ArbiterResponse second = session.pressClockButton(Side.WHITE, session.getBoard().getBitboardPosition());
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, second.type());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.ILLEGAL_MOVE_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
    assertTrue(session.getResult().description().contains("White loses the game"));
  }

  // ===== Moving an opponent's piece (A-007) =====

  /** The A-007 ladder: notice + restore, notice + warning + restore, loss on the third moved opponent piece. */
  @Test
  void testMovedOpponentPieceEscalatesToGameLoss() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame(); // White to move

    // First time: the arbiter intervenes (clock paused, restore required); Black is informed
    // passively via the pending opponent info.
    final Optional<ArbiterResponse> first = session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.E7, Square.E5, Piece.BLACK_PAWN, System.currentTimeMillis()));
    assertTrue(first.isPresent());
    assertEquals(ArbiterResponseType.POSITION_CHANGE, first.get().type());
    assertTrue(first.get().renderedPlayerMessage().contains("You moved an opponent"));
    assertFalse(first.get().renderedPlayerMessage().contains("Warning"));
    assertEquals(Side.NONE, session.getClock().getRunningFor()); // paused
    final String firstInfo = session.consumePendingOpponentInfo();
    assertTrue(firstInfo.contains("moved one of your pieces"));
    assertNull(session.consumePendingOpponentInfo()); // consumed exactly once
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // Second time: same, plus the warning.
    final Optional<ArbiterResponse> second = session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.D7, Square.D5, Piece.BLACK_PAWN, System.currentTimeMillis()));
    assertTrue(second.get().renderedPlayerMessage()
        .contains("Warning: the next time you move an opponent's piece, you lose the game"));
    assertTrue(session.consumePendingOpponentInfo().contains("been warned"));
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // Third time: the game is lost.
    final Optional<ArbiterResponse> third = session.recordEvent(Side.WHITE,
        BoardEvent.dragMove(Square.G8, Square.F6, Piece.BLACK_KNIGHT, System.currentTimeMillis()));
    assertTrue(third.isPresent());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.MOVED_OPPONENT_PIECE_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
    assertEquals(Side.WHITE, session.getTerminationActor());
    assertEquals("White loses the game by repeatedly moving the opponent's pieces.",
        session.getResult().description());
  }

  // ===== Wrong clock press (pressing the opponent's clock) =====

  /** Pressing the opponent's lever while it is already DOWN (their clock not running) is a physical no-op. */
  @Test
  void testWrongClockPressIsNoOpWhenOpponentClockNotRunning() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame(); // White's clock runs

    // White presses BLACK's lever — Black's clock is not running, the lever is down: nothing.
    final GameSession.WrongClockPressOutcome outcome = session.pressOpponentClock(Side.WHITE);
    assertFalse(outcome.offense());
    assertEquals(Side.WHITE, session.getClock().getRunningFor()); // clock untouched
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  /** The A-006 ladder: pause + admonishment, pause + warning, loss on the third press. */
  @Test
  void testWrongClockPressEscalatesToGameLoss() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame(); // White's clock runs — BLACK pressing it is the offense

    // First press: the arbiter pauses the game and admonishes; the opponent is informed passively.
    final GameSession.WrongClockPressOutcome first = session.pressOpponentClock(Side.BLACK);
    assertTrue(first.offense());
    assertFalse(first.gameLost());
    assertTrue(first.message().contains("Please do not press your opponent's clock"));
    assertFalse(first.message().contains("Warning"));
    assertTrue(first.opponentInfo().contains("pressed your clock"));
    assertEquals(Side.NONE, session.getClock().getRunningFor()); // paused

    // The arbiter restarts the interrupted clock (White's) after the pause.
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());

    // Second press: same pause, plus the warning.
    final GameSession.WrongClockPressOutcome second = session.pressOpponentClock(Side.BLACK);
    assertTrue(second.message().contains("Warning: the next press of your opponent's clock loses the game"));
    assertTrue(second.opponentInfo().contains("been warned"));
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress());

    // Third press: the game is lost.
    final GameSession.WrongClockPressOutcome third = session.pressOpponentClock(Side.BLACK);
    assertTrue(third.gameLost());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.WRONG_CLOCK_PRESS_GAME_LOST, session.getResult().type());
    assertEquals(Side.WHITE, session.getResult().winner());
    assertEquals(Side.BLACK, session.getTerminationActor());
    assertEquals("Black loses the game by repeatedly pressing the opponent's clock.",
        session.getResult().description());
  }

  /** During the admonishment pause nothing runs — further presses are physical no-ops, not extra offenses. */
  @Test
  void testWrongClockPressDuringPauseIsNoOp() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    assertTrue(session.pressOpponentClock(Side.BLACK).offense()); // pause active now
    assertFalse(session.pressOpponentClock(Side.BLACK).offense()); // no clock running -> no-op
    assertFalse(session.pressOpponentClock(Side.BLACK).offense());

    // Resume, then the NEXT real press is offense #2 (the pause presses were not counted).
    assertEquals(Side.WHITE, session.resumeAfterWrongClockPress());
    final GameSession.WrongClockPressOutcome second = session.pressOpponentClock(Side.BLACK);
    assertTrue(second.offense());
    assertTrue(second.message().contains("Warning"));
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  /** The resume helper is a safe no-op when no wrong-clock pause is active. */
  @Test
  void testResumeAfterWrongClockPressWithoutPauseIsNoOp() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    assertNull(session.resumeAfterWrongClockPress());
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  // ===== Abandonment (player left the game) =====

  /** Abandonment is adjudicated like a resignation: the leaver loses when the opponent can still mate. */
  @Test
  void testAbandonAdjudicatesLossWhenOpponentCanMate() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final GameResult result = session.abandon(Side.BLACK);

    assertEquals(GameResultType.ABANDONMENT, result.type());
    assertEquals(Side.WHITE, result.winner());
    assertEquals("Black left the game. White wins the game.", result.description());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(Side.BLACK, session.getTerminationActor());
  }

  /**
   * FIDE 5.1.2-style exception, as chess servers apply it to abandonment: when the REMAINING player could not
   * checkmate by any series of legal moves (here: a lone king), the abandoned game is a draw, not a loss.
   */
  @Test
  void testAbandonAdjudicatesDrawWhenOpponentCannotMate() {
    // White has only the king; Black (who leaves) has king + queen. White cannot possibly mate.
    final GameSession session = new GameSession(TEST_TIME, 2, true,
        Board.fromFenStrict("4k3/8/8/3q4/8/8/8/4K3 w - - 0 1"));
    session.startGame();

    final GameResult result = session.abandon(Side.BLACK);

    assertEquals(GameResultType.ABANDONMENT, result.type());
    assertEquals(Side.NONE, result.winner());
    assertTrue(result.description().contains("Black left the game"));
    assertTrue(result.description().contains("the game is a draw"));
    assertTrue(session.isDrawExceptionByInsufficientMaterial()); // lone king = insufficient material
    assertEquals(GameState.ENDED, session.getState());
  }

  /** Abandonment of a game that is not running adjudicates nothing (ended games stay as they ended). */
  @Test
  void testAbandonIsNoOpWhenGameNotRunning() {
    final GameSession session = new GameSession(TEST_TIME);
    // Not started yet.
    assertEquals(null, session.abandon(Side.BLACK));

    session.startGame();
    session.resign(Side.WHITE);
    assertEquals(GameState.ENDED, session.getState());
    // Already decided — the resignation result stands.
    assertEquals(null, session.abandon(Side.BLACK));
    assertEquals(GameResultType.RESIGNATION, session.getResult().type());
  }

  /**
   * Plays an eight-half-move knight shuffle (Nf3 Nf6 Ng1 Ng8 ×2) so the initial position has occurred 3 times. White is
   * to move. From here white's `Nf3` would create the 3rd occurrence of position-after-1.Nf3 ⇒
   * `canClaimThreefoldRepetitionRuleWithOwnMove()` is true. This keeps the with-move short-circuit from firing and lets
   * the tests exercise the per-move SAN-validation / rejectedWithMove paths.
   */
  private void shuffleKnightsToReachThreefoldClaimable(GameSession session) {
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT);
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT);
    makeMove(session, Square.F3, Square.G1, Piece.WHITE_KNIGHT);
    makeMove(session, Square.F6, Square.G8, Piece.BLACK_KNIGHT);
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT);
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT);
    makeMove(session, Square.F3, Square.G1, Piece.WHITE_KNIGHT);
    makeMove(session, Square.F6, Square.G8, Piece.BLACK_KNIGHT);
  }

  @Test
  void testMustExecuteMoveAfterRejectedClaim() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    shuffleKnightsToReachThreefoldClaimable(session);

    // White claims threefold with move "e4". The move is legal; the resulting position has
    // never occurred — so the per-move check rejects with mustExecuteMove="e4".
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");
    assertFalse(result.accepted());
    assertTrue(result.moveToPerform().isPresent());
    assertNotNull(session.getMustExecuteMove());

    // Now white must play e4.
    final BitboardPosition afterE4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.E2, Piece.NONE).createChangedPosition(Square.E4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterE4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, response.type());
    assertNull(session.getMustExecuteMove());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void testClaimWithInvalidSanIsRejectedAsInvalidMove() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    shuffleKnightsToReachThreefoldClaimable(session);

    // SAN "e9" is structurally invalid (no rank 9). Ashlar Chess rejects it; we surface the
    // reason via invalidMove so the SAN-input panel re-prompts.
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e9");

    assertFalse(result.accepted());
    assertTrue(result.invalidMove(),
        "Illegal SAN must be reported via the invalidMove flag, not as a regular rejection");
    assertTrue(result.moveToPerform().isEmpty());
    assertTrue(result.message().startsWith("The move 'e9' is invalid:"),
        "Message should surface the Ashlar Chess validation reason: " + result.message());
    assertFalse(result.message().contains("lenient SAN parser"));

    // Game state is unchanged: no must-execute move was set, white still has the move.
    assertNull(session.getMustExecuteMove());
    assertEquals(Side.WHITE, session.getHavingMove());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  @Test
  void testFiftyMoveClaimWithInvalidSanIsRejectedAsInvalidMove() {
    // Custom FEN with halfMoveClock = 99, white to move, K+R vs K. Any non-capture
    // non-pawn move advances the clock to 100 ⇒ canClaimFiftyMoveRuleWithOwnMove() == true,
    // so the short-circuit does not fire and the SAN-validation path is reachable.
    final Board startingBoard = Board.fromFenStrict("4k3/8/8/8/3R4/8/8/4K3 w - - 99 51");
    final GameSession session = new GameSession(TEST_TIME,
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true, startingBoard);
    session.startGame();

    // SAN "Kz9" is structurally invalid.
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_WITH_MOVE, "Kz9");

    assertFalse(result.accepted());
    assertTrue(result.invalidMove());
    assertTrue(result.moveToPerform().isEmpty());
    assertNull(session.getMustExecuteMove());
    assertEquals(Side.WHITE, session.getHavingMove());
  }

  @Test
  void testIncompleteCastlingCanContinueWithoutRestoration() {
    final Board startingBoard = Board.fromFenStrict("4k3/8/8/8/8/8/8/4K2R w K - 0 1");
    final GameSession session = new GameSession(TEST_TIME,
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true, startingBoard);
    session.startGame();

    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.E1, Square.G1, Piece.WHITE_KING, 0));
    final BitboardPosition kingOnly = BitboardPositions.from(startingBoard.getBitboardPosition())
        .createChangedPosition(Square.E1, Piece.NONE).createChangedPosition(Square.G1, Piece.WHITE_KING).build();

    final ArbiterResponse incomplete = session.pressClockButton(Side.WHITE, kingOnly);

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, incomplete.type());
    assertEquals(kingOnly, incomplete.restorePosition().get());
    assertEquals(Side.NONE, session.getClock().getRunningFor());

    session.continueWithoutRestoration();

    assertEquals(Side.WHITE, session.getClock().getRunningFor());
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(Square.H1, Square.F1, Piece.WHITE_ROOK, 1));
    final BitboardPosition castled = BitboardPositions.from(kingOnly).createChangedPosition(Square.H1, Piece.NONE)
        .createChangedPosition(Square.F1, Piece.WHITE_ROOK).build();

    final ArbiterResponse accepted = session.pressClockButton(Side.WHITE, castled);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, accepted.type());
    assertEquals(Side.BLACK, session.getHavingMove());
  }

  @Test
  void testMustExecuteMoveWrongPosition() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    shuffleKnightsToReachThreefoldClaimable(session);

    // White claims threefold with move "e4" — legal, doesn't trigger threefold ⇒ rejectedWithMove.
    session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");

    // White plays d4 instead of e4 — the must-execute-move is still e4, so this fails as
    // INCOMPLETE_MOVE.
    final BitboardPosition afterD4 = BitboardPositions.from(session.getBoard().getBitboardPosition())
        .createChangedPosition(Square.D2, Piece.NONE).createChangedPosition(Square.D4, Piece.WHITE_PAWN).build();

    final ArbiterResponse response = session.pressClockButton(Side.WHITE, afterD4);
    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertTrue(response.message().contains("was not executed"));
    // The message now names the move the player still owes.
    assertTrue(response.message().contains("e4"), "Message should name the specified move: " + response.message());
  }

  @Test
  void testClaimWithMoveAcceptsLenientSanAndNamesTheMove() {
    // Strict SAN rejects the spurious "+" (Rd1 is not check); the lenient parser forgives it.
    // The accepted-claim message names the move the player entered.
    final Board startingBoard = Board.fromFenStrict("4k3/8/8/8/3R4/8/8/4K3 w - - 99 51");
    final GameSession session = new GameSession(TEST_TIME,
        io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES, true, startingBoard);
    session.startGame();

    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_WITH_MOVE, "Rd1+");

    assertTrue(result.accepted(), "Lenient SAN should forgive the spurious check mark: " + result.message());
    assertFalse(result.invalidMove());
    assertTrue(result.message().contains("Rd1+"), "Claimant message should name the move: " + result.message());
    assertEquals(GameState.ENDED, session.getState());
  }

  /**
   * When no move from the current position can possibly create a threefold repetition, the with-move claim
   * short-circuits with a generic "no move could satisfy" rejection BEFORE the SAN is even validated. The player's SAN
   * is not tested for legality (no invalidMove flag set), and no must-execute-move is established — the player is free
   * to play any legal move.
   */
  @Test
  void testThreefoldClaimWithMoveShortCircuitsWhenImpossibleFromCurrentPosition() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    // From the initial position, no legal move can possibly produce a threefold repetition.
    // The SAN ("e4") is legal, so it passes SAN validation; the short-circuit then fires
    // on the impossibility of ever reaching threefold and rejects the claim without
    // performing the move.
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e4");

    assertFalse(result.accepted());
    assertFalse(result.invalidMove());
    assertTrue(result.moveToPerform().isEmpty());
    assertTrue(result.message().contains("no move from the current position can lead to" + " a threefold repetition"));
    assertNull(session.getMustExecuteMove());
    assertEquals(Side.WHITE, session.getHavingMove());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  @Test
  void testFiftyMoveClaimWithMoveShortCircuitsWhenClockIsBelowThreshold() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    // Half-move clock 0; canClaimFiftyMoveRuleWithOwnMove() requires 99+. SAN ("e4") is
    // legal, so SAN validation passes and the short-circuit then rejects the claim.
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_WITH_MOVE, "e4");

    assertFalse(result.accepted());
    assertFalse(result.invalidMove());
    assertTrue(result.moveToPerform().isEmpty());
    assertTrue(result.message().contains("no move from the current position can satisfy" + " the 50-move rule"));
    assertNull(session.getMustExecuteMove());
  }

  /**
   * SAN validation must precede the short-circuit: even when no move could satisfy the claim, an invalid SAN is
   * reported as invalidMove first so the player can correct it.
   */
  @Test
  void testInvalidSanReportedBeforeShortCircuitForThreefold() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e9");

    assertFalse(result.accepted());
    assertTrue(result.invalidMove());
    assertTrue(result.message().startsWith("The move 'e9' is invalid:"));
    assertFalse(result.message().contains("lenient SAN parser"));
  }

  @Test
  void testInvalidSanReportedBeforeShortCircuitForFiftyMove() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_WITH_MOVE, "Kz9");

    assertFalse(result.accepted());
    assertTrue(result.invalidMove());
    assertTrue(result.message().startsWith("The move 'Kz9' is invalid:"));
    assertFalse(result.message().contains("lenient SAN parser"));
  }

  @Test
  void testAcceptedClaimCarriesShortGameEndDescriptionAndPerPlayerMessages() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT);
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT);
    makeMove(session, Square.F3, Square.G1, Piece.WHITE_KNIGHT);
    makeMove(session, Square.F6, Square.G8, Piece.BLACK_KNIGHT);
    makeMove(session, Square.G1, Square.F3, Piece.WHITE_KNIGHT);
    makeMove(session, Square.G8, Square.F6, Piece.BLACK_KNIGHT);
    makeMove(session, Square.F3, Square.G1, Piece.WHITE_KNIGHT);
    makeMove(session, Square.F6, Square.G8, Piece.BLACK_KNIGHT);
    // Now P0 has occurred 3 times. White can claim threefold-on-board.
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(result.accepted());

    // Claimer's arbiter line is short and player-centric; opponent gets a separate notice.
    assertEquals("Your claim was accepted.", result.message());
    assertTrue(result.opponentMessage().isPresent());
    assertTrue(result.opponentMessage().get().contains("threefold repetition"));

    // The game-result panel uses the short termination description, NOT the long claim text.
    assertEquals("The game is drawn by threefold repetition.", session.getResult().description());
  }

  /**
   * FIDE 9.2/9.3 allow one claim per move; repeats escalate instead of locking the buttons (teaching philosophy):
   * warning on the first repeat (the legitimate claim was already used), loss of the game on the next.
   */
  @Test
  void testRepeatClaimOnSameMoveEscalatesToGameLoss() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    // First claim from the initial position is rejected on the merits (no threefold) and is the
    // one legitimate claim for this move.
    final DrawClaimResult first = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(first.accepted());
    assertFalse(first.repeatClaim());

    // Second claim on the same move: rejected with the warning. Nothing action-relevant for the
    // opponent (no arbiter-window message), but they see what happened as passive info.
    final DrawClaimResult second = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertFalse(second.accepted());
    assertTrue(second.repeatClaim());
    assertTrue(second.message().contains("You cannot make more than one draw claim on your move"));
    assertTrue(second.message().contains("You are warned"));
    assertTrue(second.opponentMessage().isEmpty());
    assertTrue(second.opponentInfo().get().contains("second draw claim on the same move"));
    assertTrue(second.opponentInfo().get().contains("The claim was not considered"));
    assertTrue(second.opponentInfo().get().contains("been warned"));
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // Third claim: the game is lost.
    final DrawClaimResult third = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(third.accepted());
    assertTrue(third.message().contains("you lose the game"));
    assertTrue(third.opponentMessage().get().contains("repeatedly claimed a draw on the same move"));
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.REPEAT_CLAIM_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
  }

  /**
   * The legitimate first claim already applied the FIDE 9.5.3 penalty and registered the draw offer; the repeat
   * violation is purely procedural — no second two-minute penalty and no second offer conversion.
   */
  @Test
  void testRepeatClaimAddsNoSecondPenaltyAndNoSecondOffer() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // White's legitimate (rejected) claim: Black gets the one-time 2-minute penalty credit.
    session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    final long blackTimeAfterFirst = session.getClock().getRemainingTimeMs(Side.BLACK);
    assertTrue(blackTimeAfterFirst > TEST_TIME.initialTimeMs()); // penalty applied once

    final DrawClaimResult repeat = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(repeat.repeatClaim());
    assertFalse(repeat.convertsToDrawOffer());
    // No further penalty: Black's remaining time cannot have grown again.
    assertTrue(session.getClock().getRemainingTimeMs(Side.BLACK) <= blackTimeAfterFirst);
  }

  /**
   * A-003 (wrong-time) and A-004 (repeat on same move) are separate ladders: warnings on one never advance the other.
   */
  @Test
  void testWrongTimeAndRepeatClaimCountersAreIndependent() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();

    // Black reaches the WRONG-TIME warning while White is on move (two wrong-time claims).
    session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null).message().contains("Warning"));

    // After 1. e4 Black IS on move: a legitimate claim, then a repeat — the repeat must get the
    // REPEAT warning (first A-004 violation), not an A-003 game loss.
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN);
    assertFalse(session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null).repeatClaim());
    final DrawClaimResult repeat = session.claimDraw(Side.BLACK, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertTrue(repeat.repeatClaim());
    assertTrue(repeat.message().contains("more than one draw claim"));
    assertEquals(GameState.IN_PROGRESS, session.getState()); // no cross-ladder loss
  }

  /**
   * The repeat-claim warning persists across turns, but a legitimate single claim on a later move is never a
   * violation — only ANOTHER repeat after the warning loses the game.
   */
  @Test
  void testRepeatClaimWarningPersistsButLegitimateClaimsStayAllowed() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    final DrawClaimResult warned = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertTrue(warned.repeatClaim()); // White is now warned

    // Play a move pair; on White's next move a SINGLE claim is legitimate — no loss.
    makeMove(session, Square.E2, Square.E4, Piece.WHITE_PAWN); // 1. e4
    makeMove(session, Square.E7, Square.E5, Piece.BLACK_PAWN); // 1... e5
    final DrawClaimResult legit = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(legit.repeatClaim());
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // But a repeat on THIS move is the second violation — game lost.
    final DrawClaimResult fatal = session.claimDraw(Side.WHITE, DrawClaimType.FIFTY_MOVE_ON_BOARD, null);
    assertFalse(fatal.accepted());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.REPEAT_CLAIM_GAME_LOST, session.getResult().type());
    assertEquals(Side.BLACK, session.getResult().winner());
  }

  @Test
  void testRejectedClaimRegistersDrawOfferToOpponent() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    assertFalse(result.accepted());
    assertTrue(result.convertsToDrawOffer());
    // The session has registered a pending draw offer that the opponent can accept/reject.
    assertTrue(session.getDrawOfferManager().isDrawOffered());
    assertEquals(Side.WHITE, session.getDrawOfferManager().getOfferingSide());
  }

  @Test
  void testInvalidSanDoesNotLockClaimsForThisTurn() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    // Invalid SAN on a with-move claim does not constitute a completed claim attempt — the
    // player is re-prompted and may try another claim with a legal SAN.
    final DrawClaimResult invalid = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e9");
    assertTrue(invalid.invalidMove());

    final DrawClaimResult onBoard = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);
    // A normal rejection (the position has not occurred 3 times), NOT the
    // "already-made-a-claim" lock.
    assertFalse(onBoard.accepted());
    assertFalse(onBoard.message().contains("already made a draw claim"));
  }

  /**
   * FIDE 9.4: a player loses the right to claim under 9.2/9.3 once any piece has been touched on this move. The session
   * must reject claims after a CLICK, DRAG_*, or REMOVE event in the current turn, before processing the claim.
   */
  @Test
  void testClaimAfterTouchingPieceIsRejectedPerFide94() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    shuffleKnightsToReachThreefoldClaimable(session);

    // White touches a piece (CLICK on own knight) before attempting a claim.
    session.recordEvent(Side.WHITE, BoardEvent.click(Square.G1, Piece.WHITE_KNIGHT, System.currentTimeMillis()));

    // The position has actually occurred three times (knight shuffle reached threefold)
    // so without the 9.4 check the claim would succeed. With the check, it must be rejected.
    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);

    assertFalse(result.accepted(),
        "FIDE 9.4: claim must be rejected after touching a piece, even if position is repeated");
    assertTrue(result.message().contains("FIDE 9.4"),
        "Rejection message should reference Article 9.4: " + result.message());
    assertEquals(GameState.IN_PROGRESS, session.getState());
  }

  /**
   * FIDE 9.5.3: an incorrect (i.e. completed but rejected) draw claim adds 2 minutes to the opponent's clock. Applies
   * to rejected on-board and claim-with-move attempts; does NOT apply to invalid-SAN cases (the player can re-prompt
   * with a correct SAN).
   */
  @Test
  void testRejectedClaimAddsTwoMinutePenaltyToOpponentPerFide953() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    // From the standard initial position, threefold has not occurred. Any claim here
    // is incorrect and should incur the FIDE 9.5.3 penalty.
    final long blackBefore = session.getClock().getRemainingTimeMs(Side.BLACK);

    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_ON_BOARD, null);

    assertFalse(result.accepted(), "Threefold has not occurred — the claim must be rejected");
    assertFalse(result.invalidMove(), "It is a completed claim attempt, not invalid SAN");

    final long blackAfter = session.getClock().getRemainingTimeMs(Side.BLACK);
    final long delta = blackAfter - blackBefore;
    // Allow a small tolerance window: clock ticks during the test add no time to BLACK
    // (BLACK isn't running), but assertion stays robust if implementation drifts a few ms.
    assertTrue(delta >= 119_000 && delta <= 121_000,
        "FIDE 9.5.3: opponent should gain ~2 minutes (120000 ms); actual delta = " + delta);
  }

  /**
   * Invalid-SAN claims do NOT trigger the FIDE 9.5.3 penalty — the player has not actually completed a claim; they can
   * re-prompt with a correct SAN.
   */
  @Test
  void testInvalidSanClaimDoesNotTriggerNineFiveThreePenalty() {
    final GameSession session = new GameSession(TEST_TIME);
    session.startGame();
    shuffleKnightsToReachThreefoldClaimable(session);

    final long blackBefore = session.getClock().getRemainingTimeMs(Side.BLACK);

    final DrawClaimResult result = session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "e9");

    assertTrue(result.invalidMove(), "Invalid SAN must surface as invalidMove, not as a rejection");

    final long blackAfter = session.getClock().getRemainingTimeMs(Side.BLACK);
    // Black's clock isn't running and no penalty fires, so any difference must be tiny.
    assertTrue(Math.abs(blackAfter - blackBefore) < 1_000,
        "Invalid SAN must not penalise the opponent; delta = " + (blackAfter - blackBefore));
  }
}
