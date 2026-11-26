import { test, expect } from '@playwright/test';
import { createGame, joinGame, expectGameStarted, expectGameResult, SCORE_DRAW } from './helpers/app';
import { dragPiece } from './helpers/board';
import { startIsolatedServer } from './helpers/isolated-server';

test('expiry before a third moved-opponent-piece offense draws against a lone king on both boards', async ({ browser, request }) => {
  test.setTimeout(45_000);
  const server = await startIsolatedServer(request, {});
  const contexts = [await server.context(browser), await server.context(browser)];
  try {
    const [white, black] = await Promise.all(contexts.map(context => context.newPage()));
    const frames: { type: string; running?: string; whiteTimeMs?: number; receivedAt: number }[][] = [[], []];
    const opponentPieceReleases: number[] = [];
    let startedAt = 0;
    [white, black].forEach((page, index) => page.on('websocket', socket => socket.on('framereceived', frame => {
      const data = JSON.parse(frame.payload.toString());
      frames[index].push({ ...data, receivedAt: Date.now() });
      if (index === 0 && data.type === 'gameStarted') startedAt = Date.now();
    })));
    white.on('websocket', socket => socket.on('framesent', frame => {
      const data = JSON.parse(frame.payload.toString());
      if (data.type === 'boardEvent' && data.event.eventType === 'DRAG_MOVE'
          && data.event.square === 'e8' && data.event.targetSquare === 'e7') {
        opponentPieceReleases.push(Date.now());
      }
    }));
    const id = await createGame(white, {
      timeMs: 10_000, autoResume: false, fen: '4k3/8/8/8/8/8/8/R3K3 w - - 0 1',
      free: false, // the frames watched above are those of a game with centred pieces
    });
    await joinGame(black, id);
    await expectGameStarted(white);
    await expectGameStarted(black);

    // The first two offenses follow the real drag -> Revert -> both Ready journey.
    for (let offense = 0; offense < 2; offense++) {
      await dragPiece(white, 'e8', 'e7');
      await white.getByRole('button', { name: 'Revert', exact: true }).click();
      for (const page of [white, black]) {
        await expect(page.getByRole('button', { name: 'Ready to continue', exact: true })).toBeVisible();
      }
      await black.getByRole('button', { name: 'Ready to continue', exact: true }).click();
      if (offense === 0) {
        await white.getByRole('button', { name: 'Ready to continue', exact: true }).click();
        await expect(white.locator('#arbiterMessage')).toContainText('Game continues');
      }
    }

    await expect.poll(() => frames[0].filter(frame => frame.type === 'clockUpdate').at(-1)?.running).toBe('none');
    const remaining = frames[0].filter(frame => frame.type === 'clockUpdate').at(-1)!.whiteTimeMs!;
    expect(remaining, 'setup must leave enough time for the final Ready').toBeGreaterThan(1000);
    // The periodic check runs at whole seconds from gameStarted. Keep the clock paused until
    // resuming it makes expiry land about 250 ms after a tick, leaving room to drag before the next.
    const resumeAt = startedAt + Math.ceil((Date.now() - startedAt + remaining + 200) / 1000) * 1000
      + 250 - remaining;
    await white.waitForTimeout(Math.max(0, resumeAt - Date.now()));
    await white.getByRole('button', { name: 'Ready to continue', exact: true }).click();
    await expect(white.locator('#arbiterMessage')).toContainText('Game continues');
    await expect.poll(() => frames[0].filter(frame => frame.type === 'clockUpdate').at(-1)?.running).toBe('white');
    const resumed = frames[0].filter(frame => frame.type === 'clockUpdate').at(-1)!;
    // Prepare the third drag now, then release after expiry. Only mouse-up remains in the
    // narrow interval, so slow locator reads or a multi-step drag cannot let the ticker win.
    const source = await white.locator('#board .square[data-square="e8"]').boundingBox();
    const target = await white.locator('#board .square[data-square="e7"]').boundingBox();
    if (!source || !target) throw new Error('Cannot locate the king drag');
    await white.mouse.move(source.x + source.width / 2, source.y + source.height / 2);
    await white.mouse.down();
    await white.mouse.move(target.x + target.width / 2, target.y + target.height / 2, { steps: 10 });
    await white.waitForTimeout(Math.max(0, resumed.receivedAt + resumed.whiteTimeMs! + 40 - Date.now()));
    expect(frames[0].filter(frame => frame.type === 'gameEnded'), 'act before the periodic check finds expiry')
      .toHaveLength(0);

    await white.mouse.up();
    await expect.poll(() => opponentPieceReleases.length).toBe(3);

    for (const [index, page] of [white, black].entries()) {
      await expectGameResult(page, SCORE_DRAW);
      await expect(page.locator('#gameResultReason')).toHaveText(index === 0
        ? 'You flagged, but because your opponent has insufficient material to mate, the game is a draw.'
        : 'Your opponent flagged, but because you have insufficient material to mate, the game is a draw.');
      await expect(page.getByRole('button', { name: 'Revert', exact: true })).toHaveCount(0);
      await expect(page.locator('#resignBtn')).toBeDisabled();
      expect(frames[index].filter(frame => frame.type === 'gameEnded')).toHaveLength(1);
    }
    expect(opponentPieceReleases[2]).toBeLessThanOrEqual(frames[0].find(frame => frame.type === 'gameEnded')!.receivedAt);
    expect(frames[0].filter(frame => frame.type === 'restoreRequired')).toHaveLength(2);
    await expect(white.locator('#bottomClockTime')).toHaveText(/^0?0:00/);
    await expect(black.locator('#topClockTime')).toHaveText(/^0?0:00/);
  } finally {
    for (const context of contexts) await context.close();
    await server.stop();
  }
});
