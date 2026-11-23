# Release procedure

Use a development branch and pull request for every release. Never commit or push release work
directly to `main`.

## Release naming

Choose the version and descriptive name once. Use them in these exact forms:

- pull-request target: `main`;
- pull-request title: `NAME` only, without the version;
- first heading in the pull-request body: `## NAME`;
- changelog heading: `VERSION — NAME — DATE`;
- GitHub release title: `VERSION`;
- first heading in the GitHub release notes: `## NAME`.

For example, the PR title is `Released Move Finality Guard`, its body begins with
`## Released Move Finality Guard`, the changelog heading includes
`0.1.5 — Released Move Finality Guard`, and the GitHub release title is `0.1.5`.

The Maven project version and Git tag also contain only `VERSION`.

## Prepare the release branch

1. Start from the released `main`, then make and verify the intended changes on a development
   branch. A branch that predates the last release instead merges the released `main` into itself,
   resolves the conflicts, and re-runs both suites before the release preparation below.
2. Set the Maven project version in `pom.xml`. That is the only place to edit: the app reads its
   own version from `version.properties`, which Maven filters from the project version.
3. Add a dated changelog entry headed `VERSION — NAME — DATE`, and point the README's
   current-release link to it. The anchor drops the dots and the em dash, and doubles the
   separators, for example `CHANGELOG.md#016--touch-move-and-release-guards--2026-09-22`. Check
   that the link resolves.
4. Update `tasks.md` with the release scope, verification, and remaining manual/publication work.
5. Pin the version in Java and Playwright coverage:
   `TestOtbChessServer.testAppVersionComesFromFilteredResourceNotFallback` and
   `tests/e2e/version.spec.ts`, whose test name carries the version too. `git grep` the previous
   version to catch any other pin. Verify `/api/version` and the visible version on both the lobby
   and board pages.
6. No launcher edit is needed: `start.bat` serves whatever branch `OTB_TEST_BRANCH` names, falling
   back to its first argument and then to the default `add-sounds`. When the release
   branch is not the default, run it as `start.bat <release-branch>` (or set `OTB_TEST_BRANCH`) in
   step 1 of the manual test below.
7. Run the full Java suite. Run the full Playwright suite for gameplay, shared state, or common UI
   changes; focused Playwright coverage is sufficient for isolated release metadata after the
   affected behavior has already passed the full suite.
8. Rebuild the packaged jar and verify it reports the prepared version.
9. Commit and push the release branch, then open or update the PR against `main`. Set its title to
   `NAME` only, without the version, and begin its body with `## NAME`.

## Manual test and publish

1. Run `start.bat <release-branch>` to fetch the pushed release branch into the stable worktree
   (plain `start.bat` serves the default branch). Verify the version and the changed user journey
   manually before merging.
2. Merge the PR into `main` only after automated and manual verification pass.
3. Create the `VERSION` tag from the merge commit and publish a GitHub release titled `VERSION`.
   Use the matching changelog entry as the release notes, beginning with `## NAME` rather than
   repeating the version in that heading.
4. Deployment is separate from source publication: the host keeps serving the previous build until
   it is updated. Move the production checkout (`/Users/chess-server/Claude/otb-chess` on the iMac)
   to the new tag, rebuild, and restart the app daemon using
   [SETUP.md §12](SETUP.md#12-deploying-updates) — the restart needs an administrator password
   because the daemon is installed system-wide, unless the operator has the scoped grant described
   there. Then verify the live health endpoint, version, and a two-player flow. A restart ends
   running games, so deploy when the site is idle.
5. Add no commit to the release branch after the merge, and report publication and deployment in
   the conversation instead of `tasks.md`: a commit made after the merge is on no release and
   keeps the branch from being deleted.
6. Delete the release branch when it is no longer needed. Check first, then delete it on GitHub
   and locally:
   ```bash
   git fetch --prune origin
   git log --oneline origin/main..origin/<release-branch>   # must print nothing
   gh pr list --head <release-branch> --state open          # must list nothing
   git push origin --delete <release-branch>
   git branch -d <release-branch>
   ```
   A branch that still holds commits which are not on `main` is not deleted as it is: create the
   next development branch from it first, so that those commits move there, then delete it.
7. Start the next development branch from the newly released `main`. When it replaces the default
   testing branch of `start.bat`, change that default in `start.bat`, `TestWindowsLauncher`, the
   README, this file, `CLAUDE.md` and `AGENTS.md` in the same commit.
