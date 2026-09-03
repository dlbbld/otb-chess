# Project instructions

(Keep this file in sync with `CLAUDE.md` — same content, two filenames because different
coding agents read different conventional files.)

## Testing policy (non-negotiable)

Every feature or behavior change ships with tests at BOTH levels, in the same commit:

1. **In-app unit tests (Java)** — `src/test/java/...` (e.g. `TestGameSession`, `TestArbiterEngine`,
   `TestGameRoom`): pin the session/engine logic, messages, counters, result types.
2. **End-to-end tests (Playwright)** — `tests/e2e/*.spec.ts`: pin the user-visible flow through the
   live client — arbiter messages on BOTH boards, button states, panels, result score/reason.

Long e2e runtimes are explicitly acceptable when the change has behavioral risk, but use judgment:

- Run the full `npx playwright test` suite for changes that can affect game flow, server/client
  state, board synchronization, clocks, restoration, claims/offers, result handling, or shared UI
  plumbing.
- For wording-only changes or tightly scoped low-risk edits, run focused unit/e2e coverage for the
  touched behavior instead of the full e2e suite. Do not spend minutes on the full browser suite
  just because a message string changed.
- When a bug comes from a user-reported journey, the regression e2e must replay that journey
  literally. Run broader e2e only if the implementation touches code with plausible side effects.
- Before finishing, state which tests were run and why that scope was sufficient.

## Workflow

- **Commit and push every finished feature/fix — always, without being asked.** Commit locally per
  verified change, and push as soon as the feature/fix is done. Never leave completed work sitting
  only in a local branch or worktree. PRs only when asked.
- When reporting work as done, say where it landed: branch name, and whether it is pushed and
  whether it is on `main`. "Committed and pushed" to a side branch is not "in main" — a fix can be
  safely on `origin/<branch>` and still absent from every release. Do not let those states blur.
- Stage files explicitly (`git add <paths>`), never `git add -A` — another agent's or the user's
  unrelated work may be sitting uncommitted in the working tree.
- The e2e suite runs its own server on dedicated ports 18080/18081 — the user's dev server on 8080
  (`start.bat`) keeps running during test runs and must not be killed. The suite tests the
  packaged jar: rebuild (`npm run build:server` or `npm run e2e`) after Java changes; static
  files (HTML/JS/CSS) are read from disk and need no rebuild. If e2e results look stale, check
  for a leftover java process listening on 18080 and stop it.
- `start.bat` serves a stable snapshot of the latest commit (the `..\otb-chess-stable` worktree)
  for the user's manual testing — uncommitted edits never reach it. `start-dev.bat` runs the
  working tree as-is.
- Do not work in this working tree at the same time as another agent; sequential use only.
- `tasks.md` is the live-planning source of truth: record completed work there (check items in
  place, don't delete).

## Commit messages

Commit messages should be concise. Use a short imperative subject by default.
Add a body only for non-obvious design decisions, migrations, or rule semantics.
Do not include routine test results or PR-style summaries in commit bodies.
