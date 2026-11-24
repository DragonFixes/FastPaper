FastPaper
=========

FastPaper is a performance fork of [Paper](https://github.com/PaperMC/Paper) for Minecraft 26.3.
It is **not Paper** and is not made or supported by the PaperMC team.

Repository: <https://github.com/DragonFixes/FastPaper>

What it changes
------
FastPaper builds on the Swift performance patches by
[Swift-ality](https://github.com/Swift-ality/Paper). The rule for those changes is that gameplay
stays the same: the server does less work to reach the same result, by skipping work whose answer
is already known, caching answers until their inputs change, or walking less data (for example an
index for `@e[type=...]` selectors instead of scanning every entity).

Each change can be switched off at startup with a `-D` flag, so it can be disabled on a live server
without a rebuild. A few changes alter tick order (parallel entity ticking, async pathfinding) and
are off by default.

* [changes.md](changes.md): every change, what it does, its switch, and measured gains
* [docs/swift-perf-candidates.md](docs/swift-perf-candidates.md): profiled candidates for future work
* [gametests/README.md](gametests/README.md): the gameplay-parity test suite

Support
------
Report bugs and ask questions on the
[FastPaper issue tracker](https://github.com/DragonFixes/FastPaper/issues).

Do not report FastPaper problems to Paper's issue tracker, forums or Discord. If a bug also happens
on an unmodified Paper build, report it to Paper from that build.

Server admins
------
FastPaper runs like a Paper server: start the paperclip jar with `java -jar`. Paper's
[documentation](https://docs.papermc.io) applies to configuration and plugins, except for the
FastPaper-specific switches listed in [changes.md](changes.md).

### Commands

| Command | Permission (default: op) | What it does |
|---|---|---|
| `/tpsbar (targets)` | `bukkit.command.tpsbar`, `bukkit.command.tpsbar.other` for targets | Toggles a boss bar with TPS, MSPT and ping |
| `/rambar (targets)` | `bukkit.command.rambar`, `bukkit.command.rambar.other` for targets | Toggles a boss bar with heap memory use |

Both bars come from [Purpur](https://github.com/PurpurMC/Purpur) (MIT). A player's choice is kept
across relogs and restarts. Titles, colors, fill mode and update interval are set under
`commands.tpsbar` and `commands.rambar` in `config/paper-global.yml`.

Plugin developers
------
FastPaper keeps the Paper API. Plugins written for Paper 26.3 run on it unchanged, and you build
against Paper's own `paper-api` artifact:

##### Gradle
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

##### Maven
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

Building from source
------
You need JDK 25, Git and an internet connection. Clone the repository with Git (a zip download
will not build), then on Windows run:

```bat
scripts\build-deploy.bat
```

This applies the patches, builds the server jar and puts it in `deploy\`. Other helper scripts are
in `scripts\`; see [AGENTS.md](AGENTS.md) for the list.

With Gradle directly:

```
./gradlew applyPatches
./gradlew createPaperclipJar
```

The jar is written to `paper-server/build/libs`. Run `./gradlew tasks` for the full task list.

Contributing
------
Open pull requests against the `develop` branch of
[DragonFixes/FastPaper](https://github.com/DragonFixes/FastPaper). The patch workflow is described
in [CONTRIBUTING.md](CONTRIBUTING.md), which comes from Paper and still applies to how patches are
made and rebuilt.

Credits
------
FastPaper is based on [Paper](https://github.com/PaperMC/Paper) by the PaperMC team and on the
Swift patches by [Swift-ality](https://github.com/Swift-ality/Paper). See [LICENSE.md](LICENSE.md)
for licensing.
