// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dlbbld.ashlarchess.board.LegalMove;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.otbchess.game.GameSession;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.server.message.MessageConverter;
import io.github.dlbbld.otbchess.server.model.GameRoom;

/**
 * Exploratory rule fuzzer, not part of the regular suite (the class name keeps Surefire from picking it up).
 *
 * <p>
 * Two simulated clients - mirroring what {@code game.js} does with each server message - drive the real
 * {@link GameWebSocketServer} with random legal moves, half-made moves, stray touches, clock presses, offers, claims,
 * Revert/Ready clicks, page refreshes and timer firings. After every action it checks that no handler failed, that
 * clocks and pause flags agree, that the player whose clock runs can use the board, that both boards and the server
 * show the same position, that a pending draw offer is visible to its recipient, and - from time to time - that an
 * untouched legal move is accepted, that what the arbiter told the player to do is then accepted, and that honest play
 * always gets a move accepted again.
 *
 * <p>
 * Run: {@code mvn -o -q test -Dtest=RuleFuzzer -Dfuzz.count=3000}. Options: {@code -Dfuzz.from} (first seed),
 * {@code -Dfuzz.steps} (actions per game), {@code -Dfuzz.verbose=true -Dfuzz.tail=400} (print the action log of each
 * finding), {@code -Dfuzz.skip=FINAL,COMPLETE,STALE,PENDINGREFRESH} (silence single checks, so that a known
 * finding does not hide further ones; the findings these were added for are fixed, and a run without them is clean).
 */
class RuleFuzzer {
  private static final Gson GSON = new Gson();
  private static final String[] FENS = { "",
      "r3k2r/pppq1ppp/2n2n2/3pp3/3PP3/2N2N2/PPPQ1PPP/R3K2R w KQkq - 0 8",
      "4k3/P6p/8/3pP3/8/8/p6P/4K2R w K d6 0 1", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
      "4k3/1P6/8/8/8/8/6p1/4K3 w - - 0 1", "4k3/4r3/8/8/8/8/4R3/4K3 w - - 99 80",
      "4k3/4r3/8/8/8/8/4R3/4K3 w - - 147 120", "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2" };

  static class Tasks {
    final List<Runnable> pending = new ArrayList<>();
    final ScheduledExecutorService executor = (ScheduledExecutorService) Proxy.newProxyInstance(
        ScheduledExecutorService.class.getClassLoader(), new Class<?>[] { ScheduledExecutorService.class },
        (proxy, method, args) -> switch (method.getName()) {
          case "schedule" -> {
            pending.add((Runnable) args[0]);
            yield future();
          }
          case "scheduleAtFixedRate", "scheduleWithFixedDelay" -> future();
          case "shutdownNow" -> List.of();
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });

    static ScheduledFuture<?> future() {
      return (ScheduledFuture<?>) Proxy.newProxyInstance(ScheduledFuture.class.getClassLoader(),
          new Class<?>[] { ScheduledFuture.class }, (p, m, a) -> switch (m.getName()) {
            case "cancel", "isDone", "isCancelled" -> false;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "compareTo", "getDelay" -> m.getReturnType() == int.class ? (Object) 0 : (Object) 0L;
            default -> null;
          });
    }
  }

  class Client {
    final String name;
    String side;
    boolean enabled;
    boolean myTurn;
    boolean active;
    boolean revert;
    boolean ready;
    boolean offerPanel;
    boolean toldFinal;
    String toldCastle;
    String toldTouched;
    boolean toldCastleAny;
    String toldCastleTouchSide;
    String token;
    String gameId;
    Map<String, String> board = new LinkedHashMap<>();
    final List<String> internalErrors = new ArrayList<>();
    final WebSocket socket;

    Client(String name) {
      this.name = name;
      socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[] { WebSocket.class },
          (proxy, method, args) -> switch (method.getName()) {
            case "send" -> {
              on(JsonParser.parseString((String) args[0]).getAsJsonObject());
              yield null;
            }
            case "isOpen" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
          });
    }

    void setBoard(JsonObject data) {
      if (data.has("board")) {
        board = new LinkedHashMap<>();
        for (final var e : data.getAsJsonObject("board").entrySet()) {
          board.put(e.getKey(), e.getValue().getAsString());
        }
      }
    }

    void turn(JsonObject data) {
      if (data.has("havingMove")) {
        myTurn = data.get("havingMove").getAsString().equals(side);
        enabled = myTurn;
      }
    }

