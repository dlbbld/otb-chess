import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  SCORE_DRAW,
} from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

// The test server runs with short windows (playwright.config.ts):
// OTB_DISCONNECT_GRACE_MS=1500 (info message), OTB_ABANDON_MS=4000 (adjudication).

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('a player closing the browser loses by abandonment when the opponent can mate', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // Play a real move so the game is clearly in progress.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');

  // Black closes the browser (the whole context = all sockets).
  await game.contexts[1].close();

  // Stage 1: White is informed, for information only.
  await expect(white.locator('#arbiterMessage')).toContainText('Your opponent has disconnected', {
    timeout: 10_000,
  });

  // Stage 2: the game is adjudicated — White has mating material, so White wins.
  await expect(white.locator('#arbiterMessage')).toContainText('Your opponent left the game. You win.', {
    timeout: 15_000,
  });
  await expectGameResult(white, '1-0');
  await expect(white.locator('#gameResultReason')).toContainText('Black left the game. White wins the game.');
});

test('abandonment is a draw when the remaining player cannot possibly mate', async ({ browser }) => {
  // White (the remaining player) has only the king; Black has king + queen. No series of legal
  // moves lets White mate — the abandoned game is adjudicated as a draw, as chess servers do.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/3q4/8/8/8/4K3 w - - 0 1' });
  const { white } = game;

  await game.contexts[1].close(); // Black leaves

  await expectGameResult(white, SCORE_DRAW);
  await expect(white.locator('#arbiterMessage')).toContainText(
    'Your opponent left the game, but because you have insufficient material to mate, the game is a draw.');
});

test('a page refresh (reconnect) does not forfeit the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // Black refreshes: the socket closes but the saved session resumes the same seat.
  await black.reload();
  await expect(black.locator('#arbiterMessage')).toContainText('Reconnected', { timeout: 10_000 });

  // Wait past the abandonment window — the reconnect must have defused the adjudication.
  await black.waitForTimeout(5_000);
  await expect(white.locator('#gameResultPanel')).toBeHidden();
  await expect(black.locator('#gameResultPanel')).toBeHidden();

  // The game is still fully alive.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});
