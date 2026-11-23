package com.destroystokyo.paper;

import com.destroystokyo.paper.util.VersionFetcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import io.papermc.paper.ServerBuildInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.TextColor.color;
import static io.papermc.paper.ServerBuildInfo.StringRepresentation.VERSION_SIMPLE;

@DefaultQualifier(NonNull.class)
public class PaperVersionFetcher implements VersionFetcher {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final ComponentLogger COMPONENT_LOGGER = ComponentLogger.logger(LogManager.getRootLogger().getName());
    private static final int DISTANCE_ERROR = -1;
    private static final int DISTANCE_UNKNOWN = -2;
    // FastPaper has no build download API; version distance is measured against the
    // GitHub repository, so only builds carrying git branch and commit info can be checked.
    private static final String REPOSITORY = "DragonFixes/FastPaper";
    private static final String DOWNLOAD_PAGE = "https://github.com/" + REPOSITORY;
    private static final ServerBuildInfo BUILD_INFO = ServerBuildInfo.buildInfo();
    private static final String USER_AGENT = BUILD_INFO.brandName() + "/" + BUILD_INFO.asString(VERSION_SIMPLE) + " (" + DOWNLOAD_PAGE + ")";
    private static final Gson GSON = new Gson();

    @Override
    public long getCacheTime() {
        return 720000;
    }

    @Override
    public Component getVersionMessage() {
        final Component updateMessage;
        if (BUILD_INFO.buildNumber().isEmpty() && BUILD_INFO.gitCommit().isEmpty()) {
            updateMessage = text("You are running a development version without access to version information", color(0xFF5300));
        } else {
            updateMessage = getUpdateStatusMessage();
        }
        final @Nullable Component history = this.getHistory();

        return history != null ? Component.textOfChildren(updateMessage, Component.newline(), history) : updateMessage;
    }

    public static void getUpdateStatusStartupMessage() {
        if (BUILD_INFO.buildNumber().isEmpty() && BUILD_INFO.gitCommit().isEmpty()) {
            COMPONENT_LOGGER.warn(text("*** You are running a development version without access to version information ***"));
            return;
        }

        final int distance = fetchDistance();
        switch (distance) {
            case DISTANCE_ERROR -> COMPONENT_LOGGER.error(text("*** Error obtaining version information! Cannot fetch version info ***"));
            case 0 -> { }
            case DISTANCE_UNKNOWN -> COMPONENT_LOGGER.warn(text("*** You are running an unknown version! Cannot fetch version info ***"));
            default -> {
                COMPONENT_LOGGER.info(text("*** Currently you are " + distance + " commit" + (distance == 1 ? "" : "s") + " behind ***"));
                COMPONENT_LOGGER.info(text("*** It is highly recommended to build the latest version from " + DOWNLOAD_PAGE + " ***"));
            }
        }
    }

    private static int fetchDistance() {
        final Optional<String> gitBranch = BUILD_INFO.gitBranch();
        final Optional<String> gitCommit = BUILD_INFO.gitCommit();
        if (gitBranch.isPresent() && gitCommit.isPresent()) {
            return fetchDistanceFromGitHub(gitBranch.get(), gitCommit.get());
        }
        return DISTANCE_UNKNOWN;
    }

    private static Component getUpdateStatusMessage() {
        final int distance = fetchDistance();

        return switch (distance) {
            case DISTANCE_ERROR -> text("Error obtaining version information", NamedTextColor.YELLOW);
            case 0 -> text("You are running the latest version", NamedTextColor.GREEN);
            case DISTANCE_UNKNOWN -> text("Unknown version", NamedTextColor.YELLOW);
            default -> text("You are " + distance + " commit(s) behind", NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(text("Get the new version at: ")
                    .append(text(DOWNLOAD_PAGE, NamedTextColor.GOLD)
                        .hoverEvent(text("Click to open", NamedTextColor.WHITE))
                        .clickEvent(ClickEvent.openUrl(DOWNLOAD_PAGE))));
        };
    }

    // Contributed by Techcable <Techcable@outlook.com> in GH-65
    private static int fetchDistanceFromGitHub(final String branch, final String hash) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) URI.create("https://api.github.com/repos/%s/compare/%s...%s".formatted(PaperVersionFetcher.REPOSITORY, branch, hash)).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", PaperVersionFetcher.USER_AGENT);
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) return DISTANCE_UNKNOWN; // Unknown commit
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                final JsonObject obj = GSON.fromJson(reader, JsonObject.class);
                final String status = obj.get("status").getAsString();
                return switch (status) {
                    case "identical" -> 0;
                    case "behind" -> obj.get("behind_by").getAsInt();
                    default -> DISTANCE_ERROR;
                };
            } catch (final JsonSyntaxException | NumberFormatException e) {
                LOGGER.error("Error parsing json from GitHub's API", e);
                return DISTANCE_ERROR;
            }
        } catch (final IOException e) {
            LOGGER.error("Error while parsing version", e);
            return DISTANCE_ERROR;
        }
    }

    private @Nullable Component getHistory() {
        final VersionHistoryManager.@Nullable VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
        if (data == null) {
            return null;
        }

        final @Nullable String oldVersion = data.getOldVersion();
        if (oldVersion == null) {
            return null;
        }

        return text("Previous version: " + oldVersion, NamedTextColor.GRAY, TextDecoration.ITALIC);
    }
}
