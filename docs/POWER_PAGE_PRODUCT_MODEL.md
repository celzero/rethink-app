# Power Page Product Model

This document defines the recommended product model for the new `Power` tab.

The goal is to simplify the current Rethink UI without throwing away the engine it already gives us.

## Product stance

This app should be positioned as:

- app-first Internet control
- sharable blocking knowledge
- uBO-like network blocking for apps

This app should **not** be positioned as:

- full uBlock Origin for all apps
- a browser DOM or scriptlet blocker
- a replacement for browser-native cosmetic filtering

Reason: our current engine operates at Android DNS, VPN, firewall, and connection-tracking layers. It can block domains, IPs, ports, and app traffic, but it does not have page DOM, browser resource-type context, or decrypted HTTPS response bodies by default.

## Recommended core objects

### 1. App Pack

An `App Pack` is the sharable unit for one app.

It should contain:

- target app identity
- one or more contributor-authored blocklists for that app
- rationale and confidence for rules
- optional tags like `ads`, `tracking`, `social`, `video`, `adult`
- optional compatibility notes

It should not try to represent the user's whole device setup.

Examples:

- `Parrot Downloader / Ads`
- `Instagram / Reels Distraction Block`
- `YouTube / Tracking Reduction`

### 2. Corpus

The `Corpus` is background knowledge, not the main user-facing control surface.

It should contain:

- labeled IPs
- labeled domains
- app associations
- categories like `ad-network`, `tracker`, `cdn`, `analytics`
- confidence and rationale
- provenance from one or more packs / profiles

The corpus helps with:

- faster future analysis
- overlap detection across apps
- richer annotations in logs

The corpus should not directly become policy by itself. It is evidence, not the final active ruleset.

### 3. Profile

A `Profile` is the user-facing bundle.

It should contain:

- a name
- zero or more app packs
- optional global rules
- metadata like author, purpose, version, createdAt

Examples:

- `Exam`
- `Smooth Internet`
- `No Social Media`
- `Travel / Low Data`

Profiles are how users think. App packs are how contributors build. Corpus is how the system learns.

### 4. Active Setup

`Active Setup` is the resolved result currently applied to the device.

It should be computed from:

- enabled profiles
- manually imported app packs
- user overrides
- engine defaults

This distinction matters because users need to know:

- what they imported
- what is currently effective
- what rule came from where

## Merge semantics

We should define merge behavior before UI work.

### Precedence

Recommended precedence:

1. user explicit overrides
2. enabled profiles
3. imported standalone app packs
4. app defaults
5. corpus annotations only

The corpus should never silently block something on its own.

### Rule identity

Rules should dedupe by stable keys.

- domain rule key: `packageName + domain + ruleType`
- IP rule key: `packageName + ip + port + protocol + ruleType`

### Conflict handling

For now, prefer simple behavior:

- explicit allow wins over profile block only if user created the allow locally
- explicit local block always wins
- duplicate blocks merge provenance and keep the highest confidence
- imported rules should preserve source profile and source app-pack ids

### Explainability

Every effective rule should be explainable in UI and logs:

- why is this blocked?
- which profile added it?
- which app pack contributed it?
- what corpus labels are associated with it?

If we cannot explain a block, users will not trust profile sharing.

## Power tab information architecture

`Power` should become the default start destination.

The current `Home` tab is too engine-centric. `Power` should be user-outcome-centric.

Recommended sections:

### 1. Protection status

Top strip:

- protected / paused / off
- number of active profiles
- number of active app packs
- quick action to save current setup

### 2. Active profiles

Primary list:

- enabled profiles
- short description
- number of apps affected
- number of rules contributed
- last updated

Actions:

- enable / disable
- inspect contents
- duplicate as my profile

### 3. Discover profiles

Secondary list:

- suggested profiles
- featured community profiles
- imported but inactive profiles

Actions:

- preview
- merge into my setup
- import from file

### 4. Top controlled apps

This section grounds the system in familiar app names.

Show:

- app name
- status summary like `ads blocked`, `tracking reduced`, `fully blocked`
- active pack count
- quick inspect

