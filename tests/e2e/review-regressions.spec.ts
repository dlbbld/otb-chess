import { test, expect, Browser } from '@playwright/test';
import { startTwoPlayerGame, createGame, joinGame, expectGameStarted, expectGameResult, clickRestore, claimFiftyMoveOnBoard, CreateOptions, SCORE_DRAW, TwoPlayerGame } from './helpers/app';
import { bothPlayersMove, dragPiece, pressClock, expectPiece } from './helpers/board';

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
  await bothPlayersMove(game.white, game.black);
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

// Follow-up review of the fixes above.

interface FrameLog {
  /** When this page received gameStarted - the server starts the clock and its 1 s ticker right after. */
  startedAt: number;
  gameEnded: number;
}

/** Like startTwoPlayerGame (creator is White), but records the server frames each page receives from the start. */
async function startLoggedGame(browser: Browser, opts: CreateOptions = {}): Promise<{ logs: { white: FrameLog; black: FrameLog } }> {
  const contexts = [await browser.newContext(), await browser.newContext()];
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();
  game = { contexts, white, black, creator: white, joiner: black, gameId: '' };
  const logs = { white: { startedAt: 0, gameEnded: 0 }, black: { startedAt: 0, gameEnded: 0 } };
  for (const side of ['white', 'black'] as const) {
    game[side].on('websocket', socket => socket.on('framereceived', frame => {
      const type = JSON.parse(frame.payload.toString()).type;
      if (type === 'gameStarted') logs[side].startedAt = Date.now();
      if (type === 'gameEnded') logs[side].gameEnded++;
    }));
  }
  game.gameId = await createGame(white, opts);
  await joinGame(black, game.gameId);
  await expectGameStarted(white);
  await expectGameStarted(black);
  return { logs };
}

test('a clock press during the auto-resume delay leaves the player able to move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  // A first offence that costs nothing but needs a restore: White moves one of Black's pieces.
  await dragPiece(white, 'e7', 'e5');
  await expect(white.locator('#arbiterMessage')).toContainText('You moved an opponent');
  await clickRestore(white);
  // White presses the own clock at once, inside the pause before the clock restarts, without a move.
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('pressed without a move being made');
  await expect(white.locator('#chessClock')).not.toHaveClass(/paused/);
  // Stay past the end of the pause: nothing arriving late may lock the board or replace the message.
  await white.waitForTimeout(2000);
  await expect(white.locator('#arbiterMessage')).toContainText('pressed without a move being made');
  // White's clock is running, so White must be able to move.
  await dragPiece(white, 'e2', 'e4');
  for (const page of [white, black]) await expectPiece(page, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

// The expired side has 2.2 s; the server's ticker looks at the clocks 2 s and 3 s after the start.
// Acting at 2.45 s therefore meets a clock that has run out but has not been adjudicated yet.
const EXPIRED_BUT_UNNOTICED = { timeMs: 2200, actAtMs: 2450 };

test('a valid draw claim after expiry still loses on time', async ({ browser }) => {
  // Rook each: Black can mate, so White's flag fall is a loss. The halfmove clock makes the claim valid.
  const { logs } = await startLoggedGame(browser, {
    timeMs: EXPIRED_BUT_UNNOTICED.timeMs, fen: '4k3/4r3/8/8/8/4K3/4R3/8 w - - 100 80',
  });
  const { white, black } = game;
  await white.waitForTimeout(Math.max(0, logs.white.startedAt + EXPIRED_BUT_UNNOTICED.actAtMs - Date.now()));
  expect(logs.white.gameEnded, 'the claim must arrive before the periodic flag-fall check').toBe(0);
  await claimFiftyMoveOnBoard(white);
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toContainText('loses on time');
  }
});

test('resigning after the opponent\'s expiry keeps the win on time', async ({ browser }) => {
  const { logs } = await startLoggedGame(browser, { timeMs: EXPIRED_BUT_UNNOTICED.timeMs });
  const { white, black } = game;
  await black.waitForTimeout(Math.max(0, logs.black.startedAt + EXPIRED_BUT_UNNOTICED.actAtMs - Date.now()));
  expect(logs.black.gameEnded, 'the resignation must arrive before the periodic flag-fall check').toBe(0);
  await black.locator('#resignBtn').click();
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toHaveText('White loses on time. Black wins the game.');
  }
});

test('the result is announced once and late actions do not disturb a rematch offer', async ({ browser }) => {
  const { logs } = await startLoggedGame(browser);
  const { white, black } = game;
  await bothPlayersMove(white, black);
  await white.locator('#offerDrawBtn').click();
  await black.locator('#acceptDrawBtn').click();
  for (const page of [white, black]) await expectGameResult(page, SCORE_DRAW);
  await black.locator('#rematchBtn').click();
  await expect(white.locator('#arbiterMessage')).toHaveText('Your opponent offers a rematch.');
  await expect(black.locator('#arbiterMessage')).toHaveText('Rematch offer sent.');
  // Actions that can still be in flight when the ending reaches a client. The PGN request is
  // answered after them, so its dialog marks the point where the server has handled them all.
  for (const page of [white, black]) {
    await page.evaluate(() => {
      const ws = (window as any).game.ws;
      ws.send({ type: 'resign' });
      ws.send({ type: 'acceptDraw' });
      ws.send({ type: 'offerDraw' });
      ws.send({ type: 'opponentClockPressed' });
      ws.send({ type: 'requestPgn' });
    });
    await expect(page.locator('#pgnDialog')).toBeVisible();
  }
  await expect(white.locator('#arbiterMessage')).toHaveText('Your opponent offers a rematch.');
  await expect(black.locator('#arbiterMessage')).toHaveText('Rematch offer sent.');
  for (const page of [white, black]) await expectGameResult(page, SCORE_DRAW);
  expect(logs.white.gameEnded).toBe(1);
  expect(logs.black.gameEnded).toBe(1);
});
