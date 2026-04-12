package com.dlb.chess.dumbboard.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dlb.chess.board.enums.Side;

/**
 * Records the full sequence of board events within a single turn (between clock presses).
 */
public class ActionSequence {

  private final List<BoardEvent> events;
  private final Side sideToMove;

  public ActionSequence(Side sideToMove) {
    this.sideToMove = sideToMove;
    this.events = new ArrayList<>();
  }

  public void addEvent(BoardEvent event) {
    events.add(event);
  }

  public List<BoardEvent> getEvents() {
    return Collections.unmodifiableList(events);
  }

  public Side getSideToMove() {
    return sideToMove;
  }

  public void clear() {
    events.clear();
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }
}
