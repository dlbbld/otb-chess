import { test, expect, Browser } from '@playwright/test';
import { createGame, joinGame, expectGameStarted, clickRestore, TwoPlayerGame } from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';
import { startIsolatedServer } from './helpers/isolated-server';

let isolated: Awaited<ReturnType<typeof startIsolatedServer>>;
let game: TwoPlayerGame | undefined;
test.beforeEach(async ({request}) => {
  isolated = await startIsolatedServer(request, {OTB_WRONG_CLOCK_PAUSE_MS:'5000'});
});
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
  await isolated?.stop();
});

async function beginOverlappingPauses(browser: Browser) {
  const contexts = [await isolated.context(browser), await isolated.context(browser)];
  const white = await contexts[0].newPage(); const black = await contexts[1].newPage();
  game = {contexts, white, black, creator:white, joiner:black, gameId:''};
  game.gameId = await createGame(white, {autoResume:false});
  await joinGame(black, game.gameId);
  await expectGameStarted(white); await expectGameStarted(black);
  const pressedAt = Date.now();
  await black.locator('#topClockBtn').click();
  await expect(white.locator('#opponentInfoPanel')).toContainText('paused');
  await dragPiece(white, 'e7', 'e6'); await clickRestore(white);
  for (const page of [white, black]) await expect(page.getByRole('button', {name:'Ready to continue'})).toBeVisible();
  expect(Date.now() - pressedAt, 'the restoration must happen before the old five-second timer').toBeLessThan(4500);
  return {white, black, pressedAt};
}

test('an obsolete wrong-clock timer cannot run time while both players still need to confirm Ready', async ({browser}) => {
  const {white, black, pressedAt} = await beginOverlappingPauses(browser);
  await white.waitForTimeout(Math.max(0, pressedAt + 5500 - Date.now()));
  for (const page of [white, black]) {
    await expect(page.getByRole('button', {name:'Ready to continue'})).toBeVisible();
    await expect(page.locator('#arbiterMessage')).toContainText('ready to continue');
    expect(await page.evaluate(() => (window as any).game.clockRunning)).toBe('none');
    expect(await page.evaluate(() => (window as any).game.board.enabled)).toBe(false);
  }
  await white.getByRole('button', {name:'Ready to continue'}).click();
  await black.getByRole('button', {name:'Ready to continue'}).click();
  await expect(white.locator('#arbiterMessage')).toContainText('Game continues');
  await dragPiece(white, 'e2', 'e4'); await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('an obsolete wrong-clock timer cannot restart White after play has passed to Black', async ({browser}) => {
  const {white, black, pressedAt} = await beginOverlappingPauses(browser);
  await white.getByRole('button', {name:'Ready to continue'}).click();
  await black.getByRole('button', {name:'Ready to continue'}).click();
  await expect(white.locator('#arbiterMessage')).toContainText('Game continues');
  await dragPiece(white, 'e2', 'e4'); await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  expect(Date.now() - pressedAt, 'the turn must change before the old five-second timer').toBeLessThan(4500);
  await white.waitForTimeout(Math.max(0, pressedAt + 5500 - Date.now()));
  for (const page of [white, black]) expect(await page.evaluate(() => (window as any).game.clockRunning)).toBe('black');
  await dragPiece(black, 'e7', 'e5'); await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
});
