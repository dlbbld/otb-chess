// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
/**
 * OTB Chess -- over-the-board chess arbiter server.
 *
 * <p>
 * Explicit JPMS module. Its dependencies resolve on the module path as follows:
 * <ul>
 * <li>{@code io.github.dlbbld.ashlarchess} -- ashlar-chess, an <em>explicit</em> JPMS module that exports only its
 * curated public API. Its {@code @NonNull}/{@code @Nullable} annotations resolve via its
 * {@code requires static transitive org.eclipse.jdt.annotation}, so no JDT dependency is needed here. It no longer
 * leaks Guava in its public API, so this module no longer reads Guava.</li>
 * <li>{@code org.java_websocket}, {@code com.google.gson} -- real modules.</li>
 * <li>{@code jdk.httpserver} -- JDK module providing {@code com.sun.net.httpserver}, used for static-file serving and
 * the small REST endpoints.</li>
 * </ul>
 *
 * <p>
 * No {@code opens} directive is needed: all JSON (de)serialization goes through the gson tree API
 * ({@code JsonObject} / {@code Map}), so gson never reflects over this module's own types.
 *
 * <p>
 * log4j is a runtime logging backend only and is not referenced anywhere in this module's code, so it is intentionally
 * not a {@code requires} here.
 *
 * <p>
 * Nothing is {@code exports}-ed: this is an application, not a library, and the launcher reaches the main class
 * ({@code io.github.dlbbld.otbchess.server.OtbChessServer}) from within the module.
 */
module io.github.dlbbld.otbchess {
  requires io.github.dlbbld.ashlarchess;
  requires org.java_websocket;
  requires com.google.gson;
  requires jdk.httpserver;
}
