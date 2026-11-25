import { test, expect } from '@playwright/test';
import { startIsolatedServer } from './helpers/isolated-server';
import { createGame, joinGame, expectGameStarted } from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

// Found at the deployment of 0.5.0 (2026-11-25): the app was restarted while browsers were
// connected, and for about 50 seconds the new process could not bind its WebSocket port
// ("Address already in use" - the connections of the killed process were still expiring). Each
// attempt gave up after 10 seconds and the site answered 502 meanwhile. The server now takes its
// port over at once.

test('a server killed with players connected is back at once, and the players are told that their game is gone', async ({
  browser,
  request,
}) => {
  test.setTimeout(90_000);
  let server = await startIsolatedServer(request, {});
  const ports = { httpPort: server.httpPort, wsPort: server.wsPort };
  const contexts = [await server.context(browser), await server.context(browser)];
  try {
    const white = await contexts[0].newPage();
    const black = await contexts[1].newPage();
    const gameId = await createGame(white);
    await joinGame(black, gameId);
    await expectGameStarted(white);
    await expectGameStarted(black);
    await dragPiece(white, 'e2', 'e4');
    await pressClock(white);
    await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');

    // The process is killed with both players connected, and started again on the same ports -
    // what `launchctl kickstart -k` does on the host. A start that cannot bind the port gives up
    // after 10 seconds: the new server has to answer well before that.
    await server.stop();
    server = await startIsolatedServer(request, {}, ports, 8000);
    const health = await request.get(`${server.baseURL}/api/health`);
    expect(await health.json()).toEqual({ status: 'ok', websocket: true });

    // Games live in memory only. The pages reconnect by themselves and say what happened.
    for (const page of [white, black]) {
      await expect(page.locator('#arbiterMessage')).toHaveText(
        'This game is no longer available — it may have ended or expired.', { timeout: 30_000 });
      await expect(page.locator('#arbiterButtons').getByText('Back to lobby')).toBeVisible();
    }

    // And the restarted server is usable at once: a new game is created and joined.
    await white.locator('#arbiterButtons').getByText('Back to lobby').click();
    await expect(white.locator('#gameInProgress')).toBeHidden();
    const second = await createGame(white);
    expect(second).not.toBe(gameId);
    await joinGame(black, second);
    await expectGameStarted(white);
    await expectGameStarted(black);
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
    await server.stop();
  }
});
