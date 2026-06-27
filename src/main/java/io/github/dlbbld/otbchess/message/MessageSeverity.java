// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.message;

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
