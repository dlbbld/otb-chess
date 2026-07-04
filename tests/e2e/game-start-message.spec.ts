import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame } from './helpers/app';

/**
 * The game-start arbiter message must be exactly right for every player, always. It encodes two
 * INDEPENDENT facts:
 *   - who joined  : the creator opened the game and waited, so their OPPONENT is the one who joined;
 *                   the joiner is the one who joined.
 *   - first move  : only the side to move has a running clock at the start.
 * Colour determines NEITHER fact -- the creator may choose Black, and a custom FEN may start with
 * Black to move -- so the message keys each clause to its own fact. That gives a 2x2 of
 * creator/joiner x first-move/not; all four combinations are pinned below.
 *
 * Two standard games (no FEN) cover the four perspectives without needing a custom position:
 *   - creator plays White -> creator HAS the first move (case 1); joiner does NOT (case 3)
 *   - creator plays Black -> creator does NOT have the first move (case 2); joiner DOES (case 4)
 */

// "Game started" itself is a transient banner over the board; the arbiter message keeps only
// the two short facts. The creator's opponent is named by COLOUR ("Black joined."); no "your
// turn" coaching is appended — a running clock says it all.
const MSG = {
  creatorFirst: 'Black joined. Your clock has been started.',
  creatorNotFirst: "White joined. Opponent's clock has been started.",
  joinerNotFirst: "You joined the game. Opponent's clock has been started.",
  joinerFirst: 'You joined the game. Your clock has been started.',
};

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('case 1: creator WITH the first move -> "Black joined" + own clock (+ banner)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { side: 'white' });
  await expect(game.creator.locator('#arbiterMessage')).toHaveText(MSG.creatorFirst);
  // The event itself shows as the transient banner over the board.
  await expect(game.creator.locator('#gameBanner')).toHaveText('Game started');
  await expect(game.creator.locator('#gameBanner')).toBeVisible();
  // ... and fades away on its own.
  await expect(game.creator.locator('#gameBanner')).toBeHidden({ timeout: 10_000 });
});

test('case 2: creator WITHOUT the first move -> "White joined" + opponent clock', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { side: 'black' });
  await expect(game.creator.locator('#arbiterMessage')).toHaveText(MSG.creatorNotFirst);
});

test('case 3: joiner WITHOUT the first move -> "you joined" + opponent clock', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { side: 'white' });
  await expect(game.joiner.locator('#arbiterMessage')).toHaveText(MSG.joinerNotFirst);
});

test('case 4: joiner WITH the first move -> "you joined" + own clock (+ banner)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { side: 'black' });
  await expect(game.joiner.locator('#arbiterMessage')).toHaveText(MSG.joinerFirst);
  await expect(game.joiner.locator('#gameBanner')).toHaveText('Game started');
});
