import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, createGame, joinGame, clickRestore, TwoPlayerGame } from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

// Regressions for the gaps left by the second review pass.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const blackTimeMs = (page: Page) => page.evaluate(() => (window as any).game._lastClockData.blackTimeMs);
const clockRunning = (page: Page) => page.evaluate(() => (window as any).game.clockRunning);
const revertButton = (page: Page) => page.getByRole('button', { name: 'Revert', exact: true });
const readyButton = (page: Page) => page.getByRole('button', { name: 'Ready to continue' });

for (const autoResume of [false, true]) {
  test(`a Revert message from the opponent cannot interrupt a move in progress (autoResume ${autoResume})`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { autoResume });
    await dragPiece(game.white, 'e2', 'e4');

    // Not reachable through the UI: Black has no Revert button. A modified client can still send it.
    // The server answers one socket's messages in order, so the pong proves the Revert was handled
    // before White presses the clock.
    await game.black.evaluate(() => new Promise<void>((resolve) => {
      const socket: WebSocket = (window as any).game.ws.ws;
      socket.addEventListener('message', function onPong(event) {
        if (JSON.parse(event.data).type === 'pong') {
          socket.removeEventListener('message', onPong);
          resolve();
        }
      });
      socket.send(JSON.stringify({ type: 'restorePosition' }));
      socket.send(JSON.stringify({ type: 'keepalive' }));
    }));
    await pressClock(game.white);

    await expect(game.white.locator('#arbiterMessage')).toContainText('Move accepted');
    await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
    for (const page of [game.white, game.black]) {
      await expect(readyButton(page)).toHaveCount(0);
      await expect(revertButton(page)).toHaveCount(0);
    }
    await dragPiece(game.black, 'e7', 'e5');
    await pressClock(game.black);
    await expect(game.white.locator('#arbiterMessage')).toContainText('Your turn');
  });
}

test('draw claims while Revert and Ready are pending are not considered', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { autoResume: false });
  // White's first action of the move: dragging a Black pawn. The arbiter stops the clocks.
  await dragPiece(game.white, 'e7', 'e6');
  await expect(revertButton(game.white)).toBeVisible();
  await expect.poll(() => clockRunning(game!.white)).toBe('none');
  const blackTime = await blackTimeMs(game.white);

  for (const button of ['#claimThreefoldOnBoardBtn', '#claimFiftyMoveOnBoardBtn', '#claimThreefoldWithMoveBtn']) {
    await game.white.locator(button).click();
    await expect(game.white.locator('#arbiterMessage')).toContainText('restore the position before claiming a draw');
  }
  await expect(revertButton(game.white)).toBeVisible();
  await expect(game.black.locator('#drawOfferPanel')).toBeHidden();
  await expect(game.white.locator('#sanInputPanel')).toBeHidden();

  await clickRestore(game.white);
  for (const page of [game.white, game.black]) {
    await expect(readyButton(page)).toBeVisible();
    await page.locator('#claimThreefoldOnBoardBtn').click();
    await expect(page.locator('#arbiterMessage')).toContainText('wait until both players are ready');
    await expect(readyButton(page)).toBeVisible();
    await expect(page.locator('#drawOfferPanel')).toBeHidden();
    await expect(page.locator('#gameResultPanel')).toBeHidden();
  }
  expect(await clockRunning(game.white)).toBe('none');
  expect(await blackTimeMs(game.white)).toBe(blackTime);

  await readyButton(game.white).click();
  await readyButton(game.black).click();
  await expect(game.white.locator('#arbiterMessage')).toContainText('Game continues');
  expect(await blackTimeMs(game.white)).toBe(blackTime);

  await dragPiece(game.white, 'e2', 'e4');
  await pressClock(game.white);
  await expect(game.white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
});

// Hold only the target message so both players' requests can be released at the same instant.
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

test('an abort and a join at the same instant agree on one outcome', async ({ browser }) => {
  const contexts = [await browser.newContext(), await browser.newContext()];
  await contexts[1].addInitScript(holdMessage, 'joinGame');
  const [creator, joiner] = await Promise.all(contexts.map((context) => context.newPage()));
  game = { contexts, white: creator, black: joiner, creator, joiner, gameId: '' };
  game.gameId = await createGame(creator);
  await creator.evaluate(holdMessage, 'abort');
  await joinGame(joiner, game.gameId);
  await creator.locator('#arbiterButtons #abortBtn').click();
  for (const page of [creator, joiner]) {
    await expect.poll(() => page.evaluate(() => !!(window as any).pendingReviewMessage)).toBe(true);
  }
  const at = Date.now() + 200;
  await Promise.all([creator, joiner].map((page) => page.evaluate((at) => {
    setTimeout(() => {
      (window as any).reviewMessageReleased = true;
      (window as any).pendingReviewMessage();
    }, Math.max(0, at - Date.now()));
  }, at)));

  // Either the abort won (creator back in the lobby, joiner told the code is gone) or the join
  // won (the game runs on both boards). Never an aborted creator with a joiner in a running game.
  const joinerOutcome = joiner.locator('#arbiterMessage');
  await expect(joinerOutcome).toContainText(/wasn't found|clock has been started/, { timeout: 15_000 });
  if ((await joinerOutcome.innerText()).includes("wasn't found")) {
    await expect(creator).toHaveURL(/\/$/);
    await expect(creator.locator('#createGameBtn')).toBeVisible();
  } else {
    await expect(creator).toHaveURL(/game\.html/);
    // The late abort is refused; its refusal may be the last thing the arbiter told the creator.
    await expect.poll(() => creator.evaluate(() => (window as any).game.gameActive)).toBe(true);
    await expect(creator.locator('#arbiterMessage')).toContainText(/clock has been started|cannot be aborted/);
    await dragPiece(creator, 'e2', 'e4');
    await pressClock(creator);
    await expect(joiner.locator('#arbiterMessage')).toContainText('Your turn');
  }
});
