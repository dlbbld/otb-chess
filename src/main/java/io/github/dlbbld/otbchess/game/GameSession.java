// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.game;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.dlbbld.ashlarchess.adjudication.AdjudicationResult;
import io.github.dlbbld.ashlarchess.adjudication.Adjudicator;
import io.github.dlbbld.ashlarchess.bitboard.BitboardPosition;
import io.github.dlbbld.ashlarchess.board.Board;
import io.github.dlbbld.ashlarchess.board.enums.Side;
import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.ashlarchess.board.MoveSpecification;
import io.github.dlbbld.ashlarchess.pgn.PgnCreate;
import io.github.dlbbld.otbchess.placement.PlacementBoard.PlacedPiece;
import io.github.dlbbld.otbchess.arbiter.ArbiterEngine;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse;
import io.github.dlbbld.otbchess.arbiter.BoardMark;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponse.IllegalMoveDetail;
import io.github.dlbbld.otbchess.arbiter.ArbiterResponseType;
import io.github.dlbbld.otbchess.arbiter.MidPlayValidator;
import io.github.dlbbld.otbchess.arbiter.RestoreTargetInvariant;
import io.github.dlbbld.otbchess.event.ActionSequence;
import io.github.dlbbld.otbchess.event.BoardEvent;
import io.github.dlbbld.otbchess.game.model.DrawClaimResult;
import io.github.dlbbld.otbchess.game.model.DrawClaimType;
import io.github.dlbbld.otbchess.game.model.GameResult;
import io.github.dlbbld.otbchess.game.model.GameResultType;
import io.github.dlbbld.otbchess.game.model.GameState;
import io.github.dlbbld.otbchess.game.model.TimeControl;
import io.github.dlbbld.otbchess.message.MessageKey;

/**
 * Central orchestrator for an OTB Chess game.
 *
 * <p>
 * Holds the game state and coordinates all managers: clock, arbiter, draw offers, draw claims.
 *
 * <p>
 * Thread safety: methods are synchronized because WebSocket messages arrive from different threads.
 */
public class GameSession {

  private final Board board;
  private final ClockManager clock;
  private final ArbiterEngine arbiter;
  private final DrawOfferManager drawOfferManager;
  private final DrawClaimManager drawClaimManager;
  private final TimeControl timeControl;
  private final boolean autoResumeAfterRestore;

  private GameState state;
  private GameResult result;
  private long endedAtMs;

  // For the personalised end-of-game messages: the side that performed the terminating action
  // (resigned, flagged, or accepted a draw); the client phrases the message in the second person
  // for each player. drawExceptionByInsufficientMaterial additionally distinguishes the two
  // resignation / flag-fall draw reasons (material shortage vs. unwinnable despite material).
  private Side terminationActor = Side.NONE;
  private boolean drawExceptionByInsufficientMaterial;

  // Per-turn state
  private ActionSequence currentSequence;
  private BitboardPosition positionBeforeTurn;
  private final Set<Square> removedSquaresThisTurn;

  // State for "must execute specified move" after rejected draw claim. The SAN is kept alongside
  // the move so the "not executed" message can name the move the player still owes.
  private MoveSpecification mustExecuteMove;
  private String mustExecuteMoveSan;

  // FIDE 9.2 / 9.3: a player may make at most one draw claim per move. Set when a claim
  // attempt is processed (accepted or rejected, but not when the SAN was invalid — the
  // player presented no legal intended move, so the claim is not considered). Reset on
  // startNewTurn().
  private boolean claimMadeThisTurn;

  // Claims while NOT having the move (FIDE 9.2/9.3 require the move). Teaching philosophy: the
  // claim buttons stay enabled so the player can repeat the fault and learn — the arbiter
  // escalates instead: plain rejection, then a warning, then loss of the game on the third
  // wrong-time claim. Counted per player across the whole game (a warning, once given, stands).
  private static final int WRONG_TIME_CLAIM_LIMIT = 3;
  private final Map<Side, Integer> wrongTimeClaimCounts = new EnumMap<>(Side.class);

  // Second-or-later claims on the SAME move (FIDE 9.2/9.3 allow one claim per move). Same
  // philosophy: buttons stay enabled; the first violation gets the warning immediately (the
  // player already used their legitimate claim), the second loses the game. Violations are
  // counted per player across the whole game — a legitimate single claim on a later move is
  // never a violation, but a repeated one after the warning loses.
  private static final int REPEAT_CLAIM_VIOLATION_LIMIT = 2;
  private final Map<Side, Integer> repeatClaimViolationCounts = new EnumMap<>(Side.class);

  // Claims AFTER touching/moving a piece on this move, before the clock press (FIDE 9.4: the
  // right to claim is lost once a piece is touched). Same ladder as the wrong-time claims:
  // rejection, warning, loss on the third — counted per player across the whole game, i.e. the
  // count accumulates over different moves.
  private static final int AFTER_TOUCH_CLAIM_LIMIT = 3;
  private final Map<Side, Integer> afterTouchClaimCounts = new EnumMap<>(Side.class);

  // FIDE 9.5.3: an incorrect draw claim adds 2 minutes to the opponent's clock.
  // (Article-9 of the Competitive Rules of Play; rapid/blitz Appendix A.3 reduces this
  // to 1 minute — not differentiated here, see fide-deviations.md.)
  private static final long INCORRECT_CLAIM_PENALTY_MS = 2 * 60 * 1000;

  // Ready-to-continue tracking (both players must click after arbiter intervention)
  private boolean waitingForReady;
  private boolean whiteReady;
  private boolean blackReady;
  private boolean waitingForRestoration;
  private boolean restorationResumePending;
  // A clock press of the side to move ended the automatic restoration pause itself. The server
  // announces that resume, because the delayed resume then finds nothing left to do.
  private boolean resumedByClockPress;
  // The last press of the own lever was the press of a lever that was up: the clock was running for
  // the player and the arbiter ruled on the move. The server lets both players hear such a press.
  private boolean clockPressRegistered;
  private BitboardPosition restorationTargetPosition;
  // True when the current restoration was caused by a released-piece (FIDE 4.7) violation. While set,
  // completeRestoration() preserves the released-piece rule window so the committed move stays final
  // across revert cycles until the player actually plays it. Cleared on a new turn / other violations.
  private boolean restorationFromReleasedPiece;
  // Latched at the completed legal release; only startNewTurn may clear it. Recovery state
  // and the action-sequence rule window are deliberately not the authority for move finality.
  private ArbiterEngine.FinalMoveCommitment finalMoveCommitment;

  public GameSession(TimeControl timeControl) {
    this(timeControl, io.github.dlbbld.otbchess.arbiter.IllegalMoveTracker.DEFAULT_MAX_ILLEGAL_MOVES);
  }

  public GameSession(TimeControl timeControl, int maxIllegalMoves) {
    this(timeControl, maxIllegalMoves, true);
  }

  public GameSession(TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore) {
    this(timeControl, maxIllegalMoves, autoResumeAfterRestore, new Board());
  }

  /**
   * Constructor for games that start from a non-initial position (e.g. a custom FEN supplied on the start screen). The
   * board is pre-built by the caller; FEN parsing and validation happen at the server boundary so the validation reason
   * can be returned to the client before the {@link GameSession} is created.
   *
   * <p>
   * The starting side-to-move is taken from the supplied board, so e.g. a FEN with Black to move correctly starts the
   * clock on Black at game start.
   */
  public GameSession(TimeControl timeControl, int maxIllegalMoves, boolean autoResumeAfterRestore,
      Board startingBoard) {
    this.board = startingBoard;
    this.clock = new ClockManager(timeControl);
    this.arbiter = new ArbiterEngine(maxIllegalMoves);
    this.drawOfferManager = new DrawOfferManager();
    this.drawClaimManager = new DrawClaimManager();
    this.timeControl = timeControl;
    this.autoResumeAfterRestore = autoResumeAfterRestore;

    this.state = GameState.WAITING_FOR_PLAYERS;
    this.result = null;
    this.currentSequence = new ActionSequence(board.getSideToMove());
    this.positionBeforeTurn = board.getBitboardPosition();
    this.removedSquaresThisTurn = new HashSet<>();
    this.mustExecuteMove = null;
    this.waitingForReady = false;
    this.whiteReady = false;
    this.blackReady = false;
    this.waitingForRestoration = false;
    this.restorationResumePending = false;
    this.restorationTargetPosition = positionBeforeTurn;
  }

  /**
   * Starts the game. Both players are connected.
   */
  public synchronized void startGame() {
    if (state != GameState.WAITING_FOR_PLAYERS) {
      throw new IllegalStateException("A game can be started once only, state is " + state);
    }
    this.state = GameState.IN_PROGRESS;
    // The clock starts on whichever side is to move in the starting position,
    // not blindly on White, so a FEN with Black to move correctly clocks Black.
    this.clock.startClock(board.getSideToMove());
  }

  // ===== Mid-play event recording =====

  /**
   * Records a board event during play. Returns an arbiter response if mid-play intervention is needed.
   */
  public synchronized Optional<ArbiterResponse> recordEvent(Side side, BoardEvent event) {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }
    if (side != board.getSideToMove()) {
      return Optional.empty();
    }
    // A board event can itself decide the game through the misconduct ladder. Expiry must
    // win that race just as it does for a clock press, claim, offer or resignation.
    if (checkFlagFall().isPresent()) {
      return Optional.empty();
    }
    if (isTurnEventLimitReached()) {
      return Optional.empty(); // not recorded; the server tells the sender
    }

    // Check mid-play validation
    Optional<ArbiterResponse> midPlayResponse = MidPlayValidator.validate(event, side, positionBeforeTurn,
        removedSquaresThisTurn);
    if (midPlayResponse.isPresent()) {
      wrongClockPressPausedFor = null; // This intervention supersedes the old admonishment pause.
      pressWithoutMovePause = false;
      final Optional<BitboardPosition> committedReleasePosition = finalMoveCommitment != null
          ? Optional.of(finalMoveCommitment.position())
          : arbiter.findReleasedPieceCommitmentPosition(board, currentSequence);
      if (committedReleasePosition.isPresent()) {
        RestoreTargetInvariant.verify(board, committedReleasePosition.get());
        midPlayResponse = Optional.of(midPlayResponse.get().withRestorePosition(committedReleasePosition.get()));
        restorationFromReleasedPiece = true;
      } else {
        restorationFromReleasedPiece = false;
      }
      clock.stopClock();
      // Moving an OPPONENT's piece escalates like the other misconducts (A-007): notice,
      // notice + warning, loss of the game on the third time — counted across the whole game.
      if (midPlayResponse.get().playerMessageKey() == MessageKey.ARBITER_POSITION_CHANGE_OPPONENT_PIECE) {
        return Optional.of(escalateMovedOpponentPiece(side, midPlayResponse.get()));
      }
      return midPlayResponse;
    }

