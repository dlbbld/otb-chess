import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, expectGameResult } from './helpers/app';
import { pressClock } from './helpers/board';
import {
  freePiece, pointOn, releaseAt, dragFree, placements, expectSamePlacements,
} from './helpers/free';

// J'adoube (E-005; FIDE 4.2.1), in a game with free placement of the pieces: the button announces
// the adjustment of one piece, which is then not touched. Said in the opponent's time, and a piece
// adjusted without it once the move is made, are ruled on by the arbiter on ladders of their own.
// Both boards are watched throughout.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
const info = (page: Page) => page.locator('#opponentInfoPanel');
const jadoube = (page: Page) => page.locator('#jadoubeBtn');

const NO_ADJUSTING = "You cannot adjust pieces without saying J'adoube.";
const PAUSED = ' The game is paused and will continue shortly.';
const RESTARTED = ' The clock has restarted.';

/** Where the piece that stands clearly on `square` lies, in units. */
async function placementOn(page: Page, square: string): Promise<{ x: number; y: number }> {
  const id = Number(await freePiece(page, square).getAttribute('data-id'));
  const placed = (await placements(page)).find((p) => p.id === id)!;
  return { x: placed.x, y: placed.y };
}

test('J\'adoube adjusts one piece on its square: both boards show it, and the piece is not touched',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    const { white, black } = game;
    await expect(jadoube(white)).toBeVisible();
    await expect(jadoube(white)).toBeEnabled();
    await expect(jadoube(white)).toHaveAttribute('aria-label', "J'adoube");
    const before = await placementOn(white, 'g1');

    await jadoube(white).click();
    await expect(arbiter(white)).toHaveText("White: J'adoube.");
    await expect(info(black)).toHaveText("White: J'adoube.");
    await expect(jadoube(white)).toBeDisabled();

    // The knight is set down a quarter of a square to the right, still clearly on g1.
    await dragFree(white, 'g1', 'g1', { dx: 0.2, dy: -0.1 });
    for (const page of [white, black]) {
      await expect(freePiece(page, 'g1')).toHaveAttribute('data-piece', 'WHITE_KNIGHT');
    }
    const after = await placementOn(white, 'g1');
    expect(after.x).toBeGreaterThan(before.x + 150);
    await expectSamePlacements(white, black);
    await expect(jadoube(white)).toBeEnabled(); // one J'adoube, one piece
    await expect(arbiter(white)).not.toHaveClass(/error/);

    // The knight was not touched: a pawn move is accepted.
    await dragFree(white, 'e2', 'e4');
    await pressClock(white);
    await expect(arbiter(white)).toHaveText("Move accepted. Opponent's turn.");
    await expect(arbiter(black)).toHaveText('Your turn.');
  });

test('without J\'adoube the same shift before the move is a touch, and the knight has to move',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    const { white } = game;
    await dragFree(white, 'g1', 'g1', { dx: 0.2, dy: -0.1 });
    await expect(arbiter(white)).not.toContainText('adjust'); // no call: the move is not made yet
    await dragFree(white, 'e2', 'e4');
    await pressClock(white);
    await expect(arbiter(white)).toContainText('knight');
    await expect(arbiter(white)).toHaveClass(/error/);
  });

test('an adjustment that leaves the square is undone on both boards, without a move and without penalty',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    const { white, black } = game;
    const before = await placements(white);

    await jadoube(white).click();
    await expect(jadoube(white)).toBeDisabled();
    await dragFree(white, 'g1', 'f3'); // carried to f3: no move can be made this way
    await expect(arbiter(white)).toHaveText('The adjustment is undone: an adjusted piece stays on its square.');
    for (const page of [white, black]) {
      await expect(freePiece(page, 'g1')).toHaveAttribute('data-piece', 'WHITE_KNIGHT');
      await expect(freePiece(page, 'f3')).toHaveCount(0);
    }
    expect(await placements(white)).toEqual(before);
    await expectSamePlacements(white, black);
    await expect(jadoube(white)).toBeEnabled();

    // Neither moved nor touched: a pawn move is accepted.
    await dragFree(white, 'e2', 'e4');
    await pressClock(white);
    await expect(arbiter(white)).toHaveText("Move accepted. Opponent's turn.");
  });

test('a J\'adoube that is not used ends with the clock press', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragFree(white, 'e2', 'e4');
  await jadoube(white).click(); // said after the move is made
  await expect(jadoube(white)).toBeDisabled();
  await pressClock(white);
  await expect(arbiter(black)).toHaveText('Your turn.');
  await expect(jadoube(white)).toBeEnabled();
  await expect(jadoube(black)).toBeEnabled();

  // It did not pass to Black: a shifted piece is a touch for Black.
  await dragFree(black, 'g8', 'g8', { dx: 0.2 });
  await dragFree(black, 'e7', 'e5');
  await pressClock(black);
  await expect(arbiter(black)).toContainText('knight');
});

