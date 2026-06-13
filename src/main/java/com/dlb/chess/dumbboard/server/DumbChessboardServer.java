package com.dlb.chess.dumbboard.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

/**
 * Main entry point for the Dumb Chessboard server.
 *
 * <p>Starts two servers:
 * <ul>
 *   <li>HTTP server on port 8080 for serving static files (HTML, CSS, JS)</li>
 *   <li>WebSocket server on port 8081 for real-time game communication</li>
 * </ul>
 */
public class DumbChessboardServer {

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
    if (!wsServer.awaitStarted(10, TimeUnit.SECONDS)) {
      throw new IOException("WebSocket server did not start within 10 seconds");
    }
    System.out.println("WebSocket server running at ws://localhost:" + WS_PORT);

    // Start HTTP server for static files. The /api/lastGameId endpoint queries the WebSocket server.
    final var httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
    final var staticHandler = new StaticFileHandler(STATIC_DIR.toAbsolutePath());
    // TESTING-ONLY: /api/lastGameId returns the most recently created joinable game ID so the
    // lobby in a second browser session can pre-fill the join code. Remove once development is done.
    httpServer.createContext("/api/lastGameId", exchange -> {
      final String id = wsServer.getJoinableLastCreatedGameId();
      final byte[] body = (id == null ? "" : id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
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

    System.out.println();
    System.out.println("Dumb Chessboard is ready!");
    System.out.println("Open http://localhost:" + HTTP_PORT + " in your browser to start.");
  }
}
