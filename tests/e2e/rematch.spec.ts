import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  colorOf,
  resign,
} from './helpers/app';
import { dragPiece, pressClock, expectPiece } from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('rematch: offer blinks on the opponent, accepting starts a new game with colours swapped', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // End the game normally: White resigns, Black wins.
  await resign(white);
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
  await expect(white.locator('#rematchBtn')).toBeVisible();
  await expect(black.locator('#rematchBtn')).toBeVisible();

  // White offers a rematch: their own button freezes as "Rematch offered", and — Lichess-style —
  // Black's button starts blinking as the invitation.
  await white.locator('#rematchBtn').click();
  await expect(white.locator('#rematchBtn')).toBeDisabled();
  await expect(white.locator('#rematchBtn')).toHaveText('Rematch offered');
  await expect(white.locator('#arbiterMessage')).toContainText('Rematch offer sent');
  await expect(black.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
  await expect(black.locator('#arbiterMessage')).toContainText('offers a rematch');

  // Black clicks their (blinking) button: that accepts — the rematch starts.
  await black.locator('#rematchBtn').click();

  // Both players are in a fresh game with colours SWAPPED and the same time control.
  await expect(white.locator('#arbiterMessage')).toContainText('Rematch started - you now play Black');
  await expect(black.locator('#arbiterMessage')).toContainText('Rematch started - you now play White');
  expect(await colorOf(white)).toBe('black');
  expect(await colorOf(black)).toBe('white');
  await expect(white.locator('#timeControlLabel')).toHaveText('3+0 • Blitz');
  await expect(black.locator('#timeControlLabel')).toHaveText('3+0 • Blitz');
  await expect(white.locator('#gameResultPanel')).toBeHidden();
  await expect(black.locator('#gameResultPanel')).toBeHidden();

  // The new game is fully playable: the NEW White (the former Black player) opens 1. e4.
  await dragPiece(black, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(black);
  await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(white, 'e4', 'WHITE_PAWN');
});

test('a rematch offer during a running game is rejected', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // The button is hidden mid-game (result panel), so talk to the server directly.
  await white.evaluate(() => (window as any).game.ws.send({ type: 'rematchOffer' }));
  await expect(white.locator('#arbiterMessage')).toContainText(
    'A rematch can only be offered after the game has ended');
});
