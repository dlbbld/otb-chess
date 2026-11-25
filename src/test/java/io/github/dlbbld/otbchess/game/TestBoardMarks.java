// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.arbiter.BoardMark;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * The squares the arbiter points at with a ruling on a clock press: green for the piece a touch obliges the player
 * to move or capture, red for the start and end of an illegal move - in the clear cases only.
 */
class TestBoardMarks {

  /** After 1. e4 d5: White can capture on d5. */
  private static final String CAPTURE_ON_D5 = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2";
  private static final String BOTH_CASTLINGS = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1";
  /** The knight on f3 gives check: White cannot castle. */
  private static final String WHITE_IN_CHECK = "r3k2r/pppppppp/8/8/8/5n2/PPPPP1PP/R3K2R w KQkq - 0 1";

  private long time;

  private static GameSession session(String fen) {
    final var session = fen == null ? new GameSession(new TimeControl(60000, 0), 3, true)
        : new GameSession(new TimeControl(60000, 0), 3, true, Board.fromFenStrict(fen));
    session.startGame();
    return session;
  }

  /** The turn-start position with pieces moved: triples of piece, from, to. */
  private static BitboardPosition moved(GameSession session, Object... moves) {
    var builder = BitboardPositions.from(session.getPositionBeforeTurn());
    for (int i = 0; i < moves.length; i += 3) {
      builder = builder.createChangedPosition((Square) moves[i + 1], Piece.NONE)
          .createChangedPosition((Square) moves[i + 2], (Piece) moves[i]);
    }
    return builder.build();
  }

