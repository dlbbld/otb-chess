import { Page, Locator, expect } from '@playwright/test';

/** A board square in algebraic notation, e.g. "e4". */
export type Square = string;

export function squareLocator(page: Page, square: Square): Locator {
  return page.locator(`#board .square[data-square="${square}"]`);
}

export function pieceLocator(page: Page, square: Square): Locator {
  return page.locator(`#board .square[data-square="${square}"] .piece`);
}

/**
 * Physically drags a piece from one square to another using real mouse events.
 *
 * The board is a custom mousedown/mousemove/mouseup widget (not HTML5 drag-and-drop) that
 * resolves the drop target via document.elementFromPoint, so we drive page.mouse with
 * intermediate steps and release over the destination square's centre. The drag-ghost is
 * pointer-events:none, so it does not intercept the drop.
 *
 * Requires the board to be enabled — i.e. it is this player's turn.
 */
export async function dragPiece(page: Page, from: Square, to: Square): Promise<void> {
  const s = await squareLocator(page, from).boundingBox();
  const d = await squareLocator(page, to).boundingBox();
  if (!s || !d) throw new Error(`Cannot locate squares for drag ${from} -> ${to}`);
  const sx = s.x + s.width / 2;
  const sy = s.y + s.height / 2;
  const dx = d.x + d.width / 2;
  const dy = d.y + d.height / 2;
  await page.mouse.move(sx, sy);
  await page.mouse.down();
  await page.mouse.move(dx, dy, { steps: 10 });
  await page.mouse.up();
}

/**
 * Touches a square in place (mousedown + mouseup without moving) — the board emits a CLICK / touch
 * event, used for touch-move scenarios. Requires the board to be enabled (this player's turn).
 */
export async function clickSquare(page: Page, square: Square): Promise<void> {
  const b = await squareLocator(page, square).boundingBox();
  if (!b) throw new Error(`Cannot locate square ${square}`);
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
  await page.mouse.down();
  await page.mouse.up();
}

/**
 * Lifts a piece off the board (drag from the square to outside the board) — emits a REMOVE event.
 * Used for capture-by-removal, e.g. removing the pawn captured en passant.
 */
export async function removePiece(page: Page, from: Square): Promise<void> {
  const b = await squareLocator(page, from).boundingBox();
  if (!b) throw new Error(`Cannot locate square ${from}`);
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
  await page.mouse.down();
  await page.mouse.move(2, 2, { steps: 6 }); // top-left corner: off the board
  await page.mouse.up();
}

/**
 * Presses this player's own clock lever. The board flips per player so a player's own pieces
 * are always at the bottom, which makes the bottom lever always this player's clock.
 */
export async function pressClock(page: Page): Promise<void> {
  await page.locator('#bottomClockBtn').click();
}

/** Asserts (with auto-retry) that `square` holds the given piece code, e.g. "WHITE_PAWN". */
export async function expectPiece(page: Page, square: Square, piece: string): Promise<void> {
  await expect(pieceLocator(page, square)).toHaveAttribute('data-piece', piece);
}

/** Asserts (with auto-retry) that `square` is empty. */
export async function expectEmpty(page: Page, square: Square): Promise<void> {
  await expect(pieceLocator(page, square)).toHaveCount(0);
}