test('J\'adoube in the opponent\'s time: admonition, warning, and the third time loses the game',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    const { white, black } = game;

    await jadoube(black).click();
    await expect(arbiter(black)).toHaveText('You can only adjust pieces in your own time.');
    await expect(arbiter(black)).toHaveClass(/error/);
    await expect(info(white)).toHaveText("Your opponent said J'adoube in your time.");
    await expect(jadoube(black)).toBeEnabled(); // nothing is armed
    await expect(white.locator('#chessClock')).not.toHaveClass(/paused/); // no pause

    await jadoube(black).click();
    await expect(arbiter(black)).toHaveText(
      'You can only adjust pieces in your own time. Warning: the next time you lose the game.');
    await expect(info(white)).toContainText('has been warned');

    await jadoube(black).click();
    for (const page of [white, black]) {
      await expectGameResult(page, '1-0');
      await expect(jadoube(page)).toBeDisabled();
    }
    await expect(arbiter(black)).toContainText("As you have said it again, you lose the game.");
    await expect(arbiter(white)).toContainText("repeatedly said J'adoube in your time");
  });

test('after the move is made a piece shifted without J\'adoube is called at once; the move stands',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    const { white, black } = game;
    await dragFree(white, 'e2', 'e4');

    // The pawn stands clearly on e4 and is set down again on e4, a little to the side.
    await dragFree(white, 'e4', 'e4', { dx: 0.2 });
    await expect(arbiter(white)).toHaveText(NO_ADJUSTING + PAUSED);
    await expect(arbiter(white)).toHaveClass(/error/);
    await expect(info(black)).toContainText("Your opponent adjusted a piece without saying J'adoube.");
    await expect(white.locator('#chessClock')).toHaveClass(/paused/);
    await expect(black.locator('#chessClock')).toHaveClass(/paused/);
    await expect(white.locator('#arbiterButtons button')).toHaveCount(0); // nothing is restored
    await expectSamePlacements(white, black); // the pawn stays where it was shifted to

    await expect(arbiter(white)).toHaveText(NO_ADJUSTING + RESTARTED, { timeout: 15_000 });
    await pressClock(white);
    await expect(arbiter(white)).toHaveText("Move accepted. Opponent's turn.");
    await expect(arbiter(black)).toHaveText('Your turn.');
    for (const page of [white, black]) {
      await expect(freePiece(page, 'e4')).toHaveAttribute('data-piece', 'WHITE_PAWN');
    }
  });

test('the third adjustment without J\'adoube loses the game, whatever piece is shifted', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragFree(white, 'e2', 'e4');

  await dragFree(white, 'e4', 'e4', { dx: 0.2 }); // the piece that was moved
  await expect(arbiter(white)).toHaveText(NO_ADJUSTING + RESTARTED, { timeout: 15_000 });
  await dragFree(white, 'g1', 'g1', { dx: -0.2 }); // another own piece
  const warned = NO_ADJUSTING + ' Warning: the next time you lose the game.';
  await expect(arbiter(white)).toHaveText(warned + PAUSED);
  await expect(arbiter(white)).toHaveText(warned + RESTARTED, { timeout: 15_000 });
  await dragFree(white, 'e7', 'e7', { dy: 0.2 }); // a piece of the opponent

  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
  }
  await expect(arbiter(white)).toContainText('As you have done it again, you lose the game.');
  await expect(arbiter(black)).toContainText("repeatedly adjusted pieces without saying J'adoube");
});

test('with J\'adoube a piece can be adjusted after the move is made, also a piece of the opponent',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    const { white, black } = game;
    await dragFree(white, 'e2', 'e4');
    await jadoube(white).click();
    await expect(jadoube(white)).toBeDisabled();
    await dragFree(white, 'e7', 'e7', { dx: 0.2 });
    await expect(jadoube(white)).toBeEnabled();
    await expect(arbiter(white)).toHaveText("White: J'adoube."); // no call
    await expectSamePlacements(white, black);
    await pressClock(white);
    await expect(arbiter(white)).toHaveText("Move accepted. Opponent's turn.");
  });

test('a game with centred pieces has no J\'adoube button', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: false });
  await expect(game.white.locator('#resignBtn')).toBeVisible();
  await expect(jadoube(game.white)).toBeHidden();
  await expect(jadoube(game.black)).toBeHidden();
});
