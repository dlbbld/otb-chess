import { test, expect, BrowserContext } from '@playwright/test';
import { expectGameStarted } from './helpers/app';
import { soundsOff } from './helpers/sounds-off';

// Found on 2026-11-25: two tabs of one browser share the stored seat. Joining the own game from a
// second tab took the creator's seat away - after a reload the first tab came back as the joiner,
// the second tab was shut out, and one colour had no player. The start screen now refuses to join
// the game this browser is already in, and says what to use instead.

let contexts: BrowserContext[] = [];

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

const REFUSAL = 'This game is already open in this browser, so you cannot join it here.';

async function createOnStartScreen(context: BrowserContext) {
  const page = await context.newPage();
  await page.goto('/');
  await page.locator('#createGameBtn').click();
  const code = page.locator('.game-code-value');
  await expect(code).toBeVisible({ timeout: 15_000 });
  return { page, gameId: ((await code.textContent()) ?? '').trim() };
}

test('the own game cannot be joined from another tab of the same browser; another browser can join it', async ({
  browser,
  baseURL,
}) => {
  const sameBrowser = await browser.newContext({ storageState: soundsOff(baseURL!) });
  contexts.push(sameBrowser);
  const { page: creator, gameId } = await createOnStartScreen(sameBrowser);

  // A second tab of the same browser: the code is refused, in whatever case it is typed.
  const secondTab = await sameBrowser.newPage();
  await secondTab.goto('/');
  for (const typed of [gameId, gameId.toLowerCase()]) {
    await secondTab.locator('#gameCodeInput').fill(typed);
    await secondTab.locator('#joinGameBtn').click();
    await expect(secondTab.locator('#joinRefused')).toBeVisible();
    await expect(secondTab.locator('#joinRefused')).toContainText(REFUSAL);
    await expect(secondTab.locator('#joinRefused')).toContainText(
      'join from a private (incognito) window or from another browser');
    await expect(secondTab.locator('#joinRefused a')).toHaveAttribute('href', /MANUAL\.md#playing-both-sides-yourself$/);
    await expect(secondTab).toHaveURL(/\/$/);
  }
  // Typing another code takes the refusal away again.
  await secondTab.locator('#gameCodeInput').fill('SOMEOTHERCODE');
  await expect(secondTab.locator('#joinRefused')).toBeHidden();

  // Nothing was changed: the creator is still the creator, also after a reload.
  await creator.reload();
  await expect(creator.locator('.game-code-value')).toHaveText(gameId, { timeout: 15_000 });
  await expect(creator.locator('#arbiterMessage')).toContainText('Waiting for opponent');

  // Another browser - here another browser context, as a private window is - joins normally.
  const otherBrowser = await browser.newContext({ storageState: soundsOff(baseURL!) });
  contexts.push(otherBrowser);
  const joiner = await otherBrowser.newPage();
  await joiner.goto('/');
  await expect(joiner.locator('#joinRefused')).toBeHidden();
  await joiner.locator('#gameCodeInput').fill(gameId);
  await joiner.locator('#joinGameBtn').click();
  await expectGameStarted(creator);
  await expectGameStarted(joiner);
});

test('a player who joined a game cannot join it again from another tab of the same browser', async ({
  browser,
  baseURL,
}) => {
  const creatorBrowser = await browser.newContext({ storageState: soundsOff(baseURL!) });
  const joinerBrowser = await browser.newContext({ storageState: soundsOff(baseURL!) });
  contexts.push(creatorBrowser, joinerBrowser);
  const { page: creator, gameId } = await createOnStartScreen(creatorBrowser);
  const joiner = await joinerBrowser.newPage();
  await joiner.goto('/');
  await joiner.locator('#gameCodeInput').fill(gameId);
  await joiner.locator('#joinGameBtn').click();
  await expectGameStarted(creator);
  await expectGameStarted(joiner);

  const secondTab = await joinerBrowser.newPage();
  await secondTab.goto('/');
  await secondTab.locator('#gameCodeInput').fill(gameId);
  await secondTab.locator('#gameCodeInput').press('Enter');

  await expect(secondTab.locator('#joinRefused')).toContainText(REFUSAL);
  // The way back into the running game is still offered, and the first tab plays on.
  await expect(secondTab.locator('#returnToGameBtn')).toBeVisible();
  await expect(joiner.locator('#arbiterMessage')).not.toContainText('another window');
});
