// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.core.BitboardPositions;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * A released-piece (FIDE 4.7) commitment must bind for the WHOLE turn — across repeated revert cycles — until the
 * committed move is actually played and the clock pressed. Regression test for the bug where the commitment was lost
 * after the first restoration, so a different move (a2-a3) got wrongly accepted.
 */
class TestReleasedPieceCommitment {

  private static final TimeControl TIME = new TimeControl(5 * 60 * 1000L, 0);

  /** Drives the server-side restoration handshake: enter restoration to the response's target, then resume. */
  private static void revertAndResume(GameSession session, ArbiterResponse violation) {
    session.enterWaitingForRestoration(violation.restorePosition().orElse(session.getPositionBeforeTurn()));
    session.completeRestoration();
    session.resumeAfterRestorationDelay();
  }

  @Test
  void releasedPieceCommitmentSurvivesRepeatedRevertCycles() {
    final GameSession s = new GameSession(TIME, 2, true);
    s.startGame();
    final Side w = Side.WHITE;
    final BitboardPosition start = s.getBoard().getBitboardPosition();

    final BitboardPosition releasedOnA4 = BitboardPositions.from(start).createChangedPosition(Square.A2, Piece.NONE)
        .createChangedPosition(Square.A4, Piece.WHITE_PAWN).build();
    final BitboardPosition pawnB3 = BitboardPositions.from(start).createChangedPosition(Square.B2, Piece.NONE)
        .createChangedPosition(Square.B3, Piece.WHITE_PAWN).build();
    final BitboardPosition pawnA3 = BitboardPositions.from(start).createChangedPosition(Square.A2, Piece.NONE)
        .createChangedPosition(Square.A3, Piece.WHITE_PAWN).build();

    // Cycle 1: release a2-a4, fiddle back, play b2-b3, press -> released-piece violation, restore to a4.
    s.recordEvent(w, BoardEvent.dragMove(Square.A2, Square.A4, Piece.WHITE_PAWN, 0));
    s.recordEvent(w, BoardEvent.dragMove(Square.A4, Square.A2, Piece.WHITE_PAWN, 1));
    s.recordEvent(w, BoardEvent.dragMove(Square.B2, Square.B3, Piece.WHITE_PAWN, 2));
    final ArbiterResponse r1 = s.pressClockButton(w, pawnB3);
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, r1.type(), "cycle 1 must be a released-piece violation");
    assertEquals(releasedOnA4, r1.restorePosition().orElseThrow(), "cycle 1 must restore the committed a4 move");
    revertAndResume(s, r1);

    // Cycle 2: undo a4-a2 again, play b2-b3 again, press. The a4 commitment must STILL bind.
    s.recordEvent(w, BoardEvent.dragMove(Square.A4, Square.A2, Piece.WHITE_PAWN, 3));
    s.recordEvent(w, BoardEvent.dragMove(Square.B2, Square.B3, Piece.WHITE_PAWN, 4));
    final ArbiterResponse r2 = s.pressClockButton(w, pawnB3);
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, r2.type(),
        "cycle 2 must STILL be a released-piece violation (commitment preserved)");
    assertEquals(releasedOnA4, r2.restorePosition().orElseThrow(),
        "cycle 2 revert must restore BOTH b3->b2 and a2->a4 (the committed move)");
    revertAndResume(s, r2);

    // A different move with the committed pawn (a2-a3) must be REJECTED — it was released on a4.
    s.recordEvent(w, BoardEvent.dragMove(Square.A2, Square.A3, Piece.WHITE_PAWN, 5));
    final ArbiterResponse r3 = s.pressClockButton(w, pawnA3);
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, r3.type(),
        "a2-a3 must be rejected; the pawn was released on a4");
    revertAndResume(s, r3);

    // The player can still satisfy the commitment by actually playing a2-a4 (no deadlock).
    s.recordEvent(w, BoardEvent.dragMove(Square.A2, Square.A4, Piece.WHITE_PAWN, 6));
    final ArbiterResponse r4 = s.pressClockButton(w, releasedOnA4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, r4.type(),
        "playing the committed move a2-a4 must finally be accepted");
  }

  @Test
  void releasedPieceFinalMoveCanBeClockPressedDuringAutoResumeGap() {
    final GameSession s = new GameSession(TIME, 2, true);
    s.startGame();
    final Side w = Side.WHITE;
    final BitboardPosition start = s.getBoard().getBitboardPosition();
    final BitboardPosition releasedOnA4 = BitboardPositions.from(start).createChangedPosition(Square.A2, Piece.NONE)
        .createChangedPosition(Square.A4, Piece.WHITE_PAWN).build();
    final BitboardPosition afterA4AndH3 = BitboardPositions.from(releasedOnA4)
        .createChangedPosition(Square.H2, Piece.NONE).createChangedPosition(Square.H3, Piece.WHITE_PAWN).build();

    s.recordEvent(w, BoardEvent.dragMove(Square.A2, Square.A4, Piece.WHITE_PAWN, 0));
    s.recordEvent(w, BoardEvent.dragMove(Square.H2, Square.H3, Piece.WHITE_PAWN, 1));
    final ArbiterResponse violation = s.pressClockButton(w, afterA4AndH3);
    assertEquals(ArbiterResponseType.RELEASED_PIECE_VIOLATION, violation.type());

    s.enterWaitingForRestoration(violation.restorePosition().orElseThrow());
    s.completeRestoration();

    final ArbiterResponse accepted = s.pressClockButton(w, releasedOnA4);
    assertEquals(ArbiterResponseType.MOVE_ACCEPTED, accepted.type(),
        "pressing immediately after Revert must auto-resume the clock before switching it");
  }
}
