package com.dlb.chess.dumbboard.message;

public enum MessageSeverity {
  INFO("info"),
  SUCCESS("success"),
  WARNING("warning"),
  ERROR("error");

  private final String style;

  MessageSeverity(String style) {
    this.style = style;
  }

  public String style() {
    return style;
  }
}
