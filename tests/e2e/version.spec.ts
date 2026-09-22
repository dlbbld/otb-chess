import { test, expect } from '@playwright/test';

test('0.1.5 is reported by the API and shown on lobby and board pages', async ({ page, request }) => {
  const response = await request.get('/api/version');
  expect(response.ok()).toBe(true);
  await expect(response.json()).resolves.toEqual({ version: '0.1.5' });

  await page.goto('/');
  await expect(page.locator('[data-app-version]')).toHaveText('v0.1.5');

  await page.goto('/game.html');
  await expect(page.locator('[data-app-version]')).toHaveText('v0.1.5');
});
