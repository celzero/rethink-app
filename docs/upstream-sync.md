# rethink-tv upstream-sync workflow

`rethink-tv` is a hard fork of `celzero/rethink-app` with two
operating invariants:

1. We never edit upstream files. Everything we own lives in
   `app/src/tv/`, `docs/`, `scripts/`, the `.github/workflows/android-tv*.yml`
   workflows, the `.github/workflows/upstream-sync.yml` workflow, the
   rethink-tv blocks in `app/build.gradle` (clearly fenced with
   `// rethink-tv fork: ...` comments), and the prepended fork notice
   in `README.md`.

2. We merge from upstream — we do not rebase. Rebasing rewrites every
   fork commit's SHA and destroys release-tag / CI-run traceability.
   Merging keeps both histories intact and produces one explicit
   merge commit per sync.

Together these two invariants make `git merge upstream/main` a
near-trivial operation: by construction, all the files upstream
touches are files we do not touch, so merge conflicts should be rare.

This document describes how the sync workflow runs in practice and
how to recover when it does hit a conflict.

---

## Branches & remotes

```
celzero/rethink-app  ──►  upstream/main
                                │
                                │  (weekly merge)
                                ▼
ezelab/rethink-tv    ──►  origin/upstream-sync  ──►  PR  ──►  origin/main
```

* `upstream` git remote points at `https://github.com/celzero/rethink-app.git`.
* `upstream-sync` is a long-lived branch on `origin` (the fork). It is
  reset every run to `origin/main` and then has `upstream/main` merged
  on top of it. It is never the source of truth for `main`; the PR
  is.
* `main` is protected. Only `upstream-sync → main` PRs and feature
  PRs can land on it.

## Workflow

`.github/workflows/upstream-sync.yml` runs every Monday at 03:00 UTC
and on-demand via `workflow_dispatch`. The job runs
`scripts/sync-upstream.sh --open-pr`, which:

1. Verifies the working tree is clean.
2. Fetches `upstream/main` and `origin/main`.
3. If `origin/main` is already an ancestor or equal to
   `upstream/main`, exits with code 0 (nothing to do).
4. Resets the `upstream-sync` branch to `origin/main`.
5. Merges `upstream/main` with `--no-ff` and a structured commit
   message. On conflict, exits 2 and leaves the working tree as-is.
6. Sanity-checks that the rethink-tv-owned files and `build.gradle`
   markers still exist after the merge.
7. Force-pushes `upstream-sync` to `origin` (with
   `--force-with-lease`).
8. Opens a PR `upstream-sync → main`, or notes if one is already
   open (in which case the push updates it automatically).

The PR triggers the full CI matrix: `🫣 Android CI` (upstream's phone
build) and `📺 Android TV CI` (our TV build). Both must be green
before the PR is mergeable.

---

## Conflict-resolution playbook

The merge can conflict in three categories.

### Category A — conflict inside rethink-tv-owned files

This includes:

* `app/src/tv/**`
* `docs/**`
* `scripts/**`
* `.github/workflows/android-tv*.yml`
* `.github/workflows/upstream-sync.yml`
* The fenced `rethink-tv fork:` blocks in `app/build.gradle`
* The fork-notice block at the top of `README.md`

By construction upstream does not touch these. If they conflict, it
means we rebased / cherry-picked something incorrectly in a prior
sync. Always keep `--ours` (the rethink-tv version):

```bash
git checkout --ours -- app/src/tv app/build.gradle README.md
git add app/src/tv app/build.gradle README.md
git commit --no-edit
```

### Category B — conflict on a structural file we depend on

Example: upstream renames `app/src/full/java/com/celzero/bravedns/service/ProxyManager.kt`
to a different package, but the constant `ID_WG_BASE` that
`app/src/main/`'s `WgHopMapDao.kt` interpolates into a `@Query`
remains.

Action:

1. Take upstream's version (`git checkout --theirs`).
2. Verify `assembleFdroidTvDebug` still compiles locally or in CI.
3. If it does, push the PR and merge.
4. If it does NOT, file an issue in the fork to track the structural
   divergence and shadow the affected file in `app/src/tv/java/...`
   until upstream stabilises.

### Category C — surprise upstream restructuring

Example: upstream moves the `flavorDimensions = ["releaseChannel", "releaseType"]`
declaration somewhere else, or splits the `app/` module into two.

Action: do not auto-resolve. Update this document with the new
playbook entry, then resolve manually with a human-readable commit
message explaining the structural change.

---

## Manual sync (running locally)

```bash
# from a clean working tree on `main`, fully in sync with origin
scripts/sync-upstream.sh                # local dry-run
scripts/sync-upstream.sh --push         # push, no PR
scripts/sync-upstream.sh --open-pr      # push and open PR (uses `gh`)
```

The script is idempotent. Re-running it after an upstream change
produces a new merge commit on `upstream-sync` and updates the PR.

### Environment overrides

| Variable           | Default          | Use for                                   |
|--------------------|------------------|-------------------------------------------|
| `UPSTREAM_REMOTE`  | `upstream`       | Renamed remote                            |
| `UPSTREAM_BRANCH`  | `main`           | Sync from a non-main upstream branch      |
| `ORIGIN_REMOTE`    | `origin`         | Multi-remote fork setup                   |
| `SYNC_BRANCH`      | `upstream-sync`  | Alternative integration branch            |
| `BASE_BRANCH`      | `main`           | Sync against a release branch instead     |

---

## Why this matters

This workflow is the foundation under everything else in the fork.
If it stops working, three things degrade in lockstep:

1. **Security**: we fall behind upstream's CVE / dependency-update
   cadence. Upstream patches Retrofit / OkHttp / Room frequently; we
   need them on the same day.
2. **Engine parity**: the TV UI deliberately reuses upstream's
   `VpnController`, `FirewallManager`, `WireguardManager`,
   `RethinkBlocklistManager` and friends. Falling behind upstream
   means the TV UI drifts away from features the engine has
   already shipped (e.g. new proxy types, new DNS endpoint kinds).
3. **Trust**: users install `rethink-tv` partly because they trust
   the upstream maintainers' engine. If our engine drifts, that
   trust dilutes.

If the sync starts failing weekly, treat it as a tier-1 incident.
