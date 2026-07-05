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

  await expect(gameActions.locator('#offerDrawBtn')).toBeVisible();
  await expect(gameActions.locator('#resignBtn')).toBeVisible();
  await expect(gameActions.locator('#requestPieceBtn')).toHaveCount(0);

  await expect(claims.locator('.control-label')).toHaveText('Claim');
  await expect(claims.locator('.claim-btn')).toHaveCount(4);
  await expect(claims.locator('#claimThreefoldOnBoardBtn .btn-icon')).toBeVisible();
  await expect(claims.locator('#claimFiftyMoveWithMoveBtn .btn-icon')).toBeVisible();

  await expect(utilities.locator('#requestPieceBtn')).toBeVisible();
  await expect(utilities.locator('#exportPgnBtn')).toBeVisible();
  await expect(utilities.locator('#flipBoardBtn')).toBeVisible();

  const topY = (await gameActions.boundingBox())?.y ?? 0;
  const claimY = (await claims.boundingBox())?.y ?? 0;
  const utilityY = (await utilities.boundingBox())?.y ?? 0;
  expect(topY).toBeLessThan(claimY);
  expect(claimY).toBeLessThan(utilityY);

  const overflowing = await white.locator('.board-controls button').evaluateAll((buttons) =>
    buttons
      .filter((button) => button.scrollWidth > button.clientWidth + 1)
      .map((button) => button.id));
  expect(overflowing).toEqual([]);
});
