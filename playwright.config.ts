import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E config for the OTB Chess web app.
 *
 * The app is a Java HTTP server (static files) + WebSocket game server. The e2e suite runs its
 * OWN instance on dedicated ports 18080/18081 (via OTB_HTTP_PORT/OTB_WS_PORT), so it never
 * collides with a dev server on the normal 8080/8081 — you can keep playing/testing on
 * http://localhost:8080 (e.g. start.bat) while the suite runs. `webServer` boots the runnable
 * jar (built by the `build:server` npm script) before the suite and tears it down after. Running
 * `java -jar` directly (rather than `mvn exec:java`) means Playwright owns the JVM process, so the
 * server shuts down cleanly with the runner instead of lingering as an orphaned grandchild.
 * NOTE: the suite tests the packaged jar — rebuild (`npm run build:server` or `npm run e2e`)
 * after Java changes; static files are read from disk, so client changes need no rebuild.
 *
 * Tests run SERIALLY (workers: 1) on purpose: the game server is stateful (shared game
 * rooms), so parallel files could race. Each test still creates its own game (isolated by
 * id) and reads the game code from the DOM.
 */
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:18080',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'java -jar target/otb-chess.jar',
    url: 'http://localhost:18080',
    // NOTE: webServer.env REPLACES the process environment rather than extending it, so spread
    // process.env explicitly — a JVM without SystemRoot/PATH on Windows boots but then fails
    // networking intermittently.
    env: {
      ...process.env as Record<string, string>,
      OTB_HTTP_PORT: '18080',
      OTB_WS_PORT: '18081',
      // Short disconnect/abandonment windows so the abandonment tests run in seconds
      // (production defaults: 12 s grace, 60 s abandonment). The abandonment window leaves
      // enough room after the grace message for a lobby round-trip (close tab -> new tab ->
      // Return to game) to resume BEFORE adjudication.
      OTB_DISCONNECT_GRACE_MS: '1500',
      OTB_ABANDON_MS: '6000',
    },
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
