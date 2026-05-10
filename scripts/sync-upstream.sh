#!/usr/bin/env bash
#
# rethink-tv: sync the fork from upstream celzero/rethink-app.
#
# Workflow
# --------
# This script fetches `upstream/main`, merges it into a long-lived
# `upstream-sync` branch that is then pushed to `origin`. From there a
# human (or `.github/workflows/upstream-sync.yml`) opens a pull request
# `upstream-sync → main` so the merge can be reviewed before it lands
# on the protected branch.
#
# Design choices
# --------------
# 1. We merge, we do not rebase. Rebasing across a hard fork rewrites
#    every fork commit's SHA, which destroys traceability for CI runs,
#    release tags, and contribute-back PRs. Merging keeps both
#    histories intact and produces an explicit merge commit per sync.
#
# 2. We never run this against `main` directly. The intermediate
#    `upstream-sync` branch exists so a human can resolve conflicts
#    and verify CI in a PR before fast-forwarding `main`.
#
# 3. The script is idempotent and safe to re-run. If there is nothing
#    new on `upstream/main`, the merge is a no-op and no push happens.
#
# 4. The only files we own are `app/src/tv/`, `docs/`, `scripts/`,
#    `.github/workflows/android-tv*.yml`, `.github/workflows/upstream-sync.yml`,
#    the `tv` flavor block + `sourceSets.tv` + `tvRelease` signing
#    block in `app/build.gradle`, and the prepended fork notice in
#    `README.md`. Conflicts on anything OUTSIDE that set are a
#    structural surprise from upstream — surface them rather than
#    auto-resolving.
#
# Usage
# -----
#   scripts/sync-upstream.sh                # default: dry-run-ish
#   scripts/sync-upstream.sh --push         # push the merged branch
#                                           # to origin so CI runs
#   scripts/sync-upstream.sh --open-pr      # implies --push, then
#                                           # opens a PR via `gh`
#
# Environment
# -----------
#   UPSTREAM_REMOTE  defaults to `upstream`
#   UPSTREAM_BRANCH  defaults to `main`
#   ORIGIN_REMOTE    defaults to `origin`
#   SYNC_BRANCH      defaults to `upstream-sync`
#   BASE_BRANCH      defaults to `main`
#
# Exit codes
# ----------
#   0   success (with or without changes merged)
#   1   precondition failure (e.g. dirty working tree, missing remote)
#   2   merge conflict — left in the working tree for a human to fix
set -euo pipefail

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"
ORIGIN_REMOTE="${ORIGIN_REMOTE:-origin}"
SYNC_BRANCH="${SYNC_BRANCH:-upstream-sync}"
BASE_BRANCH="${BASE_BRANCH:-main}"

PUSH=0
OPEN_PR=0
for arg in "$@"; do
  case "$arg" in
    --push)    PUSH=1 ;;
    --open-pr) PUSH=1; OPEN_PR=1 ;;
    -h|--help)
      sed -n '/^# Usage/,/^# Exit codes/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

say() { printf '\033[36m▶\033[0m %s\n' "$*"; }
die() { printf '\033[31m✖\033[0m %s\n' "$*" >&2; exit 1; }

[ -d .git ] || die "not a git repo — run from the rethink-tv root"

if [ -n "$(git status --porcelain)" ]; then
  die "working tree is dirty — commit, stash, or clean before syncing"
fi

if ! git remote get-url "$UPSTREAM_REMOTE" >/dev/null 2>&1; then
  die "remote '$UPSTREAM_REMOTE' is not configured (try: git remote add upstream https://github.com/celzero/rethink-app.git)"
fi

say "Fetching $UPSTREAM_REMOTE/$UPSTREAM_BRANCH and $ORIGIN_REMOTE/$BASE_BRANCH"
git fetch "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH"
git fetch "$ORIGIN_REMOTE" "$BASE_BRANCH"

UPSTREAM_SHA=$(git rev-parse "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH")
UPSTREAM_SHORT=$(git rev-parse --short "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH")
BASE_SHA=$(git rev-parse "$ORIGIN_REMOTE/$BASE_BRANCH")

# Has the fork already absorbed every upstream commit?
if git merge-base --is-ancestor "$UPSTREAM_SHA" "$BASE_SHA"; then
  say "no upstream changes — $BASE_BRANCH is already at or beyond $UPSTREAM_REMOTE/$UPSTREAM_BRANCH ($UPSTREAM_SHORT)"
  exit 0
fi

# Reset (or create) the sync branch from `origin/$BASE_BRANCH` so it
# starts from the current fork HEAD and we just need to merge upstream
# on top of it.
say "Resetting $SYNC_BRANCH to $ORIGIN_REMOTE/$BASE_BRANCH"
git checkout -B "$SYNC_BRANCH" "$ORIGIN_REMOTE/$BASE_BRANCH"

MERGE_MSG="chore(sync): merge upstream celzero/rethink-app@${UPSTREAM_SHORT}

