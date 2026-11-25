import { APIRequestContext, Browser, BrowserContext, Page, expect, test } from '@playwright/test';
import { colorOf, createGame, expectGameResult, expectGameStarted, joinGame, resign } from './helpers/app';
import { dragPiece, expectEmpty, expectPiece, pressClock } from './helpers/board';
import { startIsolatedServer } from './helpers/isolated-server';

let contexts: BrowserContext[] = [];
let stopServer: (() => Promise<void>) | undefined;

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
  await stopServer?.();
  stopServer = undefined;
});

async function startGame(browser: Browser, request: APIRequestContext) {
  const server = await startIsolatedServer(request, {
    OTB_DISCONNECT_GRACE_MS: '1500', OTB_ABANDON_MS: '15000',
  });
  stopServer = server.stop;
  contexts = [await server.context(browser), await server.context(browser)];
  for (const context of contexts) {
    // Simulate losing one inbound frame when the connection drops. This listener is installed
    // before the application's onmessage handler, so neither its token nor its UI sees the frame.
    await context.addInitScript(() => {
      const NativeWebSocket = window.WebSocket;
      const state = window as any;
      state.dropNextRematch = false;
      state.droppedRematches = 0;
      window.WebSocket = class extends NativeWebSocket {
        constructor(url: string | URL, protocols?: string | string[]) {
          super(url, protocols);
          this.addEventListener('message', event => {
            if (state.dropNextRematch && JSON.parse(event.data).type === 'rematchStarted') {
              state.dropNextRematch = false;
              state.droppedRematches++;
              event.stopImmediatePropagation();
              this.close();
            }
          });
        }
      };
    });
  }
  const [white, black] = await Promise.all(contexts.map(context => context.newPage()));
  const endings = new Map<Page, any[]>([[white, []], [black, []]]);
  for (const page of [white, black]) {
    page.on('websocket', socket => socket.on('framereceived', frame => {
      const message = JSON.parse(frame.payload.toString());
      if (message.type === 'gameEnded') endings.get(page)!.push(message);
    }));
  }
  const gameId = await createGame(white, { timeMs: 60000 });
  await joinGame(black, gameId);
  for (const page of [white, black]) await expectGameStarted(page);
  return { white, black, gameId, endings };
}

async function expectActiveSeat(page: Page, side: 'white' | 'black', gameId: string) {
  expect(await colorOf(page)).toBe(side);
  await expect(page.locator('#gameResultPanel')).toBeHidden();
  await expect(page.locator('#resignBtn')).toBeEnabled();
  await expect.poll(() => page.evaluate(() => (window as any).game.gameActive)).toBe(true);
  // The stored seat and the page's own seat both carry the new colour.
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('otbSeats') || '[]')[0]);
  expect(saved).toMatchObject({ gameId, side });
  expect(saved.token).toBeTruthy();
  expect(await page.evaluate(() => history.state.otbSeat)).toMatchObject({ gameId, side, token: saved.token });
}