    void on(JsonObject m) {
      final String type = m.get("type").getAsString();
      log.add("    <" + name + " " + type + (m.has("message") ? " \"" + clip(m.get("message").getAsString()) + "\"" : ""));
      switch (type) {
        case "gameCreated", "gameJoined" -> {
          side = m.get("side").getAsString();
          token = m.get("token").getAsString();
          gameId = m.get("gameId").getAsString();
          setBoard(m);
        }
        case "rematchStarted" -> {
          side = m.get("side").getAsString();
          token = m.get("token").getAsString();
          setBoard(m);
          active = true;
          turn(m);
          offerPanel = false;
          revert = false;
          ready = false;
          toldFinal = false;
          toldCastle = null;
        }
        case "resync" -> {
          side = m.get("side").getAsString();
          setBoard(m);
          final String state = m.get("state").getAsString();
          active = !state.equals("ENDED");
          offerPanel = false;
          revert = false;
          ready = false;
          myTurn = m.get("havingMove").getAsString().equals(side);
          enabled = myTurn && state.equals("IN_PROGRESS");
        }
        case "gameStarted" -> {
          active = true;
          turn(m);
        }
        case "move_accepted" -> {
          toldFinal = false;
          toldCastle = null;
          toldTouched = null;
          toldCastleAny = false;
          myTurn = false;
          enabled = false;
          revert = false;
          ready = false;
          offerPanel = false;
        }
        case "opponentMoved", "boardUpdate" -> {
          setBoard(m);
          turn(m);
          if (type.equals("opponentMoved")) {
            revert = false;
            ready = false;
          }
        }
        case "opponentBoardEvent" -> {
          if (active && !myTurn) {
            apply(board, m.getAsJsonObject("event"));
          }
        }
        case "touch_move_violation", "released_piece_violation" -> {
          revert = false;
          ready = false;
          final String text = m.get("message").getAsString();
          toldFinal = false;
          toldCastle = !text.startsWith("Castling has been started") ? null
              : text.contains("queenside") ? "QUEEN_SIDE" : text.contains("kingside") ? "KING_SIDE" : null;
          final var touched = java.util.regex.Pattern
              .compile("You first touched the \\w+ on ([a-h][1-8]), which has legal moves").matcher(text);
          toldTouched = type.equals("touch_move_violation") && touched.find() ? touched.group(1) : null;
          toldCastleAny = type.equals("touch_move_violation") && text.contains("castling move.");
          toldCastleTouchSide = !toldCastleAny ? null
              : text.contains("perform the queenside castling move") ? "QUEEN_SIDE"
                  : text.contains("perform the kingside castling move") ? "KING_SIDE" : "UNNAMED";
        }
        case "restoreRequired" -> {
          ready = false;
          revert = true;
        }
        case "positionRestored" -> {
          toldFinal = m.get("message").getAsString().contains("your move is final");
          setBoard(m);
          revert = false;
          ready = false;
          if (m.get("autoResumePending").getAsBoolean()) {
            enabled = false;
          }
        }
        case "waitingForReady" -> {
          enabled = false;
          revert = false;
          ready = true;
        }
        case "waitingForOpponentReady" -> enabled = false;
        case "gameResumed" -> {
          revert = false;
          ready = false;
          turn(m);
        }
        case "gameEnded" -> {
          active = false;
          enabled = false;
          offerPanel = false;
        }
        case "drawOffered" -> offerPanel = true;
        case "drawRejected", "drawOfferExpired" -> offerPanel = false;
        case "error" -> {
          if (m.has("devDetail")) {
            internalErrors.add(m.get("devDetail").getAsString());
          }
        }
        default -> {
        }
      }
    }
  }

  static String clip(String s) {
    return s.length() > 110 ? s.substring(0, 110) + "…" : s;
  }

  static void apply(Map<String, String> board, JsonObject e) {
    final String type = e.get("eventType").getAsString();
    final String sq = e.get("square").getAsString();
    final String target = e.get("targetSquare").getAsString();
    final String piece = e.get("piece").getAsString();
    switch (type) {
      case "DRAG_MOVE", "DRAG_CAPTURE" -> {
        board.put(sq, "NONE");
        board.put(target, piece);
      }
      case "REMOVE" -> board.put(sq, "NONE");
      case "RESTORE_TO_EMPTY", "RESTORE_TO_OCCUPIED" -> board.put(target, piece);
      default -> {
      }
    }
  }

  // ---- per-game state ----
  GameWebSocketServer server;
  Tasks resumeTasks;
  Tasks wrongClockTasks;
  Client a;
  Client b;
  GameRoom room;
  Random rnd;
  List<String> log;
  List<String> sideArea;
  String finding;

  GameSession session() {
    return room.getSession();
  }

  Client client(Side side) {
    return side.name().toLowerCase().equals(a.side) ? a : b;
  }

  Client mover() {
    return client(session().getHavingMove());
  }

  Client other() {
    return client(session().getHavingMove().getOppositeSide());
  }

  void send(Client c, JsonObject msg) {
    log.add(c.name + "> " + clip(describe(msg)));
    server.onMessage(c.socket, msg.toString());
  }

  static String describe(JsonObject msg) {
    final JsonObject copy = msg.deepCopy();
    copy.remove("boardState");
    return copy.toString();
  }

  void send(Client c, String type) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", type);
    send(c, msg);
  }

  void sendWithBoard(Client c, String type) {
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", type);
    msg.add("boardState", GSON.toJsonTree(c.board));
    send(c, msg);
  }

  void event(Client c, String type, String sq, String target, String piece, String displaced) {
    if (session().isRestorationResumePending() && skip("PENDINGREFRESH")) {
      return; // known: a page refresh inside the 1.5 s resume delay re-enables the board although events are dropped
    }
    final JsonObject e = new JsonObject();
    e.addProperty("eventType", type);
    e.addProperty("square", sq);
    e.addProperty("targetSquare", target);
    e.addProperty("piece", piece);
    e.addProperty("displacedPiece", displaced);
    apply(c.board, e);
    c.toldFinal = false; // the instruction referred to the board as it was
    c.toldCastle = null;
    c.toldTouched = null;
    c.toldCastleAny = false;
    if (type.equals("REMOVE")) {
      sideArea.add(piece);
    } else if (!displaced.equals("NONE")) {
      sideArea.add(displaced);
    }
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "boardEvent");
    msg.add("event", e);
    msg.add("boardState", GSON.toJsonTree(c.board));
    send(c, msg);
  }

  List<String> squares(Client c, boolean occupied) {
    final List<String> result = new ArrayList<>();
    for (final var e : new TreeMap<>(c.board).entrySet()) {
      if (e.getValue().equals("NONE") != occupied) {
        result.add(e.getKey());
      }
    }
    return result;
  }

  <T> T pick(List<T> list) {
    return list.get(rnd.nextInt(list.size()));
  }

  void garbage(Client c) {
    final List<String> occ = squares(c, true);
    final List<String> empty = squares(c, false);
    if (occ.isEmpty()) {
      return;
    }
    final String from = pick(occ);
    final String piece = c.board.get(from);
    switch (rnd.nextInt(10)) {
      case 0, 1, 2, 3 -> event(c, "CLICK", from, "NONE", piece, "NONE");
      case 4, 5 -> event(c, "DRAG_MOVE", from, pick(empty), piece, "NONE");
      case 6 -> {
        final String to = pick(occ);
        if (!to.equals(from)) {
          event(c, "DRAG_CAPTURE", from, to, piece, c.board.get(to));
        }
      }
      case 7 -> event(c, "REMOVE", from, "NONE", piece, "NONE");
      default -> {
        final String color = rnd.nextBoolean() ? "WHITE" : "BLACK";
        final String p = !sideArea.isEmpty() && rnd.nextBoolean() ? sideArea.remove(rnd.nextInt(sideArea.size()))
            : color + "_" + pick(List.of("QUEEN", "ROOK", "BISHOP", "KNIGHT"));
        final String to = rnd.nextInt(3) == 0 ? pick(occ) : pick(empty);
        if (c.board.get(to).equals("NONE")) {
          event(c, "RESTORE_TO_EMPTY", "NONE", to, p, "NONE");
        } else {
          event(c, "RESTORE_TO_OCCUPIED", "NONE", to, p, c.board.get(to));
        }
      }
    }
  }

  /** Events that turn the client's board into the position after {@code move}; king first. */
  List<String[]> plan(Client c, LegalMove move, boolean promotionByDrag) {
    return plan(c, move, promotionByDrag ? 1 : 0);
  }

  /** mode 0 natural, 1 promotion by dragging the pawn first, 2 captured piece removed first, 3 promoted piece placed first. */
  List<String[]> plan(Client c, LegalMove move, int mode) {
    final boolean promotionByDrag = mode == 1;
    final Map<String, String> target = MessageConverter.fromStaticPosition(
        session().getPositionBeforeTurn().afterMove(move.moveSpecification(), session().getHavingMove()));
    final Map<String, String> cur = new LinkedHashMap<>(c.board);
    final List<String[]> events = new ArrayList<>();
    if (promotionByDrag && move.isPromotion() && !move.moveSpecification().isCastling()) {
      final String from = move.moveSpecification().fromSquare().getName();
      final String to = move.moveSpecification().toSquare().getName();
      final String pawn = cur.get(from);
      if (!pawn.equals("NONE") && pawn.endsWith("PAWN")) {
        final String there = cur.get(to);
        events.add(new String[] { there.equals("NONE") ? "DRAG_MOVE" : "DRAG_CAPTURE", from, to, pawn, there });
        cur.put(from, "NONE");
        cur.put(to, pawn);
      }
    }
    if (mode == 2 && move.isCapture() && !move.moveSpecification().isCastling()) {
      final String sq = move.isEnPassant() ? move.enPassantCapturedPawnSquare().getName()
          : move.moveSpecification().toSquare().getName();
      if (!cur.get(sq).equals("NONE")) {
        events.add(new String[] { "REMOVE", sq, "NONE", cur.get(sq), "NONE" });
        cur.put(sq, "NONE");
      }
    }
    if (mode == 3 && move.isPromotion()) {
      final String to = move.moveSpecification().toSquare().getName();
      final String there = cur.get(to);
      events.add(new String[] { there.equals("NONE") ? "RESTORE_TO_EMPTY" : "RESTORE_TO_OCCUPIED", "NONE", to,
          target.get(to), there });
      cur.put(to, target.get(to));
    }
    final List<String> sources = new ArrayList<>();
    final List<String> sinks = new ArrayList<>();
    for (final String sq : new TreeMap<>(target).keySet()) {
      if (!cur.get(sq).equals(target.get(sq))) {
        if (!cur.get(sq).equals("NONE")) {
          sources.add(sq);
        }
        if (!target.get(sq).equals("NONE")) {
          sinks.add(sq);
        }
      }
    }
    sinks.sort((x, y) -> Boolean.compare(!target.get(x).endsWith("KING"), !target.get(y).endsWith("KING")));
    final List<String> restores = new ArrayList<>();
    for (final String sink : sinks) {
      final String need = target.get(sink);
      String from = null;
      for (final String s : sources) {
        if (cur.get(s).equals(need) && !s.equals(sink)) {
          from = s;
          break;
        }
      }
      if (from == null) {
        restores.add(sink);
        continue;
      }
      sources.remove(from);
      final String there = cur.get(sink);
      events.add(new String[] { there.equals("NONE") ? "DRAG_MOVE" : "DRAG_CAPTURE", from, sink, need, there });
      cur.put(from, "NONE");
      cur.put(sink, need);
    }
    for (final String s : sources) {
      if (!cur.get(s).equals(target.get(s)) && !cur.get(s).equals("NONE") && !restores.contains(s)) {
        events.add(new String[] { "REMOVE", s, "NONE", cur.get(s), "NONE" });
        cur.put(s, "NONE");
      }
    }
    for (final String sink : restores) {
      final String there = cur.get(sink);
      events.add(new String[] { there.equals("NONE") ? "RESTORE_TO_EMPTY" : "RESTORE_TO_OCCUPIED", "NONE", sink,
          target.get(sink), there });
      cur.put(sink, target.get(sink));
    }
    return events;
  }

  void play(Client c, List<String[]> events, int limit) {
    for (int i = 0; i < events.size() && i < limit; i++) {
      if (!c.enabled || !c.active) {
        return;
      }
      final String[] e = events.get(i);
      event(c, e[0], e[1], e[2], e[3], e[4]);
    }
  }

  void runTask(Tasks tasks) {
    if (!tasks.pending.isEmpty()) {
      log.add("timer fires (" + (tasks == resumeTasks ? "restoration resume" : "wrong-clock resume") + ")");
      tasks.pending.remove(0).run();
    }
  }

  static boolean skip(String key) {
    return System.getProperty("fuzz.skip", "").contains(key);
  }

  void reconnect(boolean first) {
    final Client old = first ? a : b;
    final Client fresh = new Client(old.name);
    fresh.side = old.side;
    fresh.token = old.token;
    fresh.gameId = old.gameId;
    if (first) {
      a = fresh;
    } else {
      b = fresh;
    }
    log.add(old.name + " refreshes the page");
    final JsonObject msg = new JsonObject();
    msg.addProperty("type", "resume");
    msg.addProperty("gameId", fresh.gameId);
    msg.addProperty("token", fresh.token);
    send(fresh, msg);
  }

  boolean fail(String what) {
    if (finding == null) {
      finding = what;
    }
    return false;
  }

  boolean check() {
    for (final Client c : new Client[] { a, b }) {
      if (!c.internalErrors.isEmpty()) {
        return fail("INTERNAL_ERROR " + c.internalErrors.get(0));
      }
    }
    final var s = session();
    if (s.getState() != GameState.IN_PROGRESS) {
      return true;
    }
    final boolean paused = s.isWaitingForRestoration() || s.isWaitingForReady() || s.isRestorationResumePending();
    final Side running = s.getClock().getRunningFor();
    if (paused && running != Side.NONE) {
      return fail("CLOCK_RUNS_WHILE_PAUSED running=" + running + " restoring=" + s.isWaitingForRestoration()
          + " ready=" + s.isWaitingForReady() + " pending=" + s.isRestorationResumePending());
    }
    if (!paused && running == Side.NONE && wrongClockTasks.pending.isEmpty()) {
      return fail("CLOCK_STOPPED_FOREVER (no pause flag, no timer pending)");
    }
    if (!paused && running != Side.NONE && running != s.getHavingMove()) {
      return fail("CLOCK_RUNS_FOR_WRONG_SIDE running=" + running + " havingMove=" + s.getHavingMove());
    }
    if (s.isRestorationResumePending() && resumeTasks.pending.isEmpty()) {
      return fail("RESUME_PENDING_WITHOUT_TIMER");
    }
    final Client m = mover();
    final Client o = other();
    if (!paused && running != Side.NONE) {
      if (!m.enabled || !m.myTurn) {
        return fail("MOVER_BOARD_LOCKED while the mover's clock runs (enabled=" + m.enabled + " myTurn=" + m.myTurn + ")");
      }
      if (o.enabled || o.myTurn) {
        return fail("OPPONENT_BOARD_ENABLED while the mover's clock runs");
      }
    }
    if (s.isWaitingForRestoration() && !m.revert && !m.enabled) {
      return fail("RESTORATION_WITHOUT_REVERT_BUTTON_OR_BOARD");
    }
    if (s.isWaitingForRestoration() && !m.revert) {
      return fail("RESTORATION_WITHOUT_REVERT_BUTTON (board enabled=" + m.enabled + ")");
    }
    if (s.isWaitingForReady()) {
      for (final Client c : new Client[] { a, b }) {
        final Side cs = c.side.equals("white") ? Side.WHITE : Side.BLACK;
        if (!s.isReady(cs) && !c.ready) {
          return fail("READY_WAIT_WITHOUT_READY_BUTTON for " + c.side);
        }
      }
    }
    final var offers = s.getDrawOfferManager();
    for (final Client c : new Client[] { a, b }) {
      final Side cs = c.side.equals("white") ? Side.WHITE : Side.BLACK;
      final boolean pendingForMe = offers.isDrawOffered() && offers.getOfferingSide() == cs.getOppositeSide();
      if (pendingForMe && !offers.hasOpponentTouchedPiece() && !c.offerPanel) {
        return fail("DRAW_OFFER_PENDING_BUT_NOT_SHOWN to " + c.side);
      }
      if (!pendingForMe && c.offerPanel && !skip("STALE")) {
        return fail("STALE_DRAW_OFFER_PANEL on " + c.side);
      }
    }
    if (!o.board.equals(m.board)) {
      return fail("OPPONENT_SEES_A_DIFFERENT_BOARD paused=" + paused);
    }
    if (!m.board.equals(MessageConverter.fromStaticPosition(room.getPhysicalPosition()))) {
      // The server's notion of the physical board must follow the mover's board.
      return fail("PHYSICAL_POSITION_DRIFT paused=" + paused);
    }
    return true;
  }

  /** Honest recovery: restore, get ready, then some legal move must be accepted. */
  boolean rescue() {
    log.add("--- rescue ---");
    final var s = session();
    final List<LegalMove> moves = new ArrayList<>(s.getBoard().getLegalMoves());
    final int plyBefore = s.getBoard().getPerformedMovesAsSan().size();
    final int perPass = moves.size() * 4;
    for (int round = -1; round < perPass * 3; round++) {
      // Every second round only presses the clock: after a restoration the move may already be final.
      // Three passes over the moves: a rejected attempt can lift a lock that blocked earlier candidates.
      final int attempt = round < 0 || round % 2 == 1 ? -1 : (round % perPass) / 2;
      for (int guard = 0; guard < 20; guard++) {
        if (s.getState() != GameState.IN_PROGRESS) {
          return true;
        }
        if (s.isWaitingForRestoration()) {
          if (!mover().revert) {
            return fail("RESCUE: restoration without Revert button");
          }
          send(mover(), "restorePosition");
        } else if (s.isWaitingForReady()) {
          send(a, "readyToContinue");
          send(b, "readyToContinue");
        } else if (s.isRestorationResumePending()) {
          if (resumeTasks.pending.isEmpty()) {
            return fail("RESCUE: resume pending without timer");
          }
          while (!resumeTasks.pending.isEmpty()) {
            runTask(resumeTasks);
          }
        } else if (s.getClock().getRunningFor() == Side.NONE) {
          if (wrongClockTasks.pending.isEmpty()) {
            return fail("RESCUE: clock stopped, nothing pending");
          }
          while (!wrongClockTasks.pending.isEmpty()) {
            runTask(wrongClockTasks);
          }
        } else {
          break;
        }
        if (!check()) {
          return false;
        }
      }
      if (s.getState() != GameState.IN_PROGRESS || s.getBoard().getPerformedMovesAsSan().size() != plyBefore) {
        return true;
      }
      final Client m = mover();
      if (attempt >= 0) {
        final LegalMove move = moves.get(attempt / 2);
        final boolean byDrag = attempt % 2 == 1;
        if (byDrag && !move.isPromotion()) {
          continue;
        }
        log.add("rescue tries " + move.moveSpecification() + (byDrag ? " (promotion by drag)" : ""));
        play(m, plan(m, move, byDrag), 99);
        if (!check()) {
          return false;
        }
      } else {
        log.add("rescue presses the clock as is");
      }
      if (s.getState() == GameState.IN_PROGRESS && s.getBoard().getPerformedMovesAsSan().size() == plyBefore
          && !s.isWaitingForRestoration()) {
        sendWithBoard(m, "clockPress");
      }
      if (!check()) {
        return false;
      }
      if (s.getState() != GameState.IN_PROGRESS || s.getBoard().getPerformedMovesAsSan().size() != plyBefore) {
        return true;
      }
    }
    return fail("DEADLOCK: no legal move is accepted after honest recovery");
  }

  int ply() {
    return session().getBoard().getPerformedMovesAsSan().size();
  }

  boolean settle() {
    final var s = session();
    for (int guard = 0; guard < 20; guard++) {
      if (s.getState() != GameState.IN_PROGRESS) {
        return true;
      }
      if (s.isWaitingForRestoration()) {
        if (!mover().revert) {
          return fail("SETTLE: restoration without Revert button");
        }
        send(mover(), "restorePosition");
      } else if (s.isWaitingForReady()) {
        send(a, "readyToContinue");
        send(b, "readyToContinue");
      } else if (s.isRestorationResumePending()) {
        while (!resumeTasks.pending.isEmpty()) {
          runTask(resumeTasks);
        }
      } else if (s.getClock().getRunningFor() == Side.NONE) {
        while (!wrongClockTasks.pending.isEmpty()) {
          runTask(wrongClockTasks);
        }
      } else {
        return check();
      }
      if (!check()) {
        return false;
      }
    }
    return fail("SETTLE: the game does not come back to play");
  }

  /** What the arbiter told the player to do must then be accepted. */
  void obey() {
    final var s = session();
    final Client m = mover();
    final int before = ply();
    if (m.toldFinal && !skip("FINAL")) {
      log.add("--- obey: the arbiter said the move is final, press the clock ---");
      if (!settle() || s.getState() != GameState.IN_PROGRESS || ply() != before || !m.toldFinal) {
        return;
      }
      sendWithBoard(m, "clockPress");
      if (s.getState() == GameState.IN_PROGRESS && ply() == before) {
        fail("TOLD_MOVE_IS_FINAL_BUT_CLOCK_PRESS_REJECTED");
      }
    } else if (m.toldCastle != null && !skip("COMPLETE")) {
      final String side = m.toldCastle;
      log.add("--- obey: the arbiter said castling must be completed (" + side + ") ---");
      if (!settle() || s.getState() != GameState.IN_PROGRESS || ply() != before || !side.equals(m.toldCastle)) {
        return;
      }
      for (final LegalMove move : s.getBoard().getLegalMoves()) {
        if (move.moveSpecification().isCastling() && move.moveSpecification().castlingMove().name().equals(side)) {
          play(m, plan(m, move, 0), 99);
          if (s.getState() == GameState.IN_PROGRESS && ply() == before && !s.isWaitingForRestoration()) {
            sendWithBoard(m, "clockPress");
          }
          if (s.getState() == GameState.IN_PROGRESS && ply() == before) {
            fail("TOLD_TO_COMPLETE_CASTLING_BUT_COMPLETION_REJECTED");
          }
          return;
        }
      }
      fail("TOLD_TO_COMPLETE_CASTLING_BUT_CASTLING_IS_NOT_LEGAL");
    } else if (m.toldTouched != null || m.toldCastleAny) {
      final String from = m.toldTouched;
      final boolean castle = m.toldCastleAny;
      final String castleSide = m.toldCastleTouchSide;
      log.add("--- obey: touch-move ruling, " + (castle ? "castle" : "move the piece on " + from) + " ---");
      if (!settle() || s.getState() != GameState.IN_PROGRESS || ply() != before) {
        return;
      }
      final List<LegalMove> candidates = new ArrayList<>();
      for (final LegalMove move : s.getBoard().getLegalMoves()) {
        final var spec = move.moveSpecification();
        if (castle ? spec.isCastling() && spec.castlingMove().name().equals(castleSide)
            : !spec.isCastling() && spec.fromSquare().getName().equals(from)) {
          candidates.add(move);
        }
      }
      if (candidates.isEmpty()) {
        fail("TOUCH_MOVE_RULING_NAMES_A_PIECE_WITHOUT_LEGAL_MOVES castle=" + castle);
        return;
      }
      // The castling ruling names its side, so exactly that castling is tried. A named piece is tried with one
      // random move.
      final List<LegalMove> tries = castle ? candidates : List.of(pick(candidates));
      for (final LegalMove move : tries) {
        if (!settle() || s.getState() != GameState.IN_PROGRESS || ply() != before) {
          return;
        }
        play(mover(), plan(mover(), move, 0), 99);
        if (s.getState() == GameState.IN_PROGRESS && ply() == before && !s.isWaitingForRestoration()) {
          sendWithBoard(mover(), "clockPress");
        }
        if (s.getState() != GameState.IN_PROGRESS || ply() != before) {
          return;
        }
      }
      fail(castle ? "TOLD_TO_CASTLE_BUT_CASTLING_REJECTED" : "TOLD_TO_MOVE_TOUCHED_PIECE_BUT_ITS_MOVE_REJECTED");
    }
  }

  /** At the untouched start of a turn any legal move, physically executed in any FIDE-conform order, is accepted. */
  void cleanMove() {
    final var s = session();
    final Client m = mover();
    if (s.getClock().getRunningFor() != s.getHavingMove() || !m.enabled || s.getMustExecuteMove() != null) {
      return;
    }
    final List<LegalMove> moves = s.getBoard().getLegalMoves();
    if (moves.isEmpty()) {
      return;
    }
    final LegalMove move = pick(moves);
    final int mode = rnd.nextInt(4);
    final int before = ply();
    log.add("--- clean move " + move.moveSpecification() + " mode " + mode + " ---");
    play(m, plan(m, move, mode), 99);
    if (s.getState() != GameState.IN_PROGRESS || ply() != before) {
      return;
    }
    sendWithBoard(m, "clockPress");
    if (s.getState() == GameState.IN_PROGRESS && ply() == before) {
      fail("CLEAN_LEGAL_MOVE_REJECTED mode=" + mode + " capture=" + move.isCapture() + " ep=" + move.isEnPassant()
          + " promotion=" + move.isPromotion() + " castling=" + move.isCastling());
    }
  }

  void step() {
    final var s = session();
    final Client m = mover();
    final Client o = other();
    final int r = rnd.nextInt(100);
    if (r < 22) {
      if (m.enabled) {
        final List<LegalMove> moves = s.getBoard().getLegalMoves();
        if (!moves.isEmpty()) {
          play(m, plan(m, pick(moves), rnd.nextInt(4) == 0), rnd.nextInt(3) == 0 ? 1 : 99);
        }
      }
    } else if (r < 40) {
      if (m.enabled) {
        garbage(m);
      }
    } else if (r < 60) {
      if (m.myTurn) {
        sendWithBoard(m, "clockPress");
      }
    } else if (r < 70) {
      if (m.revert) {
        send(m, "restorePosition");
      }
    } else if (r < 78) {
      final Client c = rnd.nextBoolean() ? a : b;
      if (c.ready) {
        c.ready = false;
        send(c, "readyToContinue");
      }
    } else if (r < 83) {
      runTask(resumeTasks);
    } else if (r < 86) {
      runTask(wrongClockTasks);
    } else if (r < 89) {
      sendWithBoard(rnd.nextBoolean() ? a : b, "offerDraw");
    } else if (r < 91) {
      final Client c = rnd.nextBoolean() ? a : b;
      if (c.offerPanel) {
        send(c, rnd.nextInt(4) == 0 ? "acceptDraw" : "rejectDraw");
      }
    } else if (r < 94) {
      final Client c = rnd.nextBoolean() ? a : b;
      final JsonObject msg = new JsonObject();
      msg.addProperty("type", "claimDraw");
      final String claim = pick(List.of("THREEFOLD_ON_BOARD", "THREEFOLD_WITH_MOVE", "FIFTY_MOVE_ON_BOARD",
          "FIFTY_MOVE_WITH_MOVE"));
      msg.addProperty("claimType", claim);
      if (claim.endsWith("WITH_MOVE") && rnd.nextInt(3) != 0) {
        final List<String> sans = s.getBoard().getLegalMovesAsSan();
        msg.addProperty("san", sans.isEmpty() || rnd.nextInt(5) == 0 ? "Qh7" : pick(sans));
      }
      send(c, msg);
    } else if (r < 96) {
      send(o, "opponentClockPressed");
    } else if (r < 98) {
      if (!skip("RECONNECT")) {
        reconnect(rnd.nextBoolean());
      }
    } else {
      rescue();
    }
  }

  void runGame(long seed, int steps) throws Exception {
    rnd = new Random(seed);
    log = new ArrayList<>();
    sideArea = new ArrayList<>();
    finding = null;
    server = new GameWebSocketServer("127.0.0.1", 0);
    resumeTasks = new Tasks();
    wrongClockTasks = new Tasks();
    for (final String[] f : new String[][] { { "clockExecutor", "r" }, { "maintenance", "w" } }) {
      final var field = GameWebSocketServer.class.getDeclaredField(f[0]);
      field.setAccessible(true);
      ((java.util.concurrent.ExecutorService) field.get(server)).shutdownNow();
      field.set(server, f[1].equals("r") ? resumeTasks.executor : wrongClockTasks.executor);
    }
    a = new Client("A");
    b = new Client("B");
    final JsonObject create = new JsonObject();
    create.addProperty("type", "createGame");
    create.addProperty("side", rnd.nextBoolean() ? "white" : "black");
    create.addProperty("initialTimeMs", 3_600_000);
    create.addProperty("incrementMs", 0);
    create.addProperty("maxIllegalMoves", -1);
    create.addProperty("autoResumeAfterRestore", rnd.nextBoolean());
    final String fen = FENS[rnd.nextInt(FENS.length)];
    if (!fen.isEmpty()) {
      create.addProperty("fen", fen);
    }
    send(a, create);
    final var roomsField = GameWebSocketServer.class.getDeclaredField("gameRooms");
    roomsField.setAccessible(true);
    @SuppressWarnings("unchecked") final Map<String, GameRoom> rooms = (Map<String, GameRoom>) roomsField.get(server);
    final String id = rooms.keySet().iterator().next();
    room = rooms.get(id);
    final JsonObject join = new JsonObject();
    join.addProperty("type", "joinGame");
    join.addProperty("gameId", id);
    send(b, join);
    int lastPly = -1;
    for (int i = 0; i < steps && finding == null; i++) {
      if (session().getState() != GameState.IN_PROGRESS) {
        for (final Client c : new Client[] { a, b }) {
          send(c, "requestPgn");
          if (c.active || c.enabled) {
            fail("CLIENT_STILL_ACTIVE_AFTER_GAME_END " + c.side);
          }
        }
        check();
        if (finding != null || rnd.nextBoolean()) {
          break;
        }
        send(a, "rematchOffer");
        send(b, "rematchOffer");
        if (session().getState() != GameState.IN_PROGRESS) {
          fail("REMATCH_DID_NOT_START");
          break;
        }
        sideArea.clear();
        lastPly = -1;
        check();
        continue;
      }
      try {
        final boolean fresh = ply() != lastPly;
        lastPly = ply();
        if (fresh && rnd.nextInt(3) == 0) {
          cleanMove();
        } else if (rnd.nextInt(12) == 0 && !skip("OBEY")) {
          obey();
        } else {
          step();
        }
      } catch (final RuntimeException e) {
        fail("HARNESS_OR_SERVER_EXCEPTION " + e);
      }
      if (finding == null) {
        check();
      }
    }
    if (finding == null && session().getState() == GameState.IN_PROGRESS) {
      rescue();
    }
  }

  @Test
  void fuzz() throws Exception {
    final long from = Long.getLong("fuzz.from", 1);
    final long count = Long.getLong("fuzz.count", 2000);
    final int steps = Integer.getInteger("fuzz.steps", 120);
    final boolean verbose = Boolean.getBoolean("fuzz.verbose");
    final Map<String, List<Long>> findings = new TreeMap<>();
    final java.io.PrintStream out = System.out;
    final java.io.PrintStream err = System.err;
    final var sink = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
    for (long seed = from; seed < from + count; seed++) {
      System.setOut(sink);
      System.setErr(sink);
      try {
        runGame(seed, steps);
      } finally {
        System.setOut(out);
        System.setErr(err);
      }
      if (finding != null) {
        final String key = finding.replaceAll("[A-Z0-9]{12}", "ID").replaceAll("\\d+", "N");
        findings.computeIfAbsent(clip(key), k -> new ArrayList<>()).add(seed);
        if (verbose) {
          out.println("FUZZ seed " + seed + " -> " + finding);
          final int start = Long.getLong("fuzz.tail", 60) >= log.size() ? 0 : log.size() - (int) (long) Long.getLong("fuzz.tail", 60);
          log.subList(start, log.size()).forEach(l -> out.println("FUZZLOG " + l));
        }
      }
    }
    for (final var e : findings.entrySet()) {
      out.println("FUZZ " + e.getValue().size() + "x " + e.getKey() + " seeds=" + e.getValue().subList(0, Math.min(8, e.getValue().size())));
    }
    out.println("FUZZ done seeds " + from + ".." + (from + count - 1) + " findings=" + findings.size());
  }
}
