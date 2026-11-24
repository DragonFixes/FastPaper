pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """

        =====================[ ERROR ]=====================
         The FastPaper project directory is not a properly
         cloned Git repository.

         In order to build FastPaper from source you must
         clone the FastPaper repository using Git, not
         download a code zip from GitHub:

         git clone https://github.com/DragonFixes/FastPaper.git

         See https://github.com/DragonFixes/FastPaper
         for further information on building FastPaper.
        ===================================================
    """.trimIndent()
    error(errorText)
}

rootProject.name = "fastpaper"

for (name in listOf("paper-api", "paper-server")) {
    include(name)
    file(name).mkdirs()
}

include("paper-checkstyle")

optionalInclude("test-plugin")
optionalInclude("paper-generator")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val paperVersionChannel = providers.gradleProperty("channel").get().trim()
    val paperBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    // Local builds keep the "<mc>.build.<n>-<channel>" shape of CI builds, with build 0:
    // plugins parse Bukkit.getBukkitVersion() as numeric dot-separated parts and fail on
    // a word such as "local" where a number is expected.
    val versionString = if (paperBuildNumber == null) {
        "$mcVersion.build.0-local"
    } else {
        "$mcVersion.build.$paperBuildNumber-${paperVersionChannel.lowercase()}"
    }
    version = versionString
}

if (providers.gradleProperty("paperBuildCacheEnabled").orNull.toBoolean()) {
    val buildCacheUsername = providers.gradleProperty("paperBuildCacheUsername").orElse("").get()
    val buildCachePassword = providers.gradleProperty("paperBuildCachePassword").orElse("").get()
    if (buildCacheUsername.isBlank() || buildCachePassword.isBlank()) {
        println("The Paper remote build cache is enabled, but no credentials were provided. Remote build cache will not be used.")
    } else {
        val buildCacheUrl = providers.gradleProperty("paperBuildCacheUrl")
            .orElse("https://gradle-build-cache.papermc.io/")
            .get()
        val buildCachePush = providers.gradleProperty("paperBuildCachePush").orNull?.toBoolean()
            ?: System.getProperty("CI").toBoolean()
        buildCache {
            remote<HttpBuildCache> {
                url = uri(buildCacheUrl)
                isPush = buildCachePush
                credentials {
                    username = buildCacheUsername
                    password = buildCachePassword
                }
            }
        }
    }
}
