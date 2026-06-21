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

test('the creator sees Abort (not Resign) while waiting for an opponent', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await createGame(page); // creator alone, no opponent yet

  await expect(page.locator('#abortBtn')).toBeVisible();
  await expect(page.locator('#resignBtn')).toBeHidden();

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
  await createGame(page);

  await page.locator('#abortBtn').click();

  // Back on the start screen, where a new game can be created.
  await expect(page.locator('#createGameBtn')).toBeVisible();

  await context.close();
});

test('once the opponent joins, Abort is replaced by Resign', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);

  await expect(game.creator.locator('#abortBtn')).toBeHidden();
  await expect(game.creator.locator('#resignBtn')).toBeVisible();
  await expect(game.joiner.locator('#abortBtn')).toBeHidden();
  await expect(game.joiner.locator('#resignBtn')).toBeVisible();
});
