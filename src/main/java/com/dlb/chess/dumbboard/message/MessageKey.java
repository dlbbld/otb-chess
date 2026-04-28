package com.dlb.chess.dumbboard.message;

public enum MessageKey {
  CUSTOM_INFO("custom.info", MessageSeverity.INFO),
  CUSTOM_WARNING("custom.warning", MessageSeverity.WARNING),
  CUSTOM_ERROR("custom.error", MessageSeverity.ERROR),

  ARBITER_MOVE_ACCEPTED("arbiter.move_accepted", MessageSeverity.SUCCESS),
  ARBITER_TOUCH_MOVE_OWN_PLAYER("arbiter.touch_move.own.player", MessageSeverity.ERROR),
  ARBITER_TOUCH_MOVE_OWN_OPPONENT("arbiter.touch_move.own.opponent", MessageSeverity.ERROR),
  ARBITER_TOUCH_MOVE_OPPONENT_PLAYER("arbiter.touch_move.opponent.player", MessageSeverity.ERROR),
  ARBITER_TOUCH_MOVE_OPPONENT_OPPONENT("arbiter.touch_move.opponent.opponent", MessageSeverity.ERROR),
  ARBITER_RELEASED_PIECE_PLAYER("arbiter.released_piece.player", MessageSeverity.ERROR),
  ARBITER_RELEASED_PIECE_OPPONENT("arbiter.released_piece.opponent", MessageSeverity.ERROR),
  ARBITER_RELEASED_PIECE_CASTLING_PLAYER("arbiter.released_piece.castling.player", MessageSeverity.ERROR),
  ARBITER_RELEASED_PIECE_CASTLING_OPPONENT("arbiter.released_piece.castling.opponent", MessageSeverity.ERROR),
  ARBITER_ILLEGAL_MOVE_PLAYER_NEXT("arbiter.illegal_move.player.next", MessageSeverity.ERROR),
  ARBITER_ILLEGAL_MOVE_PLAYER_LIMIT("arbiter.illegal_move.player.limit", MessageSeverity.ERROR),
  ARBITER_ILLEGAL_MOVE_PLAYER_UNLIMITED("arbiter.illegal_move.player.unlimited", MessageSeverity.ERROR),
  ARBITER_ILLEGAL_MOVE_OPPONENT("arbiter.illegal_move.opponent", MessageSeverity.ERROR),
  ARBITER_ILLEGAL_MOVE_OPPONENT_GENERIC("arbiter.illegal_move.opponent.generic", MessageSeverity.ERROR),
  ARBITER_ILLEGAL_MOVE_GAME_LOST_PLAYER("arbiter.illegal_move.game_lost.player", MessageSeverity.ERROR),
  ARBITER_POSITION_CHANGE_OPPONENT_PIECE("arbiter.position_change.opponent_piece", MessageSeverity.ERROR);

  private final String propertyKey;
  private final MessageSeverity severity;

  MessageKey(String propertyKey, MessageSeverity severity) {
    this.propertyKey = propertyKey;
    this.severity = severity;
  }

  public String propertyKey() {
    return propertyKey;
  }

  public MessageSeverity severity() {
    return severity;
  }
}
