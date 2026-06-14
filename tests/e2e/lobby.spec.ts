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

test('an invalid starting FEN is rejected on the start screen, not on the board', async ({ page }) => {
  await page.goto('/');
  await page.locator('#startingFen').fill('not-a-valid-fen');
  await page.locator('#createGameBtn').click();

  // The reason is shown in place and the player stays on the lobby — they are never sent to a board.
  await expect(page.locator('#fenError')).toContainText('Invalid FEN');
  await expect(page.locator('#startingFen')).toBeVisible(); // the FEN input only exists on the lobby
});

test('a valid starting FEN proceeds to the board', async ({ page }) => {
  await page.goto('/');
  await page.locator('#startingFen').fill('4k3/8/8/8/8/8/8/4K2R w K - 0 1');
  await page.locator('#createGameBtn').click();

  // Navigation happened and the game was created (the share code appears on the board page).
  await expect(page.locator('.game-code-value')).toBeVisible({ timeout: 15_000 });
});

test('a zero-minute initial time is rejected on the start screen', async ({ page }) => {
  await page.goto('/');
  await page.locator('#customMinutes').fill('0');
  await page.locator('#createGameBtn').click();

  // The game is not started — the player stays on the lobby with the reason.
  await expect(page.locator('#timeError')).toContainText('Minimum time');
  await expect(page.locator('#createGameBtn')).toBeVisible();
});
