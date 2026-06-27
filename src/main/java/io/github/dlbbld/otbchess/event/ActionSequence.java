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

  public ActionSequence(Side sideToMove) {
    this.sideToMove = sideToMove;
    this.events = new ArrayList<>();
    this.releasedPieceRuleStartIndex = 0;
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

  public Side getSideToMove() {
    return sideToMove;
  }

  public void clear() {
    events.clear();
    releasedPieceRuleStartIndex = 0;
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }
}