### 5. Recent wins

Human-facing value signal:

- ads blocked
- trackers blocked
- distracting apps suppressed
- newly annotated destinations

This is better than surfacing raw DNS/proxy complexity on the front page.

### 6. Advanced entry point

Keep a smaller advanced section linking out to:

- Firewall
- DNS
- Proxy
- Logs

This keeps the engine available without making it the default mental model.

## Navigation recommendation

Do not delete `DNS`.

Recommended nav direction:

- `Power` as default tab
- keep `Stats`
- keep `Configure`
- keep `About`

Where `Home` goes:

- either rename `Home` to `Power` and repurpose the fragment
- or add a new `PowerFragment` and demote the old `Home` screen into `Advanced` later

Recommended first move:

- add a new `PowerFragment`
- make it the start destination
- leave current `HomeScreenFragment` reachable from `Configure` or `Advanced`

That is safer than rewriting the current home screen in place.

## uBO compatibility: what is realistic

We should target a compatibility subset, not a full clone.

### Good fit

These are realistic to support or adapt:

- hosts-style domain entries
- plain domain block entries
- domain allowlist entries
- category-based curated lists
- app-scoped IP/domain rules
- sharable network-only community packs

### Possible later, with care

These may be partially supportable if we build a parser and map only safe cases:

- a subset of ABP / uBO network filters that reduce to hostname-level blocking
- simple exception rules
- trusted curated filter subscriptions that we convert into our own normalized rule model

### Poor fit or out of scope

These do not map well to the Android VPN/firewall layer:

- cosmetic filters
- procedural cosmetic filters
- scriptlet injection
- HTML filtering
- response-header filtering
- DOM removal or restyling
- browser request-type semantics like `image`, `script`, `xhr` for arbitrary Android apps
- URL-path-level rules for encrypted HTTPS app traffic unless we do deeper interception

## Filter-list source strategy

Do not say "we support uBO lists" unless we can clearly define the subset.

Better phrasing:

- `Supports a network-filter subset inspired by uBO / ABP-style lists`

Recommended import strategy:

1. Start with hosts-style and hostname blocklists.
2. Add a normalization pipeline that accepts only rules we can safely map.
3. Reject unsupported rules explicitly and report counts.
4. Preserve source and license metadata for imported lists.

## Licensing caution for filter lists

This area needs deliberate handling.

- uBlock Origin code is GPL-3.0.
- Many uBO-associated filter lists have mixed licenses.
- Some are GPL, some Creative Commons, some public-domain-like, some non-commercial, and some unclear.

So:

- do not copy uBO code into this app casually
- do not bundle third-party filter lists until their licenses are reviewed
- if we import external lists, we need source attribution and license tracking per list

## Open-source reality check

Closest existing open-source Android blockers are still more limited than full uBO:

- AdAway: great for hosts / DNS-style blocking
- personalDNSfilter: DNS-focused filtering
- Rethink: strongest current foundation here because it already combines DNS, firewall, logs, and per-app control

That means our best path is not to "embed uBO", but to build a better profile and knowledge layer on top of Rethink's engine.

## Recommended implementation sequence

### Phase 1

- Introduce `PowerFragment`
- Make it the default tab
- Show active profiles, suggested profiles, and top controlled apps

### Phase 2

- Formalize `App Pack`, `Profile`, and `Active Setup` data models
- Add profile storage and merge logic
- Add save-current-setup flow

### Phase 3

- Surface corpus annotations in logs and app details
- Show provenance for effective blocks

### Phase 4

- Add import pipeline for a supported hostname/network-filter subset
- Keep unsupported filter syntax visible in import reports

## Decision summary

Recommended decisions for now:

- `Power` becomes the default product surface.
- `Firewall` remains the main engine under the hood.
- `DNS` stays in the engine but moves out of the primary front-page story.
- `Profile` becomes the main user concept.
- `App Pack` becomes the main sharable building block.
- `Corpus` remains supporting intelligence, not direct policy.
- We target a realistic network-filter subset, not full uBO compatibility.
