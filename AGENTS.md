# Agent guide — MediaDroid

Working agreement for **all** coding agents (Claude Code, Codex, Copilot, …) and human contributors
in this repository. These rules are not optional. This file is the per-repo distillation of the
house standard; the "What this is" section is written for this project and the rest is the standard.

## What this is

An Android media player: Kotlin, Jetpack Compose, Gradle (wrapper 9.6.0). It plays movies and shows
straight from local storage, reading the Kodi-convention `.nfo` sidecars for titles, plots and art and
`folder.jpg` / `poster.jpg` for covers — no server, no account, no re-scraping, no re-encoding. The
Gradle modules are `:app` (the Compose app) and three plain-JVM cores: `:core:nfo` (NFO XML parsing,
the `SxxExx` / `NxNN` name convention, Kodi art discovery), `:core:library` (domain models plus
search / filter / group-by-decade) and `:core:player` (the binge queue). The cores carry no Android
dependency on purpose: the parsing, search and binge logic are pure and unit-tested without a device,
and the screens sit on top of them. The bar is a household where nobody has to be taught the app —
usable by a six-year-old and a grandparent, instant even on cheap hardware.

**The README is a forward-looking specification** — it is authoritative. The app is an early skeleton:
the cores are built and tested, the UI is next. When code and spec disagree, the spec wins unless the
spec itself is being revised in the same change; cite the relevant section in the commit body.

## Commits

- **Group changes semantically** — one requirement/concern per commit, with a detailed body.
- **Every subject line starts with a prefix**: `+` added · `-` removed · `*` changed · `#` bug fixed
  · `!` critical todo. The changelog is generated from these prefixes, so they are load-bearing, not
  decoration.
- Never start a subject with "fix"/"bugfix"/"changed"/"modified".
- **No AI traces anywhere**: no `Co-Authored-By` AI lines, no "Generated with" footers, no agent
  mentions in messages, comments, authorship, or docs. Author identity is the maintainer's own.

## The loop (always, in this order)

1. **Before committing**: `./gradlew assembleDebug` and `./gradlew test` until green (the pull-request
   gate runs the same). New behaviour is test-first (TDD): add the failing test, then make it pass.
   While iterating, `./gradlew :core:nfo:test` — the module you are in — is the fast tier; run the
   whole `./gradlew test` before you push.
2. **Commit** (rules above) and **push**.
3. **Wait for CI**; a merged pull request triggers the nightly on `main` automatically (dated
   prerelease + GFS prune). Fix and loop until everything is green.

Stable releases are **manual** (`gh workflow run release.yml`) — never cut one unless explicitly asked.

## Tests come in two tiers

The fast tier is what `./gradlew test` runs: the pure-JVM unit tests in `:core:nfo`, `:core:library`
and `:core:player`. They finish in seconds because the cores import no Android — that is the whole
point of keeping parsing, search and binge logic out of `:app`. A test belongs to the fast tier unless
it genuinely needs a device, and that direction is deliberate: the one slow test somebody leaves on the
fast path silently turns a seconds-long smoke into a minutes-long one.

- **A fast-tier test finishes in well under a second.** If yours does not, make it so or move it off
  the fast path. A fast tier that creeps up to twenty minutes stops being read, and then people open
  pull requests just to learn whether their code compiles — which runs the expensive tier ten times
  per change instead of once.
- **Anything that needs an emulator or a real device — instrumented UI, a `connectedCheck` — is the
  slow tier** and runs only on the pull-request gate, never on the branch smoke. Keep the logic it
  exercises in a core where a plain unit test can reach it; reserve the device tier for what cannot run
  without one.
- **Opting out defers a test; it never skips one.** The pull request runs everything. The tiers decide
  *when*, not *whether*. After changing what a tier runs, confirm every kind of test still has a step
  that runs it — a category excluded from the smoke with no step of its own executes nowhere, and
  nothing reports that.

## Sourcing an implementation

Never write a format, codec, cipher or compression scheme out of your own understanding when somebody
has already got it right. Work **down** this ladder, stop at the first rung that applies, and say in the
commit body which rung you used and why the ones above it did not.

**1 — Licence-compatible source you can take.** MIT, BSD, Apache-2.0, LGPL, public domain: anything
this repository's LGPL-3.0-or-later can absorb. Search for it before writing anything. There are two
ways to take it and the choice is not cosmetic:

- **Vendor it** — a verbatim subtree under `Vendored/<Library>/` next to its own `LICENSE.txt`, kept in
  the upstream's own formatting. Do *not* restyle it: the whole point is that the next upstream version
  still applies cleanly, and a reformatted copy conflicts on every update. Keep it in its own package,
  clearly marked as vendored, rather than folding it into the app's own code.
- **Convert it** — carry the algorithm across into this codebase properly. Converted code is *our*
  code, so every rule under "Code conventions" applies to it, including the official Kotlin style
  wherever that says the same thing more plainly. Do not restate those rules here or anywhere else: one
  stale copy of them is how a guide spends years asking for an indent the code never used. A conversion
  that still reads like C, or like a decompiler's output, is not finished.

Either way, record where it came from — a `THIRD_PARTY_NOTICES.md` in the module, or a
`THIRD-PARTY-NOTICE.<Name>.txt` beside the code. Attribution is a licence term, not a courtesy.

**2 — Licence-incompatible source: use it, but not its code.** GPL where we ship LGPL, anything
proprietary, anything with no licence at all. Read it and *build material from it*: a written
specification, a set of test cases, and a third-party oracle you can run to produce expected output.
Then implement from that derived material. Do not paste it, do not transliterate it line by line, and
do not carry its file layout or its identifier names across — that is still the same copy. Kodi's own
NFO reader is GPLv3: read it for the spec, the cases and an oracle, never for code to lift.

