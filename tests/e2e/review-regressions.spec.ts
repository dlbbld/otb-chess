import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, createGame, joinGame, expectGameStarted, expectGameResult, clickRestore, TwoPlayerGame } from './helpers/app';
import { dragPiece, pressClock, expectPiece } from './helpers/board';

let game: TwoPlayerGame;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
});

for (const side of ['white', 'black'] as const) {
  test(`refreshing ${side} before Revert preserves restoration and the physical board`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { autoResume: false });
    await dragPiece(game.white, 'e2', 'e5');
    await pressClock(game.white);
    await expect(game.white.getByRole('button', { name: 'Revert', exact: true })).toBeVisible();
    await game[side].reload();
    for (const page of [game.white, game.black]) await expectPiece(page, 'e5', 'WHITE_PAWN');
    await expect(game.white.locator('#arbiterMessage')).toContainText('restore');
    await clickRestore(game.white);
    for (const page of [game.white, game.black]) {
      await expectPiece(page, 'e2', 'WHITE_PAWN');
      await page.getByRole('button', { name: 'Ready to continue' }).click();
    }
    await dragPiece(game.white, 'e2', 'e4');
    await pressClock(game.white);
    await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
  });

  test(`refreshing ${side} after White is ready preserves the acknowledgement`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { autoResume: false });
    await dragPiece(game.white, 'e2', 'e5');
    await pressClock(game.white);
    await clickRestore(game.white);
    await game.white.getByRole('button', { name: 'Ready to continue' }).click();
    await expect(game.white.locator('#arbiterMessage')).toHaveText('Waiting for your opponent to be ready...');
    await game[side].reload();
    await expect(game.white.locator('#arbiterMessage')).toHaveText('Waiting for your opponent to be ready...');
    await expect(game.white.getByRole('button', { name: 'Ready to continue' })).toHaveCount(0);
    await game.black.getByRole('button', { name: 'Ready to continue' }).click();
    for (const page of [game.white, game.black]) {
      await expect(page.locator('#arbiterMessage')).toContainText('Game continues');
    }
    await dragPiece(game.white, 'e2', 'e4');
    await pressClock(game.white);
    await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
  });

  test(`refreshing ${side} during manual readiness preserves both controls`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { autoResume: false });
    await dragPiece(game.white, 'e2', 'e5');
    await pressClock(game.white);
    await clickRestore(game.white);
    for (const page of [game.white, game.black]) {
      await expect(page.getByRole('button', { name: 'Ready to continue' })).toBeVisible();
    }
    await game[side].reload();
    for (const page of [game.white, game.black]) {
      await expect(page.getByRole('button', { name: 'Ready to continue' })).toBeVisible();
      await expect(page.locator('#arbiterMessage')).toContainText('ready to continue');
      await expectPiece(page, 'e2', 'WHITE_PAWN');
      await page.getByRole('button', { name: 'Ready to continue' }).click();
    }
    for (const page of [game.white, game.black]) {
      await expect(page.locator('#arbiterMessage')).toContainText('Game continues');
    }
    await dragPiece(game.white, 'e2', 'e4');
    await pressClock(game.white);
    await expect(game.white.locator('#arbiterMessage')).toContainText('Move accepted');
    await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
  });
}

test('refresh preserves an unpressed legal move on both boards', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  await dragPiece(game.white, 'e2', 'e4');
  await expectPiece(game.black, 'e4', 'WHITE_PAWN');
  await game.white.reload();
  await expect(game.white.locator('#arbiterMessage')).toHaveText('Reconnected.');
  await expectPiece(game.white, 'e4', 'WHITE_PAWN');
  await pressClock(game.white);
  await expect(game.white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('checkmate during a wrong-clock pause ends on both boards', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: '7k/8/5KQ1/8/8/8/8/8 w - - 0 1' });
  await game.black.locator('#topClockBtn').click();
  await expect(game.white.locator('#opponentInfoPanel')).toContainText('paused');
  await dragPiece(game.white, 'g6', 'g7');
  for (const page of [game.white, game.black]) {
    await expectGameResult(page, '1-0');
    await expect(page.locator('#gameResultReason')).toContainText('checkmate');
    await expect(page.locator('#resignBtn')).toBeDisabled();
    await expect(page.locator('#devConsole')).toBeHidden();
  }
  await expect(game.white.locator('#arbiterMessage')).toContainText('delivered checkmate');
  await expect(game.black.locator('#arbiterMessage')).toContainText('been checkmated');
});

test('delayed terminal messages cannot rewrite a resignation on either board', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  await game.white.locator('#offerDrawBtn').click();
  await expect(game.black.locator('#acceptDrawBtn')).toBeVisible();
  await game.white.locator('#resignBtn').click();
  await expectGameResult(game.black, '0-1');
  // Replay actions that can already be in flight when the ending reaches the client.
  await game.black.evaluate(() => {
    const ws = (window as any).game.ws;
    ws.send({ type: 'acceptDraw' });
    ws.send({ type: 'resign' });
    ws.send({ type: 'requestPgn' });
  });
  await expect(game.black.locator('#pgnDialog')).toBeVisible();
  for (const page of [game.white, game.black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toHaveText('White resigns. Black wins the game.');
    await expect(page.locator('#resignBtn')).toBeDisabled();
    await expect(page.locator('#drawOfferPanel')).toBeHidden();
  }
});

test('clock press after expiry cannot earn increment before the next ticker', async ({ browser }) => {
  const contexts = [await browser.newContext(), await browser.newContext()];
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();
  game = { contexts, white, black, creator: white, joiner: black, gameId: '' };
  let startedAt = 0;
  let ended = false;
  white.on('websocket', socket => socket.on('framereceived', frame => {
    const type = JSON.parse(frame.payload.toString()).type;
    if (type === 'gameStarted') startedAt = Date.now();
    if (type === 'gameEnded') ended = true;
  }));
  game.gameId = await createGame(white, { timeMs: 2500, incMs: 2000 });
  await joinGame(black, game.gameId);
  await expectGameStarted(white);
  await expectGameStarted(black);
  await dragPiece(white, 'e2', 'e4');
  await white.waitForTimeout(Math.max(0, startedAt + 2700 - Date.now()));
  expect(ended, 'press must arrive before the periodic flag-fall check').toBe(false);
  await pressClock(white);
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toContainText('loses on time');
    await expect(page.locator('#resignBtn')).toBeDisabled();
  }
});