    // Track removed opponent pieces. Two paths:
    // • DRAG_CAPTURE: the player drops their own piece on top of an opponent piece;
    // the displaced piece (= the opponent piece) is implicitly removed. The
    // opponent piece is identified by event.displacedPiece().
    // • REMOVE: the player drags an opponent piece off the board explicitly,
    // as the first step of a capture-by-removal sequence. The opponent piece is
    // identified by event.piece(); displacedPiece is NONE for REMOVE events.
    if (event.type() == io.github.dlbbld.otbchess.event.BoardEventType.DRAG_CAPTURE) {
      if (event.displacedPiece() != io.github.dlbbld.ashlarchess.board.enums.Piece.NONE
          && event.displacedPiece().getSide() != side) {
        removedSquaresThisTurn.add(event.targetSquare());
      }
    } else if (event.type() == io.github.dlbbld.otbchess.event.BoardEventType.REMOVE) {
      if (event.piece() != io.github.dlbbld.ashlarchess.board.enums.Piece.NONE && event.piece().getSide() != side) {
        removedSquaresThisTurn.add(event.square());
      }
    }

    currentSequence.addEvent(event);
    if (finalMoveCommitment == null) {
      arbiter.findFinalMoveCommitment(board, currentSequence)
          .filter(commitment -> mustExecuteMove == null
              || mustExecuteMove.equals(commitment.move().moveSpecification()))
          .ifPresent(commitment -> finalMoveCommitment = commitment);
    }
    return Optional.empty();
  }

  /**
   * The arbiter weighs every event of a turn again when ruling on it, so the cost of a ruling grows with the square
   * of the turn's length. No player comes near this number of piece movements in one turn (a long fumble has a few
   * dozen); the limit keeps a scripted client from keeping the server busy with a single endless turn.
   */
  public static final int MAX_BOARD_EVENTS_PER_TURN = 500;

  /** @return whether this turn holds as many board events as are recorded; further ones are refused. */
  public synchronized boolean isTurnEventLimitReached() {
    return currentSequence.getEvents().size() >= MAX_BOARD_EVENTS_PER_TURN;
  }

  // ===== Clock press =====

  /**
   * Player presses the clock. Triggers move evaluation.
   *
   * @param side          the side pressing the clock
   * @param afterPosition the current board state as seen on the physical board
   * @return the arbiter response
   */
  public synchronized ArbiterResponse pressClockButton(Side side, BitboardPosition afterPosition) {
    return pressClockButton(side, afterPosition, List.of());
  }

  /**
   * The clock press of a game with free placement (E-005).
   *
   * @param afterPosition the position the rules look at: every piece on the square it counts on
   * @param unclearPieces the pieces that lie in an unclear placement; with any, the arbiter makes the call for
   *                      them and does not rule on the move
   */
  public synchronized ArbiterResponse pressClockButton(Side side, BitboardPosition afterPosition,
      List<PlacedPiece> unclearPieces) {
    clockPressRegistered = false;
    return markRuling(ruleOnClockPress(side, afterPosition, unclearPieces));
  }

  /**
   * Takes over the squares a ruling on the move points at - the move shown with a clock press or with a draw offer.
   * An accepted move has started a new turn and the end of the game has taken the circles off already; a press that
   * is not ruled on (not the player's turn, a pause) leaves them as they are.
   */
  private ArbiterResponse markRuling(ArbiterResponse response) {
    switch (response.type()) {
      // A ruling on what was touched or released replaces what the arbiter pointed at before - also with nothing.
      case TOUCH_MOVE_VIOLATION, RELEASED_PIECE_VIOLATION -> boardMarks = response.boardMarks();
      case ILLEGAL_MOVE, ILLEGAL_MOVE_GAME_LOST -> {
        // A press without a move (FIDE 7.5.3) changed nothing on the board: what was pointed at stays.
        if (!response.illegalMoveDetail().map(detail -> detail.noMoveMade()).orElse(false)) {
          // The touched piece still has to move after this illegal move is taken back: its circle stays.
          final List<BoardMark> marks = new ArrayList<>(marksOf(BoardMark.Color.GREEN));
          marks.addAll(response.boardMarks());
          boardMarks = List.copyOf(marks);
        }
      }
      default -> {
      }
    }
    return response;
  }

  private List<BoardMark> marksOf(BoardMark.Color color) {
    return boardMarks.stream().filter(mark -> mark.color() == color).toList();
  }

  private ArbiterResponse ruleOnClockPress(Side side, BitboardPosition afterPosition,
      List<PlacedPiece> unclearPieces) {
    if (state != GameState.IN_PROGRESS) {
      return ArbiterResponse.incompleteMove("The game is not in progress.");
    }
    if (side != board.getSideToMove()) {
      return ArbiterResponse.incompleteMove("It is not your turn.");
    }
    if (waitingForRestoration) {
      return ArbiterResponse
          .incompleteMove("The game is paused. Please restore the position before pressing the clock.");
    }
    if (waitingForReady) {
      return ArbiterResponse.incompleteMove("The game is paused. Please wait until both players are ready.");
    }
    if (restorationResumePending && resumeAfterRestorationDelay()) {
      resumedByClockPress = true;
    }
    if (clock.getRunningFor() == Side.NONE) {
      noteToldToWaitForClock(side);
      return ArbiterResponse.incompleteMove("The game is paused. Please wait until the clock restarts.");
    }
    if (checkFlagFall().isPresent()) {
      return ArbiterResponse.incompleteMove("Your time has expired.");
    }
    // From here on the arbiter rules on the move: the lever was up and went down.
    clockPressRegistered = true;

    // Free placement: a piece that lies unclear, or a bound piece that is not on a square it is bound to, comes
    // before everything else - there is no move to rule on.
    final Optional<ArbiterResponse> placementCall = ruleOnPlacement(side, unclearPieces);
    if (placementCall.isPresent()) {
      return placementCall.get();
    }

    // Special case: must execute specified move (after rejected draw claim)
    if (mustExecuteMove != null) {
      return evaluateMustExecuteMove(afterPosition);
    }

    // Normal evaluation
    final ArbiterResponse response = evaluateMove(afterPosition);
    final boolean keepDrawOffer = drawOfferManager.isDrawOffered() && drawOfferManager.getOfferingSide() == side;

    return handleArbiterResponse(response, side, keepDrawOffer);
  }

  /**
   * Triggered after each board event during play. If the current physical position corresponds to a legal move that
   * immediately ends the game (checkmate, stalemate, dead position, fivefold repetition, 75-move rule), the move is
   * accepted and the game is ended without waiting for a clock press. For any non-ending move, this returns empty and
   * the player must still press the clock as usual.
   *
   * <p>
   * This must NOT have side effects on the illegal-move counter — intermediate positions during piece manipulation are
   * not "moves" and must not be recorded as illegal. Only the clock press (or an offered draw, etc.) goes through the
   * full {@link ArbiterEngine} evaluation that records illegal moves.
   */
  public synchronized Optional<ArbiterResponse> evaluateForAutoEnd(Side side, BitboardPosition afterPosition) {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }
    if (side != board.getSideToMove()) {
      return Optional.empty();
    }
    if (waitingForRestoration || waitingForReady || restorationResumePending || checkFlagFall().isPresent()) {
      return Optional.empty();
    }
    // Skip during the patient-loop recovery from a rejected draw claim — that path requires the
    // mustExecuteMove flow at clock press, not auto-end.
    if (mustExecuteMove != null) {
      return Optional.empty();
    }

    // Position match — pure read, no side effects. Most intermediate positions during piece
    // manipulation will produce no match and we exit immediately.
    final java.util.Set<io.github.dlbbld.ashlarchess.board.LegalMove> matchingMoves = io.github.dlbbld.otbchess.core.PositionComparator
        .findMatchingMoves(board, afterPosition);
    if (matchingMoves.isEmpty()) {
      return Optional.empty();
    }
    final io.github.dlbbld.ashlarchess.board.LegalMove matchedMove = matchingMoves.iterator().next();

    // Released-piece guard (FIDE 4.7): if the player has already committed a release in this
    // turn and the current physical position is NOT one of the committed move's allowed final
    // positions, the player must not auto-finish a different move. The clock-press flow will
    // produce a RELEASED_PIECE_VIOLATION and the standard restoration recovery handles it.
    if ((finalMoveCommitment != null && !finalMoveCommitment.position().equals(afterPosition))
        || arbiter.hasReleasedPieceViolation(board, afterPosition, currentSequence)) {
      return Optional.empty();
    }

    // Free placement: a move that breaks the binding of an unclear release is not made, so it ends nothing.
    if (finalMoveCommitment == null && arbiter.findUnclearBinding(board, currentSequence)
        .filter(binding -> !binding.moves().contains(matchedMove)).isPresent()) {
      return Optional.empty();
    }

    // Touch-move check (also pure read). If the matched move would violate touch-move, leave
    // detection to the clock press so the existing arbiter feedback flow handles it.
    final java.util.Optional<io.github.dlbbld.otbchess.touchmove.TouchMoveObligation> obligation = arbiter
        .findBindingObligation(board, currentSequence);
    if (finalMoveCommitment == null && obligation.isPresent()
        && !io.github.dlbbld.otbchess.touchmove.TouchMoveEvaluator.satisfiesObligation(obligation.get(), matchedMove)) {
      return Optional.empty();
    }
    // Safety net: never auto-accept a move that breaks the first-touch rule. The clock press then
    // hits the same check in ArbiterEngine, which stops instead of recording the move.
    if (!io.github.dlbbld.otbchess.touchmove.FirstTouchInvariant.isHonoured(currentSequence, board, matchedMove)) {
      return Optional.empty();
    }

    // Speculatively perform the matched move and check whether the resulting position ends the
    // game (checkmate, stalemate, dead position, fivefold, 75-move).
    final MoveSpecification spec = matchedMove.moveSpecification();
    applyCommittedMove(spec);
    final Optional<GameResult> ending = checkAutomaticEndings();
    if (ending.isEmpty()) {
      // Not a game-ending move — leave evaluation to the clock press, undo our speculative move.
      board.unmove();
      return Optional.empty();
    }

    // Game-ending move: keep the move performed and finalize state.
    drawOfferManager.clearOffer();
    endGame(ending.get());
    startNewTurn();
    return Optional.of(ArbiterResponse.moveAccepted(matchedMove));
  }

  // ===== Free placement (E-005): the call for an unclear placement =====

  /** Number of unclear placements at a clock press that lose the game, when the game is created without one. */
  public static final int DEFAULT_MAX_UNCLEAR_PLACEMENTS = 3;
  /** No number of unclear placements loses the game. */
  public static final int UNLIMITED_UNCLEAR_PLACEMENTS = -1;

  private int maxUnclearPlacements = DEFAULT_MAX_UNCLEAR_PLACEMENTS;
  private final Map<Side, Integer> unclearPlacementCounts = new EnumMap<>(Side.class);
  // The arbiter's short pause is the one for an unclear placement: the board cannot be used until the clock
  // restarts, because the player corrects the placement on their own running clock.
  private boolean unclearPlacementPause;
  // The arbiter has named the squares a piece is bound to in this turn: they are circled until the piece stands on
  // one of them.
  private boolean bindingCalled;

  /** Sets the number of unclear placements that lose the game: 1 or more, or unlimited. */
  public synchronized void setMaxUnclearPlacements(int maxUnclearPlacements) {
    if (maxUnclearPlacements != UNLIMITED_UNCLEAR_PLACEMENTS && maxUnclearPlacements < 1) {
      throw new IllegalArgumentException("maxUnclearPlacements must be >= 1 or unlimited");
    }
    this.maxUnclearPlacements = maxUnclearPlacements;
  }

  public synchronized int getUnclearPlacementCount(Side side) {
    return unclearPlacementCounts.getOrDefault(side, 0);
  }

  /**
   * A draw offer by the player to move while the arbiter has a call to make about a placement: ruled on like a
   * clock press. Outside play - a pause, a clock that has run out - nothing is ruled on.
   *
   * @return the call, or empty when there is none to make and the offer is ruled on as in every game
   */
  public synchronized Optional<ArbiterResponse> offerDrawWithPlacementCall(Side side,
      List<PlacedPiece> unclearPieces) {
    if (state != GameState.IN_PROGRESS || side != board.getSideToMove()) {
      return Optional.empty();
    }
    if (unclearPieces.isEmpty() && misplacedBinding().isEmpty()) {
      return Optional.empty();
    }
    if (waitingForRestoration || waitingForReady || restorationResumePending || clock.getRunningFor() == Side.NONE
        || checkFlagFall().isPresent()) {
      return Optional.of(ArbiterResponse.incompleteMove("The game is paused. Please wait until the clock restarts."));
    }
    return ruleOnPlacement(side, unclearPieces);
  }

  /** @return whether the board is locked because the arbiter is making a call about a placement */
  public synchronized boolean isUnclearPlacementPause() {
    return unclearPlacementPause && (pressWithoutMovePause || waitingForReady);
  }

  /**
   * The two calls about a placement at the clock press (E-005), in this order: a piece lies unclear, or a piece
   * bound by an unclear release stands on a square it is not bound to.
   */
  private Optional<ArbiterResponse> ruleOnPlacement(Side side, List<PlacedPiece> unclearPieces) {
    if (!unclearPieces.isEmpty()) {
      return Optional.of(ruleOnUnclearPlacement(side, unclearPieces));
    }
    return misplacedBinding().map(binding -> ruleOnMisplacedBoundPiece(side, binding));
  }

  /**
   * The binding of an unclear release that the player has to honour. A move owed after a rejected draw claim comes
   * first: a binding that does not allow it binds to nothing.
   */
  private Optional<ArbiterEngine.UnclearBinding> bindingInForce() {
    if (state != GameState.IN_PROGRESS || finalMoveCommitment != null) {
      return Optional.empty();
    }
    return arbiter.findUnclearBinding(board, currentSequence).filter(binding -> mustExecuteMove == null
        || binding.moves().stream().anyMatch(move -> move.moveSpecification().equals(mustExecuteMove)));
  }

  /** The binding in force whose piece does not stand on one of the squares it is bound to. */
  private Optional<ArbiterEngine.UnclearBinding> misplacedBinding() {
    return bindingInForce().filter(binding -> !binding.isPieceOnBoundSquare());
  }

  /**
   * Every clock press with a piece in an unclear placement is a step on a ladder of its own, per player for the
   * whole game: the call, the call with a warning before the last step, the loss of the game. The arbiter stops the
   * clock for the call and restarts it as after a clock press without a move.
   */
  private ArbiterResponse ruleOnUnclearPlacement(Side side, List<PlacedPiece> unclearPieces) {
    final int count = unclearPlacementCounts.merge(side, 1, Integer::sum);
    final boolean limited = maxUnclearPlacements != UNLIMITED_UNCLEAR_PLACEMENTS;
    final String call = unclearPlacementCall(side, unclearPieces);
    if (limited && count >= maxUnclearPlacements) {
      terminationActor = side;
      endGame(new GameResult(GameResultType.UNCLEAR_PLACEMENT_GAME_LOST, side.getOppositeSide(),
          sideName(side) + " loses the game by repeatedly placing pieces unclearly."));
      return ArbiterResponse.unclearPlacementGameLost(call);
    }
    pauseForPlacementCall();
    final boolean warned = limited && count == maxUnclearPlacements - 1;
    final String opponentInfo = "Your opponent pressed the clock while a piece was not clearly on a square"
        + (warned ? " and has been warned: the next time loses them the game." : ".");
    return ArbiterResponse.unclearPlacement(
        call + (warned ? " Warning: the next time you press the clock with a piece placed unclearly, you lose"
            + " the game." : ""),
        opponentInfo);
  }

  /**
   * A piece that was bound by an unclear release stands clearly on a square it is not bound to, or is off the
   * board: the arbiter names the squares it may go to. The piece stays where it is, the move is not made, and
   * nothing is counted - as for a released piece today. The arbiter intervenes at every clock press until the piece
   * stands on one of the squares.
   */
  private ArbiterResponse ruleOnMisplacedBoundPiece(Side side, ArbiterEngine.UnclearBinding binding) {
    pauseForPlacementCall();
    bindingCalled = true;
    final String name = pieceName(binding.piece());
    final String released = " was released on " + joinSquares(binding.squares(), "", "and") + ".";
    return ArbiterResponse.unclearPlacement(
        "Your " + name + released + " Place it on " + joinSquares(binding.squares(), "on ", "or") + ".",
        "Your opponent's " + name + released + " It has to be placed "
            + (binding.squares().size() == 1 ? "there." : "on one of them."));
  }

  /** The pause for a call about a placement: the clock stops and the board is locked until it restarts. */
  private void pauseForPlacementCall() {
    clock.stopClock();
    wrongClockPressPausedFor = null; // this call supersedes an admonishment pause
    unclearPlacementPause = true;
    if (autoResumeAfterRestore) {
      pressWithoutMovePause = true;
      pressWithoutMovePauseId++;
    } else {
      waitingForReady = true;
      whiteReady = false;
      blackReady = false;
    }
  }

  /**
   * What the arbiter says about the pieces that lie unclear.
   * <ul>
   * <li>An own piece that is bound by its release: the squares it is bound to, which are the ones that give a
   * legal move. When it lies elsewhere by now, the call says where it was released.</li>
   * <li>An own piece that was released in a clear placement before (FIDE 4.7) and picked up again: the square of
   * that release.</li>
   * <li>An own piece that is bound to nothing: no square. Any legal move with it remains.</li>
   * <li>An opponent's piece that stood on a square was set down while it was being taken: it is to be put back or
   * taken off.</li>
   * </ul>
   */
  private String unclearPlacementCall(Side side, List<PlacedPiece> unclearPieces) {
    final Optional<ArbiterEngine.UnclearBinding> binding = bindingInForce();
    final Optional<ArbiterEngine.ReleasedPiece> released = arbiter.findReleasedPiece(board, currentSequence);
    final StringBuilder call = new StringBuilder();
    // One piece that still lies where it was let go: the short form, in one sentence.
    boolean reminderSaid = false;
    for (final PlacedPiece unclear : unclearPieces) {
      final boolean own = unclear.piece().getSide() == side;
      final String name = pieceName(unclear.piece());
      call.append("It is not clear on which square your ").append(own ? "" : "opponent's ").append(name)
          .append(" is.");
      if (!own) {
        if (unclear.origin() != Square.NONE) {
          call.append(" Put it back on ").append(unclear.origin().getName())
              .append(" or take it off the board.");
        }
      } else if (binding.isPresent() && binding.get().piece() == unclear.piece()
          && binding.get().at() == unclear.square()) {
        final List<Square> squares = binding.get().squares();
        bindingCalled = true;
        if (unclear.liesOn().containsAll(squares)) {
          call.append(" Place it on ").append(joinSquares(squares, "on ", "or"));
          if (unclearPieces.size() == 1) {
            call.append(", and place your pieces more carefully.");
            reminderSaid = true;
          } else {
            call.append('.');
          }
        } else {
          call.append(" It was released on ").append(joinSquares(squares, "", "and")).append(". Place it on ")
              .append(joinSquares(squares, "on ", "or")).append('.');
        }
      } else if (released.isPresent() && released.get().piece() == unclear.piece()
          && released.get().square() == unclear.square()) {
        final String square = released.get().square().getName();
        call.append(" It was released on ").append(square).append(". Place it on ").append(square).append('.');
      }
      call.append(' ');
    }
    if (reminderSaid) {
      return call.toString().trim();
    }
    return call.append("Place your pieces more carefully.").toString();
  }

  private static String pieceName(io.github.dlbbld.ashlarchess.board.enums.Piece piece) {
    return piece.getPieceType().name().toLowerCase();
  }

  /**
   * "d1", "d1 or on e1", "d1, on e1 or on e2": the squares in a sentence.
   *
   * @param repeated what is said again before every square after the first ("on " after "Place it on")
   */
  private static String joinSquares(List<Square> squares, String repeated, String conjunction) {
    final StringBuilder joined = new StringBuilder(squares.get(0).getName());
    for (int i = 1; i < squares.size(); i++) {
      joined.append(i == squares.size() - 1 ? " " + conjunction + " " : ", ").append(repeated)
          .append(squares.get(i).getName());
    }
    return joined.toString();
  }

  private ArbiterResponse handleArbiterResponse(ArbiterResponse response, Side side, boolean keepDrawOffer) {
    // Safety net: stop before any state change if the arbiter asks for an unjustifiable restore.
    response.restorePosition().ifPresent(target -> RestoreTargetInvariant.verify(board, target));
    switch (response.type()) {
      case MOVE_ACCEPTED -> {
        // Validate the committed move before changing the clock. If time expired during
        // evaluation, undo it so flag fall is adjudicated against the original board.
        applyCommittedMove(response.acceptedMove().get().moveSpecification());
        if (!clock.switchClock()) {
          board.unmove();
          checkFlagFall();
          clockPressRegistered = false;
          return ArbiterResponse.incompleteMove("Your time has expired.");
        }

        // Clear draw offer unless we're keeping it (draw was just offered with this move)
        if (!keepDrawOffer) {
          drawOfferManager.clearOffer();
        }

        // Check for automatic game endings
        final Optional<GameResult> ending = checkAutomaticEndings();
        if (ending.isPresent()) {
          endGame(ending.get());
        }

        // Start new turn
        startNewTurn();
      }
      case ILLEGAL_MOVE -> {
        // Add penalty time to opponent
        clock.addPenaltyTime(side.getOppositeSide(), arbiter.getIllegalMoveTracker().getPenaltyTimeMs());
        // The arbiter stops the clock for every illegal move. Most are then taken off the board by a
        // restoration. A press without a move (FIDE 7.5.3) left nothing to restore: the game pauses as it does
        // after a restoration - until the clock restarts by itself, or until both players are ready - so that
        // the ruling is read before the next press counts.
        final boolean noMoveMade = response.illegalMoveDetail().map(IllegalMoveDetail::noMoveMade).orElse(false);
        clock.stopClock();
        if (noMoveMade) {
          wrongClockPressPausedFor = null; // this ruling supersedes an admonishment pause
          unclearPlacementPause = false;
          if (autoResumeAfterRestore) {
            pressWithoutMovePause = true;
            pressWithoutMovePauseId++;
          } else {
            waitingForReady = true;
            whiteReady = false;
            blackReady = false;
          }
        }
        restorationFromReleasedPiece = false;
      }
      case ILLEGAL_MOVE_GAME_LOST -> {
        endGame(new GameResult(GameResultType.ILLEGAL_MOVE_GAME_LOST, side.getOppositeSide(),
            response.renderedPlayerMessage()));
      }
      case TOUCH_MOVE_VIOLATION -> {
        clock.stopClock();
        restorationFromReleasedPiece = false;
      }
      case RELEASED_PIECE_VIOLATION -> {
        clock.stopClock();
        // Latch the commitment so the upcoming restoration keeps the released move final (FIDE 4.7).
        restorationFromReleasedPiece = true;
      }
      case INCOMPLETE_MOVE -> {
        // Nothing to do
      }
      default -> {
        // Other types shouldn't occur at clock press
      }
    }

    return response;
  }

  private ArbiterResponse evaluateMustExecuteMove(BitboardPosition afterPosition) {
    // Compute expected position after the specified move
    final BitboardPosition expectedPosition = positionBeforeTurn.afterMove(mustExecuteMove, board.getSideToMove());

    if (expectedPosition.equals(afterPosition)) {
      // Correct — perform the move
      final MoveSpecification executedMove = mustExecuteMove;
      final io.github.dlbbld.ashlarchess.board.LegalMove matchedLegalMove = board.getLegalMoves().stream()
          .filter(lm -> lm.moveSpecification().equals(executedMove)).findFirst()
          .orElseThrow(() -> new IllegalStateException("Specified move is not in the legal move set"));
      applyCommittedMove(executedMove);
      if (!clock.switchClock()) {
        board.unmove();
        checkFlagFall();
        clockPressRegistered = false;
        return ArbiterResponse.incompleteMove("Your time has expired.");
      }
      mustExecuteMove = null;
      mustExecuteMoveSan = null;

      final Optional<GameResult> ending = checkAutomaticEndings();
      if (ending.isPresent()) {
        endGame(ending.get());
      }

      startNewTurn();
      return ArbiterResponse.moveAccepted(matchedLegalMove);
    }

    // Incorrect — instruct to revert
    clock.stopClock();
    return ArbiterResponse.incompleteMove("The specified move, " + mustExecuteMoveSan
        + ", was not executed. Please revert the position and play the specified move.");
  }

  // ===== Draw offers =====

  /**
   * Player offers a draw at the correct time (their turn, after making a move). The move is validated but NOT performed
   * and the clock does NOT switch — the player still has to press the clock themselves to commit the move. This matches
   * FIDE: the draw offer is communicated after the move is made and before the clock is pressed; the clock press is a
   * separate act.
   *
   * <p>
   * Returns {@link ArbiterResponseType#MOVE_ACCEPTED} when the move is legal and the offer has been registered (server
   * should then forward the offer to the opponent and tell the offering player to press the clock). Returns the
   * appropriate intervention type if the move is invalid (in which case the offer is dropped without penalty).
   */
  public synchronized ArbiterResponse offerDrawCorrectTime(Side side, BitboardPosition afterPosition) {
    checkFlagFall();
    if (state != GameState.IN_PROGRESS) {
      return ArbiterResponse.incompleteMove("The game is not in progress.");
    }

    // An offer that answers the opponent's acceptable offer is its acceptance, and a draw can be
    // agreed at any time - also while the arbiter has stopped the clocks, exactly like Accept.
    switch (answerOfferWithOffer(side)) {
      case AGREED -> {
        return ArbiterResponse.incompleteMove(DRAW_AGREEMENT_DESCRIPTION);
      }
      case TOO_EARLY -> {
        return ArbiterResponse.incompleteMove(DRAW_TOO_EARLY);
      }
      default -> {
        // no offer to answer: this is an offer of its own
      }
    }
    final String pauseMessage = pauseMessage(side, "offering a draw");
    if (pauseMessage != null) {
      return ArbiterResponse.incompleteMove(pauseMessage);
    }

    // Validate the move first (no side effects on the move state — evaluateClockPress is
    // pure; the move-performing happens in handleArbiterResponse, which we skip on success).
    final ArbiterResponse moveResponse = evaluateMove(afterPosition);

    if (moveResponse.type() != ArbiterResponseType.MOVE_ACCEPTED) {
      // Move is not valid — this offer is not registered, and the standard intervention applies
      // (illegal-move counter, restoration target, etc.) via handleArbiterResponse. An offer that
      // is already pending stays: the opponent's is not this player's to cancel, and an earlier
      // one of their own cannot be withdrawn (FIDE 9.1.2.1) while it is on the opponent's screen.
      return markRuling(handleArbiterResponse(moveResponse, side, false));
    }

    // Move is valid. It is the offerer's move of this turn, made on the board: with it both players
    // have moved unless it is the first move of the game.
    if (board.getPerformedMovesAsSan().isEmpty()) {
      final var early = rejectEarlyOffer(side);
      earlyOfferOpponentInfo = early.opponentInfo();
      return ArbiterResponse.incompleteMove(early.arbiterMessage());
    }

    // Register the offer (handles repeat / game-lost penalty).
    final var result = drawOfferManager.offerDrawCorrectTime(side);
    if (result.gameLost()) {
      endGame(new GameResult(GameResultType.DRAW_AGREEMENT, side.getOppositeSide(), result.arbiterMessage()));
      return ArbiterResponse.illegalMoveGameLost(result.arbiterMessage());
    }
    if (!result.accepted()) {
      // Repeated offer (info or warning) — message returned, but the move is still pending
      // (the player has to press the clock to commit it).
      return ArbiterResponse.incompleteMove(result.arbiterMessage());
    }

    // Offer registered. The matched move is returned to the server but the move itself is
    // NOT performed and the clock does NOT switch — the player still owes a clock press.
    return moveResponse;
  }

  /**
   * Player offers a draw at the wrong time (not their turn, or no move made). The offer is still valid per FIDE 9.1.2.1
   * and forwarded to the opponent, but escalating penalties apply.
   *
   * @return the arbiter message (may be null if no penalty), and whether game is lost
   */
  public synchronized DrawOfferManager.DrawOfferResult offerDrawWrongTime(Side side) {
    checkFlagFall();
    if (state != GameState.IN_PROGRESS) {
      return DrawOfferManager.DrawOfferResult.repeated("The game is not in progress.");
    }

    switch (answerOfferWithOffer(side)) {
      case AGREED -> {
        return DrawOfferManager.DrawOfferResult.drawAgreed();
      }
      case TOO_EARLY -> {
        return DrawOfferManager.DrawOfferResult.repeated(DRAW_TOO_EARLY);
      }
      default -> {
        // no offer to answer: this is an offer of its own
      }
    }
    final String pauseMessage = pauseMessage(side, "offering a draw");
    if (pauseMessage != null) {
      return DrawOfferManager.DrawOfferResult.repeated(pauseMessage);
    }

    if (!haveBothPlayersMadeAMove()) {
      return rejectEarlyOffer(side);
    }

    final boolean offererHasMove = side == board.getSideToMove();
    final var result = drawOfferManager.offerDrawWrongTime(side, offererHasMove);
    if (result.gameLost()) {
      loseByOfferingAtTheWrongTime(side);
    }
    return result;
  }

  /**
   * An offer before both players have made a move is not valid (FIDE 5.2.3): the arbiter says so at once to both
   * players instead of passing on an offer that could not be accepted. It counts on the offer ladder of the move.
   */
  private DrawOfferManager.DrawOfferResult rejectEarlyOffer(Side side) {
    final var result = drawOfferManager.offerDrawTooEarly(side);
    if (result.gameLost()) {
      loseByOfferingAtTheWrongTime(side);
    }
    return result;
  }

  private void loseByOfferingAtTheWrongTime(Side side) {
    terminationActor = side;
    endGame(new GameResult(GameResultType.WRONG_TIME_OFFER_GAME_LOST, side.getOppositeSide(),
        sideName(side) + " loses the game by repeatedly offering a draw at the wrong time."));
  }

  private String earlyOfferOpponentInfo;

  /**
   * @return and clears what the opponent is to be told about an offer that was made with a move before both players
   *         had moved; the offerer's part is the answer to the offer itself
   */
  public synchronized Optional<String> consumeEarlyOfferOpponentInfo() {
    final Optional<String> info = Optional.ofNullable(earlyOfferOpponentInfo);
    earlyOfferOpponentInfo = null;
    return info;
  }

  /** What became of an offer that was made while the opponent's offer was on the table. */
  private enum CounterOffer {
    /** There was no offer of the opponent that could be accepted: the new offer stands for itself. */
    NO_OFFER_TO_ANSWER,
    /** The draw is agreed and the game has ended. */
    AGREED,
    /** The offer could be accepted, but not yet: FIDE 5.2.3. Nothing changed. */
    TOO_EARLY
  }

  /**
   * Both players want a draw: an offer made while the opponent's offer can still be accepted is the acceptance of
   * that offer. Registering it as a new offer instead would replace the pending one and leave each player waiting
   * for the other. An offer that can no longer be accepted (the recipient touched a piece or made the move) is not
   * agreed this way; the new offer then simply takes its place.
   */
  private CounterOffer answerOfferWithOffer(Side side) {
    if (!drawOfferManager.canAccept(side)) {
      return CounterOffer.NO_OFFER_TO_ANSWER;
    }
    if (!haveBothPlayersMadeAMove()) {
      return CounterOffer.TOO_EARLY;
    }
    agreeDraw(side);
    return CounterOffer.AGREED;
  }

  /**
   * FIDE 5.2.3: a game is drawn upon agreement "provided both players have made at least one move". A move made on
   * the board counts although its clock press is still outstanding - that is the moment FIDE 9.1.2.1 asks the offer
   * to be made at.
   */
  private boolean haveBothPlayersMadeAMove() {
    return board.getPerformedMovesAsSan().size() + (finalMoveCommitment == null ? 0 : 1) >= 2;
  }

  /**
   * Ends the game as a draw by agreement, accepted by {@code acceptingSide}. A move the offering player has made on
   * the board and not yet completed with the clock belongs to the game (FIDE 9.1.2.1: the offer is made after the
   * move and before the clock press): it is performed first, so that the game record ends with it.
   */
  private GameResult agreeDraw(Side acceptingSide) {
    if (finalMoveCommitment != null && drawOfferManager.getOfferingSide() == board.getSideToMove()
        && (mustExecuteMove == null || mustExecuteMove.equals(finalMoveCommitment.move().moveSpecification()))) {
      board.move(finalMoveCommitment.move().moveSpecification());
    }
    terminationActor = acceptingSide; // the player who accepted the offer (for the personalised message)
    final GameResult drawResult = new GameResult(GameResultType.DRAW_AGREEMENT, Side.NONE,
        DRAW_AGREEMENT_DESCRIPTION);
    endGame(drawResult);
    return drawResult;
  }

  /**
   * @return the refusal for an offer or claim made while the arbiter has stopped the clocks - a restoration pause or
   *         the admonishment pause after a wrong clock press - or {@code null} when play is on
   */
  private String pauseMessage(Side side, String action) {
    if (waitingForRestoration) {
      return "The game is paused. Please restore the position before " + action + ".";
    }
    if (waitingForReady) {
      return "The game is paused. Please wait until both players are ready.";
    }
    if (restorationResumePending || isWrongClockPressPause() || pressWithoutMovePause) {
      noteToldToWaitForClock(side);
      return "The game is paused. Please wait until the clock restarts.";
    }
    return null;
  }

  /** Remembers who was told to wait for the restart after a wrong clock press: they are told when it comes. */
  private void noteToldToWaitForClock(Side side) {
    if (isWrongClockPressPause() && !restorationResumePending) {
      toldToWaitForClock.add(side);
    }
  }

  /** @return and clears whether the side was told, during the admonishment pause, to wait for the clock. */
  public synchronized boolean consumeToldToWaitForClock(Side side) {
    return toldToWaitForClock.remove(side);
  }

  /** The admonishment pause after a wrong clock press: the arbiter restarts the interrupted clock by itself. */
  private boolean isWrongClockPressPause() {
    return wrongClockPressPausedFor != null && clock.getRunningFor() == Side.NONE;
  }

  /**
   * Whether the on-move player has already made a legal release in this turn — i.e. a release that corresponds to (the
   * start of) a legal move from the position before turn. Used by the server to detect Scenario 2 of the draw-offer
   * flow: an offer arriving after the opponent has committed a move via FIDE 4.7 is rejected outright.
   */
  public synchronized boolean hasReleasedPieceCommitment() {
    return finalMoveCommitment != null || arbiter.hasReleasedPieceCommitment(board, currentSequence);
  }

  /**
   * Player accepts a draw offer.
   *
   * @return the game result if accepted, empty if rejected. Check {@code getAcceptDrawRejectionMessage}.
   */
  public synchronized Optional<GameResult> acceptDraw(Side side) {
    if (state != GameState.IN_PROGRESS || checkFlagFall().isPresent()) {
      lastAcceptDrawRejection = "The game is not in progress.";
      return Optional.empty();
    }
    final Optional<String> rejection = drawOfferManager.acceptDraw(side);
    if (rejection.isPresent()) {
      this.lastAcceptDrawRejection = rejection.get();
      return Optional.empty();
    }
    if (!haveBothPlayersMadeAMove()) {
      // The offer itself stays on the table; it can be accepted once both players have moved.
      this.lastAcceptDrawRejection = DRAW_TOO_EARLY;
      return Optional.empty();
    }

    this.lastAcceptDrawRejection = null;
    return Optional.of(agreeDraw(side));
  }

  private static final String DRAW_AGREEMENT_DESCRIPTION = "The game is drawn by agreement.";
  private static final String DRAW_TOO_EARLY = DrawOfferManager.DRAW_TOO_EARLY;

  private String lastAcceptDrawRejection;

  public synchronized String getLastAcceptDrawRejection() {
    return lastAcceptDrawRejection;
  }

  private String lastRejectDrawRejection;

  public synchronized String getLastRejectDrawRejection() {
    return lastRejectDrawRejection;
  }

  /**
   * Player rejects a draw offer.
   */
  public synchronized String rejectDraw(Side side) {
    final String offererMessage = drawOfferManager.offererRejectionMessage();
    final Optional<String> rejection = drawOfferManager.rejectDraw(side);
    if (rejection.isPresent()) {
      lastRejectDrawRejection = rejection.get();
      return offererMessage;
    }
    lastRejectDrawRejection = null;
    return offererMessage;
  }

  // ===== Draw claims =====

  /**
   * Player claims a draw (threefold repetition or 50-move rule).
   */
  public synchronized DrawClaimResult claimDraw(Side side, DrawClaimType type, String san) {
    final DrawClaimResult proceduralRejection = rejectClaimBeforeRuleMachinery(side);
    if (proceduralRejection != null) {
      return proceduralRejection;
    }

    // A rejected claim is a draw offer (FIDE 9.5) - but not before both players have made a move,
    // when no draw can be agreed (FIDE 5.2.3). Nothing has been touched yet on the move of a claim.
    final DrawClaimResult claimResult = drawClaimManager.processClaim(board, type, san,
        haveBothPlayersMadeAMove());

    // An invalid SAN doesn't constitute a completed claim attempt: no legal intended move was
    // presented, so the claim is not considered. Any other outcome counts and locks claims for
    // the rest of this turn.
    if (!claimResult.invalidMove()) {
      claimMadeThisTurn = true;
    }

    if (claimResult.accepted()) {
      final GameResultType resultType = (type == DrawClaimType.THREEFOLD_ON_BOARD
          || type == DrawClaimType.THREEFOLD_WITH_MOVE) ? GameResultType.THREEFOLD_CLAIM
              : GameResultType.FIFTY_MOVE_CLAIM;

      if (claimResult.moveToPerform().isPresent()) {
        applyCommittedMove(claimResult.moveToPerform().get());
      }

      // Use the short game-end description for the result panel; the long claim-feedback
      // line goes only to the per-player arbiter messages.
      final String description = claimResult.gameEndDescription().orElse(claimResult.message());
      endGame(new GameResult(resultType, Side.NONE, description));
    } else if (!claimResult.invalidMove()) {
      // FIDE 9.5.3: an incorrect (i.e. completed but rejected) claim adds 2 minutes to the
      // opponent's clock. Both rejected on-board claims and rejectedWithMove claims qualify;
      // invalid-SAN doesn't because no legal intended move was presented and the claim was not
      // considered.
      clock.addPenaltyTime(side.getOppositeSide(), INCORRECT_CLAIM_PENALTY_MS);
      if (claimResult.moveToPerform().isPresent()) {
        mustExecuteMove = claimResult.moveToPerform().get();
        mustExecuteMoveSan = san;
        clock.startClock(side);
      }
    }

    // FIDE 9.5: a rejected claim is treated as a draw offer to the opponent. We register a
    // correct-time offer (the claimer is on the move) so it follows the standard accept /
    // reject / touch-piece-invalidation flow without going through the wrong-time escalation.
    // The same holds for this implied offer as for a spoken one: if the opponent's offer can still
    // be accepted, both players want the draw and the game is drawn by agreement.
    if (claimResult.convertsToDrawOffer() && answerOfferWithOffer(side) != CounterOffer.AGREED) {
      drawOfferManager.offerDrawCorrectTime(side, rejectedClaimDrawOfferMessage(type));
    }

    return claimResult;
  }

  private static String rejectedClaimDrawOfferMessage(DrawClaimType type) {
    return switch (type) {
      case THREEFOLD_ON_BOARD, THREEFOLD_WITH_MOVE ->
          "Your opponent rejected the draw offer, which was automatically part of your claim for threefold repetition.";
      case FIFTY_MOVE_ON_BOARD, FIFTY_MOVE_WITH_MOVE ->
          "Your opponent rejected the draw offer, which was automatically part of your claim under the 50-move rule.";
    };
  }

  /**
   * Player started a claim-with-move and then retracted it before presenting a legal move.
   */
  public synchronized DrawClaimResult retractDrawClaim(Side side) {
    final DrawClaimResult proceduralRejection = rejectClaimBeforeRuleMachinery(side);
    if (proceduralRejection != null) {
      return proceduralRejection;
    }

    claimMadeThisTurn = true;
    return DrawClaimResult.retracted(
        "You retracted your draw claim. The claim was not considered, but it counts as your claim on this move.",
        "Your opponent retracted a draw claim. The claim was not considered.");
  }

  private DrawClaimResult rejectClaimBeforeRuleMachinery(Side side) {
    checkFlagFall();
    if (state != GameState.IN_PROGRESS) {
      return DrawClaimResult.error("You cannot claim a draw now.");
    }
    // While the arbiter has stopped the clocks nothing is adjudicated: like a draw offer, a claim made
    // now is not considered at all - no penalty time, no converted offer, no step on a claim ladder.
    final String pauseMessage = pauseMessage(side, "claiming a draw");
    if (pauseMessage != null) {
      return DrawClaimResult.paused(pauseMessage);
    }
    if (side != board.getSideToMove()) {
      // FIDE 9.2 / 9.3: a draw claim can only be made by the player whose turn it is. (Offering a
      // draw is different — an offer is possible at any time; a CLAIM requires the move.) The
      // buttons stay enabled (see wrongTimeClaimCounts) and the arbiter escalates: rejection,
      // then a warning, then loss of the game on the third wrong-time claim.
      final int count = wrongTimeClaimCounts.merge(side, 1, Integer::sum);
      if (count >= WRONG_TIME_CLAIM_LIMIT) {
        endGame(new GameResult(GameResultType.WRONG_TIME_CLAIM_GAME_LOST, side.getOppositeSide(),
            sideName(side) + " loses the game by repeatedly claiming a draw when not having the move."));
        return DrawClaimResult.rejectedWithoutDrawOffer(
            "You have been warned that you will lose the game when you claim a draw again while not having the"
                + " move. As you have claimed a draw again, you lose the game.",
            "Your opponent has, despite the warnings, repeatedly requested to claim a draw while not having the"
                + " move, and so has lost the game.");
      }
      if (count == WRONG_TIME_CLAIM_LIMIT - 1) {
        return DrawClaimResult.wrongTime(
            "You cannot claim a draw when not having the move. Warning: your next"
                + " draw claim when not having the move loses the game.",
            "Your opponent again claimed a draw while not having the move. The claim was not considered, and"
                + " they have been warned: their next draw claim when not having the move loses them the game.");
      }
      // "Not considered" (not "rejected"): a claim made out of turn never reaches the rule
      // machinery — only a claim that was actually examined on the merits can be rejected.
      return DrawClaimResult.wrongTime("You cannot claim a draw when not having the move.",
          "Your opponent claimed a draw while not having the move. The claim was not considered.");
    }
    if (!currentSequence.isEmpty()) {
      // FIDE 9.4: the player loses the right to claim under 9.2 / 9.3 once any piece has
      // been touched on this move. Any event in the current turn's action sequence
      // (CLICK, DRAG_*, REMOVE, RESTORE_*) counts as a touch — claims must be made
      // before starting to interact with pieces. Same escalation ladder as the wrong-time
      // claims (see afterTouchClaimCounts): rejection, warning, loss on the third.
      final int count = afterTouchClaimCounts.merge(side, 1, Integer::sum);
      if (count >= AFTER_TOUCH_CLAIM_LIMIT) {
        endGame(new GameResult(GameResultType.CLAIM_AFTER_TOUCH_GAME_LOST, side.getOppositeSide(),
            sideName(side) + " loses the game by repeatedly claiming a draw after touching a piece."));
        return DrawClaimResult.rejectedWithoutDrawOffer(
            "You have been warned that you will lose the game when you claim a draw again after touching a piece."
                + " As you have claimed a draw again, you lose the game.",
            "Your opponent has, despite the warnings, repeatedly claimed a draw after touching a piece, and so"
                + " has lost the game.");
      }
      final String rejection = "You cannot claim a draw after touching or moving a piece on this move (FIDE 9.4)."
          + " Claims must be made before any piece interaction.";
      if (count == AFTER_TOUCH_CLAIM_LIMIT - 1) {
        return DrawClaimResult.wrongTime(
            rejection + " Warning: your next draw claim after touching a piece loses the game.",
            "Your opponent again claimed a draw after touching a piece on this move. The claim was not considered,"
                + " and they have been warned: their next draw claim after touching a piece loses them the game.");
      }
      return DrawClaimResult.wrongTime(rejection,
          "Your opponent claimed a draw after touching a piece on this move. The claim was not considered.");
    }
    if (claimMadeThisTurn) {
      // FIDE 9.2/9.3 allow one claim per move. The buttons stay enabled (see
      // repeatClaimViolationCounts) and the arbiter escalates: warning on the first repeat
      // (the legitimate claim was already used), loss of the game on the next.
      final int violations = repeatClaimViolationCounts.merge(side, 1, Integer::sum);
      if (violations >= REPEAT_CLAIM_VIOLATION_LIMIT) {
        endGame(new GameResult(GameResultType.REPEAT_CLAIM_GAME_LOST, side.getOppositeSide(),
            sideName(side) + " loses the game by repeatedly claiming a draw on the same move."));
        return DrawClaimResult.rejectedWithoutDrawOffer(
            "You have been warned that you will lose the game when you claim a draw again on the same move."
                + " As you have claimed a draw again, you lose the game.",
            "Your opponent has, despite the warnings, repeatedly claimed a draw on the same move, and so has"
                + " lost the game.");
      }
      return DrawClaimResult.repeatClaim(
          "You cannot make more than one draw claim on your move. You are warned:"
              + " the next draw claim on a move you have already claimed on loses the game.",
          "Your opponent made a second draw claim on the same move. The claim was not considered, and they have"
              + " been warned: their next draw claim on a move they have already claimed on loses them the game.");
    }
    return null;
  }

  // ===== Resignation =====

  /**
   * Player resigns.
   *
   * <p>
   * FIDE 5.1.2: a resignation is a loss unless the opponent could not checkmate by any series of legal moves, in which
   * case it is a draw. {@link Adjudicator} applies that exception; we use the QUICK variant (the live-play path -
   * bounded latency, drawing only when it can prove the opponent unwinnable) rather than the FULL analyzer, whose deep
   * helpmate search can cost hundreds of ms and is not warranted for a button press.
   *
   * <p>
   * On a draw the player-facing message splits the two cases the adjudicator folds together: a material shortage versus
   * a position unwinnable despite sufficient material (see {@link #drawReason(Side)}).
   *
   * @return the result of the game, or {@code null} when the game has not started
   */
  public synchronized GameResult resign(Side side) {
    checkFlagFall();
    if (state == GameState.ENDED) {
      return result;
    }
    if (state != GameState.IN_PROGRESS) {
      return null; // no opponent has joined yet: there is no game to resign
    }
    final Side opponent = side.getOppositeSide();

    if (Adjudicator.adjudicateResignationQuick(board, side) == AdjudicationResult.DRAW) {
      terminationActor = side;
      drawExceptionByInsufficientMaterial = board.isInsufficientMaterial(opponent);
      final GameResult drawResult = new GameResult(GameResultType.RESIGNATION, Side.NONE,
          sideName(side) + " resigned, but because " + drawReason(opponent) + ", the game is a draw.");
      endGame(drawResult);
      return drawResult;
    }

    final GameResult lossResult = new GameResult(GameResultType.RESIGNATION, opponent,
        sideName(side) + " resigns. " + sideName(opponent) + " wins the game.");
    endGame(lossResult);
    return lossResult;
  }

  // ===== Moving an opponent's piece (A-007) =====

  // Dragging an opponent's piece on the board is never allowed; the arbiter escalates like the
  // other misconducts: notice + restore, notice + warning + restore, loss of the game on the
  // third time. Counted per player across the whole game.
  private static final int MOVED_OPPONENT_PIECE_LIMIT = 3;
  private final Map<Side, Integer> movedOpponentPieceCounts = new EnumMap<>(Side.class);

  // Passive information for the opponent produced by the latest escalation step (see the
  // face-to-face principle / info window below the clock); consumed by the server layer.
  private String pendingOpponentInfo;

  /** @return and clears the passive opponent-info text of the latest escalation step, if any. */
  public synchronized String consumePendingOpponentInfo() {
    final String info = pendingOpponentInfo;
    pendingOpponentInfo = null;
    return info;
  }

  private ArbiterResponse escalateMovedOpponentPiece(Side side, ArbiterResponse original) {
    final int count = movedOpponentPieceCounts.merge(side, 1, Integer::sum);
    if (count >= MOVED_OPPONENT_PIECE_LIMIT) {
      terminationActor = side;
      endGame(new GameResult(GameResultType.MOVED_OPPONENT_PIECE_GAME_LOST, side.getOppositeSide(),
          sideName(side) + " loses the game by repeatedly moving the opponent's pieces."));
      // The server sees the ENDED state and broadcasts gameEnded (with the personalised
      // messages) instead of restore instructions.
      return original;
    }
    if (count == MOVED_OPPONENT_PIECE_LIMIT - 1) {
      pendingOpponentInfo = "Your opponent again moved one of your pieces and has been warned: the next time"
          + " they move one of your pieces, they lose the game. The position must be restored.";
      final ArbiterResponse warning = ArbiterResponse.positionChange(
          "Position change: You moved an opponent's piece. That is not allowed. Please restore the position."
              + " Warning: the next time you move an opponent's piece, you lose the game.");
      return original.restorePosition().map(warning::withRestorePosition).orElse(warning);
    }
    pendingOpponentInfo = "Your opponent moved one of your pieces. The game is paused until the position"
        + " has been restored.";
    return original;
  }

  // ===== Wrong clock press (pressing the opponent's clock) =====

  // Real-world modeling: on a physical clock the wrong lever CAN be pressed. Escalation like the
  // other misconducts: the arbiter pauses the game and admonishes (the clock restarts after a
  // short pause), the second time with a warning, the third press loses the game. Counted per
  // player across the whole game.
  private static final int WRONG_CLOCK_PRESS_LIMIT = 3;
  private final Map<Side, Integer> wrongClockPressCounts = new EnumMap<>(Side.class);
  // Whose clock the arbiter must restart after the admonishment pause; null when no pause active.
  private Side wrongClockPressPausedFor;
  private long wrongClockPauseId;
  // The pause after a clock press without a move (FIDE 7.5.3) in a game that resumes by itself: the arbiter has
  // stopped the clock and restarts it after a short while. There is nothing to restore, so no Revert ends it.
  private boolean pressWithoutMovePause;
  private long pressWithoutMovePauseId;
  // The squares the arbiter points at since its last ruling on the move (circles on both boards): the green
  // ones of a touch or of a move that was already made, and the red ones of an illegal move.
  private List<BoardMark> boardMarks = List.of();
  // Who was answered "wait until the clock restarts" during the current admonishment pause.
  private final Set<Side> toldToWaitForClock = EnumSet.noneOf(Side.class);
  /** What both players read when the arbiter restarts the clock, after a restoration and after this pause alike. */
  public static final String CLOCK_RESTARTED = "Clock restarted. Game continues.";
  private static final String WRONG_CLOCK_WARNING = " Warning: the next press of your opponent's clock loses the game.";

  /**
   * Outcome of a press of the opponent's clock lever.
   *
   * @param offense      false when the press was a physical no-op (the opponent's lever was already down — their
   *                     clock was not running); nothing is counted or announced then
   * @param gameLost     true on the third offense — the game has been ended inside this call
   * @param message      arbiter message for the offender ({@code null} for a no-op)
   * @param opponentInfo passive info for the opponent ({@code null} for a no-op and for the game-ending press, which
   *                     speaks through {@code gameEnded} instead)
   * @param pauseId identifies this pause for its delayed callback; zero when no resume is scheduled
   * @param warned              true for the second offense: the next one loses the game
   * @param restartMessage      what the offender reads when the clock restarts ({@code null} without a pause)
   * @param opponentRestartInfo the opponent's passive info once the clock has restarted ({@code null} without a pause)
   */
  public record WrongClockPressOutcome(boolean offense, boolean gameLost, String message, String opponentInfo,
      long pauseId, boolean warned, String restartMessage, String opponentRestartInfo) {

    private static WrongClockPressOutcome withoutPause(boolean offense, boolean gameLost) {
      return new WrongClockPressOutcome(offense, gameLost, null, null, 0, false, null, null);
    }
  }

  /**
   * The player pressed their OPPONENT's clock lever. Physically meaningful only while the opponent's clock is
   * running (their lever up) — otherwise the lever is already down and nothing happens, exactly like a real clock.
   */
  public synchronized WrongClockPressOutcome pressOpponentClock(Side side) {
    checkFlagFall();
    final Side opponent = side.getOppositeSide();
    if (state != GameState.IN_PROGRESS || clock.getRunningFor() != opponent) {
      return WrongClockPressOutcome.withoutPause(false, false);
    }
    final int count = wrongClockPressCounts.merge(side, 1, Integer::sum);
    if (count >= WRONG_CLOCK_PRESS_LIMIT) {
      terminationActor = side;
      endGame(new GameResult(GameResultType.WRONG_CLOCK_PRESS_GAME_LOST, opponent,
          sideName(side) + " loses the game by repeatedly pressing the opponent's clock."));
      return WrongClockPressOutcome.withoutPause(true, true);
    }
    clock.stopClock();
    wrongClockPressPausedFor = opponent;
    wrongClockPauseId++;
    toldToWaitForClock.clear();
    if (count == WRONG_CLOCK_PRESS_LIMIT - 1) {
      final String warned = "Your opponent pressed your clock and has been warned: the next press loses them the game.";
      return new WrongClockPressOutcome(true, false,
          "Please do not press your opponent's clock. The game is paused and will continue shortly."
              + WRONG_CLOCK_WARNING,
          warned + " The game is paused; your clock will restart shortly.", wrongClockPauseId, true,
          CLOCK_RESTARTED + WRONG_CLOCK_WARNING, warned + " Your clock has restarted.");
    }
    return new WrongClockPressOutcome(true, false,
        "Please do not press your opponent's clock. The game is paused and will continue shortly.",
        "Your opponent pressed your clock. The game is paused; your clock will restart shortly.", wrongClockPauseId,
        false, CLOCK_RESTARTED, "Your opponent pressed your clock. Your clock has restarted.");
  }

  /**
   * Ends the admonishment pause after a wrong clock press: restarts the clock of the side that was running before
   * the offense. No-op when the game ended meanwhile or no such pause is active.
   *
   * @return the side whose clock was restarted, or {@code null} when nothing happened
   */
  public synchronized Side resumeAfterWrongClockPress() {
    return resumeAfterWrongClockPress(wrongClockPauseId);
  }

  /** Resume only the pause this callback was scheduled for; never consume a newer pause. */
  public synchronized Side resumeAfterWrongClockPress(long pauseId) {
    if (pauseId != wrongClockPauseId) {
      return null;
    }
    if (state != GameState.IN_PROGRESS || wrongClockPressPausedFor == null
        || waitingForRestoration || waitingForReady || restorationResumePending
        || wrongClockPressPausedFor != board.getSideToMove() || clock.getRunningFor() != Side.NONE) {
      wrongClockPressPausedFor = null;
      return null;
    }
    final Side side = wrongClockPressPausedFor;
    wrongClockPressPausedFor = null;
    clock.startClock(side);
    return side;
  }

  /**
   * @return whether the arbiter has stopped the clock after a clock press without a move and will restart it by
   *         itself. A press of the own lever in this pause is not ruled on.
   */
  public synchronized boolean isPressWithoutMovePause() {
    return pressWithoutMovePause;
  }

  /** @return what identifies the current pause after a press without a move for its delayed restart */
  public synchronized long getPressWithoutMovePauseId() {
    return pressWithoutMovePauseId;
  }

  /**
   * Ends the pause after a clock press without a move: restarts the clock of the player to move. Only the pause the
   * callback was scheduled for; no-op when the game ended or another intervention took over meanwhile.
   *
   * @return whether this call restarted the clock
   */
  public synchronized boolean resumeAfterPressWithoutMove(long pauseId) {
    if (!pressWithoutMovePause || pauseId != pressWithoutMovePauseId) {
      return false;
    }
    pressWithoutMovePause = false;
    unclearPlacementPause = false;
    if (state != GameState.IN_PROGRESS || waitingForRestoration || waitingForReady || restorationResumePending
        || clock.getRunningFor() != Side.NONE) {
      return false;
    }
    clock.startClock(board.getSideToMove());
    return true;
  }

  // ===== Abandonment =====

  /**
   * A player abandoned the game (closed the browser / never reconnected). Adjudicated like a resignation, as chess
   * servers do: the leaver loses — unless the remaining player could not checkmate by any series of legal moves
   * (helpmate test via {@link Adjudicator}, QUICK variant like {@link #resign(Side)}), in which case it is a draw.
   *
   * <p>
   * A clock that has run out but has not been adjudicated yet comes first, like for every other action that can decide
   * the game: the game then ends on time inside this call and nothing is left to adjudicate here.
   *
   * @return the game result, or {@code null} when there is nothing to adjudicate (game not running, or decided by
   *         the flag fall this call found - see {@link #getResult()})
   */
  public synchronized GameResult abandon(Side side) {
    checkFlagFall();
    if (state != GameState.IN_PROGRESS) {
      // Never started (reaper territory) or already decided (e.g. flag fell while they were gone).
      return null;
    }
    final Side opponent = side.getOppositeSide();
    terminationActor = side;

    if (Adjudicator.adjudicateResignationQuick(board, side) == AdjudicationResult.DRAW) {
      drawExceptionByInsufficientMaterial = board.isInsufficientMaterial(opponent);
      final GameResult drawResult = new GameResult(GameResultType.ABANDONMENT, Side.NONE,
          sideName(side) + " left the game, but because " + drawReason(opponent) + ", the game is a draw.");
      endGame(drawResult);
      return drawResult;
    }

    final GameResult lossResult = new GameResult(GameResultType.ABANDONMENT, opponent,
        sideName(side) + " left the game. " + sideName(opponent) + " wins the game.");
    endGame(lossResult);
    return lossResult;
  }

  // ===== Flag fall =====

  /**
   * Checks for flag fall. Called periodically, and first thing by every player action that can decide the game:
   * the periodic check leaves up to a second in which an expired clock has not been adjudicated yet, and an action
   * arriving in that gap must not outrank the flag fall.
   *
   * <p>
   * FIDE 6.9: a player who runs out of time loses unless the opponent could not checkmate by any series of legal moves,
   * in which case it is a draw. {@link Adjudicator} applies that exception; we use the QUICK variant for the same
   * live-play / latency reason as {@link #resign(Side)}. On a draw the message splits insufficient material from a
   * position unwinnable despite sufficient material (see {@link #drawReason(Side)}).
   */
  public synchronized Optional<GameResult> checkFlagFall() {
    if (state != GameState.IN_PROGRESS) {
      return Optional.empty();
    }

    clock.tick();

    for (final Side side : new Side[] { Side.WHITE, Side.BLACK }) {
      if (clock.isFlagFall(side)) {
        final Side opponent = side.getOppositeSide();

        final GameResult flagResult;
        if (Adjudicator.adjudicateFlagfallQuick(board, side) == AdjudicationResult.DRAW) {
          terminationActor = side;
          drawExceptionByInsufficientMaterial = board.isInsufficientMaterial(opponent);
          flagResult = new GameResult(GameResultType.FLAG_FALL, Side.NONE,
              sideName(side) + " flagged, but because " + drawReason(opponent) + ", the game is a draw.");
        } else {
          flagResult = new GameResult(GameResultType.FLAG_FALL, opponent,
              sideName(side) + " loses on time. " + sideName(opponent) + " wins the game.");
        }

        endGame(flagResult);
        return Optional.of(flagResult);
      }
    }

    return Optional.empty();
  }

  // ===== Automatic game endings =====

  /**
   * Game-ending detection that runs after every accepted move and on every auto-end board event. Only fast checks are
   * allowed here — never the full CUA (isDeadPositionFull / isUnwinnableFull). Insufficient material is detected via
   * the cheap structural test on the board; positions that are dead by exhaustive search but not by insufficient
   * material are not auto-ended (the players will end them via fivefold/75-move/stalemate or claim a draw).
   */
  private Optional<GameResult> checkAutomaticEndings() {
    // 1. Checkmate
    if (board.isCheckmate()) {
      final Side winner = board.getSideToMove().getOppositeSide();
      return Optional
          .of(new GameResult(GameResultType.CHECKMATE, winner, sideName(winner) + " won the game by checkmate."));
    }

    // 2. Stalemate
    if (board.isStalemate()) {
      return Optional.of(new GameResult(GameResultType.STALEMATE, Side.NONE, "The game is drawn by stalemate."));
    }

    // 3. Insufficient material (FIDE 9.4 / 5.2.2). Fast structural check; no search.
    if (board.isInsufficientMaterial()) {
      return Optional.of(new GameResult(GameResultType.DEAD_POSITION, Side.NONE,
          "The game is drawn by insufficient material. Neither player can checkmate."));
    }

    // 4. Fivefold repetition
    if (board.isFivefoldRepetition()) {
      return Optional.of(
          new GameResult(GameResultType.FIVEFOLD_REPETITION, Side.NONE, "The game is drawn by fivefold repetition."));
    }

    // 5. 75-move rule
    if (board.isSeventyFiveMove()) {
      return Optional
          .of(new GameResult(GameResultType.SEVENTY_FIVE_MOVE, Side.NONE, "The game is drawn by the 75-move rule."));
    }

    return Optional.empty();
  }

  // ===== Helpers =====

  private ArbiterResponse evaluateMove(BitboardPosition afterPosition) {
    return finalMoveCommitment == null ? arbiter.evaluateClockPress(board, afterPosition, currentSequence)
        : arbiter.evaluateFinalMove(board, afterPosition, finalMoveCommitment, currentSequence);
  }

  /** Last line of defense: no acceptance path may apply a different move after a legal release. */
  private void applyCommittedMove(MoveSpecification move) {
    if (finalMoveCommitment != null && !finalMoveCommitment.move().moveSpecification().equals(move)) {
      throw new IllegalStateException("Cannot replace the final released move with a different move");
    }
    board.move(move);
  }

  private void startNewTurn() {
    this.boardMarks = List.of();
    this.wrongClockPressPausedFor = null;
    this.pressWithoutMovePause = false;
    this.unclearPlacementPause = false;
    this.bindingCalled = false;
    this.finalMoveCommitment = null;
    this.currentSequence = new ActionSequence(board.getSideToMove());
    this.positionBeforeTurn = board.getBitboardPosition();
    this.restorationTargetPosition = positionBeforeTurn;
    this.removedSquaresThisTurn.clear();
    this.mustExecuteMove = null;
    this.mustExecuteMoveSan = null;
    this.claimMadeThisTurn = false;
    this.restorationFromReleasedPiece = false;
    // The wrong-time offer escalation is per move (a draw offer is only semi-illegal) — the
    // count never carries over, unlike the claim ladders.
    drawOfferManager.resetWrongTimeCountsForNewMove();
  }

  private void endGame(GameResult gameResult) {
    if (state == GameState.ENDED) {
      return;
    }
    this.state = GameState.ENDED;
    this.result = gameResult;
    this.endedAtMs = System.currentTimeMillis();
    this.wrongClockPressPausedFor = null;
    this.pressWithoutMovePause = false;
    this.waitingForReady = false;
    this.waitingForRestoration = false;
    this.restorationResumePending = false;
    // A move owed after a rejected draw claim is no longer owed: nothing may ask for it, or for a
    // restoration towards it, once the game is over.
    this.mustExecuteMove = null;
    this.mustExecuteMoveSan = null;
    // Nothing is owed any more, so nothing is pointed at. The illegal move that loses the game is marked
    // afterwards, by its own ruling.
    this.boardMarks = List.of();
    drawOfferManager.clearOffer();
    this.clock.stopClock();
  }

  private static String sideName(Side side) {
    return side == Side.WHITE ? "White" : "Black";
  }

  /**
   * Reason clause for a flag-fall / resignation that {@link Adjudicator} ruled a draw: the would-be winner cannot mate.
   * The adjudicator folds two FIDE cases together; we split them for the player-facing message using the cheap
   * structural material test - a material shortage (lone king, K+B, K+N, ...) versus a position that is unwinnable
   * despite sufficient material (a blocked wall / fortress).
   *
   * @param opponent the would-be winner (the side that did not resign / flag)
   */
  private String drawReason(Side opponent) {
    return board.isInsufficientMaterial(opponent) ? sideName(opponent) + " has insufficient material to mate"
        : sideName(opponent) + " has no potential mate";
  }

  /**
   * Enters the "waiting for ready" state after an arbiter intervention. Both players must signal readiness before the
   * game continues.
   *
   * <p>
   * The released-piece rule window is reset here because, after a recovery handshake, prior in-turn events should not
   * retroactively bind the resumed play. In particular, a "legal-in-isolation" release that was actually invalid in
   * context (e.g. a pawn drop that violates an active touch-move obligation on a different piece) must not be treated
   * as a commitment after the recovery — otherwise the player can never satisfy the touch-move and the rule deadlocks.
   * A completed legal move is retained separately in finalMoveCommitment, so unrelated interventions cannot erase it.
   */
  public synchronized void enterWaitingForReady() {
    this.wrongClockPressPausedFor = null;
    this.pressWithoutMovePause = false;
    this.waitingForReady = true;
    this.whiteReady = false;
    this.blackReady = false;
    currentSequence.resetReleasedPieceRule();
    bindingCalled = false; // a binding ends with the rule window
  }

  /**
   * Enters the restoration state after an invalid move. Board events are then monitored until the physical board
   * matches the position before the turn.
   */
  public synchronized void enterWaitingForRestoration() {
    enterWaitingForRestoration(positionBeforeTurn);
  }

  public synchronized void enterWaitingForRestoration(BitboardPosition restorationTargetPosition) {
    if (state == GameState.ENDED) {
      return; // endGame cleared every pause; a finished game must not be paused again
    }
    RestoreTargetInvariant.verify(board, restorationTargetPosition);
    this.wrongClockPressPausedFor = null;
    this.pressWithoutMovePause = false;
    this.waitingForRestoration = true;
    this.restorationResumePending = false;
    this.waitingForReady = false;
    this.whiteReady = false;
    this.blackReady = false;
    this.restorationTargetPosition = finalMoveCommitment == null ? restorationTargetPosition
        : finalMoveCommitment.position();
    if (finalMoveCommitment != null) {
      this.restorationFromReleasedPiece = true;
    }
  }

  /**
   * Marks the restore flow complete and either waits for ready confirmation or resumes later.
   *
   * @return whether a restoration was pending; false leaves the game untouched, so a request that arrives when no
   *         restoration is asked for (a repeated click, a message from a modified client) cannot pause a running game
   */
  public synchronized boolean completeRestoration() {
    if (state != GameState.IN_PROGRESS || !waitingForRestoration) {
      return false;
    }
    this.waitingForRestoration = false;
    currentSequence.markPositionRestored();
    // The illegal move is off the board again, so its squares are no longer pointed at. What is owed stays
    // marked until the move is accepted: a touched piece, and the move that was already made.
    this.boardMarks = marksOf(BoardMark.Color.GREEN);
    // Preserve the FIDE 4.7 released-piece commitment when THIS restoration was caused by a
    // released-piece violation: the committed move must stay final across revert cycles until the
    // player actually plays it and presses the clock. Illegal/touch-move restorations still reset
    // the rule window (see enterWaitingForReady's rationale) to avoid deadlocks.
    if (!restorationFromReleasedPiece) {
      currentSequence.resetReleasedPieceRule();
      bindingCalled = false; // a binding ends with the rule window
    }
    this.whiteReady = false;
    this.blackReady = false;
    if (autoResumeAfterRestore) {
      this.restorationResumePending = true;
      this.waitingForReady = false;
    } else {
      this.restorationResumePending = false;
      this.waitingForReady = true;
    }
    return true;
  }

  /**
   * Restarts the clock after an automatic restoration pause.
   *
   * @return whether this call restarted the clock; false when the pause is over already (the game ended, another
   *         intervention took over, or a clock press resumed the game first)
   */
  public synchronized boolean resumeAfterRestorationDelay() {
    if (state == GameState.IN_PROGRESS && restorationResumePending && !waitingForReady && !waitingForRestoration) {
      restorationResumePending = false;
      clock.startClock(board.getSideToMove());
      return true;
    }
    return false;
  }

  /**
   * @return the squares the arbiter currently points at, for both boards, one circle per square; empty when there are
   *         none. On the square where an illegal move of the touched piece started, the red circle covers the green
   *         one until the position is restored.
   */
  public synchronized List<BoardMark> getBoardMarks() {
    final List<BoardMark> red = marksOf(BoardMark.Color.RED);
    final List<BoardMark> bound = boundSquareMarks();
    if (red.isEmpty() && bound.isEmpty()) {
      return boardMarks;
    }
    final List<BoardMark> marks = new ArrayList<>(marksOf(BoardMark.Color.GREEN));
    bound.stream().filter(mark -> !marks.contains(mark)).forEach(marks::add);
    marks.removeIf(green -> red.stream().anyMatch(mark -> mark.square() == green.square()));
    marks.addAll(red);
    return List.copyOf(marks);
  }

  /**
   * Free placement: the squares a piece is bound to by an unclear release, green like everything the player has to
   * play - from the call that named them until the piece stands on one of them.
   */
  private List<BoardMark> boundSquareMarks() {
    if (!bindingCalled) {
      return List.of();
    }
    return misplacedBinding().map(binding -> binding.squares().stream().map(BoardMark::green).toList())
        .orElse(List.of());
  }

  /** @return and clears whether a clock press ended the automatic restoration pause since the last call. */
  public synchronized boolean consumeResumedByClockPress() {
    final boolean resumed = resumedByClockPress;
    resumedByClockPress = false;
    return resumed;
  }

  /**
   * @return and clears whether the last press of the own lever registered: the clock was running for the player and
   *         the arbiter ruled on the move, whatever the ruling. A press that is not ruled on - the game is over, it is
   *         not the player's turn, the game is paused, the time has expired - does not register.
   */
  public synchronized boolean consumeClockPressRegistered() {
    final boolean registered = clockPressRegistered;
    clockPressRegistered = false;
    return registered;
  }

  /**
   * Continues after an intervention where the required reference position already matches the physical board. The
   * action sequence is deliberately preserved: for an incomplete castling move, the king release still binds the player
   * to finish castling by moving the rook.
   */
  public synchronized void continueWithoutRestoration() {
    if (state == GameState.IN_PROGRESS && !waitingForReady && !waitingForRestoration) {
      restorationResumePending = false;
      clock.startClock(board.getSideToMove());
    }
  }

  /**
   * A player signals readiness to continue after an arbiter intervention.
   *
   * @return true if both players are now ready and the game should continue
   */
  public synchronized boolean playerReady(Side side) {
    if (!waitingForReady) {
      return false;
    }
    switch (side) {
      case WHITE -> whiteReady = true;
      case BLACK -> blackReady = true;
      default -> {
        return false;
      }
    }
    if (whiteReady && blackReady) {
      waitingForReady = false;
      unclearPlacementPause = false;
      if (state == GameState.IN_PROGRESS) {
        clock.startClock(board.getSideToMove());
      }
      return true;
    }
    return false;
  }

  /**
   * Returns the position before the current turn, for restoring after an illegal move.
   */
  public synchronized BitboardPosition getRestorePosition() {
    return restorationTargetPosition;
  }

  public synchronized boolean isWaitingForReady() {
    return waitingForReady;
  }

  public synchronized boolean isWaitingForRestoration() {
    return waitingForRestoration;
  }

  public synchronized boolean isRestorationResumePending() {
    return restorationResumePending;
  }

  public synchronized boolean isRestoredPosition(BitboardPosition position) {
    return restorationTargetPosition.equals(position);
  }

  public synchronized boolean isAutoResumeAfterRestore() {
    return autoResumeAfterRestore;
  }

  /**
   * @return whether the current (latched) restoration is the result of a released-piece commitment (FIDE 4.7) — the
   *         player's move is final and they only need to press the clock. Lets the server phrase the restore/resume
   *         messages so the player doesn't think they still have to move.
   */
  public synchronized boolean isRestorationFromReleasedPiece() {
    return restorationFromReleasedPiece;
  }

  /**
   * @return whether the position the board is restored to is the player's completed move, so that a clock press on it
   *         is accepted. Narrower than {@link #isRestorationFromReleasedPiece()}: a king released on its castling
   *         square is a commitment too, but the rook still has to be moved, and a move owed after a rejected draw
   *         claim is not replaced by another released move. Only this method may word a message as "your move is
   *         final".
   */
  public synchronized boolean isRestoredMoveFinal() {
    if (!restorationFromReleasedPiece) {
      return false;
    }
    if (mustExecuteMove != null) {
      return positionBeforeTurn.afterMove(mustExecuteMove, board.getSideToMove()).equals(restorationTargetPosition);
    }
    return finalMoveCommitment != null && finalMoveCommitment.position().equals(restorationTargetPosition);
  }

  // ===== PGN export =====

  /**
   * The moves of the game in PGN movetext. The library ends a game it does not see decided on the board with the
   * "unknown" marker {@code *}; once this game has a result, the marker is replaced by it.
   */
  public synchronized String exportPgn() {
    final String pgn = PgnCreate.toPgnString(board);
    final int marker = pgn.lastIndexOf('*');
    if (state != GameState.ENDED || result == null || marker < 0) {
      return pgn;
    }
    final String token = switch (result.winner()) {
      case WHITE -> "1-0";
      case BLACK -> "0-1";
      default -> "1/2-1/2";
    };
    return pgn.substring(0, marker) + token + pgn.substring(marker + 1);
  }

  // ===== Getters =====

  public synchronized GameState getState() {
    return state;
  }

  public synchronized GameResult getResult() {
    return result;
  }

  public synchronized long getEndedAtMs() {
    return endedAtMs;
  }

  public synchronized boolean isReady(Side side) {
    return side == Side.WHITE ? whiteReady : blackReady;
  }

  /** The side that resigned, flagged, or accepted a draw (for the personalised end-of-game message). */
  public synchronized Side getTerminationActor() {
    return terminationActor;
  }

  /** Whether the draw-by-exception is by insufficient material (vs. unwinnable despite material). */
  public synchronized boolean isDrawExceptionByInsufficientMaterial() {
    return drawExceptionByInsufficientMaterial;
  }

  public synchronized Side getHavingMove() {
    return board.getSideToMove();
  }

  public synchronized Board getBoard() {
    return board;
  }

  public synchronized ClockManager getClock() {
    return clock;
  }

  public synchronized DrawOfferManager getDrawOfferManager() {
    return drawOfferManager;
  }

  public synchronized BitboardPosition getPositionBeforeTurn() {
    return positionBeforeTurn;
  }

  public synchronized MoveSpecification getMustExecuteMove() {
    return mustExecuteMove;
  }
}
