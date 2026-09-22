# Release procedure

Use a development branch and pull request for every release. Never commit or push release work
directly to `main`.

## Canonical release title

Choose one title in this exact form:

> `VERSION — NAME`

Example: `0.1.5 — Released Move Finality Guard`.

Use that title unchanged as:

- the pull-request title;
- the version name in the changelog heading (followed by the release date);
- the GitHub release title.

The Maven project version and Git tag contain only `VERSION`, for example `0.1.5`.

## Prepare the release branch

1. Start from the released `main`, then make and verify the intended changes on a development
   branch. A branch that predates the last release instead merges the released `main` into itself,
   resolves the conflicts, and re-runs both suites before the release preparation below.
2. Set the Maven project version in `pom.xml`. That is the only place to edit: the app reads its
   own version from `version.properties`, which Maven filters from the project version.
3. Add a dated changelog entry headed with the canonical release title, and point the README's
   current-release link to it. The anchor drops the dots and the em dash, and doubles the
   separators, for example `CHANGELOG.md#016--touch-move-and-release-guards--2026-09-22`. Check
   that the link resolves.
4. Update `tasks.md` with the release scope, verification, and remaining manual/publication work.
5. Pin the version in Java and Playwright coverage:
   `TestOtbChessServer.testAppVersionComesFromFilteredResourceNotFallback` and
   `tests/e2e/version.spec.ts`, whose test name carries the version too. `git grep` the previous
   version to catch any other pin. Verify `/api/version` and the visible version on both the lobby
   and board pages.
6. Point the manual-testing launcher at the release branch: the branch name is hardcoded in
   `start.bat` and in `tests/e2e/launcher.spec.ts`, so both change with every release branch.
7. Run the full Java suite. Run the full Playwright suite for gameplay, shared state, or common UI
   changes; focused Playwright coverage is sufficient for isolated release metadata after the
   affected behavior has already passed the full suite.
8. Rebuild the packaged jar and verify it reports the prepared version.
9. Commit and push the release branch, then open or update the PR using the canonical release title.

## Manual test and publish

1. Run `start.bat` to fetch the pushed release branch into the stable worktree. Verify the version
   and the changed user journey manually before merging.
2. Merge the PR into `main` only after automated and manual verification pass.
3. Create the `VERSION` tag from the merge commit and publish a GitHub release whose title is the
   canonical release title. Use the matching changelog entry as the release notes.
4. Deployment is separate from source publication: the host keeps serving the previous build until
   it is updated. Move the production checkout (`/Users/chess-server/Claude/otb-chess` on the iMac)
   to the new tag, rebuild, and restart the app daemon using
   [SETUP.md §12](SETUP.md#12-deploying-updates) — the restart needs an administrator password
   because the daemon is installed system-wide. Then verify the live health endpoint, version, and
   a two-player flow. A restart ends running games, so deploy when the site is idle.
5. Record publication and deployment in `tasks.md`, then delete obsolete remote development
   branches. Start the next branch from the newly released `main` or rebase it onto that commit.
