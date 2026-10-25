# Contributing

Thanks for helping improve **MediaDroid**. This guide covers building, testing, and the conventions
every change is expected to follow. Coding agents must also read [`AGENTS.md`](AGENTS.md).

## Prerequisites

- **JDK 21** (the cores pin `jvmToolchain(21)`) and an **Android SDK with platform 37**
  (`compileSdk = 37`). `local.properties` must point `sdk.dir` at that SDK; it is gitignored, so create
  it on your machine rather than committing it.
- `node` is only needed to run the repo-README check locally; CI provides it.

## Build

```bash
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper (9.6.0) fetches its own Gradle; AGP, Kotlin and the Compose BOM come from
`gradle/libs.versions.toml`.

## Test

```bash
./gradlew test                            # every module's unit tests
./gradlew :core:nfo:test                  # one module while iterating
```

Tests are JUnit 4 and live beside the code in each core's `src/test`. New behaviour is test-first: add
the failing test, then make it pass. Keep test data deterministic — hand the parsers literal NFO strings
and the query/queue functions literal models rather than reading fixtures off disk, so a test never
depends on the machine it runs on.

### The two tiers

The **fast tier** is what `./gradlew test` runs: the pure-JVM unit tests in `:core:nfo`, `:core:library`
and `:core:player`. They run in seconds because the cores carry no Android dependency — parsing, search
and binge logic are plain Kotlin that needs no device. That is the property to protect: a test belongs to
the fast tier unless it genuinely needs an emulator, and the one slow test somebody leaves on the fast
path quietly turns a seconds-long smoke into a minutes-long one.

| Tier | What | Runs on |
|---|---|---|
| Fast | `./gradlew test` — pure-JVM unit tests in the `:core:*` modules | branch push (`smoke.yml`) and the pull request |
| Slow | anything needing an emulator or device: instrumented UI, `connectedCheck` | the pull request only (`ci.yml`) |

**Opting out defers a test, it never skips one.** Everything runs on the pull request. The tiers decide
*when*, not *whether*.

Two rules that make the difference real:

- **A fast-tier test finishes in well under a second.** If it does not, either make it so or move it to
  the device tier. A fast tier that creeps toward twenty minutes stops being read, and then people open
  pull requests just to find out whether their code compiles — which runs the expensive tier ten times
  per change instead of once.
- **Keep the logic reachable without a device.** If a behaviour can be exercised through a core, test it
  there; reserve the device tier for what cannot run without one. After changing what a tier runs,
  confirm every kind of test still has a step that runs it — a category excluded from the smoke with no
  step of its own executes nowhere, and nothing reports that.

## Commit conventions

- One concern per commit, with a descriptive body.
- Subject lines start with a prefix — `+` added · `-` removed · `*` changed · `#` bug fixed ·
  `!` critical todo. The changelog is generated from these, so they are load-bearing. Never begin with
  "fix"/"changed"/"modified".
- Write everything as if authored by hand: no AI attribution anywhere.

## Code style

