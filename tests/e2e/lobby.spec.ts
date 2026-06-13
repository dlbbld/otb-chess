import { test, expect } from '@playwright/test';
import { createGame, joinGame, expectGameStarted } from './helpers/app';

test('opening the game page without a game id shows guidance', async ({ page }) => {
  await page.goto('/game.html');
  await expect(page.locator('#arbiterMessage')).toContainText('No game ID');
});

test('joining a non-existent game shows a "not found" error', async ({ page }) => {
  await page.goto('/game.html?gameId=does-not-exist-xyz');
  await expect(page.locator('#arbiterMessage')).toContainText('Game not found');
});

test('a third player cannot join a full game', async ({ browser }) => {
  const c1 = await browser.newContext();
  const c2 = await browser.newContext();
  const c3 = await browser.newContext();
  try {
    const p1 = await c1.newPage();
    const p2 = await c2.newPage();
    const p3 = await c3.newPage();

    const gameId = await createGame(p1);
    await joinGame(p2, gameId);
    await expectGameStarted(p1); // the two seats are taken

    await joinGame(p3, gameId);
    await expect(p3.locator('#arbiterMessage')).toContainText('already full');
  } finally {
    await c1.close();
    await c2.close();
    await c3.close();
  }
});