  private void drag(GameSession session, Piece piece, Square from, Square to) {
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(from, to, piece, time++));
  }

  private void click(GameSession session, Piece piece, Square square) {
    session.recordEvent(Side.WHITE, BoardEvent.click(square, piece, time++));
  }

  /** What the server does with a ruling that asks for a restoration, up to the restarted clock. */
  private static void revertAndResume(GameSession session, ArbiterResponse ruling) {
    session.enterWaitingForRestoration(ruling.restorePosition().orElse(session.getPositionBeforeTurn()));
    assertTrue(session.completeRestoration());
    assertTrue(session.resumeAfterRestorationDelay());
  }

  /** White plays e2-e4 after whatever was touched before, and presses the clock. */
  private ArbiterResponse pawnToE4AndClock(GameSession session) {
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E4);
    return session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4));
  }

  private static List<BoardMark> green(Square... squares) {
    return List.of(squares).stream().map(BoardMark::green).toList();
  }

  private static List<BoardMark> red(Square... squares) {
    return List.of(squares).stream().map(BoardMark::red).toList();
  }

  // ----- green: the piece that must be moved -----

  @Test
  void aTouchedPieceThatWasNotMovedIsMarkedGreenUntilTheMoveIsMade() {
    final var session = session(null);
    assertEquals(List.of(), session.getBoardMarks());
    click(session, Piece.WHITE_KNIGHT, Square.G1);

    final ArbiterResponse ruling = pawnToE4AndClock(session);

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(green(Square.G1), ruling.boardMarks());
    assertEquals(green(Square.G1), session.getBoardMarks());

    // The obligation outlives the restoration, and so does the circle.
    revertAndResume(session, ruling);
    assertEquals(green(Square.G1), session.getBoardMarks());

    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3)).type());
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void ofSeveralTouchedOwnPiecesOnlyTheFirstIsMarked() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    click(session, Piece.WHITE_KNIGHT, Square.B1);
    click(session, Piece.WHITE_PAWN, Square.A2);

    pawnToE4AndClock(session);

    assertEquals(green(Square.G1), session.getBoardMarks());
  }

  @Test
  void aTouchedPieceThatCannotMoveIsNotMarkedButTheFirstOneThatCanIs() {
    final var session = session(null);
    click(session, Piece.WHITE_ROOK, Square.A1); // no legal move
    click(session, Piece.WHITE_KNIGHT, Square.B1);

    pawnToE4AndClock(session);

    assertEquals(green(Square.B1), session.getBoardMarks());
  }

  @Test
  void aTouchedPieceThatCannotMoveAloneGivesNoRulingAndNoMark() {
    final var session = session(null);
    click(session, Piece.WHITE_ROOK, Square.A1);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, pawnToE4AndClock(session).type());
    assertEquals(List.of(), session.getBoardMarks());
  }

  // ----- green: the piece that must be captured -----

  @Test
  void aTouchedOpponentPieceThatCanBeCapturedIsMarkedGreen() {
    final var session = session(CAPTURE_ON_D5);
    click(session, Piece.BLACK_PAWN, Square.D5);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3));

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(green(Square.D5), session.getBoardMarks());
  }

  @Test
  void ofSeveralTouchedOpponentPiecesOnlyTheFirstThatCanBeCapturedIsMarked() {
    final var session = session(CAPTURE_ON_D5);
    click(session, Piece.BLACK_PAWN, Square.A7); // cannot be captured
    click(session, Piece.BLACK_PAWN, Square.D5);
    click(session, Piece.BLACK_QUEEN, Square.D8);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);

    session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3));

    assertEquals(green(Square.D5), session.getBoardMarks());
  }

  @Test
  void anOwnPieceAndTheOpponentPieceItCanCaptureAreBothMarked() {
    final var session = session(CAPTURE_ON_D5);
    click(session, Piece.WHITE_PAWN, Square.E4);
    click(session, Piece.BLACK_PAWN, Square.D5);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3));

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(green(Square.E4, Square.D5), session.getBoardMarks());
  }

  @Test
  void kingAndRookTouchedForCastlingAreBothMarked() {
    final var session = session(BOTH_CASTLINGS);
    click(session, Piece.WHITE_KING, Square.E1);
    click(session, Piece.WHITE_ROOK, Square.A1);
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1));

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(green(Square.E1, Square.A1), session.getBoardMarks());
  }

  @Test
  void aKingThatStandsOnItsCastlingSquareAlreadyIsMarkedThere() {
    final var session = session(BOTH_CASTLINGS);
    click(session, Piece.WHITE_KING, Square.E1);
    click(session, Piece.WHITE_ROOK, Square.H1);
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1));

    // Only the rook is still to be moved: the king is not put back, and e1 is empty.
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(Piece.WHITE_KING, ruling.restorePosition().orElseThrow().get(Square.G1));
    assertEquals(green(Square.G1, Square.H1), session.getBoardMarks());
  }

  // ----- red: the illegal move -----

  @Test
  void anIllegalMoveIsMarkedRedOnItsStartAndEndSquareUntilThePositionIsRestored() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(red(Square.B1, Square.B3), ruling.boardMarks());
    assertEquals(red(Square.B1, Square.B3), session.getBoardMarks());

    session.enterWaitingForRestoration(session.getPositionBeforeTurn());
    assertEquals(red(Square.B1, Square.B3), session.getBoardMarks(), "still on the board while the move is");
    assertTrue(session.completeRestoration());
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void anIllegalMoveWithSeveralDisplacedPiecesIsNotMarked() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_KNIGHT, Square.B1,
        Square.B3, Piece.WHITE_KNIGHT, Square.G1, Square.G3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void twoPiecesOfTheSameKindThatMovedAreNotMarkedAsOneMove() {
    final var session = session(null);
    // The knight from g1 goes to g3 and the one from b1 takes its place: the board looks like b1-g3.
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3);
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.G1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.G3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void aPieceThatWasMovedAboutBeforeItsIllegalMoveIsStillMarked() {
    final var session = session(null);
    // Not released on a square it can go to, which would be the move (FIDE 4.7).
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B4);
    drag(session, Piece.WHITE_KNIGHT, Square.B4, Square.B1);
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(red(Square.B1, Square.B3), session.getBoardMarks());
  }

  @Test
  void aCastlingThatIsNotAllowedIsMarkedAgainWhenItIsRepeatedAfterTheRestoration() {
    final var session = session(WHITE_IN_CHECK);
    final BitboardPosition castled = moved(session, Piece.WHITE_KING, Square.E1, Square.G1, Piece.WHITE_ROOK,
        Square.H1, Square.F1);
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);
    drag(session, Piece.WHITE_ROOK, Square.H1, Square.F1);
    final ArbiterResponse first = session.pressClockButton(Side.WHITE, castled);
    assertEquals(red(Square.E1, Square.G1), session.getBoardMarks());
    revertAndResume(session, first);
    assertEquals(List.of(), session.getBoardMarks());

    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);
    drag(session, Piece.WHITE_ROOK, Square.H1, Square.F1);
    final ArbiterResponse second = session.pressClockButton(Side.WHITE, castled);

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, second.type());
    assertEquals(red(Square.E1, Square.G1), session.getBoardMarks());
  }

  @Test
  void aCastlingThatIsNotAllowedIsMarkedOnTheKingsSquares() {
    final var session = session(WHITE_IN_CHECK);
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);
    drag(session, Piece.WHITE_ROOK, Square.H1, Square.F1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1, Piece.WHITE_ROOK, Square.H1, Square.F1));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type(), ruling.renderedPlayerMessage());
    assertEquals(red(Square.E1, Square.G1), session.getBoardMarks());
  }

  @Test
  void theIllegalMoveThatLosesTheGameStaysMarked() {
    final var session = new GameSession(new TimeControl(60000, 0), 1, true);
    session.startGame();
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, ruling.type());
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(red(Square.B1, Square.B3), session.getBoardMarks());
  }

  /**
   * After a restoration the touched piece has to be moved. An illegal move of another piece is then a touch-move
   * violation like a legal one: only the piece that is owed is pointed at, and no illegal move is marked.
   */
  @Test
  void theTouchedPieceStaysMarkedAloneThroughAnIllegalMoveOfAnotherPiece() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    revertAndResume(session, pawnToE4AndClock(session));
    assertEquals(green(Square.G1), session.getBoardMarks());

    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);
    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3));

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(green(Square.G1), session.getBoardMarks());

    revertAndResume(session, ruling);
    assertEquals(green(Square.G1), session.getBoardMarks());
  }

  @Test
  void anIllegalMoveOfTheTouchedPieceCoversItsCircleUntilThePositionIsRestored() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    revertAndResume(session, pawnToE4AndClock(session));

    // The bound knight is now moved illegally: one circle per square, and red is the newer ruling.
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3);
    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(red(Square.G1, Square.G3), session.getBoardMarks());

    // The knight is back on g1 and still has to move - a pawn move is ruled on as before.
    revertAndResume(session, ruling);
    assertEquals(green(Square.G1), session.getBoardMarks());
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, pawnToE4AndClock(session).type());
    assertEquals(green(Square.G1), session.getBoardMarks());
  }

  // ----- the move shown with a draw offer -----

  @Test
  void anIllegalMoveShownWithADrawOfferIsMarkedLikeOneShownWithAClockPress() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);

    final ArbiterResponse ruling = session.offerDrawCorrectTime(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(red(Square.B1, Square.B3), session.getBoardMarks());
    revertAndResume(session, ruling);
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void aTouchMoveRulingOnADrawOfferIsMarkedToo() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E4);

    final ArbiterResponse ruling = session.offerDrawCorrectTime(Side.WHITE,
        moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4));

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals(green(Square.G1), session.getBoardMarks());
  }

  @Test
  void anIllegalMoveOfTheTouchedPieceShownWithADrawOfferCoversItsCircleToo() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    revertAndResume(session, pawnToE4AndClock(session));
    assertEquals(green(Square.G1), session.getBoardMarks());

    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3);
    final ArbiterResponse ruling = session.offerDrawCorrectTime(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, ruling.type());
    assertEquals(red(Square.G1, Square.G3), session.getBoardMarks());
    revertAndResume(session, ruling);
    assertEquals(green(Square.G1), session.getBoardMarks());
  }

  @Test
  void aDrawOfferWithALegalMoveMarksNothing() {
    final var session = session(null);
    DrawTestSupport.bothPlayersMove(session);
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E4);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session
        .offerDrawCorrectTime(Side.WHITE, moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4)).type());
    assertEquals(List.of(), session.getBoardMarks());
  }

  // ----- the end of the game -----

  @Test
  void aGameThatEndsAnotherWayTakesTheTouchedPiecesCircleOff() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    revertAndResume(session, pawnToE4AndClock(session));
    assertEquals(green(Square.G1), session.getBoardMarks());

    session.resign(Side.WHITE);

    assertEquals(GameState.ENDED, session.getState());
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void aGameThatEndsWhileAnIllegalMoveIsOnTheBoardTakesItsCirclesOff() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3);
    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.B1, Square.B3));
    session.enterWaitingForRestoration(ruling.restorePosition().orElse(session.getPositionBeforeTurn()));
    assertEquals(red(Square.B1, Square.B3), session.getBoardMarks());

    session.resign(Side.BLACK);

    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void theIllegalMoveThatLosesTheGameIsTheOnlyThingMarkedAfterIt() {
    final var session = new GameSession(new TimeControl(60000, 0), 1, true);
    session.startGame();
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    revertAndResume(session, pawnToE4AndClock(session));
    // The touched knight itself, moved as no knight moves.
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.G3));

    assertEquals(ArbiterResponseType.ILLEGAL_MOVE_GAME_LOST, ruling.type());
    assertEquals(red(Square.G1, Square.G3), session.getBoardMarks(), "no move is owed any more");
  }

  // ----- blue: the move that was already made -----

  /**
   * Reported wish (2026-11-25): the knight is played to f3 and put back, then a pawn is played and the clock pressed.
   * The arbiter says that the move was already made - and shows on the board which move that was.
   */
  @Test
  void theMoveThatWasAlreadyMadeIsMarkedBlueUntilThePositionIsRestored() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);
    drag(session, Piece.WHITE_KNIGHT, Square.F3, Square.G1);
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E3);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_PAWN, Square.E2, Square.E3));

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, ruling.type());
    assertEquals(List.of(BoardMark.blue(Square.G1), BoardMark.blue(Square.F3)), ruling.boardMarks());
    assertEquals(List.of(BoardMark.blue(Square.G1), BoardMark.blue(Square.F3)), session.getBoardMarks());

    // The restoration puts the knight on f3 again: the move is on the board, nothing is pointed at.
    revertAndResume(session, ruling);
    assertEquals(List.of(), session.getBoardMarks());
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3)).type());
    assertEquals(List.of(), session.getBoardMarks());
  }

  @Test
  void theBlueCirclesOfAMoveThatWasAlreadyMadeAreShownAgainWhenThePositionIsChangedAgain() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E3);
    final ArbiterResponse first = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3, Piece.WHITE_PAWN, Square.E2, Square.E3));
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, first.type());
    assertEquals(List.of(BoardMark.blue(Square.G1), BoardMark.blue(Square.F3)), session.getBoardMarks());
    revertAndResume(session, first);
    assertEquals(List.of(), session.getBoardMarks());

    drag(session, Piece.WHITE_PAWN, Square.D2, Square.D3);
    final ArbiterResponse second = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3, Piece.WHITE_PAWN, Square.D2, Square.D3));

    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, second.type());
    assertEquals(List.of(BoardMark.blue(Square.G1), BoardMark.blue(Square.F3)), session.getBoardMarks());
  }

  @Test
  void theBlueCirclesAreTakenOffWhenTheGameEnds() {
    final var session = session(null);
    drag(session, Piece.WHITE_KNIGHT, Square.G1, Square.F3);
    drag(session, Piece.WHITE_KNIGHT, Square.F3, Square.G1);
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E3);
    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_PAWN, Square.E2, Square.E3));
    session.enterWaitingForRestoration(ruling.restorePosition().orElse(session.getPositionBeforeTurn()));
    assertEquals(2, session.getBoardMarks().size());

    session.resign(Side.WHITE);

    assertEquals(List.of(), session.getBoardMarks());
  }

  // ----- what leaves the marks as they are -----

  @Test
  void aClockPressWithoutAMoveAndAPressThatIsNotRuledOnLeaveTheMarks() {
    final var session = session(null);
    click(session, Piece.WHITE_KNIGHT, Square.G1);
    revertAndResume(session, pawnToE4AndClock(session));

    // Black presses out of turn: no ruling.
    session.pressClockButton(Side.BLACK, session.getPositionBeforeTurn());
    assertEquals(green(Square.G1), session.getBoardMarks());

    // White presses without a move (FIDE 7.5.3): an illegal move, but nothing on the board to point at -
    // and the knight still has to move.
    final ArbiterResponse noMove = session.pressClockButton(Side.WHITE, session.getPositionBeforeTurn());
    assertEquals(ArbiterResponseType.ILLEGAL_MOVE, noMove.type());
    assertEquals(List.of(), noMove.boardMarks());
    assertEquals(green(Square.G1), session.getBoardMarks());
  }

  @Test
  void anOrdinaryGameHasNoMarks() {
    final var session = session(null);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, pawnToE4AndClock(session).type());
    assertEquals(List.of(), session.getBoardMarks());
  }
}
