import { test, expect } from '@playwright/test';
import { createGame, startTwoPlayerGame, TwoPlayerGame } from './helpers/app';

// A challenge can be aborted while the creator waits alone, like cancelling a Lichess challenge.
// Once the opponent joins, Abort is replaced by Resign.

let game: TwoPlayerGame | undefined;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
  game = undefined;
});

test('the creator sees an explicit Abort game action beside the join code', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  const gameId = await createGame(page); // creator alone, no opponent yet

  let waitingActions = page.locator('#arbiterButtons');
  await expect(waitingActions.locator('.game-code-value')).toBeVisible();
  await expect(waitingActions.getByRole('button', { name: 'Copy code' })).toBeVisible();
  await expect(waitingActions.locator('#abortBtn')).toHaveText('Abort game');
  await expect(waitingActions.locator('#abortBtn')).toBeVisible();
  await expect(page.locator('.board-controls #abortBtn')).toHaveCount(0);
  await expect(page.locator('#resignBtn')).toBeHidden();

  // A waiting-page refresh resumes the same room and must restore the explicit action too.
  await page.reload();
  waitingActions = page.locator('#arbiterButtons');
  await expect(waitingActions.locator('.game-code-value')).toHaveText(gameId);
  await expect(waitingActions.locator('#abortBtn')).toHaveText('Abort game');
  await expect(waitingActions.locator('#abortBtn')).toBeVisible();

  await context.close();
});

test('a black creator also gets Abort while waiting (symmetry)', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await createGame(page, { side: 'black' });

  await expect(page.locator('#abortBtn')).toBeVisible();
  await expect(page.locator('#resignBtn')).toBeHidden();

  await context.close();
});

test('aborting returns the creator to the lobby to start a new challenge', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();

  // Literal reported journey: start in the lobby, keep the default White side, and create.
  await page.goto('/');
  await expect(page.locator('input[name="side"][value="white"]')).toBeChecked();
  await page.locator('#createGameBtn').click();
  await expect(page.locator('.game-code-value')).toBeVisible({ timeout: 15_000 });

  await page.locator('#arbiterButtons #abortBtn').click();

  // The server confirms the abort, the client clears the saved seat, and the creator returns to
  // the lobby where a fresh challenge can be created.
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('#createGameBtn')).toBeVisible();
  await expect(page.locator('#gameInProgress')).toBeHidden();

  await context.close();
});

test('once the opponent joins, Abort is replaced by Resign', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);

  await expect(game.creator.locator('#abortBtn')).toBeHidden();
  await expect(game.creator.locator('#resignBtn')).toBeVisible();
  await expect(game.joiner.locator('#abortBtn')).toBeHidden();
  await expect(game.joiner.locator('#resignBtn')).toBeVisible();
});
