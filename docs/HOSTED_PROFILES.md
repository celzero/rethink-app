# Hosted Profiles

This document defines how a Power `Profile` should be authored, hosted, and discovered online.

The goal is to make profiles:

- easy to create
- easy to publish on plain HTTPS
- easy for the app to fetch from a URL
- easy to evolve without breaking older clients

This is intentionally closer to `static JSON packages + an index` than to a full package manager.

## Product model

A hosted profile is a sharable bundle of:

- global domain blocklist entries
- global IP blocklist entries
- app-specific domain blocklist entries
- app-specific IP blocklist entries
- Rethink local blocklist selections

These five inputs together make up a `Profile`.

## Why not full npm?

We do not need:

- transitive dependencies
- package install scripts
- registry auth
- mutable package tags
- lockfiles

What we do want is:

- a stable URL for a browsable registry
- a stable URL for each profile artifact
- versioned profile artifacts
- metadata for trust, attribution, and preview

So the recommended design is:

1. one `registry` JSON file
2. one `profile` JSON file per profile version
3. optional README / homepage pages next to them

## Supported online objects

### 1. Power Profile document

A single importable profile artifact.

Recommended top-level fields:

- `kind`
  Use `"power-profile"`.
- `schemaVersion`
  Start with `1`.
- `version`
  Profile package version, for example `"1.0.0"`.
- `publishedAtEpochMs`
  Publish timestamp.
- `license`
  License for the profile data.
- `homepageUrl`
  Human-readable page for the profile.

Then include the current importable payload fields that already map to the app's portable format:

- `id`
- `name`
- `description`
- `meta`
- `provider`
- `sourceSummary`
- `sourceDocUrl`
- `sourceTokens`
- `generatedAtEpochMs`
- `supportedRuleKind`
- `domains`
- `ips`
- `apps`
- `localBlocklistTagIds`

Important compatibility note:

The current app-side portable importer already ignores unknown JSON keys. That means we can safely add `kind`, `schemaVersion`, `version`, `license`, and other metadata without breaking the existing file format.

### 2. Power Profile registry

A browsable index of profile packages.

Recommended top-level fields:

- `kind`
  Use `"power-profile-registry"`.
- `schemaVersion`
  Start with `1`.
- `id`
  Registry id, for example `"app-horse-community"`.
- `name`
- `description`
- `homepageUrl`
- `generatedAtEpochMs`
- `profiles`

Each `profiles[]` entry should contain:

- `id`
- `name`
- `description`
- `provider`
- `version`
- `artifactUrl`
- `homepageUrl`
- `readmeUrl`
- `sha256`
- `publishedAtEpochMs`
- `sourceTokens`
- `supportedRuleKind`
- `estimatedRuleCount`

The app can later support two URL flows:

1. user pastes a direct profile JSON URL
2. user pastes a registry URL and browses its contents

## Authoring rules

### Profile identity

Use stable ids.

Recommended style:

- `smooth-browsing`
- `safe-beautiful-internet`
- `app-horse`

Do not encode version numbers into `id`.
Version should live in the `version` field and in the artifact filename.

### Versioning

Use semver-like version strings:

- `1.0.0`
- `1.1.0`
- `2.0.0`

Recommended meaning:

- patch: metadata fix or no rule-shape change
- minor: additive rules / wider coverage
- major: breaking behavior or schema change

### Artifact filenames

Recommended pattern:

- `profiles/<profile-id>/<profile-id>.power-profile.v1.json`
- `profiles/<profile-id>/<profile-id>-1.0.0.power-profile.v1.json`

The important thing is:

- stable enough to link
- clearly versioned
- clearly machine-readable

### App-specific entries

For app-level rules, every `apps[]` entry should include:

- `packageName`
- `appName`
- `firewallStatus`
- `connectionStatus`
- `domainRules`
- `ipRules`

This allows one hosted profile to bundle multiple apps and multiple app-packs.

### Rethink blocklists

`localBlocklistTagIds` should be used only for profiles that intentionally depend on Rethink's own on-device blocklist catalog.

That is useful for:

- curated Rethink-powered profiles
- profiles like `Safe and Beautiful Internet`

But these ids are only portable across devices that have the same Rethink blocklist catalog.

So for public community sharing:

- prefer explicit `domains`, `ips`, and `apps` rules when possible
- use `localBlocklistTagIds` when you intentionally want Rethink-native behavior

## Hosting recommendations

Use plain HTTPS static hosting.

Good options:

- GitHub Pages
- `raw.githubusercontent.com`
- Cloudflare R2
- S3
- any static site host

Recommended layout:

```text
/registry/profile-registry.v1.json
/profiles/app-horse/app-horse.power-profile.v1.json
/profiles/app-horse/README.md
```

Best practice:

- keep the registry URL stable
- keep artifact URLs immutable per version
- publish checksums in the registry

## App fetch model

Recommended future app behavior:

### Direct import by URL

1. user pastes a URL
2. app fetches JSON
3. if `kind = power-profile`, preview and import it
4. if `kind = power-profile-registry`, show its browse UI

### Registry browse

1. user adds a registry URL
2. app fetches and caches the registry
3. app lists profile entries
4. user taps one entry
5. app fetches the artifact URL and previews it before import

### Caching

Recommended future cache data:

- `etag`
- `lastModified`
- `fetchedAtEpochMs`
- `sha256`

This will make refresh and update checks cheap.

## Trust and safety

Public profile hosting means the app must treat profiles as data from outside.

Recommended checks before import:

- profile `kind` and `schemaVersion`
- maximum rule count
- package name shape
- domain normalization
- valid public IP normalization
- checksum match if registry provides `sha256`

Recommended UI trust signals:

- provider name
- source summary
- source tokens
- homepage URL
- version
- publish time

## Relationship to the global corpus

The global corpus should stay separate from the profile package.

Reason:

- corpus is evidence and labeling
- profile is policy

A future hosted corpus format can exist, but it should not be required to consume a profile package.

For now, a profile should remain self-contained.

## Current recommendation

Build the online sharing system in this order:

1. support direct URL import of a single `power-profile` document
2. support browsing a `power-profile-registry`
3. add update checks using `sha256` and timestamps
4. later add signatures if we need stronger trust guarantees

That gives us an npm-like experience while staying simple enough to host from a normal Git repo.
