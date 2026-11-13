# EME APP: instructions for Claude

The rules for every change are in CONTRIBUTING.md. Read and follow them:

@CONTRIBUTING.md

## Before you start

- Run `git pull` on `main` first. Other people's pull requests get merged, so the local copy
  can be behind.
- Check the open issues and pull requests (`gh issue list`, `gh pr list`) to see whether the
  problem is already reported or someone is already working on it.

## How to make a change

- Never commit on `main` and never push to `main` directly, also not as the owner. Create a
  branch (`fix/...` or `feat/...`), commit there, push the branch and open a pull request.
- One logical change per branch and per pull request. Link the issue in the pull request:
  `Fixes #12` when it solves the whole issue (GitHub closes the issue on merge), `Part of #12`
  when it solves a part. After merging the last part of an issue, close it by hand with a
  comment that links the pull requests.
- Run `./gradlew testDebugUnitTest` before every commit. Do not commit when a test fails.
- A change in logic comes with a unit test. A bug fix comes with a test that fails without
  the fix.
- A new or changed text goes into all 13 `strings.xml` files.
- Stage files by name (`git add <file>`), not `git add -A` or `git add .`.
- Never force-push and never rewrite history that is already on GitHub.
- Merge a pull request only when the "Unit tests" check is green.

## Never

- Never make the app send anything to logger.hb9q.ch other than the login and the read
  requests it does today. The HB9Q chat is read only, as agreed with HB9Q.
- Never make the app post anything to www.chris.org (N0UK chat). It is read only.
- Never poll HB9Q, W3SZ or N0UK more often than the current intervals.
- Never put a login, password or cookie in code, test data, a commit, an issue or a pull
  request.
- Never add an e-mail address to the test data; replace it by `xxxx@test.com`.

## Releases

A version bump (`versionCode`, `versionName` in `app/build.gradle.kts`) and a new
`apk/EME-APP.apk` go in their own release commit, separate from fixes and features.

## Communication

Say in plain words which git or GitHub step you are about to take and why, before you take it.
Say what you tested and what you did not test.
