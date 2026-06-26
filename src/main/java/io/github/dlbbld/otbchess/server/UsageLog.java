// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, privacy-preserving usage log (see {@code PRIVACY.md}).
 *
 * <p>
 * Records exactly two event types — a game being <em>created</em> and a game being <em>joined</em> — as one line each:
 * {@code <ISO-8601 timestamp>\t<event>\t<gameId>}. The game id pairs a create with its join so we can tell whether a
 * created game ever got a second player. Nothing else is written: no move counts, results, rule-violation counts,
 * board/PGN state, names, emails, or IP addresses.
 *
 * <p>
 * Entries are purged after a fixed retention window (default 30 days). Writes are serialized; the log is best-effort, so
 * an I/O failure is reported to stderr and never breaks gameplay.
 */
public final class UsageLog {

  /** Event written when a game room is created. */
  public static final String EVENT_CREATE = "create";
  /** Event written when a second player joins a game room. */
  public static final String EVENT_JOIN = "join";

  private final Path file;
  private final Duration retention;
  private final Object lock = new Object();

  public UsageLog(Path file, Duration retention) {
    this.file = file;
    this.retention = retention;
  }

  /**
   * Builds a usage log from configuration. {@code logPath} is the file to append to (its parent directory is created if
   * needed); {@code retentionDays} is how long entries are kept.
   */
  public static UsageLog fromConfig(String logPath, int retentionDays) {
    return new UsageLog(Path.of(logPath), Duration.ofDays(retentionDays));
  }

  /**
   * Appends one event line. {@code gameId} is the join code (not personal data). Failures are logged to stderr and
   * swallowed so usage logging can never disrupt a game.
   */
  public void record(String event, String gameId) {
    final String line = Instant.now().toString() + '\t' + event + '\t' + gameId + System.lineSeparator();
    synchronized (lock) {
      try {
        if (file.getParent() != null) {
          Files.createDirectories(file.getParent());
        }
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (final IOException e) {
        System.err.println("[usage] failed to write " + event + " for " + gameId + ": " + e);
      }
    }
  }

  /**
   * Drops entries older than the retention window by rewriting the file. Lines whose leading timestamp cannot be parsed
   * are kept (fail-open: we never discard data we don't understand).
   */
  public void purgeExpired() {
    final Instant cutoff = Instant.now().minus(retention);
    synchronized (lock) {
      if (!Files.exists(file)) {
        return;
      }
      try {
        final List<String> kept = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          if (!isExpired(line, cutoff)) {
            kept.add(line);
          }
        }
        Files.write(file, kept, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      } catch (final IOException e) {
        System.err.println("[usage] purge failed: " + e);
      }
    }
  }

  private static boolean isExpired(String line, Instant cutoff) {
    final int tab = line.indexOf('\t');
    if (tab <= 0) {
      return false;
    }
    try {
      return Instant.parse(line.substring(0, tab)).isBefore(cutoff);
    } catch (final RuntimeException e) {
      return false;
    }
  }
}
