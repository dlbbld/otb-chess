// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.enums.Piece;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.TimeControl;

/**
 * Differential check of the touch-move rulings against the text of FIDE 4.3, not part of the regular suite (the class
 * name keeps Surefire from picking it up). Random touches of own and opponent pieces followed by a cleanly executed
 * legal move; kings, rooks and special moves are left out. Run: {@code mvn -o -q test -Dtest=TouchMoveOracle}.
 */
class TouchMoveOracle {
  static final String[] FENS = { "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b - - 3 3",
      "r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 7",
      "2r3k1/pp3ppp/2n1b3/3p4/3P4/2N1BN2/PP3PPP/2R3K1 w - - 0 1",
      "8/2p2kp1/1p1p3p/p2Pn3/P3P3/2P2B1P/6P1/6K1 w - - 0 1", "rnbqkbnr/ppp2ppp/8/3pp3/3PP3/8/PPP2PPP/RNBQKBNR w - - 0 3" };

  @Test
  void compare() {
    final Random rnd = new Random(7);
    final Map<String, List<String>> mismatches = new TreeMap<>();
    int total = 0;
    for (int trial = 0; trial < 40000; trial++) {
      final String fen = FENS[rnd.nextInt(FENS.length)];
      final Board board = Board.fromFenStrict(fen);
      final Side side = board.getSideToMove();
      final BitboardPosition pos = board.getBitboardPosition();
      final List<LegalMove> legal = board.getLegalMoves();
      final List<Square> candidates = new ArrayList<>();
      for (final Square sq : Square.REAL) {
        final Piece p = pos.get(sq);
        if (p != Piece.NONE && !p.name().endsWith("KING") && !p.name().endsWith("ROOK")) {
          candidates.add(sq);
        }
      }
      final int touches = rnd.nextInt(3);
      final List<Square> touched = new ArrayList<>();
      for (int i = 0; i < touches; i++) {
        touched.add(candidates.get(rnd.nextInt(candidates.size())));
      }
      LegalMove move = legal.get(rnd.nextInt(legal.size()));
      if (move.isCastling() || move.isEnPassant() || move.isPromotion() || move.movingPiece().name().endsWith("KING")
          || move.movingPiece().name().endsWith("ROOK")) {
        continue;
      }
      final Square from = move.moveSpecification().fromSquare();
      final Square to = move.moveSpecification().toSquare();
      final boolean removeFirst = move.isCapture() && rnd.nextInt(3) == 0;

      // ---- oracle (FIDE 4.3) ----
      final List<Square> order = new ArrayList<>(touched);
      if (removeFirst) {
        order.add(to);
        order.add(from);
      } else {
        order.add(from);
        if (move.isCapture()) {
          order.add(to);
        }
      }
      Square firstOwn = null;
      Square firstOpp = null;
      for (final Square sq : order) {
        if (pos.get(sq).getSide() == side) {
          firstOwn = firstOwn == null ? sq : firstOwn;
        } else {
          firstOpp = firstOpp == null ? sq : firstOpp;
        }
      }
      String required = "any";
      if (firstOwn != null && firstOpp != null && canCapture(legal, firstOwn, firstOpp)) {
        required = firstOwn.getName() + "x" + firstOpp.getName();
      } else {
        for (final Square sq : order) {
          if (pos.get(sq).getSide() == side) {
            if (hasMove(legal, sq)) {
              required = "move " + sq.getName();
              break;
            }
          } else if (capturable(legal, sq)) {
            required = "capture " + sq.getName();
            break;
          }
        }
      }
      final boolean expected = required.equals("any") || required.equals("move " + from.getName())
          || move.isCapture() && required.equals("capture " + to.getName())
          || move.isCapture() && required.equals(from.getName() + "x" + to.getName());

      // ---- engine ----
      final var session = new GameSession(new TimeControl(600000, 0), -1, true, board);
      session.startGame();
      BitboardPosition after = pos;
      boolean intervened = false;
      for (final Square sq : touched) {
        intervened |= session.recordEvent(side, BoardEvent.click(sq, pos.get(sq), 0)).isPresent();
      }
      if (removeFirst) {
        intervened |= session.recordEvent(side, BoardEvent.remove(to, pos.get(to), 0)).isPresent();
        intervened |= session.recordEvent(side, BoardEvent.dragMove(from, to, pos.get(from), 0)).isPresent();
      } else if (move.isCapture()) {
        intervened |= session.recordEvent(side, BoardEvent.dragCapture(from, to, pos.get(from), pos.get(to), 0)).isPresent();
      } else {
        intervened |= session.recordEvent(side, BoardEvent.dragMove(from, to, pos.get(from), 0)).isPresent();
      }
      after = pos.afterMove(move.moveSpecification(), side);
      if (intervened) {
        continue;
      }
      final var response = session.pressClockButton(side, after);
      final boolean accepted = response.type() == ArbiterResponseType.MOVE_ACCEPTED;
      total++;
      if (accepted != expected) {
        final StringBuilder kinds = new StringBuilder();
        for (final Square sq : touched) {
          kinds.append(pos.get(sq).getSide() == side ? (hasMove(legal, sq) ? "own " : "own-stuck ") : (capturable(legal, sq) ? "opp " : "opp-safe "));
        }
        final String key = (accepted ? "ENGINE ACCEPTS, FIDE REJECTS" : "ENGINE REJECTS, FIDE ACCEPTS") + " | touches: " + kinds
            + "| move: " + (move.isCapture() ? (removeFirst ? "capture(removal first)" : "capture") : "quiet") + " | " + response.type();
        mismatches.computeIfAbsent(key, k -> new ArrayList<>()).add(fen + " touched=" + touched + " move=" + from.getName()
            + (move.isCapture() ? "x" : "-") + to.getName() + " fideRequires=" + required + " engine=" + response.renderedPlayerMessage());
      }
    }
    System.out.println("ORACLE trials=" + total + " mismatchKinds=" + mismatches.size());
    org.junit.jupiter.api.Assertions.assertTrue(total > 10000, "too few comparable cases");
    for (final var e : mismatches.entrySet()) {
      System.out.println("ORACLE " + e.getValue().size() + "x " + e.getKey());
      System.out.println("ORACLE    e.g. " + e.getValue().get(0));
    }
    org.junit.jupiter.api.Assertions.assertTrue(mismatches.isEmpty(), mismatches.keySet().toString());
  }

  static boolean hasMove(List<LegalMove> legal, Square from) {
    return legal.stream().anyMatch(m -> !m.moveSpecification().isCastling() && m.moveSpecification().fromSquare() == from);
  }

  static boolean capturable(List<LegalMove> legal, Square sq) {
    return legal.stream().anyMatch(m -> m.isCapture() && !m.isEnPassant() && m.moveSpecification().toSquare() == sq);
  }

  static boolean canCapture(List<LegalMove> legal, Square from, Square to) {
    return legal.stream().anyMatch(m -> m.isCapture() && !m.moveSpecification().isCastling()
        && m.moveSpecification().fromSquare() == from && m.moveSpecification().toSquare() == to);
  }
}
