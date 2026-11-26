// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

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
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.placement.PlacementBoard.PlacedPiece;

/**
 * The rulings at the clock press for a piece let go in an unclear placement (free placement, E-005), in the order
 * the arbiter applies them: first a piece that lies unclear - he names the squares it is bound to - then a bound
 * piece that stands clearly on a square it is not bound to. Only the first counts.
 */
class TestUnclearBindingRulings {

  /** King e2, rook a1: the rook reaches every square of the first rank. */
  private static final String ROOK = "4k3/8/8/8/8/8/4K3/R7 w - - 0 1";
  private static final String ROOK_RELEASED = "Your rook was released on d1 and e1. Place it on d1 or on e1.";

  private GameSession session;
  private long time;

  private void start(String fen) {
    session = new GameSession(new TimeControl(600000, 0), 3, true, Board.fromFenStrict(fen));
    session.startGame();
  }

  private static Square sq(String name) {
    return Square.parse(name);
  }

  private static List<Square> squares(String... names) {
    return List.of(names).stream().map(Square::parse).toList();
  }

  /** The piece counting on {@code on} is let go across the border of {@code across}; it lies there from now on. */
  private List<PlacedPiece> letGoAcross(String on, Piece piece, String... across) {
    session.recordEvent(Side.WHITE, BoardEvent.releaseUnclear(sq(on), piece, squares(across), time++));
    return List.of(new PlacedPiece(1, piece, 0, 0, sq(on), sq(on), false, squares(across)));
  }

  private void move(String from, String to, Piece piece) {
    session.recordEvent(Side.WHITE, BoardEvent.dragMove(sq(from), sq(to), piece, time++));
  }

  private BitboardPosition with(Piece piece, String from, String to) {
    return BitboardPositions.from(session.getPositionBeforeTurn()).createChangedPosition(sq(from), Piece.NONE)
        .createChangedPosition(sq(to), piece).build();
  }

  private ArbiterResponse press(BitboardPosition position, List<PlacedPiece> unclear) {
    return session.pressClockButton(Side.WHITE, position, unclear);
  }

  private ArbiterResponse pressUnclear(List<PlacedPiece> unclear) {
    return press(session.getPositionBeforeTurn(), unclear);
  }

  private void restartClock() {
    assertTrue(session.isUnclearPlacementPause(), "the board is locked for the call");
    assertEquals(Side.NONE, session.getClock().getRunningFor());
    assertTrue(session.resumeAfterPressWithoutMove(session.getPressWithoutMovePauseId()));
    assertEquals(Side.WHITE, session.getClock().getRunningFor());
  }

  private static List<BoardMark> green(String... names) {
    return List.of(names).stream().map(name -> BoardMark.green(sq(name))).toList();
  }

  // ===== The call for a piece that lies unclear names the squares it is bound to =====

