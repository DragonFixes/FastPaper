<div align="center">

<img src="docs/assets/banner.svg" alt="FastPaper" width="100%">

<br>

[![Minecraft](https://img.shields.io/badge/minecraft-26.3-298046?style=for-the-badge&colorA=191920)](https://www.minecraft.net)
[![Java](https://img.shields.io/badge/java-25-ff6a3d?style=for-the-badge&colorA=191920)](https://adoptium.net)
[![Build](https://img.shields.io/github/actions/workflow/status/DragonFixes/FastPaper/build.yaml?branch=master&style=for-the-badge&colorA=191920&label=build)](https://github.com/DragonFixes/FastPaper/actions)
[![Discord](https://img.shields.io/badge/discord-join-5865f2?style=for-the-badge&colorA=191920&logo=discord&logoColor=white)](https://dsc.gg/streamline)
[![Issues](https://img.shields.io/github/issues/DragonFixes/FastPaper?style=for-the-badge&colorA=191920&colorB=ffb347)](https://github.com/DragonFixes/FastPaper/issues)
[![License](https://img.shields.io/badge/license-GPLv3-3a7bd5?style=for-the-badge&colorA=191920)](LICENSE.md)

**FastPaper** is a performance fork of [Paper](https://github.com/PaperMC/Paper) for Minecraft 26.3.<br>
The server does less work to reach the same result.

[Changes](CHANGES.md) · [Discord](https://dsc.gg/streamline) · [Game tests](gametests/README.md) · [Issues](https://github.com/DragonFixes/FastPaper/issues) · [Contributing](CONTRIBUTING.md)

</div>

> [!IMPORTANT]
> FastPaper is **not Paper** and is not made or supported by the PaperMC team.
> Report problems on the [FastPaper issue tracker](https://github.com/DragonFixes/FastPaper/issues), not to Paper.

> [!WARNING]
> Back up your worlds before switching any server to a fork.

## 🚀 Features

- **Gameplay stays the same.** The same entities are found, in the same order, with the same random rolls and side effects.
- **Less work per tick.** Changes skip work whose answer is already known, cache answers until their inputs change, or walk less data.
- **Faster selectors.** `@e[type=...]`, `@e[tag=...]` and `nbt=` checks go straight to the entities that can match.
- **Leaner mob AI.** Brains, sensors and villager POI searches skip lookups that cannot change the outcome.
- **Native region compression.** Chunk, entity and POI files are deflated by libdeflate, in the same zlib format.
- **Every change has an off switch.** Each one is a `-D` flag, so it can be disabled on a live server without a rebuild.
- **Opt-in parallelism.** Parallel entity ticking, async tracking and async pathfinding change tick order, so they are off by default.
- **Built on [Swift](https://github.com/Swift-ality/Paper), [Leaf](https://github.com/Winds-Studio/Leaf) and [Purpur](https://github.com/PurpurMC/Purpur)** patches, with a gameplay-parity test suite behind them.

See **[CHANGES.md](CHANGES.md)** for every change, what it does, its switch, and measured gains, and
[docs/swift-perf-candidates.md](docs/swift-perf-candidates.md) for profiled candidates for future work.

## 🖥️ Server admins

FastPaper runs like a Paper server: start the paperclip jar with `java -jar`. Paper's
[documentation](https://docs.papermc.io) applies to configuration and plugins, except for the
FastPaper-specific switches listed in [CHANGES.md](CHANGES.md).

```sh
java -Xms4G -Xmx4G -jar fastpaper.jar --nogui
```

Turn a change off by passing its switch, for example `-Dswift.selectorIndex=false`.

### ⌨️ Commands

| Command | Permission (default: op) | What it does |
|---|---|---|
| `/tpsbar (targets)` | `bukkit.command.tpsbar`, `bukkit.command.tpsbar.other` for targets | Toggles a boss bar with TPS, MSPT and ping |
| `/rambar (targets)` | `bukkit.command.rambar`, `bukkit.command.rambar.other` for targets | Toggles a boss bar with heap memory use |

Both bars come from [Purpur](https://github.com/PurpurMC/Purpur) (MIT). A player's choice is kept
across relogs and restarts. Titles, colors, fill mode and update interval are set under
`commands.tpsbar` and `commands.rambar` in `config/paper-global.yml`.

## 📦 Plugin developers

FastPaper keeps the Paper API. Plugins written for Paper 26.3 run on it unchanged, and you build
against Paper's own `paper-api` artifact.

<details>
<summary><b>Gradle</b> (Kotlin DSL)</summary>

```kotlin
repositories {
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
```

</details>

<details>
<summary><b>Maven</b></summary>

```xml
<repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
</repository>
```

```xml
<dependency>
    <groupId>io.papermc.paper</groupId>
    <artifactId>paper-api</artifactId>
    <version>[26.3.build,)</version>
    <scope>provided</scope>
</dependency>
```

</details>

## 🔨 Building from source

You need **JDK 25**, **Git** and an internet connection. Clone the repository with Git (a zip
download will not build), then on Windows run:

```bat
scripts\build-deploy.bat
```

This applies the patches, builds the server jar and puts it in `deploy\`. Other helper scripts are
in `scripts\`; see [AGENTS.md](AGENTS.md) for the list.

<details>
<summary>With Gradle directly</summary>

```sh
./gradlew applyPatches
./gradlew createPaperclipJar
```

The jar is written to `paper-server/build/libs`. Run `./gradlew tasks` for the full task list.

</details>

## 🧪 Testing

Gameplay-parity tests boot a real server and run vanilla GameTests against every change:

```sh
./gradlew :paper-server:runGameTests
```

See [gametests/README.md](gametests/README.md) for selectors, shadow-verify runs and parallel modes.

## 🤝 Contributing

Open pull requests against the `master` branch of
[DragonFixes/FastPaper](https://github.com/DragonFixes/FastPaper). The patch workflow is described
in [CONTRIBUTING.md](CONTRIBUTING.md), which comes from Paper and still applies to how patches are
made and rebuilt.

## 💬 Support

Report bugs on the [FastPaper issue tracker](https://github.com/DragonFixes/FastPaper/issues), and ask questions or chat on
[Discord](https://dsc.gg/streamline).

Do not report FastPaper problems to Paper's issue tracker, forums or Discord. If a bug also happens
on an unmodified Paper build, report it to Paper from that build.

## 📜 Credits

FastPaper builds on the work of these projects:

| Project | By | What FastPaper uses |
|---|---|---|
| [Paper](https://github.com/PaperMC/Paper) | the PaperMC team | The server FastPaper is a fork of |
| [Swift](https://github.com/Swift-ality/Paper) | Swift-ality | The performance patches |
| [Leaf](https://github.com/Winds-Studio/Leaf) | Winds Studio | Leaf's 26.3 patches, including the Gale patches they carry |
| [Purpur](https://github.com/PurpurMC/Purpur) | the PurpurMC team | Purpur's server changes and the `/tpsbar` and `/rambar` commands |
| [Modify Player Data](https://github.com/eclipseisoffline/modifyplayerdata) | eclipseisoffline | The idea and behavior behind letting data commands modify players |

Each ported patch's commit message lists its original authors.

## ⚖️ License

FastPaper inherits its licensing from Paper and the projects above. See [LICENSE.md](LICENSE.md).

<div align="center">
<sub>Made faster, not different.</sub>
</div>