**Constants are not expression.** Tables, magic numbers, the exact element and field names a format
uses, the art base-names a scraper writes (`folder.jpg`, `tvshow-poster`, `clearlogo`): copy them
exactly, from whichever source is authoritative, on every rung of this ladder. A field name somebody
worked out for themselves is the defect that nothing catches until real `.nfo` files arrive. Where a
value is arbitrary-but-agreed, matching it *is* the specification.

**3 — Original reference material.** The specification, the standard (RFC, ITU-T, ISO, ECMA), the
academic paper, the vendor's own documentation, the format author's write-up. For the NFO and art
conventions that is the Kodi wiki's own NFO and Artwork pages — the normative text, not a blog's
summary of it; where the two disagree, the normative text wins and the disagreement is worth a comment.

**4 — Other trusted sources.** Reverse-engineering write-ups, articles and blog posts by named people
with a track record, and long-lived project wikis that cite their evidence.

**5 — Untrusted material, by agreement only.** Forum answers, unattributed gists, wiki edits with no
provenance. Only when nothing above exists, and only where several *independent* sources agree —
majority vote, discounting the ones that plainly copied each other. Treat the result as a hypothesis
and mark it as one in the code.

Whatever rung you land on, the finished implementation is judged the same way: it must agree with an
oracle or with real files, not merely compile and look plausible. When a licence-incompatible
implementation was your oracle, keep the comparison as a test wherever it can run, and where it cannot,
commit the captured expected output with a note saying what produced it.

## Code conventions

- Kotlin, official style (`kotlin.code.style=official`): 4-space indent for `.kt`/`.kts` (the
  `.editorconfig` sets it; every other file in the repo is 2-space), K&R braces (the opening brace ends
  the line that opens the block; `} else` and `} catch` continue the closing brace's line), max line
  length 120, LF endings, final newline.
- The `:core:*` modules are plain JVM Kotlin — no `android.*` imports there. Domain models, parsing and
  binge logic live in the cores; `:app` holds Compose screens and platform glue only. This is what keeps
  the fast tier device-free, and it is a rule, not a preference.
- KDoc on public members; trailing commas where they read cleanly. No per-file licence headers in `.kt`.
- Versions come from the build date, never git tags and never a commit count: `app/build.gradle.kts`
  computes `versionCode` as `YYYYMMDD` and `versionName` as `YYYY.MM.DD` from the day the build runs, so
  the number only ever moves forward and no tag feeds it. The repo-level marker is the same date:
  releases tag `vYYYYMMDD`, nightlies `nightly-YYYYMMDD`. This repo carries no `scripts/` — the version,
  changelog and prune scripts live in `Hawkynt/RepositoryTemplate`.

## README & repo conventions

- Standard frame: title → grouped shields.io badges → one-line `>` blockquote → for a GUI repo one
  hero image → body → `## ❤️ Support` (Sponsors + PayPal, mirrors `.github/FUNDING.yml`) →
  `## 📜 License`. The body is a funnel: Vision and Features say what the thing is, Installation and
  Quick start get the reader running, the free band goes deeper, and what a *contributor* needs —
  dependencies, building, contributing, issues — comes last.
- **The canonical section order and the emoji vocabulary live in
  [`repo-readme/README.md`](https://github.com/Hawkynt/RepositoryTemplate/blob/main/repo-readme/README.md),
  and the `repo-readme` action enforces them.** One emoji means one thing, a section shared with the
  package convention carries the same name and emoji on both sides, and `## 📜 License` is the last
  heading in the file. The tables are not reproduced here for the reason given under "Sourcing an
  implementation": one stale copy is how a guide spends years asking for a convention the repo never
  used.
- **GUI repositories document the whole primary UI, not merely the startup screen.** Every main screen
  that represents a distinct user workflow needs a committed screenshot — for MediaDroid the browse
  wall (the hero), the details screen, the player with its binge queue, and the filters/facets. Tiny
  confirmations and duplicate variants do not need their own image. MediaDroid sets `gui: true` on the
  repo-readme check in `ci.yml`, which makes both the single hero image under the pitch and the
  `## 🖼️ Screenshots` tour required rather than merely expected.
- GUI screenshots are **generated documentation**, never hand-maintained glamour shots. The app must
  expose a documentation/demo mode that populates each screenshot surface with deterministic, plausible,
  visually useful demo data. Reuse the real Compose components, the real domain models, formatting and
  validation paths; do not paint fake rows or maintain a second mock UI solely for screenshots.
- Demo scenarios must be self-contained and reproducible in CI: fixed values, no personal/user data, no
  network dependency, no clock/random dependence unless fixed. Prefer representative variety — a mix of
  movies and shows, multiple seasons and episodes, watched and unwatched, missing year or genre — so the
  screenshots explain what the app can actually do.
- Screenshot regeneration belongs in a working-branch workflow that commits the changed files through
  `Hawkynt/RepositoryTemplate/commit-generated-file@v1`. Adding or materially changing a primary screen
  therefore includes its demo scenario, generated screenshot, README/docs reference, and CI generation
  step in the same change. Use descriptive kebab-case filenames and useful alt text.
- License is LGPL-3.0-or-later (full `LICENSE`); no per-file licence headers in `.kt` files.
- CI lives in `.github/workflows/{smoke,ci,_build,nightly,release}.yml`; the version, changelog and
  prune scripts live in `Hawkynt/RepositoryTemplate` — there is no `scripts/` here to keep in sync.
  Releases are dated `vYYYYMMDD`, nightlies `nightly-YYYYMMDD`.
