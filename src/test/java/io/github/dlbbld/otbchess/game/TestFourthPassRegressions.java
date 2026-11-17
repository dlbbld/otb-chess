// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * Regressions for the third review pass: a release and a touch must never give opposite orders, "your move is final"
 * must be true, and a draw offer can neither be replaced by a counter offer nor withdrawn by its maker.
 */
class TestFourthPassRegressions {

  private static final String BOTH_CASTLINGS = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1";

  private long time;

  private static GameSession session() {
    final var session = new GameSession(new TimeControl(60000, 0), 2, true);
    session.startGame();
    return session;
  }

  private static GameSession castlingSession() {
    final var session = new GameSession(new TimeControl(60000, 0), 2, true, Board.fromFenStrict(BOTH_CASTLINGS));
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
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.dragMove(from, to, piece, time++)).isEmpty());
  }

  private void click(GameSession session, Piece piece, Square square) {
    assertTrue(session.recordEvent(Side.WHITE, BoardEvent.click(square, piece, time++)).isEmpty());
  }

  /** What the server does with a ruling that asks for a restoration, up to the restarted clock. */
  private static void revertAndResume(GameSession session, ArbiterResponse ruling) {
    session.enterWaitingForRestoration(ruling.restorePosition().orElse(session.getPositionBeforeTurn()));
    assertTrue(session.completeRestoration());
    assertTrue(session.resumeAfterRestorationDelay());
  }

  // ===== A release and a touch never give opposite orders =====

  @Test
  void aKingReleasedForKingsideCastlingIsNotOverruledByTheOtherRookMovedLater() {
    final var session = castlingSession();
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);
    drag(session, Piece.WHITE_KING, Square.G1, Square.E1);
    drag(session, Piece.WHITE_ROOK, Square.A1, Square.B1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_ROOK, Square.A1, Square.B1));
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, ruling.type());
    assertTrue(ruling.message().contains("kingside castling is legal"), ruling.message());
    assertTrue(ruling.message().contains("moving the rook from h1 to f1"), ruling.message());
    final BitboardPosition kingOnG1 = moved(session, Piece.WHITE_KING, Square.E1, Square.G1);
    assertEquals(kingOnG1, ruling.restorePosition().orElseThrow());

    revertAndResume(session, ruling);
    drag(session, Piece.WHITE_ROOK, Square.H1, Square.F1);
    // Exactly what the arbiter asked for must be accepted - not answered with "please castle queenside".
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1, Piece.WHITE_ROOK, Square.H1, Square.F1)).type());
    assertEquals("O-O", session.getBoard().getPerformedMovesAsSan().get(0));
  }

  @Test
  void touchingTheOtherRookAfterTheKingWasReleasedDoesNotChangeTheCastlingSide() {
    final var session = castlingSession();
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);
    click(session, Piece.WHITE_ROOK, Square.A1);
    drag(session, Piece.WHITE_ROOK, Square.H1, Square.F1);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1, Piece.WHITE_ROOK, Square.H1, Square.F1)).type());
  }

  @Test
  void aRookTouchedFirstRulesOutCastlingAndTheRulingNamesTheRook() {
    final var session = castlingSession();
    final long blackTime = session.getClock().getRemainingTimeMs(Side.BLACK);
    click(session, Piece.WHITE_ROOK, Square.A1);
    drag(session, Piece.WHITE_KING, Square.E1, Square.C1);
    assertFalse(session.hasReleasedPieceCommitment(), "FIDE 4.4.2: this castling was never available");

    final ArbiterResponse kingOnly = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.C1));
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, kingOnly.type());
    assertTrue(kingOnly.message().contains("You first touched the rook on a1"), kingOnly.message());
    assertFalse(kingOnly.message().contains("Castling has been started"), kingOnly.message());
    assertEquals(blackTime, session.getClock().getRemainingTimeMs(Side.BLACK), "not counted as an illegal move");

    revertAndResume(session, kingOnly);
    assertFalse(session.isRestoredMoveFinal());
    drag(session, Piece.WHITE_KING, Square.E1, Square.C1);
    drag(session, Piece.WHITE_ROOK, Square.A1, Square.D1);
    final ArbiterResponse castled = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.C1, Piece.WHITE_ROOK, Square.A1, Square.D1));
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, castled.type());
    assertTrue(castled.message().contains("You first touched the rook on a1"), castled.message());

    revertAndResume(session, castled);
    drag(session, Piece.WHITE_ROOK, Square.A1, Square.B1);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_ROOK, Square.A1, Square.B1)).type());
  }

  @Test
  void aKingPutOnTheWrongCastlingSquareAfterKingAndRookWereTouchedIsToldWhichCastlingIsOwed() {
    final var session = castlingSession();
    click(session, Piece.WHITE_KING, Square.E1);
    click(session, Piece.WHITE_ROOK, Square.A1);
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1));
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, ruling.type());
    assertEquals("Because you touched the king and the rook on a1, and queenside castling is legal,"
        + " please perform the queenside castling move.", ruling.message());
    assertEquals("Your opponent touched the king and the rook on a1, and queenside castling is legal."
        + " They are requested to restore the position and perform the queenside castling move.",
        ruling.renderedOpponentMessage().orElseThrow());

    revertAndResume(session, ruling);
    drag(session, Piece.WHITE_KING, Square.E1, Square.C1);
    drag(session, Piece.WHITE_ROOK, Square.A1, Square.D1);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.C1, Piece.WHITE_ROOK, Square.A1, Square.D1)).type());
  }

  @Test
  void aMoveMadeAgainstTheTouchedPieceDoesNotBindAndCanBeCorrectedBeforeTheClock() {
    final var session = session();
    click(session, Piece.WHITE_PAWN, Square.G2);
    drag(session, Piece.WHITE_PAWN, Square.H2, Square.H3);
    assertFalse(session.hasReleasedPieceCommitment());
    drag(session, Piece.WHITE_PAWN, Square.H3, Square.H2);
    drag(session, Piece.WHITE_PAWN, Square.G2, Square.G3);

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_PAWN, Square.G2, Square.G3)).type());
  }

  // ===== "Your move is final" only when the clock press is then accepted =====

  @Test
  void aMoveThatBreaksTouchMoveIsNotCalledFinalAfterALaterIntervention() {
    final var session = session();
    final BitboardPosition turnStart = session.getPositionBeforeTurn();
    click(session, Piece.WHITE_PAWN, Square.G2);
    drag(session, Piece.WHITE_PAWN, Square.H2, Square.H3);

    final ArbiterResponse intervention = session
        .recordEvent(Side.WHITE, BoardEvent.dragMove(Square.A7, Square.A6, Piece.BLACK_PAWN, time++)).orElseThrow();
    assertEquals(ArbiterResponseType.POSITION_CHANGE, intervention.type());
    assertTrue(intervention.restorePosition().isEmpty(), "h2-h3 is not a move to go back to");
    assertFalse(session.isRestorationFromReleasedPiece());

    revertAndResume(session, intervention);
    assertEquals(turnStart, session.getRestorePosition());
    assertFalse(session.isRestoredMoveFinal());

    drag(session, Piece.WHITE_PAWN, Square.H2, Square.H3);
    final ArbiterResponse touchMove = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_PAWN, Square.H2, Square.H3));
    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, touchMove.type());
    assertTrue(touchMove.message().contains("pawn on g2"), touchMove.message());

    revertAndResume(session, touchMove);
    drag(session, Piece.WHITE_PAWN, Square.G2, Square.G4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        session.pressClockButton(Side.WHITE, moved(session, Piece.WHITE_PAWN, Square.G2, Square.G4)).type());
  }

  @Test
  void aStartedCastlingIsNotCalledFinalUntilTheRookHasMoved() {
    final var session = castlingSession();
    drag(session, Piece.WHITE_KING, Square.E1, Square.G1);
    drag(session, Piece.WHITE_KING, Square.G1, Square.F1);

    final ArbiterResponse ruling = session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.F1));
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, ruling.type());
    assertTrue(ruling.message().startsWith("Castling has been started"), ruling.message());
    final BitboardPosition kingOnG1 = moved(session, Piece.WHITE_KING, Square.E1, Square.G1);

    session.enterWaitingForRestoration(ruling.restorePosition().orElseThrow());
    assertEquals(kingOnG1, session.getRestorePosition());
    assertTrue(session.isRestorationFromReleasedPiece(), "the king's release still commits to castling");
    assertFalse(session.isRestoredMoveFinal(), "but the move is not made: the rook has to follow");
    assertTrue(session.completeRestoration());
    assertFalse(session.isRestoredMoveFinal());
    assertTrue(session.resumeAfterRestorationDelay());

    // Pressing the clock now is not accepted - which is why the message must not ask for it.
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION,
        session.pressClockButton(Side.WHITE, kingOnG1).type());
    session.continueWithoutRestoration();
    drag(session, Piece.WHITE_ROOK, Square.H1, Square.F1);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE,
        moved(session, Piece.WHITE_KING, Square.E1, Square.G1, Piece.WHITE_ROOK, Square.H1, Square.F1)).type());
  }

  @Test
  void aCompletedMoveIsStillCalledFinalAndItsClockPressIsAccepted() {
    final var session = session();
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E4);
    final BitboardPosition afterE4 = moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4);

    final ArbiterResponse intervention = session
        .recordEvent(Side.WHITE, BoardEvent.dragMove(Square.A7, Square.A6, Piece.BLACK_PAWN, time++)).orElseThrow();
    assertEquals(afterE4, intervention.restorePosition().orElseThrow());
    session.enterWaitingForRestoration(intervention.restorePosition().orElseThrow());
    assertTrue(session.isRestoredMoveFinal());
    assertTrue(session.completeRestoration());
    assertTrue(session.isRestoredMoveFinal());
    assertTrue(session.resumeAfterRestorationDelay());

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4).type());
  }

  // ===== Draw offers =====

  private static void assertDrawnByAgreement(GameSession session, Side acceptedBy) {
    assertEquals(GameState.ENDED, session.getState());
    assertEquals(GameResultType.DRAW_AGREEMENT, session.getResult().type());
    assertEquals(Side.NONE, session.getResult().winner());
    assertEquals("The game is drawn by agreement.", session.getResult().description());
    assertEquals(acceptedBy, session.getTerminationActor());
  }

  @ParameterizedTest
  @ValueSource(strings = { "WHITE", "BLACK" })
  void aCounterOfferAgreesTheDrawInsteadOfReplacingThePendingOffer(String first) {
    final var session = session();
    final Side offerer = Side.valueOf(first);
    assertTrue(session.offerDrawWrongTime(offerer).accepted());

    final var counter = session.offerDrawWrongTime(offerer.getOppositeSide());

    assertFalse(counter.accepted(), "nothing is registered or forwarded");
    assertFalse(counter.gameLost());
    assertNull(counter.arbiterMessage());
    assertNull(counter.opponentInfo());
    assertDrawnByAgreement(session, offerer.getOppositeSide());
  }

  @Test
  void aCounterOfferMadeWithAMoveOnTheBoardAgreesTheDrawWithoutJudgingTheMove() {
    final var session = session();
    assertTrue(session.offerDrawWrongTime(Side.BLACK).accepted());
    // An illegal move that commits to nothing: the offer can still be accepted.
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E5);

    final ArbiterResponse response = session.offerDrawCorrectTime(Side.WHITE,
        moved(session, Piece.WHITE_PAWN, Square.E2, Square.E5));

    assertEquals(ArbiterResponseType.INCOMPLETE_MOVE, response.type());
    assertDrawnByAgreement(session, Side.WHITE);
    assertEquals(0, session.getBoard().getPerformedMovesAsSan().size());
  }

  @Test
  void anOfferThatCanNoLongerBeAcceptedIsReplacedAndTheNewOfferCannotBeRejectedByItsMaker() {
    final var session = session();
    assertTrue(session.offerDrawWrongTime(Side.BLACK).accepted());
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E4);
    // The server's bookkeeping for "the recipient made the move": Black's offer can no longer be accepted.
    assertTrue(session.hasReleasedPieceCommitment());
    session.getDrawOfferManager().recordOpponentTouchedPiece();

    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session
        .offerDrawCorrectTime(Side.WHITE, moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4)).type());
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertEquals(Side.WHITE, session.getDrawOfferManager().getOfferingSide());

    // White presses Reject on the panel that still showed Black's old offer.
    session.rejectDraw(Side.WHITE);
    assertEquals("You cannot reject your own draw offer.", session.getLastRejectDrawRejection());
    assertTrue(session.getDrawOfferManager().isDrawOffered(), "FIDE 9.1.2.1: an offer cannot be withdrawn");
    assertEquals(Side.WHITE, session.getDrawOfferManager().getOfferingSide());

    assertTrue(session.acceptDraw(Side.BLACK).isPresent());
    assertDrawnByAgreement(session, Side.BLACK);
  }

  @Test
  void aRejectedClaimThatMeetsThePendingOfferOfTheOpponentAgreesTheDraw() {
    final var session = session();
    drag(session, Piece.WHITE_PAWN, Square.E2, Square.E4);
    final BitboardPosition afterE4 = moved(session, Piece.WHITE_PAWN, Square.E2, Square.E4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.offerDrawCorrectTime(Side.WHITE, afterE4).type());
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, session.pressClockButton(Side.WHITE, afterE4).type());
    assertTrue(session.getDrawOfferManager().canAccept(Side.BLACK));

    // FIDE 9.5: the incorrect claim counts as a draw offer - and White's offer is still on the table.
    final var claim = session.claimDraw(Side.BLACK, DrawClaimType.THREEFOLD_ON_BOARD, null);

    assertFalse(claim.accepted());
    assertTrue(claim.convertsToDrawOffer());
    assertDrawnByAgreement(session, Side.BLACK);
  }

  @ParameterizedTest
  @ValueSource(strings = { "WHITE", "BLACK" })
  void anOfferWithAnInvalidMoveLeavesAPendingOfferAlone(String pending) {
    final var session = session();
    final Side offerer = Side.valueOf(pending);
    assertTrue(session.offerDrawWrongTime(offerer).accepted());
    // Touch-move: White touches the g2 pawn and then plays h2-h3. For White as recipient the offer was made at
    // the wrong time, so touching does not end it; it is ended here the way the server does after a release.
    click(session, Piece.WHITE_PAWN, Square.G2);
    drag(session, Piece.WHITE_PAWN, Square.H2, Square.H3);
    if (offerer == Side.BLACK) {
      session.getDrawOfferManager().recordOpponentTouchedPiece();
    }

    final ArbiterResponse response = session.offerDrawCorrectTime(Side.WHITE,
        moved(session, Piece.WHITE_PAWN, Square.H2, Square.H3));

    assertEquals(ArbiterResponseType.TOUCH_MOVE_VIOLATION, response.type());
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertTrue(session.getDrawOfferManager().isDrawOffered(),
        "the offer on the opponent's screen must still exist: it cannot be withdrawn or cancelled this way");
    assertEquals(offerer, session.getDrawOfferManager().getOfferingSide());
  }

  @Test
  void onlyTheRecipientCanAcceptOrRejectAnOffer() {
    final var offers = new DrawOfferManager();
    assertTrue(offers.offerDrawWrongTime(Side.BLACK, false).accepted());
    assertTrue(offers.canAccept(Side.WHITE));
    assertFalse(offers.canAccept(Side.BLACK));

    assertEquals("You cannot reject your own draw offer.", offers.rejectDraw(Side.BLACK).orElseThrow());
    assertEquals("You cannot accept your own draw offer.", offers.acceptDraw(Side.BLACK).orElseThrow());
    assertTrue(offers.isDrawOffered());

    offers.recordOpponentTouchedPiece();
    assertFalse(offers.canAccept(Side.WHITE));

    final var fresh = new DrawOfferManager();
    assertTrue(fresh.offerDrawWrongTime(Side.BLACK, false).accepted());
    assertTrue(fresh.rejectDraw(Side.WHITE).isEmpty());
    assertFalse(fresh.isDrawOffered());
  }
}
