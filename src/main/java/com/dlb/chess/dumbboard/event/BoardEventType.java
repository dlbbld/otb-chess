package com.dlb.chess.dumbboard.event;

public enum BoardEventType {

  // Player clicks a piece and releases on the same square (touch)
  CLICK,

  // Player drags a piece from one square to an empty square
  DRAG_MOVE,

  // Player drags a piece from one square onto an occupied square (displaced piece goes to side area)
  DRAG_CAPTURE,

  // Player drags a piece off the board (piece goes to side area)
  REMOVE,

  // Player drags a piece from side area onto an empty square
  RESTORE_TO_EMPTY,

  // Player drags a piece from side area onto an occupied square (displaced piece goes to side area)
  RESTORE_TO_OCCUPIED
}