Routine upstream sync via scripts/sync-upstream.sh.

Upstream: ${UPSTREAM_REMOTE}/${UPSTREAM_BRANCH} = ${UPSTREAM_SHA}
Fork base: ${ORIGIN_REMOTE}/${BASE_BRANCH} = $(git rev-parse --short "$BASE_SHA")

Conflict policy: conflicts INSIDE rethink-tv-owned files (app/src/tv/,
docs/, scripts/, .github/workflows/android-tv*.yml, .github/workflows/
upstream-sync.yml, the rethink-tv blocks in app/build.gradle, the
README fork notice) are resolved by keeping the rethink-tv version.
Conflicts OUTSIDE that set indicate an upstream structural change
that the TV flavor depends on — leave them for a human and document
in docs/upstream-sync.md.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

say "Merging $UPSTREAM_REMOTE/$UPSTREAM_BRANCH ($UPSTREAM_SHORT) into $SYNC_BRANCH"
if ! git merge --no-ff --no-edit -m "$MERGE_MSG" "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"; then
  cat <<EOF >&2

Merge conflict.

Conflicted files:
$(git diff --name-only --diff-filter=U)

Suggested next steps:

  1. Inspect each conflicted file. If it is in the rethink-tv-owned
     set listed in docs/upstream-sync.md, keep our version.
  2. Resolve, then run:
       git add -A
       git commit --no-edit
  3. Optionally re-run this script with --push and --open-pr to
     publish the merge.

EOF
  exit 2
fi

# Verify we did not accidentally lose any rethink-tv-owned file.
REQUIRED=(
  "README.md"
  "app/build.gradle"
  "app/src/tv/res/values/strings.xml"
  ".github/workflows/android-tv.yml"
  ".github/workflows/android-tv-release.yml"
  "scripts/sync-upstream.sh"
  "docs/release.md"
)
MISSING=()
for f in "${REQUIRED[@]}"; do
  [ -e "$f" ] || MISSING+=("$f")
done
if [ ${#MISSING[@]} -gt 0 ]; then
  die "sync removed rethink-tv-owned files: ${MISSING[*]} — bailing out"
fi

# Verify the rethink-tv build.gradle markers survived.
if ! grep -q 'rethink-tv fork: Android TV flavor' app/build.gradle; then
  die "rethink-tv flavor block missing from app/build.gradle after merge — bailing out"
fi
if ! grep -q "sourceSets {" app/build.gradle || ! grep -q "src/full/java" app/build.gradle; then
  die "rethink-tv inherit-from-full sourceSets block missing from app/build.gradle after merge — bailing out"
fi

say "Merge clean. $SYNC_BRANCH is now $(git rev-parse --short HEAD)"

if [ "$PUSH" -eq 0 ]; then
  cat <<EOF

Merge committed locally on $SYNC_BRANCH.
To push and open a PR:

    scripts/sync-upstream.sh --open-pr

To push only (no PR):

    git push --force-with-lease $ORIGIN_REMOTE $SYNC_BRANCH

EOF
  exit 0
fi

say "Pushing $SYNC_BRANCH to $ORIGIN_REMOTE (force-with-lease)"
git push --force-with-lease "$ORIGIN_REMOTE" "$SYNC_BRANCH"

if [ "$OPEN_PR" -eq 1 ]; then
  if ! command -v gh >/dev/null 2>&1; then
    die "gh CLI not found — install from https://cli.github.com/ or push manually"
  fi
  say "Opening PR $SYNC_BRANCH → $BASE_BRANCH via gh"
  EXISTING=$(gh pr list --head "$SYNC_BRANCH" --base "$BASE_BRANCH" --state open --json number --jq '.[0].number' 2>/dev/null || true)
  if [ -n "$EXISTING" ]; then
    say "PR #$EXISTING is already open; pushed updates will appear on it automatically"
  else
    gh pr create \
      --base "$BASE_BRANCH" \
      --head "$SYNC_BRANCH" \
      --title "chore(sync): merge upstream celzero/rethink-app@${UPSTREAM_SHORT}" \
      --body "Automated upstream sync via \`scripts/sync-upstream.sh\`.

* Upstream: \`${UPSTREAM_REMOTE}/${UPSTREAM_BRANCH}\` = \`${UPSTREAM_SHA}\`
* Fork base: \`${ORIGIN_REMOTE}/${BASE_BRANCH}\` = \`$(git rev-parse --short "$BASE_SHA")\`

Reviewer checklist:

- [ ] \`📺 Android TV CI\` is green
- [ ] \`🫣 Android CI\` (upstream's phone CI) is green
- [ ] No new upstream files in \`app/src/full/\` reference symbols
      that the TV flavor depends on without an \`app/src/tv/\` override
- [ ] Conflicts (if any) were resolved per the rules in
      \`docs/upstream-sync.md\`"
  fi
fi
