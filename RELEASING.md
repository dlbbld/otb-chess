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
   branch.
2. Set the Maven project version in `pom.xml`.
3. Add a dated changelog entry headed with the canonical release title, and point the README's
   current-release link to it.
4. Update `tasks.md` with the release scope, verification, and remaining manual/publication work.
5. Pin the version in Java and Playwright coverage. Verify `/api/version` and the visible version
   on both the lobby and board pages.
6. Run the full Java suite. Run the full Playwright suite for gameplay, shared state, or common UI
   changes; focused Playwright coverage is sufficient for isolated release metadata after the
   affected behavior has already passed the full suite.
7. Rebuild the packaged jar and verify it reports the prepared version.
8. Commit and push the release branch, then open or update the PR using the canonical release title.

## Manual test and publish

1. Run `start.bat` to fetch the pushed release branch into the stable worktree. Verify the version
   and the changed user journey manually before merging.
2. Merge the PR into `main` only after automated and manual verification pass.
3. Create the `VERSION` tag from the merge commit and publish a GitHub release whose title is the
   canonical release title. Use the matching changelog entry as the release notes.
4. Deployment is separate from source publication. Update and restart the production host using
   [SETUP.md §12](SETUP.md#12-deploying-updates), then verify the live health endpoint, version,
   and a two-player flow.
5. Record publication and deployment in `tasks.md`, then delete obsolete remote development
   branches. Start the next branch from the newly released `main` or rebase it onto that commit.
