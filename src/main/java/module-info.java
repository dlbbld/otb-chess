/**
 * OTB Chess -- over-the-board chess arbiter server.
 *
 * <p>
 * Explicit JPMS module. Its dependencies resolve on the module path as follows:
 * <ul>
 * <li>{@code io.github.dlbbld.ashlarchess} -- ashlar-chess 19.1.0, an <em>automatic</em> module. It has a stable
 * {@code Automatic-Module-Name} (so this {@code requires} is forward-compatible with a future explicit
 * {@code module-info} of the same name) but no descriptor yet, so it exports every package.</li>
 * <li>{@code com.google.common} -- Guava. Required only because ashlar-chess's public API leaks Guava types
 * (e.g. {@code Board.getLegalMoves()} returns {@code ImmutableList}, {@code Square.REAL} is a Guava collection). An
 * automatic module cannot {@code requires transitive} its own dependencies, so this module must read Guava directly.
 * Drop this {@code requires} (and the matching pom dependency) once ashlar-chess stops leaking Guava or declares it
 * transitively.</li>
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
  requires com.google.common;
  requires org.java_websocket;
  requires com.google.gson;
  requires jdk.httpserver;
}
