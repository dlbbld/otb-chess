// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.message;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Restates the chess library's illegal-move reasons as statements about the move the player has made.
 *
 * <p>
 * The library phrases a reason conditionally ("it would leave the own king in check") because the move was only
 * presented to it. On the board the move has been played, so the arbiter says what it does ("it leaves the own king in
 * check"). The pairs live in {@code /messages/illegal-move-reasons.properties} so the wording can be changed, and a new
 * library reason added, without touching the arbiter.
 */
public final class IllegalMoveReasons {

  // Absolute (leading-slash) resource path, for the same module-path reason as Messages.
  private static final String RESOURCE = "/messages/illegal-move-reasons.properties";
  private static final String LIBRARY_SUFFIX = ".library";
  private static final String ARBITER_SUFFIX = ".arbiter";
  private static final Map<String, String> STATEMENTS = loadStatements();

  private IllegalMoveReasons() {
  }

  /**
   * The arbiter's wording for a library reason, or the reason unchanged when it has no entry.
   *
   * @param libraryReason the reason text as the chess library phrases it
   * @return the statement to show the players
   */
  public static String asStatement(String libraryReason) {
    if (libraryReason == null) {
      return null;
    }
    return STATEMENTS.getOrDefault(libraryReason, libraryReason);
  }

  /** The library reason texts that have a restatement, keyed by the library text. Used by the tests. */
  static Map<String, String> statements() {
    return STATEMENTS;
  }

  private static Map<String, String> loadStatements() {
    final Properties properties = new Properties();
    try (var stream = IllegalMoveReasons.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing illegal-move reason resource: " + RESOURCE);
      }
      try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
    } catch (final IOException e) {
      throw new IllegalStateException("Could not load illegal-move reason resource: " + RESOURCE, e);
    }

    final Map<String, String> statements = new LinkedHashMap<>();
    for (final String key : properties.stringPropertyNames()) {
      if (!key.endsWith(LIBRARY_SUFFIX)) {
        continue;
      }
      final String name = key.substring(0, key.length() - LIBRARY_SUFFIX.length());
      final String statement = properties.getProperty(name + ARBITER_SUFFIX);
      if (statement == null || statement.isBlank()) {
        throw new IllegalStateException("Illegal-move reason '" + name + "' has no " + ARBITER_SUFFIX + " wording");
      }
      statements.put(properties.getProperty(key), statement);
    }
    for (final String key : properties.stringPropertyNames()) {
      if (key.endsWith(ARBITER_SUFFIX)
          && properties.getProperty(key.substring(0, key.length() - ARBITER_SUFFIX.length()) + LIBRARY_SUFFIX) == null) {
        throw new IllegalStateException("Illegal-move reason '" + key + "' has no " + LIBRARY_SUFFIX + " text");
      }
    }
    return Map.copyOf(statements);
  }
}
