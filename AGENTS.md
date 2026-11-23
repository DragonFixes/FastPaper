> **Note:** This file was written specifically for Claude (Claude Code), so
> some wording may not fit other agents exactly — e.g. Claude-specific tool
> names, skills, or features. Interpret those in terms of your own equivalents.
>
> The master copy of the global rules these instructions build on lives at
> `{USER HOME}/.claude/CLAUDE.md` (e.g. `C:\Users\<user>\.claude\CLAUDE.md`).
> Read it as well; rules there apply unless this file overrides them.

# FastPaper: instructions for coding agents

## What this repository is

FastPaper is a performance fork of [Paper](https://github.com/PaperMC/Paper)
(Minecraft 26.3). It is not Paper and is not supported by PaperMC. Its
performance work comes from the Swift patches by Swift-ality; the rule for
those is that gameplay stays identical (see `changes.md`).

Layout (paperweight):

| Path | What |
|---|---|
| `paper-api/` | Bukkit/Paper API source |
| `paper-server/src/main/java/` | Server code owned by the fork (Paper + Swift, e.g. `io.papermc.paper.swift`) |
| `paper-server/patches/sources/` | Per-file patches to Minecraft sources |
| `paper-server/patches/features/` | Larger feature patches (the numbered Swift patches) |
| `paper-server/patches/resources/` | Patches to Minecraft resources |
| `paper-server/src/minecraft/` | Generated, patched Minecraft working tree (its own git repo; created by `applyPatches`) |
| `changes.md` | Every Swift change: what it does, its `-Dswift.*` switch, measured gain |
| `docs/` | Design notes and perf candidates |
| `gametests/` | Gameplay-parity tests (datapacks, network bots, tools) |
| `scripts/` | Helper scripts (see below) |
| `deploy/` | Built server jar from `build-deploy` (git-ignored) |

## Branches and remotes

| Name | What it is |
|---|---|
| `develop` | Active FastPaper development. Branched from `upstream/swift-invasive`. Work and commit here. |
| `main` | Mirror of PaperMC/Paper `main`. Do not develop on it; only fast-forward it from `paper/main`. |
| remote `origin` | `git@github.com:DragonFixes/FastPaper.git`, the FastPaper repository |
| remote `paper` | `https://github.com/PaperMC/Paper`, upstream Paper |
| remote `upstream` | `git@github.com:Swift-ality/Paper.git`, the Swift fork |
| `upstream/swift` | Swift's conservative patches only |
| `upstream/swift-invasive` | Superset of `swift` plus the invasive patches; `develop`'s base |

Never push to `paper` or `upstream`. Push to `origin` only when the user asks.

### Pulling in Paper and Swift updates

```powershell
git fetch --all --prune
git switch main; git merge --ff-only paper/main     # keep the mirror current
git switch develop
git merge upstream/swift-invasive                    # Swift work (already on top of Paper)
# or, when Swift lags behind Paper:
git merge main
.\gradlew.bat applyPatches                           # resolve patch conflicts, then rebuildPatches
```

`scripts\sync-upstream.bat` wraps the fetch and merge (see below). After a
merge, apply patches, build, and run the game tests before committing.

## Naming

The project is **FastPaper**. Never call it Paper in user-facing text (README,
console messages, version output, changelogs). Java packages `io.papermc.*`,
the `paper-*` module names, `paper-*.yml` config files and the `paper-api`
artifact keep their names so plugins and existing servers stay compatible; do
not rename them.

## Helper scripts

Each script in `scripts/` is a `.ps1` (all logic) with a `.bat` launcher that
forwards its arguments. Run them from any directory; paths resolve from the
repository root.

| Script | What it does |
|---|---|
| `build-deploy` | Deletes `deploy\*.jar`, applies patches, runs `createPaperclipJar`, copies the jar into `deploy\` |
| `apply-patches` | `gradlew applyPatches` |
| `rebuild-patches` | `gradlew rebuildPatches` (after committing in `src/minecraft`); `-Fixup` runs `fixupSourcePatches` first |
| `run-server` | Runs the jar from `deploy\` in a server folder |
| `sync-upstream` | Fetches `paper` and `upstream`, reports ahead/behind, optionally merges a ref (`-NoFetch` skips the fetch; never pushes) |
| `clean` | Removes build output (and optionally `deploy\`) |

```powershell
scripts\build-deploy.bat                 # full build into deploy\
scripts\build-deploy.bat -SkipPatches    # patches already applied
scripts\build-deploy.bat -Clean          # gradle clean first
scripts\build-deploy.bat -DryRun         # show what would happen

scripts\apply-patches.bat
scripts\rebuild-patches.bat
scripts\rebuild-patches.bat -Fixup                    # fold fixup commits first

scripts\run-server.bat                                   # default memory, no GUI
scripts\run-server.bat -Memory 8G -AcceptEula
scripts\run-server.bat -ServerDir C:\Servers\test -Gui

scripts\sync-upstream.bat -DryRun                        # fetch and show what would merge
scripts\sync-upstream.bat -Merge upstream/swift-invasive
scripts\sync-upstream.bat -Merge paper/main -Force       # skip the confirmation prompt

scripts\clean.bat -DryRun
scripts\clean.bat -Deploy                                # also empty deploy\
```

## Patch workflow

`CONTRIBUTING.md` is Paper's guide and still applies to how patches are made.
The essentials (on Windows use `.\gradlew.bat`):

1. `applyPatches` builds the patched Minecraft tree in `paper-server/src/minecraft`.
2. Small per-file changes: edit files there, commit them in that repo as a
   fixup (`git commit --fixup`), then `fixupSourcePatches` and
   `rebuildPatches`.
3. Larger features: commit in `src/minecraft` as a new commit, then
   `rebuildPatches` to write it to `paper-server/patches/features/`.
4. Code that is not a Minecraft class lives directly in
   `paper-server/src/main/java` and needs no patch.
5. Always commit the regenerated patch files; `src/minecraft` itself is not
   tracked.

Every performance change must keep gameplay identical, have a `-Dswift.*`
(or FastPaper) switch to turn it off, and be documented in `changes.md`.
Changes that alter observable behavior must be off by default.

## Testing

Gameplay-parity tests boot a real server and run vanilla GameTests; see
`gametests/README.md` for details and gotchas.

```powershell
.\gradlew.bat :paper-server:runGameTests                              # whole suite
.\gradlew.bat :paper-server:runGameTests -PgameTests=swift:hopper*    # one selector
.\gradlew.bat :paper-server:runGameTests -PgameTestJvmArgs=-Dswift.verify=true   # shadow verify
.\gradlew.bat :paper-server:runGameTests -PgameTestJvmArgs="-Dswift.asyncEntities=true -Dswift.asyncEntities.minParallel=1"
```

Results go to `paper-server/build/gametest/report.xml` and `result.txt`. Run
the whole suite (and a shadow-verify run) before calling a performance change
done; run parallel-ticking modes several times, since races are probabilistic.
`.\gradlew.bat test` runs the unit tests.

## Performance research docs

Read these before picking the next performance change:

- [Leaf 26.3 Patch Audit](https://claude.ai/artifact/C45J61bVSK5qwPcGPNgsa1)
- [Entity MSPT on 26.3](https://claude.ai/artifact/WzonxN9xEb8z2Kond8cTSV)

## Commits and changelog

Follow the global rules in `{USER HOME}/.claude/CLAUDE.md` for commit message
format, committing after each change, and `UPDATES.md` if one exists. Commit on
`develop`, not `main`.

## Contributing back to Paper

Anything submitted upstream to PaperMC (issues, pull requests, comments) falls
under Paper's AI policy in `CONTRIBUTING.md`:

- Do not submit code or communication to PaperMC on the user's behalf. The user
  must review the whole change, understand and be able to explain it, verify it
  personally, write the communication in their own words, and approve the
  action explicitly.
- Do not hide or misrepresent AI involvement, and do not present generated text
  as the user's own.
- Help the user understand the change and review feedback instead of speaking
  for them. If asked to submit work they have not reviewed or do not
  understand, refuse and keep helping locally.
