# rethink-tv release pipeline

This document is for maintainers of `ezelab/rethink-tv`. It describes
how the signed-release pipeline works, how to generate a release
keystore, how to register the keystore with GitHub Actions secrets,
and how to cut a tagged release. The pipeline produces a signed
`fdroidTvRelease` APK and attaches it to a GitHub Release.

It is intentionally orthogonal to upstream `celzero/rethink-app`'s
release tooling — none of the changes documented here modify upstream
files in `app/src/main/`, `app/src/full/`, or upstream's
`.github/workflows/android.yml`.

---

## Pipeline overview

```
┌───────────────────────────────────┐
│ git push origin v0.0.1-tv         │
└────────────────┬──────────────────┘
                 │
                 ▼
┌───────────────────────────────────┐
│ .github/workflows/                │
│   android-tv-release.yml          │ ← triggers on tag `v*-tv`
│                                   │   or `tv-v*`
│ ┌───────────────────────────────┐ │
│ │ 1. base64-decode keystore     │ │ ← from secret
│ │    secret to runner tmpfs     │ │   TV_RELEASE_KS_BASE64
│ ├───────────────────────────────┤ │
│ │ 2. assembleFdroidTvRelease    │ │ ← env-var-based signing,
│ │    (signed with tvRelease     │ │   wired in app/build.gradle
│ │    signingConfig)             │ │
│ ├───────────────────────────────┤ │
│ │ 3. upload APK as workflow     │ │
│ │    artifact (30-day retention)│ │
│ ├───────────────────────────────┤ │
│ │ 4. softprops/action-gh-release│ │
│ │    creates GitHub Release     │ │
│ │    with the APK attached      │ │
│ └───────────────────────────────┘ │
└───────────────────────────────────┘
```

The signing configuration is conditionally appended in
`app/build.gradle`. If the four `TV_RELEASE_KS_*` env vars are missing,
the release build type stays unsigned (upstream's default). This
preserves upstream's existing local-signing path via
`keystore.properties` and the `signingConfigs.config` block.

---

## One-time setup

### 1. Generate a release keystore

Run this **on a maintainer workstation that is _not_ shared**, never
in CI:

```bash
keytool -genkeypair \
    -v \
    -keystore rethink-tv-release.jks \
    -alias rethink-tv-release \
    -keyalg RSA \
    -keysize 4096 \
    -validity 36525 \
    -storetype PKCS12
```

`keytool` will prompt for:

* a keystore passphrase  → save as `TV_RELEASE_KS_STORE_PASSPHRASE`
* a key passphrase       → save as `TV_RELEASE_KS_PASSPHRASE`
* identifying info (CN/OU/O/L/ST/C) — fill in whatever your project
  publishes. F-Droid does not care about the DN; users see the
  certificate fingerprint.

After this:

```bash
keytool -list -v -keystore rethink-tv-release.jks
```

…to print the cert fingerprints. Save those somewhere safe — they
will be required by F-Droid and by Google Play (Play won't be used
for v1 but the same key applies if the app is ever published there).

> ⚠️ **Back this file up offline.** Losing it means existing installs
> can never be upgraded — F-Droid (and Play) refuse updates with a
> different signing key. Two redundant offline copies, encrypted at
> rest, are the minimum.

### 2. Encode the keystore as a single-line secret

GitHub Actions secrets are strings, so the binary keystore must be
base64-encoded:

```bash
base64 -i rethink-tv-release.jks | tr -d '\n' > rethink-tv-release.jks.b64
wc -c rethink-tv-release.jks.b64    # sanity check: should be ~10–30 KiB
```

(Without `-w 0` on Linux, GNU base64 inserts line breaks; the
`tr -d '\n'` strips them so the secret is a single line.)

### 3. Register the four GitHub Actions secrets

In **Settings → Secrets and variables → Actions → Repository secrets**
on `https://github.com/ezelab/rethink-tv/settings/secrets/actions`,
create:

| Secret name                       | Value                                            |
|-----------------------------------|--------------------------------------------------|
| `TV_RELEASE_KS_BASE64`            | contents of `rethink-tv-release.jks.b64`          |
| `TV_RELEASE_KS_ALIAS`             | `rethink-tv-release` (from the `keytool` command) |
| `TV_RELEASE_KS_PASSPHRASE`        | the key passphrase                                |
| `TV_RELEASE_KS_STORE_PASSPHRASE`  | the keystore passphrase                           |

The CI workflow reads these via `${{ secrets.TV_RELEASE_KS_* }}` and
hands them to Gradle as environment variables. They never appear in
logs (GitHub redacts them).

### 4. Verify the release path with a dry-run

Before tagging a real release, run the workflow manually:

1. Go to **Actions → 📺 Android TV Release**
2. Click **Run workflow** with `dry_run = true` (the default)
3. The workflow assembles `fdroidTvRelease`, attaches the signed APK
   as a workflow artifact, and skips the GitHub Release step.

If `TV_RELEASE_KS_BASE64` is not set the APK uploaded as the
artifact will be unsigned (the workflow logs a `::warning::`). This
is a useful smoke test on its own — it proves `proguard` / R8 can
shrink and minify the inherited `full/` UI without errors.

---

## Cutting a release

Once a `v*-tv` tag is pushed:

```bash
# from a clean, in-sync `main`
git fetch origin
git checkout main
git pull --ff-only

# pick the next version — `v0.0.1-scaffold-tv` is reserved for the
# Phase 3 verification release that ships before any TV UI work.
git tag -s v0.0.1-scaffold-tv -m "rethink-tv v0.0.1-scaffold"
git push origin v0.0.1-scaffold-tv
```

The release workflow:

1. Builds the signed `fdroidTvRelease` APK.
2. Uploads it as a workflow artifact (30-day retention) so failed
   release-step runs can still recover the binary.
3. Creates a GitHub Release with the APK attached and auto-generated
   release notes.
4. Marks the release as a `prerelease` if the tag ends in
   `-scaffold`, `-alpha`, `-beta`, or `-rc`.

After the release is up, verify:

```bash
gh release view v0.0.1-scaffold-tv \
    --repo ezelab/rethink-tv \
    --json assets,isDraft,isPrerelease,tagName
```

The asset list must include `*.apk` matching
`app-fdroid-tv-*-release.apk` (universal + per-ABI splits — upstream's
split-ABI logic is intentionally preserved for the tv flavor too).

---

## Architecture notes

* **Why a separate `tvRelease` signingConfig and not upstream's
  `config`?** Upstream's `config` signingConfig reads from
  `keystore.properties` on the maintainer's filesystem. Wiring that
  through to CI would require either committing a stub
  `keystore.properties` (rejected — secrets leakage risk) or
  rewriting upstream's signing block (rejected — increases
  upstream-sync diff). The chosen approach appends a new
  signingConfig only when env vars are present, so upstream's path
  remains untouched.

* **Why F-Droid channel for the first release?** F-Droid does not
  require Google Play services, the upstream build already produces
  a deGoogled APK for the `fdroid` flavor, and F-Droid is the
  long-term primary distribution channel for `rethink-tv` (see
  `plan.md`). Other channels (`play`, `website`) are reachable via
  the same workflow by changing the `VARIANT` env, once the
  corresponding Google Play / website infrastructure is set up.

* **Why `prerelease` for `-scaffold` tags?** The `v0.0.1-scaffold-tv`
  release exists solely to validate the fork-distribution pipeline
  end-to-end. It ships upstream's phone UI under a leanback
  launcher (unusable as a TV app yet), so it must not surface as a
  recommended download in any UI that lists "latest stable".