async function playMove(mover: Page, opponent: Page, from: string, to: string, piece: string) {
  await dragPiece(mover, from, to);
  await pressClock(mover);
  await expect(mover.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(opponent.locator('#arbiterMessage')).toContainText('Your turn');
  for (const page of [mover, opponent]) {
    await expectPiece(page, to, piece);
    await expectEmpty(page, from);
    await expect(page.locator('#gameResultPanel')).toBeHidden();
  }
}

for (const lostSide of ['white', 'black'] as const) {
  test(`losing rematchStarted preserves the former ${lostSide} player's seat through reconnect and refresh`, async ({ browser, request }) => {
    const game = await startGame(browser, request);
    const { white, black, gameId } = game;
    await playMove(white, black, 'e2', 'e4', 'WHITE_PAWN');
    await resign(black);
    for (const page of [white, black]) await expectGameResult(page, '1-0');

    const disconnected = game[lostSide];
    const other = disconnected === white ? black : white;
    await disconnected.evaluate(() => { (window as any).dropNextRematch = true; });
    await white.locator('#rematchBtn').click();
    await expect(black.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
    await black.locator('#rematchBtn').click();
    await expect(other.locator('#gameResultPanel')).toBeHidden();
    await expect.poll(() => disconnected.evaluate(() => (window as any).droppedRematches)).toBe(1);
    await expect(disconnected.locator('#arbiterMessage')).toHaveText('Reconnected.', { timeout: 10000 });

    // A player who missed the whole start must recover the swapped colour, clear the old result,
    // restore the initial board and persist the recovered identity for a later full page load.
    await expectActiveSeat(black, 'white', gameId);
    await expectActiveSeat(white, 'black', gameId);
    for (const page of [white, black]) {
      await expectPiece(page, 'e2', 'WHITE_PAWN');
      await expectPiece(page, 'e7', 'BLACK_PAWN');
      await expectEmpty(page, 'e4');
    }
    await playMove(black, white, 'e2', 'e4', 'WHITE_PAWN');
    await playMove(white, black, 'e7', 'e5', 'BLACK_PAWN');

    await disconnected.reload();
    await expect(disconnected.locator('#arbiterMessage')).toHaveText('Reconnected.');
    await expectActiveSeat(disconnected, lostSide === 'white' ? 'black' : 'white', gameId);
    for (const page of [white, black]) {
      await expectPiece(page, 'e4', 'WHITE_PAWN');
      await expectPiece(page, 'e5', 'BLACK_PAWN');
    }
    await playMove(black, white, 'g1', 'f3', 'WHITE_KNIGHT');
    await playMove(white, black, 'b8', 'c6', 'BLACK_KNIGHT');
    for (const page of [white, black]) expect(game.endings.get(page)).toHaveLength(1);
  });
}

async function clickTogether(pages: Page[], selector: string, type: string) {
  for (const page of pages) {
    await page.evaluate(type => {
      const state = window as any;
      const socket = state.game.ws.ws;
      const send = socket.send.bind(socket);
      state.releaseConcurrentAction = null;
      socket.send = (data: string) => {
        if (JSON.parse(data).type === type) {
          state.releaseConcurrentAction = () => { socket.send = send; send(data); };
        } else send(data);
      };
    }, type);
  }
  await Promise.all(pages.map(page => page.locator(selector).click()));
  for (const page of pages) {
    await expect.poll(() => page.evaluate(() => !!(window as any).releaseConcurrentAction)).toBe(true);
  }
  const at = Date.now() + 150;
  await Promise.all(pages.map(page => page.evaluate(at => {
    setTimeout(() => (window as any).releaseConcurrentAction(), Math.max(0, at - Date.now()));
  }, at)));
}

test('a resume sent before the rematch still returns its player to the swapped seat', async ({ browser, request }) => {
  const { white, black, gameId } = await startGame(browser, request);
  const tokenBeforeRematch = await white.evaluate(() => (window as any).game.ws.sessionToken);
  const returning = await white.context().newPage();
  await returning.addInitScript(() => {
    const originalSend = WebSocket.prototype.send;
    const state = window as any;
    state.pendingRematchResume = null;
    WebSocket.prototype.send = function (data: any) {
      if (typeof data === 'string' && JSON.parse(data).type === 'resume') {
        state.pendingRematchResume = { request: JSON.parse(data), send: () => originalSend.call(this, data) };
      } else originalSend.call(this, data);
    };
  });
  await returning.goto('/game.html');
  await expect.poll(() => returning.evaluate(() => (window as any).pendingRematchResume?.request.token))
    .toBe(tokenBeforeRematch);

  // The returning tab has already prepared its old seat's resume request. Hold it at the network
  // boundary while the original two pages end the game and swap seats in a rematch.
  await resign(black);
  for (const page of [white, black]) await expectGameResult(page, '1-0');
  await white.locator('#rematchBtn').click();
  await expect(black.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
  await black.locator('#rematchBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('Rematch started');
  await returning.evaluate(() => (window as any).pendingRematchResume.send());
  await expect(returning.locator('#arbiterMessage')).toHaveText('Reconnected.');

  // The request belonged to the former White player. It must now reattach Black, preserving the
  // other player's White seat; Java additionally forces the overlap inside token validation.
  await expectActiveSeat(returning, 'black', gameId);
  await expectActiveSeat(black, 'white', gameId);
  await white.close();
  await playMove(black, returning, 'e2', 'e4', 'WHITE_PAWN');
  await playMove(returning, black, 'e7', 'e5', 'BLACK_PAWN');
});

test('competing endings and rematch offers keep one result and playable swapped seats after both reconnect', async ({ browser, request }) => {
  const game = await startGame(browser, request);
  const pages = [game.white, game.black];
  let white = game.white;
  let black = game.black;
  // Java tests force the old ticker/result and resume/seat-swap interleavings. These real browser
  // journeys cover their surrounding protocol, presentation, clocks and access across rematches.
  for (let round = 0; round < 3; round++) {
    await clickTogether(pages, '#resignBtn', 'resign');
    for (const page of pages) await expect(page.locator('#gameResultPanel')).toBeVisible();
    const result = game.endings.get(pages[0])![round];
    expect(result.resultType).toBe('RESIGNATION');
    for (const page of pages) {
      await expectGameResult(page, result.winner === 'white' ? '1-0' : '0-1');
      await expect(page.locator('#gameResultReason')).toHaveText(result.description);
      expect(game.endings.get(page)).toHaveLength(round + 1);
      expect(game.endings.get(page)![round]).toEqual(result);
    }

    await clickTogether(pages, '#rematchBtn', 'rematchOffer');
    for (const page of pages) await expect(page.locator('#arbiterMessage')).toContainText('Rematch started');
    [white, black] = [black, white];
    await Promise.all(pages.map(page => page.reload()));
    for (const page of pages) await expect(page.locator('#arbiterMessage')).toContainText(/reconnected/i);
    await expectActiveSeat(white, 'white', game.gameId);
    await expectActiveSeat(black, 'black', game.gameId);
    await playMove(white, black, 'e2', 'e4', 'WHITE_PAWN');
    await playMove(black, white, 'e7', 'e5', 'BLACK_PAWN');

    // A stale ending must neither replace the fresh board's result nor cancel its clock ticker.
    const beforeTick = await white.evaluate(() => (window as any).game._lastClockData.whiteTimeMs);
    await expect.poll(() => white.evaluate(() => (window as any).game._lastClockData.whiteTimeMs))
      .toBeLessThan(beforeTick - 100);
    for (const page of pages) {
      await expect(page.locator('#gameResultPanel')).toBeHidden();
      expect(game.endings.get(page)).toHaveLength(round + 1);
    }
  }
});