  @Test
  void theCallNamesTheTwoSquaresTheRookIsBoundTo() {
    start(ROOK);
    final ArbiterResponse call = pressUnclear(letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1"));
    assertEquals(ArbiterResponseType.UNCLEAR_PLACEMENT, call.type());
    assertEquals("It is not clear on which square your rook is. Place it on d1 or on e1, and place your pieces more"
        + " carefully.", call.renderedPlayerMessage());
    assertEquals(1, session.getUnclearPlacementCount(Side.WHITE));
    assertEquals(green("d1", "e1"), session.getBoardMarks(), "the squares to choose from are circled");
    restartClock();
  }

  @Test
  void theCallNamesOnlyTheSquareThatGivesALegalMove() {
    start("4k3/8/8/8/8/8/8/R3K3 w - - 0 1"); // the king is on e1
    final ArbiterResponse call = pressUnclear(letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1"));
    assertEquals("It is not clear on which square your rook is. Place it on d1, and place your pieces more"
        + " carefully.", call.renderedPlayerMessage());
    assertEquals(green("d1"), session.getBoardMarks());
  }

  @Test
  void theCallNamesNoSquareWhenThePieceIsBoundToNothing() {
    start("4k3/8/8/8/8/8/4K3/2B5 w - - 0 1");
    final List<PlacedPiece> bishop = letGoAcross("c1", Piece.WHITE_BISHOP, "c4", "d4");
    assertEquals("It is not clear on which square your bishop is. Place your pieces more carefully.",
        pressUnclear(bishop).renderedPlayerMessage());
    assertTrue(session.getBoardMarks().isEmpty());
    restartClock();

    // Any legal bishop move remains.
    move("c1", "e3", Piece.WHITE_BISHOP);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED,
        press(with(Piece.WHITE_BISHOP, "c1", "e3"), List.of()).type());
  }

  @Test
  void aPieceLetGoAcrossTheBorderOfItsOwnSquareIsOnlyTouched() {
    start("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    final List<PlacedPiece> pawn = letGoAcross("e2", Piece.WHITE_PAWN, "e2", "e3");
    assertEquals("It is not clear on which square your pawn is. Place your pieces more carefully.",
        pressUnclear(pawn).renderedPlayerMessage());
    restartClock();
    // Any legal move of the pawn remains - and only of the pawn: it was touched.
    move("e2", "e4", Piece.WHITE_PAWN);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, press(with(Piece.WHITE_PAWN, "e2", "e4"), List.of()).type());
  }

  @Test
  void aSecondUnclearReleaseElsewhereGetsTheCallForTheFirstBinding() {
    start(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    final List<PlacedPiece> rook = letGoAcross("a1", Piece.WHITE_ROOK, "f1", "g1");
    assertEquals("It is not clear on which square your rook is. It was released on d1 and e1. Place it on d1 or on"
        + " e1. Place your pieces more carefully.", pressUnclear(rook).renderedPlayerMessage());
    assertEquals(green("d1", "e1"), session.getBoardMarks());
  }

  @Test
  void aReleasedPiecePickedUpAgainGetsTheCallForTheSquareItWasReleasedOn() {
    start(ROOK);
    move("a1", "d1", Piece.WHITE_ROOK); // released on d1 (FIDE 4.7)
    session.recordEvent(Side.WHITE,
        BoardEvent.releaseUnclear(sq("d1"), Piece.WHITE_ROOK, squares("f1", "g1"), time++));
    final List<PlacedPiece> rook = List.of(
        new PlacedPiece(1, Piece.WHITE_ROOK, 0, 0, sq("d1"), sq("d1"), false, squares("f1", "g1")));
    assertEquals("It is not clear on which square your rook is. It was released on d1. Place it on d1. Place your"
        + " pieces more carefully.", press(with(Piece.WHITE_ROOK, "a1", "d1"), rook).renderedPlayerMessage());
  }

  @Test
  void severalUnclearPiecesAreNamedInOneCallThatCountsOnce() {
    start(ROOK);
    final PlacedPiece rook = letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1").get(0);
    final PlacedPiece king = new PlacedPiece(2, Piece.WHITE_KING, 0, 0, sq("e2"), sq("e2"), false,
        squares("e2", "f2"));
    assertEquals("It is not clear on which square your rook is. Place it on d1 or on e1. It is not clear on which"
        + " square your king is. Place your pieces more carefully.",
        pressUnclear(List.of(rook, king)).renderedPlayerMessage());
    assertEquals(1, session.getUnclearPlacementCount(Side.WHITE));
  }

  // ===== A bound piece on a square it is not bound to =====

  @Test
  void theBoundRookPutOnAnotherSquareIsSentToItsSquaresAtEveryPressWithoutCounting() {
    start(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("a1", "b1", Piece.WHITE_ROOK); // clearly on b1: the arbiter had not named the squares yet
    final BitboardPosition onB1 = with(Piece.WHITE_ROOK, "a1", "b1");

    for (int press = 1; press <= 4; press++) {
      final ArbiterResponse call = press(onB1, List.of());
      assertEquals(ArbiterResponseType.UNCLEAR_PLACEMENT, call.type());
      assertEquals(ROOK_RELEASED, call.renderedPlayerMessage());
      assertEquals(Optional.of("Your opponent's rook was released on d1 and e1. It has to be placed on one of"
          + " them."), call.renderedOpponentMessage());
      assertEquals(green("d1", "e1"), session.getBoardMarks());
      restartClock();
    }
    assertEquals(0, session.getUnclearPlacementCount(Side.WHITE), "it does not count, like a released piece");
    assertEquals(GameState.IN_PROGRESS, session.getState());
    assertEquals(Side.WHITE, session.getHavingMove(), "Rb1 was not made");

    // Taken to one of its squares: the circles go, and the move is ruled on as any other.
    move("b1", "d1", Piece.WHITE_ROOK);
    assertTrue(session.getBoardMarks().isEmpty());
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, press(with(Piece.WHITE_ROOK, "a1", "d1"), List.of()).type());
    assertEquals(Side.BLACK, session.getHavingMove());
    assertTrue(session.exportPgn().contains("Rd1"), session.exportPgn());
  }

  @Test
  void theBoundRookPutBackOnItsSquareIsNotAPressWithoutAMove() {
    start(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    // Put back clearly on a1: no event for the rules, the board shows the position of the turn start.
    final ArbiterResponse call = press(session.getPositionBeforeTurn(), List.of());
    assertEquals(ArbiterResponseType.UNCLEAR_PLACEMENT, call.type(), "no illegal move is counted");
    assertEquals(ROOK_RELEASED, call.renderedPlayerMessage());
  }

  @Test
  void aMoveOfAnotherPieceIsNotRuledOnWhileTheRookIsBound() {
    start(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("e2", "e3", Piece.WHITE_KING);
    assertEquals(ROOK_RELEASED, press(with(Piece.WHITE_KING, "e2", "e3"), List.of()).renderedPlayerMessage());
    assertEquals(Side.WHITE, session.getHavingMove());
  }

  @Test
  void theBoundRookPlacedClearlyOnOneOfItsSquaresIsAnOrdinaryMove() {
    start(ROOK);
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("a1", "e1", Piece.WHITE_ROOK);
    assertTrue(session.hasReleasedPieceCommitment(), "released on e1, as in every game");
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, press(with(Piece.WHITE_ROOK, "a1", "e1"), List.of()).type());
  }

  @Test
  void aNewTurnStartsWithoutBindingAndWithoutCircles() {
    start(ROOK);
    pressUnclear(letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1"));
    restartClock();
    move("a1", "d1", Piece.WHITE_ROOK);
    press(with(Piece.WHITE_ROOK, "a1", "d1"), List.of());
    assertEquals(Side.BLACK, session.getHavingMove());
    assertTrue(session.getBoardMarks().isEmpty());
  }

  // ===== Other moments that read the board =====

  @Test
  void aMoveThatBreaksTheBindingDoesNotEndTheGame() {
    // Qg7 is mate, and so is Ra8.
    final String mateInOne = "7k/1Q6/6K1/8/8/8/8/R7 w - - 0 1";
    start(mateInOne);
    letGoAcross("a1", Piece.WHITE_ROOK, "a2", "a3");
    move("b7", "g7", Piece.WHITE_QUEEN);
    assertTrue(session.evaluateForAutoEnd(Side.WHITE, with(Piece.WHITE_QUEEN, "b7", "g7")).isEmpty(),
        "the queen's move is not made while the rook is bound to a2 and a3");
    assertEquals(GameState.IN_PROGRESS, session.getState());

    // The control: without the binding the same release ends the game.
    start(mateInOne);
    move("b7", "g7", Piece.WHITE_QUEEN);
    assertTrue(session.evaluateForAutoEnd(Side.WHITE, with(Piece.WHITE_QUEEN, "b7", "g7")).isPresent());
    assertEquals(GameState.ENDED, session.getState());

    // And the bound piece placed clearly on a square it is bound to ends it as well.
    start(mateInOne);
    letGoAcross("a1", Piece.WHITE_ROOK, "a7", "a8");
    move("a1", "a8", Piece.WHITE_ROOK);
    assertTrue(session.evaluateForAutoEnd(Side.WHITE, with(Piece.WHITE_ROOK, "a1", "a8")).isPresent());
    assertEquals(GameState.ENDED, session.getState());
  }

  @Test
  void aDrawOfferWithTheBoundPieceElsewhereGetsTheCallAndIsNotMade() {
    start(ROOK);
    assertTrue(session.offerDrawWithPlacementCall(Side.WHITE, List.of()).isEmpty(), "nothing to call");
    letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    move("a1", "b1", Piece.WHITE_ROOK);
    final ArbiterResponse call = session.offerDrawWithPlacementCall(Side.WHITE, List.of()).orElseThrow();
    assertEquals(ROOK_RELEASED, call.renderedPlayerMessage());
    assertFalse(session.getDrawOfferManager().isDrawOffered());
    assertEquals(0, session.getUnclearPlacementCount(Side.WHITE));
    assertTrue(session.offerDrawWithPlacementCall(Side.BLACK, List.of()).isEmpty(), "only the player to move");
  }

  @Test
  void aMoveOwedAfterARejectedClaimComesBeforeTheBinding() {
    start(ROOK);
    assertFalse(session.claimDraw(Side.WHITE, DrawClaimType.THREEFOLD_WITH_MOVE, "Rb1").accepted());
    assertNotNull(session.getMustExecuteMove(), "Rb1 is owed");
    final List<PlacedPiece> rook = letGoAcross("a1", Piece.WHITE_ROOK, "d1", "e1");
    assertEquals("It is not clear on which square your rook is. Place your pieces more carefully.",
        pressUnclear(rook).renderedPlayerMessage(), "a binding that does not allow the owed move binds to nothing");
    assertTrue(session.getBoardMarks().isEmpty());
    restartClock();
    move("a1", "b1", Piece.WHITE_ROOK);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, press(with(Piece.WHITE_ROOK, "a1", "b1"), List.of()).type());
  }
}
