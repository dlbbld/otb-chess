import { test, expect } from '@playwright/test';
import {
  createGame,
  joinGame,
  expectGameStarted,
  startTwoPlayerGame,
  resign,
  expectGameResult,
} from './helpers/app';

test('opening the game page without a game id shows guidance and a way back', async ({ page }) => {
  await page.goto('/game.html');
  await expect(page.locator('#arbiterMessage')).toContainText('No game code was provided');
  await expect(page.locator('#arbiterButtons')).toContainText('Back to lobby');
});

test('joining a non-existent game shows a friendly message and a way back', async ({ page }) => {
  await page.goto('/game.html?gameId=does-not-exist-xyz');
  await expect(page.locator('#arbiterMessage')).toContainText("wasn't found");
  // Every game control is dead — only the way back is actionable.
  await expect(page.locator('#exportPgnBtn')).toBeDisabled();
  await expect(page.locator('#flipBoardBtn')).toBeDisabled();
  await expect(page.locator('#resignBtn')).toBeDisabled();
  // A clear path back to the lobby, not a stuck board with a raw error.
  const backBtn = page.locator('#arbiterButtons').getByText('Back to lobby');
  await expect(backBtn).toBeVisible();
  await expect(backBtn).toBeEnabled();
  await backBtn.click();
  await expect(page).toHaveURL(/\/$/);
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
    await expect(p3.locator('#arbiterMessage')).toContainText('already has two players');
    await expect(p3.locator('#arbiterButtons')).toContainText('Back to lobby');
  } finally {
    await c1.close();
    await c2.close();
    await c3.close();
  }
});

test('joining a game that has already ended shows a friendly message and a way back', async ({ browser }) => {
  const game = await startTwoPlayerGame(browser);
  const third = await browser.newContext();
  try {
    await resign(game.creator);
    await expectGameResult(game.joiner, '0-1'); // creator (White) resigned

    const p = await third.newPage();
    await joinGame(p, game.gameId);
    await expect(p.locator('#arbiterMessage')).toContainText('already ended');
    await expect(p.locator('#arbiterButtons')).toContainText('Back to lobby');
  } finally {
    await Promise.all(game.contexts.map((c) => c.close()));
    await third.close();
  }
});

test('a new tab in the same browser finds its way back into the running game via the lobby', async ({
  browser,
}) => {
  const game = await startTwoPlayerGame(browser);
  try {
    // The player "loses" the game tab and opens a NEW tab in the same browser (same context =
    // same localStorage): the lobby must offer the way back, not strand them.
    const newTab = await game.contexts[0].newPage();
    await newTab.goto('/');
    await expect(newTab.locator('#gameInProgress')).toBeVisible();
    await newTab.locator('#returnToGameBtn').click();

    // The new tab resumes the same seat (secret token from localStorage) — game continues.
    await expect(newTab.locator('#arbiterMessage')).toContainText('Reconnected', { timeout: 15_000 });
    await expect(newTab.locator('#board')).toBeVisible();
  } finally {
    await Promise.all(game.contexts.map((c) => c.close()));
  }
});

test('the lobby shows no game-in-progress banner without a saved session', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#gameInProgress')).toBeHidden();
  await expect(page.locator('#createGameBtn')).toBeVisible();
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
