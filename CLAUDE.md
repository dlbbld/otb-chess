# Project instructions

## Testing policy (non-negotiable)

Every feature or behavior change ships with tests at BOTH levels, in the same commit:

1. **In-app unit tests (Java)** — `src/test/java/...` (e.g. `TestGameSession`, `TestArbiterEngine`,
   `TestGameRoom`): pin the session/engine logic, messages, counters, result types.
2. **End-to-end tests (Playwright)** — `tests/e2e/*.spec.ts`: pin the user-visible flow through the
   live client — arbiter messages on BOTH boards, button states, panels, result score/reason.

Long e2e runtimes are explicitly acceptable — never trim e2e coverage for speed. A feature is not
done until `mvn test` and `npx playwright test` are both green. When a bug comes from a
user-reported journey, the regression e2e must replay that journey literally.

## Workflow

- Commit locally per verified change; push when the feature is complete. PRs only when asked.
- The e2e suite runs its own server on dedicated ports 18080/18081 — a dev server on 8080
  (`start.bat`) keeps running during test runs. The suite tests the packaged jar: rebuild
  (`npm run build:server` or `npm run e2e`) after Java changes.
- `start.bat` serves a stable snapshot of the latest commit (the `..\otb-chess-stable` worktree);
  `start-dev.bat` runs the working tree as-is.
- `tasks.md` is the live-planning source of truth: record completed work there (check items in
  place, don't delete).
