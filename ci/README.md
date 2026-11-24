# Required workflows for Soniq-based music apps

The canonical workflow templates live in [`ci/templates`](templates) so they can
be copied into music-app repositories. GitHub Actions only discovers runnable
workflows under `.github/workflows`; the files here are templates, not active
workflows in this repository.

## Install in an app repository

Copy both templates into the app repository without changing their workflow or
job names:

```sh
mkdir -p .github/workflows
cp /path/to/Soniq-Base-Android/ci/templates/build.yml .github/workflows/build.yml
cp /path/to/Soniq-Base-Android/ci/templates/release.yml .github/workflows/release.yml
```

The app repository must provide the expected `:soniq-base` and `:app` Gradle
modules, tag-derived versions, `CHANGELOG.md`, and the release helper at
`scripts/release.py`. The Build workflow also runs
`python3 -m unittest discover -s scripts/tests -v`, so include the release
helper's tests at `scripts/tests/`. The matching helper and tests in this repo
can be copied into the app repository; update its app release name in
`scripts/release.py` if the app chooses a different APK artifact name. The
workflows use the stable artifact name `soniq-music-app` by default.

## Make the build a merge requirement

For **each** app repository, a repository or organization administrator must
configure a ruleset or branch protection rule for `main` that:

1. Requires pull requests to merge.
2. Requires the status check **`Build / build`** before merging.
3. Prevents direct pushes that bypass the pull-request check, except for any
   explicitly approved administrator or automation bypass.

Keep the workflow name (`Build`) and job id (`build`) unchanged so the required
check remains `Build / build`. The Release workflow is triggered by version tags,
manual dispatch, or an opted-in release commit on `main`; it is not a PR merge
check.

These repository-side rules are what make adoption mandatory. A workflow file
or policy in this Soniq Base repository cannot itself impose branch protection
on other repositories, so each app's ruleset must be configured by an
administrator. Recheck the required status-check setting after installing the
workflow and running it once if GitHub has not yet listed the check.
