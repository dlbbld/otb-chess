package io.github.dlbbld.otbchess.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import io.github.dlbbld.ashlarchess.board.Board;

/**
 * Main entry point for the OTB Chess server.
 *
 * <p>
 * Starts two servers:
 * <ul>
 * <li>HTTP server on port 8080 for serving static files (HTML, CSS, JS)</li>
 * <li>WebSocket server on port 8081 for real-time game communication</li>
 * </ul>
 */
public class OtbChessServer {

  private static final int HTTP_PORT = 8080;
  private static final int WS_PORT = 8081;
  private static final Path STATIC_DIR = Path.of("static");

  public static void main(String[] args) throws IOException, InterruptedException {
    // Start the WebSocket server first and wait until it is actually listening. The HTTP server
    // (below) is exposed only afterwards, so that once the page is reachable over HTTP the browser
    // can always open its WebSocket. This removes a startup race where a client could load the page
    // before :8081 was bound (the client has no WebSocket-reconnect path).
    final var wsServer = new GameWebSocketServer(WS_PORT);
    wsServer.start();
    try {
      if (!wsServer.awaitStarted(10, TimeUnit.SECONDS)) {
        throw new IOException("WebSocket server did not start within 10 seconds");
      }
      System.out.println("WebSocket server running at ws://localhost:" + WS_PORT);

      // Start HTTP server for static files.
      final var httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
      final var staticHandler = new StaticFileHandler(STATIC_DIR.toAbsolutePath());
      // Exposes the Maven project version embedded in the runnable JAR manifest, so static pages
      // can display the release without duplicating it in HTML or JavaScript.
      httpServer.createContext("/api/version", exchange -> {
        final byte[] body = appVersionJson();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
          os.write(body);
        }
      });
      // Validates a starting FEN before the lobby navigates to the board, so an invalid FEN keeps
      // the player on the start screen with the reason instead of stranding them on a dead board.
      // Returns {"valid":true} or {"valid":false,"message":"Invalid FEN: ..."} from Ashlar Chess.
      httpServer.createContext("/api/validateFen", exchange -> {
        final byte[] body = validateFenResponse(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
          os.write(body);
        }
      });
      httpServer.createContext("/", staticHandler::handle);
      httpServer.setExecutor(null);
      httpServer.start();
      System.out.println("HTTP server running at http://localhost:" + HTTP_PORT);
    } catch (IOException | InterruptedException | RuntimeException e) {
      // Startup failed after the WebSocket server began listening (e.g. the HTTP port is taken).
      // Stop it so the JVM can exit instead of lingering on :8081 (its selector thread is non-daemon).
      try {
        wsServer.stop();
      } catch (final InterruptedException stopInterrupted) {
        Thread.currentThread().interrupt();
      }
      throw e;
    }

    System.out.println();
    System.out.println("OTB Chess is ready!");
    System.out.println("Open http://localhost:" + HTTP_PORT + " in your browser to start.");
  }

  private static final Gson GSON = new Gson();

  /**
   * Builds the {@code /api/validateFen} response body. An empty FEN means the normal starting position and is always
   * valid. Any parse failure is treated as a user FEN error (the FEN is user input) and reported verbatim from Ashlar
   * Chess, mirroring the create-game validation.
   */
  private static byte[] validateFenResponse(String rawQuery) {
    final String fen = queryParam(rawQuery, "fen");
    final JsonObject obj = new JsonObject();
    if (fen == null || fen.isBlank()) {
      obj.addProperty("valid", true);
    } else {
      try {
        Board.fromFenStrict(fen.trim());
        obj.addProperty("valid", true);
      } catch (final Exception e) {
        obj.addProperty("valid", false);
        obj.addProperty("message", "Invalid FEN: " + e.getMessage());
      }
    }
    return GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] appVersionJson() {
    final JsonObject obj = new JsonObject();
    obj.addProperty("version", appVersion());
    return GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
  }

  private static String appVersion() {
    final String version = OtbChessServer.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }

  private static String queryParam(String rawQuery, String key) {
    if (rawQuery == null) {
      return null;
    }
    for (final String pair : rawQuery.split("&")) {
      final int eq = pair.indexOf('=');
      if (eq > 0 && key.equals(pair.substring(0, eq))) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
