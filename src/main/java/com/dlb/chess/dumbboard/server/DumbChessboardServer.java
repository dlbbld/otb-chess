package com.dlb.chess.dumbboard.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

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

  public static void main(String[] args) throws IOException {
    // Start HTTP server for static files
    final var httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
    final var staticHandler = new StaticFileHandler(STATIC_DIR.toAbsolutePath());
    httpServer.createContext("/", staticHandler::handle);
    httpServer.setExecutor(null);
    httpServer.start();
    System.out.println("HTTP server running at http://localhost:" + HTTP_PORT);

    // Start WebSocket server for game communication
    final var wsServer = new GameWebSocketServer(WS_PORT);
    wsServer.start();
    System.out.println("WebSocket server running at ws://localhost:" + WS_PORT);

    System.out.println();
    System.out.println("Dumb Chessboard is ready!");
    System.out.println("Open http://localhost:" + HTTP_PORT + " in your browser to start.");
  }
}
