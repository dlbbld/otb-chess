// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.dlbbld.ashlarchess.board.enums.Side;

/**
 * Records the full sequence of board events within a single turn (between clock presses).
 */
public class ActionSequence {

  private final List<BoardEvent> events;
  private final Side sideToMove;
  private int releasedPieceRuleStartIndex;
  private int positionRestoredIndex;
  private boolean positionRestored;

  public ActionSequence(Side sideToMove) {
    this.sideToMove = sideToMove;
    this.events = new ArrayList<>();
    this.releasedPieceRuleStartIndex = 0;
    this.positionRestoredIndex = 0;
  }

  public void addEvent(BoardEvent event) {
    events.add(event);
  }

  public List<BoardEvent> getEvents() {
    return Collections.unmodifiableList(events);
  }

  public List<BoardEvent> getEventsSinceReleasedPieceRuleReset() {
    return Collections.unmodifiableList(events.subList(releasedPieceRuleStartIndex, events.size()));
  }

  public void resetReleasedPieceRule() {
    releasedPieceRuleStartIndex = events.size();
  }

  /**
   * The events since the board was last set back by a restoration - all of the turn's events when there was none.
   * They are what led to the position that is on the board now; the earlier ones still say what was touched.
   */
  public List<BoardEvent> getEventsSincePositionRestored() {
    return Collections.unmodifiableList(events.subList(positionRestoredIndex, events.size()));
  }

  /** The board has been set back by a restoration: what was done on it before no longer shows on it. */
  public void markPositionRestored() {
    positionRestoredIndex = events.size();
    positionRestored = true;
  }

  /**
   * @return whether the board was set back by a restoration in this turn: the arbiter has ruled on the turn at least
   *         once, and what is on the board now replaces what it took off
   */
  public boolean hasPositionBeenRestored() {
    return positionRestored;
  }

  public Side getSideToMove() {
    return sideToMove;
  }

  public void clear() {
    events.clear();
    releasedPieceRuleStartIndex = 0;
    positionRestoredIndex = 0;
    positionRestored = false;
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }
}
