import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame } from './helpers/app';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('board controls are grouped as game actions, draw claims, and utilities', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  const gameActions = white.locator('.board-controls .game-actions');
  const claims = white.locator('.board-controls .claim-section');
  const utilities = white.locator('.board-controls .utility-actions');

  await expect(gameActions.getByRole('button', { name: 'Offer Draw' })).toBeVisible();
  await expect(gameActions.getByRole('button', { name: 'Resign' })).toBeVisible();
  await expect(gameActions.locator('#requestPieceBtn')).toHaveCount(0);

  await expect(claims.locator('.control-label')).toHaveCount(0);
  await expect(claims.locator('.claim-btn')).toHaveCount(4);
  await expect(claims.locator('#claimThreefoldOnBoardBtn .btn-icon')).toBeVisible();
  await expect(claims.locator('#claimFiftyMoveWithMoveBtn .btn-icon')).toBeVisible();
  await expect(claims.getByRole('button', {
    name: 'Claim threefold repetition on the current position',
  })).toBeVisible();

  await expect(utilities.getByRole('button', { name: 'Request Piece' })).toBeVisible();
  await expect(utilities.getByRole('button', { name: 'Display PGN' })).toBeVisible();
  await expect(utilities.getByRole('button', { name: 'Flip Board' })).toBeVisible();

  await expect(white.locator('.board-controls .btn-label')).toHaveCount(0);
  await expect(white.locator('.board-controls')).not.toContainText('Offer Draw');
  await expect(white.locator('.board-controls')).not.toContainText('Threefold Position');
  await expect(white.locator('#offerDrawBtn')).toHaveAttribute('data-tooltip', 'Offer Draw');
  await expect(white.locator('#resignBtn')).toHaveAttribute('data-tooltip', 'Resign');
  await white.locator('#offerDrawBtn').hover();
  await white.waitForFunction(() => {
    const button = document.querySelector('#offerDrawBtn');
    if (!button) return false;
    return Number(window.getComputedStyle(button, '::before').opacity) > 0.9;
  });
  const offerTooltip = await white.locator('#offerDrawBtn').evaluate((button) => {
    const style = window.getComputedStyle(button, '::before');
    return { content: style.content, opacity: style.opacity };
  });
  expect(offerTooltip.content).toBe('"Offer Draw"');
  expect(Number(offerTooltip.opacity)).toBeGreaterThan(0.9);

  const topY = (await gameActions.boundingBox())?.y ?? 0;
  const claimY = (await claims.boundingBox())?.y ?? 0;
  const utilityY = (await utilities.boundingBox())?.y ?? 0;
  expect(topY).toBeLessThan(claimY);
  expect(claimY).toBeLessThan(utilityY);

  const tooWide = await white.locator('.board-controls button').evaluateAll((buttons) =>
    buttons
      .filter((button) => button.getBoundingClientRect().width > 52)
      .map((button) => button.id));
  expect(tooWide).toEqual([]);
});

test('display PGN button toggles the PGN panel', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  const pgnPanel = white.locator('#pgnDialog');
  await expect(pgnPanel).toBeHidden();

  await white.locator('#exportPgnBtn').click();
  await expect(pgnPanel).toBeVisible();

  await white.locator('#exportPgnBtn').click();
  await expect(pgnPanel).toBeHidden();

  await white.locator('#exportPgnBtn').click();
  await expect(pgnPanel).toBeVisible();

  await white.locator('#closePgnBtn').click();
  await expect(pgnPanel).toBeHidden();
});
