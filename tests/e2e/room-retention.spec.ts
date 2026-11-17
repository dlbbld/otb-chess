import { test, expect, BrowserContext } from '@playwright/test';
import { spawn, ChildProcess } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';

async function freePort(): Promise<number> {
  const server = createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = (server.address() as { port: number }).port;
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
}

test('completed disconnected rooms release capacity while connected players can rematch', async ({ browser, request }) => {
  test.setTimeout(60000);
  const httpPort = await freePort();
  let wsPort = await freePort();
  while (wsPort === httpPort) wsPort = await freePort();
  const base = `http://127.0.0.1:${httpPort}`;
  const server: ChildProcess = spawn('java', ['-jar', 'target/otb-chess.jar'], {
    windowsHide: true,
    env: { ...process.env, OTB_HTTP_PORT: String(httpPort), OTB_WS_PORT: String(wsPort),
      OTB_MAX_GAMES: '1', OTB_ENDED_ROOM_TTL_MS: '100', OTB_USAGE_LOG: 'target/retention-usage.log' },
    stdio: 'ignore',
  });
  const contexts: BrowserContext[] = [];
  try {
    await expect.poll(async () => {
      try { return (await request.get(`${base}/api/health`)).status(); } catch { return 0; }
    }).toBe(200);
    for (let i = 0; i < 3; i++) {
      const context = await browser.newContext();
      // The application uses /ws for arbitrary HTTP ports. This isolated server has
      // no reverse proxy, so route its sockets directly to its dedicated WS port.
      await context.addInitScript(port => {
        const NativeWebSocket = window.WebSocket;
        window.WebSocket = class extends NativeWebSocket {
          constructor(url: string | URL, protocols?: string | string[]) {
            super(`ws://127.0.0.1:${port}`, protocols);
          }
        };
      }, wsPort);
      contexts.push(context);
    }
    const white = await contexts[0].newPage();
    const black = await contexts[1].newPage();
    const next = await contexts[2].newPage();
    const createUrl = `${base}/game.html?creator=true&side=white&time=60000&inc=0`;
    await white.goto(createUrl);
    await expect(white.locator('.game-code-value')).toBeVisible();
    const code = await white.locator('.game-code-value').innerText();
    await black.goto(`${base}/game.html?gameId=${code}`);
    await expect(black.locator('#arbiterMessage')).toContainText('clock has been started');
    await white.locator('#resignBtn').click();
    for (const page of [white, black]) await expect(page.locator('#gameResultScore')).toHaveText('0-1');
    await next.waitForTimeout(200);
    await next.goto(createUrl);
    await expect(next.locator('#arbiterMessage')).toContainText('at capacity');
    await white.locator('#rematchBtn').click();
    await black.locator('#rematchBtn').click();
    for (const page of [white, black]) await expect(page.locator('#arbiterMessage')).toContainText('Rematch started');
    await white.locator('#resignBtn').click();
    for (const page of [white, black]) await expect(page.locator('#gameResultScore')).toHaveText('1-0');
    await contexts[0].close();
    await contexts[1].close();
    await next.waitForTimeout(200);
    await next.reload();
    await expect(next.locator('.game-code-value')).toBeVisible();
    await expect(next.locator('.game-code-value')).not.toHaveText(code);
    await expect(next.getByRole('button', { name: 'Abort game', exact: true })).toBeVisible();
  } finally {
    for (const context of contexts) await context.close();
    if (server.exitCode === null) {
      const exited = once(server, 'exit');
      server.kill();
      await exited;
    }
  }
});
