import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E config for the Dumb Chessboard web app.
 *
 * The app is a Java HTTP server (static files, :8080) + WebSocket game server (:8081).
 * `webServer` boots the Java server before the suite and tears it down after; locally an
 * already-running server (e.g. started via start.bat) is reused.
 *
 * Tests run SERIALLY (workers: 1) on purpose: the game server is stateful (shared game
 * rooms) and the /api/lastGameId endpoint is global, so parallel files could race. Each
 * test still creates its own game (isolated by id) and reads the game code from the DOM,
 * never from that global endpoint.
 */
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command:
      'mvn -q compile exec:java -Dexec.mainClass=com.dlb.chess.dumbboard.server.DumbChessboardServer',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