- Kotlin, official style: 4-space indent for `.kt`/`.kts` (set in `.editorconfig`; other files are
  2-space), K&R braces (`} else` and `} catch` on the closing brace's line), max line length 120, LF
  endings, final newline, KDoc on public members.
- The `:core:*` modules are plain JVM Kotlin with no `android.*` imports; keep domain models, parsing and
  binge logic there and leave Compose screens and platform glue in `:app`.

## GUI screenshots

MediaDroid is a GUI repo, so the repo-README check runs with `gui: true`: the README must carry one hero
image directly under the pitch, with no heading — the image that decides whether a reader keeps reading —
and a tour under `## 🖼️ Screenshots`. Both are checked. The primary surfaces are the browse wall (the
hero), the details screen, the player with its binge queue, and the filters/facets. Do not multiply
screenshots for trivial confirmation dialogs or visually identical variants.

Each documented surface needs an application-owned demo scenario: a documentation-only entry point that
opens the real Compose UI in a deterministic state and writes the image without operator interaction.
The exact mechanism is project-specific; these properties are not:

- Build the scenario from the application's real domain models (`Movie`, `Show`, `Season`, `Episode`) and
  real Compose components. Do not draw fake rows over the UI, stitch screenshots, or maintain a parallel
  mock screen.
- Populate enough plausible data to make the screenshot useful: a mix of movies and shows, several
  seasons and episodes, watched and unwatched states, posters and backdrops, missing year or genre where
  relevant. An empty browse wall is technically reproducible and practically useless.
- Keep it deterministic and private: fixed values, no personal data, no live network or cloud dependency.
- Make scenarios independently addressable so CI can capture each primary screen directly. Adding a new
  primary surface means adding its scenario and screenshot in the same change.
- Store generated images under `screenshots/` with descriptive kebab-case filenames, reference them from
  README/docs with useful alt text, and keep the surrounding text authoritative — screenshots complement
  documentation rather than replacing it.

Screenshot regeneration belongs in a working-branch workflow that commits the changed files through
`Hawkynt/RepositoryTemplate/commit-generated-file@v1`, and that job should sanity-check that each expected
image exists and is a plausible image before committing it.

## Continuous integration

Workflows live in `.github/workflows/`:

| Workflow | Trigger | Purpose |
|---|---|---|
| `smoke.yml` | push to a working (non-`main`) branch | The fast tier: assemble + `./gradlew test` on one Linux runner. Minutes. |
| `ci.yml` | pull request to `main` | The gate: build, full tests, and the repo-README check (`gui: true`). |
| `_build.yml` | called by `nightly`/`release` | Shared build step so both paths produce identical APK/AAB artifacts. |
| `nightly.yml` | on merge to `main` (the pull request already gated it) | Dated `nightly-YYYYMMDD` prerelease, GFS-pruned. |
| `release.yml` | manual dispatch | Cuts a dated `vYYYYMMDD` release. |

### Fast on push, comprehensive on the pull request

```
push to a working branch  ->  smoke.yml     fast: assemble + unit tests, one runner
pull request              ->  ci.yml        the gate: build + full tests + repo-README check
merge to main             ->  nightly.yml   the pull request already gated it; publish a nightly
```

`smoke.yml` and `ci.yml` are self-contained here — the template's shared reusable workflows are
.NET-specific — while `_build.yml` is the single build block that `nightly.yml` and `release.yml`
both call, so the two publish paths always ship identical artifacts.

Three properties of this split are not negotiable:

- **Every job declares `timeout-minutes`.** Without one a job inherits GitHub's six-hour default, so a
  wedged emulator or a hung Gradle daemon holds a runner for six hours and queues every other job behind
  it. Size it at roughly twice the observed successful run.
- **The pull request is the gate; the smoke is a courtesy.** The smoke exists so a push gets a fast
  answer without burning an emulator; correctness is decided on the pull request. Do not move a check
  onto the smoke that needs a device.
- **Every kind of test has somewhere it runs.** A test excluded from the smoke needs a step on the gate,
  or it executes nowhere. This is easy to get wrong by omission and invisible when you do.

Versions come from the build date, not git tags: `app/build.gradle.kts` computes `versionCode`
(`YYYYMMDD`) and `versionName` (`YYYY.MM.DD`) from the day the build runs, so the number only ever moves
forward and no tag feeds it; the repo-level marker is the same date (`vYYYYMMDD` / `nightly-YYYYMMDD`).
The versioning, changelog and prune scripts live in `Hawkynt/RepositoryTemplate` — there is no `scripts/`
directory here to keep in sync.

To validate workflow edits, [`actionlint`](https://github.com/rhysd/actionlint) is the recommended linter:

```bash
actionlint .github/workflows/*.yml
```

### When the repo-README check fails

`ci.yml` runs the `repo-readme` check with `gui: true`, which enforces the README's badge block, the
canonical section order, the emoji vocabulary, and — because this is a GUI repo — both the hero image
under the pitch and the `## 🖼️ Screenshots` tour. It reads one file and generates nothing, so there is no
artifact to fetch and no line endings to argue with; reproduce it in full with one command:

```bash
node ../RepositoryTemplate/scripts/repo-readme.mjs --check . --repo Hawkynt/MediaDroid
```

Every finding names the rule that produced it and the line it sits on, and CI reports them as annotations
on the diff. `--write` fixes the badge block for you:

```bash
node ../RepositoryTemplate/scripts/repo-readme.mjs --write . --repo Hawkynt/MediaDroid
```

The rest are prose decisions, which is why they are left to a person. The rules and the canonical order
are in
[`repo-readme/README.md`](https://github.com/Hawkynt/RepositoryTemplate/blob/main/repo-readme/README.md).

## Releases

Stable releases are cut manually by the maintainer:

```bash
gh workflow run release.yml
```

Never cut a release unless explicitly asked.
