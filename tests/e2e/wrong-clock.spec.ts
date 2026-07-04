import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, expectGameResult } from './helpers/app';

// Pressing the OPPONENT's clock lever — possible on a physical clock, so it is modeled (A-006).
// The test server runs with a short arbiter pause: OTB_WRONG_CLOCK_PAUSE_MS=1000.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

/** The opponent's clock is the TOP lever (the own side is always at the bottom). */
async function pressOpponentClock(page: import('@playwright/test').Page) {
  await page.locator('#topClockBtn').click();
}

test('pressing the opponent\'s clock escalates: pause, pause + warning, then loss', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White is to move, so WHITE's clock runs — Black pressing it is the offense.
  // First press: the arbiter pauses the game and admonishes; White sees it passively.
  await pressOpponentClock(black);
  await expect(black.locator('#arbiterMessage')).toContainText('Please do not press your opponent');
  await expect(black.locator('#arbiterMessage')).not.toContainText('Warning');
  await expect(white.locator('#opponentInfoPanel')).toContainText('pressed your clock');
  await expect(black.locator('#chessClock')).toHaveClass(/paused/);

  // After the admonishment pause the interrupted clock restarts.
  await expect(black.locator('#chessClock')).not.toHaveClass(/paused/, { timeout: 10_000 });

  // Second press: same pause plus the warning.
  await pressOpponentClock(black);
  await expect(black.locator('#arbiterMessage')).toContainText(
    'Warning: the next press of your opponent\'s clock loses the game');
  await expect(white.locator('#opponentInfoPanel')).toContainText('been warned');
  await expect(black.locator('#chessClock')).toHaveClass(/paused/);
  await expect(black.locator('#chessClock')).not.toHaveClass(/paused/, { timeout: 10_000 });

  // Third press: Black loses the game.
  await pressOpponentClock(black);
  await expect(black.locator('#arbiterMessage')).toContainText('you lose the game');
  await expect(white.locator('#arbiterMessage')).toContainText('repeatedly pressed your clock');
  await expectGameResult(white, '1-0');
  await expectGameResult(black, '1-0');
  await expect(black.locator('#gameResultReason')).toContainText(
    'Black loses the game by repeatedly pressing the opponent\'s clock.');
});

test('pressing the opponent\'s lever while it is already down is a physical no-op', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // White is to move: BLACK's clock is not running, Black's lever is down. White pressing it
  // does nothing — no message, no pause, exactly like a real clock.
  await pressOpponentClock(white);
  await white.waitForTimeout(800);
  await expect(white.locator('#arbiterMessage')).not.toContainText('do not press');
  await expect(white.locator('#chessClock')).not.toHaveClass(/paused/);
  await expect(white.locator('#arbiterMessage')).toContainText('Game started');
});
