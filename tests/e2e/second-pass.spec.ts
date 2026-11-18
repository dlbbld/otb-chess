import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, createGame, joinGame, clickRestore, TwoPlayerGame } from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

// Hold only the target message, letting the real pages create their sockets and UI normally.
// Release both requests at the same wall-clock instant to exercise contention on the live server.
function holdMessage(type: string) {
  const originalSend = WebSocket.prototype.send;
  const state = window as any;
  state.pendingReviewMessage = null;
  state.reviewMessageReleased = false;
  WebSocket.prototype.send = function (data: any) {
    if (typeof data === 'string' && JSON.parse(data).type === type && !state.reviewMessageReleased) {
      state.pendingReviewMessage = () => originalSend.call(this, data);
      return;
    }
    originalSend.call(this, data);
  };
}

async function releaseTogether(pages: Page[]) {
  for (const page of pages) await expect.poll(() => page.evaluate(() => !!(window as any).pendingReviewMessage)).toBe(true);
  const at = Date.now() + 200;
  await Promise.all(pages.map(page => page.evaluate(at => {
    setTimeout(() => {
      (window as any).reviewMessageReleased = true;
      (window as any).pendingReviewMessage();
    }, Math.max(0, at - Date.now()));
  }, at)));
}

test('simultaneous Ready confirmations leave both boards playable and ignore a late confirmation', async ({browser}) => {
  game = await startTwoPlayerGame(browser, {autoResume: false});
  await dragPiece(game.white, 'e7', 'e6');
  await clickRestore(game.white);
  for (const page of [game.white, game.black]) {
    await expect(page.getByRole('button', {name: 'Ready to continue'})).toBeVisible();
    await page.evaluate(holdMessage, 'readyToContinue');
  }
  await Promise.all([game.white, game.black].map(page => page.getByRole('button', {name:'Ready to continue'}).click()));
  await releaseTogether([game.white, game.black]);
  for (const page of [game.white, game.black]) {
    await expect(page.locator('#arbiterMessage')).toContainText('Game continues');
    await expect(page.getByRole('button', {name:'Ready to continue'})).toHaveCount(0);
  }
  // A queued duplicate must not disable the now-active White board.
  await game.white.evaluate(() => (window as any).game.ws.send({type:'readyToContinue'}));
  await dragPiece(game.white, 'e2', 'e4'); await pressClock(game.white);
  await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
  await dragPiece(game.black, 'e7', 'e5'); await pressClock(game.black);
  await expect(game.white.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(game.black.locator('#arbiterMessage')).toContainText('Move accepted');
});

test('simultaneous joiners get exactly one seat and the winning token still reconnects', async ({browser}) => {
  const contexts = await Promise.all([browser.newContext(), browser.newContext(), browser.newContext()]);
  for (const context of contexts.slice(1)) await context.addInitScript(holdMessage, 'joinGame');
  const [white, a, b] = await Promise.all(contexts.map(context => context.newPage()));
  game = {contexts, white, black:a, creator:white, joiner:a, gameId:''};
  const starts = [0, 0, 0];
  [white, a, b].forEach((page, i) => page.on('websocket', socket => socket.on('framereceived', frame => {
    if (JSON.parse(frame.payload.toString()).type === 'gameStarted') starts[i]++;
  })));
  game.gameId = await createGame(white);
  await Promise.all([joinGame(a, game.gameId), joinGame(b, game.gameId)]);
  await releaseTogether([a, b]);
  await expect.poll(async () => (await Promise.all([a,b].map(p => p.evaluate(() => !!(window as any).game.gameActive)))).filter(Boolean).length).toBe(1);
  const winner = await a.evaluate(() => (window as any).game.gameActive) ? a : b;
  const loser = winner === a ? b : a;
  await expect(loser.locator('#arbiterMessage')).toContainText('already has two players');
  await expect(winner.locator('#arbiterMessage')).toContainText('clock has been started');
  expect(starts[0]).toBe(1);
  expect(starts[1] + starts[2]).toBe(1);
  game.black = winner; game.joiner = winner;
  await winner.reload();
  await expect(winner.locator('#arbiterMessage')).toHaveText('Reconnected.');
  await dragPiece(white, 'e2', 'e4'); await pressClock(white);
  await expect(winner.locator('#arbiterMessage')).toContainText('Your turn');
  await dragPiece(winner, 'e7', 'e5'); await pressClock(winner);
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(winner.locator('#arbiterMessage')).toContainText('Move accepted');
});

test('draw offers while restoring and waiting for Ready cannot count an illegal move twice', async ({browser}) => {
  game = await startTwoPlayerGame(browser, {autoResume:false});
  await dragPiece(game.white, 'e2', 'e5'); await pressClock(game.white);
  await expect(game.white.getByRole('button', {name:'Revert',exact:true})).toBeVisible();
  await expect.poll(() => game!.white.evaluate(() => (window as any).game.clockRunning)).toBe('none');
  const blackTime = await game.white.evaluate(() => (window as any).game._lastClockData.blackTimeMs);
  for (let i=0; i<3; i++) {
    await game.white.locator('#offerDrawBtn').click();
    await expect(game.white.locator('#arbiterMessage')).toContainText('restore the position before offering a draw');
  }
  await clickRestore(game.white);
  for (const page of [game.white, game.black]) {
    await page.locator('#offerDrawBtn').click();
    await expect(page.locator('#arbiterMessage')).toContainText('wait until both players are ready');
    await expect(page.getByRole('button', {name:'Ready to continue'})).toBeVisible();
    await expect(page.locator('#gameResultPanel')).toBeHidden();
    await expect(page.locator('#drawOfferPanel')).toBeHidden();
  }
  expect(await game.white.evaluate(() => (window as any).game._lastClockData.blackTimeMs)).toBe(blackTime);
  await game.white.getByRole('button', {name:'Ready to continue'}).click();
  await game.black.getByRole('button', {name:'Ready to continue'}).click();
  await expect(game.white.locator('#arbiterMessage')).toContainText('Game continues');
  await dragPiece(game.white, 'e2', 'e4'); await pressClock(game.white);
  await expect(game.white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('an offer during automatic restoration delay leaves recovery intact', async ({browser}) => {
  game = await startTwoPlayerGame(browser);
  await dragPiece(game.white, 'e2', 'e5'); await pressClock(game.white);
  await clickRestore(game.white);
  await game.white.locator('#offerDrawBtn').click();
  await expect(game.white.locator('#arbiterMessage')).toContainText('wait until the clock restarts');
  for (const page of [game.white, game.black]) {
    await expect(page.locator('#arbiterMessage')).toContainText('Game continues');
    await expect(page.locator('#gameResultPanel')).toBeHidden();
  }
  await dragPiece(game.white, 'e2', 'e4'); await pressClock(game.white);
  await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
});

for (const [reason, fen] of [
  ['checkmate', '7k/6Q1/5K2/8/8/8/8/8 b - - 0 1'],
  ['stalemate', '7k/5Q2/6K1/8/8/8/8/8 b - - 0 1'],
  ['insufficient material', '4k3/8/8/8/8/8/8/4K3 w - - 0 1'],
  ['75-move rule', '4k3/8/8/8/3R4/8/8/4K3 w - - 150 80'],
]) {
  test(`a starting position already ended by ${reason} is rejected in the lobby and on a direct URL`, async ({page}) => {
    await page.goto('/');
    await page.locator('#startingFen').fill(fen);
    await page.locator('#createGameBtn').click();
    await expect(page.locator('#fenError')).toContainText(reason);
    await expect(page).toHaveURL(/\/$/);
    await page.goto(`/game.html?creator=true&side=white&time=60000&inc=0&fen=${encodeURIComponent(fen)}`);
    await expect(page.locator('#arbiterMessage')).toContainText(reason);
    await expect(page.locator('.game-code-value')).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).game.gameActive)).toBe(false);
    expect(await page.evaluate(() => (window as any).game.clockRunning)).toBe('none');
  });
}
