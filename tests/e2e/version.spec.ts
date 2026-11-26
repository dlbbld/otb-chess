import { test, expect } from '@playwright/test';

// During development the project version carries "-beta" and the pages show "v0.6.0 beta", so a
// board of the version being built cannot be mistaken for the released one. The release removes
// the suffix (RELEASING.md).
test('0.6.0-beta is reported by the API and shown as "v0.6.0 beta" on lobby and board pages', async ({ page, request }) => {
  const response = await request.get('/api/version');
  expect(response.ok()).toBe(true);
  await expect(response.json()).resolves.toEqual({ version: '0.6.0-beta' });

  await page.goto('/');
  await expect(page.locator('[data-app-version]')).toHaveText('v0.6.0 beta');

  await page.goto('/game.html');
  await expect(page.locator('[data-app-version]')).toHaveText('v0.6.0 beta');
});
